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
    // Task 197: requisition statuses that still need their department, so the department cannot be deleted.
    // V13 only allows DRAFT, and a draft is still open. When the approval workflow adds statuses, list here every
    // status that is not closed or cancelled (RequisitionStatus says so too).
    private static final List<String> OPEN_REQUISITION_STATUSES = List.of("DRAFT");

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

    // Task 246: is this department still used (active)? Empty when it does not exist. FOR SHARE keeps the row
    // locked until the caller's transaction ends, so a PUT that deactivates the department waits for the caller's
    // commit; other FOR SHARE readers (account assignment, requisitions) still run in parallel. Must be called in a
    // read-write transaction (PostgreSQL refuses FOR SHARE in a read-only one).
    public Optional<Boolean> findActiveForShare(UUID id) {
        return jdbc.query("SELECT active FROM departments WHERE id = :id FOR SHARE",
                new MapSqlParameterSource("id", id), (row, number) -> row.getBoolean("active")).stream().findFirst();
    }

    // Task 197 (DELETE), task 198 (also PUT): locks the department row until the write commits; false when the
    // department does not exist. Requisition writes and account assignments read this row FOR SHARE while they save,
    // so the write waits for them, and each later statement (READ COMMITTED) sees what they committed. A save that
    // starts after this lock waits for the write. FOR UPDATE is the strongest row lock, so once it is held the UPDATE
    // or DELETE statement does not wait for this row again (DepartmentService checks the caller right after the lock).
    public boolean lockForWrite(UUID id) {
        return !jdbc.query("SELECT id FROM departments WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id), (row, number) -> row.getObject("id", UUID.class)).isEmpty();
    }

    // Only requisitions of this department itself; a requisition of a child department belongs to that child.
    public boolean hasOpenRequisitions(UUID id) {
        return exists("SELECT 1 FROM recruitment_requisitions WHERE department_id = :id AND status IN (:statuses)",
                new MapSqlParameterSource("id", id).addValue("statuses", OPEN_REQUISITION_STATUSES));
    }

    public boolean hasChildren(UUID id) {
        return exists("SELECT 1 FROM departments WHERE parent_id = :id", new MapSqlParameterSource("id", id));
    }

    // Every account counts, also disabled or locked ones: user_accounts.department_id still points here.
    public boolean hasMembers(UUID id) {
        return exists("SELECT 1 FROM user_accounts WHERE department_id = :id", new MapSqlParameterSource("id", id));
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM departments WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    private boolean exists(String query, MapSqlParameterSource parameters) {
        return jdbc.queryForObject("SELECT EXISTS (" + query + ")", parameters, Boolean.class);
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
