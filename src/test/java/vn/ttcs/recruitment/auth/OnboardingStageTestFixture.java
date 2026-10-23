package vn.ttcs.recruitment.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.application.ApplicationStage;

import java.sql.Timestamp;
import java.util.UUID;

/** Keeps older stage-parameterized tests valid: ONBOARDED always has a real, completed checklist. */
final class OnboardingStageTestFixture {
    private OnboardingStageTestFixture() { }

    static void stage(JdbcTemplate jdbc, UUID application, ApplicationStage stage) {
        if (stage != ApplicationStage.ONBOARDED) {
            jdbc.update("UPDATE job_applications SET stage=? WHERE id=?", stage.name(), application);
            return;
        }
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(tx -> {
            var context = jdbc.queryForMap("""
                    SELECT r.department_id,r.created_by FROM job_applications a
                    JOIN recruitment_requisitions r ON r.id=a.requisition_id WHERE a.id=?
                    """, application);
            UUID department = (UUID) context.get("department_id");
            UUID actor = (UUID) context.get("created_by");
            UUID template = UUID.randomUUID();
            UUID checklist = UUID.randomUUID();
            Timestamp at = Timestamp.from(ApplicationTestSupport.NOW);
            jdbc.update("UPDATE job_applications SET stage='HIRED' WHERE id=?", application);
            jdbc.update("""
                    INSERT INTO onboarding_templates(id,department_id,name,active,created_by,created_at,updated_at)
                    VALUES (?,?,'Stage fixture',true,?,?,?)
                    """, template, department, actor, at, at);
            jdbc.update("""
                    INSERT INTO onboarding_template_items(id,template_id,title,assignee_id,due_offset_days,sort_order)
                    VALUES (?,?,'Prepared account',?,0,0)
                    """, UUID.randomUUID(), template, actor);
            jdbc.update("""
                    INSERT INTO onboarding_checklists(id,application_id,department_id,template_id,template_version,
                        template_name,start_date,created_by,created_at,updated_at)
                    VALUES (?,?,?,?,0,'Stage fixture',DATE '2026-11-01',?,?,?)
                    """, checklist, application, department, template, actor, at, at);
            jdbc.update("""
                    INSERT INTO onboarding_checklist_items(id,checklist_id,title,assignee_id,due_date,status,
                        sort_order,completed_at,completed_by)
                    VALUES (?,?,'Prepared account',?,DATE '2026-11-01','DONE',0,?,?)
                    """, UUID.randomUUID(), checklist, actor, at, actor);
            jdbc.update("UPDATE onboarding_checklists SET status='COMPLETED',completed_at=?,completed_by=? WHERE id=?", at, actor, checklist);
            jdbc.update("UPDATE job_applications SET stage='ONBOARDED' WHERE id=?", application);
            jdbc.update("""
                    INSERT INTO application_stage_events(id,application_id,stage_before,stage_after,actor_type,actor_id,reason,occurred_at)
                    VALUES (?,?,'HIRED','ONBOARDED','STAFF',?,'Completed fixture checklist',?)
                    """, UUID.randomUUID(), application, actor, at);
        });
    }
}
