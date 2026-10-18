package vn.ttcs.recruitment.auth;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
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
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.ApplicationSubmission;
import vn.ttcs.recruitment.application.CvValidator;
import vn.ttcs.recruitment.application.ValidatedCv;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 362 (story S4-09, "CV xem trước ngay trong trang"; "chỉ recruiter phụ trách và hiring manager của vị trí đó mở
 * được; mọi truy cập được ghi nhật ký"): GET /api/v1/applications/{id}/cv over real HTTP and PostgreSQL, with task
 * 363's access rule and log, and the active-content refusal of the public intake.
 * <p>
 * Fixture: Lan applied to "Lập trình viên Java" (Phòng CNTT, managed by itHead, PRIMARY recruiter primary) with a PDF and
 * to "Kế toán tổng hợp" (Phòng Tài chính, managed by financeHead, no team) with a DOCX. Tokens come from real session
 * rows, so the seeded permissions decide the scope. Vietnamese file names are built from code points.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-intake.enabled=true", "app.application-intake.trusted-proxies=",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.page-url=https://portal.example.test/tra-cuu",
        "app.application-confirmation.poll-delay-ms=3600000", "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-tracking.cleanup-delay-ms=3600000",
        "spring.mail.host=127.0.0.1", "spring.mail.port=1"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CandidateCvIntegrationTest {
    private static final Instant NOW = ApplicationTestSupport.NOW;
    private static final String PDF = "application/pdf";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String BOUNDARY = "candidate-cv-boundary";
    // "Nguyễn Thị Lan": ễ U+1EC5, ị U+1ECB.
    private static final String LAN = "Nguy" + Character.toString(0x1EC5) + "n Th" + Character.toString(0x1ECB) + " Lan";
    private static final String LAN_ENCODED = "Nguy%E1%BB%85n%20Th%E1%BB%8B%20Lan";

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
    private UUID admin, hr, primary, otherRecruiter, itHead, noRole;
    private UUID javaPosting;
    private ApplicationTestSupport.Stored lanJava, lanAccounting;
    private ValidatedCv javaCv, accountingCv;

    @BeforeEach
    void fixture() {
        clock.set(NOW);
        port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, port).reset();

        javaPosting = applications.publishedPosting("Lập trình viên Java");
        UUID accountingPosting = applications.publishedPosting("Kế toán tổng hợp");
        UUID javaRequisition = applications.requisitionOf(javaPosting);

        admin = account("Quản trị viên", "admin@example.test", Role.ADMIN);
        hr = account("Nguyễn Thị Hoa", "hr@example.test", Role.HR_MANAGER);
        primary = account("Trần Văn Bình", "primary@example.test", Role.RECRUITER);
        otherRecruiter = account("Đỗ Văn Dũng", "other-recruiter@example.test", Role.RECRUITER);
        itHead = account("Phạm Văn Cường", "it-head@example.test", Role.HIRING_MANAGER);
        UUID financeHead = account("Vũ Văn Hải", "finance-head@example.test", Role.HIRING_MANAGER);
        noRole = account("Không vai trò", "no-role@example.test");
        jdbc.update("UPDATE departments SET name = 'Phòng CNTT', manager_user_id = ? WHERE id = ?", itHead,
                departmentOf(javaRequisition));
        jdbc.update("UPDATE departments SET name = 'Phòng Tài chính', manager_user_id = ? WHERE id = ?", financeHead,
                departmentOf(applications.requisitionOf(accountingPosting)));
        applications.assign(javaRequisition, primary, "PRIMARY", hr);

        javaCv = applications.pdf("CV " + LAN + ".pdf");
        accountingCv = applications.docx("CV " + LAN + ".docx");
        lanJava = applications.stored(javaPosting, new ApplicationSubmission(LAN, "lan@example.test",
                ApplicationTestSupport.PHONE, null, null), javaCv);
        lanAccounting = applications.stored(accountingPosting, new ApplicationSubmission(LAN, "lan@example.test",
                ApplicationTestSupport.PHONE, null, null), accountingCv);
        assertThat(lanAccounting.candidateId()).isEqualTo(lanJava.candidateId());
    }

    // A PDF: 200 with the exact bytes, the stored type, inline with an ASCII name first, and every protective header.
    // One GRANTED CV row names the application.
    @Test
    void aPdfIsServedInlineWithItsBytesAndEveryHeader() throws Exception {
        var response = get(cvPath(lanJava.applicationId()), token(hr));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(javaCv.content());
        var headers = response.headers();
        assertThat(headers.allValues("Content-Type")).containsExactly(PDF);
        assertThat(headers.firstValueAsLong("Content-Length").orElseThrow()).isEqualTo(javaCv.sizeBytes());
        assertThat(headers.allValues("Content-Disposition")).containsExactly(
                "inline; filename=\"CV Nguyen Thi Lan.pdf\"; filename*=UTF-8''CV%20" + LAN_ENCODED + ".pdf");
        assertThat(headers.allValues("X-Content-Type-Options")).containsExactly("nosniff");
        assertThat(headers.allValues("Content-Security-Policy")).containsExactly("default-src 'none'; sandbox");
        assertNoStore(response);
        assertThat(events()).containsExactly(event(hr, lanJava.applicationId(), "ALL", "GRANTED"));
        assertThat(jdbc.queryForObject("SELECT occurred_at FROM candidate_access_events", Timestamp.class))
                .isEqualTo(Timestamp.from(NOW));
    }

    // A DOCX is only ever downloaded: attachment, with the DOCX type.
    @Test
    void aDocxIsAnAttachmentWithTheDocxType() throws Exception {
        var response = get(cvPath(lanAccounting.applicationId()), token(admin));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(accountingCv.content());
        assertThat(response.headers().allValues("Content-Type")).containsExactly(DOCX);
        assertThat(response.headers().allValues("Content-Disposition")).containsExactly(
                "attachment; filename=\"CV Nguyen Thi Lan.docx\"; filename*=UTF-8''CV%20" + LAN_ENCODED + ".docx");
        assertThat(response.headers().allValues("X-Content-Type-Options")).containsExactly("nosniff");
        assertThat(events()).containsExactly(event(admin, lanAccounting.applicationId(), "ALL", "GRANTED"));
    }

    // The stored type decides: a PDF stored under a .docx name (only possible with SQL) stays an inline PDF.
    @Test
    void theStoredTypeWinsOverAMisleadingFileName() throws Exception {
        jdbc.update("UPDATE application_cvs SET filename = 'Hồ sơ.docx' WHERE application_id = ?",
                lanJava.applicationId());

        var response = get(cvPath(lanJava.applicationId()), token(hr));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().allValues("Content-Type")).containsExactly(PDF);
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow())
                .startsWith("inline; filename=\"Ho so.pdf\"; filename*=UTF-8''").endsWith(".pdf");
    }

    // The PRIMARY recruiter and the department's manager read the Java CV (SCOPED); the frontend's origin may read the
    // file name (Content-Disposition is exposed to CORS).
    @Test
    void theAssignedRecruiterAndTheHiringManagerReadTheirCandidatesCv() throws Exception {
        for (UUID reader : List.of(primary, itHead)) {
            var response = get(cvPath(lanJava.applicationId()), token(reader), Map.of("Origin", "http://localhost:5173"));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(javaCv.content());
            assertThat(response.headers().firstValue("Access-Control-Expose-Headers").orElseThrow())
                    .contains("Content-Disposition");
        }
        assertThat(events()).containsExactly(event(primary, lanJava.applicationId(), "SCOPED", "GRANTED"),
                event(itHead, lanJava.applicationId(), "SCOPED", "GRANTED"));
    }

    // Outside the scope: 403 FORBIDDEN without the file, and one committed DENIED CV row naming the application, also
    // when that application has no CV (the scope is checked before the file).
    @Test
    void aCvOutsideTheScopeIs403AndTheRefusalIsLogged() throws Exception {
        var refused = get(cvPath(lanAccounting.applicationId()), token(primary));
        assertForbidden(refused);
        var other = get(cvPath(lanJava.applicationId()), token(otherRecruiter));
        assertForbidden(other);
        jdbc.update("DELETE FROM application_cvs WHERE application_id = ?", lanAccounting.applicationId());
        assertForbidden(get(cvPath(lanAccounting.applicationId()), token(primary)));

        assertThat(events()).containsExactly(
                event(primary, lanAccounting.applicationId(), "SCOPED", "DENIED"),
                event(otherRecruiter, lanJava.applicationId(), "SCOPED", "DENIED"),
                event(primary, lanAccounting.applicationId(), "SCOPED", "DENIED"));
    }

    // Unknown application: the same 404 for every permitted reader, before the scope; an application without a candidate
    // link (only possible with SQL) is unknown too; a malformed id is 400. Nothing is logged.
    @Test
    void anUnknownApplicationIs404ForEveryReaderAndNotLogged() throws Exception {
        jdbc.update("DELETE FROM candidate_applications WHERE application_id = ?", lanAccounting.applicationId());
        for (UUID reader : List.of(hr, primary, otherRecruiter, itHead)) {
            for (UUID application : List.of(UUID.randomUUID(), lanAccounting.applicationId())) {
                var response = get(cvPath(application), token(reader));
                assertThat(response.statusCode()).isEqualTo(404);
                JsonNode error = json.readTree(response.body());
                assertThat(error.path("code").asText()).isEqualTo("APPLICATION_NOT_FOUND");
                assertThat(error.path("message").asText()).isEqualTo("Không tìm thấy hồ sơ ứng tuyển.");
                assertNoStore(response);
            }
        }
        var malformed = get("/api/v1/applications/not-a-uuid/cv", token(hr));
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(json.readTree(malformed.body()).path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(events()).isEmpty();
    }

    // A readable application without a CV row: 404 APPLICATION_CV_NOT_FOUND, nothing logged (nothing was shown).
    @Test
    void aReadableApplicationWithoutACvIs404() throws Exception {
        jdbc.update("DELETE FROM application_cvs WHERE application_id = ?", lanJava.applicationId());
        for (UUID reader : List.of(hr, primary)) {
            var response = get(cvPath(lanJava.applicationId()), token(reader));
            assertThat(response.statusCode()).isEqualTo(404);
            JsonNode error = json.readTree(response.body());
            assertThat(error.path("code").asText()).isEqualTo("APPLICATION_CV_NOT_FOUND");
            assertThat(error.path("message").asText()).isEqualTo("Hồ sơ ứng tuyển này không có tệp CV.");
            assertNoStore(response);
        }
        assertThat(events()).isEmpty();
    }

    // Without a CANDIDATES read permission: 403 for an existing and an unknown id alike; anonymous: 401. Not logged.
    @Test
    void noPermissionIs403AndNoTokenIs401() throws Exception {
        String token = token(noRole);
        for (UUID application : List.of(lanJava.applicationId(), UUID.randomUUID())) {
            assertForbidden(get(cvPath(application), token));
        }
        var anonymous = get(cvPath(lanJava.applicationId()), null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(json.readTree(anonymous.body()).path("code").asText()).isEqualTo("UNAUTHORIZED");
        assertNoStore(anonymous);
        assertThat(events()).isEmpty();
    }

    // Every read logs again; a second reader of the same CV adds its own row.
    @Test
    void everyGrantedReadIsLogged() throws Exception {
        get(cvPath(lanJava.applicationId()), token(hr));
        get(cvPath(lanJava.applicationId()), token(hr));
        get(cvPath(lanJava.applicationId()), token(primary));

        assertThat(events()).containsExactly(event(hr, lanJava.applicationId(), "ALL", "GRANTED"),
                event(hr, lanJava.applicationId(), "ALL", "GRANTED"),
                event(primary, lanJava.applicationId(), "SCOPED", "GRANTED"));
    }

    // The public intake refuses a DOCX with a remote template before anything is stored, without echoing the file
    // name; the same document without the template is accepted (201), so the refusal is the content's.
    @Test
    void thePublicIntakeRefusesADocxWithARemoteTemplate() throws Exception {
        long before = count("job_applications");
        byte[] plain = plainDocx();
        byte[] remote = withRemoteTemplate(plain);

        var refused = submit("remote@example.test", "Mau CV tu xa.docx", remote);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
        JsonNode error = json.readTree(refused.body());
        assertThat(error.path("code").asText()).isEqualTo("APPLICATION_CV_ACTIVE_CONTENT");
        assertThat(error.path("message").asText()).startsWith("CV có nội dung tự động mở tệp");
        assertThat(refused.body()).doesNotContain("Mau CV", "example.test", "attachedTemplate");
        assertThat(count("job_applications")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_applications WHERE normalized_email = ?",
                Integer.class, "remote@example.test")).isZero();

        var accepted = submit("plain@example.test", "CV.docx", plain);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        assertThat(count("job_applications")).isEqualTo(before + 1);
    }

    private HttpResponse<String> submit(String email, String filename, byte[] cv) throws Exception {
        var body = new ByteArrayOutputStream();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("fullName", "Nguyễn Văn A");
        fields.put("email", email);
        fields.put("phone", ApplicationTestSupport.PHONE);
        for (var field : fields.entrySet()) {
            body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + field.getKey()
                    + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"cv\"; filename=\"" + filename
                + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(cv);
        body.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return applications.post("/api/v1/public/job-postings/" + javaPosting + "/applications",
                HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()),
                "multipart/form-data; boundary=" + BOUNDARY, Map.of());
    }

    private static byte[] plainDocx() throws Exception {
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Candidate CV");
            document.write(output);
            return output.toByteArray();
        }
    }

    // The same document with an External attachedTemplate on a web host, in word/_rels/settings.xml.rels.
    private static byte[] withRemoteTemplate(byte[] docx) throws Exception {
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId362\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                + "relationships/attachedTemplate\" Target=\"https://templates.example.test/cv.dotm\""
                + " TargetMode=\"External\"/></Relationships>";
        var output = new ByteArrayOutputStream();
        try (var input = new ZipInputStream(new ByteArrayInputStream(docx)); var zip = new ZipOutputStream(output)) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                byte[] bytes = input.readAllBytes();
                if (!entry.getName().equals("word/_rels/settings.xml.rels")) {
                    zip.putNextEntry(new ZipEntry(entry.getName()));
                    zip.write(bytes);
                    zip.closeEntry();
                }
            }
            zip.putNextEntry(new ZipEntry("word/_rels/settings.xml.rels"));
            zip.write(rels.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    private void assertForbidden(HttpResponse<byte[]> response) throws Exception {
        assertThat(response.statusCode()).as(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(403);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(response.headers().firstValue("Content-Disposition")).isEmpty();
        assertNoStore(response);
    }

    private static String cvPath(UUID application) {
        return "/api/v1/applications/" + application + "/cv";
    }

    private HttpResponse<byte[]> get(String path, String token) throws Exception {
        return get(path, token, Map.of());
    }

    private HttpResponse<byte[]> get(String path, String token, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).GET();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        headers.forEach(builder::header);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static void assertNoStore(HttpResponse<?> response) {
        assertThat(response.headers().allValues("Cache-Control")).as("Cache-Control").anyMatch(
                value -> value.contains("no-store"));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
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
        row.put("access_kind", "CV");
        row.put("access_scope", scope);
        row.put("outcome", outcome);
        return row;
    }
}
