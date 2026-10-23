package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.application.ApplicationSubmission;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 398 (story S5-06): only final-stage rejections with evidence enter the pool, with a reason and interests. */
class TalentPoolEntryIntegrationTest extends TalentPoolTestSupport {
    @Test
    void aRejectionAfterTheInterviewEntersThePoolWithItsReasonTitlesAndSkills() throws Exception {
        rejectedFrom(first.applicationId(), "INTERVIEW");
        JsonNode entry = added(recruiterToken, first.applicationId(), List.of("Lập trình viên Java", " lap trinh vien JAVA"),
                List.of("Java", "Spring"));
        assertThat(entry.path("candidateId").asText()).isEqualTo(first.candidateId().toString());
        assertThat(entry.path("sourceApplicationId").asText()).isEqualTo(first.applicationId().toString());
        assertThat(entry.path("evidence").asText()).isEqualTo("FINAL_STAGE_REJECTION");
        assertThat(entry.path("evidenceName").asText()).isEqualTo("Bị loại ở vòng phỏng vấn hoặc offer");
        assertThat(entry.path("reason").asText()).isEqualTo("Phỏng vấn tốt, thiếu kinh nghiệm quản lý");
        assertThat(entry.path("titleInterests").toString()).isEqualTo("[\"Lập trình viên Java\"]");
        assertThat(entry.path("skills").toString()).isEqualTo("[\"Java\",\"Spring\"]");
        assertThat(entry.path("addedBy").path("id").asText()).isEqualTo(recruiter.toString());
        assertThat(entry.path("fullName").asText()).isEqualTo(ApplicationTestSupport.FULL_NAME);
        assertThat(entry.path("removedAt").isNull()).isTrue();
        assertThat(entry.toString()).doesNotContain("trackingCode", "coverLetter");
        // Nothing about the application changes.
        assertThat(jdbc.queryForObject("SELECT stage FROM job_applications WHERE id = ?", String.class,
                first.applicationId())).isEqualTo("REJECTED");
        assertThat(count("application_stage_events")).isEqualTo(1);

        UUID id = UUID.fromString(entry.path("entryId").asText());
        assertThat(ok(call("GET", POOL + "/" + id, null, token(manager))).path("entryId").asText()).isEqualTo(id.toString());
        // The same person cannot have two active entries, even from another application.
        rejectedFrom(other.applicationId(), "OFFER");
        assertError(add(hrToken, other.applicationId(), List.of("Kế toán"), List.of()), 409, "TALENT_POOL_ALREADY_ADDED");
        assertThat(count("talent_pool_entries")).isEqualTo(1);
    }

    @Test
    void anEntryShowsWhetherItsCandidateIsAvailableInAnotherPipelineOrHired() throws Exception {
        // first and other are one person; other is still NEW in another requisition.
        rejectedFrom(first.applicationId(), "INTERVIEW");
        JsonNode entry = added(recruiterToken, first.applicationId(), List.of("Java"), List.of());
        assertThat(entry.path("candidateStatus").asText()).isEqualTo("IN_PIPELINE");
        assertThat(entry.path("candidateStatusName").asText()).isEqualTo("Đang có hồ sơ đang xử lý");
        String path = POOL + "/" + entry.path("entryId").asText();
        applications.stage(other.applicationId(), ApplicationStage.HIRED);
        assertThat(ok(call("GET", path, null, recruiterToken)).path("candidateStatus").asText()).isEqualTo("HIRED");
        applications.stage(other.applicationId(), ApplicationStage.WITHDRAWN);
        JsonNode available = ok(call("GET", path, null, recruiterToken));
        assertThat(available.path("candidateStatus").asText()).isEqualTo("AVAILABLE");
        // The flag never discloses the other application itself.
        assertThat(available.toString()).doesNotContain(other.applicationId().toString(), otherRequisition.toString());
    }

