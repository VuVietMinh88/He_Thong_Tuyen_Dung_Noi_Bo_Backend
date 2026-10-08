package vn.ttcs.recruitment.approval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ApprovalFlowRepository {
    private static final String SELECT = """
            SELECT f.id, f.department_id, v.version, v.name, v.created_by, v.created_at
            FROM approval_flows f JOIN approval_flow_versions v ON v.flow_id=f.id
            """;
    private final JdbcTemplate jdbc;
    public ApprovalFlowRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Boolean> lockDepartment(UUID departmentId) {
        return jdbc.query("SELECT active FROM departments WHERE id=? FOR UPDATE",
                (rs, n) -> rs.getBoolean(1), departmentId).stream().findFirst();
    }

    public boolean activeDepartment(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM departments WHERE id=? AND active)", Boolean.class, id));
    }

    public Optional<UUID> forDepartment(UUID departmentId) {
        return jdbc.query("SELECT id FROM approval_flows WHERE department_id=?", (rs,n) -> rs.getObject(1, UUID.class),
                departmentId).stream().findFirst();
    }

    public Optional<ApprovalFlowView> find(UUID id, Integer version) {
        var summaries = version == null
                ? jdbc.query(SELECT + " WHERE f.id=? AND v.version=f.current_version AND v.sealed", (r,n) -> summary(r), id)
                : jdbc.query(SELECT + " WHERE f.id=? AND v.version=? AND v.sealed", (r,n) -> summary(r), id, version);
        return summaries.stream().findFirst().map(s -> new ApprovalFlowView(s.id(), s.departmentId(), s.version(),
                s.name(), s.createdBy(), s.createdAt(), steps(s.id(), s.version())));
    }

    public ApprovalFlowView.Page list(UUID departmentId, int page, int size) {
        String where = " WHERE v.version=f.current_version AND v.sealed";
        var args = new java.util.ArrayList<Object>();
        if (departmentId != null) { where += " AND f.department_id=?"; args.add(departmentId); }
        long total = jdbc.queryForObject("SELECT count(*) FROM approval_flows f JOIN approval_flow_versions v ON v.flow_id=f.id"
                + where, Long.class, args.toArray());
        args.add(size); args.add((long) page * size);
        var items = jdbc.query(SELECT + where + " ORDER BY f.department_id,f.id LIMIT ? OFFSET ?", (r,n) -> summary(r), args.toArray());
        return new ApprovalFlowView.Page(items, page, size, total, (total + size - 1) / size);
    }

    public boolean eligibleRole(String role) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM roles r JOIN role_permissions p ON p.role_code=r.code
                WHERE r.code=? AND r.internal AND p.permission_code IN ('REQUISITIONS_WRITE_ALL','REQUISITIONS_WRITE_SCOPED'))
                """, Boolean.class, role));
    }

    public void createFlow(UUID id, UUID departmentId) {
        jdbc.update("INSERT INTO approval_flows(id,department_id,current_version) VALUES (?,?,1)", id, departmentId);
    }

    public void publish(UUID id, int version, ApprovalFlowRequest request, UUID actor, Instant now) {
        jdbc.update("INSERT INTO approval_flow_versions(flow_id,version,name,created_by,created_at) VALUES (?,?,?,?,?)",
                id, version, request.name(), actor, Timestamp.from(now));
        for (var step : request.steps()) {
            jdbc.update("""
                    INSERT INTO approval_flow_steps(flow_id,version,position,salary_threshold,approver_user_id,approver_role)
                    VALUES (?,?,?,?,?,?)
                    """, id, version, step.position(), step.salaryThreshold(), step.approverUserId(), step.approverRole());
        }
        jdbc.update("UPDATE approval_flow_versions SET sealed=true WHERE flow_id=? AND version=?", id, version);
        if (version > 1) jdbc.update("UPDATE approval_flows SET current_version=? WHERE id=?", version, id);
    }

    private List<ApprovalFlowRequest.Step> steps(UUID id, int version) {
        return jdbc.query("SELECT * FROM approval_flow_steps WHERE flow_id=? AND version=? ORDER BY position",
                (r,n) -> new ApprovalFlowRequest.Step(r.getInt("position"), r.getBigDecimal("salary_threshold"),
                        r.getObject("approver_user_id", UUID.class), r.getString("approver_role")), id, version);
    }
    private ApprovalFlowView.Summary summary(ResultSet r) throws SQLException {
        return new ApprovalFlowView.Summary(r.getObject("id", UUID.class), r.getObject("department_id", UUID.class),
                r.getInt("version"), r.getString("name"), r.getObject("created_by", UUID.class), r.getTimestamp("created_at").toInstant());
    }
}
