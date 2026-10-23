package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.application.ApplicationSubmission;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story S5-05 (tasks 392 ff.): the screening fixture plus two more applications of the first requisition. "first"
 * (Nguyễn Văn A) and "other" are one person in two requisitions; "binh" came from the LinkedIn source; "withdrawn"
 * withdrew. RECRUITER is assigned to the first requisition, the outsider to the other one, the manager manages the
 * first requisition's department, HR reads everything.
 */
abstract class CandidateSearchTestSupport extends ScreeningTestSupport {
    static final String SEARCH = "/api/v1/candidate-search";
    ApplicationTestSupport.Stored binh, withdrawn;
    UUID linkedIn;

    @BeforeEach
    void searchFixture() {
        linkedIn = applications.source("LinkedIn");
        UUID posting = postingOf(requisition);
        binh = applications.stored(posting, new ApplicationSubmission("Trần Thị Bình", "binh@example.test",
                "0987654321", linkedIn, "Tôi có 5 năm kinh nghiệm Java và Spring."));
        withdrawn = applications.stored(posting, new ApplicationSubmission("Lê Văn Rút", "rut@example.test",
                "0901111222", null, null));
        applications.stage(withdrawn.applicationId(), ApplicationStage.WITHDRAWN);
    }

    UUID postingOf(UUID requisitionId) {
        return jdbc.queryForObject("SELECT id FROM job_postings WHERE requisition_id = ?", UUID.class, requisitionId);
    }

    UUID positionOf(UUID requisitionId) {
        return jdbc.queryForObject("SELECT position_id FROM recruitment_requisitions WHERE id = ?", UUID.class,
                requisitionId);
    }

    void rating(UUID application, int value) {
        jdbc.update("INSERT INTO application_screening_ratings(application_id,rating,updated_by,updated_at) VALUES (?,?,?,?)",
                application, value, hr, Timestamp.from(ApplicationTestSupport.NOW));
    }

    void tagged(UUID application, UUID tagId) {
        jdbc.update("INSERT INTO application_screening_tags(application_id,tag_id,added_by,added_at) VALUES (?,?,?,?)",
                application, tagId, hr, Timestamp.from(ApplicationTestSupport.NOW));
    }

    void receivedAt(UUID application, Instant at) {
        jdbc.update("UPDATE job_applications SET received_at = ? WHERE id = ?", Timestamp.from(at), application);
    }

    static String query(String... pairs) {
        StringBuilder query = new StringBuilder();
        for (int index = 0; index < pairs.length; index += 2) {
            query.append(index == 0 ? "?" : "&").append(pairs[index]).append('=')
                    .append(URLEncoder.encode(pairs[index + 1], StandardCharsets.UTF_8));
        }
        return query.toString();
    }

    JsonNode search(String token, String... pairs) throws Exception {
        return ok(call("GET", SEARCH + query(pairs), null, token));
    }

    static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(UUID.fromString(item.path("applicationId").asText())));
        return ids;
    }

    JsonNode item(JsonNode page, UUID application) {
        for (JsonNode item : page.path("items")) {
            if (item.path("applicationId").asText().equals(application.toString())) return item;
        }
        throw new AssertionError("Application not in the page");
    }

    void assertError(java.net.http.HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
    }

    long granted(UUID actor) {
        return jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id = ? AND outcome = 'GRANTED'"
                + " AND access_kind = 'PROFILE'", Long.class, actor);
    }
}