    @Test
    void hiringDecisionsAndDeclinedOffersAreEvidenceToo() throws Exception {
        UUID posting = jdbc.queryForObject("SELECT id FROM job_postings WHERE requisition_id = ?", UUID.class, requisition);
        var decided = applications.stored(posting, new ApplicationSubmission("Ứng viên Hai", "hai@example.test",
                "0901000002", null, null));
        var declined = applications.stored(posting, new ApplicationSubmission("Ứng viên Ba", "ba@example.test",
                "0901000003", null, null));
        rejectedByHiringDecision(decided.applicationId());
        declinedOffer(declined.applicationId(), declined.candidateId());
        assertThat(added(recruiterToken, decided.applicationId(), List.of("Kiểm thử"), List.of()).path("evidence").asText())
                .isEqualTo("HIRING_DECISION");
        assertThat(added(recruiterToken, declined.applicationId(), List.of("Kiểm thử"), List.of()).path("evidence").asText())
                .isEqualTo("OFFER_DECLINED");
    }

    @Test
    void earlyRejectionsAndActiveApplicationsDoNotQualify() throws Exception {
        assertError(add(recruiterToken, first.applicationId(), List.of("Java"), List.of()), 409, "TALENT_POOL_NOT_ELIGIBLE");
        rejectedFrom(first.applicationId(), "SCREENING");
        assertError(add(recruiterToken, first.applicationId(), List.of("Java"), List.of()), 409, "TALENT_POOL_NOT_ELIGIBLE");
        // A rejection after the interview that was later reopened is not current: the stage decides first.
        rejectedFrom(other.applicationId(), "INTERVIEW");
        applications.stage(other.applicationId(), ApplicationStage.OFFER);
        assertError(add(hrToken, other.applicationId(), List.of("Java"), List.of()), 409, "TALENT_POOL_NOT_ELIGIBLE");
        assertThat(count("talent_pool_entries")).isZero();
    }

    @Test
    void onlyWritersInScopeAddAndOnlyReadersInScopeRead() throws Exception {
        rejectedFrom(first.applicationId(), "INTERVIEW");
        assertError(add(token(outsider), first.applicationId(), List.of("Java"), List.of()), 403, "FORBIDDEN");
        assertError(add(token(manager), first.applicationId(), List.of("Java"), List.of()), 403, "FORBIDDEN");
        assertError(add(recruiterToken, UUID.randomUUID(), List.of("Java"), List.of()), 404, "APPLICATION_NOT_FOUND");
        UUID id = UUID.fromString(added(recruiterToken, first.applicationId(), List.of("Java"), List.of())
                .path("entryId").asText());
        assertError(call("GET", POOL + "/" + id, null, token(outsider)), 403, "FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id = ? AND outcome = 'DENIED'",
                Integer.class, outsider)).isEqualTo(1);
        assertError(call("GET", POOL + "/" + UUID.randomUUID(), null, recruiterToken), 404, "TALENT_POOL_ENTRY_NOT_FOUND");
        UUID noRole = applications.account("No role", "none@example.test");
        assertError(call("GET", POOL + "/" + id, null, token(noRole)), 403, "FORBIDDEN");
        jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?", Timestamp.from(now()), hr);
        assertThat(add(hrToken, first.applicationId(), List.of("Java"), List.of()).statusCode()).isEqualTo(401);
    }

    @Test
    void invalidRequestsAreRefusedWithoutChanges() throws Exception {
        rejectedFrom(first.applicationId(), "INTERVIEW");
        for (String invalid : List.of(addBody(null, "Lý do", List.of("Java"), List.of()),
                addBody(first.applicationId(), "   ", List.of("Java"), List.of()),
                addBody(first.applicationId(), "x".repeat(1001), List.of("Java"), List.of()),
                addBody(first.applicationId(), "<b>Lý do</b>", List.of("Java"), List.of()),
                addBody(first.applicationId(), "Lý do", List.of(), List.of()),
                addBody(first.applicationId(), "Lý do", List.of("x".repeat(101)), List.of()),
                addBody(first.applicationId(), "Lý do", List.of("Java"), java.util.Collections.nCopies(1, "<x>")))) {
            assertError(call("POST", POOL, invalid, recruiterToken), 400, "VALIDATION_ERROR");
        }
        assertError(call("POST", POOL, "{\"applicationId\":\"" + first.applicationId()
                + "\",\"reason\":\"Lý do\",\"titleInterests\":[\"Java\"],\"candidateId\":\"x\"}", recruiterToken), 400,
                "INVALID_JSON");
        assertThat(count("talent_pool_entries")).isZero();
    }
}
