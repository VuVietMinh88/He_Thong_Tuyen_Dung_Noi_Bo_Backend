package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 558 (story S8-05, "Toàn bộ dữ liệu tải xong dưới 2 giây"): the whole dashboard API on a realistic volume,
 * measured through real HTTP calls on the temporary PostgreSQL. The volume is seeded with set-based SQL: 12
 * departments, 30 positions, 400 requisitions with postings, 40,000 applications received over the last 400 days with
 * their stage history and candidate links, 120,000 older applications (up to five years of history), thousands of
 * interviews and offers, and the closure events. Every viewer gets three warm-up calls, then ten measured calls; each
 * must answer in under 2 seconds. A trivial authenticated call (GET /api/v1/auth/me) is measured the same way as the
 * floor of any request. This measures the backend API only (database, service, JSON), not the browser.
 * <p>
 * Tagged "performance": it seeds a large volume and depends on the machine's load, so the default build skips it
 * (pom.xml excludedGroups). Run it with ./mvnw -o -q test -Pperformance -Dtest=DashboardPerformanceIntegrationTest.
 * The default suite checks the indexes and the plans of the real statements in DashboardQueryPlanIntegrationTest.
 */
@Tag("performance")
class DashboardPerformanceIntegrationTest extends ReportingTestSupport {
    static final int APPLICATIONS = 40_000;
    static final long LIMIT_MILLIS = 2_000;

    @Test
    void theWholeDashboardAnswersInUnderTwoSecondsOnARealisticVolume() throws Exception {
        long seedStarted = System.nanoTime();
        seed();
        long seedMillis = (System.nanoTime() - seedStarted) / 1_000_000;
        System.out.printf("REPORTING_PERF seed: %d applications, %d stage events, %d interviews, %d offers, "
                        + "%d requisitions in %d ms%n", count("job_applications"), count("application_stage_events"),
                count("interview_sessions"), count("offers"), count("recruitment_requisitions"), seedMillis);

        explain("weekly chart, 53 buckets", """
                SELECT width_bucket(a.received_at, (SELECT array_agg(g) FROM generate_series(
                           TIMESTAMPTZ '2025-10-12 17:00:00+00', TIMESTAMPTZ '2026-10-11 17:00:00+00', INTERVAL '7 days') g)),
                       count(*)
                FROM job_applications a JOIN recruitment_requisitions r ON r.id = a.requisition_id
                WHERE a.received_at >= TIMESTAMPTZ '2025-10-12 17:00:00+00' AND a.received_at < TIMESTAMPTZ '2026-10-18 17:00:00+00'
                GROUP BY 1""");
        explain("applications this week", """
                SELECT count(*) FROM job_applications a JOIN recruitment_requisitions r ON r.id = a.requisition_id
                WHERE a.received_at >= TIMESTAMPTZ '2026-10-11 17:00:00+00' AND a.received_at < TIMESTAMPTZ '2026-10-18 17:00:00+00'""");
        explain("overdue page", """
                SELECT r.id, (SELECT count(*) FROM job_applications h WHERE h.requisition_id = r.id
                              AND h.stage IN ('HIRED', 'ONBOARDED')) AS hired
                FROM recruitment_requisitions r JOIN positions p ON p.id = r.position_id
                JOIN departments d ON d.id = r.department_id
                WHERE r.status IN ('APPROVED', 'PAUSED') AND r.needed_by IS NOT NULL AND r.needed_by < DATE '2026-10-15'
                ORDER BY r.needed_by, r.id LIMIT 10""");
        explain("closed per month", """
                SELECT count(*) FROM requisition_workflow_events e JOIN recruitment_requisitions r ON r.id = e.requisition_id
                WHERE e.event_type IN ('FILLED', 'CANCELLED') AND e.occurred_at >= TIMESTAMPTZ '2025-09-30 17:00:00+00'
                  AND e.occurred_at < TIMESTAMPTZ '2026-10-31 17:00:00+00'""");

        List<Long> floor = new ArrayList<>();
        for (int call = 0; call < 13; call++) {
            long started = System.nanoTime();
            ok(get("/api/v1/auth/me", hrToken));
            if (call >= 3) {
                floor.add((System.nanoTime() - started) / 1_000_000);
            }
        }
        System.out.printf("REPORTING_PERF reference GET /api/v1/auth/me: ms per call %s (median %d)%n", floor,
                floor.stream().sorted().toList().get(5));

        for (var viewer : List.of(new Viewer("HR_MANAGER (ALL)", hrToken), new Viewer("RECRUITER (SCOPED)", recruiterToken),
                new Viewer("HIRING_MANAGER (SCOPED)", managerToken))) {
            String path = "/api/v1/reports/dashboard?from=2025-10-16&to=2026-10-15";
            for (int warmUp = 0; warmUp < 3; warmUp++) {
                ok(get(path, viewer.token()));
            }
            List<Long> millis = new ArrayList<>();
            JsonNode last = null;
            for (int call = 0; call < 10; call++) {
                long started = System.nanoTime();
                var response = get(path, viewer.token());
                millis.add((System.nanoTime() - started) / 1_000_000);
                last = ok(response);
            }
            List<Long> sorted = millis.stream().sorted().toList();
            System.out.printf("REPORTING_PERF %s: open=%d newThisWeek=%d overdue=%d weeks=%d; ms per call %s "
                            + "(median %d, max %d)%n", viewer.name(),
                    last.path("cards").path("openRequisitions").path("value").asLong(),
                    last.path("cards").path("newApplicationsThisWeek").path("value").asLong(),
                    last.path("overdueRequisitions").path("totalElements").asLong(),
                    last.path("charts").path("weeklyApplications").path("points").size(), millis, sorted.get(5),
                    sorted.getLast());
            assertThat(sorted.getLast()).as(viewer.name() + " slowest call in ms").isLessThan(LIMIT_MILLIS);
        }
    }

