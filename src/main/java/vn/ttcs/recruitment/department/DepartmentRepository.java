package vn.ttcs.recruitment.department;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class DepartmentRepository {
    private static final long TREE_WRITE_LOCK_KEY = 195196L;
    private static final String FROM = " FROM departments d JOIN user_accounts manager ON manager.id = d.manager_user_id ";
    private static final String SELECT = """
            SELECT d.id, d.code, d.name, d.parent_id, d.manager_user_id,
                   manager.full_name AS manager_full_name, d.active, d.created_at
            """ + FROM;
    private static final String ORDER = " ORDER BY d.code, d.id ";

    private final NamedParameterJdbcTemplate jdbc;

    public DepartmentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DepartmentPage search(String query, Boolean active, int page, int size) {
        var parameters = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        if (query != null && !query.isBlank()) {
            String literal = query.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_");
            parameters.addValue("query", "%" + literal + "%");
            where.append(" AND (lower(d.code) LIKE lower(:query) ESCAPE '!'"
                    + " OR lower(d.name) LIKE lower(:query) ESCAPE '!') ");
        }
        if (active != null) {
            parameters.addValue("active", active);
            where.append(" AND d.active = :active ");
        }
        long total = jdbc.queryForObject("SELECT count(*)" + FROM + where, parameters, Long.class);
        parameters.addValue("size", size).addValue("offset", (long) page * size);
        var items = jdbc.query(SELECT + where + ORDER + " LIMIT :size OFFSET :offset",
                parameters, (row, number) -> map(row));
        return new DepartmentPage(items, page, size, total, (total + size - 1) / size);
    }

    public Optional<DepartmentView> findById(UUID id) {
        return jdbc.query(SELECT + " WHERE d.id = :id", new MapSqlParameterSource("id", id),
                (row, number) -> map(row)).stream().findFirst();
    }

    public List<DepartmentView> findAll() {
        return jdbc.query(SELECT + ORDER, new MapSqlParameterSource(), (row, number) -> map(row));
    }

    public Map<UUID, UUID> findParents() {
        Map<UUID, UUID> parents = new LinkedHashMap<>();
        jdbc.query("SELECT id, parent_id FROM departments", new MapSqlParameterSource(), row -> {
            parents.put(row.getObject("id", UUID.class), row.getObject("parent_id", UUID.class));
        });
        return parents;
    }

    // The departments this user manages directly, plus every department below them in the tree (children,
    // grandchildren...). Used for REQUISITIONS_*_SCOPED. The active flag is ignored: it says whether a department
    // is still used, not who is responsible for it. UNION (not UNION ALL) drops rows already found, so the
    // recursion also stops on a cycle left by manual SQL edits.
    public Set<UUID> findManagedDepartmentIds(UUID managerUserId) {
        return Set.copyOf(jdbc.query("""
                WITH RECURSIVE managed(id) AS (
                    SELECT id FROM departments WHERE manager_user_id = :managerUserId
                    UNION
                    SELECT child.id FROM departments child JOIN managed ON child.parent_id = managed.id
                )
                SELECT id FROM managed
                """, new MapSqlParameterSource("managerUserId", managerUserId),
                (row, number) -> row.getObject("id", UUID.class)));
    }

    public void acquireTreeWriteLock() {
        // All hierarchy writers hold this until commit; concurrent parent changes cannot create a cycle.
        jdbc.query("SELECT pg_advisory_xact_lock(:key)", new MapSqlParameterSource("key", TREE_WRITE_LOCK_KEY),
                (row, number) -> 0);
    }

    public boolean codeExists(String code, UUID excludingId) {
        var parameters = new MapSqlParameterSource("code", code);
        String condition = " WHERE code = :code ";
        if (excludingId != null) {
            parameters.addValue("id", excludingId);
            condition += " AND id <> :id ";
        }
        return jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM departments" + condition + ")",
                parameters, Boolean.class);
    }

    public void insert(UUID id, DepartmentRequest request, Instant createdAt) {
        var parameters = parameters(id, request).addValue("createdAt", Timestamp.from(createdAt));
        jdbc.update("""
                INSERT INTO departments (id,code,name,parent_id,manager_user_id,active,created_at)
                VALUES (:id,:code,:name,:parentId,:managerUserId,:active,:createdAt)
                """, parameters);
    }

    public void update(UUID id, DepartmentRequest request) {
        jdbc.update("""
                UPDATE departments SET code=:code, name=:name, parent_id=:parentId,
                                       manager_user_id=:managerUserId, active=:active
                WHERE id=:id
                """, parameters(id, request));
    }

    private MapSqlParameterSource parameters(UUID id, DepartmentRequest request) {
        return new MapSqlParameterSource("id", id).addValue("code", request.code()).addValue("name", request.name())
                .addValue("parentId", request.parentId()).addValue("managerUserId", request.managerUserId())
                .addValue("active", request.active());
    }

    private DepartmentView map(ResultSet row) throws SQLException {
        return new DepartmentView(row.getObject("id", UUID.class), row.getString("code"), row.getString("name"),
                row.getObject("parent_id", UUID.class), row.getObject("manager_user_id", UUID.class),
                row.getString("manager_full_name"), row.getBoolean("active"), row.getTimestamp("created_at").toInstant());
    }
}
