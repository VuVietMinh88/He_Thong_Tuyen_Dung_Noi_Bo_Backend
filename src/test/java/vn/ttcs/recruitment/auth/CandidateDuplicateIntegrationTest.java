package vn.ttcs.recruitment.auth;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.application.*;
import vn.ttcs.recruitment.candidateduplicate.*;
import vn.ttcs.recruitment.cvextraction.PdfCvExtractor;
import vn.ttcs.recruitment.cvsuggestion.CvSuggestionWorker;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.config.import=", "app.bootstrap.enabled=false", "logging.level.root=WARN", "debug=false",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "app.cv-extraction.enabled=true",
        "app.candidate-duplicates.enabled=true", "app.candidate-duplicates.batch-size=2",
        "app.candidate-duplicates.poll-delay-ms=3600000", "app.cv-contact-confirmation.enabled=true",
        "app.application-intake.enabled=true", "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.poll-delay-ms=3600000", "spring.mail.host=127.0.0.1", "spring.mail.port=1"})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class CandidateDuplicateIntegrationTest extends CvApplicationTestSupport {
    @Autowired DuplicateDetectionWorker detector;
    @Autowired CandidateDuplicateService duplicates;
    @Autowired PostgresApplicationReceiptWriter receipts;

    @BeforeEach void resetQuota() { jdbc.update("DELETE FROM application_ip_quotas"); }

    @Test void discoveryIsAsynchronousBoundedAndDoesNotAutoMergeOrRewriteApplications() throws Exception {
        String original = jdbc.queryForObject("SELECT row_to_json(a)::text FROM job_applications a WHERE id=?", String.class, app);
        var initial = read(app, recruiterToken);
        assertThat(initial.path("detectionStatus").asText()).isEqualTo("PENDING");
        assertThat(initial.path("indexedAt").isNull()).isTrue();
        assertThat(detector.processBatch()).isEqualTo(1);
        assertThat(read(app, recruiterToken).path("detectionStatus").asText()).isEqualTo("AVAILABLE");
        UUID second = submitted(posting, "two@example.test", "0912-345-678");
        submitted(posting, "three@example.test", "0912345678");
        submitted(posting, "four@example.test", "0912345678");
        assertThat(read(app, recruiterToken).path("items")).isEmpty();
        assertThat(detector.processBatch()).isEqualTo(2);
        assertThat(detector.processBatch()).isEqualTo(1);
        assertThat(detector.processBatch()).isZero();
        var result = read(app, recruiterToken);
        assertThat(result.path("items")).hasSize(3);
        assertThat(ids(result)).contains(second.toString());
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT candidate_id) FROM candidate_memberships", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT row_to_json(a)::text FROM job_applications a WHERE id=?", String.class, app)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT content FROM application_cvs WHERE application_id=?", byte[].class, app)).isEqualTo(pdf);
    }

    @Test void matchesEmailOrPhoneIncludingClosedApplicationsAndReportsExactReasons() throws Exception {
        UUID otherPosting = anotherPosting(recruiter);
        UUID email = submitted(otherPosting, "ONE@EXAMPLE.TEST", "0999999999");
        UUID phone = submitted(posting, "phone@example.test", "0912345678");
        UUID both = submitted(otherPosting, "both@example.test", "0912345678");
        confirm(both, "one@example.test", "0912345678");
        jdbc.update("UPDATE job_applications SET stage='REJECTED' WHERE id=?", email);
        jdbc.update("UPDATE job_applications SET stage='WITHDRAWN' WHERE id=?", phone);
        drain();
        var items = read(app, recruiterToken).path("items");
        assertThat(reason(items, email)).containsExactly("EMAIL");
        assertThat(reason(items, phone)).containsExactly("PHONE");
        assertThat(reason(items, both)).containsExactly("EMAIL", "PHONE");
    }

    @Test void countryCodesDotsAndPlusTagsStayDistinctAndMatchesAreNotTransitive() throws Exception {
        UUID formatted = submitted(posting, "format@example.test", "+84 912 345 678");
        submitted(posting, "one+job@example.test", "0988888888");
        submitted(posting, "o.ne@example.test", "0977777777");
        drain();
        assertThat(read(app, recruiterToken).path("items")).isEmpty();
        UUID b = submitted(anotherPosting(recruiter), "ONE@example.test", "0966666666");
        UUID c = submitted(posting, "third@example.test", "0966666666");
        drain();
        assertThat(ids(read(app, recruiterToken))).containsExactly(b.toString());
        assertThat(ids(read(b, recruiterToken))).containsExactlyInAnyOrder(app.toString(), c.toString());
        assertThat(ids(read(formatted, recruiterToken))).isEmpty();
    }

    @Test void contactConfirmationInvalidatesOldWarningsBeforeWorkerRunsAndFindsNewOnes() throws Exception {
        UUID oldMatch = submitted(posting, "old@example.test", "0912345678");
        UUID newMatch = submitted(posting, "new@example.test", "0999999999");
        drain();
        assertThat(ids(read(app, recruiterToken))).containsExactly(oldMatch.toString());
        String before = jdbc.queryForObject("SELECT row_to_json(a)::text FROM job_applications a WHERE id=?", String.class, app);
        confirm(app, "NEW@EXAMPLE.TEST", "+442079460958");
        var pending = read(app, recruiterToken);
        assertThat(pending.path("detectionStatus").asText()).isEqualTo("PENDING");
        assertThat(pending.path("items")).isEmpty();
        assertThat(read(oldMatch, recruiterToken).path("items")).isEmpty();
        drain();
        assertThat(ids(read(app, recruiterToken))).containsExactly(newMatch.toString());
        assertThat(jdbc.queryForObject("SELECT row_to_json(a)::text FROM job_applications a WHERE id=?", String.class, app)).isEqualTo(before);
    }

    @Test void unconfirmedOcrDoesNotSupplyIdentityKeys() throws Exception {
        UUID extractedMatch = submitted(posting, "emily@example.test", "0999999999");
        api.expect(api.request("POST", "/api/v1/applications/" + app + "/cv-extractions", null, recruiterToken), 202);
        assertThat(new CvSuggestionWorker(jobs, tx, bytes -> new PdfCvExtractor(image -> "").extract(bytes), 1).processNext()).isTrue();
        drain();
        assertThat(ids(read(app, recruiterToken))).doesNotContain(extractedMatch.toString());
        confirm(app, "emily@example.test", "0988888888");
        drain();
        assertThat(ids(read(app, recruiterToken))).containsExactly(extractedMatch.toString());
    }

    @Test void restrictedWarningNeverExposesHiddenIdsContactsPositionOrCounts() throws Exception {
        UUID hiddenPosting = anotherPosting(other);
        UUID hidden = submitted(hiddenPosting, "secret@example.test", "0912345678");
        UUID hiddenReq = jdbc.queryForObject("SELECT requisition_id FROM job_postings WHERE id=?", UUID.class, hiddenPosting);
        jdbc.update("UPDATE job_postings SET title='SECRET POSITION' WHERE id=?", hiddenPosting);
        // posting_title is an immutable submission snapshot; set it only as fixture data before detection.
        jdbc.update("UPDATE job_applications SET posting_title='SECRET POSITION' WHERE id=?", hidden);
        drain();
        var response = api.request("GET", path(app), null, recruiterToken);
        var body = api.expect(response, 200);
        assertThat(body.path("items")).isEmpty();
        assertThat(body.path("hasMore").asBoolean()).isFalse();
        assertThat(body.path("restrictedWarning").asText()).isEqualTo("Có hồ sơ có thể trùng ngoài phạm vi phụ trách");
        assertThat(response.body()).doesNotContain(hidden.toString(), hiddenReq.toString(), hiddenPosting.toString(),
                "secret@example.test", "SECRET POSITION", "totalElements", "restrictedCount", "candidateId");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(ids(read(app, adminToken))).containsExactly(hidden.toString());
        api.forbidden(api.request("GET", path(hidden), null, recruiterToken));
        api.forbidden(api.request("GET", path(app), null, otherToken));
    }

    @Test void permissionAndAssignmentRevocationApplyOnNextRequestWithSameToken() throws Exception {
        drain(); read(app, recruiterToken);
        jdbc.update("UPDATE requisition_recruiters SET recruiter_id=? WHERE requisition_id=?", other, requisition);
        api.forbidden(api.request("GET", path(app), null, recruiterToken));
        read(app, otherToken);
        jdbc.update("DELETE FROM role_permissions WHERE role_code='RECRUITER' AND permission_code='CANDIDATES_READ_SCOPED'");
        try { api.forbidden(api.request("GET", path(app), null, otherToken)); }
        finally { jdbc.update("INSERT INTO role_permissions VALUES ('RECRUITER','CANDIDATES_READ_SCOPED')"); }
        assertThat(api.request("GET", path(app), null, null).statusCode()).isEqualTo(401);
        clock.set(NOW.plusSeconds(3601));
        assertThat(api.request("GET", path(app), null, otherToken).statusCode()).isEqualTo(401);
    }

    @Test void hiringManagerHasManagedDepartmentTreeButNoUnrelatedDepartment() throws Exception {
        account("manager", Role.HIRING_MANAGER, encoder.encode(ApprovalTestSupport.PASSWORD));
        UUID managerId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email='manager@example.test'", UUID.class);
        String token = api.token("manager@example.test");
        api.forbidden(api.request("GET", path(app), null, token));
        UUID ownDepartment = jdbc.queryForObject("SELECT department_id FROM recruitment_requisitions WHERE id=?", UUID.class, requisition);
        UUID parent = api.department("PARENT", managerId, null, NOW);
        jdbc.update("UPDATE departments SET parent_id=? WHERE id=?", parent, ownDepartment);
        read(app, token);
        UUID hidden = submitted(anotherPosting(other, api.department("ELSEWHERE", admin, null, NOW)), "secret@example.test", "0912345678");
        drain();
        assertThat(ids(read(app, token))).doesNotContain(hidden.toString());
        api.forbidden(api.request("GET", path(hidden), null, token));
        jdbc.update("UPDATE departments SET manager_user_id=? WHERE id=?", admin, parent);
        api.forbidden(api.request("GET", path(app), null, token));
    }

    @Test void paginationCountsOnlyVisibleMatchesAndRejectsInvalidBounds() throws Exception {
        submitted(posting, "visible1@example.test", "0912345678");
        submitted(posting, "visible2@example.test", "0912345678");
        submitted(anotherPosting(other), "hidden@example.test", "0912345678");
        drain();
        var first = api.expect(api.request("GET", path(app) + "?size=1", null, recruiterToken), 200);
        var second = api.expect(api.request("GET", path(app) + "?size=1&page=1", null, recruiterToken), 200);
        assertThat(first.path("items")).hasSize(1); assertThat(first.path("hasMore").asBoolean()).isTrue();
        assertThat(second.path("items")).hasSize(1); assertThat(second.path("hasMore").asBoolean()).isFalse();
        assertThat(ids(first)).doesNotContainAnyElementsOf(ids(second));
        for (String query : List.of("size=0", "size=101", "page=-1", "page=1000001&size=100"))
            api.expect(api.request("GET", path(app) + "?" + query, null, recruiterToken), 400);
        api.expect(api.request("GET", path(UUID.randomUUID()), null, recruiterToken), 404);
    }

    @Test void cachedPairIsHiddenImmediatelyWhenMembershipsBecomeTheSameGroup() throws Exception {
        UUID second = submitted(posting, "second@example.test", "0912345678");
        drain();
        UUID group = jdbc.queryForObject("SELECT candidate_id FROM candidate_memberships WHERE application_id=?", UUID.class, app);
        // Simulate the future merge module; detector must neither expose same-group warnings nor undo membership.
        jdbc.update("UPDATE candidate_memberships SET candidate_id=? WHERE application_id=?", group, second);
        assertThat(read(app, recruiterToken).path("items")).isEmpty();
        drain();
        assertThat(jdbc.queryForObject("SELECT candidate_id FROM candidate_memberships WHERE application_id=?", UUID.class, second)).isEqualTo(group);
        // Simulate a later undo after the detector has already removed the within-group edge.
        jdbc.update("UPDATE candidate_memberships SET candidate_id=? WHERE application_id=?", second, second);
        assertThat(read(second, recruiterToken).path("detectionStatus").asText()).isEqualTo("PENDING");
        drain();
        assertThat(ids(read(app, recruiterToken))).containsExactly(second.toString());
    }

    @Test void aLateCommitIsDiscoveredWithoutTimestampWatermarkGaps() throws Exception {
        drain();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var inserted = new CountDownLatch(1); var release = new CountDownLatch(1);
            var late = pool.submit(() -> tx.execute(ignored -> {
                UUID id = submitted(posting, "late@example.test", "0912345678"); inserted.countDown();
                try { assertThat(release.await(8, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                return id;
            }));
            assertThat(inserted.await(8, TimeUnit.SECONDS)).isTrue();
            try { assertThat(detector.processBatch()).isZero(); }
            finally { release.countDown(); }
            UUID id = late.get(10, TimeUnit.SECONDS);
            drain(); assertThat(ids(read(app, recruiterToken))).containsExactly(id.toString());
        }
    }

    @Test void multipleWorkersDoNotCompeteAndIndexingRollsBackOnPairWriteFailure() throws Exception {
        submitted(posting, "second@example.test", "0912345678");
        int profilesBefore = jdbc.queryForObject("SELECT count(*) FROM candidate_profiles", Integer.class);
        try (var lock = dataSource.getConnection(); var command = lock.createStatement()) {
            lock.setAutoCommit(false); command.execute("SELECT pg_advisory_xact_lock(36710300)");
            assertThat(detector.processBatch()).isZero(); lock.rollback();
        }
        jdbc.execute("CREATE FUNCTION reject_duplicate_pair() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'private pair'; END $$");
        jdbc.execute("CREATE TRIGGER reject_duplicate_pair BEFORE INSERT ON candidate_duplicate_pairs FOR EACH ROW EXECUTE FUNCTION reject_duplicate_pair()");
        try {
            assertThatThrownBy(detector::processBatch).isInstanceOf(vn.ttcs.recruitment.common.ApiException.class)
                    .hasMessageNotContaining("private pair");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_contact_index", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_memberships", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_profiles", Integer.class)).isEqualTo(profilesBefore);
        } finally { jdbc.execute("DROP TRIGGER reject_duplicate_pair ON candidate_duplicate_pairs"); jdbc.execute("DROP FUNCTION reject_duplicate_pair()"); }
        drain(); assertThat(read(app, recruiterToken).path("items")).hasSize(1);
    }

    @Test void featureOffStopsDetectorAndApiWithoutDeletingExistingData() throws Exception {
        drain();
        org.springframework.test.util.ReflectionTestUtils.setField(detector, "enabled", false);
        org.springframework.test.util.ReflectionTestUtils.setField(duplicates, "enabled", false);
        try {
            assertThat(detector.processBatch()).isZero();
            assertThat(api.code(api.request("GET", path(app), null, recruiterToken), 503)).isEqualTo("DUPLICATE_DETECTION_UNAVAILABLE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_contact_index", Integer.class)).isEqualTo(1);
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(detector, "enabled", true);
            org.springframework.test.util.ReflectionTestUtils.setField(duplicates, "enabled", true);
        }
    }

    @Test void detectorTablesCanBeUnavailableWhilePublicIntakeStillStoresCvAndReceipt() throws Exception {
        jdbc.execute("ALTER TABLE candidate_duplicate_pairs RENAME TO candidate_duplicate_pairs_offline");
        try {
            var error = api.request("GET", path(app), null, recruiterToken);
            api.expect(error, 503); assertThat(error.body()).doesNotContain("candidate_duplicate_pairs", "SELECT", "SQLException");
            detector.poll(); // scheduled failure is contained, no exception or private SQL leaks
            var accepted = submitPublic("manual@example.test");
            api.expect(accepted, 201);
            UUID id = jdbc.queryForObject("SELECT id FROM job_applications WHERE email='manual@example.test'", UUID.class);
            assertThat(jdbc.queryForObject("SELECT content FROM application_cvs WHERE application_id=?", byte[].class, id)).isEqualTo(pdf);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM application_email_outbox WHERE application_id=?", Integer.class, id)).isEqualTo(1);
            assertThat(submitPublic("manual@example.test").statusCode()).isEqualTo(409);
        } finally { jdbc.execute("ALTER TABLE candidate_duplicate_pairs_offline RENAME TO candidate_duplicate_pairs"); }
        drain(); assertThat(ids(read(app, recruiterToken))).hasSize(1);
    }

    @Test void backfillKeepsExistingReceiptTrackingHashAndSourceData() throws Exception {
        var receipt = receipts.accept(posting, new ApplicationSubmission("Receipt", "receipt@example.test", "0912345678", null, null),
                validator.validate("cv.pdf", new ByteArrayInputStream(pdf)));
        UUID id = receipt.applicationId();
        var original = jdbc.queryForObject("SELECT row_to_json(a)::text FROM job_applications a WHERE id=?", String.class, id);
        byte[] encrypted = jdbc.queryForObject("SELECT ciphertext FROM application_email_outbox WHERE application_id=?", byte[].class, id);
        drain();
        assertThat(jdbc.queryForObject("SELECT row_to_json(a)::text FROM job_applications a WHERE id=?", String.class, id)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT ciphertext FROM application_email_outbox WHERE application_id=?", byte[].class, id)).isEqualTo(encrypted);
        assertThat(ids(read(app, recruiterToken))).containsExactly(id.toString());
    }

    @Test void obsoleteNormalizerOrKeysAreHiddenAndRebuiltWithoutChangingSource() throws Exception {
        UUID second = submitted(posting, "second@example.test", "0912345678");
        drain();
        jdbc.update("UPDATE candidate_contact_index SET normalizer_version='old-version' WHERE application_id=?", app);
        assertThat(read(app, recruiterToken).path("detectionStatus").asText()).isEqualTo("PENDING");
        assertThat(read(second, recruiterToken).path("items")).isEmpty();
        drain(); assertThat(ids(read(app, recruiterToken))).containsExactly(second.toString());
        jdbc.update("UPDATE candidate_contact_index SET phone='0999999999' WHERE application_id=?", app);
        assertThat(read(second, recruiterToken).path("items")).isEmpty();
        drain(); assertThat(ids(read(app, recruiterToken))).containsExactly(second.toString());
        assertThat(jdbc.queryForObject("SELECT phone FROM job_applications WHERE id=?", String.class, app)).isEqualTo("0912345678");
    }

    @Test void deletingAnApplicationRemovesItsIndexMembershipAndWarnings() throws Exception {
        UUID second = submitted(posting, "second@example.test", "0912345678");
        drain();
        jdbc.update("DELETE FROM job_applications WHERE id=?", second);
        assertThat(read(app, recruiterToken).path("items")).isEmpty();
        for (String table : List.of("candidate_contact_index", "candidate_memberships"))
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE application_id=?", Integer.class, second)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_duplicate_pairs", Integer.class)).isZero();
        api.expect(api.request("GET", path(second), null, recruiterToken), 404);
        assertThat(detector.processBatch()).isZero();
    }

    @Test void upgradingV30AndBackfillingPreservesSubmittedConfirmedCvAndEmailData() throws Exception {
        confirm(app, "confirmed@example.test", "0912345678");
        receipts.accept(posting, new ApplicationSubmission("Receipt", "receipt@example.test", "0912345678", null, null),
                validator.validate("cv.pdf", new ByteArrayInputStream(pdf)));
        String original = jdbc.queryForObject("SELECT current_schema()", String.class);
        String schema = "duplicate_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        var isolated = new org.springframework.jdbc.datasource.DelegatingDataSource(dataSource) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                var connection = super.getConnection(); connection.setSchema(schema); return connection;
            }
        };
        try {
            var legacy = new org.springframework.jdbc.core.JdbcTemplate(isolated);
            org.flywaydb.core.Flyway.configure().dataSource(isolated).schemas(schema).defaultSchema(schema).target("30").load().migrate();
            for (String table : List.of("user_accounts", "departments", "positions", "recruitment_catalog_items", "recruitment_requisitions",
                    "job_postings", "job_applications", "application_cvs", "application_email_outbox",
                    "application_confirmed_contacts", "application_contact_history"))
                legacy.execute("INSERT INTO " + schema + "." + table + " SELECT * FROM " + original + "." + table);
            String before = legacy.queryForObject("SELECT row_to_json(a)::text FROM job_applications a WHERE id=?", String.class, app);
            String confirmed = legacy.queryForObject("SELECT row_to_json(c)::text FROM application_confirmed_contacts c WHERE application_id=?", String.class, app);
            String history = legacy.queryForObject("SELECT row_to_json(h)::text FROM application_contact_history h WHERE application_id=?", String.class, app);
            byte[] encrypted = legacy.queryForObject("SELECT ciphertext FROM application_email_outbox", byte[].class);
            org.flywaydb.core.Flyway.configure().dataSource(isolated).schemas(schema).defaultSchema(schema).target("31").load().migrate();
            assertThat(legacy.queryForObject("SELECT count(*) FROM candidate_contact_index", Integer.class)).isZero();
            var repo = new CandidateDuplicateRepository(legacy, new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(isolated));
            var migratedDetector = new DuplicateDetectionWorker(repo, legacy,
                    new org.springframework.jdbc.support.JdbcTransactionManager(isolated), true, 25);
            assertThat(migratedDetector.processBatch()).isEqualTo(2);
            assertThat(legacy.queryForObject("SELECT count(*) FROM candidate_duplicate_pairs", Integer.class)).isEqualTo(1);
            assertThat(legacy.queryForObject("SELECT row_to_json(a)::text FROM job_applications a WHERE id=?", String.class, app)).isEqualTo(before);
            assertThat(legacy.queryForObject("SELECT row_to_json(c)::text FROM application_confirmed_contacts c WHERE application_id=?", String.class, app)).isEqualTo(confirmed);
            assertThat(legacy.queryForObject("SELECT row_to_json(h)::text FROM application_contact_history h WHERE application_id=?", String.class, app)).isEqualTo(history);
            assertThat(legacy.queryForObject("SELECT ciphertext FROM application_email_outbox", byte[].class)).isEqualTo(encrypted);
            assertThat(legacy.queryForObject("SELECT content FROM application_cvs WHERE application_id=?", byte[].class, app)).isEqualTo(pdf);
        } finally { jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(original);
    }

    private JsonNode read(UUID id, String token) throws Exception { return api.expect(api.request("GET", path(id), null, token), 200); }
    private String path(UUID id) { return "/api/v1/applications/" + id + "/duplicate-candidates"; }
    private List<String> ids(JsonNode result) {
        var ids = new ArrayList<String>(); result.path("items").forEach(item -> ids.add(item.path("applicationId").asText())); return ids;
    }
    private List<String> reason(JsonNode items, UUID id) {
        for (var item : items) if (item.path("applicationId").asText().equals(id.toString())) {
            var reasons = new ArrayList<String>(); item.path("matchedBy").forEach(value -> reasons.add(value.asText())); return reasons;
        }
        throw new AssertionError("Missing expected application");
    }
    private void drain() { for (int i=0; i<30; i++) if (detector.processBatch() == 0) return; throw new AssertionError("Detector did not drain"); }
    private UUID submitted(UUID postingId, String email, String phone) {
        byte[] hash = new byte[32]; new java.security.SecureRandom().nextBytes(hash);
        return storage.store(postingId, new ApplicationSubmission("Manual Candidate", email, phone, null, null),
                validator.validate("cv.pdf", new ByteArrayInputStream(pdf)), hash).id();
    }
    private UUID anotherPosting(UUID assigned) {
        UUID department = jdbc.queryForObject("SELECT department_id FROM recruitment_requisitions WHERE id=?", UUID.class, requisition);
        return anotherPosting(assigned, department);
    }
    private UUID anotherPosting(UUID assigned, UUID department) {
        UUID req = UUID.randomUUID(), post = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recruitment_requisitions(id,position_id,department_id,headcount,reason,status,created_by,created_at,updated_at)
                SELECT ?,position_id,?,1,'NEW_HEADCOUNT','APPROVED',created_by,created_at,updated_at FROM recruitment_requisitions WHERE id=?
                """, req, department, requisition);
        jdbc.update("""
                INSERT INTO job_postings(id,requisition_id,status,revision,title,job_description,candidate_requirements,
                    work_location_id,employment_type_id,application_deadline,show_salary,created_by,created_at,updated_at,published_by,published_at)
                SELECT ?,?,'PUBLISHED',1,title,job_description,candidate_requirements,work_location_id,employment_type_id,
                    application_deadline,show_salary,created_by,created_at,updated_at,published_by,published_at FROM job_postings WHERE id=?
                """, post, req, posting);
        jdbc.update("INSERT INTO requisition_recruiters VALUES (?,?,'PRIMARY',?,?)", req, assigned, admin, Timestamp.from(NOW));
        return post;
    }
    private void confirm(UUID id, String email, String phone) throws Exception {
        String hash = jdbc.queryForObject("SELECT encode(sha256,'hex') FROM application_cvs WHERE application_id=?", String.class, id);
        api.expect(api.request("PUT", "/api/v1/applications/" + id + "/confirmed-contact", json.writeValueAsString(Map.of(
                "fullName", "Confirmed Candidate", "email", email, "phone", phone, "expectedRevision", 0,
                "expectedCvHash", hash, "nameSource", "MANUAL", "emailSource", "MANUAL", "phoneSource", "MANUAL")), recruiterToken), 200);
    }
    private HttpResponse<String> submitPublic(String email) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        for (var field : Map.of("fullName", "Manual Candidate", "email", email, "phone", "0912345678").entrySet())
            bytes.write(("--duplicate-boundary\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        bytes.write("--duplicate-boundary\r\nContent-Disposition: form-data; name=\"cv\"; filename=\"cv.pdf\"\r\nContent-Type: application/pdf\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        bytes.write(pdf); bytes.write("\r\n--duplicate-boundary--\r\n".getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port")
                + "/api/v1/public/job-postings/" + posting + "/applications")).timeout(java.time.Duration.ofSeconds(15))
                .header("Content-Type", "multipart/form-data; boundary=duplicate-boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }
}
