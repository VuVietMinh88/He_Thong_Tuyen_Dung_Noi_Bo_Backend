package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.ApplicationSubmission;
import vn.ttcs.recruitment.application.CvValidator;
import vn.ttcs.recruitment.application.ValidatedCv;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 361 (story S4-09, "Một trang gồm thông tin cá nhân, CV xem trước…, lịch sử ứng tuyển các vị trí, nhãn và ghi
 * chú; hiển thị đầy đủ các lần ứng tuyển trước đây của cùng người này"): GET /api/v1/candidates/{id}/unified-profile
 * over real HTTP and PostgreSQL, with task 363's access rule and log.
 * <p>
 * Fixture: three postings in three departments (Phòng CNTT managed by itHead, Phòng Kiểm thử by qaHead, Phòng Tài chính
 * by financeHead). The Java requisition has a PRIMARY (primary) and a SUPPORTING (supporting) recruiter; the other two
 * have no team. Lan applied to all three, always typing LAN_TYPED, with one name and one phone: Kế toán in March (PDF,
 * no source), Java in June (DOCX, source LinkedIn, cover letter), Kiểm thử now (PDF). Tokens come from real session
 * rows, so the seeded permissions (with V33_1 for APPROVER) decide the scope.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.page-url=https://portal.example.test/tra-cuu",
        "app.application-confirmation.poll-delay-ms=3600000", "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-tracking.cleanup-delay-ms=3600000",
        "spring.mail.host=127.0.0.1", "spring.mail.port=1"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CandidateProfileIntegrationTest {
    private static final Instant NOW = ApplicationTestSupport.NOW;
    private static final Instant MARCH = Instant.parse("2026-03-02T02:00:00Z");
    private static final Instant JUNE = Instant.parse("2026-06-10T04:30:00Z");
    private static final String LAN_NAME = "Nguyễn Thị Lan";
    private static final String LAN_PHONE = "0912345678";
    // What Lan typed every time; the normalized form below is never shown.
    private static final String LAN_TYPED = "Lan.Nguyen@Example.TEST";
    private static final String LAN_NORMALIZED = "lan.nguyen@example.test";
    private static final String COVER_LETTER = "Tôi mong muốn được đóng góp cho đội phát triển Java.";
    private static final String DOCX_NAME = "CV Nguyễn Thị Lan.docx";
    private static final List<String> HEADER_KEYS = List.of("id", "fullName", "email", "phone", "contactMismatch",
            "currentApplicationId", "createdAt", "updatedAt", "tags", "notes", "applications");
    private static final List<String> FULL_ROW_KEYS = List.of("id", "isCurrent", "jobPostingId", "jobTitle",
            "departmentName", "appliedAt", "stage", "stageName", "fullName", "email", "phone", "source", "recruiterId",
            "recruiterName", "hiringManagerId", "hiringManagerName", "coverLetter", "cvFileName", "cvFileType",
            "cvFileSize");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private Environment environment;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationStorageService storage;
    @Autowired private CvValidator validator;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private TokenService tokens;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private ApplicationTestSupport applications;
    private int port;
    private UUID admin, hr, primary, supporting, otherRecruiter, itHead, qaHead, financeHead, approver, interviewer;
    private UUID noRole;
    private UUID javaPosting, testingPosting, accountingPosting;
    private UUID javaRequisition, testingRequisition, accountingRequisition;
    private UUID javaDepartment, linkedIn;
    private ApplicationTestSupport.Stored lanAccounting, lanJava, lanTesting;
    private ValidatedCv javaCv;

    @BeforeEach
    void fixture() {
        clock.set(NOW);
        port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, port).reset();

        javaPosting = applications.publishedPosting("Lập trình viên Java");
        testingPosting = applications.publishedPosting("Kiểm thử phần mềm");
        accountingPosting = applications.publishedPosting("Kế toán tổng hợp");
        javaRequisition = applications.requisitionOf(javaPosting);
        testingRequisition = applications.requisitionOf(testingPosting);
        accountingRequisition = applications.requisitionOf(accountingPosting);

        admin = account("Quản trị viên", "admin@example.test", Role.ADMIN);
        hr = account("Nguyễn Thị Hoa", "hr@example.test", Role.HR_MANAGER);
        primary = account("Trần Văn Bình", "primary@example.test", Role.RECRUITER);
        supporting = account("Lê Thị Cúc", "supporting@example.test", Role.RECRUITER);
        otherRecruiter = account("Đỗ Văn Dũng", "other-recruiter@example.test", Role.RECRUITER);
        itHead = account("Phạm Văn Cường", "it-head@example.test", Role.HIRING_MANAGER);
        qaHead = account("Hoàng Thị Giang", "qa-head@example.test", Role.HIRING_MANAGER);
        financeHead = account("Vũ Văn Hải", "finance-head@example.test", Role.HIRING_MANAGER);
        approver = account("Người duyệt", "approver@example.test", Role.APPROVER);
        interviewer = account("Người phỏng vấn", "interviewer@example.test", Role.INTERVIEWER);
        noRole = account("Không vai trò", "no-role@example.test");

        javaDepartment = departmentOf(javaRequisition);
        jdbc.update("UPDATE departments SET name = 'Phòng CNTT', manager_user_id = ? WHERE id = ?", itHead,
                javaDepartment);
        jdbc.update("UPDATE departments SET name = 'Phòng Kiểm thử', manager_user_id = ? WHERE id = ?", qaHead,
                departmentOf(testingRequisition));
        jdbc.update("UPDATE departments SET name = 'Phòng Tài chính', manager_user_id = ? WHERE id = ?", financeHead,
                departmentOf(accountingRequisition));
        applications.assign(javaRequisition, primary, "PRIMARY", hr);
        applications.assign(javaRequisition, supporting, "SUPPORTING", hr);
        linkedIn = applications.source("LinkedIn");

        javaCv = applications.docx(DOCX_NAME);
        lanAccounting = storedAt(MARCH, accountingPosting,
                new ApplicationSubmission(LAN_NAME, LAN_TYPED, LAN_PHONE, null, null), applications.pdf());
        lanJava = storedAt(JUNE, javaPosting,
                new ApplicationSubmission(LAN_NAME, LAN_TYPED, LAN_PHONE, linkedIn, COVER_LETTER), javaCv);
        lanTesting = storedAt(NOW, testingPosting,
                new ApplicationSubmission(LAN_NAME, LAN_TYPED, LAN_PHONE, null, null), applications.pdf());
        assertThat(List.of(lanJava.candidateId(), lanTesting.candidateId())).containsOnly(lanAccounting.candidateId());
    }

    // No token: the matcher answers 401 before anything is read; no-store like every answer of this route.
    @Test
    void anAnonymousCallerGets401() throws Exception {
        var response = get(profilePath(lanJava.candidateId()), null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("UNAUTHORIZED");
        assertNoStore(response);
        assertThat(events()).isEmpty();
    }

    // Without a CANDIDATES read permission the answer is 403 for an existing and an unknown id alike (403 before 404),
    // and nothing is logged: nothing was looked up.
    @Test
    void aCallerWithoutACandidatesPermissionGets403ForEveryId() throws Exception {
        String token = token(noRole);
        for (UUID id : List.of(lanJava.candidateId(), UUID.randomUUID())) {
            var response = get(profilePath(id), token);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
            assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("FORBIDDEN");
            assertNoStore(response);
        }
        assertThat(events()).isEmpty();
    }

    // Every permitted role gets the same 404 for an unknown profile, and for a profile whose applications were all
    // deleted; neither is logged. A malformed id is 400.
    @Test
    void anUnknownProfileIs404ForEveryPermittedRole() throws Exception {
        UUID empty = UUID.randomUUID();
        jdbc.update("INSERT INTO candidates (id, normalized_email, created_at) VALUES (?, 'empty@example.test', ?)",
                empty, Timestamp.from(MARCH));
        for (UUID reader : List.of(admin, hr, primary, itHead, interviewer, approver)) {
            String token = token(reader);
            for (UUID id : List.of(UUID.randomUUID(), empty)) {
                var response = get(profilePath(id), token);
                assertThat(response.statusCode()).as("%s reads %s: %s", reader, id, response.body()).isEqualTo(404);
                JsonNode error = json.readTree(response.body());
                assertThat(error.path("code").asText()).isEqualTo("CANDIDATE_NOT_FOUND");
                assertThat(error.path("message").asText()).isEqualTo("Không tìm thấy hồ sơ ứng viên.");
                assertNoStore(response);
            }
        }
        var malformed = get("/api/v1/candidates/not-a-uuid/unified-profile", token(hr));
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json.readTree(malformed.body()).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(events()).isEmpty();
    }

    // A scoped reader with nothing readable in an existing profile gets 403 and exactly one committed DENIED PROFILE
    // row without an application, whatever the role.
    @Test
    void aScopedReaderWithNothingReadableGets403AndOneDeniedRow() throws Exception {
        for (UUID reader : List.of(otherRecruiter, approver, interviewer)) {
            var response = get(profilePath(lanJava.candidateId()), token(reader));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
            assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("FORBIDDEN");
            assertNoStore(response);
        }
        assertThat(events()).containsExactly(
                event(otherRecruiter, null, "SCOPED", "DENIED"),
                event(approver, null, "SCOPED", "DENIED"),
                event(interviewer, null, "SCOPED", "DENIED"));
    }

    // CANDIDATES_READ_ALL reads every application, newest first; the header comes from them all. Every key of the
    // header and of a full row is pinned, with each row's own snapshot, source, staff and CV metadata.
    @Test
    void anAllReaderSeesEveryApplicationNewestFirst() throws Exception {
        for (UUID reader : List.of(hr, admin)) {
            JsonNode profile = profile(lanJava.candidateId(), reader);

            assertThat(profile.propertyNames()).containsExactlyInAnyOrderElementsOf(HEADER_KEYS);
            assertThat(profile.path("id").asText()).isEqualTo(lanJava.candidateId().toString());
            assertThat(profile.path("fullName").asText()).isEqualTo(LAN_NAME);
            assertThat(profile.path("email").asText()).isEqualTo(LAN_TYPED);
            assertThat(profile.path("phone").asText()).isEqualTo(LAN_PHONE);
            assertThat(profile.path("contactMismatch").asBoolean()).isFalse();
            assertThat(profile.path("currentApplicationId").asText()).isEqualTo(lanTesting.applicationId().toString());
            assertThat(profile.path("createdAt").asText()).isEqualTo(MARCH.toString());
            assertThat(profile.path("updatedAt").asText()).isEqualTo(NOW.toString());
            assertThat(profile.path("tags").isArray()).isTrue();
            assertThat(profile.path("tags").size()).isZero();
            assertThat(profile.path("notes").isArray()).isTrue();
            assertThat(profile.path("notes").size()).isZero();
            assertThat(ids(profile)).containsExactly(lanTesting.applicationId(), lanJava.applicationId(),
                    lanAccounting.applicationId());
            assertThat(current(profile)).containsExactly(true, false, false);

            JsonNode java = profile.path("applications").get(1);
            assertThat(java.propertyNames()).containsExactlyInAnyOrderElementsOf(FULL_ROW_KEYS);
            assertThat(java.path("jobPostingId").asText()).isEqualTo(javaPosting.toString());
            assertThat(java.path("jobTitle").asText()).isEqualTo("Lập trình viên Java");
            assertThat(java.path("departmentName").asText()).isEqualTo("Phòng CNTT");
            assertThat(java.path("appliedAt").asText()).isEqualTo(JUNE.toString());
            assertThat(java.path("stage").asText()).isEqualTo("NEW");
            assertThat(java.path("stageName").asText()).isEqualTo("Hồ sơ mới");
            assertThat(java.path("fullName").asText()).isEqualTo(LAN_NAME);
            assertThat(java.path("email").asText()).isEqualTo(LAN_TYPED);
            assertThat(java.path("phone").asText()).isEqualTo(LAN_PHONE);
            assertThat(java.path("source").propertyNames()).containsExactlyInAnyOrder("type", "label", "details");
            assertThat(java.path("source").path("type").asText()).isEqualTo("CAREER_PAGE");
            assertThat(java.path("source").path("label").asText()).isEqualTo("Trang tuyển dụng");
            assertThat(java.path("source").path("details").asText()).isEqualTo("LinkedIn");
            assertThat(java.path("recruiterId").asText()).isEqualTo(primary.toString());
            assertThat(java.path("recruiterName").asText()).isEqualTo("Trần Văn Bình");
            assertThat(java.path("hiringManagerId").asText()).isEqualTo(itHead.toString());
            assertThat(java.path("hiringManagerName").asText()).isEqualTo("Phạm Văn Cường");
            assertThat(java.path("coverLetter").asText()).isEqualTo(COVER_LETTER);
            assertThat(java.path("cvFileName").asText()).isEqualTo(DOCX_NAME);
            assertThat(java.path("cvFileType").asText()).isEqualTo("docx");
            assertThat(java.path("cvFileSize").asInt()).isEqualTo(javaCv.sizeBytes());

            // No team, no source, no cover letter: those keys are left out, never sent as null.
            JsonNode accounting = profile.path("applications").get(2);
            List<String> plainKeys = new ArrayList<>(FULL_ROW_KEYS);
            plainKeys.removeAll(List.of("recruiterId", "recruiterName", "coverLetter"));
            assertThat(accounting.propertyNames()).containsExactlyInAnyOrderElementsOf(plainKeys);
            assertThat(accounting.path("source").propertyNames()).containsExactlyInAnyOrder("type", "label");
            assertThat(accounting.path("cvFileType").asText()).isEqualTo("pdf");
            assertThat(accounting.path("hiringManagerId").asText()).isEqualTo(financeHead.toString());
            assertThat(accounting.path("departmentName").asText()).isEqualTo("Phòng Tài chính");
        }
    }

    // The PRIMARY and the SUPPORTING recruiter of the Java requisition and the manager of its department read the Java
    // application only; the testing department's manager reads the testing application only.
    @Test
    void aScopedReaderSeesOnlyTheApplicationsInItsScope() throws Exception {
        for (UUID reader : List.of(primary, supporting, itHead)) {
            JsonNode profile = profile(lanJava.candidateId(), reader);
            assertThat(profile.propertyNames()).containsExactlyInAnyOrderElementsOf(HEADER_KEYS);
            assertThat(ids(profile)).containsExactly(lanJava.applicationId());
            assertThat(current(profile)).containsExactly(true);
            assertThat(profile.path("currentApplicationId").asText()).isEqualTo(lanJava.applicationId().toString());
            assertThat(profile.path("applications").get(0).propertyNames())
                    .containsExactlyInAnyOrderElementsOf(FULL_ROW_KEYS);
        }
        assertThat(ids(profile(lanJava.candidateId(), qaHead))).containsExactly(lanTesting.applicationId());
    }

    // An application outside the scope adds nothing: not its id, posting, title, department, manager, date or stage,
    // and the header dates come from the readable application although the others are older and newer.
    @Test
    void anApplicationOutsideTheScopeAddsNothing() throws Exception {
        applications.stage(lanTesting.applicationId(), ApplicationStage.OFFER);
        applications.stage(lanAccounting.applicationId(), ApplicationStage.REJECTED);

        var response = get(profilePath(lanJava.candidateId()), token(primary));
        assertThat(response.statusCode()).isEqualTo(200);
        String body = response.body();
        JsonNode profile = json.readTree(body);

        assertThat(profile.path("applications").size()).isEqualTo(1);
        assertThat(profile.path("createdAt").asText()).isEqualTo(JUNE.toString());
        assertThat(profile.path("updatedAt").asText()).isEqualTo(JUNE.toString());
        for (String hidden : List.of(lanTesting.applicationId().toString(), lanAccounting.applicationId().toString(),
                testingPosting.toString(), accountingPosting.toString(), "Kiểm thử phần mềm", "Kế toán tổng hợp",
                "Phòng Kiểm thử", "Phòng Tài chính", qaHead.toString(), financeHead.toString(), "Hoàng Thị Giang",
                "Vũ Văn Hải", MARCH.toString(), NOW.toString(), "OFFER", "REJECTED", "Đề xuất offer", "\"Loại\"")) {
            assertThat(body).as("the scoped body shows %s", hidden).doesNotContain(hidden);
        }
    }

    // A recruiter who plants a public application with someone's email in a posting of their own requisition sees
    // exactly that application in the profile it was linked to, never the person's other applications. HR sees all
    // four, and the planted name and phone blank the header.
    @Test
    void aPlantedApplicationShowsItsRecruiterNothingElse() throws Exception {
        UUID plantPosting = applications.publishedPosting("Chuyên viên tuyển dụng");
        applications.assign(applications.requisitionOf(plantPosting), otherRecruiter, "PRIMARY", hr);
        var planted = storedAt(NOW.plusSeconds(60), plantPosting,
                new ApplicationSubmission("Người Mạo Danh", LAN_NORMALIZED, "0987654321", null, null),
                applications.pdf());
        assertThat(planted.candidateId()).isEqualTo(lanJava.candidateId());

        var response = get(profilePath(lanJava.candidateId()), token(otherRecruiter));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode profile = json.readTree(response.body());
        assertThat(ids(profile)).containsExactly(planted.applicationId());
        for (String hidden : List.of("Lập trình viên Java", "Kiểm thử phần mềm", "Kế toán tổng hợp", LAN_NAME,
                LAN_TYPED, LAN_PHONE, lanJava.applicationId().toString())) {
            assertThat(response.body()).as("the planted view shows %s", hidden).doesNotContain(hidden);
        }

        JsonNode all = profile(lanJava.candidateId(), hr);
        assertThat(ids(all)).containsExactly(planted.applicationId(), lanTesting.applicationId(),
                lanJava.applicationId(), lanAccounting.applicationId());
        assertThat(all.path("contactMismatch").asBoolean()).isTrue();
        assertThat(all.path("fullName").isNull()).isTrue();
        assertThat(all.path("phone").isNull()).isTrue();
    }

    // Contact data is never promoted: one different phone, or one different name, blanks both header values and sets
    // contactMismatch; every row keeps its own values; the header email is the newest row's typed email. The header is
    // computed over the reader's rows only: the recruiter who reads one of them sees its values without a mismatch.
    @Test
    void differentNamesOrPhonesBlankTheHeaderContact() throws Exception {
        var anJune = storedAt(JUNE, javaPosting,
                new ApplicationSubmission("Phạm Văn An", "an@example.test", "0911111111", null, null),
                applications.pdf());
        var anNow = storedAt(NOW, testingPosting,
                new ApplicationSubmission("Phạm Văn An", "AN@Example.Test", "0922222222", null, null),
                applications.pdf());
        var binhJune = storedAt(JUNE, javaPosting,
                new ApplicationSubmission("Lý Văn Bình", "binh@example.test", "0933333333", null, null),
                applications.pdf());
        storedAt(NOW, testingPosting,
                new ApplicationSubmission("Lý Thị Bình", "binh@example.test", "0933333333", null, null),
                applications.pdf());

        JsonNode an = profile(anJune.candidateId(), hr);
        assertThat(an.path("contactMismatch").asBoolean()).isTrue();
        assertThat(an.path("fullName").isNull()).isTrue();
        assertThat(an.path("phone").isNull()).isTrue();
        assertThat(an.propertyNames()).containsExactlyInAnyOrderElementsOf(HEADER_KEYS);
        assertThat(an.path("email").asText()).isEqualTo("AN@Example.Test");
        assertThat(an.path("currentApplicationId").asText()).isEqualTo(anNow.applicationId().toString());
        assertThat(texts(an, "phone")).containsExactly("0922222222", "0911111111");
        assertThat(texts(an, "email")).containsExactly("AN@Example.Test", "an@example.test");

        JsonNode binh = profile(binhJune.candidateId(), hr);
        assertThat(binh.path("contactMismatch").asBoolean()).isTrue();
        assertThat(binh.path("fullName").isNull()).isTrue();
        assertThat(binh.path("phone").isNull()).isTrue();
        assertThat(texts(binh, "fullName")).containsExactly("Lý Thị Bình", "Lý Văn Bình");

        JsonNode scoped = profile(anJune.candidateId(), primary);
        assertThat(ids(scoped)).containsExactly(anJune.applicationId());
        assertThat(scoped.path("contactMismatch").asBoolean()).isFalse();
        assertThat(scoped.path("fullName").asText()).isEqualTo("Phạm Văn An");
        assertThat(scoped.path("phone").asText()).isEqualTo("0911111111");
        assertThat(scoped.path("email").asText()).isEqualTo("an@example.test");
    }

    // The body never carries the CV bytes, any hash, the normalized email, a staff email, the requisition or a CV URL.
    @Test
    void theBodyNeverCarriesFilesHashesStaffEmailsOrInternalIds() throws Exception {
        String body = get(profilePath(lanJava.candidateId()), token(hr)).body();

        List<String> hidden = new ArrayList<>(List.of("cvFileUrl", "requisitionId", "normalizedEmail", "trackingCode",
                "candidateCode", "rating", "sha256", LAN_NORMALIZED, "JVBERi0", "UEsDB",
                javaRequisition.toString(), testingRequisition.toString(), accountingRequisition.toString()));
        for (Map<String, Object> cv : jdbc.queryForList("SELECT content, sha256 FROM application_cvs")) {
            hidden.add(Base64.getEncoder().encodeToString((byte[]) cv.get("content")));
            hidden.add(Base64.getEncoder().encodeToString((byte[]) cv.get("sha256")));
            hidden.add(HexFormat.of().formatHex((byte[]) cv.get("sha256")));
        }
        for (byte[] hash : jdbc.queryForList("SELECT tracking_code_hash FROM job_applications", byte[].class)) {
            hidden.add(Base64.getEncoder().encodeToString(hash));
            hidden.add(HexFormat.of().formatHex(hash));
        }
        hidden.addAll(jdbc.queryForList("SELECT email FROM user_accounts", String.class));
        hidden.add(lanJava.trackingCode());
        for (String value : hidden) {
            assertThat(body).as("the profile shows %s", value).doesNotContain(value);
        }
        assertThat(body).contains(LAN_TYPED);
    }

    // Every successful read writes one GRANTED PROFILE row per returned application with the reader's scope, at the
    // clock's time; a second read writes them again.
    @Test
    void everyReadLogsOneGrantedRowPerReturnedApplication() throws Exception {
        profile(lanJava.candidateId(), hr);
        profile(lanJava.candidateId(), primary);
        profile(lanJava.candidateId(), hr);

        List<Map<String, Object>> expected = new ArrayList<>();
        for (var application : List.of(lanTesting, lanJava, lanAccounting)) {
            expected.add(event(hr, application.applicationId(), "ALL", "GRANTED"));
            expected.add(event(hr, application.applicationId(), "ALL", "GRANTED"));
        }
        expected.add(event(primary, lanJava.applicationId(), "SCOPED", "GRANTED"));
        assertThat(sorted(events())).isEqualTo(sorted(expected));
        assertThat(jdbc.queryForList("SELECT DISTINCT occurred_at FROM candidate_access_events", Timestamp.class))
                .containsExactly(Timestamp.from(NOW));
    }

    // Requisitions filled or cancelled keep their applications in the profile; the title is the snapshot taken when
    // the application arrived, the department name is the current one. Only a PRIMARY counts as the recruiter: a lone
    // SUPPORTING recruiter leaves the recruiter keys out, and a team with both still yields one row.
    @Test
    void closedRequisitionsRenamedPostingsAndTeamsWithoutAPrimary() throws Exception {
        jdbc.update("UPDATE recruitment_requisitions SET status = 'FILLED' WHERE id = ?", accountingRequisition);
        jdbc.update("UPDATE recruitment_requisitions SET status = 'CANCELLED' WHERE id = ?", testingRequisition);
        jdbc.update("UPDATE job_postings SET status = 'CLOSED' WHERE id IN (?, ?)", accountingPosting, testingPosting);
        jdbc.update("UPDATE job_postings SET title = 'Kỹ sư Java (đã đổi tên)' WHERE id = ?", javaPosting);
        jdbc.update("UPDATE departments SET name = 'Khối Công nghệ' WHERE id = ?", javaDepartment);
        applications.assign(testingRequisition, otherRecruiter, "SUPPORTING", hr);

        var response = get(profilePath(lanJava.candidateId()), token(hr));
        assertThat(response.statusCode()).isEqualTo(200);
        assertNoStore(response);
        JsonNode profile = json.readTree(response.body());
        assertThat(ids(profile)).containsExactly(lanTesting.applicationId(), lanJava.applicationId(),
                lanAccounting.applicationId());
        JsonNode testing = profile.path("applications").get(0);
        JsonNode java = profile.path("applications").get(1);
        assertThat(testing.has("recruiterId")).isFalse();
        assertThat(testing.has("recruiterName")).isFalse();
        assertThat(testing.path("hiringManagerId").asText()).isEqualTo(qaHead.toString());
        assertThat(java.path("jobTitle").asText()).isEqualTo("Lập trình viên Java");
        assertThat(java.path("departmentName").asText()).isEqualTo("Khối Công nghệ");
        assertThat(java.path("recruiterId").asText()).isEqualTo(primary.toString());
        assertThat(response.body()).doesNotContain(otherRecruiter.toString()).doesNotContain("Đỗ Văn Dũng");
    }

    private JsonNode profile(UUID candidate, UUID reader) throws Exception {
        var response = get(profilePath(candidate), token(reader));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertNoStore(response);
        return json.readTree(response.body());
    }

    private static String profilePath(UUID candidate) {
        return "/api/v1/candidates/" + candidate + "/unified-profile";
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).GET();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertNoStore(HttpResponse<String> response) {
        assertThat(response.headers().allValues("Cache-Control")).as("Cache-Control").anyMatch(
                value -> value.contains("no-store"));
    }

    private static List<UUID> ids(JsonNode profile) {
        List<UUID> ids = new ArrayList<>();
        profile.path("applications").forEach(application -> ids.add(UUID.fromString(application.path("id").asText())));
        return ids;
    }

    private static List<Boolean> current(JsonNode profile) {
        List<Boolean> current = new ArrayList<>();
        profile.path("applications").forEach(application -> current.add(application.path("isCurrent").asBoolean()));
        return current;
    }

    private static List<String> texts(JsonNode profile, String key) {
        List<String> values = new ArrayList<>();
        profile.path("applications").forEach(application -> values.add(application.path(key).asText()));
        return values;
    }

    private ApplicationTestSupport.Stored storedAt(Instant at, UUID posting, ApplicationSubmission submission,
                                                   ValidatedCv cv) {
        clock.set(at);
        try {
            return applications.stored(posting, submission, cv);
        } finally {
            clock.set(NOW);
        }
    }

    // A session row of this account (SQL) and a signed access token for it, valid for 15 minutes from NOW.
    private String token(UUID account) {
        UUID session = UUID.randomUUID();
        jdbc.update("INSERT INTO auth_sessions (id, user_id, refresh_token_hash, created_at, expires_at)"
                + " VALUES (?, ?, ?, ?, ?)",
                session, account, (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""),
                Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(NOW.plus(Duration.ofDays(7))));
        return tokens.createAccessToken(account, session);
    }

    private UUID account(String fullName, String email, Role... roles) {
        UUID id = applications.staff(fullName, email);
        for (Role role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role.name());
        }
        return id;
    }

    private UUID departmentOf(UUID requisition) {
        return jdbc.queryForObject("SELECT department_id FROM recruitment_requisitions WHERE id = ?", UUID.class,
                requisition);
    }

    private List<Map<String, Object>> events() {
        return jdbc.queryForList("SELECT actor_id, candidate_id, application_id, access_kind, access_scope, outcome"
                + " FROM candidate_access_events ORDER BY id");
    }

    private Map<String, Object> event(UUID actor, UUID application, String scope, String outcome) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("actor_id", actor);
        row.put("candidate_id", lanJava.candidateId());
        row.put("application_id", application);
        row.put("access_kind", "PROFILE");
        row.put("access_scope", scope);
        row.put("outcome", outcome);
        return row;
    }

    private static List<String> sorted(List<Map<String, Object>> rows) {
        return rows.stream().map(Object::toString).sorted().toList();
    }
}
