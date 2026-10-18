package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ScreeningLockIntegrationTest extends ScreeningTestSupport {
    @Test
    void readAndWriteScopeDowngradesDuringApplicationLockWaitRejectUnassignedActor() throws Exception {
        ok(call("GET", path(first.applicationId()), null, hrToken)); // warm HTTP before bounded lock waits
        for (String permission : new String[]{"CANDIDATES_READ_", "CANDIDATES_WRITE_"}) {
            try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
                hold(blocker, "job_applications", first.applicationId());
                var request = executor.submit(() -> rate(first.applicationId(), hrToken, 4));
                try {
                    applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                    jdbc.update("UPDATE role_permissions SET permission_code=? WHERE role_code='HR_MANAGER' AND permission_code=?",
                            permission + "SCOPED", permission + "ALL");
                } finally { blocker.rollback(); }
                var response = request.get(10, TimeUnit.SECONDS);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
            } finally {
                jdbc.update("UPDATE role_permissions SET permission_code=? WHERE role_code='HR_MANAGER' AND permission_code=?",
                        permission + "ALL", permission + "SCOPED");
            }
        }
        assertThat(count("application_screening_ratings")).isZero();
    }

    @Test
    void handoverWhileRequisitionIsLockedRevokesOldRecruiterBeforeWrite() throws Exception {
        ok(call("GET", path(first.applicationId()), null, recruiterToken));
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            hold(blocker, "recruitment_requisitions", requisition);
            var request = executor.submit(() -> rate(first.applicationId(), recruiterToken, 5));
            applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
            try (var statement = blocker.prepareStatement("UPDATE requisition_recruiters SET recruiter_id=? WHERE requisition_id=? AND assignment_role='PRIMARY'")) {
                statement.setObject(1, outsider); statement.setObject(2, requisition); statement.executeUpdate();
            }
            blocker.commit();
            var response = request.get(10, TimeUnit.SECONDS);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
        }
        assertThat(count("application_screening_ratings")).isZero();
    }

    @Test
    void catalogDeactivatedDuringLockWaitCannotBeAttachedAndExistingTagsRemain() throws Exception {
        ok(call("PUT", path(first.applicationId()) + "/tags", "{\"tagIds\":[\"" + tag + "\"]}", hrToken));
        UUID second = catalog("CANDIDATE_TAG", "Chờ khóa", true);
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            hold(blocker, "recruitment_catalog_items", second);
            var request = executor.submit(() -> call("PUT", path(first.applicationId()) + "/tags",
                    "{\"tagIds\":[\"" + second + "\"]}", hrToken));
            applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
            try (var statement = blocker.prepareStatement("UPDATE recruitment_catalog_items SET active=false WHERE id=?")) {
                statement.setObject(1, second); statement.executeUpdate();
            }
            blocker.commit();
            var response = request.get(10, TimeUnit.SECONDS);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
            assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("SCREENING_TAG_INVALID");
        }
        assertThat(jdbc.queryForList("SELECT tag_id FROM application_screening_tags", UUID.class)).containsExactly(tag);
    }

    @Test
    void permissionScopeIsRecheckedAfterWaitingForCatalogToo() throws Exception {
        ok(call("GET", path(first.applicationId()), null, hrToken));
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            hold(blocker, "recruitment_catalog_items", tag);
            var request = executor.submit(() -> call("PUT", path(first.applicationId()) + "/tags",
                    "{\"tagIds\":[\"" + tag + "\"]}", hrToken));
            try {
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                jdbc.update("UPDATE role_permissions SET permission_code='CANDIDATES_WRITE_SCOPED' WHERE role_code='HR_MANAGER' AND permission_code='CANDIDATES_WRITE_ALL'");
            } finally { blocker.rollback(); }
            var response = request.get(10, TimeUnit.SECONDS);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
        } finally {
            jdbc.update("UPDATE role_permissions SET permission_code='CANDIDATES_WRITE_ALL' WHERE role_code='HR_MANAGER' AND permission_code='CANDIDATES_WRITE_SCOPED'");
        }
        assertThat(count("application_screening_tags")).isZero();
    }

    @Test
    void tokenExpiryDuringApplicationWaitRejectsWrite() throws Exception {
        ok(call("GET", path(first.applicationId()), null, recruiterToken));
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            hold(blocker, "job_applications", first.applicationId());
            var request = executor.submit(() -> rate(first.applicationId(), recruiterToken, 4));
            try {
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                clock.set(ApplicationTestSupport.NOW.plusSeconds(3600));
            } finally { blocker.rollback(); }
            assertThat(request.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(401);
        }
        assertThat(count("application_screening_ratings")).isZero();
    }

    private void hold(Connection connection, String table, UUID id) throws Exception {
        connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement("SELECT id FROM " + table + " WHERE id=? FOR UPDATE")) {
            statement.setObject(1, id); statement.executeQuery().close();
        }
    }
}
