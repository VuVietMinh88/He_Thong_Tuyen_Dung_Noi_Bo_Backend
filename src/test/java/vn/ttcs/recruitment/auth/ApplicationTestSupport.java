package vn.ttcs.recruitment.auth;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.application.ApplicationReferralRoutes;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.ApplicationSubmission;
import vn.ttcs.recruitment.application.ApplicationTrackingRoutes;
import vn.ttcs.recruitment.application.CvValidator;
import vn.ttcs.recruitment.application.ReferralApplicationStorage;
import vn.ttcs.recruitment.application.ValidatedCv;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tasks 339 ff. (stories S4-05, S4-06, S4-07, S4-09): the SQL and HTTP helpers the candidate-facing tests share.
 * Applications are stored through TRAN DUC LUONG's ApplicationStorageService.store, with a tracking code whose bytes
 * the test knows, so a test can look the application up like a candidate.
 */
final class ApplicationTestSupport {
    static final String LOOKUP = ApplicationTrackingRoutes.LOOKUP;
    static final String WITHDRAW = ApplicationTrackingRoutes.WITHDRAW;
    static final String REFERRALS = ApplicationReferralRoutes.SUBMISSION;
    static final String MY_REFERRALS = ApplicationReferralRoutes.MY;
    static final String REFERRAL_NOTE = "Bạn học cũ, mạnh Java";
    private static final String REFERRAL_BOUNDARY = "referral-http-boundary";
    static final String FULL_NAME = "Nguyễn Văn A";
    static final String PHONE = "0912345678";
    // 10:00 on 15 Oct 2026 in Vietnam; every posting made here takes applications until 31 Dec 2026.
    static final Instant NOW = Instant.parse("2026-10-15T03:00:00Z");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ApplicationStorageService storage;
    private final CvValidator validator;
    private final int port;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final SecureRandom random = new SecureRandom();

    ApplicationTestSupport(JdbcTemplate jdbc, ObjectMapper json, ApplicationStorageService storage, CvValidator validator,
                           int port) {
        this.jdbc = jdbc;
        this.json = json;
        this.storage = storage;
        this.validator = validator;
        this.port = port;
    }

    // An application as a candidate knows it: its id (for SQL only) and the tracking code it received; from task 360
    // also the candidate profile it was linked to (for SQL only).
    record Stored(UUID applicationId, String trackingCode, UUID candidateId) {
        @Override public String toString() { return "Stored[" + applicationId + ", tracking code redacted]"; }
    }

    // Every application table, children first, then both quotas. Call it before JobPostingTestSupport.reset() or
    // ApprovalTestSupport.reset(): they delete postings, requisitions and accounts but not applications, and the
    // applications' RESTRICT keys would make them fail. The candidate profiles (task 360) go first: a profile with a
    // link cannot be deleted, and nothing else deletes a profile. The access log (task 363) has no keys but rejects
    // DELETE (append-only, V33); TRUNCATE fires no row trigger. The stage events (task 345) and the withdrawal notices
    // (task 346, whose recipients are staff accounts) would go with their applications (CASCADE) but are deleted
    // first, like every child table; so are the referrals (task 350, whose referrers are staff accounts too). The
    // referral attempts and their quota rows (task 351) reference staff accounts only and are deleted with them. The
    // candidate merge trail (task 369) references profiles and staff accounts and rejects DELETE (append-only, V68_2),
    // so it is emptied with TRUNCATE before the profiles.
    void reset() {
        // Disposable test databases: remove immutable completed checklists before their application/account parents.
        jdbc.execute("TRUNCATE onboarding_templates CASCADE");
        // Offer history is immutable and references applications; this database belongs only to this test suite.
        jdbc.execute("TRUNCATE offers CASCADE");
        jdbc.execute("TRUNCATE offer_request_buckets");
        // Tests use disposable databases only. Clear the interview tree and its immutable audit rows before
        // deleting applications/accounts; CASCADE also clears the evaluation children introduced by V56.
        jdbc.execute("TRUNCATE interview_sessions CASCADE");
        jdbc.execute("TRUNCATE interview_bulk_requests, interview_invitation_request_buckets");
        jdbc.execute("TRUNCATE candidate_access_events");
        jdbc.update("DELETE FROM referral_attempts");
        jdbc.update("DELETE FROM referral_quotas");
        jdbc.update("DELETE FROM application_notice_outbox");
        jdbc.update("DELETE FROM application_referrals");
        jdbc.update("DELETE FROM application_stage_events");
        jdbc.execute("TRUNCATE candidate_merges CASCADE");
        jdbc.update("DELETE FROM candidate_applications");
        jdbc.update("DELETE FROM candidates");
        jdbc.update("DELETE FROM application_email_outbox");
        jdbc.update("DELETE FROM application_cvs");
        jdbc.update("DELETE FROM job_applications");
        jdbc.update("DELETE FROM application_tracking_ip_quotas");
        jdbc.update("DELETE FROM application_ip_quotas");
    }

