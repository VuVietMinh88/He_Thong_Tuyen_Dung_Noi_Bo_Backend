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
    // Task 197: requisition statuses that still need their department, so the department cannot be deleted. Every
    // status that is not filled, rejected or cancelled belongs here (RequisitionStatus says so too). Task 257: a
    // requisition waiting for approval, returned for more information or approved is still open. Task 293: a paused
    // requisition is still open; a filled, rejected or cancelled one is closed (its foreign key still blocks the
    // deletion, as DEPARTMENT_IN_USE). No status change leads from a closed status to an open one, so a deletion that
    // saw nothing open stays right (OpenRequisitionStatusChangeTest).
    static final List<String> OPEN_REQUISITION_STATUSES = List.of("DRAFT", "PENDING_APPROVAL", "RETURNED",
            "APPROVED", "PAUSED");
    // The named parameter of MANAGED_DEPARTMENT_IDS: the manager's user id.
    public static final String MANAGER = "managerUserId";
    // Task 363: the one definition of "the departments a user manages": those whose manager_user_id is the user, plus
    // every department below them in the tree (children, grandchildren...). findManagedDepartmentIds runs it, and the
    // candidate module (CandidateAccess) uses it as a constant fragment, "x.department_id IN (" + ... + ")", so a Java
    // check and an SQL filter can never disagree. Every column resolves inside the fragment (departments, child,
    // managed), whatever the aliases of the outer query. The active flag is ignored: it says whether a department is
    // still used, not who is responsible for it. UNION (not UNION ALL) drops rows already found, so the recursion also
    // stops on a cycle left by manual SQL edits.
    public static final String MANAGED_DEPARTMENT_IDS = "WITH RECURSIVE managed(id) AS ("
            + "SELECT id FROM departments WHERE manager_user_id = :" + MANAGER
            + " UNION SELECT child.id FROM departments child JOIN managed ON child.parent_id = managed.id"
            + ") SELECT id FROM managed";

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

    // The departments this user manages directly, plus every department below them in the tree (MANAGED_DEPARTMENT_IDS).
    // Used for REQUISITIONS_*_SCOPED, headcount plans and approver rules; from task 363 the same SQL decides
    // CANDIDATES_*_SCOPED for department managers.
    public Set<UUID> findManagedDepartmentIds(UUID managerUserId) {
        return Set.copyOf(jdbc.query(MANAGED_DEPARTMENT_IDS, new MapSqlParameterSource(MANAGER, managerUserId),
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

    // Task 273: like findActiveForShare, but FOR NO KEY UPDATE, for creating a headcount plan of this department.
    // This lock conflicts with the FOR SHARE that every requisition save of the department holds until it commits, so
    // the plan is only inserted once the saves in flight have committed (and are counted by the plan), and a save that
    // starts later waits for the plan and then finds it. FOR SHARE alone would let a save that found no plan commit
    // after the plan, uncounted by a save that did check it. It does not block foreign key checks (FOR KEY SHARE).
    public Optional<Boolean> findActiveForNoKeyUpdate(UUID id) {
        return jdbc.query("SELECT active FROM departments WHERE id = :id FOR NO KEY UPDATE",
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

    // A department whose manager is the account being handed over (feedback item 10).
    public record ManagedDepartment(UUID departmentId, String code, String name, boolean active) { }

    // The departments this user manages directly, for handing their management over before the account is locked:
    // every active one, and an inactive one that still has open requisitions of its own (OPEN_REQUISITION_STATUSES).
    // Deactivating a department does not close its requisitions, and its manager still reads them in scope and, holding
    // the role, still decides their HIRING_MANAGER and RECRUITER levels (the active flag is ignored there, see
    // findManagedDepartmentIds). An inactive department without open requisitions of its own is not listed: usually
    // nothing is left to hand over, but an inactive parent still gives its manager the scope of the departments below
    // it, which only PUT /departments/{id} moves then. Unlike findManagedDepartmentIds, the departments below them are
    // not listed: each has a manager of its own, and handing a department over also moves the scope of everything
    // below it.
    public List<ManagedDepartment> findManagedBy(UUID userId) {
        return jdbc.query("""
                SELECT d.id, d.code, d.name, d.active FROM departments d
                WHERE d.manager_user_id = :userId
                  AND (d.active OR EXISTS (SELECT 1 FROM recruitment_requisitions r
                                           WHERE r.department_id = d.id AND r.status IN (:statuses)))
                ORDER BY d.code, d.id
                """, new MapSqlParameterSource("userId", userId).addValue("statuses", OPEN_REQUISITION_STATUSES),
                (row, number) -> new ManagedDepartment(row.getObject("id", UUID.class), row.getString("code"),
                        row.getString("name"), row.getBoolean("active")));
    }

    // Feedback item 10: gives the department another manager and changes nothing else, for handing the departments of
    // an account over (DepartmentManagerHandover). The caller holds the tree lock and the row (lockForWrite).
    public void updateManager(UUID id, UUID managerUserId) {
        jdbc.update("UPDATE departments SET manager_user_id = :managerUserId WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("managerUserId", managerUserId));
    }
}
