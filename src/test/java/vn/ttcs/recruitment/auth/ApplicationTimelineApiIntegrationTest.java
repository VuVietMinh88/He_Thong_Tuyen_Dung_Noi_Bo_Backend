package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.account.Role;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 404: internal history is authorized for the exact application, even when profiles share an email. */
class ApplicationTimelineApiIntegrationTest extends ScreeningTestSupport {
    private static final String PRIVATE_NOTE = "GHI_CHU_TIMELINE_JAVA";
    private static final String OTHER_PRIVATE_NOTE = "GHI_CHU_TIMELINE_FINANCE";
    private static final String PRIVATE_STAGE_REASON = "LY_DO_CHUYEN_GIAI_DOAN_NOI_BO";
    private static final String PRIVATE_MESSAGE_ID = "private-delivery-id@example.test";

    @Test
    void anonymousAndInvalidBearerCannotReadExistingOrUnknownApplications() throws Exception {
        for (UUID id : List.of(first.applicationId(), UUID.randomUUID())) {
            for (String bearer : new String[] {null, "not-a-jwt"}) {
                var response = call("GET", timeline(id), null, bearer);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
                assertThat(response.body()).doesNotContain(PRIVATE_NOTE, "APPLICATION_SUBMITTED");
            }
        }
        assertThat(count("candidate_access_events")).isZero();
    }

    @Test
    void noReadPermissionIs403BeforeExistenceLookupAndDoesNotCreateAnAuditEntry() throws Exception {
        UUID noRole = applications.account("No role", "timeline-no-role@example.test");
        String bearer = token(noRole);
        for (UUID id : List.of(first.applicationId(), UUID.randomUUID())) {
            var response = call("GET", timeline(id), null, bearer);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
            assertThat(response.body()).doesNotContain("APPLICATION_SUBMITTED");
        }
        assertThat(count("candidate_access_events")).isZero();
    }

    @Test
    void anAuthorizedReaderGets404ForAnUnknownApplicationWithoutAnAuditEntry() throws Exception {
        var response = call("GET", timeline(UUID.randomUUID()), null, hrToken);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("APPLICATION_NOT_FOUND");
        assertThat(count("candidate_access_events")).isZero();
    }

    @Test
    void scopedReadAndAuditStayOnOneApplicationDespiteTheSharedCandidate() throws Exception {
        note(first.applicationId(), PRIVATE_NOTE, 1);
        note(other.applicationId(), OTHER_PRIVATE_NOTE, 2);

        JsonNode allowed = ok(call("GET", timeline(first.applicationId()), null, recruiterToken));
        assertPage(allowed, first.applicationId(), 0, 20, 2);
        assertThat(allowed.toString()).contains(PRIVATE_NOTE)
                .doesNotContain(OTHER_PRIVATE_NOTE, other.applicationId().toString());
        for (JsonNode event : allowed.path("items")) {
            assertThat(event.path("applicationId").asText()).isEqualTo(first.applicationId().toString());
        }

        var denied = call("GET", timeline(other.applicationId()), null, recruiterToken);
        assertThat(denied.statusCode()).as(denied.body()).isEqualTo(403);
        assertThat(denied.body()).doesNotContain(PRIVATE_NOTE, OTHER_PRIVATE_NOTE, "APPLICATION_SUBMITTED");
        assertThat(count("candidate_access_events")).isEqualTo(2);
        assertAudit(recruiter, first.applicationId(), "GRANTED", 1);
        assertAudit(recruiter, other.applicationId(), "DENIED", 1);
    }

    @Test
    void allReadersAndDepartmentManagersFollowTheExistingCandidateScope() throws Exception {
        String managerToken = token(manager);
        ok(call("GET", timeline(first.applicationId()), null, managerToken));
        assertThat(call("GET", timeline(other.applicationId()), null, managerToken).statusCode()).isEqualTo(403);
        for (UUID id : List.of(first.applicationId(), other.applicationId())) {
            ok(call("GET", timeline(id), null, hrToken));
        }
        UUID admin = applications.account("Timeline Admin", "timeline-admin@example.test", Role.ADMIN);
        ok(call("GET", timeline(other.applicationId()), null, token(admin)));
    }

