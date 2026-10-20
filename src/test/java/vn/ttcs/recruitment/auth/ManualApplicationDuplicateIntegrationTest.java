package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.application.ApplicationSubmission;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 408 (story S5-08): manual entries go through the shared duplicate detection, before and after the entry. */
class ManualApplicationDuplicateIntegrationTest extends ManualApplicationTestSupport {
    static String checkPath(UUID requisitionId) {
        return manualPath(requisitionId) + "/duplicate-check";
    }

    String check(String email, String phone) {
        return json.writeValueAsString(Map.of("email", email, "phone", phone));
    }

    static List<String> ids(JsonNode matches) {
        List<String> ids = new ArrayList<>();
        matches.forEach(match -> ids.add(match.path("applicationId").asText()));
        return ids;
    }

    @Test
    void theCheckReportsTheSameEmailInScopeAndTheRefusalItWouldMeet() throws Exception {
        JsonNode assigned = ok(call("POST", checkPath(requisition), check("  LAN@example.test", "0900000000"),
                recruiterToken));
        assertThat(assigned.path("emailInRequisition").asBoolean()).isTrue();
        assertThat(ids(assigned.path("sameEmail"))).containsExactly(first.applicationId().toString());
        assertThat(assigned.path("sameEmail").get(0).path("sameRequisition").asBoolean()).isTrue();
        assertThat(assigned.path("sameEmail").get(0).path("stageName").asText()).isEqualTo("Hồ sơ mới");
        // "other" is the same person in a requisition RECRUITER does not handle: never reported to it.
        assertThat(assigned.toString()).doesNotContain(other.applicationId().toString());

        JsonNode all = ok(call("POST", checkPath(requisition), check("lan@example.test", "0900000000"), hrToken));
        assertThat(ids(all.path("sameEmail")))
                .containsExactlyInAnyOrder(first.applicationId().toString(), other.applicationId().toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id = ? AND outcome = 'GRANTED'",
                Integer.class, recruiter)).isEqualTo(1);

        JsonNode fresh = ok(call("POST", checkPath(requisition), check("new@example.test", "0900000000"), recruiterToken));
        assertThat(fresh.path("emailInRequisition").asBoolean()).isFalse();
        assertThat(fresh.path("sameEmail").size()).isZero();
        assertThat(fresh.path("samePhone").size()).isZero();
        assertThat(count("job_applications")).isEqualTo(2);
    }

    @Test
    void theRefusalIsReportedEvenWhenTheListOfSameEmailApplicationsIsFull() throws Exception {
        // 21 newer applications of the same person elsewhere push first (the requisition's own) out of the 20 listed.
        jdbc.update("UPDATE job_applications SET received_at = ? WHERE id = ?",
                java.sql.Timestamp.from(ApplicationTestSupport.NOW.minusSeconds(86400)), first.applicationId());
        for (int index = 0; index < 21; index++) {
            applications.stored(applications.publishedPosting("Vị trí " + index), "lan@example.test");
        }
        JsonNode full = ok(call("POST", checkPath(requisition), check("lan@example.test", "0900000000"), hrToken));
        assertThat(full.path("sameEmail").size()).isEqualTo(20);
        assertThat(ids(full.path("sameEmail"))).doesNotContain(first.applicationId().toString());
        assertThat(full.path("emailInRequisition").asBoolean()).isTrue();
        assertThat(ok(call("POST", checkPath(requisition), check("mai@example.test", "0900000000"), hrToken))
                .path("emailInRequisition").asBoolean()).isFalse();
    }

    @Test
    void aPhoneInItsNationalOrInternationalFormFindsOtherProfiles() throws Exception {
        // first was stored with 0912345678 and lan@example.test.
        JsonNode phone = ok(call("POST", checkPath(requisition), check("mai@example.test", "+84 912 345 678"),
                recruiterToken));
        assertThat(ids(phone.path("samePhone"))).containsExactly(first.applicationId().toString());
        assertThat(phone.path("sameEmail").size()).isZero();
        var international = applications.stored(postingOf(requisition), new ApplicationSubmission("Lý Văn Bảo",
                "bao@example.test", "+84988777666", null, null));
        JsonNode national = ok(call("POST", checkPath(requisition), check("mai@example.test", "0988-777-666"),
                recruiterToken));
        assertThat(ids(national.path("samePhone"))).containsExactly(international.applicationId().toString());
    }

    @Test
    void theEntryAnswersWithTheApplicationsItWasGroupedWith() throws Exception {
        UUID third = applications.requisitionOf(applications.publishedPosting("Kế toán"));
        var response = enter(hrToken, third, fields("lan@example.test"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("candidateId").asText()).isEqualTo(first.candidateId().toString());
        assertThat(ids(body.path("duplicates").path("sameEmail")))
                .containsExactlyInAnyOrder(first.applicationId().toString(), other.applicationId().toString());
        assertThat(body.path("duplicates").path("sameEmail").get(0).path("sameRequisition").asBoolean()).isFalse();
        // The fixture phone 0977 888 999 is nobody else's.
        assertThat(body.path("duplicates").path("samePhone").size()).isZero();

        var phoneTwin = enter(recruiterToken, requisition, Map.of("fullName", "Đỗ Thị Hoa", "email", "hoa@example.test",
                "phone", "0912 345 678", "candidateConsent", "true"));
        assertThat(phoneTwin.statusCode()).as(phoneTwin.body()).isEqualTo(201);
        JsonNode twin = json.readTree(phoneTwin.body());
        assertThat(ids(twin.path("duplicates").path("samePhone"))).containsExactly(first.applicationId().toString());
        assertThat(twin.path("duplicates").path("sameEmail").size()).isZero();
    }

    @Test
    void theCheckFollowsTheEntrysCallerRulesAndValidation() throws Exception {
        String body = check("lan@example.test", "0912345678");
        assertThat(call("POST", checkPath(requisition), body, token(outsider)).statusCode()).isEqualTo(403);
        assertThat(call("POST", checkPath(requisition), body, token(manager)).statusCode()).isEqualTo(403);
        assertThat(call("POST", checkPath(requisition), body, null).statusCode()).isEqualTo(401);
        var unknown = call("POST", checkPath(UUID.randomUUID()), body, hrToken);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(json.readTree(unknown.body()).path("code").asText()).isEqualTo("REQUISITION_NOT_FOUND");
        for (String invalid : List.of(check("not-an-email", "0912345678"), check("lan@example.test", "12"), "{}",
                "{\"email\":\"lan@example.test\",\"phone\":\"0912345678\",\"requisitionId\":\"x\"}")) {
            var response = call("POST", checkPath(requisition), invalid, recruiterToken);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        }
        assertThat(count("candidate_access_events")).isZero();
    }
}
