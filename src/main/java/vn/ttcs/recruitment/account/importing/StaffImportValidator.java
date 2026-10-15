package vn.ttcs.recruitment.account.importing;

import jakarta.validation.Validator;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.account.CreateAccountRequest;
import vn.ttcs.recruitment.account.PhoneAlreadyUsedException;
import vn.ttcs.recruitment.account.ProfileValidation;
import vn.ttcs.recruitment.account.Role;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static vn.ttcs.recruitment.account.importing.StaffImportColumn.DEPARTMENT_CODE;
import static vn.ttcs.recruitment.account.importing.StaffImportColumn.DISPLAY_TITLE;
import static vn.ttcs.recruitment.account.importing.StaffImportColumn.EMAIL;
import static vn.ttcs.recruitment.account.importing.StaffImportColumn.FULL_NAME;
import static vn.ttcs.recruitment.account.importing.StaffImportColumn.PHONE;
import static vn.ttcs.recruitment.account.importing.StaffImportColumn.ROLES;

/**
 * Checks the rows read from a staff import file with the rules of creating one account (POST /accounts) and
 * editing its profile (PUT /accounts/{id}). Every row is checked, so the administrator sees all problems of the
 * file at once. Each column gets at most one error: the first rule it breaks. A name that another account or row
 * already has is only a warning, because two people may have the same name; warnings never make a row invalid.
 * Checking only reads the database.
 */
@Component
public class StaffImportValidator {
    // Same limits as CreateAccountRequest, AccountUpdateRequest and DepartmentRequest, which are also the sizes of
    // the database columns. Roles and phone need no length limit: only known codes and the phone pattern pass.
    private static final Map<StaffImportColumn, Integer> MAX_LENGTHS = Map.of(
            EMAIL, 254,
            FULL_NAME, 255,
            DEPARTMENT_CODE, 50,
            DISPLAY_TITLE, 120);
    private static final Pattern PHONE_PATTERN = Pattern.compile(ProfileValidation.VIETNAM_PHONE_PATTERN);
    private static final List<String> ROLE_CODES = Arrays.stream(Role.values()).map(Role::name).toList();
    // A run of the Unicode White_Space characters, the same ones as \p{IsWhite_Space} in the searches of
    // InterviewQuestionService and PublicJobPostingQuery: tab to carriage return, the space, U+0085, the no-break
    // spaces U+00A0, U+2007, U+202F and the other Unicode spaces and separators. They are written out because the
    // same pattern also collapses the stored names in PostgreSQL (existingNames), which reads it the same way.
    // PostgreSQL's [[:space:]] would not do: it follows the database locale, and glibc's (the postgres:16 image)
    // leaves out U+0085 and the no-break spaces, while Windows' adds U+180E, which Unicode no longer counts as a space.
    private static final String SPACE_RUN = "[\\u0009-\\u000D\\u0020\\u0085\\u00A0\\u1680\\u2000-\\u200A"
            + "\\u2028\\u2029\\u202F\\u205F\\u3000]+";
    private static final Pattern SPACES = Pattern.compile(SPACE_RUN);

    private final NamedParameterJdbcTemplate jdbc;
    private final Validator validator;

    public StaffImportValidator(NamedParameterJdbcTemplate jdbc, Validator validator) {
        this.jdbc = jdbc;
        this.validator = validator;
    }

