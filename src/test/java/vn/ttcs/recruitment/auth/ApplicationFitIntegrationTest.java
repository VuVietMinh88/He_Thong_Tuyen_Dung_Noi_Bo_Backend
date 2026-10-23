package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.application.ApplicationSubmission;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.fit.FitRefreshWorker;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Task 413 (story S5-09): fit results per criterion, refreshed after criteria changes, advisory only. */
class ApplicationFitIntegrationTest extends ScreeningTestSupport {
    @Autowired FitRefreshWorker refresher;

    UUID position(UUID requisitionId) {
        return jdbc.queryForObject("SELECT position_id FROM recruitment_requisitions WHERE id = ?", UUID.class, requisitionId);
    }

    static String fitPath(UUID application) {
        return "/api/v1/applications/" + application + "/fit";
    }

    static String qualificationsPath(UUID application) {
        return "/api/v1/applications/" + application + "/qualifications";
    }

    String qualifications(Object years, Object education, List<String> skills, Long version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("experienceYears", years);
        body.put("educationLevel", education);
        body.put("skills", skills);
        if (version != null) body.put("expectedVersion", version);
        return json.writeValueAsString(body);
    }

    void criteria(Integer years, String education, List<String> skills, long version) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("minExperienceYears", years);
        body.put("requiredEducation", education);
        body.put("requiredSkills", skills);
        body.put("expectedVersion", version);
        ok(call("PUT", "/api/v1/positions/" + position(requisition) + "/required-criteria",
                json.writeValueAsString(body), hrToken));
    }

    Map<String, Object> stored(UUID application) {
        return jdbc.queryForMap("SELECT status, required_met, score, criteria_version, qualifications_version"
                + " FROM application_fit_results WHERE application_id = ?", application);
    }

    void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }

    @Test
    void everyNewApplicationGetsAResultAndCriteriaChangesAreShownLiveThenRefreshed() throws Exception {
        assertThat(stored(first.applicationId())).containsEntry("status", "NO_CRITERIA").containsEntry("score", null);
        criteria(3, "BACHELOR", List.of("Java"), 0);
        // The save recomputes nothing: the stored row names the old criteria, and readers evaluate it live.
        assertThat(stored(first.applicationId())).containsEntry("status", "NO_CRITERIA").containsEntry("criteria_version", 0L);
        JsonNode live = ok(call("GET", fitPath(first.applicationId()), null, recruiterToken));
        assertThat(live.path("fit").path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(live.path("fit").path("current").asBoolean()).isFalse();
        // The refresh rewrites the stale rows only.
        assertThat(refresher.refreshStale()).isEqualTo(1);
        assertThat(stored(first.applicationId())).containsEntry("status", "UNKNOWN").containsEntry("required_met", false)
                .containsEntry("criteria_version", 1L);
        assertThat(((Number) stored(first.applicationId()).get("score")).intValue()).isZero();
        assertThat(ok(call("GET", fitPath(first.applicationId()), null, recruiterToken)).path("fit").path("current")
                .asBoolean()).isTrue();
        assertThat(refresher.refreshStale()).isZero();
        // other belongs to another position: untouched by this position's criteria.
        assertThat(stored(other.applicationId())).containsEntry("status", "NO_CRITERIA");

        var fresh = applications.stored(jdbc.queryForObject("SELECT id FROM job_postings WHERE requisition_id = ?",
                UUID.class, requisition), new ApplicationSubmission("Mới", "moi@example.test", "0901234567", null, null));
        assertThat(stored(fresh.applicationId())).containsEntry("status", "UNKNOWN").containsEntry("criteria_version", 1L);
        JsonNode fit = ok(call("GET", fitPath(fresh.applicationId()), null, recruiterToken));
        assertThat(fit.path("fit").path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(fit.path("fit").path("statusName").asText()).isEqualTo("Chưa đủ dữ liệu để đánh giá");
        assertThat(fit.path("fit").path("current").asBoolean()).isTrue();
        assertThat(fit.path("qualifications").isNull()).isTrue();
        assertThat(fit.path("criteria").path("requiredSkills").toString()).isEqualTo("[\"Java\"]");
        assertThat(fit.path("fit").path("criteria").size()).isEqualTo(3);
        assertThat(fit.path("fit").path("criteria").get(0).path("status").asText()).isEqualTo("UNKNOWN");
    }

    @Test
    void recordedQualificationsGiveAResultPerCriterionWithoutChangingTheStage() throws Exception {
        criteria(3, "BACHELOR", List.of("Java", "SQL"), 0);
        JsonNode met = ok(call("PUT", qualificationsPath(first.applicationId()),
                qualifications(4.5, "MASTER", List.of(" java ", "sql", "Docker"), 0L), recruiterToken));
        assertThat(met.path("qualifications").path("experienceYears").decimalValue()).isEqualByComparingTo("4.5");
        assertThat(met.path("qualifications").path("skills").toString()).isEqualTo("[\"java\",\"sql\",\"Docker\"]");
        assertThat(met.path("qualifications").path("recordedBy").path("id").asText()).isEqualTo(recruiter.toString());
        assertThat(met.path("qualifications").path("version").asLong()).isEqualTo(1);
        assertThat(met.path("fit").path("status").asText()).isEqualTo("MET");
        assertThat(met.path("fit").path("requiredMet").asBoolean()).isTrue();
        assertThat(met.path("fit").path("score").asInt()).isEqualTo(100);
        assertThat(stored(first.applicationId())).containsEntry("status", "MET").containsEntry("qualifications_version", 1L);

        JsonNode notMet = ok(call("PUT", qualificationsPath(first.applicationId()),
                qualifications(2, "COLLEGE", List.of("Java"), 1L), hrToken));
        assertThat(notMet.path("fit").path("status").asText()).isEqualTo("NOT_MET");
        assertThat(notMet.path("fit").path("requiredMet").asBoolean()).isFalse();
        // 0 of 34 + 0 of 33 + half of 33 (17) = 17.
        assertThat(notMet.path("fit").path("score").asInt()).isEqualTo(17);
        JsonNode skills = notMet.path("fit").path("criteria").get(2);
        assertThat(skills.path("matchedSkills").toString()).isEqualTo("[\"Java\"]");
        assertThat(skills.path("missingSkills").toString()).isEqualTo("[\"SQL\"]");

        // A looser position: shown at once (live), stored by the next refresh.
        criteria(1, null, List.of(), 1);
        JsonNode looser = ok(call("GET", fitPath(first.applicationId()), null, recruiterToken));
        assertThat(looser.path("fit").path("status").asText()).isEqualTo("MET");
        assertThat(looser.path("fit").path("current").asBoolean()).isFalse();
        refresher.refreshStale();
        assertThat(stored(first.applicationId())).containsEntry("status", "MET").containsEntry("criteria_version", 2L);

        // The score is advice only: the stage and its history never move.
        assertThat(jdbc.queryForObject("SELECT stage FROM job_applications WHERE id = ?", String.class,
                first.applicationId())).isEqualTo("NEW");
        assertThat(count("application_stage_events")).isZero();
    }

    @Test
    void skillsLeftOutStayUnknownWhileAnEmptyListMeansNone() throws Exception {
        criteria(null, null, List.of("Java"), 0);
        JsonNode unrecorded = ok(call("PUT", qualificationsPath(first.applicationId()),
                qualifications(2, null, null, 0L), recruiterToken));
        assertThat(unrecorded.path("qualifications").path("skills").isNull()).isTrue();
        assertThat(unrecorded.path("fit").path("status").asText()).isEqualTo("UNKNOWN");
        JsonNode none = ok(call("PUT", qualificationsPath(first.applicationId()),
                qualifications(2, null, List.of(), 1L), recruiterToken));
        assertThat(none.path("qualifications").path("skills").size()).isZero();
        assertThat(none.path("fit").path("status").asText()).isEqualTo("NOT_MET");
        assertThat(none.path("fit").path("criteria").get(0).path("missingSkills").toString()).isEqualTo("[\"Java\"]");
    }

    // A wait inside the fit hook (here the foreign key share lock of the result on a position that a position update
    // holds) that ends after the deadline's business midnight refuses the application like a late one.
    @Test
    void aWaitInTheFitHookThatEndsAfterTheDeadlineRefusesTheApplication() throws Exception {
        UUID posting = jdbc.queryForObject("SELECT id FROM job_postings WHERE requisition_id = ?", UUID.class, requisition);
        jdbc.update("UPDATE job_postings SET application_deadline = ? WHERE id = ?", LocalDate.of(2026, 10, 15), posting);
        clock.set(Instant.parse("2026-10-15T16:59:59Z"));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holderPid = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var holder = executor.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM positions WHERE id = ? FOR UPDATE", UUID.class, position(requisition));
                holderPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var late = executor.submit(() -> applications.stored(posting, "late@example.test"));
                int waiter = applications.awaitWaiterOf(holderPid.get());
                assertThat(jdbc.queryForObject("SELECT query FROM pg_stat_activity WHERE pid = ?", String.class, waiter))
                        .contains("application_fit_results");
                clock.set(Instant.parse("2026-10-15T17:00:00Z"));
                release.countDown();
                holder.get(20, TimeUnit.SECONDS);
                assertThatThrownBy(() -> late.get(20, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .cause().isInstanceOf(ApiException.class)
                        .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT)
                        .hasFieldOrPropertyWithValue("code", "JOB_POSTING_EXPIRED");
                assertThat(jdbc.queryForObject("SELECT count(*) FROM job_applications WHERE normalized_email = ?",
                        Integer.class, "late@example.test")).isZero();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void readersAndWritersFollowTheCandidateScope() throws Exception {
        String body = qualifications(3, "BACHELOR", List.of(), 0L);
        assertError(call("PUT", qualificationsPath(first.applicationId()), body, token(outsider)), 403, "FORBIDDEN");
        assertError(call("PUT", qualificationsPath(first.applicationId()), body, token(manager)), 403, "FORBIDDEN");
        assertError(call("PUT", qualificationsPath(UUID.randomUUID()), body, recruiterToken), 404, "APPLICATION_NOT_FOUND");
        ok(call("GET", fitPath(first.applicationId()), null, token(manager)));
        assertError(call("GET", fitPath(first.applicationId()), null, token(outsider)), 403, "FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id = ? AND outcome = 'DENIED'",
                Integer.class, outsider)).isEqualTo(1);
        assertError(call("GET", fitPath(UUID.randomUUID()), null, recruiterToken), 404, "APPLICATION_NOT_FOUND");
        UUID noRole = applications.account("No role", "none@example.test");
        assertError(call("GET", fitPath(first.applicationId()), null, token(noRole)), 403, "FORBIDDEN");
        assertThat(count("application_qualifications")).isZero();
    }

    @Test
    void staleVersionsAndInvalidQualificationsAreRefused() throws Exception {
        ok(call("PUT", qualificationsPath(first.applicationId()), qualifications(1, null, null, 0L), recruiterToken));
        assertError(call("PUT", qualificationsPath(first.applicationId()), qualifications(2, null, null, 0L),
                recruiterToken), 409, "QUALIFICATIONS_CHANGED");
        List<String> tooMany = IntStream.range(0, 51).mapToObj(index -> "Skill " + index).toList();
        for (String invalid : List.of(qualifications(60.5, null, null, 1L), qualifications(2.55, null, null, 1L),
                qualifications(-1, null, null, 1L), qualifications(null, null, tooMany, 1L),
                qualifications(null, null, List.of("<script>"), 1L), qualifications(null, null, null, null))) {
            assertError(call("PUT", qualificationsPath(first.applicationId()), invalid, recruiterToken), 400,
                    "VALIDATION_ERROR");
        }
        for (String invalid : List.of(qualifications(null, "PHD", null, 1L),
                "{\"expectedVersion\":1,\"score\":100}", "{\"expectedVersion\":1,\"stage\":\"REJECTED\"}")) {
            assertError(call("PUT", qualificationsPath(first.applicationId()), invalid, recruiterToken), 400,
                    "INVALID_JSON");
        }
        assertThat(jdbc.queryForObject("SELECT version FROM application_qualifications WHERE application_id = ?",
                Long.class, first.applicationId())).isEqualTo(1);
    }
}
