package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.account.Role;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The feedback account-handover API and S5 candidate APIs must agree on the current owner. Use the actual handover
 * endpoint and keep the same access tokens before and after: no SQL reassignment or fresh login can hide stale scope.
 */
@TestPropertySource(properties = "app.migration.lineage=feedback")
class AccountHandoverCandidateScopeIntegrationTest extends ScreeningTestSupport {
    private static final String PRIVATE_NOTE = "Ghi chú của recruiter trước khi bàn giao";
    private static final String BOARD = "/api/v1/application-board";

    @Test
    void recruiterHandoverImmediatelyMovesCandidateBoardAndScreeningScopeWithoutLosingAssessment() throws Exception {
        String recipientToken = token(outsider);
        ok(rate(first.applicationId(), recruiterToken, 4));
        ok(call("PUT", path(first.applicationId()) + "/tags",
                json.writeValueAsString(Map.of("tagIds", List.of(tag))), recruiterToken));
        ok(call("POST", path(first.applicationId()) + "/notes",
                json.writeValueAsString(Map.of("content", PRIVATE_NOTE)), recruiterToken));

        assertThat(applicationIds(profile(recruiterToken))).containsExactly(first.applicationId().toString());
        var beforeRecipient = profile(recipientToken);
        assertThat(applicationIds(beforeRecipient)).containsExactly(other.applicationId().toString());
        assertThat(beforeRecipient.toString()).doesNotContain(PRIVATE_NOTE, first.applicationId().toString());
        assertThat(boardIds(recruiterToken, "")).containsExactly(first.applicationId().toString());
        assertThat(boardIds(recipientToken, "")).containsExactly(other.applicationId().toString());
        assertThat(call("GET", path(first.applicationId()), null, recipientToken).statusCode()).isEqualTo(403);

        JsonNode handover = handOver(recruiter, Map.of("requisitions", List.of(
                Map.of("requisitionId", requisition, "toUserId", outsider)), "note", "Bàn giao tuyển dụng"));

        assertThat(handover.path("transferred").asInt()).isOne();
        assertThat(handover.path("remaining").path("requisitions").isEmpty()).isTrue();
        // Handover changes record scope, without locking the old account or revoking its session.
        ok(call("GET", "/api/v1/auth/me", null, recruiterToken));
        assertThat(call("GET", profilePath(), null, recruiterToken).statusCode()).isEqualTo(403);
        assertThat(boardIds(recruiterToken, "")).isEmpty();
        assertThat(call("GET", BOARD + "?requisitionId=" + requisition, null, recruiterToken).statusCode()).isEqualTo(403);
        assertThat(ok(call("GET", BOARD + "/positions", null, recruiterToken)).path("total").asLong()).isZero();
        assertThat(call("GET", path(first.applicationId()), null, recruiterToken).statusCode()).isEqualTo(403);
        assertThat(rate(first.applicationId(), recruiterToken, 1).statusCode()).isEqualTo(403);
        assertThat(call("POST", path(first.applicationId()) + "/notes", "{\"content\":\"Out of scope\"}",
                recruiterToken).statusCode()).isEqualTo(403);

        assertThat(applicationIds(profile(recipientToken))).containsExactlyInAnyOrder(
                first.applicationId().toString(), other.applicationId().toString());
        assertThat(boardIds(recipientToken, "")).containsExactlyInAnyOrder(
                first.applicationId().toString(), other.applicationId().toString());
        assertThat(ok(call("GET", BOARD + "/positions", null, recipientToken)).path("total").asLong()).isEqualTo(2);
        var assessment = ok(call("GET", path(first.applicationId()), null, recipientToken));
        assertThat(assessment.path("rating").path("value").asInt()).isEqualTo(4);
        assertThat(assessment.path("tags").size()).isOne();
        assertThat(assessment.path("notes").size()).isOne();
        assertThat(assessment.path("notes").get(0).path("content").asText()).isEqualTo(PRIVATE_NOTE);
        assertThat(assessment.path("notes").get(0).path("authorId").asText()).isEqualTo(recruiter.toString());
        assertThat(ok(rate(first.applicationId(), recipientToken, 5)).path("rating").path("updatedBy").asText())
                .isEqualTo(outsider.toString());
    }