    // A PUBLISHED posting with this title (deadline 31 Dec 2026) of a new APPROVED requisition, written with SQL with
    // its own account, department, position and catalog values, so tests may make as many as they need.
    UUID publishedPosting(String title) {
        UUID account = UUID.randomUUID(), department = UUID.randomUUID(), position = UUID.randomUUID();
        UUID requisition = UUID.randomUUID(), posting = UUID.randomUUID();
        Timestamp at = Timestamp.from(NOW);
        jdbc.update("INSERT INTO user_accounts(id,email,password_hash,full_name,enabled,created_at) VALUES (?,?,'test-only','HR',true,?)",
                account, account + "@example.test", at);
        jdbc.update("INSERT INTO departments(id,code,name,manager_user_id) VALUES (?,?,'IT',?)", department, department.toString(), account);
        jdbc.update("INSERT INTO positions(id,code,name,level,salary_min,salary_max,created_at,updated_at) VALUES (?,?,'Developer','Junior',0,0,?,?)",
                position, position.toString(), at, at);
        UUID location = catalog("WORK_LOCATION"), employment = catalog("EMPLOYMENT_TYPE");
        jdbc.update("""
                INSERT INTO recruitment_requisitions(id,position_id,department_id,headcount,reason,status,created_by,created_at,updated_at)
                VALUES (?,?,?,1,'NEW_HEADCOUNT','APPROVED',?,?,?)
                """, requisition, position, department, account, at, at);
        jdbc.update("""
                INSERT INTO job_postings(id,requisition_id,status,revision,title,job_description,candidate_requirements,
                    work_location_id,employment_type_id,application_deadline,show_salary,created_by,created_at,updated_at,
                    published_by,published_at)
                VALUES (?,?,'PUBLISHED',1,?,'Description','Requirements',?,?,?,false,?,?,?,?,?)
                """, posting, requisition, title, location, employment, LocalDate.of(2026, 12, 31), account, at, at, account, at);
        return posting;
    }

