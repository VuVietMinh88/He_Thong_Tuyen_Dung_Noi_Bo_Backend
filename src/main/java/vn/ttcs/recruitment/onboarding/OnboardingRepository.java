package vn.ttcs.recruitment.onboarding;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** All methods join the service transaction; parent locks protect replacement and aggregate versions. */
@Repository
class OnboardingRepository {
    private final JdbcTemplate jdbc;

    OnboardingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    OnboardingTemplateView.Page templates(UUID departmentId, int page, int size) {
        String where = departmentId == null ? "" : " WHERE department_id=?";
        Object[] countArgs = departmentId == null ? new Object[0] : new Object[]{departmentId};
        Object[] pageArgs = departmentId == null ? new Object[]{size, (long) page * size}
                : new Object[]{departmentId, size, (long) page * size};
        long total = jdbc.queryForObject("SELECT count(*) FROM onboarding_templates" + where, Long.class, countArgs);
        var rows = jdbc.query("SELECT * FROM onboarding_templates" + where + " ORDER BY name,id LIMIT ? OFFSET ?",
                this::template, pageArgs);
        return new OnboardingTemplateView.Page(rows, page, size, total, (total + size - 1) / size);
    }

    Optional<OnboardingTemplateView> template(UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM onboarding_templates WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                this::template, id).stream().findFirst();
    }

    void createTemplate(UUID id, OnboardingRequests.CreateTemplate request, UUID actor, Instant at) {
        jdbc.update("""
                INSERT INTO onboarding_templates(id,department_id,name,active,version,created_by,created_at,updated_at)
                VALUES (?,?,?,?,0,?,?,?)
                """, id, request.departmentId(), request.name(), request.active(), actor, time(at), time(at));
        replaceTemplateItems(id, request.items());
    }

    void updateTemplate(UUID id, OnboardingRequests.UpdateTemplate request, Instant at) {
        jdbc.update("UPDATE onboarding_templates SET name=?,active=?,version=version+1,updated_at=? WHERE id=?",
                request.name(), request.active(), time(at), id);
        replaceTemplateItems(id, request.items());
    }

    private void replaceTemplateItems(UUID templateId, List<OnboardingRequests.TemplateItem> items) {
        jdbc.update("DELETE FROM onboarding_template_items WHERE template_id=?", templateId);
        for (int index = 0; index < items.size(); index++) {
            var item = items.get(index);
            jdbc.update("""
                    INSERT INTO onboarding_template_items(id,template_id,title,assignee_id,due_offset_days,sort_order)
                    VALUES (?,?,?,?,?,?)
                    """, UUID.randomUUID(), templateId, item.title(), item.assigneeId(), item.dueOffsetDays(), index);
        }
    }

    Optional<OnboardingChecklistView> checklist(UUID applicationId, boolean lock) {
        return jdbc.query("SELECT * FROM onboarding_checklists WHERE application_id=?" + (lock ? " FOR UPDATE" : ""),
                this::checklist, applicationId).stream().findFirst();
    }

    void createChecklist(UUID id, UUID applicationId, OnboardingTemplateView template, LocalDate startDate,
                         UUID actor, Instant at) {
        jdbc.update("""
                INSERT INTO onboarding_checklists(id,application_id,department_id,template_id,template_version,
                    template_name,start_date,status,version,created_by,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'PENDING',0,?,?,?)
                """, id, applicationId, template.departmentId(), template.id(), template.version(), template.name(),
                startDate, actor, time(at), time(at));
        for (var item : template.items()) {
            jdbc.update("""
                    INSERT INTO onboarding_checklist_items(id,checklist_id,title,assignee_id,due_date,status,sort_order)
                    VALUES (?,?,?,?,?,'PENDING',?)
                    """, UUID.randomUUID(), id, item.title(), item.assigneeId(), startDate.plusDays(item.dueOffsetDays()),
                    item.position());
        }
    }

    void updateItem(UUID checklistId, UUID itemId, OnboardingRequests.UpdateItem request, UUID actor, Instant at) {
        // Keeping DONE preserves who actually completed it; reopening removes that old completion evidence.
        jdbc.update("""
                UPDATE onboarding_checklist_items SET title=?,assignee_id=?,due_date=?,
                    completed_at=CASE WHEN ?='DONE' THEN COALESCE(completed_at,?) ELSE NULL END,
                    completed_by=CASE WHEN ?='DONE' THEN COALESCE(completed_by,?) ELSE NULL END,status=?
                WHERE id=? AND checklist_id=?
                """, request.title(), request.assigneeId(), request.dueDate(), request.status(), time(at),
                request.status(), actor, request.status(), itemId, checklistId);
    }

    void advanceChecklist(UUID id, boolean completed, UUID actor, Instant at) {
        jdbc.update("""
                UPDATE onboarding_checklists SET version=version+1,updated_at=?,status=?,completed_at=?,completed_by=?
                WHERE id=?
                """, time(at), completed ? "COMPLETED" : "PENDING", completed ? time(at) : null,
                completed ? actor : null, id);
    }

    void onboard(UUID applicationId, UUID actor, Instant at) {
        if (jdbc.update("UPDATE job_applications SET stage='ONBOARDED' WHERE id=? AND stage='HIRED'", applicationId) != 1) {
            throw new IllegalStateException("The locked application changed its onboarding stage");
        }
        jdbc.update("""
                INSERT INTO application_stage_events(id,application_id,stage_before,stage_after,actor_type,actor_id,
                    reason,occurred_at)
                VALUES (?,?,'HIRED','ONBOARDED','STAFF',?,?,?)
                """, UUID.randomUUID(), applicationId, actor, "Hoàn tất danh sách công việc tiếp nhận nhân sự.", time(at));
    }

    UUID departmentOf(UUID requisitionId) {
        return jdbc.queryForObject("SELECT department_id FROM recruitment_requisitions WHERE id=?", UUID.class, requisitionId);
    }

    Optional<Boolean> activeDepartment(UUID id, boolean lock) {
        return jdbc.query("SELECT active FROM departments WHERE id=?" + (lock ? " FOR KEY SHARE" : ""),
                (row, index) -> row.getBoolean(1), id).stream().findFirst();
    }

    boolean activeAssignee(UUID id, boolean lock) {
        return jdbc.query("SELECT enabled AND admin_locked_at IS NULL FROM user_accounts WHERE id=?"
                        + (lock ? " FOR KEY SHARE" : ""), (row, index) -> row.getBoolean(1), id)
                .stream().findFirst().orElse(false);
    }

    private OnboardingTemplateView template(ResultSet row, int index) throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        var items = jdbc.query("SELECT * FROM onboarding_template_items WHERE template_id=? ORDER BY sort_order,id",
                (item, number) -> new OnboardingTemplateView.Item(item.getObject("id", UUID.class), item.getString("title"),
                        item.getObject("assignee_id", UUID.class), item.getInt("due_offset_days"), item.getInt("sort_order")), id);
        return new OnboardingTemplateView(id, row.getObject("department_id", UUID.class), row.getString("name"),
                row.getBoolean("active"), row.getLong("version"), items, instant(row, "created_at"), instant(row, "updated_at"));
    }

    private OnboardingChecklistView checklist(ResultSet row, int index) throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        var items = jdbc.query("SELECT * FROM onboarding_checklist_items WHERE checklist_id=? ORDER BY sort_order,id",
                (item, number) -> new OnboardingChecklistView.Item(item.getObject("id", UUID.class), item.getString("title"),
                        item.getObject("assignee_id", UUID.class), item.getObject("due_date", LocalDate.class),
                        item.getString("status"), item.getInt("sort_order"), instant(item, "completed_at"),
                        item.getObject("completed_by", UUID.class)), id);
        int done = (int) items.stream().filter(item -> "DONE".equals(item.status())).count();
        return new OnboardingChecklistView(id, row.getObject("application_id", UUID.class),
                row.getObject("department_id", UUID.class), row.getObject("template_id", UUID.class),
                row.getLong("template_version"), row.getString("template_name"), row.getObject("start_date", LocalDate.class),
                row.getString("status"), row.getLong("version"), items.size(), done, OnboardingProgress.percent(done, items.size()),
                instant(row, "completed_at"), row.getObject("completed_by", UUID.class), items,
                instant(row, "created_at"), instant(row, "updated_at"));
    }

    private static Timestamp time(Instant value) { return Timestamp.from(value); }
    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