    public List<StaffImportCheckedRow> check(List<StaffImportRow> rows) {
        Map<String, List<Integer>> rowNumbersByEmail = new HashMap<>();
        Map<String, List<Integer>> rowNumbersByPhone = new HashMap<>();
        Map<String, List<Integer>> rowNumbersByNameKey = new HashMap<>();
        Set<String> names = new HashSet<>();
        for (StaffImportRow row : rows) {
            if (row.email() != null) {
                rowNumbersByEmail.computeIfAbsent(row.email(), email -> new ArrayList<>()).add(row.rowNumber());
            }
            if (isPhone(row.phone())) {
                rowNumbersByPhone.computeIfAbsent(row.phone(), phone -> new ArrayList<>()).add(row.rowNumber());
            }
            String name = comparableName(row);
            if (name != null) {
                names.add(name);
                rowNumbersByNameKey.computeIfAbsent(nameKey(name), key -> new ArrayList<>()).add(row.rowNumber());
            }
        }
        // Four queries for the whole file instead of four per row; each is skipped when it has nothing to look for.
        Set<String> usedEmails = usedEmails(rowNumbersByEmail.keySet());
        Set<String> usedPhones = usedPhones(rowNumbersByPhone.keySet());
        Set<String> existingNames = existingNames(names);
        Map<String, Boolean> activeByDepartmentCode = activeByDepartmentCode(rows);

        List<StaffImportCheckedRow> checked = new ArrayList<>();
        for (StaffImportRow row : rows) {
            // One check per column, in column order. A check returns null when its cell is fine.
            List<StaffImportRowError> errors = Stream.of(
                            checkEmail(row, rowNumbersByEmail, usedEmails),
                            checkText(row, FULL_NAME, row.fullName()),
                            checkRoles(row),
                            checkDepartment(row, activeByDepartmentCode),
                            checkPhone(row, rowNumbersByPhone, usedPhones),
                            checkText(row, DISPLAY_TITLE, row.displayTitle()))
                    .filter(Objects::nonNull)
                    .toList();
            // Warnings are found the same way, but they never change whether the row is valid.
            List<StaffImportRowError> warnings = Stream.of(checkNameRepeated(row, rowNumbersByNameKey, existingNames))
                    .filter(Objects::nonNull)
                    .toList();
            checked.add(StaffImportCheckedRow.of(row, errors, warnings));
        }
        return checked;
    }

    // The required and length rules that every text column shares.
    private static StaffImportRowError checkText(StaffImportRow row, StaffImportColumn column, String value) {
        if (value == null) {
            return column.required() ? required(row, column) : null;
        }
        Integer maxLength = MAX_LENGTHS.get(column);
        if (maxLength != null && value.length() > maxLength) {
            return error(row, column, "TOO_LONG", column.header() + " tối đa " + maxLength + " ký tự, ô này có "
                    + value.length() + " ký tự.");
        }
        return null;
    }

    private StaffImportRowError checkEmail(StaffImportRow row, Map<String, List<Integer>> rowNumbersByEmail,
                                           Set<String> usedEmails) {
        StaffImportRowError textError = checkText(row, EMAIL, row.email());
        if (textError != null) {
            return textError;
        }
        String email = row.email();
        // The @Email rule of CreateAccountRequest itself, so the import accepts exactly what POST /accounts accepts.
        // Required and length were checked above, so only the format can fail here.
        if (!validator.validateValue(CreateAccountRequest.class, "email", email).isEmpty()) {
            return error(row, EMAIL, "EMAIL_INVALID",
                    "Email không đúng định dạng, ví dụ đúng: " + EMAIL.example() + ".");
        }
        if (usedEmails.contains(email)) {
            return error(row, EMAIL, "EMAIL_ALREADY_EXISTS", "Email đã được sử dụng cho một tài khoản nội bộ.");
        }
        // Every row of a repeated email is marked, so the administrator decides which person the email belongs to.
        String otherRows = otherRows(row, rowNumbersByEmail.get(email));
        if (otherRows != null) {
            return error(row, EMAIL, "EMAIL_DUPLICATED_IN_FILE",
                    "Email này cũng có ở dòng " + otherRows + " của tệp. Mỗi email chỉ dùng cho một người.");
        }
        return null;
    }

    // A warning, not an error: two people may have the same name, so the administrator only checks that the row is
    // not someone who already has an account, or one person entered twice. A name with its own error (required, too
    // long) gets no warning. As for the email, a name another account has is reported instead of the repeat.
    private static StaffImportRowError checkNameRepeated(StaffImportRow row,
                                                         Map<String, List<Integer>> rowNumbersByNameKey,
                                                         Set<String> existingNames) {
        String name = comparableName(row);
        if (name == null) {
            return null;
        }
        if (existingNames.contains(name)) {
            return error(row, FULL_NAME, "FULL_NAME_ALREADY_EXISTS",
                    "Đã có tài khoản nội bộ mang họ tên này. Hãy kiểm tra để không tạo trùng một người.");
        }
        String otherRows = otherRows(row, rowNumbersByNameKey.get(nameKey(name)));
        if (otherRows != null) {
            return error(row, FULL_NAME, "FULL_NAME_DUPLICATED_IN_FILE", "Họ tên này cũng có ở dòng " + otherRows
                    + " của tệp. Hãy kiểm tra đó có phải cùng một người không.");
        }
        return null;
    }

