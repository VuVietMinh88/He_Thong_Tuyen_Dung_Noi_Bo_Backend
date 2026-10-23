package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 407 (story S5-08): an internal user enters a candidate found elsewhere, through every public rule. */
class ManualApplicationIntegrationTest extends ManualApplicationTestSupport {
    @Test
    void assignedRecruiterEntersACandidateThroughTheSharedStorage() throws Exception {
        var response = enter(recruiterToken, requisition, fields("mai@example.test"));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
        JsonNode body = json.readTree(response.body());
        UUID application = UUID.fromString(body.path("applicationId").asText());
        assertThat(body.path("requisitionId").asText()).isEqualTo(requisition.toString());
        assertThat(body.path("stage").asText()).isEqualTo("NEW");
        assertThat(body.path("fullName").asText()).isEqualTo("Hoàng Thị Mai");
        assertThat(body.path("enteredBy").path("id").asText()).isEqualTo(recruiter.toString());
        assertThat(body.path("enteredBy").path("fullName").asText()).isEqualTo("Recruiter A");
        assertThat(response.body()).doesNotContain("trackingCode", "recruiter@example.test", "sha256");

        var stored = jdbc.queryForMap("SELECT stage, phone, normalized_email, source_id, cover_letter, requisition_id"
                + " FROM job_applications WHERE id = ?", application);
        assertThat(stored.get("stage")).isEqualTo("NEW");
        assertThat(stored.get("phone")).isEqualTo("0977888999");
        assertThat(stored.get("normalized_email")).isEqualTo("mai@example.test");
        assertThat(stored.get("source_id")).isEqualTo(source);
        assertThat(stored.get("requisition_id")).isEqualTo(requisition);
        assertThat(jdbc.queryForObject("SELECT filename FROM application_cvs WHERE application_id = ?", String.class,
                application)).isEqualTo("CV Mai.pdf");
        assertThat(jdbc.queryForObject("SELECT entered_by FROM manual_application_intakes WHERE application_id = ?",
                UUID.class, application)).isEqualTo(recruiter);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_applications WHERE application_id = ?",
                Integer.class, application)).isEqualTo(1);
        // The candidate, not the staff member, receives the tracking code: one encrypted confirmation is queued.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_email_outbox WHERE application_id = ?",
                Integer.class, application)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id = ?"
                + " AND application_id = ? AND outcome = 'GRANTED'", Integer.class, recruiter, application)).isEqualTo(1);

        var hrEntry = enter(hrToken, otherRequisition, fields("mai@example.test"));
        assertThat(hrEntry.statusCode()).as(hrEntry.body()).isEqualTo(201);
        // Same email, another requisition: linked to the same candidate profile, like the public form.
        UUID second = UUID.fromString(json.readTree(hrEntry.body()).path("applicationId").asText());
        assertThat(json.readTree(hrEntry.body()).path("candidateId").asText()).isEqualTo(body.path("candidateId").asText());
        assertThat(applications.candidateOf(second)).isEqualTo(applications.candidateOf(application));
    }

    @Test
    void onlyWritersInScopeMayEnterAndNothingIsStoredOtherwise() throws Exception {
        assertError(enter(token(outsider), requisition, fields("mai@example.test")), 403, "FORBIDDEN");
        assertError(enter(token(manager), requisition, fields("mai@example.test")), 403, "FORBIDDEN");
        UUID noRole = applications.account("No role", "none@example.test");
        assertError(enter(token(noRole), requisition, fields("mai@example.test")), 403, "FORBIDDEN");
        assertThat(enter(null, requisition, fields("mai@example.test")).statusCode()).isEqualTo(401);
        jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?", Timestamp.from(ApplicationTestSupport.NOW),
                recruiter);
        assertThat(enter(recruiterToken, requisition, fields("mai@example.test")).statusCode()).isEqualTo(401);
        assertThat(count("manual_application_intakes")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_applications WHERE normalized_email = 'mai@example.test'",
                Integer.class)).isZero();
    }

    @Test
    void thePublicRulesForThePostingTheSourceAndDuplicatesStillApply() throws Exception {
        assertError(enter(hrToken, UUID.randomUUID(), fields("mai@example.test")), 404, "REQUISITION_NOT_FOUND");
        UUID withoutPosting = UUID.randomUUID();
        Timestamp at = Timestamp.from(ApplicationTestSupport.NOW);
        jdbc.update("""
                INSERT INTO recruitment_requisitions(id,position_id,department_id,headcount,reason,status,created_by,created_at,updated_at)
                SELECT ?, position_id, department_id, 1, 'NEW_HEADCOUNT', 'APPROVED', created_by, ?, ? FROM recruitment_requisitions WHERE id = ?
                """, withoutPosting, at, at, requisition);
        assertError(enter(hrToken, withoutPosting, fields("mai@example.test")), 404, "JOB_POSTING_NOT_FOUND");

        jdbc.update("UPDATE job_postings SET application_deadline = ? WHERE requisition_id = ?", LocalDate.of(2026, 10, 1),
                otherRequisition);
        assertError(enter(hrToken, otherRequisition, fields("mai@example.test")), 409, "JOB_POSTING_EXPIRED");

        Map<String, String> inactiveSource = fields("mai@example.test");
        inactiveSource.put("sourceId", catalog("CANDIDATE_SOURCE", "Ngừng dùng", false).toString());
        assertError(enter(recruiterToken, requisition, inactiveSource), 400, "VALIDATION_ERROR");

        // first already applied with lan@example.test: the shared arbiter refuses the same email in the requisition.
        var duplicate = enter(recruiterToken, requisition, fields("  LAN@example.test "));
        assertError(duplicate, 409, "APPLICATION_ALREADY_SUBMITTED");
        assertThat(duplicate.body()).doesNotContain(first.applicationId().toString());
        assertThat(count("manual_application_intakes")).isZero();
        assertThat(count("application_email_outbox")).isZero();
    }

    @Test
    void theFormAndTheCvAreCheckedLikeThePublicOnes() throws Exception {
        assertThat(call("POST", manualPath(requisition), "{}", recruiterToken).statusCode()).isEqualTo(415);
        Map<String, String> noConsent = fields("mai@example.test");
        noConsent.remove("candidateConsent");
        Map<String, String> badEmail = fields("not-an-email");
        Map<String, String> extra = fields("mai@example.test");
        extra.put("trackingCode", "chosen-by-staff");
        Map<String, String> badSource = fields("mai@example.test");
        badSource.put("sourceId", "abc");
        for (Map<String, String> invalid : List.of(noConsent, badEmail, extra, badSource)) {
            assertError(enter(recruiterToken, requisition, invalid), 400, "VALIDATION_ERROR");
        }
        assertError(enter(recruiterToken, requisition, body(fields("mai@example.test"), "cv.pdf", null)), 400,
                "VALIDATION_ERROR");
        var fake = enter(recruiterToken, requisition, body(fields("mai@example.test"), "cv.pdf",
                "not really a pdf".getBytes()));
        assertThat(fake.statusCode()).as(fake.body()).isIn(400, 415);
        var docx = enter(recruiterToken, requisition, body(fields("mai@example.test"), "cv.docx",
                applications.docx().content()));
        assertThat(docx.statusCode()).as(docx.body()).isEqualTo(201);
        assertThat(count("manual_application_intakes")).isEqualTo(1);
    }
}
