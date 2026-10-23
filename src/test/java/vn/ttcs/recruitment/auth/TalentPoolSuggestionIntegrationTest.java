package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.application.ApplicationSubmission;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 400 (story S5-06): suggestions from the pool for a new opening, by title and skill, read-only and scoped. */
class TalentPoolSuggestionIntegrationTest extends TalentPoolTestSupport {
    UUID opening, javaEntry, testerEntry, sqlEntry, accountantEntry;

    static String suggestionsPath(UUID requisitionId) {
        return "/api/v1/requisitions/" + requisitionId + "/talent-pool-suggestions";
    }

    UUID postingOf(UUID requisitionId) {
        return jdbc.queryForObject("SELECT id FROM job_postings WHERE requisition_id = ?", UUID.class, requisitionId);
    }

    UUID entry(String token, ApplicationTestSupport.Stored application, String before, List<String> titles,
               List<String> skills) throws Exception {
        rejectedFrom(application.applicationId(), before);
        return UUID.fromString(added(token, application.applicationId(), titles, skills).path("entryId").asText());
    }

    void arrange() throws Exception {
        UUID openingPosting = applications.publishedPosting("Java Developer");
        opening = applications.requisitionOf(openingPosting);
        applications.assign(opening, recruiter, "PRIMARY", hr);
        UUID position = jdbc.queryForObject("SELECT position_id FROM recruitment_requisitions WHERE id = ?", UUID.class,
                opening);
        ok(call("PUT", "/api/v1/positions/" + position + "/required-criteria", json.writeValueAsString(Map.of(
                "requiredSkills", List.of("Java", "SQL"), "expectedVersion", 0)), hrToken));
        var tester = applications.stored(postingOf(requisition), new ApplicationSubmission("Kiểm Thử Viên",
                "kiemthu@example.test", "0902000002", null, null));
        var accountant = applications.stored(postingOf(otherRequisition), new ApplicationSubmission("Kế Toán Viên",
                "ketoan@example.test", "0902000001", null, null));
        var sql = applications.stored(postingOf(otherRequisition), new ApplicationSubmission("Dữ Liệu Viên",
                "dulieu@example.test", "0902000003", null, null));
        var applied = applications.stored(postingOf(requisition), new ApplicationSubmission("Đã Nộp",
                "danop@example.test", "0902000004", null, null));
        javaEntry = entry(recruiterToken, first, "INTERVIEW", List.of("Java Developer"), List.of("java"));
        // other is the same person as first: closed, so the person is free again.
        applications.stage(other.applicationId(), ApplicationStage.WITHDRAWN);
        testerEntry = entry(recruiterToken, tester, "INTERVIEW", List.of("Kiểm thử"), List.of("Java", "SQL"));
        accountantEntry = entry(hrToken, accountant, "OFFER", List.of("Kế toán"), List.of("Excel"));
        sqlEntry = entry(hrToken, sql, "INTERVIEW", List.of("Lập trình viên"), List.of("sql"));
        entry(recruiterToken, applied, "INTERVIEW", List.of("Developer"), List.of("Java", "SQL"));
        // This candidate already applied to the new opening: never suggested for it.
        applications.stored(openingPosting, new ApplicationSubmission("Đã Nộp", "danop@example.test", "0902000004",
                null, null));
    }