    @Test
    void removingReadGrantTakesEffectForTheSameTokenAndWriteGrantDoesNotReplaceIt() throws Exception {
        ok(call("GET", timeline(first.applicationId()), null, recruiterToken));
        jdbc.update("DELETE FROM role_permissions WHERE role_code='RECRUITER' AND permission_code='CANDIDATES_READ_SCOPED'");
        try {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM role_permissions WHERE role_code='RECRUITER'"
                    + " AND permission_code='CANDIDATES_WRITE_SCOPED'", Integer.class)).isOne();
            for (UUID id : List.of(first.applicationId(), UUID.randomUUID())) {
                assertThat(call("GET", timeline(id), null, recruiterToken).statusCode()).isEqualTo(403);
            }
            assertThat(count("candidate_access_events")).isOne();
        } finally {
            jdbc.update("INSERT INTO role_permissions(role_code,permission_code)"
                    + " VALUES ('RECRUITER','CANDIDATES_READ_SCOPED')");
        }
    }

    @Test
    void sessionRevocationAndDisabledAccountsInvalidateExistingTokens() throws Exception {
        ok(call("GET", timeline(first.applicationId()), null, recruiterToken));
        jdbc.update("UPDATE auth_sessions SET revoked_at=? WHERE user_id=?", at(0), recruiter);
        assertThat(call("GET", timeline(first.applicationId()), null, recruiterToken).statusCode()).isEqualTo(401);
        jdbc.update("UPDATE user_accounts SET enabled=false WHERE id=?", hr);
        assertThat(call("GET", timeline(first.applicationId()), null, hrToken).statusCode()).isEqualTo(401);
        assertThat(count("candidate_access_events")).isOne();
    }

    @Test
    void anExpiredAccessTokenCannotReadOrAuditTimelineData() throws Exception {
        clock.set(ApplicationTestSupport.NOW.plus(TokenService.ACCESS_TOKEN_TTL).plusSeconds(1));
        var response = call("GET", timeline(first.applicationId()), null, recruiterToken);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        assertThat(count("candidate_access_events")).isZero();
    }

    @Test
    void actualHandoverChangesTimelineScopeWithoutARefreshOrRelogin() throws Exception {
        note(first.applicationId(), PRIVATE_NOTE, 1);
        String newRecruiterToken = token(outsider);
        ok(call("GET", timeline(first.applicationId()), null, recruiterToken));
        assertThat(call("GET", timeline(first.applicationId()), null, newRecruiterToken).statusCode()).isEqualTo(403);

        UUID admin = applications.account("Handover admin", "timeline-handover@example.test", Role.ADMIN);
        String body = json.writeValueAsString(Map.of("requisitions", List.of(
                Map.of("requisitionId", requisition, "toUserId", outsider)), "note", "Bàn giao theo kế hoạch"));
        var handedOver = ok(call("POST", "/api/v1/accounts/" + recruiter + "/handover", body, token(admin)));
        assertThat(handedOver.path("transferred").asInt()).isOne();

        ok(call("GET", "/api/v1/auth/me", null, recruiterToken));
        assertThat(call("GET", timeline(first.applicationId()), null, recruiterToken).statusCode()).isEqualTo(403);
        JsonNode shown = ok(call("GET", timeline(first.applicationId()), null, newRecruiterToken));
        assertThat(shown.toString()).contains(PRIVATE_NOTE);
        assertAudit(recruiter, first.applicationId(), "DENIED", 1);
        assertAudit(outsider, first.applicationId(), "GRANTED", 1);
    }

    @Test
    void responseIncludesExistingEventFamiliesNewestFirstAndOnlySafeEmailMetadata() throws Exception {
        seedStageChange();
        note(first.applicationId(), PRIVATE_NOTE, 4);
        rating(5, 5);
        sentConfirmation();
        sentWithdrawalNotice();
        jdbc.update("INSERT INTO application_notice_outbox(id,kind,application_id,recipient_id,message_id,status)"
                        + " VALUES (?,'RECRUITER_WITHDRAWAL',?,?,?,'PENDING')",
                UUID.randomUUID(), first.applicationId(), recruiter, "pending-delivery@example.test");

        JsonNode page = ok(call("GET", timeline(first.applicationId()), null, recruiterToken));
        assertPage(page, first.applicationId(), 0, 20, 6);
        List<String> types = new ArrayList<>();
        List<Instant> times = new ArrayList<>();
        for (JsonNode event : page.path("items")) {
            types.add(event.path("type").asText());
            times.add(Instant.parse(event.path("occurredAt").asText()));
            assertThat(event.path("applicationId").asText()).isEqualTo(first.applicationId().toString());
        }
        assertThat(types).containsExactlyInAnyOrder("APPLICATION_SUBMITTED", "STAGE_CHANGED", "EMAIL_SENT",
                "EMAIL_SENT", "SCREENING_NOTE_ADDED", "SCREENING_RATING_SNAPSHOT");
        assertThat(times).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(page.toString()).doesNotContain(PRIVATE_MESSAGE_ID, "pending-delivery@example.test",
                "lan@example.test", "recruiter@example.test", "hr@example.test", first.trackingCode(),
                "trackingCode", "tracking_code_hash", "ciphertext", "nonce", "leaseToken", "messageId",
                "lastFailure", "password", "refreshToken");
        // Six displayed events still disclose only one application, so there is one access audit entry.
        assertAudit(recruiter, first.applicationId(), "GRANTED", 1);
    }

    @Test
    void paginationIsBoundedAndTiedTimestampsRemainStableWithoutDuplicates() throws Exception {
        for (int i = 1; i <= 3; i++) {
            note(first.applicationId(), "Tied note " + i, 10);
        }
        rating(4, 10);
        JsonNode all = ok(call("GET", timeline(first.applicationId()) + "?size=100", null, recruiterToken));
        assertPage(all, first.applicationId(), 0, 100, 5);
        List<String> expected = itemJson(all);
        assertThat(expected).doesNotHaveDuplicates();

        List<String> paged = new ArrayList<>();
        for (int page = 0; page < 5; page++) {
            String path = timeline(first.applicationId()) + "?page=" + page + "&size=1";
            JsonNode slice = ok(call("GET", path, null, recruiterToken));
            assertPage(slice, first.applicationId(), page, 1, 5);
            assertThat(slice.path("items").size()).isOne();
            assertThat(itemJson(ok(call("GET", path, null, recruiterToken)))).isEqualTo(itemJson(slice));
            paged.addAll(itemJson(slice));
        }
        assertThat(paged).containsExactlyElementsOf(expected);
        JsonNode beyond = ok(call("GET", timeline(first.applicationId()) + "?page=5&size=1", null, recruiterToken));
        assertPage(beyond, first.applicationId(), 5, 1, 5);
        assertThat(beyond.path("items").isEmpty()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=-1", "?page=1000001", "?page=2147483647", "?size=0", "?size=101", "?page=text"})
    void invalidPaginationIsRejectedWithoutReadingOrLoggingAnApplication(String query) throws Exception {
        var response = call("GET", timeline(first.applicationId()) + query, null, recruiterToken);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(count("candidate_access_events")).isZero();
    }

    @Test
    void publicLookupKeepsItsExistingWhitelistAfterInternalTimelineEventsAreAvailable() throws Exception {
        seedStageChange();
        note(first.applicationId(), PRIVATE_NOTE, 4);
        rating(5, 5);
        sentConfirmation();
        ok(call("GET", timeline(first.applicationId()), null, recruiterToken));

        var response = applications.lookup(first.trackingCode(), "lan@example.test");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("postingTitle", "receivedAt", "status", "statusName", "actions");
        assertThat(response.body()).doesNotContain(PRIVATE_NOTE, PRIVATE_STAGE_REASON, PRIVATE_MESSAGE_ID,
                "timeline", "items", "rating", "changedBy", "actor", "Recruiter A", "HR Nội Bộ",
                recruiter.toString(), first.applicationId().toString(), "STAGE_CHANGED", "EMAIL_SENT");
    }

    @Test
    void timelineHasNoWriteOrDeleteRouteAndCannotBeReadThroughThePublicNamespace() throws Exception {
        note(first.applicationId(), PRIVATE_NOTE, 1);
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            assertThat(call(method, timeline(first.applicationId()), "{}", hrToken).statusCode()).isEqualTo(403);
            assertThat(call(method, timeline(first.applicationId()) + "/" + UUID.randomUUID(), "{}", hrToken)
                    .statusCode()).isEqualTo(403);
        }
        assertThat(call("GET", "/api/v1/public/applications/" + first.applicationId() + "/timeline", null, hrToken)
                .statusCode()).isEqualTo(403);
        assertThat(count("application_screening_notes")).isOne();
        assertThat(count("candidate_access_events")).isZero();
    }

    @Test
    void submissionDistinguishesStaffReferralsFromCandidatesWithoutExposingTheReferralNote() throws Exception {
        UUID posting = applications.publishedPosting("Giới thiệu nội bộ");
        var receipt = applications.refer(recruiterToken, posting, "timeline-referred@example.test");
        assertThat(receipt.statusCode()).as(receipt.body()).isEqualTo(201);
        UUID referred = jdbc.queryForObject("SELECT id FROM job_applications WHERE email=?", UUID.class,
                "timeline-referred@example.test");
        jdbc.update("UPDATE user_accounts SET full_name=? WHERE id=?", "Tên người giới thiệu hiện tại", recruiter);

        JsonNode referral = ok(call("GET", timeline(referred), null, hrToken));
        JsonNode actor = referral.path("items").get(0).path("actor");
        assertThat(actor.path("type").asText()).isEqualTo("STAFF");
        assertThat(actor.path("id").asText()).isEqualTo(recruiter.toString());
        assertThat(actor.path("displayName").asText()).isEqualTo("Tên người giới thiệu hiện tại");
        assertThat(referral.toString()).doesNotContain("timeline-referred@example.test", "recruiter@example.test",
                ApplicationTestSupport.REFERRAL_NOTE);

        JsonNode direct = ok(call("GET", timeline(first.applicationId()), null, hrToken));
        JsonNode candidate = direct.path("items").get(0).path("actor");
        assertThat(candidate.path("type").asText()).isEqualTo("CANDIDATE");
        assertThat(candidate.path("id").isNull()).isTrue();
        assertThat(candidate.path("displayName").isNull()).isTrue();
    }

    private String timeline(UUID application) {
        return "/api/v1/applications/" + application + "/timeline";
    }

    private static void assertPage(JsonNode body, UUID application, int page, int size, long total) {
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("applicationId", "items", "totalElements", "page", "size");
        assertThat(body.path("applicationId").asText()).isEqualTo(application.toString());
        assertThat(body.path("items").isArray()).isTrue();
        assertThat(body.path("page").asInt()).isEqualTo(page);
        assertThat(body.path("size").asInt()).isEqualTo(size);
        assertThat(body.path("totalElements").asLong()).isEqualTo(total);
    }

    private void assertAudit(UUID actor, UUID application, String outcome, int count) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id=?"
                        + " AND application_id=? AND candidate_id=? AND access_kind='PROFILE' AND outcome=?",
                Integer.class, actor, application, first.candidateId(), outcome)).isEqualTo(count);
    }

    private static List<String> itemJson(JsonNode page) {
        List<String> items = new ArrayList<>();
        page.path("items").forEach(item -> items.add(item.toString()));
        return items;
    }

    private void note(UUID application, String text, int secondsAfterSubmission) {
        jdbc.update("INSERT INTO application_screening_notes(id,application_id,content,author_id,created_at) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), application, text, hr, at(secondsAfterSubmission));
    }

    private void rating(int value, int secondsAfterSubmission) {
        jdbc.update("INSERT INTO application_screening_ratings(application_id,rating,updated_by,updated_at) VALUES (?,?,?,?)",
                first.applicationId(), value, hr, at(secondsAfterSubmission));
    }

    private void seedStageChange() {
        jdbc.update("UPDATE job_applications SET stage='SCREENING' WHERE id=?", first.applicationId());
        jdbc.update("INSERT INTO application_stage_events(id,application_id,stage_before,stage_after,actor_type,actor_id,reason,occurred_at)"
                        + " VALUES (?,?,'NEW','SCREENING','STAFF',?,?,?)",
                UUID.randomUUID(), first.applicationId(), recruiter, PRIVATE_STAGE_REASON, at(1));
    }

    private void sentConfirmation() {
        jdbc.update("INSERT INTO application_email_outbox(id,application_id,message_id,status,sent_at) VALUES (?,?,?,'SENT',?)",
                UUID.randomUUID(), first.applicationId(), PRIVATE_MESSAGE_ID, at(2));
    }

    private void sentWithdrawalNotice() {
        jdbc.update("INSERT INTO application_notice_outbox(id,kind,application_id,message_id,status,sent_at)"
                        + " VALUES (?,'CANDIDATE_WITHDRAWAL',?,?,'SENT',?)",
                UUID.randomUUID(), first.applicationId(), "withdrawal-delivery@example.test", at(3));
    }

    private static Timestamp at(int secondsAfterSubmission) {
        return Timestamp.from(ApplicationTestSupport.NOW.plusSeconds(secondsAfterSubmission));
    }
}
