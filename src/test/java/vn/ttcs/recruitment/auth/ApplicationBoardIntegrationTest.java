package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterEach;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.page-url=https://portal.example.test/tra-cuu",
        "app.application-confirmation.poll-delay-ms=3600000", "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-tracking.cleanup-delay-ms=3600000", "spring.mail.host=127.0.0.1", "spring.mail.port=1"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationBoardIntegrationTest {
    private static final String BOARD = "/api/v1/application-board";
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private Environment environment;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationStorageService storage;
    @Autowired private CvValidator validator;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private TokenService tokens;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private ApplicationTestSupport applications;
    private int port;
    private UUID hr, recruiter, supporting, outsider, manager, noRole;
    private UUID firstPosting, secondPosting, firstRequisition, secondRequisition, firstPosition, secondPosition;
    private List<Map<String, Object>> permissions;

    @BeforeEach
    void fixture() {
        clock.set(ApplicationTestSupport.NOW);
        port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, port).reset();
        permissions = jdbc.queryForList("SELECT role_code, permission_code FROM role_permissions");
        firstPosting = applications.publishedPosting("Java developer");
        secondPosting = applications.publishedPosting("Finance analyst");
        firstRequisition = applications.requisitionOf(firstPosting);
        secondRequisition = applications.requisitionOf(secondPosting);
        firstPosition = position(firstRequisition);
        secondPosition = position(secondRequisition);
        hr = account(Role.HR_MANAGER);
        recruiter = account(Role.RECRUITER);
        supporting = account(Role.RECRUITER);
        outsider = account(Role.RECRUITER);
        manager = account(Role.HIRING_MANAGER);
        noRole = account(null);
        applications.assign(firstRequisition, recruiter, "PRIMARY", hr);
        applications.assign(firstRequisition, supporting, "SUPPORTING", hr);
        jdbc.update("UPDATE departments SET manager_user_id = ? WHERE id = (SELECT department_id FROM recruitment_requisitions WHERE id = ?)",
                manager, firstRequisition);
    }

    @AfterEach
    void restorePermissions() {
        if (permissions == null) return;
        jdbc.update("DELETE FROM role_permissions");
        jdbc.batchUpdate("INSERT INTO role_permissions(role_code, permission_code) VALUES (?, ?)", permissions.stream()
                .map(row -> new Object[]{row.get("role_code"), row.get("permission_code")}).toList());
    }

    @Test
    void emptyBoardHasExactlySixOrderedColumnsAndNoCandidateAudit() throws Exception {
        JsonNode body = board(hr, "");
        assertThat(stages(body)).containsExactly("NEW", "SCREENING", "INTERVIEW", "OFFER", "HIRED", "REJECTED");
        body.path("columns").forEach(column -> {
            assertThat(column.path("total").asLong()).isZero();
            assertThat(column.path("cards").size()).isZero();
            assertThat(column.path("hasNext").asBoolean()).isFalse();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events", Long.class)).isZero();
    }

    @Test
    void totalsCountAllMatchingRowsButEachColumnHasItsOwnBoundedPage() throws Exception {
        for (int i = 0; i < 3; i++) store(firstPosting, "NEW", "new" + i + "@example.test");
        store(firstPosting, "SCREENING", "screen1@example.test");
        store(firstPosting, "SCREENING", "screen2@example.test");
        store(firstPosting, "REJECTED", "reject@example.test");
        store(firstPosting, "WITHDRAWN", "withdrawn@example.test");
        var body = board(hr, "?size=1");
        assertThat(column(body, "NEW").path("total").asLong()).isEqualTo(3);
        assertThat(column(body, "SCREENING").path("total").asLong()).isEqualTo(2);
        assertThat(column(body, "REJECTED").path("total").asLong()).isEqualTo(1);
        assertThat(column(body, "NEW").path("cards").size()).isEqualTo(1);
        assertThat(column(body, "SCREENING").path("cards").size()).isEqualTo(1);
        assertThat(column(body, "NEW").path("hasNext").asBoolean()).isTrue();
        assertThat(column(body, "REJECTED").path("hasNext").asBoolean()).isFalse();
        assertThat(total(body)).isEqualTo(6);
        List<String> expected = jdbc.queryForList("SELECT id::text FROM job_applications WHERE stage = 'NEW' ORDER BY received_at DESC, id DESC", String.class);
        for (int page = 0; page < 3; page++) {
            JsonNode next = board(hr, "?size=1&page=" + page);
            assertThat(column(next, "NEW").path("cards").get(0).path("applicationId").asText()).isEqualTo(expected.get(page));
            assertThat(column(next, "NEW").path("total").asLong()).isEqualTo(3);
        }
        assertThat(column(board(hr, "?size=1&page=3"), "NEW").path("cards").size()).isZero();
    }

    @Test
    void cardsDiscloseOnlyMinimalFieldsAndEveryReturnedProfileIsAudited() throws Exception {
        UUID first = store(firstPosting, "NEW", "same@example.test");
        UUID second = store(secondPosting, "OFFER", "same@example.test");
        JsonNode body = board(hr, "");
        body.path("columns").forEach(column -> column.path("cards").forEach(card ->
                assertThat(card.propertyNames()).containsExactlyInAnyOrder("applicationId", "candidateId", "requisitionId",
                        "positionId", "fullName", "jobTitle", "receivedAt", "stage")));
        assertThat(jdbc.queryForList("SELECT application_id FROM candidate_access_events ORDER BY application_id", UUID.class))
                .containsExactlyInAnyOrder(first, second);
        assertThat(jdbc.queryForList("SELECT DISTINCT access_kind || ':' || outcome FROM candidate_access_events", String.class))
                .containsExactly("PROFILE:GRANTED");
        assertThat(body.toString()).doesNotContain("tracking", "coverLetter", "salary", "same@example.test");
    }

    @Test
    void defaultsAreAllForAllReadersAndAssignedOnlyForScopedReaders() throws Exception {
        UUID assigned = store(firstPosting, "NEW", "first@example.test");
        store(secondPosting, "NEW", "other@example.test");
        assertThat(total(board(hr, ""))).isEqualTo(2);
        for (UUID actor : List.of(recruiter, supporting)) {
            JsonNode body = board(actor, "");
            assertThat(body.path("assignedOnly").asBoolean()).isTrue();
            assertThat(total(body)).isEqualTo(1);
            assertThat(column(body, "NEW").path("cards").get(0).path("applicationId").asText()).isEqualTo(assigned.toString());
        }
        assertThat(total(board(outsider, ""))).isZero();
        assertThat(total(board(manager, ""))).isZero();
        assertThat(total(board(manager, "?requisitionId=" + firstRequisition))).isEqualTo(1);
    }

    @Test
    void explicitPositionAndOpeningFiltersCannotBroadenAssignmentScope() throws Exception {
        store(firstPosting, "NEW", "first@example.test");
        store(secondPosting, "NEW", "second@example.test");
        assertThat(total(board(hr, "?positionId=" + firstPosition))).isEqualTo(1);
        assertThat(total(board(hr, "?requisitionId=" + firstRequisition + "&positionId=" + secondPosition))).isZero();
        // Two openings for the same catalog position must still be authorized separately.
        jdbc.update("UPDATE recruitment_requisitions SET position_id = ? WHERE id = ?", firstPosition, secondRequisition);
        assertThat(total(board(recruiter, "?positionId=" + firstPosition))).isEqualTo(1);
        assertThat(get(BOARD + "?requisitionId=" + secondRequisition, token(recruiter)).statusCode()).isEqualTo(403);
        assertThat(get(BOARD + "?positionId=" + firstPosition, token(outsider)).statusCode()).isEqualTo(403);
    }

    @Test
    void permissionsAreRequiredBeforeAnyFilterLookupAndWriteDoesNotImplyRead() throws Exception {
        assertThat(get(BOARD, null).statusCode()).isEqualTo(401);
        for (String query : List.of("", "?requisitionId=" + UUID.randomUUID())) {
            assertThat(get(BOARD + query, token(noRole)).statusCode()).isEqualTo(403);
        }
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'RECRUITER' AND permission_code LIKE 'CANDIDATES_READ_%'");
        assertThat(get(BOARD + "?requisitionId=" + firstRequisition, token(recruiter)).statusCode()).isEqualTo(403);
    }

    @Test
    void unknownAndOutOfScopeOpeningFiltersUseExisting404And403Convention() throws Exception {
        assertThat(get(BOARD + "?requisitionId=" + UUID.randomUUID(), token(recruiter)).statusCode()).isEqualTo(404);
        assertThat(get(BOARD + "?positionId=" + UUID.randomUUID(), token(recruiter)).statusCode()).isEqualTo(404);
        assertThat(get(BOARD + "?positionId=" + secondPosition, token(recruiter)).statusCode()).isEqualTo(403);
        assertThat(get(BOARD + "?requisitionId=" + secondRequisition, token(recruiter)).statusCode()).isEqualTo(403);
        // No candidate profile was looked up by these filter checks.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events", Long.class)).isZero();
    }

    @Test
    void invalidPaginationAndIdsAreRejectedWithoutAudit() throws Exception {
        String token = token(hr);
        for (String query : List.of("?page=-1", "?page=1000001", "?size=0", "?size=101", "?size=oops", "?requisitionId=invalid")) {
            HttpResponse<String> response = get(BOARD + query, token);
            assertThat(response.statusCode()).as(query + ": " + response.body()).isEqualTo(400);
        }
        assertThat(total(board(hr, "?size=100&page=1000000"))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events", Long.class)).isZero();
    }

    @Test
    void removingAssignmentIsEffectiveOnNextReadAndDoesNotLeakCardsOrTotals() throws Exception {
        store(firstPosting, "INTERVIEW", "first@example.test");
        assertThat(total(board(recruiter, ""))).isEqualTo(1);
        jdbc.update("DELETE FROM requisition_recruiters WHERE requisition_id = ? AND recruiter_id = ?", firstRequisition, recruiter);
        assertThat(total(board(recruiter, ""))).isZero();
        assertThat(get(BOARD + "?requisitionId=" + firstRequisition, token(recruiter)).statusCode()).isEqualTo(403);
        assertThat(total(board(supporting, ""))).isEqualTo(1);
    }

    private UUID store(UUID posting, String stage, String email) {
        UUID id = applications.stored(posting, new ApplicationSubmission("Ứng viên Kanban", email, "0912345678", null,
                "Private cover letter")).applicationId();
        jdbc.update("UPDATE job_applications SET stage = ? WHERE id = ?", stage, id);
        return id;
    }

    private UUID account(Role role) {
        UUID id = applications.staff("Người xử lý", UUID.randomUUID() + "@example.test");
        if (role != null) jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, ?)", id, role.name());
        return id;
    }

    private UUID position(UUID requisition) {
        return jdbc.queryForObject("SELECT position_id FROM recruitment_requisitions WHERE id = ?", UUID.class, requisition);
    }

    private String token(UUID actor) {
        UUID session = UUID.randomUUID();
        jdbc.update("INSERT INTO auth_sessions(id,user_id,refresh_token_hash,created_at,expires_at) VALUES(?,?,?,?,?)",
                session, actor, (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""), Timestamp.from(ApplicationTestSupport.NOW),
                Timestamp.from(ApplicationTestSupport.NOW.plus(Duration.ofDays(7))));
        return tokens.createAccessToken(actor, session);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.headers().allValues("Cache-Control")).anyMatch(value -> value.contains("no-store"));
        return response;
    }

    private JsonNode board(UUID actor, String query) throws Exception {
        var response = get(BOARD + query, token(actor));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private static JsonNode column(JsonNode body, String stage) {
        for (JsonNode column : body.path("columns")) if (stage.equals(column.path("stage").asText())) return column;
        throw new AssertionError("Missing column " + stage);
    }

    private static long total(JsonNode body) {
        long total = 0;
        for (JsonNode column : body.path("columns")) total += column.path("total").asLong();
        return total;
    }

    private static List<String> stages(JsonNode body) {
        List<String> stages = new ArrayList<>();
        body.path("columns").forEach(column -> stages.add(column.path("stage").asText()));
        return stages;
    }
}