    @Test
    void departmentHandoverMovesDescendantCandidateReadScopeWithoutGrantingScreeningWrite() throws Exception {
        UUID department = department(requisition);
        UUID childDepartment = department(otherRequisition);
        UUID childManager = jdbc.queryForObject("SELECT manager_user_id FROM departments WHERE id = ?", UUID.class,
                childDepartment);
        jdbc.update("UPDATE departments SET parent_id = ? WHERE id = ?", department, childDepartment);
        UUID newManager = applications.account("Manager mới", "new-manager@example.test", Role.HIRING_MANAGER);
        String oldManagerToken = token(manager), newManagerToken = token(newManager);
        assertThat(applicationIds(profile(oldManagerToken))).containsExactlyInAnyOrder(
                first.applicationId().toString(), other.applicationId().toString());
        assertThat(call("GET", profilePath(), null, newManagerToken).statusCode()).isEqualTo(403);
        for (UUID opening : List.of(requisition, otherRequisition)) {
            assertThat(boardIds(oldManagerToken, "?requisitionId=" + opening)).hasSize(1);
            assertThat(call("GET", BOARD + "?requisitionId=" + opening, null, newManagerToken).statusCode()).isEqualTo(403);
        }

        JsonNode result = handOver(manager, Map.of("departments", List.of(
                Map.of("departmentId", department, "toUserId", newManager))));

        assertThat(result.path("transferred").asInt()).isOne();
        assertThat(jdbc.queryForObject("SELECT manager_user_id FROM departments WHERE id = ?", UUID.class,
                childDepartment)).isEqualTo(childManager);
        assertThat(call("GET", profilePath(), null, oldManagerToken).statusCode()).isEqualTo(403);
        assertThat(applicationIds(profile(newManagerToken))).containsExactlyInAnyOrder(
                first.applicationId().toString(), other.applicationId().toString());
        for (UUID opening : List.of(requisition, otherRequisition)) {
            assertThat(call("GET", BOARD + "?requisitionId=" + opening, null, oldManagerToken).statusCode()).isEqualTo(403);
            assertThat(boardIds(newManagerToken, "?requisitionId=" + opening)).hasSize(1);
        }
        for (UUID application : List.of(first.applicationId(), other.applicationId())) {
            assertThat(call("GET", path(application), null, oldManagerToken).statusCode()).isEqualTo(403);
            ok(call("GET", path(application), null, newManagerToken));
            // The manager receives the scope, not a CANDIDATES_WRITE permission.
            assertThat(rate(application, newManagerToken, 5).statusCode()).isEqualTo(403);
        }
        assertThat(count("application_screening_ratings")).isZero();
    }

    private JsonNode handOver(UUID from, Map<String, Object> body) throws Exception {
        UUID admin = applications.account("Admin", "handover-admin@example.test", Role.ADMIN);
        return ok(call("POST", "/api/v1/accounts/" + from + "/handover", json.writeValueAsString(body), token(admin)));
    }

    private UUID department(UUID opening) {
        return jdbc.queryForObject("SELECT department_id FROM recruitment_requisitions WHERE id = ?", UUID.class, opening);
    }

    private String profilePath() {
        return "/api/v1/candidates/" + first.candidateId() + "/unified-profile";
    }

    private JsonNode profile(String token) throws Exception {
        return ok(call("GET", profilePath(), null, token));
    }

    private static List<String> applicationIds(JsonNode profile) {
        List<String> ids = new ArrayList<>();
        profile.path("applications").forEach(application -> ids.add(application.path("id").asText()));
        return ids;
    }

    private List<String> boardIds(String token, String query) throws Exception {
        List<String> ids = new ArrayList<>();
        ok(call("GET", BOARD + query, null, token)).path("columns").forEach(column ->
                column.path("cards").forEach(card -> ids.add(card.path("applicationId").asText())));
        return ids;
    }
}
