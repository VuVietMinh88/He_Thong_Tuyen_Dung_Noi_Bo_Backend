package vn.ttcs.recruitment.account.importing;

import jakarta.validation.Validator;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.account.CreateAccountRequest;
import vn.ttcs.recruitment.account.ProfileValidation;
import vn.ttcs.recruitment.account.Role;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
 * file at once. Each column gets at most one error: the first rule it breaks. Checking only reads the database.
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

    private final NamedParameterJdbcTemplate jdbc;
    private final Validator validator;

    public StaffImportValidator(NamedParameterJdbcTemplate jdbc, Validator validator) {
        this.jdbc = jdbc;
        this.validator = validator;
    }

    public List<StaffImportCheckedRow> check(List<StaffImportRow> rows) {
        Map<String, List<Integer>> rowNumbersByEmail = new HashMap<>();
        for (StaffImportRow row : rows) {
            if (row.email() != null) {
                rowNumbersByEmail.computeIfAbsent(row.email(), email -> new ArrayList<>()).add(row.rowNumber());
            }
        }
        // Two queries for the whole file instead of two per row.
        Set<String> usedEmails = usedEmails(rowNumbersByEmail.keySet());
        Map<String, Boolean> activeByDepartmentCode = activeByDepartmentCode(rows);

        List<StaffImportCheckedRow> checked = new ArrayList<>();
        for (StaffImportRow row : rows) {
            // One check per column, in column order. A check returns null when its cell is fine.
            List<StaffImportRowError> errors = Stream.of(
                            checkEmail(row, rowNumbersByEmail, usedEmails),
                            checkText(row, FULL_NAME, row.fullName()),
                            checkRoles(row),
                            checkDepartment(row, activeByDepartmentCode),
                            checkPhone(row),
                            checkText(row, DISPLAY_TITLE, row.displayTitle()))
                    .filter(Objects::nonNull)
                    .toList();
            checked.add(StaffImportCheckedRow.of(row, errors));
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
        List<Integer> otherRows = rowNumbersByEmail.get(email).stream()
                .filter(number -> number != row.rowNumber())
                .toList();
        if (!otherRows.isEmpty()) {
            String numbers = otherRows.stream().map(String::valueOf).collect(Collectors.joining(", "));
            return error(row, EMAIL, "EMAIL_DUPLICATED_IN_FILE",
                    "Email này cũng có ở dòng " + numbers + " của tệp. Mỗi email chỉ dùng cho một người.");
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

    private static StaffImportRowError checkPhone(StaffImportRow row) {
        if (row.phone() == null || PHONE_PATTERN.matcher(row.phone()).matches()) {
            return null;
        }
        return error(row, PHONE, "PHONE_INVALID", "Số điện thoại không đúng định dạng: cần số di động 10 chữ số "
                + "bắt đầu bằng 03, 05, 07, 08, 09 hoặc số cố định 11 chữ số bắt đầu bằng 02. Nếu Excel làm mất "
                + "số 0 ở đầu, hãy định dạng ô là Text rồi gõ lại.");
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