    private UUID catalog(String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO recruitment_catalog_items(id,catalog_type,code,name,sort_order,active,created_at,updated_at) VALUES (?,?,?,'Visible',0,true,?,?)",
                id, type, id.toString(), Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    // Stored through the production storage with SHA-256 of 32 known random bytes, like PostgresApplicationReceiptWriter.
    Stored stored(UUID posting, String email) {
        return stored(posting, new ApplicationSubmission(FULL_NAME, email, PHONE, null, null));
    }

    Stored stored(UUID posting, ApplicationSubmission submission) {
        return stored(posting, submission, pdf());
    }

    Stored stored(UUID posting, ApplicationSubmission submission, ValidatedCv cv) {
        byte[] token = new byte[32];
        random.nextBytes(token);
        try {
            String code = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(token);
            var saved = storage.store(posting, submission, cv, MessageDigest.getInstance("SHA-256").digest(token));
            return new Stored(saved.id(), code, candidateOf(saved.id()));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        } finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    // Task 350: a referral of this submission by this referrer, stored through the production
    // ReferralApplicationStorage in its own READ COMMITTED transaction (the referral API's), with a tracking code whose
    // bytes the test knows, like stored(...).
    Stored referred(ReferralApplicationStorage referrals, PlatformTransactionManager transactions, UUID posting,
                    ApplicationSubmission submission, ValidatedCv cv, UUID referrer, String note) {
        byte[] token = new byte[32];
        random.nextBytes(token);
        try {
            String code = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(token);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token);
            var transaction = new TransactionTemplate(transactions);
            transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            var saved = transaction.execute(status -> referrals.store(posting, submission, cv, hash,
                    new ReferralApplicationStorage.ReferralDeclaration(referrer, note, true)));
            return new Stored(saved.id(), code, candidateOf(saved.id()));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        } finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    // A well-formed code that no application has (32 fresh random bytes).
    String unknownCode() {
        byte[] token = new byte[32];
        random.nextBytes(token);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    ValidatedCv pdf() {
        return pdf("cv.pdf");
    }

    ValidatedCv pdf(String filename) {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return validator.validate(filename, new ByteArrayInputStream(output.toByteArray()));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    ValidatedCv docx() {
        return docx("cv.docx");
    }

    ValidatedCv docx(String filename) {
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Candidate CV");
            document.write(output);
            return validator.validate(filename, new ByteArrayInputStream(output.toByteArray()));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // The candidate profile an application is linked to (task 360, V32).
    UUID candidateOf(UUID application) {
        return jdbc.queryForObject("SELECT candidate_id FROM candidate_applications WHERE application_id = ?", UUID.class,
                application);
    }

    UUID requisitionOf(UUID posting) {
        return jdbc.queryForObject("SELECT requisition_id FROM job_postings WHERE id = ?", UUID.class, posting);
    }

    // An enabled staff account with this name and email (SQL, no role): enough to be a requisition's creator, a
    // department's manager or an assigned recruiter.
    UUID staff(String fullName, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts(id,email,password_hash,full_name,enabled,created_at) VALUES (?,?,'test-only',?,true,?)",
                id, email, fullName, Timestamp.from(NOW));
        return id;
    }

    // A recruiter of the requisition (V16), PRIMARY or SUPPORTING, assigned by `by`.
    void assign(UUID requisition, UUID recruiter, String role, UUID by) {
        jdbc.update("INSERT INTO requisition_recruiters(requisition_id,recruiter_id,assignment_role,assigned_by,assigned_at) VALUES (?,?,?,?,?)",
                requisition, recruiter, role, by, Timestamp.from(NOW));
    }

    // An active CANDIDATE_SOURCE catalog value with this name, as the public form offers it.
    UUID source(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO recruitment_catalog_items(id,catalog_type,code,name,sort_order,active,created_at,updated_at) VALUES (?,'CANDIDATE_SOURCE',?,?,0,true,?,?)",
                id, id.toString(), name, Timestamp.from(NOW), Timestamp.from(NOW));
        return id;
    }

    // Tests arrange stages with SQL; ONBOARDED includes the completed checklist required by V64.
    void stage(UUID application, ApplicationStage stage) {
        OnboardingStageTestFixture.stage(jdbc, application, stage);
    }

    int trackingAttempts() {
        return jdbc.queryForObject("SELECT count(*) FROM application_tracking_ip_attempts", Integer.class);
    }

    // The backend waiting behind pid: polled every 10 ms for at most 1.5 s (inside the writers' 2 s lock_timeout and
    // the lookup's 3 s statement_timeout); fails loudly instead of letting a test pass without the wait it describes.
    int awaitWaiterOf(int pid) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMillis(1500).toNanos();
        while (System.nanoTime() < deadline) {
            var waiters = jdbc.queryForList("SELECT pid FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                    Integer.class, pid);
            if (!waiters.isEmpty()) return waiters.getFirst();
            Thread.sleep(10);
        }
        throw new AssertionError("Nothing waited behind backend " + pid + " within 1.5 s");
    }

    static int backendPid(java.sql.Connection connection) throws java.sql.SQLException {
        try (var statement = connection.createStatement(); var row = statement.executeQuery("SELECT pg_backend_pid()")) {
            row.next();
            return row.getInt(1);
        }
    }

    String lookupBody(String trackingCode, String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("trackingCode", trackingCode);
        body.put("email", email);
        return json.writeValueAsString(body);
    }

    HttpResponse<String> lookup(String trackingCode, String email) throws Exception {
        return lookup(trackingCode, email, Map.of());
    }

    HttpResponse<String> lookup(String trackingCode, String email, Map<String, String> headers) throws Exception {
        return post(LOOKUP, lookupBody(trackingCode, email), "application/json", headers);
    }

    // Task 345: the withdrawal body; a null value is sent as JSON null, so tests leave a key out with withdrawBody(Map).
    String withdrawBody(String trackingCode, String email, String reason, Boolean confirm) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("trackingCode", trackingCode);
        body.put("email", email);
        if (reason != null) body.put("reason", reason);
        body.put("confirm", confirm);
        return json.writeValueAsString(body);
    }

    String withdrawBody(Map<String, Object> body) {
        return json.writeValueAsString(body);
    }

    HttpResponse<String> withdraw(String trackingCode, String email, String reason) throws Exception {
        return withdraw(trackingCode, email, reason, Map.of());
    }

    HttpResponse<String> withdraw(String trackingCode, String email, String reason, Map<String, String> headers)
            throws Exception {
        return post(WITHDRAW, withdrawBody(trackingCode, email, reason, true), "application/json", headers);
    }

    HttpResponse<String> post(String path, String body, String contentType, Map<String, String> headers) throws Exception {
        return post(path, HttpRequest.BodyPublishers.ofString(body), contentType, headers);
    }

    HttpResponse<String> post(String path, HttpRequest.BodyPublisher body, String contentType, Map<String, String> headers)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).POST(body);
        if (contentType != null) builder.header("Content-Type", contentType);
        headers.forEach(builder::header);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // Task 351: an enabled staff account with these roles (SQL), and a session row of it with a signed access token,
    // valid for 15 minutes from the test clock (TokenService).
    UUID account(String fullName, String email, Role... roles) {
        UUID id = staff(fullName, email);
        for (Role role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role.name());
        }
        return id;
    }

