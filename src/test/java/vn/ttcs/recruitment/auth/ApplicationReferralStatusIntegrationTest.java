package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.application.ApplicationReferralStatusService;
import vn.ttcs.recruitment.application.ApplicationReferralView;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.ApplicationSubmission;
import vn.ttcs.recruitment.application.CvValidator;
import vn.ttcs.recruitment.application.PublicResponseContract;
import vn.ttcs.recruitment.application.ReferralApplicationStorage;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 352 (story S4-07, "Người giới thiệu theo dõi được trạng thái ở mức tổng quát"): GET
 * /api/v1/referrals/my-referrals over real HTTP and PostgreSQL. A referrer sees only the applications they referred,
 * newest first, each as the item the referral's 201 answered: the candidate's name, the posting-title snapshot, the
 * receipt, the public status of task 340 and their own note. Never the candidate's email, phone or CV, the internal
 * stage, the source label, staff, the withdrawal reason or the tracking code. Referrals are stored through the
 * production ReferralApplicationStorage (task 350) at chosen clock times; one goes through the API of task 351.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.page-url=https://portal.example.test/tra-cuu",
        "app.application-confirmation.poll-delay-ms=3600000", "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-tracking.cleanup-delay-ms=3600000", "app.application-notice.poll-delay-ms=3600000",
        "spring.mail.host=127.0.0.1", "spring.mail.port=1"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationReferralStatusIntegrationTest {
    private static final String[] ITEM_KEYS = {"id", "candidateName", "jobTitle", "submittedAt", "status", "statusName",
            "referralNote"};
    private static final String REFERRER_NAME = "Trần Văn Nam";
    private static final String REFERRER_EMAIL = "nam.referrer@staff.test";
    private static final String OTHER_REFERRER_NAME = "Phạm Thu Hà";
    private static final String OTHER_REFERRER_EMAIL = "ha.referrer@staff.test";
    // The test clock when the lists are read; the referrals are stored before it.
    private static final Instant READ_AT = ApplicationTestSupport.NOW.plus(Duration.ofMinutes(10));

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private Environment environment;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationStorageService storage;
    @Autowired private ReferralApplicationStorage referrals;
    @Autowired private ApplicationReferralStatusService statuses;
    @Autowired private CvValidator validator;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private TokenService tokens;
    @Autowired private JwtDecoder decoder;

    private ApplicationTestSupport applications;
    private UUID posting;
    private UUID referrer;
    private UUID otherReferrer;

    @BeforeEach
    void fixture() {
        clock.set(ApplicationTestSupport.NOW);
        int port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, port).reset();
        posting = applications.publishedPosting("Lập trình viên Java");
        referrer = applications.account(REFERRER_NAME, REFERRER_EMAIL, Role.INTERVIEWER);
        otherReferrer = applications.account(OTHER_REFERRER_NAME, OTHER_REFERRER_EMAIL, Role.HIRING_MANAGER);
    }

    // Two referrers each see exactly their own referrals, newest receipt first; two referrals received at the same
    // microsecond come in descending id order (PostgreSQL compares UUIDs byte by byte, like their hex text). A query
    // string (the frontend sends search, status, page, limit) changes nothing, a referrerId in it included.
    @Test
    void eachReferrerSeesOnlyTheirOwnReferralsNewestFirst() throws Exception {
        UUID testing = applications.publishedPosting("Kiểm thử viên");
        UUID first = referAt(0, posting, "Nguyễn Văn A", "a@example.test", referrer, "Ghi chú thứ nhất");
        UUID others = referAt(1, posting, "Đỗ Minh Khoa", "khoa@example.test", otherReferrer, "Ghi chú của Hà");
        UUID second = referAt(2, testing, "Lê Thị Bình", "binh@example.test", referrer, "Ghi chú thứ hai");
        UUID tieOne = referAt(3, posting, "Vũ Văn Cường", "cuong@example.test", referrer, "Ghi chú cùng lúc 1");
        UUID tieTwo = referAt(3, testing, "Hoàng Thị Dung", "dung@example.test", referrer, "Ghi chú cùng lúc 2");
        clock.set(READ_AT);
        String token = applications.token(tokens, referrer);

        JsonNode mine = list(applications.myReferrals(token));
        List<UUID> ties = new ArrayList<>(List.of(tieOne, tieTwo));
        ties.sort(Comparator.comparing(UUID::toString).reversed());
        assertThat(ids(mine)).containsExactly(ties.get(0), ties.get(1), second, first);
        assertItem(item(mine, second), second, "Lê Thị Bình", "Kiểm thử viên", minute(2), "RECEIVED", "Đã nhận",
                "Ghi chú thứ hai");
        assertItem(item(mine, first), first, "Nguyễn Văn A", "Lập trình viên Java", minute(0), "RECEIVED", "Đã nhận",
                "Ghi chú thứ nhất");

        JsonNode theirs = list(applications.myReferrals(applications.token(tokens, otherReferrer)));
        assertThat(ids(theirs)).containsExactly(others);
        assertItem(theirs.get(0), others, "Đỗ Minh Khoa", "Lập trình viên Java", minute(1), "RECEIVED", "Đã nhận",
                "Ghi chú của Hà");

        var queried = applications.myReferrals(token, "?referrerId=" + otherReferrer
                + "&status=COMPLETED&search=Khoa&page=1&limit=1");
        assertThat(list(queried)).isEqualTo(mine);
    }

    // Every stage, set by SQL (Sprint 4 has no stage API), shows the public status of task 340 and its name; the
    // internal stage code and its name are never a value of the list.
    @Test
    void everyStageShowsItsPublicStatus() throws Exception {
        Map<UUID, ApplicationStage> stages = new LinkedHashMap<>();
        for (ApplicationStage stage : ApplicationStage.values()) {
            String name = stage.name().toLowerCase(java.util.Locale.ROOT);
            UUID id = referAt(0, posting, "Ứng viên " + name, name + "@example.test", referrer, "Ghi chú " + name);
            applications.stage(id, stage);
            stages.put(id, stage);
        }
        clock.set(READ_AT);

        JsonNode mine = list(applications.myReferrals(applications.token(tokens, referrer)));
        assertThat(mine.size()).isEqualTo(ApplicationStage.values().length);
        for (var entry : stages.entrySet()) {
            JsonNode item = item(mine, entry.getKey());
            String[] expected = publicStatus(entry.getValue());
            assertThat(item.get("status").asString()).as(entry.getValue().name()).isEqualTo(expected[0]);
            assertThat(item.get("statusName").asString()).as(entry.getValue().name()).isEqualTo(expected[1]);
        }
        List<String> internal = new ArrayList<>();
        for (ApplicationStage stage : ApplicationStage.values()) {
            internal.add(stage.name());
            internal.add(stage.displayName());
        }
        assertThat(values(mine)).doesNotContainAnyElementsOf(internal);
    }

    // A referral the candidate withdrew (public API of task 345, with a reason) shows "Kết thúc"; the reason, which
    // only staff in scope read, never reaches the referrer.
    @Test
    void aWithdrawnReferralShowsCompletedWithoutTheReason() throws Exception {
        var stored = applications.referred(referrals, transactions, posting,
                new ApplicationSubmission(ApplicationTestSupport.FULL_NAME, "lan@example.test",
                        ApplicationTestSupport.PHONE, null, null),
                applications.pdf(), referrer, ApplicationTestSupport.REFERRAL_NOTE);
        String reason = "Đã nhận việc ở công ty khác";
        var withdrawn = applications.withdraw(stored.trackingCode(), "lan@example.test", reason);
        assertThat(withdrawn.statusCode()).as(withdrawn.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT stage FROM job_applications WHERE id = ?", String.class,
                stored.applicationId())).isEqualTo("WITHDRAWN");

        var response = applications.myReferrals(applications.token(tokens, referrer));
        JsonNode mine = list(response);
        assertThat(ids(mine)).containsExactly(stored.applicationId());
        assertThat(mine.get(0).get("status").asString()).isEqualTo("COMPLETED");
        assertThat(mine.get(0).get("statusName").asString()).isEqualTo("Kết thúc");
        assertThat(response.body()).doesNotContain(reason, "WITHDRAWN", "Ứng viên rút", stored.trackingCode());
    }

    // The item is exactly seven fields, pinned on the record (PublicResponseContract of task 341: only id,
    // candidateName and referralNote may carry an internal-looking name, nothing else does) and on the JSON. Nothing
    // the referral row or the application row holds beyond them reaches the body: the candidate's email and phone,
    // the CV's file name, the stage, the source label, the requisition, the posting, the candidate profile, the
    // assigned recruiter, the referrer, another applicant of the same requisition, the tracking code.
    @Test
    void theListShowsNothingInternal() throws Exception {
        PublicResponseContract.assertExactComponents(ApplicationReferralView.class, ITEM_KEYS);
        PublicResponseContract.assertNoInternalComponents(ApplicationReferralView.class, "id", "candidateName",
                "referralNote");

        UUID requisition = applications.requisitionOf(posting);
        UUID hr = applications.staff("Ngô Văn HR", "hr@staff.test");
        UUID recruiter = applications.staff("Lê Thị Recruiter", "recruiter@staff.test");
        applications.assign(requisition, recruiter, "PRIMARY", hr);
        var stored = applications.referred(referrals, transactions, posting,
                new ApplicationSubmission(ApplicationTestSupport.FULL_NAME, "Lan.Nguyen@Example.TEST",
                        ApplicationTestSupport.PHONE, null, null),
                applications.pdf("CV Nguyễn Văn A.pdf"), referrer, ApplicationTestSupport.REFERRAL_NOTE);
        applications.stage(stored.applicationId(), ApplicationStage.SCREENING);
        var publicOne = applications.stored(posting, new ApplicationSubmission("Bùi Quốc Việt", "viet@example.test",
                "0987654321", null, null));

        var response = applications.myReferrals(applications.token(tokens, referrer));
        JsonNode mine = list(response);
        assertThat(mine.size()).isEqualTo(1);
        PublicResponseContract.assertJsonKeys(mine.get(0), ITEM_KEYS);
        assertItem(mine.get(0), stored.applicationId(), ApplicationTestSupport.FULL_NAME, "Lập trình viên Java",
                ApplicationTestSupport.NOW, "IN_REVIEW", "Đang xem xét", ApplicationTestSupport.REFERRAL_NOTE);
        assertThat(response.body()).doesNotContain("lan.nguyen", "Lan.Nguyen", "example.test",
                ApplicationTestSupport.PHONE, "CV Nguy", ".pdf", "SCREENING", "Sàng lọc", "INTERNAL_REFERRAL",
                "Giới thiệu nội bộ", requisition.toString(), posting.toString(), stored.candidateId().toString(),
                "Lê Thị Recruiter", "Ngô Văn HR", REFERRER_NAME, REFERRER_EMAIL, "Bùi Quốc Việt",
                publicOne.applicationId().toString(), stored.trackingCode(), "tracking", "#ma=", "#rut=", "tra-cuu");
    }

    // The referral API's 201 (task 351) is the very item the list shows afterwards, so the frontend's create and list
    // paths read the same names.
    @Test
    void theCreatedReferralIsTheListItem() throws Exception {
        String token = applications.token(tokens, referrer);
        var created = applications.refer(token, posting, "lan.nguyen@example.test");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode item = json.readTree(created.body());
        PublicResponseContract.assertJsonKeys(item, ITEM_KEYS);

        JsonNode mine = list(applications.myReferrals(token));
        assertThat(mine.size()).isEqualTo(1);
        assertThat(mine.get(0)).isEqualTo(item);
    }

    // Holding a candidate read (the requisition's PRIMARY recruiter: SCOPED; an HR manager: ALL) or having referred
    // nothing gives an empty array, whoever else referred into the same requisition.
    @Test
    void aNonReferrerGetsAnEmptyList() throws Exception {
        referAt(0, posting, "Nguyễn Văn A", "a@example.test", referrer, "Ghi chú");
        UUID recruiter = applications.account("Lê Thị Recruiter", "recruiter@staff.test", Role.RECRUITER);
        applications.assign(applications.requisitionOf(posting), recruiter, "PRIMARY", referrer);
        UUID manager = applications.account("Ngô Văn HR", "hr@staff.test", Role.HR_MANAGER);
        clock.set(READ_AT);

        for (UUID caller : List.of(recruiter, manager, otherReferrer)) {
            var response = applications.myReferrals(applications.token(tokens, caller));
            assertThat(list(response).isEmpty()).isTrue();
            assertThat(response.body()).isEqualTo("[]");
        }
    }

    // NO_ROLE holds no SELF_PROFILE_READ: 403 at the matcher. No token, a malformed one, or the token of an account
    // locked since: 401. Every answer is no-store.
    @Test
    void withoutARoleOrATokenNothingIsListed() throws Exception {
        referAt(0, posting, "Nguyễn Văn A", "a@example.test", referrer, "Ghi chú");
        UUID nobody = applications.account("Không vai trò", "none@staff.test");
        assertRefused(applications.myReferrals(applications.token(tokens, nobody)), 403, "FORBIDDEN");
        assertRefused(applications.myReferrals(null), 401, "UNAUTHORIZED");
        assertRefused(applications.get(ApplicationTestSupport.MY_REFERRALS, Map.of("Authorization", "Bearer not-a-jwt")),
                401, "UNAUTHORIZED");

        String token = applications.token(tokens, referrer);
        assertThat(list(applications.myReferrals(token)).size()).isEqualTo(1);
        jdbc.update("UPDATE user_accounts SET enabled = false WHERE id = ?", referrer);
        assertRefused(applications.myReferrals(token), 401, "UNAUTHORIZED");
    }

    // The service checks the reader again, without the matcher: the token's expiry against the clock (401
    // SESSION_INVALID), an active session and account (a session revoked, an account disabled or locked by an
    // administrator after the token was decoded: 401 through BadCredentialsException), and SELF_PROFILE_READ read again
    // from role_permissions (403), before any row is read.
    @Test
    void theServiceChecksTheReaderAgain() throws Exception {
        referAt(0, posting, "Nguyễn Văn A", "a@example.test", referrer, "Ghi chú");
        clock.set(READ_AT);
        Jwt reader = decoder.decode(applications.token(tokens, referrer));
        assertThat(statuses.myReferrals(reader)).hasSize(1);

        Jwt nobody = decoder.decode(applications.token(tokens, applications.account("Không vai trò", "none@staff.test")));
        assertThatThrownBy(() -> statuses.myReferrals(nobody)).isInstanceOf(AccessDeniedException.class);

        assertThat(jdbc.update("DELETE FROM role_permissions WHERE role_code = 'INTERVIEWER'"
                + " AND permission_code = 'SELF_PROFILE_READ'")).isEqualTo(1);
        try {
            assertThatThrownBy(() -> statuses.myReferrals(reader)).isInstanceOf(AccessDeniedException.class);
        } finally {
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('INTERVIEWER', 'SELF_PROFILE_READ')"
                    + " ON CONFLICT DO NOTHING");
        }
        assertThat(statuses.myReferrals(reader)).hasSize(1);

        assertRefusedWhile(reader,
                () -> assertThat(jdbc.update("UPDATE user_accounts SET enabled = false WHERE id = ?", referrer))
                        .isEqualTo(1),
                () -> jdbc.update("UPDATE user_accounts SET enabled = true WHERE id = ?", referrer));
        assertRefusedWhile(reader,
                () -> assertThat(jdbc.update("UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Review',"
                        + " admin_locked_by = ? WHERE id = ?", Timestamp.from(READ_AT), otherReferrer, referrer))
                        .isEqualTo(1),
                () -> jdbc.update("UPDATE user_accounts SET admin_locked_at = NULL, admin_lock_reason = NULL,"
                        + " admin_locked_by = NULL WHERE id = ?", referrer));
        UUID session = UUID.fromString(reader.getId());
        assertRefusedWhile(reader,
                () -> assertThat(jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE id = ?",
                        Timestamp.from(READ_AT), session)).isEqualTo(1),
                () -> jdbc.update("UPDATE auth_sessions SET revoked_at = NULL WHERE id = ?", session));

        clock.set(reader.getExpiresAt());
        assertThatThrownBy(() -> statuses.myReferrals(reader)).isInstanceOf(AuthenticationFailureException.class);
    }

    // The reader's still-valid token is refused by the service itself while the change holds, and lists again once it
    // is undone.
    private void assertRefusedWhile(Jwt reader, Runnable change, Runnable restore) {
        change.run();
        try {
            assertThatThrownBy(() -> statuses.myReferrals(reader)).isInstanceOf(BadCredentialsException.class);
        } finally {
            restore.run();
        }
        assertThat(statuses.myReferrals(reader)).hasSize(1);
    }

    // A referral by this referrer, stored by the production storage with the clock at NOW + minutes.
    private UUID referAt(int minutes, UUID postingId, String fullName, String email, UUID by, String note) {
        clock.set(minute(minutes));
        return applications.referred(referrals, transactions, postingId,
                new ApplicationSubmission(fullName, email, ApplicationTestSupport.PHONE, null, null), applications.pdf(),
                by, note).applicationId();
    }

    private static Instant minute(int minutes) {
        return ApplicationTestSupport.NOW.plus(Duration.ofMinutes(minutes));
    }

    // Task 340's table, written out here with its own exhaustive switch, so a change on either side fails.
    private static String[] publicStatus(ApplicationStage stage) {
        return switch (stage) {
            case NEW -> new String[] {"RECEIVED", "Đã nhận"};
            case SCREENING -> new String[] {"IN_REVIEW", "Đang xem xét"};
            case INTERVIEW, OFFER -> new String[] {"INTERVIEWING", "Mời phỏng vấn"};
            case HIRED, ONBOARDED, REJECTED, WITHDRAWN -> new String[] {"COMPLETED", "Kết thúc"};
        };
    }

    private JsonNode list(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control"))
                .hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(value -> assertThat(value).startsWith("application/json"));
        JsonNode body = json.readTree(response.body());
        assertThat(body.isArray()).as(response.body()).isTrue();
        for (JsonNode item : body) PublicResponseContract.assertJsonKeys(item, ITEM_KEYS);
        return body;
    }

    private void assertRefused(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo(code);
        assertThat(response.headers().firstValue("Cache-Control"))
                .hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(response.body()).doesNotContain("Nguyễn Văn A", "a@example.test", "Ghi chú");
    }

    private static void assertItem(JsonNode item, UUID id, String candidateName, String jobTitle, Instant submittedAt,
                                   String status, String statusName, String note) {
        assertThat(item.get("id").asString()).isEqualTo(id.toString());
        assertThat(item.get("candidateName").asString()).isEqualTo(candidateName);
        assertThat(item.get("jobTitle").asString()).isEqualTo(jobTitle);
        assertThat(Instant.parse(item.get("submittedAt").asString())).isEqualTo(submittedAt);
        assertThat(item.get("status").asString()).isEqualTo(status);
        assertThat(item.get("statusName").asString()).isEqualTo(statusName);
        assertThat(item.get("referralNote").asString()).isEqualTo(note);
    }

    private static List<UUID> ids(JsonNode list) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode item : list) ids.add(UUID.fromString(item.get("id").asString()));
        return ids;
    }

    private static JsonNode item(JsonNode list, UUID id) {
        for (JsonNode item : list) {
            if (item.get("id").asString().equals(id.toString())) return item;
        }
        throw new AssertionError("No item " + id + " in " + list);
    }

    private static List<String> values(JsonNode list) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : list) item.properties().forEach(entry -> values.add(entry.getValue().asString()));
        return values;
    }
}
