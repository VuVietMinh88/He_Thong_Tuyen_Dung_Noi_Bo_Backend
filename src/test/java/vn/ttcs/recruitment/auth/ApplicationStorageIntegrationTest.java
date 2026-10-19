package vn.ttcs.recruitment.auth;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.ApplicationSubmission;
import vn.ttcs.recruitment.application.CvValidator;
import vn.ttcs.recruitment.application.DuplicateApplicationException;
import vn.ttcs.recruitment.application.ValidatedCv;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.jobposting.JobPostingStatus;
import vn.ttcs.recruitment.requisition.RequisitionPipelineSource;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Real V1-V27 PostgreSQL, no HTTP or SMTP: task 325's internal persistence contract.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "spring.mail.host=127.0.0.1", "spring.mail.port=1"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationStorageIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationStorageService storage;
    @Autowired private CvValidator validator;
    @Autowired private RequisitionPipelineSource pipeline;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private DataSource dataSource;
    private UUID account;
    private UUID department;
    private UUID position;
    private UUID source;
    private UUID workLocation;
    private UUID employment;
    private UUID requisition;
    private UUID posting;
    private ValidatedCv cv;

    @BeforeEach
    void fixture() throws Exception {
        clock.set(NOW);
        jdbc.update("DELETE FROM application_cvs");
        jdbc.update("DELETE FROM job_applications");
        jdbc.execute("TRUNCATE job_posting_events");
        jdbc.update("DELETE FROM job_posting_authors");
        jdbc.update("DELETE FROM job_postings");
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("DELETE FROM recruitment_catalog_items");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM user_accounts");
        account = UUID.randomUUID(); department = UUID.randomUUID(); position = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id,email,password_hash,full_name,enabled,created_at) VALUES (?,?,?,?,true,?)",
                account, "hr@example.test", "test-only", "HR", Timestamp.from(NOW));
        jdbc.update("INSERT INTO departments(id,code,name,manager_user_id) VALUES (?,?,?,?)", department, "IT", "IT", account);
        jdbc.update("INSERT INTO positions(id,code,name,level,salary_min,salary_max,created_at,updated_at) VALUES (?,?,?,?,0,0,?,?)",
                position, "DEV", "Developer", "Junior", Timestamp.from(NOW), Timestamp.from(NOW));
        source = catalog("CANDIDATE_SOURCE"); workLocation = catalog("WORK_LOCATION"); employment = catalog("EMPLOYMENT_TYPE");
        requisition = createRequisition(); posting = createPosting(requisition);
        try (var pdf = new PDDocument(); var output = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage()); pdf.save(output);
            cv = validator.validate("cv.pdf", new ByteArrayInputStream(output.toByteArray()));
        }
    }

    @Test
    void storesTheSnapshotAndOriginalCvAtomicallyAndFeedsTheRealPipeline() {
        var saved = storage.store(posting, submission(" Candidate@Example.test "), cv, hash());
        assertThat(saved.stage()).isEqualTo(ApplicationStage.NEW);
        assertThat(saved.receivedAt()).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("SELECT normalized_email FROM job_applications WHERE id=?", String.class, saved.id()))
                .isEqualTo("candidate@example.test");
        assertThat(jdbc.queryForObject("SELECT content FROM application_cvs WHERE application_id=?", byte[].class, saved.id()))
                .isEqualTo(cv.content());
        assertThat(jdbc.queryForObject("SELECT sha256 FROM application_cvs WHERE application_id=?", byte[].class, saved.id()))
                .isEqualTo(cv.sha256());
        assertThat(pipeline.activePipeline(requisition).count()).isEqualTo(1);
        assertThat(pipeline.activePipeline(requisition).stages().getFirst().stage()).isEqualTo("NEW");
        assertThat(pipeline.activePipeline(UUID.randomUUID()).count()).isZero();
        assertThat(jdbc.queryForObject("SELECT posting_title FROM job_applications WHERE id=?", String.class, saved.id())).isEqualTo("Hiring developer");
    }

    @Test
    void storesWithoutOptionalSourceAndCoverLetter() {
        jdbc.update("UPDATE recruitment_catalog_items SET active=false WHERE id=?", source);
        var input = new ApplicationSubmission("Candidate A", "optional@example.test", "0123456789", null, null);
        var saved = storage.store(posting, input, cv, hash());
        assertThat(jdbc.queryForMap("SELECT source_id,cover_letter FROM job_applications WHERE id=?", saved.id()))
                .containsEntry("source_id", null).containsEntry("cover_letter", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_cvs WHERE application_id=?", Integer.class, saved.id()))
                .isEqualTo(1);
        assertThat(pipeline.activePipeline(requisition).count()).isEqualTo(1);
    }

    @Test
    void emailUniquenessSurvivesTerminalStagesButDifferentRequisitionsAcceptIt() {
        var saved = storage.store(posting, submission("A+tag@example.test"), cv, hash());
        jdbc.update("UPDATE job_applications SET stage='WITHDRAWN' WHERE id=?", saved.id());
        assertThatThrownBy(() -> storage.store(posting, submission(" a+tag@EXAMPLE.test "), cv, hash()))
                .isInstanceOf(DuplicateApplicationException.class);
        UUID other = createRequisition();
        storage.store(createPosting(other), submission("A+tag@example.test"), cv, hash());
        assertThat(count()).isEqualTo(2);
        assertThat(pipeline.activePipeline(requisition).count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(ApplicationStage.class)
    void everyStageKeepsTheEmailReservation(ApplicationStage stage) {
        var saved = storage.store(posting, submission("a@example.test"), cv, hash());
        jdbc.update("UPDATE job_applications SET stage=? WHERE id=?", stage.name(), saved.id());
        assertThatThrownBy(() -> storage.store(posting, submission(" A@EXAMPLE.test "), cv, hash()))
                .isInstanceOf(DuplicateApplicationException.class);
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT stage FROM job_applications WHERE id=?", String.class, saved.id()))
                .isEqualTo(stage.name());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_cvs", Integer.class)).isEqualTo(1);
    }

    @Test
    void trackingHashUniquenessIsNotTranslatedIntoAnEmailDuplicate() {
        byte[] trackingHash = hash();
        storage.store(posting, submission("a@example.test"), cv, trackingHash);
        assertThatThrownBy(() -> storage.store(posting, submission("b@example.test"), cv, trackingHash))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("job_applications_tracking_hash_key");
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_cvs", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aConcurrentDuplicateWaitsForTheFirstTransactionOutcome(boolean rollbackFirst) throws Exception {
        var inserted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var blockerPid = new AtomicInteger();
        var transaction = new TransactionTemplate(transactions);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> transaction.execute(status -> {
                var saved = storage.store(posting, submission("a@example.test"), cv, hash());
                blockerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                inserted.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                if (rollbackFirst) status.setRollbackOnly();
                return saved.id();
            }));
            try {
                assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> storage.store(posting, submission(" A@EXAMPLE.test "), cv, hash()));
                awaitBlockedBy(blockerPid.get());
                release.countDown();
                UUID firstId = first.get(20, TimeUnit.SECONDS);
                if (rollbackFirst) {
                    var accepted = second.get(20, TimeUnit.SECONDS);
                    assertThat(accepted.id()).isNotEqualTo(firstId);
                    assertThat(jdbc.queryForObject("SELECT id FROM job_applications", UUID.class)).isEqualTo(accepted.id());
                } else {
                    assertThatThrownBy(() -> second.get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(DuplicateApplicationException.class);
                    assertThat(jdbc.queryForObject("SELECT id FROM job_applications", UUID.class)).isEqualTo(firstId);
                }
            } finally { release.countDown(); }
        }
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_cvs", Integer.class)).isEqualTo(1);
    }

    @Test
    void twoConcurrentSubmissionsLeaveExactlyOneApplicationAndOneCv() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(executor.submit(() -> concurrentStore(barrier)), executor.submit(() -> concurrentStore(barrier)));
            int accepted = 0;
            for (var future : futures) if (future.get(20, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(1);
        }
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_cvs", Integer.class)).isEqualTo(1);
    }

    @Test
    void aCvInsertFailureRollsBackTheApplication() {
        jdbc.execute("CREATE FUNCTION fail_cv_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test CV failure'; END $$");
        jdbc.execute("CREATE TRIGGER fail_cv BEFORE INSERT ON application_cvs FOR EACH ROW EXECUTE FUNCTION fail_cv_insert()");
        try {
            assertThatThrownBy(() -> storage.store(posting, submission("a@example.test"), cv, hash())).isInstanceOf(RuntimeException.class);
            assertThat(count()).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER fail_cv ON application_cvs");
            jdbc.execute("DROP FUNCTION fail_cv_insert()");
        }
        storage.store(posting, submission("a@example.test"), cv, hash());
        assertThat(count()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = RequisitionStatus.class, names = "APPROVED", mode = EnumSource.Mode.EXCLUDE)
    void refusesEveryNonReceivingRequisitionStatus(RequisitionStatus status) {
        jdbc.update("UPDATE recruitment_requisitions SET status=? WHERE id=?", status.name(), requisition);
        assertThatThrownBy(() -> storage.store(posting, submission("a@example.test"), cv, hash())).isInstanceOf(ApiException.class);
        assertThat(count()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = JobPostingStatus.class, names = "PUBLISHED", mode = EnumSource.Mode.EXCLUDE)
    void refusesEveryNonPublishedPosting(JobPostingStatus status) {
        if (status == JobPostingStatus.CLOSED || status == JobPostingStatus.UNPUBLISHED || status == JobPostingStatus.EXPIRED) {
            jdbc.update("UPDATE job_postings SET status=? WHERE id=?", status.name(), posting);
        } else {
            jdbc.update("UPDATE job_postings SET status=?, published_by=null, published_at=null WHERE id=?", status.name(), posting);
        }
        assertThatThrownBy(() -> storage.store(posting, submission("a@example.test"), cv, hash())).isInstanceOf(ApiException.class);
        assertThat(count()).isZero();
    }

    @Test
    void validatesCatalogAndInputAndAcceptsTheEntireDeadlineDay() {
        jdbc.update("UPDATE job_postings SET application_deadline=? WHERE id=?", LocalDate.of(2026,10,14), posting);
        clock.set(Instant.parse("2026-10-14T16:59:59Z"));
        storage.store(posting, submission("a@example.test"), cv, hash());
        clock.set(Instant.parse("2026-10-14T17:00:00Z"));
        assertThatThrownBy(() -> storage.store(posting, submission("b@example.test"), cv, hash())).isInstanceOf(ApiException.class);
        clock.set(NOW);
        jdbc.update("UPDATE recruitment_catalog_items SET active=false WHERE id=?", source);
        assertThatThrownBy(() -> storage.store(posting, submission("b@example.test"), cv, hash())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> storage.store(posting, new ApplicationSubmission("\u200b", "a@example.test", "0123456789", null, null), cv, hash()))
                .isInstanceOf(ApiException.class);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void theDatabaseRejectsMismatchedPostingCvMetadataAndDeletingUsedSource() {
        UUID other = createRequisition();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO job_applications(id,job_posting_id,requisition_id,full_name,email,normalized_email,phone,
                    received_at,posting_title,tracking_code_hash)
                VALUES (?,?,?,'A','a@example.test','a@example.test','0123456789',?,'Title',?)
                """, UUID.randomUUID(), posting, other, Timestamp.from(NOW), hash())).isInstanceOf(DataIntegrityViolationException.class);
        var saved = storage.store(posting, submission("a@example.test"), cv, hash());
        assertThatThrownBy(() -> jdbc.update("UPDATE application_cvs SET size_bytes=1 WHERE application_id=?", saved.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE application_cvs SET sha256=? WHERE application_id=?",hash(),saved.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM recruitment_catalog_items WHERE id=?", source))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("job_applications_source_id_fkey");
    }

    @Test
    void waitsForClosureThenRefusesTheApplication() throws Exception {
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var command = blocker.prepareStatement("UPDATE recruitment_requisitions SET status='FILLED' WHERE id=?")) {
                command.setObject(1,requisition); command.executeUpdate();
            }
            var future = executor.submit(() -> storage.store(posting, submission("a@example.test"), cv, hash()));
            awaitWaiter(blocker);
            blocker.commit();
            assertThatThrownBy(() -> future.get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);
        }
        assertThat(count()).isZero();
    }

    @Test
    void callerRollbackRemovesBothRows() {
        var transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> {
            storage.store(posting, submission("a@example.test"), cv, hash());
            assertThat(pipeline.activePipeline(requisition).count()).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(count()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_cvs", Integer.class)).isZero();
    }

    @Test
    void waitsForCatalogThenRechecksBusinessMidnight() throws Exception {
        jdbc.update("UPDATE job_postings SET application_deadline=? WHERE id=?",LocalDate.of(2026,10,14),posting);
        clock.set(Instant.parse("2026-10-14T16:59:59Z"));
        try (Connection blocker=dataSource.getConnection();var executor=Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var query=blocker.prepareStatement("SELECT id FROM recruitment_catalog_items WHERE id=? FOR UPDATE")) {
                query.setObject(1,source);query.executeQuery().close();
            }
            var future=executor.submit(() -> storage.store(posting,submission("a@example.test"),cv,hash()));
            awaitWaiter(blocker);
            clock.set(Instant.parse("2026-10-14T17:00:00Z"));
            blocker.commit();
            assertThatThrownBy(() -> future.get(20,TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);
        }
        assertThat(count()).isZero();
    }

    @Test
    void submissionCommitPrecedesClosureAndPipelineIsVisibleAfterTheWait() throws Exception {
        var stored = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var insert = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                storage.store(posting, submission("a@example.test"), cv, hash());
                stored.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("test release timeout"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
                return null;
            }));
            assertThat(stored.await(10,TimeUnit.SECONDS)).isTrue();
            try {
                var closing = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                    jdbc.queryForObject("SELECT status FROM recruitment_requisitions WHERE id=? FOR NO KEY UPDATE",String.class,requisition);
                    return pipeline.activePipeline(requisition).count();
                }));
                // Observe a real lock wait instead of relying on a scheduling sleep.
                long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE cardinality(pg_blocking_pids(pid))>0",Integer.class)==0) {
                    if (System.nanoTime()>deadline) throw new AssertionError("Closure did not wait for submission");
                    Thread.sleep(20);
                }
                release.countDown();
                insert.get(20,TimeUnit.SECONDS);
                assertThat(closing.get(20,TimeUnit.SECONDS)).isEqualTo(1);
            } finally { release.countDown(); }
        }
    }

    @Test
    void upgradingV25PreservesExistingAccountsAndJobPostings() throws Exception {
        String originalSchema = jdbc.queryForObject("SELECT current_schema()", String.class);
        String schema = "task325_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        var isolated = new org.springframework.jdbc.datasource.DelegatingDataSource(dataSource) {
            @Override public Connection getConnection() throws java.sql.SQLException {
                Connection connection = super.getConnection(); connection.setSchema(schema); return connection;
            }
        };
        try {
            // V25: dữ liệu cũ
            Flyway.configure().dataSource(isolated).schemas(schema).defaultSchema(schema).target("25").load().migrate();
            var legacy = new JdbcTemplate(isolated);
            legacy.update("INSERT INTO user_accounts(id,email,password_hash,full_name,enabled,created_at) VALUES (?,?,?,'Legacy',true,?)",
                    account,"legacy@example.test","test-only",Timestamp.from(NOW));
            legacy.update("INSERT INTO departments(id,code,name,manager_user_id) VALUES (?,'LEGACY','Legacy',?)",department,account);
            legacy.update("INSERT INTO positions(id,code,name,level,salary_min,salary_max,created_at,updated_at) VALUES (?,'LEGACY','Legacy','Junior',0,0,?,?)",
                    position,Timestamp.from(NOW),Timestamp.from(NOW));
            legacy.update("INSERT INTO recruitment_requisitions(id,position_id,department_id,headcount,reason,status,created_by,created_at,updated_at) VALUES (?,?,?,1,'NEW_HEADCOUNT','DRAFT',?,?,?)",
                    requisition,position,department,account,Timestamp.from(NOW),Timestamp.from(NOW));
            legacy.update("INSERT INTO job_postings(id,requisition_id,status,revision,title,job_description,candidate_requirements,show_salary,created_by,created_at,updated_at) VALUES (?,?,'DRAFT',1,'Legacy title','JD','Requirements',false,?,?,?)",
                    posting,requisition,account,Timestamp.from(NOW),Timestamp.from(NOW));

            // V26: unpublish job postings, bảo toàn dữ liệu cũ, chưa có bảng job_applications/application_cvs
            var unpublish = Flyway.configure().dataSource(isolated).schemas(schema).defaultSchema(schema).target("26").load();
            assertThat(unpublish.migrate().migrationsExecuted).isEqualTo(1);
            unpublish.validate();
            assertThat(legacy.queryForObject("SELECT title FROM job_postings WHERE id=?", String.class, posting)).isEqualTo("Legacy title");
            assertThat(legacy.queryForObject("SELECT full_name FROM user_accounts WHERE id=?", String.class, account)).isEqualTo("Legacy");
            assertThat(legacy.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_schema=? AND table_name IN ('job_applications', 'application_cvs')",
                    Integer.class, schema)).isZero();

            // V26.1: tạo job_applications và application_cvs, bảo toàn dữ liệu cũ
            var applications = Flyway.configure().dataSource(isolated).schemas(schema).defaultSchema(schema).target("26.1").load();
            assertThat(applications.migrate().migrationsExecuted).isEqualTo(1);
            applications.validate();
            assertThat(applications.info().current().getScript()).isEqualTo("V26_1__create_job_applications_and_cvs.sql");
            assertThat(legacy.queryForObject("SELECT title FROM job_postings WHERE id=?", String.class, posting)).isEqualTo("Legacy title");
            assertThat(legacy.queryForObject("SELECT full_name FROM user_accounts WHERE id=?", String.class, account)).isEqualTo("Legacy");
            assertThat(legacy.queryForObject("SELECT count(*) FROM job_applications", Integer.class)).isZero();
            assertThat(legacy.queryForObject("SELECT count(*) FROM application_cvs", Integer.class)).isZero();

            // V27: expiry job postings, bảo toàn dữ liệu cũ và bảng application/CV
            var expiry = Flyway.configure().dataSource(isolated).schemas(schema).defaultSchema(schema).target("27").load();
            assertThat(expiry.migrate().migrationsExecuted).isEqualTo(1);
            expiry.validate();
            assertThat(legacy.queryForObject("SELECT title FROM job_postings WHERE id=?", String.class, posting)).isEqualTo("Legacy title");
            assertThat(legacy.queryForObject("SELECT full_name FROM user_accounts WHERE id=?", String.class, account)).isEqualTo("Legacy");
            assertThat(legacy.queryForObject("SELECT count(*) FROM job_applications", Integer.class)).isZero();
            assertThat(legacy.queryForObject("SELECT count(*) FROM application_cvs", Integer.class)).isZero();
        } finally { jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(originalSchema);
    }

    private boolean concurrentStore(CyclicBarrier barrier) throws Exception {
        barrier.await();
        try { storage.store(posting, submission("a@example.test"), cv, hash()); return true; }
        catch (DuplicateApplicationException duplicate) { return false; }
    }

    private ApplicationSubmission submission(String email) { return new ApplicationSubmission("Candidate A", email, "0123456789", source, "Hi\nThere"); }
    private int count() { return jdbc.queryForObject("SELECT count(*) FROM job_applications", Integer.class); }
    private byte[] hash() { byte[] value = new byte[32]; new java.security.SecureRandom().nextBytes(value); return value; }

    private UUID catalog(String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO recruitment_catalog_items(id,catalog_type,code,name,sort_order,active,created_at,updated_at) VALUES (?,?,?,?,0,true,?,?)",
                id,type,type,type,Timestamp.from(NOW),Timestamp.from(NOW));
        return id;
    }

    private UUID createRequisition() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO recruitment_requisitions(id,position_id,department_id,headcount,reason,status,created_by,created_at,updated_at) VALUES (?,?,?,1,'NEW_HEADCOUNT','APPROVED',?,?,?)",
                id,position,department,account,Timestamp.from(NOW),Timestamp.from(NOW));
        return id;
    }

    private UUID createPosting(UUID sourceRequisition) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO job_postings(id,requisition_id,status,revision,title,job_description,candidate_requirements,
                    work_location_id,employment_type_id,application_deadline,show_salary,created_by,created_at,updated_at,published_by,published_at)
                VALUES (?,?,'PUBLISHED',1,'Hiring developer','Description','Requirements',?,?,?,false,?,?,?,?,?)
                """,id,sourceRequisition,workLocation,employment,LocalDate.of(2026,12,31),account,
                Timestamp.from(NOW),Timestamp.from(NOW),account,Timestamp.from(NOW));
        return id;
    }

    private void awaitWaiter(Connection blocker) throws Exception {
        int pid;
        try (var query=blocker.createStatement(); var row=query.executeQuery("SELECT pg_backend_pid()")) { row.next();pid=row.getInt(1); }
        awaitBlockedBy(pid);
    }

    private void awaitBlockedBy(int pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))", Integer.class,pid)>0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Submission did not wait for the closure lock");
    }
}
