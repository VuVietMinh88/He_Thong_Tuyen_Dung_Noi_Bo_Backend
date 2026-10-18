package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.ApplicationSubmission;
import vn.ttcs.recruitment.application.CvValidator;
import vn.ttcs.recruitment.application.PublicApplicationStatus;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.application.PublicResponseContract.assertJsonKeys;

/**
 * Task 341 (story S4-05, "Không tiết lộ nhận xét nội bộ hay tên người phỏng vấn"): the public lookup over a real socket
 * and a real PostgreSQL, against an application surrounded by the staff around it and by every free-text internal
 * note, comment, reason and justification the schema stores today: a primary and a supporting recruiter, the
 * department's manager and the requisition's creator with distinctive names, the requisition's approval comment, the
 * posting's review comment, the recruiter assignment note, the HR manager's headcount override reason, the
 * requisition's salary justification and the supporting recruiter's account lock reason, plus a catalog source, a
 * cover letter, a phone number and a CV file name. Sprint 4 has no notes on applications, evaluations, interviews or
 * interviewers yet; whatever Sprint 5 and 6 add for them is covered by the table-lock case, which locks every table but
 * the application's own while a lookup answers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.page-url=https://portal.example.test/tra-cuu",
        "app.application-confirmation.poll-delay-ms=3600000", "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-tracking.cleanup-delay-ms=3600000", "app.application-tracking.max-attempts=3",
        "spring.mail.host=127.0.0.1", "spring.mail.port=1"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PublicApplicationPrivacyIntegrationTest {
    private static final String TITLE = "Lập trình viên Java";
    private static final String EMAIL = "Ung.Vien.Bi.Mat@Example.test";
    private static final String CANDIDATE_NAME = "Đỗ Thị Ứng Viên";
    private static final String PHONE = "0987654321";
    private static final String COVER_LETTER = "Kính gửi phòng nhân sự. THU_GIOI_THIEU_RIENG_TU";
    private static final String SOURCE_NAME = "Nguồn Bí Mật";
    private static final String CV_NAME = "cv-bi-mat.pdf";
    // The free-text internal notes, comments, reasons and justifications stored today (V6, V13, V15, V17, V19, V22).
    private static final String APPROVAL_COMMENT = "NHAN_XET_DUYET_YEU_CAU_NOI_BO";
    private static final String REVIEW_COMMENT = "NHAN_XET_DUYET_TIN_NOI_BO";
    private static final String ASSIGNMENT_NOTE = "GHI_CHU_PHAN_CONG_NOI_BO";
    private static final String OVERRIDE_REASON = "LY_DO_VUOT_DINH_BIEN_NOI_BO";
    private static final String SALARY_JUSTIFICATION = "GIAI_TRINH_LUONG_NOI_BO";
    private static final String LOCK_REASON = "LY_DO_KHOA_TAI_KHOAN_NOI_BO";
    private static final String PRIMARY = "Trần Thị Tuyển Dụng Chính";
    private static final String SUPPORTING = "Lê Văn Hỗ Trợ";
    private static final String MANAGER = "Phạm Quản Lý";
    private static final String CREATOR = "Ngô Người Tạo";
    // Every internal stage label (ApplicationStage.displayName). "Phỏng vấn" is left out on purpose: the public
    // "Mời phỏng vấn" contains it in another case; the exact status values cover it.
    private static final List<String> INTERNAL_LABELS = List.of("Hồ sơ mới", "Sàng lọc", "Đề xuất offer", "Nhận việc",
            "Loại", "Ứng viên rút");
    // The only tables the lookup may touch: the application row and the tracking quota (V31).
    private static final List<String> LOOKUP_TABLES = List.of("job_applications", "application_tracking_ip_quotas",
            "application_tracking_ip_attempts");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private Environment environment;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationStorageService storage;
    @Autowired private CvValidator validator;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private ApplicationTestSupport applications;
    private ApplicationTestSupport.Stored application;
    // Everything of the fixture a public body must never contain.
    private final List<String> internal = new ArrayList<>();

    @BeforeEach
    void fixture() {
        clock.set(ApplicationTestSupport.NOW);
        int port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, port).reset();
        internal.clear();

        UUID posting = applications.publishedPosting(TITLE);
        UUID requisition = applications.requisitionOf(posting);
        UUID creator = jdbc.queryForObject("SELECT created_by FROM recruitment_requisitions WHERE id = ?", UUID.class, requisition);
        UUID department = jdbc.queryForObject("SELECT department_id FROM recruitment_requisitions WHERE id = ?", UUID.class, requisition);
        jdbc.update("UPDATE user_accounts SET full_name = ?, email = ? WHERE id = ?", CREATOR, "nguoi.tao@noi-bo.test", creator);
        UUID manager = applications.staff(MANAGER, "quan.ly@noi-bo.test");
        jdbc.update("UPDATE departments SET manager_user_id = ? WHERE id = ?", manager, department);
        UUID primary = applications.staff(PRIMARY, "tuyen.dung.chinh@noi-bo.test");
        UUID supporting = applications.staff(SUPPORTING, "ho.tro@noi-bo.test");
        applications.assign(requisition, primary, "PRIMARY", creator);
        applications.assign(requisition, supporting, "SUPPORTING", creator);
        Timestamp at = Timestamp.from(ApplicationTestSupport.NOW);
        jdbc.update("""
                INSERT INTO requisition_recruiter_changes(id,requisition_id,revision,change_type,recruiter_id,changed_by,changed_at,note)
                VALUES (?,?,1,'PRIMARY_ASSIGNED',?,?,?,?)
                """, UUID.randomUUID(), requisition, primary, creator, at, ASSIGNMENT_NOTE);
        jdbc.update("""
                INSERT INTO requisition_workflow_events(id,requisition_id,revision,event_type,status_before,status_after,
                    round_number,level_number,actor_id,occurred_at,comment)
                VALUES (?,?,1,'APPROVED','PENDING_APPROVAL','APPROVED',1,1,?,?,?)
                """, UUID.randomUUID(), requisition, manager, at, APPROVAL_COMMENT);
        jdbc.update("""
                INSERT INTO job_posting_events(id,job_posting_id,revision,event_type,status_before,status_after,actor_id,occurred_at,comment)
                VALUES (?,?,1,'APPROVED','PENDING_APPROVAL','APPROVED',?,?,?)
                """, UUID.randomUUID(), posting, manager, at, REVIEW_COMMENT);
        jdbc.update("""
                INSERT INTO requisition_headcount_overrides(id,requisition_id,department_id,plan_year,headcount_limit,
                    headcount_used,salary_cost_used,requested_headcount,requested_salary_cost,headcount_exceeded,
                    salary_budget_exceeded,reason,overridden_by,overridden_at)
                VALUES (?,?,?,2026,1,1,0,1,0,true,false,?,?,?)
                """, UUID.randomUUID(), requisition, department, OVERRIDE_REASON, manager, at);
        assertThat(jdbc.update("UPDATE recruitment_requisitions SET salary_justification = ? WHERE id = ?",
                SALARY_JUSTIFICATION, requisition)).isEqualTo(1);
        assertThat(jdbc.update("""
                UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = ?, admin_locked_by = ? WHERE id = ?
                """, at, LOCK_REASON, creator, supporting)).isEqualTo(1);
        UUID source = applications.source(SOURCE_NAME);

        application = applications.stored(posting,
                new ApplicationSubmission(CANDIDATE_NAME, EMAIL, PHONE, source, COVER_LETTER), applications.pdf(CV_NAME));
        assertThat(jdbc.queryForMap("SELECT source_id, cover_letter, phone FROM job_applications WHERE id = ?",
                application.applicationId())).containsEntry("source_id", source).containsEntry("cover_letter", COVER_LETTER)
                .containsEntry("phone", PHONE);
        assertThat(jdbc.queryForObject("SELECT filename FROM application_cvs WHERE application_id = ?", String.class,
                application.applicationId())).isEqualTo(CV_NAME);

        internal.addAll(List.of(PRIMARY, SUPPORTING, MANAGER, CREATOR, "noi-bo.test", APPROVAL_COMMENT, REVIEW_COMMENT,
                ASSIGNMENT_NOTE, OVERRIDE_REASON, SALARY_JUSTIFICATION, LOCK_REASON, "THU_GIOI_THIEU_RIENG_TU",
                SOURCE_NAME, CV_NAME, "cv-bi-mat", PHONE, CANDIDATE_NAME, EMAIL, EMAIL.toLowerCase(Locale.ROOT),
                "Ung.Vien", "ung.vien"));
        for (UUID id : List.of(application.applicationId(), requisition, posting, department, creator, manager, primary,
                supporting, source)) {
            internal.add(id.toString());
        }
        internal.addAll(INTERNAL_LABELS);
        for (ApplicationStage stage : ApplicationStage.values()) internal.add("\"" + stage.name() + "\"");
    }

    // Whatever the stage, the candidate gets exactly the four public fields, with task 340's exact values.
    @ParameterizedTest
    @EnumSource(ApplicationStage.class)
    void everyStageShowsTheFourPublicFieldsAndNothingInternal(ApplicationStage stage) throws Exception {
        applications.stage(application.applicationId(), stage);

        var response = applications.lookup(application.trackingCode(), EMAIL);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertJsonKeys(body, "postingTitle", "receivedAt", "status", "statusName");
        assertThat(body.get("postingTitle").asString()).isEqualTo(TITLE);
        assertThat(body.get("receivedAt").asString()).isEqualTo(ApplicationTestSupport.NOW.toString());
        assertThat(body.get("status").asString()).isEqualTo(PublicApplicationStatus.of(stage).name());
        assertThat(body.get("statusName").asString()).isEqualTo(PublicApplicationStatus.of(stage).displayName());
        assertNothingInternal(response);
    }

    // The reader joins nothing: while a raw connection holds an exclusive lock on every table but the application's
    // own (staff, requisitions, recruiters, departments, postings, catalogs, CVs, outbox, every history table, and any
    // table a later sprint adds), the lookup still answers at once, with the same body. A join to any of them would
    // wait and end in 503 after the 3 s statement timeout.
    @Test
    void theLookupReadsNoTableButTheApplicationAndItsQuota() throws Exception {
        String unlocked = applications.lookup(application.trackingCode(), EMAIL).body();
        List<String> others = jdbc.queryForList("""
                SELECT tablename::text FROM pg_tables WHERE schemaname = current_schema() AND NOT (tablename::text = ANY(?::text[]))
                ORDER BY tablename
                """, String.class, (Object) LOOKUP_TABLES.toArray(String[]::new));
        assertThat(others).contains("user_accounts", "departments", "recruitment_requisitions", "requisition_recruiters",
                "requisition_recruiter_changes", "requisition_workflow_events", "requisition_headcount_overrides",
                "job_postings", "job_posting_events", "recruitment_catalog_items", "application_cvs",
                "application_email_outbox");

        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try {
                try (var statement = blocker.createStatement()) {
                    statement.execute("SET LOCAL lock_timeout = '5s'");
                    statement.execute("LOCK TABLE " + String.join(", ", others.stream()
                            .map(table -> "\"" + table.replace("\"", "\"\"") + "\"").toList()) + " IN ACCESS EXCLUSIVE MODE");
                }
                long started = System.nanoTime();
                var locked = applications.lookup(application.trackingCode(), EMAIL);
                assertThat(locked.statusCode()).as(locked.body()).isEqualTo(200);
                assertThat(locked.body()).isEqualTo(unlocked);
                assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2500);
            } finally {
                blocker.rollback();
            }
        }
    }

    // Errors have exactly the house shape and repeat nothing that was submitted: no email, no code, no part of it,
    // and a 404 for a real code with a wrong email says nothing of the application behind the code.
    @Test
    void errorBodiesHaveTheHouseShapeAndRepeatNothingSubmitted() throws Exception {
        String code = application.trackingCode();
        String badEmail = "khong-hop-le@@" + "dia-chi-bi-mat.test";
        String shortCode = code.substring(1);
        String unknown = applications.unknownCode();
        String wrongEmail = "nguoi.khac@dia-chi-bi-mat.test";
        List<String> submitted = List.of(code, code.substring(0, 42), code.substring(1), badEmail, "dia-chi-bi-mat",
                unknown, unknown.substring(0, 42), wrongEmail, EMAIL, EMAIL.toLowerCase(Locale.ROOT));

        var invalidEmail = applications.lookup(code, badEmail);
        assertError(invalidEmail, 400, "VALIDATION_ERROR", submitted);
        assertThat(json.readTree(invalidEmail.body()).get("fieldErrors").propertyNames()).containsExactly("email");
        var invalidCode = applications.lookup(shortCode, EMAIL);
        assertError(invalidCode, 400, "VALIDATION_ERROR", submitted);
        assertThat(json.readTree(invalidCode.body()).get("fieldErrors").propertyNames()).containsExactly("trackingCode");

        // max-attempts=3: two misses and one hit, then the fourth well-formed attempt is refused.
        assertError(applications.lookup(unknown, EMAIL), 404, "APPLICATION_LOOKUP_NOT_FOUND", submitted);
        var wrongPair = applications.lookup(code, wrongEmail);
        assertError(wrongPair, 404, "APPLICATION_LOOKUP_NOT_FOUND", submitted);
        assertThat(json.readTree(wrongPair.body()).get("fieldErrors").isEmpty()).isTrue();
        assertThat(applications.lookup(code, EMAIL).statusCode()).isEqualTo(200);
        var limited = applications.lookup(code, EMAIL);
        assertError(limited, 429, "APPLICATION_TRACKING_RATE_LIMIT_EXCEEDED", submitted);
        assertThat(limited.headers().firstValue("Retry-After")).isPresent();
    }

    private void assertError(HttpResponse<String> response, int status, String code, List<String> submitted) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode body = json.readTree(response.body());
        assertJsonKeys(body, "code", "message", "fieldErrors");
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(response.body()).doesNotContain(submitted.toArray(String[]::new)).doesNotContain(TITLE);
        assertNothingInternal(response);
    }

    // Neither the body nor any header value carries anything internal of the fixture.
    private void assertNothingInternal(HttpResponse<String> response) {
        String[] forbidden = internal.toArray(String[]::new);
        assertThat(response.body()).doesNotContain(forbidden);
        for (Map.Entry<String, List<String>> header : response.headers().map().entrySet()) {
            for (String value : header.getValue()) {
                assertThat(value).as(header.getKey()).doesNotContain(forbidden);
            }
        }
        assertThat(Arrays.stream(forbidden).filter(String::isBlank)).isEmpty();
    }
}
