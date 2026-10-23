package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Real API races, including both orders of an evaluation submission against schedule changes. */
class InterviewEvaluationConcurrencyIntegrationTest extends InterviewEvaluationTestSupport {
    private static final String EVALUATION_WRITE = "EVALUATIONS_WRITE_SCOPED";

    @AfterEach
    void restoreGrantAndClock() {
        jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('INTERVIEWER',?) "
                + "ON CONFLICT DO NOTHING", EVALUATION_WRITE);
        clock.set(ApplicationTestSupport.NOW);
    }

    @ParameterizedTest(name = "two requests with the same version: {0}")
    @ValueSource(strings = {"draft", "submit"})
    void concurrentWritesWithTheSameVersionAcceptOnlyOne(String action) throws Exception {
        form(panelToken);
        String body = json.writeValueAsString(payload(0, 4, 5, "HIRE"));
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstWrite = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return evaluationWrite(action, body);
            });
            var secondWrite = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return evaluationWrite(action, body);
            });
            var responses = List.of(firstWrite.get(20, TimeUnit.SECONDS), secondWrite.get(20, TimeUnit.SECONDS));
            assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200, 409);
            for (var response : responses) {
                if (response.statusCode() == 409) {
                    assertThat(json.readTree(response.body()).path("code").asText())
                            .isIn("EVALUATION_VERSION_CHANGED", "EVALUATION_LOCKED");
                }
            }
        }
        var saved = ok(call("GET", ownPath(), null, panelToken));
        assertThat(saved.path("version").asLong()).isEqualTo(1);
        assertThat(saved.path("status").asText()).isEqualTo(action.equals("submit") ? "SUBMITTED" : "DRAFT");
        assertThat(count("evaluation_forms")).isEqualTo(1);
        assertThat(count("evaluation_form_items")).isEqualTo(2);
        assertThat(count("evaluation_events")).isEqualTo(action.equals("submit") ? 1 : 0);
    }

    @ParameterizedTest(name = "submit commits before {0}")
    @ValueSource(strings = {"reschedule", "cancel"})
    void aSubmissionThatWinsTheLockPreventsTheScheduleChange(String action) throws Exception {
        form(panelToken);
        Instant oldStart = startOfInterview();
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            hold(blocker, "interview_sessions", interview);
            Future<HttpResponse<String>> submission;
            Future<HttpResponse<String>> scheduling;
            try {
                submission = executor.submit(this::submit);
                int submitter = applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                scheduling = executor.submit(() -> scheduleChange(action));
                // The scheduler waits on the submitting reviewer account or behind its queued interview lock.
                applications.awaitWaiterOf(submitter);
            } finally {
                blocker.rollback();
            }
            expect(submission.get(10, TimeUnit.SECONDS), 200, null);
            expect(scheduling.get(10, TimeUnit.SECONDS), 409, "INTERVIEW_EVALUATION_SUBMITTED");
        }
        assertThat(jdbc.queryForObject("SELECT status FROM evaluation_forms WHERE evaluator_id=?",
                String.class, panel)).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("SELECT ever_submitted_at FROM interview_sessions WHERE id=?",
                Timestamp.class, interview)).isNotNull();
        assertThat(startOfInterview()).isEqualTo(oldStart);
        assertThat(jdbc.queryForObject("SELECT status FROM interview_sessions WHERE id=?", String.class, interview))
                .isEqualTo("SCHEDULED");
        assertThat(jdbc.queryForObject("SELECT version FROM interview_sessions WHERE id=?", Long.class, interview)).isZero();
        assertThat(count("evaluation_events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_events WHERE interview_id=? "
                + "AND event_type IN ('INTERVIEW_RESCHEDULED','INTERVIEW_CANCELLED')", Long.class, interview)).isZero();
    }

    @ParameterizedTest(name = "{0} commits before submit")
    @ValueSource(strings = {"reschedule", "cancel"})
    void aScheduleChangeThatWinsTheLockInvalidatesThePendingSubmission(String action) throws Exception {
        form(panelToken);
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            hold(blocker, "interview_sessions", interview);
            Future<HttpResponse<String>> scheduling;
            Future<HttpResponse<String>> submission;
            try {
                scheduling = executor.submit(() -> scheduleChange(action));
                int scheduler = applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                submission = executor.submit(this::submit);
                applications.awaitWaiterOf(scheduler);
            } finally {
                blocker.rollback();
            }
            expect(scheduling.get(10, TimeUnit.SECONDS), 200, null);
            var rejected = submission.get(10, TimeUnit.SECONDS);
            expect(rejected, 409, action.equals("reschedule") ? "EVALUATION_NOT_FINISHED" : "INTERVIEW_CANCELLED");
        }
        assertDraftUntouched();
        assertThat(jdbc.queryForObject("SELECT ever_submitted_at FROM interview_sessions WHERE id=?",
                Timestamp.class, interview)).isNull();
        assertThat(jdbc.queryForObject("SELECT version FROM interview_sessions WHERE id=?", Long.class, interview)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_events WHERE interview_id=? AND event_type=?",
                Long.class, interview, action.equals("reschedule") ? "INTERVIEW_RESCHEDULED" : "INTERVIEW_CANCELLED"))
                .isEqualTo(1);
    }

    @ParameterizedTest(name = "{0} during {1} wait")
    @CsvSource({
            "permission-lost,interview", "permission-lost,framework",
            "token-expired,interview", "token-expired,framework"
    })
    void aFormCreationRechecksAuthorityAfterEveryBlockingRead(String change, String held) throws Exception {
        // Warm the endpoint without creating a form; this keeps the bounded wait observation independent of startup.
        assertThat(call("GET", ownPath(), null, panelToken).statusCode()).isEqualTo(404);
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            hold(blocker, held.equals("interview") ? "interview_sessions" : "competency_frameworks",
                    held.equals("interview") ? interview : framework);
            Future<HttpResponse<String>> creation;
            try {
                creation = executor.submit(() -> call("POST", ownPath(), null, panelToken));
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                if (change.equals("permission-lost")) {
                    assertThat(jdbc.update("DELETE FROM role_permissions WHERE role_code='INTERVIEWER' AND permission_code=?",
                            EVALUATION_WRITE)).isEqualTo(1);
                } else {
                    clock.set(ApplicationTestSupport.NOW.plusSeconds(3600));
                }
            } finally {
                blocker.rollback();
            }
            var response = creation.get(10, TimeUnit.SECONDS);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(change.equals("permission-lost") ? 403 : 401);
        }
        assertThat(count("evaluation_forms")).isZero();
        assertThat(count("evaluation_form_items")).isZero();
        assertThat(count("evaluation_events")).isZero();
        assertThat(jdbc.queryForObject("SELECT ever_submitted_at FROM interview_sessions WHERE id=?",
                Timestamp.class, interview)).isNull();
    }

    @ParameterizedTest(name = "{0} while submission waits for reviewer account")
    @ValueSource(strings = {"session-revoked", "account-disabled"})
    void authenticationInvalidatedBeforeTheAccountLockRejectsSubmission(String change) throws Exception {
        form(panelToken);
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            hold(blocker, "user_accounts", panel);
            Future<HttpResponse<String>> submission;
            try {
                submission = executor.submit(this::submit);
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                if (change.equals("session-revoked")) {
                    assertThat(jdbc.update("UPDATE auth_sessions SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",
                            Timestamp.from(ApplicationTestSupport.NOW), panel)).isEqualTo(1);
                } else {
                    try (var update = blocker.prepareStatement("UPDATE user_accounts SET enabled=false WHERE id=?")) {
                        update.setObject(1, panel);
                        assertThat(update.executeUpdate()).isEqualTo(1);
                    }
                }
                blocker.commit();
            } finally {
                blocker.rollback();
            }
            var response = submission.get(10, TimeUnit.SECONDS);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        }
        assertDraftUntouched();
    }

    private HttpResponse<String> evaluationWrite(String action, String body) throws Exception {
        return action.equals("submit") ? call("POST", ownPath() + "/submit", body, panelToken)
                : call("PUT", ownPath(), body, panelToken);
    }

    private HttpResponse<String> submit() throws Exception {
        return evaluationWrite("submit", json.writeValueAsString(payload(0, 4, 5, "HIRE")));
    }

    private HttpResponse<String> scheduleChange(String action) throws Exception {
        String path = "/api/v1/interviews/" + interview;
        if (action.equals("cancel")) {
            return call("POST", path + "/cancel", "{\"expectedVersion\":0,\"reason\":\"Candidate changed plans\"}", hrToken);
        }
        return call("PUT", path, json.writeValueAsString(Map.of(
                "roundName", "Rescheduled technical interview", "startsAt", ApplicationTestSupport.NOW.plusSeconds(7200).toString(),
                "durationMinutes", 60, "mode", "ONSITE", "room", "Room concurrent",
                "participantIds", List.of(panel, peer), "expectedVersion", 0)), hrToken);
    }

    private void assertDraftUntouched() {
        assertThat(jdbc.queryForObject("SELECT status FROM evaluation_forms WHERE evaluator_id=?", String.class, panel))
                .isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT version FROM evaluation_forms WHERE evaluator_id=?", Long.class, panel)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evaluation_form_items WHERE score IS NOT NULL OR comment IS NOT NULL",
                Long.class)).isZero();
        assertThat(count("evaluation_events")).isZero();
    }

    private Instant startOfInterview() {
        return jdbc.queryForObject("SELECT starts_at FROM interview_sessions WHERE id=?", Timestamp.class, interview).toInstant();
    }

    private void hold(Connection connection, String table, UUID id) throws Exception {
        connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement("SELECT id FROM " + table + " WHERE id=? FOR UPDATE")) {
            statement.setObject(1, id);
            statement.executeQuery().close();
        }
    }

    private void expect(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        if (code != null) assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }
}
