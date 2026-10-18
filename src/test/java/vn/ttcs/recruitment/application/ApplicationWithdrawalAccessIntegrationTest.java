package vn.ttcs.recruitment.application;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.auth.AuthIntegrationTest;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 344 (story S4-06): ApplicationWithdrawalAccess verifies a withdrawal link (the tracking code and the email it
 * was sent from) and locks exactly one application, the requisition first, the application second, checking the
 * credential again on the locked row. Real PostgreSQL, no HTTP (the withdrawal endpoint is task 345). Lock waits are
 * observed with pg_blocking_pids and NOWAIT probes from other connections, never with sleeps.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.page-url=https://portal.example.test/tra-cuu",
        "app.application-confirmation.poll-delay-ms=3600000", "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-tracking.cleanup-delay-ms=3600000",
        "spring.mail.host=127.0.0.1", "spring.mail.port=1"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationWithdrawalAccessIntegrationTest {
    private static final String LOCKED = "55P03";
    private static final String FREE = "free";
    private static final String LOCK_APPLICATION =
            "SELECT 1 FROM job_applications WHERE id = ? FOR NO KEY UPDATE NOWAIT";
    private static final String LOCK_REQUISITION =
            "SELECT 1 FROM recruitment_requisitions WHERE id = ? FOR NO KEY UPDATE NOWAIT";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationWithdrawalAccess access;
    @Autowired private ApplicationStorageService storage;
    @Autowired private CvValidator validator;

    private final SecureRandom random = new SecureRandom();
    private UUID java;
    private UUID accounting;

    // An application as its candidate knows it (the code) and as SQL knows it (the ids).
    private record Stored(UUID id, UUID requisitionId, String code, Instant receivedAt) {
        @Override public String toString() { return "Stored[" + id + ", tracking code redacted]"; }
    }

    // Applications and their children, in key order; postings, requisitions and accounts of earlier tests stay (every
    // test makes its own with fresh ids). The access log rejects DELETE (V33); TRUNCATE fires no row trigger.
    @BeforeEach
    void fixture() {
        jdbc.execute("TRUNCATE candidate_access_events");
        for (String table : List.of("candidate_applications", "candidates", "application_email_outbox",
                "application_cvs", "job_applications")) {
            jdbc.update("DELETE FROM " + table);
        }
        java = posting("Lập trình viên Java");
        accounting = posting("Kế toán tổng hợp");
    }

    // One email, two applications in two requisitions: A's code locks A and A's requisition, nothing of B; B's code
    // locks B only. The application is returned as locked (a stage set by SQL included), and nothing is written.
    @Test
    void aCodeLocksItsOwnApplicationAndRequisitionOnly() {
        var a = stored(java, "lan@example.test");
        var b = stored(accounting, "lan@example.test");
        jdbc.update("UPDATE job_applications SET stage = 'INTERVIEW' WHERE id = ?", a.id());
        String before = versions(a, b);

        var lockedA = inTransaction(TransactionDefinition.ISOLATION_DEFAULT, () -> {
            var locked = access.lock(credential(a.code(), " LAN@Example.test "));
            assertThat(probe(LOCK_APPLICATION, a.id())).isEqualTo(LOCKED);
            // FOR NO KEY UPDATE on the requisition: a share lock conflicts with it, a key share lock does not (so the
            // keys of rows inserted under it, the withdrawal's stage event of task 345 included, are not blocked).
            assertThat(probe("SELECT 1 FROM recruitment_requisitions WHERE id = ? FOR SHARE NOWAIT", a.requisitionId()))
                    .isEqualTo(LOCKED);
            assertThat(probe("SELECT 1 FROM recruitment_requisitions WHERE id = ? FOR KEY SHARE NOWAIT",
                    a.requisitionId())).isEqualTo(FREE);
            assertThat(probe("SELECT 1 FROM job_applications WHERE id = ? FOR KEY SHARE NOWAIT", a.id()))
                    .isEqualTo(FREE);
            assertThat(probe(LOCK_APPLICATION, b.id())).isEqualTo(FREE);
            assertThat(probe(LOCK_REQUISITION, b.requisitionId())).isEqualTo(FREE);
            return locked;
        });
        assertThat(lockedA).contains(new ApplicationWithdrawalAccess.LockedApplication(a.id(), a.requisitionId(),
                ApplicationStage.INTERVIEW, "Lập trình viên Java", a.receivedAt()));

        var lockedB = inTransaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> {
            var locked = access.lock(credential(b.code(), "lan@example.test"));
            assertThat(probe(LOCK_APPLICATION, b.id())).isEqualTo(LOCKED);
            assertThat(probe(LOCK_REQUISITION, b.requisitionId())).isEqualTo(LOCKED);
            assertThat(probe(LOCK_APPLICATION, a.id())).isEqualTo(FREE);
            assertThat(probe(LOCK_REQUISITION, a.requisitionId())).isEqualTo(FREE);
            return locked;
        });
        assertThat(lockedB).contains(new ApplicationWithdrawalAccess.LockedApplication(b.id(), b.requisitionId(),
                ApplicationStage.NEW, "Kế toán tổng hợp", b.receivedAt()));
        // Row locks leave xmin as it was; any UPDATE of these rows would not.
        assertThat(versions(a, b)).isEqualTo(before);
    }

    // A code with another person's email (an application of the same requisition), an unknown code and a revoked code
    // find nothing, through the same statement, and leave the requisition and the application unlocked.
    @Test
    void aWrongEmailAnUnknownCodeOrARevokedCodeFindsAndLocksNothing() {
        var a = stored(java, "lan@example.test");
        var other = stored(java, "minh@example.test");
        assertNothingFor(credential(a.code(), "minh@example.test"), a);
        assertNothingFor(credential(other.code(), "lan@example.test"), a);
        assertNothingFor(credential(unknownCode(), "lan@example.test"), a);

        jdbc.update("UPDATE job_applications SET tracking_revoked_at = now() WHERE id = ?", a.id());
        assertNothingFor(credential(a.code(), "lan@example.test"), a);
        // The other application of the requisition is still found with its own pair.
        assertThat(inTransaction(TransactionDefinition.ISOLATION_DEFAULT,
                () -> access.lock(credential(other.code(), "minh@example.test"))))
                .map(ApplicationWithdrawalAccess.LockedApplication::id).contains(other.id());
    }

    // While another transaction holds the requisition, the call waits there, before it has touched the application row:
    // the row can still be locked from a third connection. Once the requisition is released, the call locks the row.
    @Test
    void theCallWaitsOnTheRequisitionBeforeLockingTheApplication() throws Exception {
        var a = stored(java, "lan@example.test");
        try (var executor = Executors.newSingleThreadExecutor()) {
            try (var holder = dataSource.getConnection()) {
                holder.setAutoCommit(false);
                execute(holder, "SELECT 1 FROM recruitment_requisitions WHERE id = '" + a.requisitionId()
                        + "' FOR NO KEY UPDATE");
                var call = executor.submit(() -> inTransaction(TransactionDefinition.ISOLATION_DEFAULT,
                        () -> access.lock(credential(a.code(), "lan@example.test"))));
                int waiter = awaitWaiterOf(backendPid(holder));
                assertThat(jdbc.queryForObject("SELECT query FROM pg_stat_activity WHERE pid = ?", String.class,
                        waiter)).contains("recruitment_requisitions").contains("FOR NO KEY UPDATE");
                assertThat(probe(LOCK_APPLICATION, a.id())).isEqualTo(FREE);
                holder.rollback();
                assertThat(call.get(10, TimeUnit.SECONDS)).map(ApplicationWithdrawalAccess.LockedApplication::id)
                        .contains(a.id());
            }
        }
    }

    // A revocation, or a stage change, committed while the call waits on the requisition is seen on the locked row:
    // the revoked code finds nothing, the changed stage is returned as it now is.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aRevocationOrAStageChangeCommittedDuringTheWaitIsSeen(boolean revoke) throws Exception {
        var a = stored(java, "lan@example.test");
        try (var executor = Executors.newSingleThreadExecutor()) {
            try (var holder = dataSource.getConnection()) {
                holder.setAutoCommit(false);
                execute(holder, "SELECT 1 FROM recruitment_requisitions WHERE id = '" + a.requisitionId()
                        + "' FOR NO KEY UPDATE");
                var call = executor.submit(() -> inTransaction(TransactionDefinition.ISOLATION_DEFAULT,
                        () -> access.lock(credential(a.code(), "lan@example.test"))));
                awaitWaiterOf(backendPid(holder));
                // Committed at once: the waiting call holds nothing on the application yet. The lock timeout turns a
                // call that did lock the row first into a failure instead of a test that never ends.
                inTransaction(TransactionDefinition.ISOLATION_DEFAULT, () -> {
                    jdbc.execute("SET LOCAL lock_timeout = '2s'");
                    return jdbc.update(revoke ? "UPDATE job_applications SET tracking_revoked_at = now() WHERE id = ?"
                            : "UPDATE job_applications SET stage = 'REJECTED' WHERE id = ?", a.id());
                });
                holder.rollback();
                var locked = call.get(10, TimeUnit.SECONDS);
                if (revoke) {
                    assertThat(locked).isEmpty();
                } else {
                    assertThat(locked).map(ApplicationWithdrawalAccess.LockedApplication::stage)
                            .contains(ApplicationStage.REJECTED);
                }
            }
        }
    }

    // The locks must last until the caller's own transaction ends, and only READ COMMITTED reads the newest version
    // of a row it waited for: no transaction, a read-only one and REPEATABLE READ or SERIALIZABLE are refused.
    @Test
    void theCallNeedsAReadWriteReadCommittedTransaction() {
        var a = stored(java, "lan@example.test");
        var credential = credential(a.code(), "lan@example.test");
        assertThatThrownBy(() -> access.lock(credential)).isInstanceOf(IllegalTransactionStateException.class);
        var readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.execute(status -> access.lock(credential)))
                .isExactlyInstanceOf(IllegalStateException.class).hasMessageContaining("READ COMMITTED");
        for (int isolation : List.of(TransactionDefinition.ISOLATION_REPEATABLE_READ,
                TransactionDefinition.ISOLATION_SERIALIZABLE)) {
            assertThatThrownBy(() -> inTransaction(isolation, () -> access.lock(credential)))
                    .isExactlyInstanceOf(IllegalStateException.class).hasMessageContaining("READ COMMITTED");
        }
        assertThat(inTransaction(TransactionDefinition.ISOLATION_READ_COMMITTED, () -> access.lock(credential)))
                .isPresent();
    }

    private void assertNothingFor(TrackingCredential credential, Stored application) {
        var result = inTransaction(TransactionDefinition.ISOLATION_DEFAULT, () -> {
            var locked = access.lock(credential);
            assertThat(probe(LOCK_REQUISITION, application.requisitionId())).isEqualTo(FREE);
            assertThat(probe(LOCK_APPLICATION, application.id())).isEqualTo(FREE);
            return locked;
        });
        assertThat(result).isEmpty();
    }

    private <T> T inTransaction(int isolation, java.util.function.Supplier<T> work) {
        var template = new TransactionTemplate(transactions);
        template.setIsolationLevel(isolation);
        return template.execute(status -> work.get());
    }

    private TrackingCredential credential(String code, String email) {
        return new TrackingCredential(TrackingCode.hash(code).orElseThrow(),
                ApplicationSubmission.normalizeEmail(email));
    }

    // From another connection: 55P03 when the lock is held elsewhere, "free" when it could be taken (and is released).
    private String probe(String sql, UUID id) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(sql)) {
                statement.setObject(1, id);
                statement.executeQuery().close();
                return FREE;
            } catch (SQLException exception) {
                if (LOCKED.equals(exception.getSQLState())) return LOCKED;
                throw exception;
            } finally {
                connection.rollback();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int backendPid(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var row = statement.executeQuery("SELECT pg_backend_pid()")) {
            row.next();
            return row.getInt(1);
        }
    }

    // The backend waiting behind pid: polled every 10 ms for at most 1.5 s; fails loudly instead of letting a test pass
    // without the wait it describes.
    private int awaitWaiterOf(int pid) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMillis(1500).toNanos();
        while (System.nanoTime() < deadline) {
            var waiters = jdbc.queryForList("SELECT pid FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                    Integer.class, pid);
            if (!waiters.isEmpty()) return waiters.getFirst();
            Thread.sleep(10);
        }
        throw new AssertionError("Nothing waited behind backend " + pid + " within 1.5 s");
    }

    // The row versions (xmin) of both applications and both requisitions.
    private String versions(Stored... applications) {
        var versions = new StringBuilder();
        for (var application : applications) {
            versions.append(jdbc.queryForObject("SELECT xmin::text FROM job_applications WHERE id = ?", String.class,
                    application.id())).append('/').append(jdbc.queryForObject(
                    "SELECT xmin::text FROM recruitment_requisitions WHERE id = ?", String.class,
                    application.requisitionId())).append(' ');
        }
        return versions.toString();
    }

    // Stored through TRAN DUC LUONG's ApplicationStorageService.store with SHA-256 of 32 known random bytes, as the
    // receipt writer of task 329 does; the code is those bytes in Base64URL.
    private Stored stored(UUID posting, String email) {
        byte[] token = new byte[32];
        random.nextBytes(token);
        try {
            String code = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
            var saved = storage.store(posting, new ApplicationSubmission("Nguyễn Thị Lan", email, "0912345678", null,
                    null), pdf(), MessageDigest.getInstance("SHA-256").digest(token));
            return new Stored(saved.id(), saved.requisitionId(), code, saved.receivedAt());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        } finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    private String unknownCode() {
        byte[] token = new byte[32];
        random.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    private ValidatedCv pdf() {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return validator.validate("cv.pdf", new ByteArrayInputStream(output.toByteArray()));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // A PUBLISHED posting (deadline 31 Dec 2026) of a new APPROVED requisition, with its own account, department,
    // position and catalog values.
    private UUID posting(String title) {
        UUID account = UUID.randomUUID(), department = UUID.randomUUID(), position = UUID.randomUUID();
        UUID requisition = UUID.randomUUID(), posting = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.parse("2026-09-01T03:00:00Z"));
        jdbc.update("INSERT INTO user_accounts(id,email,password_hash,full_name,enabled,created_at) VALUES (?,?,'test-only','HR',true,?)",
                account, account + "@example.test", at);
        jdbc.update("INSERT INTO departments(id,code,name,manager_user_id) VALUES (?,?,'IT',?)", department,
                department.toString(), account);
        jdbc.update("INSERT INTO positions(id,code,name,level,salary_min,salary_max,created_at,updated_at) VALUES (?,?,'Developer','Junior',0,0,?,?)",
                position, position.toString(), at, at);
        UUID location = catalog("WORK_LOCATION", at), employment = catalog("EMPLOYMENT_TYPE", at);
        jdbc.update("""
                INSERT INTO recruitment_requisitions(id,position_id,department_id,headcount,reason,status,created_by,created_at,updated_at)
                VALUES (?,?,?,1,'NEW_HEADCOUNT','APPROVED',?,?,?)
                """, requisition, position, department, account, at, at);
        jdbc.update("""
                INSERT INTO job_postings(id,requisition_id,status,revision,title,job_description,candidate_requirements,
                    work_location_id,employment_type_id,application_deadline,show_salary,created_by,created_at,updated_at,
                    published_by,published_at)
                VALUES (?,?,'PUBLISHED',1,?,'Description','Requirements',?,?,?,false,?,?,?,?,?)
                """, posting, requisition, title, location, employment, LocalDate.of(2026, 12, 31), account, at, at,
                account, at);
        return posting;
    }

    private UUID catalog(String type, Timestamp at) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO recruitment_catalog_items(id,catalog_type,code,name,sort_order,active,created_at,updated_at) VALUES (?,?,?,'Visible',0,true,?,?)",
                id, type, id.toString(), at, at);
        return id;
    }
}