    private record Viewer(String name, String token) { }

    // Diagnostic only: how PostgreSQL runs a statement shaped like the dashboard's, with its measured execution time.
    private void explain(String label, String sql) {
        List<String> plan = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + sql, String.class);
        String scans = plan.stream().filter(line -> line.contains("Scan")).map(String::strip)
                .map(line -> line.contains("(cost=") ? line.substring(0, line.indexOf("(cost=")).strip() : line)
                .distinct().toList().toString();
        System.out.printf("REPORTING_PERF plan %s: %s; %s%n", label, scans, plan.getLast().strip());
    }

    private void seed() {
        Timestamp at = Timestamp.from(NOW);
        UUID location = catalog("WORK_LOCATION", "Hà Nội", true), employment = catalog("EMPLOYMENT_TYPE", "Toàn thời gian", true);
        for (int index = 0; index < 5; index++) {
            applications.source("Nguồn " + index);
        }
        UUID reason = catalog("REJECTION_REASON", "Không phù hợp", true);
        jdbc.update("""
                INSERT INTO departments(id, code, name, manager_user_id)
                SELECT gen_random_uuid(), 'PERF-D-' || lpad(g::text, 2, '0'), 'Phòng ' || g,
                       CASE WHEN g = 1 THEN ? ELSE ? END
                FROM generate_series(1, 12) g
                """, manager, hr);
        jdbc.update("""
                INSERT INTO positions(id, code, name, level, salary_min, salary_max, created_at, updated_at)
                SELECT gen_random_uuid(), 'PERF-P-' || lpad(g::text, 2, '0'), 'Chức danh ' || g, 'Junior', 0, 0, ?, ?
                FROM generate_series(1, 30) g
                """, at, at);
        jdbc.update("""
                WITH d AS (SELECT array_agg(id ORDER BY code) AS ids FROM departments WHERE code LIKE 'PERF-D-%'),
                     p AS (SELECT array_agg(id ORDER BY code) AS ids FROM positions WHERE code LIKE 'PERF-P-%')
                INSERT INTO recruitment_requisitions(id, position_id, department_id, headcount, reason, status, needed_by,
                    created_by, created_at, updated_at)
                SELECT gen_random_uuid(), p.ids[1 + g % 30], d.ids[1 + g % 12], 1 + g % 3, 'NEW_HEADCOUNT',
                       CASE WHEN g % 10 < 6 THEN 'APPROVED' WHEN g % 10 = 6 THEN 'PAUSED'
                            WHEN g % 10 < 9 THEN 'FILLED' ELSE 'CANCELLED' END,
                       DATE '2026-10-15' + (g % 120 - 60), ?, ?, ?
                FROM generate_series(1, 400) g, d, p
                """, hr, at, at);
        jdbc.update("""
                INSERT INTO job_postings(id, requisition_id, status, revision, title, job_description, candidate_requirements,
                    work_location_id, employment_type_id, application_deadline, show_salary, created_by, created_at,
                    updated_at, published_by, published_at)
                SELECT gen_random_uuid(), r.id, 'PUBLISHED', 1, 'Perf', 'Mô tả', 'Yêu cầu', ?, ?, DATE '2026-12-31', false,
                       ?, ?, ?, ?, ?
                FROM recruitment_requisitions r
                JOIN departments d ON d.id = r.department_id AND d.code LIKE 'PERF-D-%'
                """, location, employment, hr, at, at, hr, at);
        jdbc.update("""
                WITH reqs AS (SELECT array_agg(jp.requisition_id ORDER BY jp.requisition_id) AS ids,
                                     array_agg(jp.id ORDER BY jp.requisition_id) AS postings
                              FROM job_postings jp WHERE jp.title = 'Perf'),
                     src AS (SELECT array_agg(id ORDER BY name) AS ids FROM recruitment_catalog_items
                             WHERE catalog_type = 'CANDIDATE_SOURCE')
                INSERT INTO job_applications(id, job_posting_id, requisition_id, full_name, email, normalized_email, phone,
                    source_id, stage, received_at, posting_title, tracking_code_hash)
                SELECT gen_random_uuid(), reqs.postings[1 + g % 400], reqs.ids[1 + g % 400], 'Perf ' || g,
                       'perf' || g || '@example.test', 'perf' || g || '@example.test', '0912345678',
                       CASE WHEN g % 11 = 0 THEN NULL ELSE src.ids[1 + g % 5] END,
                       (ARRAY['NEW','SCREENING','INTERVIEW','OFFER','HIRED','REJECTED','WITHDRAWN'])[1 + g % 7],
                       CAST(? AS timestamptz) - (g / 7 % 400) * INTERVAL '1 day' - (g % 24) * INTERVAL '1 hour', 'Perf',
                       sha256(convert_to('perf-' || g, 'UTF8'))
                FROM generate_series(1, CAST(? AS integer)) g, reqs, src
                """, at, APPLICATIONS);
        // Stage history consistent with each current stage (one event per step).
        String perf = " FROM job_applications a WHERE a.posting_title = 'Perf' AND a.stage IN ";
        jdbc.update("""
                INSERT INTO application_stage_events(id, application_id, stage_before, stage_after, actor_type, actor_id,
                    occurred_at)
                SELECT gen_random_uuid(), a.id, 'NEW', 'SCREENING', 'STAFF', ?, a.received_at + INTERVAL '1 day'
                """ + perf + "('SCREENING','INTERVIEW','OFFER','HIRED','REJECTED')", hr);
        jdbc.update("""
                INSERT INTO application_stage_events(id, application_id, stage_before, stage_after, actor_type, actor_id,
                    occurred_at)
                SELECT gen_random_uuid(), a.id, 'SCREENING', 'INTERVIEW', 'STAFF', ?, a.received_at + INTERVAL '3 days'
                """ + perf + "('INTERVIEW','OFFER','HIRED')", hr);
        jdbc.update("""
                INSERT INTO application_stage_events(id, application_id, stage_before, stage_after, actor_type, actor_id,
                    occurred_at)
                SELECT gen_random_uuid(), a.id, 'INTERVIEW', 'OFFER', 'STAFF', ?, a.received_at + INTERVAL '8 days'
                """ + perf + "('OFFER','HIRED')", hr);
        jdbc.update("""
                INSERT INTO application_stage_events(id, application_id, stage_before, stage_after, actor_type, actor_id,
                    occurred_at)
                SELECT gen_random_uuid(), a.id, 'OFFER', 'HIRED', 'STAFF', ?, a.received_at + INTERVAL '12 days'
                """ + perf + "('HIRED')", hr);
        jdbc.update("""
                INSERT INTO application_stage_events(id, application_id, stage_before, stage_after, actor_type, actor_id,
                    occurred_at, rejection_reason_id, rejection_reason_name)
                SELECT gen_random_uuid(), a.id, 'SCREENING', 'REJECTED', 'STAFF', ?, a.received_at + INTERVAL '2 days', ?,
                       'Không phù hợp'
                """ + perf + "('REJECTED')", hr, reason);
        jdbc.update("""
                INSERT INTO application_stage_events(id, application_id, stage_before, stage_after, actor_type, occurred_at)
                SELECT gen_random_uuid(), a.id, 'NEW', 'WITHDRAWN', 'CANDIDATE', a.received_at + INTERVAL '1 day'
                """ + perf + "('WITHDRAWN')");
        // Older history: rejected applications received 401 to 1,800 days ago, never inside a one-year dashboard.
        jdbc.update("""
                WITH reqs AS (SELECT array_agg(jp.requisition_id ORDER BY jp.requisition_id) AS ids,
                                     array_agg(jp.id ORDER BY jp.requisition_id) AS postings
                              FROM job_postings jp WHERE jp.title = 'Perf')
                INSERT INTO job_applications(id, job_posting_id, requisition_id, full_name, email, normalized_email, phone,
                    stage, received_at, posting_title, tracking_code_hash)
                SELECT gen_random_uuid(), reqs.postings[1 + g % 400], reqs.ids[1 + g % 400], 'Old ' || g,
                       'old' || g || '@example.test', 'old' || g || '@example.test', '0912345678', 'REJECTED',
                       CAST(? AS timestamptz) - (401 + g % 1400) * INTERVAL '1 day', 'Old',
                       sha256(convert_to('old-' || g, 'UTF8'))
                FROM generate_series(1, 120000) g, reqs
                """, at);
        jdbc.update("""
                INSERT INTO candidates(id, normalized_email, created_at)
                SELECT gen_random_uuid(), a.normalized_email, a.received_at FROM job_applications a WHERE a.posting_title = 'Perf'
                """);
        jdbc.update("""
                INSERT INTO candidate_applications(application_id, candidate_id, linked_at)
                SELECT a.id, c.id, a.received_at FROM job_applications a JOIN candidates c ON c.normalized_email = a.normalized_email
                WHERE a.posting_title = 'Perf'
                """);
        jdbc.update("""
                INSERT INTO interview_sessions(id, application_id, round_name, starts_at, ends_at, mode, room, normalized_room,
                    status, created_by, created_at, updated_at)
                SELECT gen_random_uuid(), x.id, 'Vòng ' || x.n, x.starts_at, x.starts_at + INTERVAL '1 hour', 'ONSITE',
                       'Phòng ' || x.n % 20, 'phòng ' || x.n % 20, CASE WHEN x.n % 9 = 0 THEN 'CANCELLED' ELSE 'SCHEDULED' END,
                       ?, ?, ?
                FROM (SELECT a.id, row_number() OVER (ORDER BY a.id) AS n,
                             CAST(? AS timestamptz) + (row_number() OVER (ORDER BY a.id) % 60 - 30) * INTERVAL '1 day' AS starts_at
                      FROM job_applications a WHERE a.posting_title = 'Perf' AND a.stage IN ('INTERVIEW','OFFER','HIRED')) x
                """, hr, at, at, at);
        jdbc.update("""
                INSERT INTO offers(id, application_id, candidate_id, position_id, candidate_name, email, position_title,
                    salary, allowance, start_date, response_deadline, band_min, band_max, outside_band, status, version,
                    approval_round, created_by, submitted_by, approved_at, created_at, updated_at, sent_at, responded_at)
                SELECT gen_random_uuid(), a.id, ca.candidate_id, r.position_id, a.full_name, a.email, 'Chức danh',
                       20000000, 0, DATE '2026-12-01', CAST(? AS timestamptz) + (row_number() OVER (ORDER BY a.id) % 10 - 3) * INTERVAL '1 day',
                       10000000, 30000000, false, 'SENT', 0, 1, ?, ?, ?, ?, ?, ?, NULL
                FROM job_applications a JOIN candidate_applications ca ON ca.application_id = a.id
                JOIN recruitment_requisitions r ON r.id = a.requisition_id
                WHERE a.posting_title = 'Perf' AND a.stage = 'OFFER'
                """, at, recruiter, recruiter, at, at, at, at);
        jdbc.update("""
                INSERT INTO requisition_workflow_events(id, requisition_id, revision, event_type, status_before, status_after,
                    actor_id, occurred_at, comment)
                SELECT gen_random_uuid(), r.id, 1, r.status, 'APPROVED', r.status, ?,
                       CAST(? AS timestamptz) - (row_number() OVER (ORDER BY r.id) % 360) * INTERVAL '1 day', 'Đóng'
                FROM recruitment_requisitions r JOIN departments d ON d.id = r.department_id AND d.code LIKE 'PERF-D-%'
                WHERE r.status IN ('FILLED', 'CANCELLED')
                """, hr, at);
        jdbc.update("""
                INSERT INTO requisition_recruiters(requisition_id, recruiter_id, assignment_role, assigned_by, assigned_at)
                SELECT r.id, ?, 'PRIMARY', ?, ? FROM recruitment_requisitions r
                JOIN departments d ON d.id = r.department_id AND d.code IN ('PERF-D-02', 'PERF-D-03')
                """, recruiter, hr, at);
        jdbc.execute("ANALYZE");
    }
}
