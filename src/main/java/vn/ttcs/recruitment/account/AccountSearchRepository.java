package vn.ttcs.recruitment.account;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountSearchRepository {
    // An expired invitation is still pending; temporary login locks are separate from disabled accounts.
    private static final String STATUS = """
            CASE WHEN NOT u.enabled THEN
                CASE WHEN EXISTS (SELECT 1 FROM account_activation_tokens t
                                  WHERE t.user_id = u.id AND t.consumed_at IS NULL)
                     THEN 'PENDING_ACTIVATION' ELSE 'DISABLED' END
                 WHEN u.locked_until > :now THEN 'TEMPORARILY_LOCKED'
                 ELSE 'ACTIVE' END
            """;
    private static final String FROM = " FROM user_accounts u LEFT JOIN departments d ON d.id = u.department_id ";
    private static final String SELECT = """
            SELECT u.id, u.email, u.full_name, u.phone, u.display_title, u.department_id,
                   d.name AS department_name, u.created_at,
                   ARRAY(SELECT r.role FROM user_roles r WHERE r.user_id = u.id ORDER BY r.role) AS roles,
            """ + STATUS + " AS status " + FROM;

    private final NamedParameterJdbcTemplate jdbc;

    public AccountSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AccountPage search(String query, Role role, AccountStatus status, UUID departmentId,
                              int page, int size, Instant now) {
        var parameters = new MapSqlParameterSource("now", Timestamp.from(now));
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        if (query != null && !query.isBlank()) {
            // Bound values prevent SQL injection. Escape LIKE wildcards so user input is literal.
            String literal = query.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_");
            parameters.addValue("query", "%" + literal + "%");
            where.append(" AND (lower(u.full_name) LIKE lower(:query) ESCAPE '!'"
                    + " OR lower(u.email) LIKE lower(:query) ESCAPE '!'"
                    + " OR lower(d.name) LIKE lower(:query) ESCAPE '!') ");
        }
        if (role != null) {
            parameters.addValue("role", role.name());
            where.append(" AND EXISTS (SELECT 1 FROM user_roles r WHERE r.user_id = u.id AND r.role = :role) ");
        }
        if (status != null) {
            parameters.addValue("status", status.name());
            where.append(" AND (").append(STATUS).append(") = :status ");
        }
        if (departmentId != null) {
            parameters.addValue("departmentId", departmentId);
            where.append(" AND u.department_id = :departmentId ");
        }
        long total = jdbc.queryForObject("SELECT count(*)" + FROM + where, parameters, Long.class);
        parameters.addValue("size", size).addValue("offset", (long) page * size);
        var items = jdbc.query(SELECT + where + " ORDER BY u.created_at DESC, u.id ASC LIMIT :size OFFSET :offset",
                parameters, (row, number) -> map(row));
        return new AccountPage(items, page, size, total, (total + size - 1) / size);
    }

    public Optional<AccountView> findById(UUID id, Instant now) {
        var parameters = new MapSqlParameterSource("id", id).addValue("now", Timestamp.from(now));
        return jdbc.query(SELECT + " WHERE u.id = :id", parameters, (row, number) -> map(row))
                .stream().findFirst();
    }

    private AccountView map(ResultSet row) throws SQLException {
        var roles = EnumSet.noneOf(Role.class);
        var array = row.getArray("roles");
        try {
            for (String code : (String[]) array.getArray()) {
                roles.add(Role.valueOf(code));
            }
        } finally {
            array.free();
        }
        return new AccountView(row.getObject("id", UUID.class), row.getString("email"),
                row.getString("full_name"), row.getString("phone"), row.getString("display_title"),
                row.getObject("department_id", UUID.class), row.getString("department_name"), roles,
                AccountStatus.valueOf(row.getString("status")), row.getTimestamp("created_at").toInstant());
    }
}
