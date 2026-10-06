package vn.ttcs.recruitment.account.importing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class StaffImportService {
    private final AuthService auth;
    private final PermissionService permissions;
    private final JdbcTemplate jdbc;
    private final StaffImportTemplate template;
    private final Clock clock;

    public StaffImportService(AuthService auth, PermissionService permissions, JdbcTemplate jdbc,
                              StaffImportTemplate template, Clock clock) {
        this.auth = auth;
        this.permissions = permissions;
        this.jdbc = jdbc;
        this.template = template;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public byte[] createTemplate(Jwt jwt) {
        requireImportAccess(jwt);
        return template.write(roleOptions());
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
