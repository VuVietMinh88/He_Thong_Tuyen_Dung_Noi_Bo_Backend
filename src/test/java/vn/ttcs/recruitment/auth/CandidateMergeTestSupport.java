package vn.ttcs.recruitment.auth;

import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.application.ApplicationSubmission;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story S5-01 (tasks 367-370): the screening fixture (first and other are one person, lan@example.test with the
 * default phone 0912345678, in RECRUITER's requisition and in OUTSIDER's otherRequisition) plus helpers to store
 * other people, confirm their contacts, offers and talent pool entries with SQL and call the duplicate and merge
 * APIs.
 */
abstract class CandidateMergeTestSupport extends ScreeningTestSupport {
    static String candidatePath(UUID candidate) {
        return "/api/v1/candidates/" + candidate;
    }

    UUID postingOf(UUID requisitionId) {
        return jdbc.queryForObject("SELECT id FROM job_postings WHERE requisition_id = ?", UUID.class, requisitionId);
    }

    // An application of another person in this requisition, through the production storage.
    ApplicationTestSupport.Stored person(UUID requisitionId, String fullName, String email, String phone) {
        return applications.stored(postingOf(requisitionId), new ApplicationSubmission(fullName, email, phone, null, null));
    }

    // A contact a recruiter confirmed for the application (task 335, V68_1), written with SQL: the name stays the
    // submitted one, the email and phone are the corrected ones.
    void confirm(UUID application, String email, String phone) {
        jdbc.update("""
                INSERT INTO application_confirmed_contacts(application_id,revision,full_name,email,normalized_email,phone,
                    cv_sha256,name_source,email_source,phone_source,confirmed_by,confirmed_by_name,confirmed_at)
                SELECT a.id,1,a.full_name,?,?,?,cv.sha256,'SUBMITTED','MANUAL','MANUAL',?,'HR Nội Bộ',?
                FROM job_applications a JOIN application_cvs cv ON cv.application_id = a.id WHERE a.id = ?
                """, email, email.toLowerCase(Locale.ROOT), phone, hr, Timestamp.from(ApplicationTestSupport.NOW),
                application);
    }

    HttpResponse<String> duplicatesOf(UUID candidate, String token) throws Exception {
        return call("GET", candidatePath(candidate) + "/duplicates", null, token);
    }

    JsonNode duplicates(UUID candidate, String token) throws Exception {
        return ok(duplicatesOf(candidate, token)).path("duplicates");
    }

    static List<String> candidates(JsonNode duplicates) {
        List<String> ids = new ArrayList<>();
        duplicates.forEach(duplicate -> ids.add(duplicate.path("candidateId").asText()));
        return ids;
    }

    static List<String> applicationIds(JsonNode applications) {
        List<String> ids = new ArrayList<>();
        applications.forEach(application -> ids.add(application.path("applicationId").asText()));
        return ids;
    }

    static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    HttpResponse<String> previewOf(UUID target, UUID source, String token) throws Exception {
        return call("GET", candidatePath(target) + "/merge-preview?sourceCandidateId=" + source, null, token);
    }

    HttpResponse<String> mergeOf(UUID target, UUID source, String token) throws Exception {
        return call("POST", candidatePath(target) + "/merge", "{\"sourceCandidateId\":\"" + source + "\"}", token);
    }

    // The candidate an application is linked to now (candidate_applications).
    UUID linkOf(UUID application) {
        return jdbc.queryForObject("SELECT candidate_id FROM candidate_applications WHERE application_id = ?", UUID.class,
                application);
    }

    UUID mergedInto(UUID candidate) {
        return jdbc.queryForObject("SELECT merged_into FROM candidates WHERE id = ?", UUID.class, candidate);
    }

    // An active offer for the application, written with SQL like the offer tests (the application at OFFER first).
    UUID offer(UUID application, String status, java.time.Instant createdAt) {
        UUID offer = UUID.randomUUID();
        jdbc.update("UPDATE job_applications SET stage = 'OFFER' WHERE id = ?", application);
        jdbc.update("""
                INSERT INTO offers(id,application_id,candidate_id,position_id,candidate_name,email,position_title,
                    salary,allowance,start_date,response_deadline,band_min,band_max,outside_band,status,version,
                    approval_round,created_by,submitted_by,hr_approver_id,created_at,updated_at)
                SELECT ?,a.id,ca.candidate_id,r.position_id,a.full_name,a.email,p.name,
                    20000000,0,DATE '2026-12-01',?,10000000,30000000,false,?,0,1,?,?,?,?,?
                FROM job_applications a JOIN candidate_applications ca ON ca.application_id=a.id
                JOIN recruitment_requisitions r ON r.id=a.requisition_id JOIN positions p ON p.id=r.position_id WHERE a.id=?
                """, offer, Timestamp.from(createdAt.plusSeconds(86400 * 10)), status, recruiter, recruiter, hr,
                Timestamp.from(createdAt), Timestamp.from(createdAt), application);
        return offer;
    }

    // An active talent pool entry of the application's candidate, written with SQL.
    UUID poolEntry(UUID application, java.time.Instant addedAt) {
        UUID entry = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO talent_pool_entries(id, candidate_id, source_application_id, evidence, reason, added_by, added_at)
                SELECT ?, candidate_id, application_id, 'FINAL_STAGE_REJECTION', 'Phù hợp vị trí sau', ?, ?
                FROM candidate_applications WHERE application_id = ?
                """, entry, hr, Timestamp.from(addedAt), application);
        return entry;
    }

    void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }

    long granted(UUID actor) {
        return jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id = ? AND outcome = 'GRANTED'",
                Long.class, actor);
    }

    long denied(UUID actor) {
        return jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id = ? AND outcome = 'DENIED'",
                Long.class, actor);
    }
}