    private static StaffImportRowError checkRoles(StaffImportRow row) {
        if (row.roles().isEmpty()) {
            return required(row, ROLES);
        }
        List<String> unknown = row.roles().stream().filter(code -> !ROLE_CODES.contains(code)).toList();
        if (!unknown.isEmpty()) {
            return error(row, ROLES, "ROLE_UNKNOWN", "Mã vai trò không hợp lệ: " + String.join(", ", unknown)
                    + ". Chỉ dùng các mã: " + String.join(", ", ROLE_CODES) + ".");
        }
        return null;
    }

    private static StaffImportRowError checkDepartment(StaffImportRow row, Map<String, Boolean> activeByCode) {
        String code = row.departmentCode();
        StaffImportRowError textError = checkText(row, DEPARTMENT_CODE, code);
        if (textError != null || code == null) {
            return textError;
        }
        Boolean active = activeByCode.get(code);
        if (active == null) {
            return error(row, DEPARTMENT_CODE, "DEPARTMENT_NOT_FOUND", "Không có phòng ban mã \"" + code
                    + "\". Mã phân biệt chữ hoa/thường, hãy ghi đúng như danh mục phòng ban.");
        }
        // Same rule as PUT /accounts/{id}: nobody new is assigned to a department that is no longer used.
        if (!active) {
            return error(row, DEPARTMENT_CODE, "DEPARTMENT_INACTIVE",
                    "Phòng ban mã \"" + code + "\" đã ngừng áp dụng, không gán được nhân sự mới.");
        }
        return null;
    }

    // Same rule as PUT /profile and PUT /accounts/{id}: a number belongs to at most one account (V38). The reader
    // already turned +84 into 0, so 0912345678 and +84912345678 are the same number here, as in the database.
    private static StaffImportRowError checkPhone(StaffImportRow row, Map<String, List<Integer>> rowNumbersByPhone,
                                                  Set<String> usedPhones) {
        String phone = row.phone();
        if (phone == null) {
            return null;
        }
        if (!isPhone(phone)) {
            return error(row, PHONE, "PHONE_INVALID", "Số điện thoại không đúng định dạng: cần số di động 10 chữ "
                    + "số bắt đầu bằng 03, 05, 07, 08, 09 hoặc số cố định 11 chữ số bắt đầu bằng 02. Nếu Excel làm "
                    + "mất số 0 ở đầu, hãy định dạng ô là Text rồi gõ lại.");
        }
        // Like the 409 of those APIs, the message never says which account has the number.
        if (usedPhones.contains(phone)) {
            return error(row, PHONE, PhoneAlreadyUsedException.CODE,
                    "Số điện thoại đã được dùng cho một tài khoản khác.");
        }
        // Every row of a repeated number is marked, as for the email.
        String otherRows = otherRows(row, rowNumbersByPhone.get(phone));
        if (otherRows != null) {
            return error(row, PHONE, "PHONE_DUPLICATED_IN_FILE", "Số điện thoại này cũng có ở dòng " + otherRows
                    + " của tệp. Mỗi số điện thoại chỉ dùng cho một người.");
        }
        return null;
    }

    private static boolean isPhone(String phone) {
        return phone != null && PHONE_PATTERN.matcher(phone).matches();
    }

    // The name as it is compared, or null when the name cell has its own error and so gets no warning. As in the
    // searches of InterviewQuestionService and PublicJobPostingQuery: Unicode NFC, so a Vietnamese letter typed as a
    // base letter plus combining marks is the same letter; each run of SPACE_RUN (spaces, tabs, line breaks, no-break
    // spaces) becomes one space and the spaces at both ends are removed. Upper/lower case is ignored by nameKey here
    // and by lower() in SQL, but the accents always count: "Nguyen Van An" is another name than "Nguyễn Văn An", and
    // so is the other place of the tone mark ("Hoà" and "Hòa"). A name made only of invisible spaces compares as
    // nothing.
    private static String comparableName(StaffImportRow row) {
        if (row.fullName() == null || checkText(row, FULL_NAME, row.fullName()) != null) {
            return null;
        }
        String name = SPACES.matcher(Normalizer.normalize(row.fullName(), Normalizer.Form.NFC)).replaceAll(" ");
        // Only the space a run became is removed at each end, as btrim() does in existingNames. strip() would also
        // remove the control characters U+001C to U+001F, which are no spaces there.
        int start = name.startsWith(" ") ? 1 : 0;
        int end = name.endsWith(" ") ? name.length() - 1 : name.length();
        return start < end ? name.substring(start, end) : null;
    }

