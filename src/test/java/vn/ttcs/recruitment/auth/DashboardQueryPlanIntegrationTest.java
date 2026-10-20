package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.reporting.DashboardQueryPlans;
import vn.ttcs.recruitment.reporting.DashboardReportService;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 558 (story S8-05): the V67 indexes exist and PostgreSQL uses them for the dashboard's real statements (built by
 * the repository and the service with their real parameters), on a small seeded volume: 5,000
 * requisitions (mostly closed), 5,000 applications over two years and 3,000 closure events, after VACUUM ANALYZE.
 * The volume timing itself is
 * DashboardPerformanceIntegrationTest, run only with -Pperformance.
 */
class DashboardQueryPlanIntegrationTest extends ReportingTestSupport {
    @Autowired DashboardReportService dashboards;
    @Autowired NamedParameterJdbcTemplate named;

    @Test
    void theDashboardIndexesOfV67Exist() {
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = current_schema()", String.class))
                .contains("job_applications_received_at_idx", "recruitment_requisitions_open_needed_by_idx",
                        "requisition_workflow_events_closed_at_idx", "offers_sent_deadline_idx");
    }

    @Test
    void theDashboardStatementsUseTheV67Indexes() {
        seed();
        Map<String, String> plans = DashboardQueryPlans.plans(dashboards, named, NOW, LocalDate.of(2026, 10, 15));
        assertThat(indexes(plans.get("week card"))).contains("job_applications_received_at_idx");
        assertThat(indexes(plans.get("weekly chart"))).contains("job_applications_received_at_idx");
        assertThat(indexes(plans.get("overdue count"))).contains("recruitment_requisitions_open_needed_by_idx");
        assertThat(indexes(plans.get("monthly closures"))).contains("requisition_workflow_events_closed_at_idx");
        // The page sorts by a computed key (fully staffed last), so PostgreSQL may reach the few open rows through any
        // index of recruitment_requisitions; it must never read the whole table.
        assertThat(nodes(plans.get("overdue page"))).doesNotContain("Seq Scan recruitment_requisitions");
    }

    // The index names of every node of a JSON plan.
    private List<String> indexes(String plan) {
        List<String> found = new ArrayList<>();
        walk(json.readTree(plan), node -> {
            if (node.has("Index Name")) {
                found.add(node.path("Index Name").asText());
            }
        });
        return found;
    }

    // "Node Type Relation Name" of every node of a JSON plan.
    private List<String> nodes(String plan) {
        List<String> found = new ArrayList<>();
        walk(json.readTree(plan), node -> {
            if (node.has("Node Type")) {
                found.add((node.path("Node Type").asText() + " " + node.path("Relation Name").asText("")).strip());
            }
        });
        return found;
    }

    private static void walk(JsonNode node, Consumer<JsonNode> visit) {
        if (node.isObject()) {
            visit.accept(node);
            node.forEach(child -> walk(child, visit));
        } else if (node.isArray()) {
            node.forEach(child -> walk(child, visit));
        }
    }

    private void seed() {
        Timestamp at = Timestamp.from(NOW);
        UUID location = catalog("WORK_LOCATION", "Hà Nội", true), employment = catalog("EMPLOYMENT_TYPE", "Toàn thời gian", true);
        // 5,000 requisitions: 4 % approved or paused (half of them overdue), the others closed or never approved.
        jdbc.update("""
                INSERT INTO recruitment_requisitions(id, position_id, department_id, headcount, reason, status, needed_by,
                    created_by, created_at, updated_at)
                SELECT gen_random_uuid(), ?, ?, 1, 'NEW_HEADCOUNT',
                       CASE WHEN g % 50 = 0 THEN 'APPROVED' WHEN g % 50 = 1 THEN 'PAUSED'
                            WHEN g % 50 < 30 THEN 'FILLED' WHEN g % 50 < 40 THEN 'CANCELLED' ELSE 'DRAFT' END,
                       DATE '2026-10-15' + (g % 200 - 100), ?, ?, ?
                FROM generate_series(1, 5000) g
                """, position, department, hr, at, at);
        jdbc.update("""
                INSERT INTO job_postings(id, requisition_id, status, revision, title, job_description, candidate_requirements,
                    work_location_id, employment_type_id, application_deadline, show_salary, created_by, created_at,
                    updated_at, published_by, published_at)
                SELECT gen_random_uuid(), r.id, 'PUBLISHED', 1, 'Plan', 'Mô tả', 'Yêu cầu', ?, ?, DATE '2026-12-31', false,
                       ?, ?, ?, ?, ?
                FROM recruitment_requisitions r WHERE r.status = 'FILLED'
                  AND NOT EXISTS (SELECT 1 FROM job_postings p WHERE p.requisition_id = r.id)
                """, location, employment, hr, at, at, hr, at);
        jdbc.update("""
                WITH reqs AS (SELECT array_agg(p.requisition_id ORDER BY p.requisition_id) AS ids,
                                     array_agg(p.id ORDER BY p.requisition_id) AS postings, count(*) AS total
                              FROM job_postings p WHERE p.title = 'Plan')
                INSERT INTO job_applications(id, job_posting_id, requisition_id, full_name, email, normalized_email, phone,
                    stage, received_at, posting_title, tracking_code_hash)
                SELECT gen_random_uuid(), reqs.postings[1 + g % reqs.total], reqs.ids[1 + g % reqs.total], 'Plan ' || g,
                       'plan' || g || '@example.test', 'plan' || g || '@example.test', '0912345678', 'REJECTED',
                       CAST(? AS timestamptz) - (g % 730) * INTERVAL '1 day', 'Plan', sha256(convert_to('plan-' || g, 'UTF8'))
                FROM generate_series(1, 5000) g, reqs
                """, at);
        jdbc.update("""
                INSERT INTO requisition_workflow_events(id, requisition_id, revision, event_type, status_before, status_after,
                    actor_id, occurred_at, comment)
                SELECT gen_random_uuid(), r.id, 1, r.status, 'APPROVED', r.status, ?,
                       CAST(? AS timestamptz) - (row_number() OVER (ORDER BY r.id) % 730) * INTERVAL '1 day', 'Đóng'
                FROM recruitment_requisitions r WHERE r.status IN ('FILLED', 'CANCELLED')
                """, hr, at);
        // What autovacuum does on a live database: statistics and the visibility map.
        jdbc.execute("VACUUM ANALYZE");
    }
}
