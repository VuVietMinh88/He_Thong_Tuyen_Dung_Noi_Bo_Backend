package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.requisition.RequisitionRecruiterRole;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.EMPLOYMENT_TYPE;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.WORK_LOCATION;

// Task 310 (story S3-10): "Ghi người xuất bản và thời điểm công khai". The publication writes who published a posting
// and when on the posting itself (V23), with the values of its PUBLISHED event; every internal view shows them, the
// public view does not. The review and publication history is read through GET /job-postings/{id}/history.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JobPostingPublicationTrailIntegrationTest {
    // 10:00 on 11 Oct 2026 in Vietnam. Every posting made here may be applied to until 30 Nov 2026.
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");
    private static final List<String> TRAIL = List.of("publishedById", "publishedByName", "publishedAt");
    private static final List<String> HISTORY_KEYS = List.of("id", "revision", "action", "statusBefore",
            "statusAfter", "actorId", "actorName", "occurredAt", "comment");
    // The eleven keys of a public posting whose salary is hidden.
    private static final List<String> PUBLIC_KEYS = List.of("id", "title", "positionName", "departmentName",
            "headcount", "workLocationName", "employmentTypeName", "applicationDeadline", "jobDescription",
            "candidateRequirements", "benefits");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;

    private JobPostingTestSupport postings;
    private ApprovalTestSupport api;
    private String passwordHash;
    private UUID adminId;
    private String adminToken;
    private UUID hrId;
    private String hrToken;
    private UUID recruiterId;
    private String recruiterToken;
    private UUID department;
    private UUID position;
    private UUID workLocation;
    private UUID employmentType;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
    }

    @BeforeEach
    void resetFixture() throws Exception {
        clock.set(START);
        postings = new JobPostingTestSupport(jdbc, json, transactions,
                Integer.parseInt(environment.getRequiredProperty("local.server.port")));
        api = postings.api;
        postings.reset();
        bootstrap.run(new DefaultApplicationArguments());
        adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        adminToken = api.token("admin@example.test");
        hrId = account("hr@example.test", "Nguyễn Thị Hoa", Role.HR_MANAGER);
        hrToken = api.token("hr@example.test");
        UUID headId = account("head@example.test", "Trưởng phòng IT", Role.HIRING_MANAGER);
        recruiterId = account("recruiter@example.test", "Trần Văn Tuyển", Role.RECRUITER);
        recruiterToken = api.token("recruiter@example.test");
        department = api.department("IT", headId, null, START);
        position = api.position(START);
        workLocation = postings.catalogValue(WORK_LOCATION, "HN", "Hà Nội", true, START);
        employmentType = postings.catalogValue(EMPLOYMENT_TYPE, "FULL_TIME", "Toàn thời gian", true, START);
    }

    // S3-10 AC3: "Ghi lại người xuất bản và thời điểm".
    @Test
    void publishingRecordsWhoAndWhen() throws Exception {
        // Three different people: the assigned recruiter writes and sends the posting, ADMIN approves it and HR
        // publishes it, so the publisher can only come from the caller of the publication.
        UUID id = draft();
        ok(postings.submit(id, 1, null, recruiterToken));
        ok(postings.decide(id, "APPROVE", null, 2, adminToken));
        assertNoTrail(id, hrToken);
        clock.set(START.plusSeconds(240).plusNanos(987_654_789));

        var publishing = postings.publish(id, 3, hrToken);
        JsonNode published = ok(publishing);
        postings.noStore(publishing);

        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(published.path("publishedById").asText()).isEqualTo(hrId.toString());
        assertThat(published.path("publishedByName").asText()).isEqualTo("Nguyễn Thị Hoa");
        // Microseconds, cut like every time the server writes, never rounded up: the time a later read returns.
        assertThat(published.path("publishedAt").asText()).isEqualTo("2026-10-11T03:04:00.987654Z");
        assertThat(published.path("publishedById").asText())
                .isNotEqualTo(published.path("createdById").asText())
                .isNotEqualTo(published.path("lastReview").path("actorId").asText());
        assertThat(published.path("createdById").asText()).isEqualTo(recruiterId.toString());
        assertThat(published.path("lastReview").path("actorId").asText()).isEqualTo(adminId.toString());

        // Every internal read shows the same trail: the detail for every composer, and the list.
        assertThat(ok(postings.get(id, hrToken))).isEqualTo(published);
        JsonNode forRecruiter = ok(postings.get(id, recruiterToken));
        for (String trail : TRAIL) {
            assertThat(forRecruiter.path(trail)).as(trail).isEqualTo(published.path(trail));
        }
        JsonNode item = onlyItem(ok(postings.list("", adminToken)));
        assertThat(item.path("publishedAt")).isEqualTo(published.path("publishedAt"));

        // The posting and its PUBLISHED event keep the same publisher and the same time.
        Map<String, Object> row = postings.row(id);
        assertThat(row.get("published_by")).isEqualTo(hrId);
        assertThat(((Timestamp) row.get("published_at")).toInstant())
                .isEqualTo(Instant.parse("2026-10-11T03:04:00.987654Z"));
        Map<String, Object> publication = postings.events(id).getLast();
        assertThat(publication.get("event_type")).isEqualTo("PUBLISHED");
        assertThat(publication.get("actor_id")).isEqualTo(hrId);
        assertThat(publication.get("occurred_at")).isEqualTo(row.get("published_at"));
    }

    @Test
    void theTrailIsEmptyUntilPublicationAndRefusedPublicationsLeaveNone() throws Exception {
        UUID id = draft();
        assertNoTrail(id, recruiterToken);
        postings.error(postings.publish(id, 1, hrToken), 409, "JOB_POSTING_NOT_APPROVED");
        ok(postings.submit(id, 1, null, recruiterToken));
        assertNoTrail(id, hrToken);
        postings.error(postings.publish(id, 2, hrToken), 409, "JOB_POSTING_NOT_APPROVED");
        ok(postings.decide(id, "APPROVE", null, 2, adminToken));
        assertNoTrail(id, hrToken);
        Map<String, Object> rowBefore = postings.row(id);
        List<Map<String, Object>> eventsBefore = postings.events(id);
        account("approver@example.test", "Người duyệt", Role.APPROVER);
        String approverToken = api.token("approver@example.test");

        // 409: an outdated revision.
        postings.error(postings.publish(id, 2, hrToken), 409, "JOB_POSTING_CHANGED");
        // 403: without JOB_POSTINGS_WRITE_ALL, the assigned recruiter included.
        postings.forbidden(postings.publish(id, 3, recruiterToken));
        postings.forbidden(postings.publish(id, 3, approverToken));
        // 401: a token that expired.
        clock.set(START.plus(Duration.ofHours(1)));
        assertThat(api.code(postings.publish(id, 3, hrToken), 401)).isEqualTo("UNAUTHORIZED");
        // 409: 00:00 on 1 Dec 2026 in Vietnam, the application deadline has passed.
        clock.set(Instant.parse("2026-11-30T17:00:00Z"));
        String laterToken = api.token("hr@example.test");
        postings.error(postings.publish(id, 3, laterToken), 409, "JOB_POSTING_DEADLINE_PASSED");

        assertThat(postings.row(id)).isEqualTo(rowBefore);
        assertThat(postings.events(id)).isEqualTo(eventsBefore);
        assertNoTrail(id, laterToken);
    }

    @Test
    void aSecondPublicationNeverRewritesTheTrail() throws Exception {
        UUID id = approved();
        clock.set(START.plusSeconds(60));
        JsonNode first = ok(postings.publish(id, 3, hrToken));
        assertThat(first.path("publishedAt").asText()).isEqualTo("2026-10-11T03:01:00Z");
        Map<String, Object> rowBefore = postings.row(id);
        List<Map<String, Object>> eventsBefore = postings.events(id);

        clock.set(START.plusSeconds(120));
        for (String token : List.of(adminToken, hrToken)) {
            JsonNode error = postings.error(postings.publish(id, 4, token), 409, "JOB_POSTING_ALREADY_PUBLISHED");
            assertThat(error.path("message").asText()).isEqualTo("Tin tuyển dụng đã được xuất bản.");
        }

        assertThat(postings.row(id)).isEqualTo(rowBefore);
        assertThat(postings.events(id)).isEqualTo(eventsBefore);
        assertThat(postings.events(id)).filteredOn(event -> "PUBLISHED".equals(event.get("event_type"))).hasSize(1);
        JsonNode view = ok(postings.get(id, adminToken));
        for (String trail : TRAIL) {
            assertThat(view.path(trail)).as(trail).isEqualTo(first.path(trail));
        }
    }

    @Test
    void historyListsEveryStepNewestFirstWithoutPersonalData() throws Exception {
        UUID id = draft();
        clock.set(START.plusSeconds(60).plusNanos(111_111_111));
        ok(postings.submit(id, 1, "Nhờ chị duyệt giúp.", recruiterToken));
        clock.set(START.plusSeconds(120).plusNanos(222_222_222));
        ok(postings.decide(id, "REQUEST_REVISION", "Bổ sung quyền lợi.\nGhi rõ bảo hiểm.", 2, adminToken));
        // A save is not a step of the history, so revision 4 is missing from it.
        Map<String, Object> change = postings.putBody(ok(postings.get(id, recruiterToken)));
        change.put("benefits", "Bảo hiểm đầy đủ.");
        ok(postings.update(id, change, recruiterToken));
        clock.set(START.plusSeconds(180).plusNanos(333_333_333));
        ok(postings.submit(id, 4, null, recruiterToken));
        clock.set(START.plusSeconds(240).plusNanos(444_444_444));
        ok(postings.decide(id, "APPROVE", "Đồng ý.", 5, hrToken));
        clock.set(START.plusSeconds(300).plusNanos(555_555_555));
        JsonNode published = ok(postings.publish(id, 6, adminToken));

        var reading = postings.history(id, hrToken);
        JsonNode history = ok(reading);
        postings.noStore(reading);

        assertThat(history.isArray()).isTrue();
        assertThat(steps(history)).containsExactly(
                new Step(7, "PUBLISHED", "APPROVED", "PUBLISHED", adminId, "Quản trị viên",
                        "2026-10-11T03:05:00.555555Z", null),
                new Step(6, "APPROVED", "PENDING_APPROVAL", "APPROVED", hrId, "Nguyễn Thị Hoa",
                        "2026-10-11T03:04:00.444444Z", "Đồng ý."),
                new Step(5, "SUBMITTED", "REVISION_REQUESTED", "PENDING_APPROVAL", recruiterId, "Trần Văn Tuyển",
                        "2026-10-11T03:03:00.333333Z", null),
                new Step(3, "REVISION_REQUESTED", "PENDING_APPROVAL", "REVISION_REQUESTED", adminId, "Quản trị viên",
                        "2026-10-11T03:02:00.222222Z", "Bổ sung quyền lợi.\nGhi rõ bảo hiểm."),
                new Step(2, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", recruiterId, "Trần Văn Tuyển",
                        "2026-10-11T03:01:00.111111Z", "Nhờ chị duyệt giúp."));
        for (JsonNode entry : history) {
            assertThat(fieldNames(entry)).as(entry.toString()).containsExactlyElementsOf(HISTORY_KEYS);
        }
        // The ids are those of the saved events, and the newest step is the publication on the posting itself.
        List<UUID> eventIds = postings.events(id).stream().map(event -> (UUID) event.get("id")).toList();
        assertThat(ids(history)).containsExactlyElementsOf(eventIds.reversed());
        assertThat(history.get(0).path("actorId")).isEqualTo(published.path("publishedById"));
        assertThat(history.get(0).path("occurredAt")).isEqualTo(published.path("publishedAt"));
        // People are an id and a name: no email, role or other account data.
        assertThat(reading.body()).doesNotContain("@example.test", "HR_MANAGER", "RECRUITER", "\"email\"",
                "\"role", "password");
        // Every composer of the posting reads the same history.
        assertThat(ok(postings.history(id, adminToken))).isEqualTo(history);
        assertThat(ok(postings.history(id, recruiterToken))).isEqualTo(history);
    }

    @Test
    void historyFollowsTheComposerScope() throws Exception {
        UUID draft = draft();
        UUID pending = draft();
        ok(postings.submit(pending, 1, null, recruiterToken));
        account("outsider@example.test", "Người ngoài", Role.RECRUITER);
        String outsiderToken = api.token("outsider@example.test");
        String headToken = api.token("head@example.test");
        account("approver@example.test", "Người duyệt", Role.APPROVER);
        String approverToken = api.token("approver@example.test");
        List<Map<String, Object>> rowsBefore = jdbc.queryForList("SELECT * FROM job_postings ORDER BY id");
        List<Map<String, Object>> eventsBefore = jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1");

        // A draft has no step yet; creating it is not one.
        assertThat(ok(postings.history(draft, recruiterToken)).isEmpty()).isTrue();
        for (String token : List.of(hrToken, adminToken, recruiterToken)) {
            assertThat(ok(postings.history(pending, token)).size()).isEqualTo(1);
        }
        // A recruiter not on the requisition's team, and JOB_POSTINGS_READ_ALL alone (HIRING_MANAGER, APPROVER).
        for (UUID posting : List.of(draft, pending)) {
            postings.forbidden(postings.history(posting, outsiderToken));
            postings.forbidden(postings.history(posting, headToken));
            postings.forbidden(postings.history(posting, approverToken));
        }
        // An unknown id is 404 before the scope for every composer; the matcher refuses the others first.
        for (String token : List.of(hrToken, recruiterToken, outsiderToken)) {
            postings.error(postings.history(UUID.randomUUID(), token), 404, "JOB_POSTING_NOT_FOUND");
        }
        postings.forbidden(postings.history(UUID.randomUUID(), headToken));
        assertThat(api.code(postings.history(pending, null), 401)).isEqualTo("UNAUTHORIZED");
        // Without the read permission a composer is refused before the lookup, like a read of the posting.
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'RECRUITER' "
                + "AND permission_code = 'JOB_POSTINGS_READ_SCOPED'");
        try {
            postings.forbidden(postings.history(pending, recruiterToken));
            postings.forbidden(postings.history(UUID.randomUUID(), recruiterToken));
        } finally {
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) "
                    + "VALUES ('RECRUITER', 'JOB_POSTINGS_READ_SCOPED') ON CONFLICT DO NOTHING");
        }

        assertThat(jdbc.queryForList("SELECT * FROM job_postings ORDER BY id")).isEqualTo(rowsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1")).isEqualTo(eventsBefore);
    }

    @Test
    void thePublicListIsOrderedByPublicationTime() throws Exception {
        // Created a, b, c, d in this order; b and d published at the same moment, then a, then c.
        UUID a = approved();
        UUID b = approved();
        UUID c = approved();
        UUID d = approved();
        clock.set(START.plusSeconds(60));
        ok(postings.publish(b, 3, hrToken));
        ok(postings.publish(d, 3, hrToken));
        clock.set(START.plusSeconds(120));
        ok(postings.publish(a, 3, hrToken));
        clock.set(START.plusSeconds(180));
        ok(postings.publish(c, 3, hrToken));
        // PostgreSQL orders UUIDs like their text, so the larger text comes first among postings published together.
        List<UUID> together = Stream.of(b, d).sorted(Comparator.comparing(UUID::toString).reversed()).toList();

        assertThat(ids(ok(postings.publicList(0, 20)))).containsExactly(c, a, together.get(0), together.get(1));
        JsonNode first = ok(postings.publicList(0, 2));
        assertThat(ids(first)).containsExactly(c, a);
        assertThat(first.path("totalElements").asLong()).isEqualTo(4);
        assertThat(ids(ok(postings.publicList(1, 2)))).containsExactlyElementsOf(together);
        // The order is the posting's own publication time.
        assertThat(ok(postings.get(c, hrToken)).path("publishedAt").asText()).isEqualTo("2026-10-11T03:03:00Z");
        assertThat(ok(postings.get(a, hrToken)).path("publishedAt").asText()).isEqualTo("2026-10-11T03:02:00Z");
        for (UUID posting : together) {
            assertThat(ok(postings.get(posting, hrToken)).path("publishedAt").asText())
                    .isEqualTo("2026-10-11T03:01:00Z");
        }
    }

    @Test
    void thePublicViewStillEqualsThePreview() throws Exception {
        UUID id = approved();
        JsonNode preview = ok(postings.preview(id, hrToken));
        ok(postings.publish(id, preview.path("revision").asInt(), hrToken));
        JsonNode internal = ok(postings.get(id, hrToken));
        assertThat(internal.path("publishedAt").isNull()).isFalse();

        var detail = postings.publicDetail(id);
        JsonNode content = ok(postings.preview(id, hrToken)).path("content");
        assertThat(ok(detail)).isEqualTo(content).isEqualTo(preview.path("content"));
        assertThat(fieldNames(ok(detail))).containsExactlyElementsOf(PUBLIC_KEYS);
        assertThat(fieldNames(ok(postings.preview(id, hrToken)))).containsExactly("id", "status", "revision",
                "content");
        // Candidates see neither the publisher nor the publication time, like the company page hides who saved it.
        for (String body : List.of(detail.body(), postings.publicList(0, 20).body(), content.toString())) {
            assertThat(body).contains(id.toString()).doesNotContain("published", hrId.toString(), "Nguyễn Thị Hoa",
                    internal.path("publishedAt").asText(), "2026-10-11");
        }
    }

    // A draft the assigned recruiter wrote, with the public details filled in (deadline 30 Nov 2026). Revision 1.
    private UUID draft() throws Exception {
        return id(postings.created(postings.body(requisition(), workLocation, employmentType), recruiterToken));
    }

    // The recruiter sends the draft for review (2) and ADMIN approves it (3); neither is the publisher HR.
    private UUID approved() throws Exception {
        UUID id = draft();
        ok(postings.submit(id, 1, null, recruiterToken));
        ok(postings.decide(id, "APPROVE", null, 2, adminToken));
        return id;
    }

    // The trail is empty in the view of this caller and in the row.
    private void assertNoTrail(UUID posting, String token) throws Exception {
        JsonNode view = ok(postings.get(posting, token));
        for (String trail : TRAIL) {
            assertThat(view.path(trail).isNull()).as(trail).isTrue();
        }
        Map<String, Object> row = postings.row(posting);
        assertThat(row.get("published_by")).isNull();
        assertThat(row.get("published_at")).isNull();
    }

    // An approved requisition the recruiter is the primary recruiter of.
    private UUID requisition() {
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        postings.assign(requisition, recruiterId, RequisitionRecruiterRole.PRIMARY, hrId, START);
        return requisition;
    }

    // One step of the history as the API returns it, without its id.
    private record Step(int revision, String action, String before, String after, UUID actor, String actorName,
                        String occurredAt, String comment) { }

    private static List<Step> steps(JsonNode history) {
        List<Step> steps = new ArrayList<>();
        for (JsonNode entry : history) {
            steps.add(new Step(entry.path("revision").asInt(), entry.path("action").asText(),
                    entry.path("statusBefore").asText(), entry.path("statusAfter").asText(),
                    UUID.fromString(entry.path("actorId").asText()), entry.path("actorName").asText(),
                    entry.path("occurredAt").asText(), entry.path("comment").isNull()
                            ? null : entry.path("comment").asText()));
        }
        return steps;
    }

    private JsonNode ok(HttpResponse<String> response) {
        return api.expect(response, 200);
    }

    private static UUID id(JsonNode view) {
        return UUID.fromString(view.path("id").asText());
    }

    // The ids of a page's items, or of the entries of a history.
    private static List<UUID> ids(JsonNode node) {
        List<UUID> ids = new ArrayList<>();
        (node.isArray() ? node : node.path("items")).forEach(item -> ids.add(id(item)));
        return ids;
    }

    private static JsonNode onlyItem(JsonNode page) {
        assertThat(page.path("items").size()).as(page.toString()).isEqualTo(1);
        return page.path("items").get(0);
    }

    private UUID account(String email, String fullName, Role role) {
        return accounts.saveAndFlush(new Account(email, fullName, passwordHash, Set.of(role), START)).getId();
    }

    private static List<String> fieldNames(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }
}
