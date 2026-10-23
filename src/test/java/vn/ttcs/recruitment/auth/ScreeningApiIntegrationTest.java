package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import vn.ttcs.recruitment.account.Role;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScreeningApiIntegrationTest extends ScreeningTestSupport {
    @Test
    void hrConfiguresCandidateTagsAndRecruiterReadsActiveOptions() throws Exception {
        String route = "/api/v1/recruitment-catalogs/CANDIDATE_TAG/items";
        var created = call("POST", route, "{\"code\":\"JAVA\",\"name\":\"Kinh nghiệm Java\",\"active\":true}", hrToken);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        catalog("CANDIDATE_TAG", "Nhãn ngừng dùng", false);
        var options = call("GET", route + "?active=true", null, recruiterToken);
        assertThat(options.statusCode()).as(options.body()).isEqualTo(200);
        assertThat(options.body()).contains("Kinh nghiệm Java").doesNotContain("Nhãn ngừng dùng");
        assertThat(call("POST", route, "{\"code\":\"OTHER\",\"name\":\"Khác\",\"active\":true}", recruiterToken).statusCode()).isEqualTo(403);
    }

    @Test
    void assignedRecruiterWritesMultipleTagsSharedRatingAndNotesWithAuthorTime() throws Exception {
        UUID second = catalog("CANDIDATE_TAG", "Java", true);
        var tags = ok(call("PUT", path(first.applicationId()) + "/tags",
                json.writeValueAsString(java.util.Map.of("tagIds", List.of(tag, second))), recruiterToken));
        assertThat(tags.path("tags").size()).isEqualTo(2);
        assertThat(tags.path("tags").get(0).path("applicationId").asText()).isEqualTo(first.applicationId().toString());
        ok(rate(first.applicationId(), recruiterToken, 2));
        var rating = ok(rate(first.applicationId(), hrToken, 5)).path("rating");
        assertThat(rating.path("value").asInt()).isEqualTo(5);
        assertThat(rating.path("updatedBy").asText()).isEqualTo(hr.toString());
        assertThat(count("application_screening_ratings")).isEqualTo(1);
        var notes = ok(call("POST", path(first.applicationId()) + "/notes", "{\"content\":\"Cần hỏi kỹ\\r\\nKinh nghiệm Java\"}", recruiterToken));
        assertThat(notes.path("notes").get(0).path("content").asText()).isEqualTo("Cần hỏi kỹ\nKinh nghiệm Java");
        assertThat(notes.path("notes").get(0).path("authorName").asText()).isEqualTo("Recruiter A");
        assertThat(notes.path("notes").get(0).path("createdAt").asText()).isEqualTo(ApplicationTestSupport.NOW.toString());
        assertThat(notes.toString()).doesNotContain("recruiter@example.test", "password", "trackingCode");
        assertThat(ok(call("PUT", path(first.applicationId()) + "/tags", "{\"tagIds\":[]}", recruiterToken)).path("tags").size()).isZero();
    }

    @Test
    void anonymousNoReadAndOutOfScopeCannotReadOrWriteEvenThroughSharedCandidate() throws Exception {
        assertThat(rate(first.applicationId(), null, 3).statusCode()).isEqualTo(401);
        UUID noRole = applications.account("No role", "none@example.test");
        for (UUID id : List.of(first.applicationId(), UUID.randomUUID())) {
            assertThat(call("GET", path(id), null, token(noRole)).statusCode()).isEqualTo(403);
        }
        assertThat(call("GET", path(other.applicationId()), null, recruiterToken).statusCode()).isEqualTo(403);
        assertThat(rate(other.applicationId(), recruiterToken, 4).statusCode()).isEqualTo(403);
        assertThat(rate(first.applicationId(), token(outsider), 4).statusCode()).isEqualTo(403);
        assertThat(rate(first.applicationId(), token(manager), 4).statusCode()).isEqualTo(403);
        assertThat(count("application_screening_ratings")).isZero();
        ok(call("GET", path(first.applicationId()), null, token(manager)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE outcome='DENIED' AND application_id=?", Integer.class, other.applicationId())).isEqualTo(1);
    }

    @Test
    void writeGrantDoesNotImplyReadAndRevokedSessionsAreRejected() throws Exception {
        jdbc.update("DELETE FROM role_permissions WHERE role_code='RECRUITER' AND permission_code='CANDIDATES_READ_SCOPED'");
        try { assertThat(rate(first.applicationId(), recruiterToken, 4).statusCode()).isEqualTo(403); }
        finally { jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('RECRUITER','CANDIDATES_READ_SCOPED')"); }
        jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?", Timestamp.from(ApplicationTestSupport.NOW), recruiter);
        assertThat(rate(first.applicationId(), recruiterToken, 4).statusCode()).isEqualTo(401);
        assertThat(count("application_screening_ratings")).isZero();
    }

    @Test
    void wrongInactiveMissingDuplicateAndOversizedTagsAreRejectedWithoutChanges() throws Exception {
        UUID inactive = catalog("CANDIDATE_TAG", "Ngừng dùng", false);
        UUID wrong = catalog("CANDIDATE_SOURCE", "Nguồn", true);
        for (UUID id : List.of(inactive, wrong, UUID.randomUUID())) {
            var result = call("PUT", path(first.applicationId()) + "/tags", "{\"tagIds\":[\"" + id + "\"]}", hrToken);
            assertThat(result.statusCode()).as(result.body()).isEqualTo(400);
        }
        for (String body : List.of("{}", "{\"tagIds\":null}", "{\"tagIds\":[null]}",
                "{\"tagIds\":[\"" + tag + "\",\"" + tag + "\"]}", "{\"tagIds\":[],\"authorId\":\"ignored\"}")) {
            assertThat(call("PUT", path(first.applicationId()) + "/tags", body, hrToken).statusCode()).isEqualTo(400);
        }
        String oversized = json.writeValueAsString(java.util.Map.of("tagIds", java.util.stream.IntStream.range(0,31).mapToObj(n -> UUID.randomUUID()).toList()));
        assertThat(call("PUT", path(first.applicationId()) + "/tags", oversized, hrToken).statusCode()).isEqualTo(400);
        assertThat(count("application_screening_tags")).isZero();
    }

    @Test
    void ratingMustBeJsonIntegerOneToFiveAndBodyCannotSpoofActor() throws Exception {
        for (String value : List.of("0", "6", "-1", "2.5", "2.0", "\"3\"", "true", "null", "2147483648")) {
            assertThat(call("PUT", path(first.applicationId()) + "/rating", "{\"rating\":" + value + "}", hrToken).statusCode()).as(value).isEqualTo(400);
        }
        assertThat(call("PUT", path(first.applicationId()) + "/rating", "{\"rating\":4,\"updatedBy\":\"other\"}", hrToken).statusCode()).isEqualTo(400);
        assertThat(count("application_screening_ratings")).isZero();
    }

    @Test
    void noteRejectsBlankInvisibleHtmlLongTextAndAuthorSpoofing() throws Exception {
        for (String content : List.of("", "  ", "\u200b\ufeff", "<script>alert(1)</script>", "a".repeat(2001))) {
            var result = call("POST", path(first.applicationId()) + "/notes", json.writeValueAsString(java.util.Map.of("content", content)), hrToken);
            assertThat(result.statusCode()).as(result.body()).isEqualTo(400);
        }
        assertThat(call("POST", path(first.applicationId()) + "/notes", "{\"content\":\"note\",\"authorId\":\"spoof\"}", hrToken).statusCode()).isEqualTo(400);
        assertThat(count("application_screening_notes")).isZero();
    }

    @Test
    void pagesAreBoundedCountsAreTotalsAndNoNoteMutationRoutesExist() throws Exception {
        for (int index = 0; index < 3; index++) ok(call("POST", path(first.applicationId()) + "/notes", "{\"content\":\"Note " + index + "\"}", recruiterToken));
        var firstPage = ok(call("GET", path(first.applicationId()) + "?page=0&size=1", null, recruiterToken));
        var nextPage = ok(call("GET", path(first.applicationId()) + "?page=1&size=1", null, recruiterToken));
        assertThat(firstPage.path("totalNotes").asLong()).isEqualTo(3);
        assertThat(firstPage.path("notes").size()).isEqualTo(1);
        assertThat(firstPage.path("notes").get(0).path("id").asText()).isNotEqualTo(nextPage.path("notes").get(0).path("id").asText());
        for (String query : List.of("?page=-1", "?size=0", "?size=101", "?page=2147483647")) {
            assertThat(call("GET", path(first.applicationId()) + query, null, hrToken).statusCode()).isEqualTo(400);
        }
        for (String method : List.of("PATCH", "DELETE")) assertThat(call(method,
                path(first.applicationId()) + "/notes/" + firstPage.path("notes").get(0).path("id").asText(), "{}", hrToken).statusCode()).isEqualTo(403);
        assertThat(call("POST", "/api/v1/candidates/" + first.candidateId() + "/notes", "{\"content\":\"unsafe\"}", hrToken).statusCode()).isEqualTo(403);
        assertThat(call("GET", path(UUID.randomUUID()), null, hrToken).statusCode()).isEqualTo(404);
    }
}
