package vn.ttcs.recruitment.account.importing;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import vn.ttcs.recruitment.account.AccountInvitationException;
import vn.ttcs.recruitment.account.AccountProvisioningService;
import vn.ttcs.recruitment.account.CreateAccountRequest;
import vn.ttcs.recruitment.account.DuplicateEmailException;
import vn.ttcs.recruitment.account.InvalidDepartmentException;
import vn.ttcs.recruitment.account.NewAccountProfile;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.account.importing.StaffImportReport.CreatedRow;
import vn.ttcs.recruitment.account.importing.StaffImportReport.SkippedRow;
import vn.ttcs.recruitment.common.ApiException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class StaffImportService {
    private static final long MAX_FILE_SIZE_BYTES = StaffImportTemplate.MAX_FILE_SIZE_MB * 1024L * 1024L;

    private final JdbcTemplate jdbc;
    private final StaffImportTemplate template;
    private final StaffImportReader reader;
    private final StaffImportValidator validator;
    private final AccountProvisioningService provisioning;

    public StaffImportService(JdbcTemplate jdbc, StaffImportTemplate template, StaffImportReader reader,
                              StaffImportValidator validator, AccountProvisioningService provisioning) {
        this.jdbc = jdbc;
        this.template = template;
        this.reader = reader;
        this.validator = validator;
        this.provisioning = provisioning;
    }

    // No transaction here, as in preview: the access check locks rows in its own short transaction, which commits
    // at once, and the role names are then read with one query.
    public byte[] createTemplate(Jwt jwt) {
        requireImportAccess(jwt);
        return template.write(roleOptions());
    }

    // Preview only reads: it creates no account and stores neither the file nor its rows. There is no
    // transaction, so no database connection is held while the workbook is parsed: the access check before it
    // commits at once, and checking the rows afterwards runs two short read-only queries. Accounts or departments
    // may still change before the real import.
    public StaffImportPreview preview(Jwt jwt, MultipartFile file) {
        requireImportAccess(jwt);
        List<StaffImportRow> rows = reader.read(uploadedXlsx(file));
        return StaffImportPreview.of(validator.check(rows));
    }

    // Creates an account for every row that is valid now and skips the others, then reports both with the reason of
    // every skipped row. The file is read and checked again instead of trusting an earlier preview, because
    // accounts and departments may have changed since.
    // This method deliberately has no transaction. AccountProvisioningService.create then runs every row in its own
    // transaction, so a row that fails rolls back alone and the accounts created before it stay. With @Transactional
    // here, one failing row would undo the whole file.
    public StaffImportReport importStaff(Jwt jwt, MultipartFile file) {
        requireImportAccess(jwt);
        List<StaffImportCheckedRow> rows = validator.check(reader.read(uploadedXlsx(file)));
        // Rows are handled in file order, so both lists of the report are in file order too.
        List<CreatedRow> created = new ArrayList<>();
        List<SkippedRow> skipped = new ArrayList<>();
        Integer stoppedAtRow = null;
        for (StaffImportCheckedRow row : rows) {
            if (!row.valid()) {
                // The same cell errors the preview shows for this row.
                skipped.add(SkippedRow.of(row, row.errors()));
                continue;
            }
            if (stoppedAtRow != null) {
                skipped.add(SkippedRow.of(row, StaffImportRowError.notAttempted(row.rowNumber(), stoppedAtRow)));
                continue;
            }
            // A lost session or permission is not caught: create checks them again for every row, and the request
            // then stops with 401/403. Accounts created before that stay.
            try {
                var account = provisioning.create(jwt, accountRequest(row),
                        new NewAccountProfile(row.departmentCode(), row.phone(), row.displayTitle()));
                created.add(new CreatedRow(row.rowNumber(), row.email(), account.id()));
            } catch (DuplicateEmailException exception) {
                // Since the rows were checked, someone created an account with this email.
                skipped.add(SkippedRow.of(row, StaffImportRowError.emailTaken(row.rowNumber())));
            } catch (InvalidDepartmentException exception) {
                // Since the rows were checked, someone stopped the department or changed its code.
                skipped.add(SkippedRow.of(row,
                        StaffImportRowError.departmentUnavailable(row.rowNumber(), row.departmentCode())));
            } catch (AccountInvitationException exception) {
                // The invitation was not sent, so this account was rolled back as in POST /accounts.
                if (exception.isAddressRefused()) {
                    // Only this address is the problem; the mail server still works, so the next rows are tried.
                    skipped.add(SkippedRow.of(row, StaffImportRowError.addressRefused(row.rowNumber())));
                } else {
                    // The mail server itself is not working, so every next row would fail the same way, and each
                    // try may wait for a timeout. The valid rows after this one are therefore not tried. Importing
                    // the same file again later creates them: accounts already created are skipped then, because
                    // their emails exist.
                    skipped.add(SkippedRow.of(row, StaffImportRowError.mailServerUnavailable(row.rowNumber())));
                    stoppedAtRow = row.rowNumber();
                }
            }
        }
        return StaffImportReport.of(created, skipped, stoppedAtRow);
    }

    // A valid row has an email, a name and only known role codes.
    private static CreateAccountRequest accountRequest(StaffImportCheckedRow row) {
        Set<Role> roles = row.roles().stream().map(Role::valueOf).collect(Collectors.toSet());
        return new CreateAccountRequest(row.email(), row.fullName(), roles);
    }

    // Importing creates accounts, so all three import APIs need exactly the rule of POST /accounts: the ADMIN role
    // and USER_ADMIN_WRITE_ALL. SecurityConfiguration checks them first; this checks them again in the service that
    // creates the accounts, with the caller's account and session locked, so a role or permission removed while
    // this request waited is seen. It runs before the file is opened: a refused caller learns nothing from it.
    private void requireImportAccess(Jwt jwt) {
        provisioning.requireCreateAccess(jwt);
    }

    // Checks what can be seen without opening the file: it is present, small enough and named .xlsx.
    private static byte[] uploadedXlsx(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IMPORT_FILE_REQUIRED",
                    "Vui lòng chọn tệp Excel (.xlsx) cần nhập trong trường file.");
        }
        // The multipart settings in application.properties already stop larger uploads; this keeps the
        // promised 2 MB limit even if those settings are raised for another upload API later.
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE",
                    "Tệp vượt quá dung lượng tối đa " + StaffImportTemplate.MAX_FILE_SIZE_MB + " MB.");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.trim().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IMPORT_FILE_INVALID",
                    "Chỉ nhận tệp Excel .xlsx. Hãy lưu tệp dạng .xlsx rồi tải lên lại.");
        }
        try {
            return file.getBytes();
        } catch (IOException exception) {
            // The upload is already held by the server, so failing to read it is a server error.
            throw new UncheckedIOException(exception);
        }
    }

    // Codes follow the Role enum, which is what account creation accepts. Names come from the V3 role catalog,
    // so the template shows the same Vietnamese names as the rest of the system.
    private List<StaffImportTemplate.RoleOption> roleOptions() {
        Map<String, String> names = jdbc.query("SELECT code, display_name FROM roles WHERE internal",
                        (row, number) -> Map.entry(row.getString("code"), row.getString("display_name")))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return Arrays.stream(Role.values())
                .map(role -> new StaffImportTemplate.RoleOption(role.name(), names.getOrDefault(role.name(), role.name())))
                .toList();
    }
}
