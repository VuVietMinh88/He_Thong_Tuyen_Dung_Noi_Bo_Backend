package vn.ttcs.recruitment.account.importing;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountInvitationException;
import vn.ttcs.recruitment.account.AccountProvisioningService;
import vn.ttcs.recruitment.account.CreateAccountRequest;
import vn.ttcs.recruitment.account.DuplicateEmailException;
import vn.ttcs.recruitment.account.InvalidDepartmentException;
import vn.ttcs.recruitment.account.NewAccountProfile;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.security.PermissionService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
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

    private final AuthService auth;
    private final PermissionService permissions;
    private final JdbcTemplate jdbc;
    private final StaffImportTemplate template;
    private final StaffImportReader reader;
    private final StaffImportValidator validator;
    private final AccountProvisioningService provisioning;
    private final Clock clock;

    public StaffImportService(AuthService auth, PermissionService permissions, JdbcTemplate jdbc,
                              StaffImportTemplate template, StaffImportReader reader,
                              StaffImportValidator validator, AccountProvisioningService provisioning, Clock clock) {
        this.auth = auth;
        this.permissions = permissions;
        this.jdbc = jdbc;
        this.template = template;
        this.reader = reader;
        this.validator = validator;
        this.provisioning = provisioning;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public byte[] createTemplate(Jwt jwt) {
        requireImportAccess(jwt);
        return template.write(roleOptions());
    }

    // Preview only reads: it creates no account and stores neither the file nor its rows. There is no
    // transaction, so no database connection is held while the workbook is parsed; checking the rows afterwards
    // runs two short read-only queries. Accounts or departments may still change before the real import.
    public StaffImportPreview preview(Jwt jwt, MultipartFile file) {
        requireImportAccess(jwt);
        List<StaffImportRow> rows = reader.read(uploadedXlsx(file));
        return StaffImportPreview.of(validator.check(rows));
    }

    // Creates an account for every row that is valid now and skips the others. The file is read and checked again
    // instead of trusting an earlier preview, because accounts and departments may have changed since.
    // This method deliberately has no transaction. AccountProvisioningService.create then runs every row in its own
    // transaction, so a row that fails rolls back alone and the accounts created before it stay. With @Transactional
    // here, one failing row would undo the whole file.
    public StaffImportResult importStaff(Jwt jwt, MultipartFile file) {
        requireImportAccess(jwt);
        List<StaffImportCheckedRow> rows = validator.check(reader.read(uploadedXlsx(file)));
        List<StaffImportRowResult> results = new ArrayList<>();
        Integer stoppedAtRow = null;
        for (StaffImportCheckedRow row : rows) {
            if (!row.valid()) {
                results.add(StaffImportRowResult.skipped(row));
                continue;
            }
            if (stoppedAtRow != null) {
                results.add(StaffImportRowResult.notAttempted(row));
                continue;
            }
            // A lost session or permission is not caught: create checks them again for every row, and the request
            // then stops with 401/403. Accounts created before that stay.
            try {
                var created = provisioning.create(jwt, accountRequest(row),
                        new NewAccountProfile(row.departmentCode(), row.phone(), row.displayTitle()));
                results.add(StaffImportRowResult.created(row, created.id()));
            } catch (DuplicateEmailException | InvalidDepartmentException exception) {
                // Since the rows were checked, someone created an account with this email or stopped the department.
                results.add(StaffImportRowResult.skipped(row));
            } catch (AccountInvitationException exception) {
                // The invitation was not sent, so this account was rolled back as in POST /accounts.
                results.add(StaffImportRowResult.skipped(row));
                if (!exception.isAddressRefused()) {
                    // The mail server itself is not working, so every next row would fail the same way, and each
                    // try may wait for a timeout. The valid rows after this one are therefore not tried. Importing
                    // the same file again later creates them: accounts already created are skipped then, because
                    // their emails exist. When the server only refused this address, the next rows are tried.
                    stoppedAtRow = row.rowNumber();
                }
            }
        }
        return new StaffImportResult(results, stoppedAtRow);
    }

    // A valid row has an email, a name and only known role codes.
    private static CreateAccountRequest accountRequest(StaffImportCheckedRow row) {
        Set<Role> roles = row.roles().stream().map(Role::valueOf).collect(Collectors.toSet());
        return new CreateAccountRequest(row.email(), row.fullName(), roles);
    }

    // Importing creates accounts, so it needs the same rule as POST /accounts: the ADMIN role and
    // USER_ADMIN_WRITE_ALL. Both are read again from the database instead of trusting the security filter alone.
    private void requireImportAccess(Jwt jwt) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant())) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        Account actor = auth.requireActiveAccount(jwt);
        if (!actor.getRoles().contains(Role.ADMIN)
                || !permissions.forUser(actor.getId()).contains("USER_ADMIN_WRITE_ALL")) {
            throw new AccessDeniedException("Staff import requires ADMIN and USER_ADMIN_WRITE_ALL");
        }
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