    // Two names of the file are the same name when their keys are equal.
    private static String nameKey(String comparableName) {
        return comparableName.toLowerCase(Locale.ROOT);
    }

    // "4, 6": the other rows of the file with the same value as this row, or null when no other row has it.
    private static String otherRows(StaffImportRow row, List<Integer> rowNumbersWithTheValue) {
        List<Integer> others = rowNumbersWithTheValue.stream().filter(number -> number != row.rowNumber()).toList();
        return others.isEmpty() ? null : others.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    // Emails of the file that already belong to an account, whatever its status. Emails longer than the database
    // column cannot match anything, so they are not sent.
    private Set<String> usedEmails(Set<String> emails) {
        List<String> candidates = emails.stream().filter(email -> email.length() <= MAX_LENGTHS.get(EMAIL)).toList();
        if (candidates.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("SELECT email FROM user_accounts WHERE email IN (:emails)",
                new MapSqlParameterSource("emails", candidates), String.class));
    }

    // Phones of the file that already belong to an account, whatever its status. Only well-formed numbers are sent;
    // every write path stores the number normalized the same way, so an exact match finds equal numbers.
    private Set<String> usedPhones(Set<String> phones) {
        if (phones.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("SELECT phone FROM user_accounts WHERE phone IN (:phones)",
                new MapSqlParameterSource("phones", List.copyOf(phones)), String.class));
    }

    // The names of the file, exactly as sent, that an account already has, whatever its status. Stored names are not
    // NFC (the account APIs only trim them), so they are put in NFC with each run of SPACE_RUN as one space and none
    // at both ends, like the sent names; normalize() needs a UTF8 database (zonky initdb -E UTF-8; the postgres:16
    // default), as in JobPostingReadRepository. lower() on both sides ignores upper/lower case, Vietnamese letters
    // included, but not the accents. PostgreSQL cannot take U+0000 in a text parameter and no stored name has it,
    // so such a name is not sent. full_name has no index: every account name is compared, in one query per file.
    private Set<String> existingNames(Set<String> names) {
        List<String> candidates = names.stream().filter(name -> name.indexOf('\0') < 0).toList();
        if (candidates.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("""
                SELECT n.name
                  FROM unnest(CAST(ARRAY[:names] AS text[])) AS n(name)
                 WHERE EXISTS (
                       SELECT 1 FROM user_accounts u
                        WHERE lower(btrim(regexp_replace(normalize(u.full_name, NFC), :spaces, ' ', 'g')))
                              = lower(n.name))
                """, new MapSqlParameterSource("names", candidates).addValue("spaces", SPACE_RUN), String.class));
    }

    // Whether each department code of the file exists, and if so whether it is active. Codes are compared
    // exactly, upper/lower case included, like the unique code of the department catalog.
    private Map<String, Boolean> activeByDepartmentCode(List<StaffImportRow> rows) {
        List<String> codes = rows.stream().map(StaffImportRow::departmentCode)
                .filter(code -> code != null && code.length() <= MAX_LENGTHS.get(DEPARTMENT_CODE))
                .distinct()
                .toList();
        if (codes.isEmpty()) {
            return Map.of();
        }
        Map<String, Boolean> activeByCode = new HashMap<>();
        jdbc.query("SELECT code, active FROM departments WHERE code IN (:codes)",
                new MapSqlParameterSource("codes", codes),
                result -> {
                    activeByCode.put(result.getString("code"), result.getBoolean("active"));
                });
        return activeByCode;
    }

    private static StaffImportRowError required(StaffImportRow row, StaffImportColumn column) {
        return error(row, column, "REQUIRED", column.header() + " là bắt buộc.");
    }

    private static StaffImportRowError error(StaffImportRow row, StaffImportColumn column, String code,
                                             String message) {
        return StaffImportRowError.of(row.rowNumber(), column, code, message);
    }
}
