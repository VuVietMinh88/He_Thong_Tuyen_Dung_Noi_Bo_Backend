package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import vn.ttcs.recruitment.account.Role;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** A locked actor must not block another writer's assignee foreign-key/key-share check. */
class OnboardingActorLockIntegrationTest extends ScreeningTestSupport {
    @Test
    void twoHrActorsCanAssignEachOtherAfterBothHaveLockedTheirOwnAccount() throws Exception {
        UUID secondHr = applications.account("HR Two", "hr-onboarding-lock@example.test", Role.HR_MANAGER);
        String secondToken = token(secondHr);
        UUID department = jdbc.queryForObject("SELECT department_id FROM recruitment_requisitions WHERE id=?",
                UUID.class, requisition);
        String firstBody = templateBody(department, secondHr);
        String secondBody = templateBody(department, hr);
        try (Connection blocker = dataSource.getConnection(); var pool = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try (var hold = blocker.prepareStatement("SELECT id FROM departments WHERE id=? FOR UPDATE")) {
                hold.setObject(1, department);
                hold.executeQuery().close();
            }
            var firstWrite = pool.submit(() -> call("POST", "/api/v1/onboarding/templates", firstBody, hrToken));
            var secondWrite = pool.submit(() -> call("POST", "/api/v1/onboarding/templates", secondBody, secondToken));
            try {
                // Both requests are now past the actor/session locks and waiting for the same department.
                // Releasing them together would create an A->B->A cycle with actor FOR UPDATE locks.
                awaitTwoWaiters(ApplicationTestSupport.backendPid(blocker));
            } finally {
                blocker.rollback();
            }
            var firstResponse = firstWrite.get(10, TimeUnit.SECONDS);
            var secondResponse = secondWrite.get(10, TimeUnit.SECONDS);
            assertThat(firstResponse.statusCode()).as(firstResponse.body()).isEqualTo(201);
            assertThat(secondResponse.statusCode()).as(secondResponse.body()).isEqualTo(201);
        }
        assertThat(count("onboarding_templates")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT assignee_id FROM onboarding_template_items", UUID.class))
                .containsExactlyInAnyOrder(hr, secondHr);
    }

    private String templateBody(UUID department, UUID assignee) {
        return json.writeValueAsString(Map.of("departmentId", department, "name", "Mutual assignment", "active", true,
                "items", List.of(Map.of("title", "Prepare workstation", "assigneeId", assignee, "dueOffsetDays", 0))));
    }

    private void awaitTwoWaiters(int blockerPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1700);
        long waiting;
        do {
            waiting = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid))",
                    Long.class, blockerPid);
            if (waiting >= 2) return;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        assertThat(waiting).as("both HR requests reached the department lock").isGreaterThanOrEqualTo(2);
    }
}