    static List<UUID> entries(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(UUID.fromString(item.path("entry").path("entryId").asText())));
        return ids;
    }

    @Test
    void pooledCandidatesAreSuggestedByTitleThenByMatchedSkills() throws Exception {
        arrange();
        long applicationsBefore = count("job_applications");
        JsonNode all = ok(call("GET", suggestionsPath(opening), null, hrToken));
        assertThat(entries(all)).containsExactly(javaEntry, testerEntry, sqlEntry);
        assertThat(all.path("basisTitles").toString()).isEqualTo("[\"Developer\",\"Java Developer\"]");
        assertThat(all.path("basisSkills").toString()).isEqualTo("[\"Java\",\"SQL\"]");
        assertThat(all.path("advice").asText()).contains("không tự tạo hồ sơ");
        JsonNode first = all.path("items").get(0);
        assertThat(first.path("matchedTitles").toString()).isEqualTo("[\"Java Developer\"]");
        assertThat(first.path("matchedSkills").toString()).isEqualTo("[\"java\"]");
        assertThat(all.path("items").get(1).path("matchedSkills").toString()).isEqualTo("[\"Java\",\"SQL\"]");
        assertThat(all.path("items").get(1).path("matchedTitles").size()).isZero();
        assertThat(entries(all)).doesNotContain(accountantEntry);
        // Suggestions are advice: nothing is created, moved or rejected.
        assertThat(count("job_applications")).isEqualTo(applicationsBefore);
        assertThat(jdbc.queryForList("SELECT DISTINCT stage FROM job_applications WHERE requisition_id = ?", String.class,
                opening)).containsExactly("NEW");
        assertThat(ok(call("GET", suggestionsPath(opening) + "?limit=1", null, hrToken)).path("items").size()).isEqualTo(1);
    }

    @Test
    void candidatesHiredOrInAnotherActivePipelineAreNotSuggested() throws Exception {
        arrange();
        applications.stage(other.applicationId(), ApplicationStage.INTERVIEW);
        assertThat(entries(ok(call("GET", suggestionsPath(opening), null, hrToken)))).containsExactly(testerEntry, sqlEntry);
        applications.stage(other.applicationId(), ApplicationStage.HIRED);
        assertThat(entries(ok(call("GET", suggestionsPath(opening), null, hrToken)))).containsExactly(testerEntry, sqlEntry);
        applications.stage(other.applicationId(), ApplicationStage.REJECTED);
        assertThat(entries(ok(call("GET", suggestionsPath(opening), null, hrToken))))
                .containsExactly(javaEntry, testerEntry, sqlEntry);
        // The pool list still shows the entry, marked.
        assertThat(ok(call("GET", POOL + "/" + javaEntry, null, hrToken)).path("candidateStatus").asText())
                .isEqualTo("AVAILABLE");
    }

    @Test
    void suggestionsFollowTheReadersScopeAndRemovals() throws Exception {
        arrange();
        assertThat(entries(ok(call("GET", suggestionsPath(opening), null, recruiterToken))))
                .containsExactly(javaEntry, testerEntry);
        assertThat(call("DELETE", POOL + "/" + testerEntry, null, recruiterToken).statusCode()).isEqualTo(204);
        assertThat(entries(ok(call("GET", suggestionsPath(opening), null, recruiterToken)))).containsExactly(javaEntry);
        assertError(call("GET", suggestionsPath(opening), null, token(outsider)), 403, "FORBIDDEN");
        assertError(call("GET", suggestionsPath(UUID.randomUUID()), null, hrToken), 404, "REQUISITION_NOT_FOUND");
        for (String invalid : List.of("?limit=0", "?limit=51", "?limit=x")) {
            assertError(call("GET", suggestionsPath(opening) + invalid, null, hrToken), 400, "VALIDATION_ERROR");
        }
        UUID noRole = applications.account("No role", "none@example.test");
        assertError(call("GET", suggestionsPath(opening), null, token(noRole)), 403, "FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id = ? AND outcome = 'GRANTED'"
                + " AND application_id = ?", Integer.class, recruiter, first.applicationId())).isGreaterThanOrEqualTo(2);
    }

    @Test
    void anOpeningWithoutTitleOrSkillMatchesSuggestsNobody() throws Exception {
        UUID posting = applications.publishedPosting("Kế toán");
        UUID lonely = applications.requisitionOf(posting);
        jdbc.update("UPDATE positions SET name = 'Tài chính' WHERE id = (SELECT position_id FROM recruitment_requisitions"
                + " WHERE id = ?)", lonely);
        entry(recruiterToken, first, "INTERVIEW", List.of("Java Developer"), List.of("Java"));
        JsonNode none = ok(call("GET", suggestionsPath(lonely), null, hrToken));
        assertThat(none.path("items").size()).isZero();
        assertThat(none.path("basisSkills").size()).isZero();
    }
}
