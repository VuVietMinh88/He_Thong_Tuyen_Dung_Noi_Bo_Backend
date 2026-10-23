package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.application.ApplicationSubmission;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 399 (story S5-06): the talent pool list, its title and skill search, its scope and removals. */
class TalentPoolSearchIntegrationTest extends TalentPoolTestSupport {
    UUID javaEntry, accountantEntry, testerEntry;
    ApplicationTestSupport.Stored accountant, tester;

    void arrange() throws Exception {
        accountant = applications.stored(jdbc.queryForObject("SELECT id FROM job_postings WHERE requisition_id = ?",
                UUID.class, otherRequisition), new ApplicationSubmission("Kế Toán Viên", "ketoan@example.test",
                "0902000001", null, null));
        tester = applications.stored(jdbc.queryForObject("SELECT id FROM job_postings WHERE requisition_id = ?",
                UUID.class, requisition), new ApplicationSubmission("Kiểm Thử Viên", "kiemthu@example.test",
                "0902000002", null, null));
        rejectedFrom(first.applicationId(), "INTERVIEW");
        rejectedFrom(accountant.applicationId(), "OFFER");
        rejectedFrom(tester.applicationId(), "INTERVIEW");
        clock.set(ApplicationTestSupport.NOW.plusSeconds(10));
        javaEntry = id(added(recruiterToken, first.applicationId(), List.of("Lập trình viên Java"), List.of("Java", "Spring")));
        clock.set(ApplicationTestSupport.NOW.plusSeconds(20));
        accountantEntry = id(added(hrToken, accountant.applicationId(), List.of("Kế toán tổng hợp"), List.of("Excel")));
        clock.set(ApplicationTestSupport.NOW.plusSeconds(30));
        testerEntry = id(added(recruiterToken, tester.applicationId(), List.of("Kiểm thử phần mềm"), List.of("java")));
        clock.set(ApplicationTestSupport.NOW);
    }

    static UUID id(JsonNode entry) {
        return UUID.fromString(entry.path("entryId").asText());
    }

    List<UUID> list(String token, String query) throws Exception {
        JsonNode page = ok(call("GET", POOL + query, null, token));
        List<UUID> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(UUID.fromString(item.path("entryId").asText())));
        assertThat(page.path("total").asLong()).isEqualTo(ids.size());
        return ids;
    }

    static String title(String value) {
        return "?title=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Test
    void everyReaderListsTheEntriesOfApplicationsInItsScopeNewestFirst() throws Exception {
        arrange();
        assertThat(list(hrToken, "")).containsExactly(testerEntry, accountantEntry, javaEntry);
        assertThat(list(recruiterToken, "")).containsExactly(testerEntry, javaEntry);
        assertThat(list(token(manager), "")).containsExactly(testerEntry, javaEntry);
        assertThat(list(token(outsider), "")).containsExactly(accountantEntry);
        JsonNode page = ok(call("GET", POOL + "?size=1", null, hrToken));
        assertThat(page.path("hasNext").asBoolean()).isTrue();
        assertThat(page.path("items").get(0).path("titleInterests").toString()).isEqualTo("[\"Kiểm thử phần mềm\"]");
        // Marked, not hidden: the tester has no other application, the person of javaEntry is still NEW elsewhere.
        assertThat(page.path("items").get(0).path("candidateStatus").asText()).isEqualTo("AVAILABLE");
        JsonNode all = ok(call("GET", POOL, null, hrToken));
        assertThat(all.path("items").get(2).path("entryId").asText()).isEqualTo(javaEntry.toString());
        assertThat(all.path("items").get(2).path("candidateStatus").asText()).isEqualTo("IN_PIPELINE");
        UUID noRole = applications.account("No role", "none@example.test");
        assertError(call("GET", POOL, null, token(noRole)), 403, "FORBIDDEN");
    }

    @Test
    void titlesAndSkillsAreSearchedWithoutCaseOrAccents() throws Exception {
        arrange();
        assertThat(list(hrToken, title("lap trinh"))).containsExactly(javaEntry);
        assertThat(list(hrToken, title("KẾ TOÁN"))).containsExactly(accountantEntry);
        assertThat(list(hrToken, "?skill=JAVA")).containsExactly(testerEntry, javaEntry);
        assertThat(list(hrToken, "?skill=java&skill=spring")).containsExactly(javaEntry);
        assertThat(list(hrToken, title("kiem thu") + "&skill=java")).containsExactly(testerEntry);
        assertThat(list(hrToken, title("_a"))).isEmpty();
        assertThat(list(recruiterToken, title("ke toan"))).isEmpty();
        for (String invalid : List.of(title("a"), "?size=0", "?page=-1", title("<b>"), "?skill=" + "x".repeat(61))) {
            assertError(call("GET", POOL + invalid, null, hrToken), 400, "VALIDATION_ERROR");
        }
    }

    @Test
    void writersInScopeRemoveAnEntryWhichStaysOnRecord() throws Exception {
        arrange();
        assertError(call("DELETE", POOL + "/" + javaEntry, null, token(outsider)), 403, "FORBIDDEN");
        assertError(call("DELETE", POOL + "/" + javaEntry, null, token(manager)), 403, "FORBIDDEN");
        assertError(call("DELETE", POOL + "/" + UUID.randomUUID(), null, recruiterToken), 404, "TALENT_POOL_ENTRY_NOT_FOUND");
        var removed = call("DELETE", POOL + "/" + testerEntry, null, recruiterToken);
        assertThat(removed.statusCode()).as(removed.body()).isEqualTo(204);
        assertThat(list(recruiterToken, "")).containsExactly(javaEntry);
        JsonNode record = ok(call("GET", POOL + "/" + testerEntry, null, recruiterToken));
        assertThat(record.path("removedBy").path("id").asText()).isEqualTo(recruiter.toString());
        assertThat(record.path("removedAt").isNull()).isFalse();
        assertError(call("DELETE", POOL + "/" + testerEntry, null, recruiterToken), 409, "TALENT_POOL_ENTRY_REMOVED");
        // After a removal the candidate may be added again: a new entry, the old one kept.
        assertThat(id(added(recruiterToken, tester.applicationId(), List.of("Kiểm thử"), List.of()))).isNotEqualTo(testerEntry);
        assertThat(count("talent_pool_entries")).isEqualTo(4);
    }
}
