package vn.ttcs.recruitment.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

@Service
public class PermissionService {
    private final JdbcTemplate jdbc;

    public PermissionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> forUser(UUID userId) {
        // Resolve on every authenticated request so role/grant changes take effect immediately.
        return Set.copyOf(jdbc.queryForList("""
                SELECT DISTINCT rp.permission_code
                FROM user_roles ur
                JOIN role_permissions rp ON rp.role_code = ur.role
                WHERE ur.user_id = ?
                """, String.class, userId));
    }
}