    String token(TokenService tokens, UUID account) {
        UUID session = UUID.randomUUID();
        jdbc.update("INSERT INTO auth_sessions (id, user_id, refresh_token_hash, created_at, expires_at)"
                + " VALUES (?, ?, ?, ?, ?)",
                session, account, (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""),
                Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(NOW.plus(Duration.ofDays(7))));
        return tokens.createAccessToken(account, session);
    }

    // Task 351: the referral form's text parts, in the frontend's order, for this posting and candidate email.
    static Map<String, String> referralFields(UUID posting, String email) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("jobPostingId", posting.toString());
        fields.put("fullName", FULL_NAME);
        fields.put("email", email);
        fields.put("phone", PHONE);
        fields.put("referralNote", REFERRAL_NOTE);
        fields.put("candidateConsent", "true");
        return fields;
    }

    // The text parts, then the file part cv (left out when cv is null).
    static byte[] referralBody(Map<String, String> fields, String filename, byte[] cv) {
        var output = new ByteArrayOutputStream();
        for (var field : fields.entrySet()) {
            output.writeBytes(("--" + REFERRAL_BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + field.getKey()
                    + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (cv != null) {
            output.writeBytes(("--" + REFERRAL_BOUNDARY + "\r\nContent-Disposition: form-data; name=\"cv\"; filename=\""
                    + filename + "\"\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.writeBytes(cv);
            output.writeBytes("\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        output.writeBytes(("--" + REFERRAL_BOUNDARY + "--\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return output.toByteArray();
    }

    HttpResponse<String> refer(String token, UUID posting, String email) throws Exception {
        return refer(token, referralFields(posting, email), "CV.pdf", pdf().content());
    }

    HttpResponse<String> refer(String token, Map<String, String> fields, String filename, byte[] cv) throws Exception {
        return refer(token, referralBody(fields, filename, cv));
    }

    HttpResponse<String> refer(String token, byte[] body) throws Exception {
        return post(REFERRALS, HttpRequest.BodyPublishers.ofByteArray(body),
                "multipart/form-data; boundary=" + REFERRAL_BOUNDARY,
                token == null ? Map.of() : Map.of("Authorization", "Bearer " + token));
    }

    int referralAttempts() {
        return jdbc.queryForObject("SELECT count(*) FROM referral_attempts", Integer.class);
    }

    // Task 352: the referrer's own list, with this bearer token (none when null) and an optional query string.
    HttpResponse<String> myReferrals(String token) throws Exception {
        return myReferrals(token, "");
    }

    HttpResponse<String> myReferrals(String token, String query) throws Exception {
        return get(MY_REFERRALS + query, token == null ? Map.of() : Map.of("Authorization", "Bearer " + token));
    }

    HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).GET();
        headers.forEach(builder::header);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
