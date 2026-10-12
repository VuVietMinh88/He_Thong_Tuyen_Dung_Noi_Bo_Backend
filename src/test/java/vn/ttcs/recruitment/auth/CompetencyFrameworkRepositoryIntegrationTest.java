package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.competency.CompetencyCriterion;
import vn.ttcs.recruitment.competency.CompetencyCriterionRepository;
import vn.ttcs.recruitment.competency.CompetencyFramework;
import vn.ttcs.recruitment.competency.CompetencyFrameworkRepository;
import vn.ttcs.recruitment.competency.CompetencyFrameworkStatus;
import vn.ttcs.recruitment.position.Position;
import vn.ttcs.recruitment.position.PositionRepository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Hibernate runs with ddl-auto=validate, so this context only starts when the entities match V8.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CompetencyFrameworkRepositoryIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-07T00:00:00Z");

    @Autowired private CompetencyFrameworkRepository frameworks;
    @Autowired private CompetencyCriterionRepository criteria;
    @Autowired private PositionRepository positions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
    }

    @BeforeEach
    void resetCompetencyData() {
        jdbc.update("DELETE FROM positions");
        // ON DELETE CASCADE removes the criteria too.
        jdbc.update("DELETE FROM competency_frameworks");
    }

    @Test
    void savesFrameworkAsDraftAndReloadsItsCriteriaInSortOrder() {
        var framework = frameworks.saveAndFlush(new CompetencyFramework("DEV_CORE", "Năng lực lập trình viên",
                "Dùng cho mọi cấp lập trình viên", CREATED_AT));
        // Saved out of order on purpose: the repository must return them by sortOrder.
        var teamwork = criteria.saveAndFlush(new CompetencyCriterion(framework.getId(), "Làm việc nhóm", null,
                new BigDecimal("24.50"), 3));
        var coding = criteria.saveAndFlush(new CompetencyCriterion(framework.getId(), "Kỹ năng lập trình",
                "Viết mã đúng và dễ đọc", new BigDecimal("40"), 1));
        var design = criteria.saveAndFlush(new CompetencyCriterion(framework.getId(), "Thiết kế hệ thống", null,
                new BigDecimal("35.5"), 2));

        var row = jdbc.queryForMap("SELECT * FROM competency_frameworks WHERE id=?", framework.getId());
        assertThat(row.get("code")).isEqualTo("DEV_CORE");
        assertThat(row.get("name")).isEqualTo("Năng lực lập trình viên");
        assertThat(row.get("description")).isEqualTo("Dùng cho mọi cấp lập trình viên");
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(row.get("created_at")).isEqualTo(Timestamp.from(CREATED_AT));
        assertThat(row.get("updated_at")).isEqualTo(Timestamp.from(CREATED_AT));

        var reloaded = frameworks.findById(framework.getId()).orElseThrow();
        assertThat(reloaded.getCode()).isEqualTo("DEV_CORE");
        assertThat(reloaded.getName()).isEqualTo("Năng lực lập trình viên");
        assertThat(reloaded.getDescription()).isEqualTo("Dùng cho mọi cấp lập trình viên");
        assertThat(reloaded.getStatus()).isEqualTo(CompetencyFrameworkStatus.DRAFT);
        assertThat(reloaded.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(CREATED_AT);

        var loaded = criteria.findByFrameworkIdOrderBySortOrderAsc(framework.getId());
        assertThat(loaded).extracting(CompetencyCriterion::getId)
                .containsExactly(coding.getId(), design.getId(), teamwork.getId());
        assertThat(loaded).extracting(CompetencyCriterion::getName)
                .containsExactly("Kỹ năng lập trình", "Thiết kế hệ thống", "Làm việc nhóm");
        assertThat(loaded).extracting(CompetencyCriterion::getSortOrder).containsExactly(1, 2, 3);
        assertThat(loaded).extracting(CompetencyCriterion::getFrameworkId).containsOnly(framework.getId());
        assertThat(loaded.get(0).getDescription()).isEqualTo("Viết mã đúng và dễ đọc");
        assertThat(loaded.get(1).getDescription()).isNull();
        // Weights come back from NUMERIC(5,2) with exactly two decimals and add up to exactly 100.00.
        assertThat(loaded).extracting(CompetencyCriterion::getWeight)
                .containsExactly(new BigDecimal("40.00"), new BigDecimal("35.50"), new BigDecimal("24.50"));
        assertThat(loaded.stream().map(CompetencyCriterion::getWeight).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualTo(new BigDecimal("100.00"));
        assertThat(criteria.findByFrameworkIdOrderBySortOrderAsc(UUID.randomUUID())).isEmpty();
    }

    @Test
    void readsActiveStatusAndKeepsPositionLinksWhenThePositionIsUpdated() {
        var framework = frameworks.saveAndFlush(new CompetencyFramework("DEV_CORE", "Năng lực lập trình viên",
                null, CREATED_AT));
        var junior = positions.saveAndFlush(new Position("DEV_JUNIOR", "Lập trình viên", "Junior",
                15_000_000L, 25_000_000L, CREATED_AT));
        var senior = positions.saveAndFlush(new Position("DEV_SENIOR", "Lập trình viên", "Senior",
                30_000_000L, 50_000_000L, CREATED_AT));
        var unlinked = positions.saveAndFlush(new Position("TESTER", "Kiểm thử viên", "Junior",
                12_000_000L, 20_000_000L, CREATED_AT));
        // Activation and assignment APIs come in later tasks, so the test sets them with SQL.
        jdbc.update("UPDATE competency_frameworks SET status='ACTIVE' WHERE id=?", framework.getId());
        jdbc.update("UPDATE positions SET competency_framework_id=? WHERE id IN (?,?)",
                framework.getId(), junior.getId(), senior.getId());

        assertThat(frameworks.findById(framework.getId()).orElseThrow().getStatus())
                .isEqualTo(CompetencyFrameworkStatus.ACTIVE);
        assertThat(positions.findById(junior.getId()).orElseThrow().getCompetencyFrameworkId())
                .isEqualTo(framework.getId());
        assertThat(positions.findById(senior.getId()).orElseThrow().getCompetencyFrameworkId())
                .isEqualTo(framework.getId());
        assertThat(positions.findById(unlinked.getId()).orElseThrow().getCompetencyFrameworkId()).isNull();

        // Editing a position through JPA (as the position API does) must not drop its framework.
        var reloaded = positions.findById(junior.getId()).orElseThrow();
        reloaded.update("DEV_JUNIOR", "Lập trình viên mới", "Junior", 16_000_000L, 26_000_000L, true,
                CREATED_AT.plusSeconds(60));
        positions.saveAndFlush(reloaded);
        assertThat(jdbc.queryForObject("SELECT name FROM positions WHERE id=?", String.class, junior.getId()))
                .isEqualTo("Lập trình viên mới");
        assertThat(jdbc.queryForObject("SELECT competency_framework_id FROM positions WHERE id=?",
                UUID.class, junior.getId())).isEqualTo(framework.getId());
    }

    @Test
    void databaseConstraintsRejectInvalidDataSavedThroughJpa() {
        var framework = frameworks.saveAndFlush(new CompetencyFramework("DEV_CORE", "Năng lực lập trình viên",
                null, CREATED_AT));
        criteria.saveAndFlush(new CompetencyCriterion(framework.getId(), "Giao tiếp", null,
                new BigDecimal("30.00"), 1));

        assertThatThrownBy(() -> frameworks.saveAndFlush(new CompetencyFramework("DEV_CORE", "Mã trùng",
                null, CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("competency_frameworks_code_key");
        assertThatThrownBy(() -> criteria.saveAndFlush(new CompetencyCriterion(framework.getId(), "Tư duy", null,
                BigDecimal.ZERO, 2)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("valid_competency_criterion_weight");
        assertThatThrownBy(() -> criteria.saveAndFlush(new CompetencyCriterion(framework.getId(), "Tư duy", null,
                new BigDecimal("100.01"), 2)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("valid_competency_criterion_weight");
        // The UNIQUE constraints are deferred. saveAndFlush runs in its own transaction here, so its COMMIT reports
        // these duplicates. Inside a service transaction they would only appear after the service returned; the
        // checkUniqueConstraintsNow tests below show how a service finds them earlier.
        assertThatThrownBy(() -> criteria.saveAndFlush(new CompetencyCriterion(framework.getId(), "Giao tiếp", null,
                new BigDecimal("10"), 2)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("competency_criteria_framework_name_key");
        assertThatThrownBy(() -> criteria.saveAndFlush(new CompetencyCriterion(framework.getId(), "Tư duy", null,
                new BigDecimal("10"), 1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("competency_criteria_framework_sort_order_key");

        assertThat(frameworks.count()).isEqualTo(1);
        assertThat(criteria.findByFrameworkIdOrderBySortOrderAsc(framework.getId()))
                .extracting(CompetencyCriterion::getName).containsExactly("Giao tiếp");
    }

    @Test
    void uniqueCheckReportsALeftoverDuplicateInsideTheWriteTransaction() {
        var framework = frameworks.saveAndFlush(new CompetencyFramework("DEV_CORE", "Năng lực lập trình viên",
                null, CREATED_AT));
        var communication = criteria.saveAndFlush(new CompetencyCriterion(framework.getId(), "Giao tiếp", null,
                new BigDecimal("30.00"), 1));

        // The flush of each duplicate succeeds (the constraints are deferred); the check finds it before COMMIT.
        var sameName = uniqueCheckFailure(new CompetencyCriterion(framework.getId(), "Giao tiếp", null,
                new BigDecimal("10"), 2));
        assertThat(sameName).hasMessageContaining("competency_criteria_framework_name_key");
        assertThat(sqlState(sameName)).isEqualTo("23505");
        var sameSortOrder = uniqueCheckFailure(new CompetencyCriterion(framework.getId(), "Tư duy", null,
                new BigDecimal("10"), 1));
        assertThat(sameSortOrder).hasMessageContaining("competency_criteria_framework_sort_order_key");
        assertThat(sqlState(sameSortOrder)).isEqualTo("23505");

        // Both write transactions were rolled back.
        assertThat(criteria.findByFrameworkIdOrderBySortOrderAsc(framework.getId()))
                .extracting(CompetencyCriterion::getId).containsExactly(communication.getId());
        // Outside a transaction there is nothing to check, so the call is refused instead of silently passing.
        assertThatThrownBy(() -> criteria.checkUniqueConstraintsNow())
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void uniqueCheckAcceptsASwapFinishedInTheSameTransaction() {
        var framework = frameworks.saveAndFlush(new CompetencyFramework("DEV_CORE", "Năng lực lập trình viên",
                null, CREATED_AT));
        var coding = criteria.saveAndFlush(new CompetencyCriterion(framework.getId(), "Kỹ năng lập trình", null,
                new BigDecimal("60"), 1));
        var teamwork = criteria.saveAndFlush(new CompetencyCriterion(framework.getId(), "Làm việc nhóm", null,
                new BigDecimal("40"), 2));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // SQL swaps the order, so the test controls which statement runs first (Hibernate would choose).
            // Between the two statements both rows have sort_order 2, which the deferred constraint allows.
            jdbc.update("UPDATE competency_criteria SET sort_order=2 WHERE id=?", coding.getId());
            jdbc.update("UPDATE competency_criteria SET sort_order=1 WHERE id=?", teamwork.getId());
            criteria.checkUniqueConstraintsNow();
        });

        assertThat(criteria.findByFrameworkIdOrderBySortOrderAsc(framework.getId()))
                .extracting(CompetencyCriterion::getId).containsExactly(teamwork.getId(), coding.getId());
    }

    @Test
    void uniqueCheckWaitsForAConcurrentWriterAndThenReportsTheDuplicate() throws Exception {
        var framework = frameworks.saveAndFlush(new CompetencyFramework("DEV_CORE", "Năng lực lập trình viên",
                null, CREATED_AT));
        criteria.saveAndFlush(new CompetencyCriterion(framework.getId(), "Giao tiếp", null,
                new BigDecimal("30.00"), 1));

        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int firstWriterPid = backendPid(connection);
            // The first request passed its duplicate check and inserted "Tư duy", but has not committed yet.
            execute(connection, "INSERT INTO competency_criteria (id,framework_id,name,weight,sort_order) "
                    + "VALUES (?,?,?,?,?)", UUID.randomUUID(), framework.getId(), "Tư duy", new BigDecimal("20"), 2);
            try (var executor = Executors.newSingleThreadExecutor()) {
                // The second request cannot see that uncommitted row, so its own duplicate check passed too.
                Future<DataIntegrityViolationException> second = executor.submit(() -> uniqueCheckFailure(
                        new CompetencyCriterion(framework.getId(), "Tư duy", null, new BigDecimal("10"), 3)));
                try {
                    awaitWaiters(firstWriterPid);
                    assertThat(second.isDone()).isFalse();
                    connection.commit();
                    var failure = second.get(10, TimeUnit.SECONDS);
                    assertThat(failure).as("the second request must fail before its COMMIT").isNotNull()
                            .hasMessageContaining("competency_criteria_framework_name_key");
                    assertThat(sqlState(failure)).isEqualTo("23505");
                } finally {
                    // Release the first writer even if an assertion above fails.
                    connection.rollback();
                }
            }
        }

        var saved = criteria.findByFrameworkIdOrderBySortOrderAsc(framework.getId());
        assertThat(saved).extracting(CompetencyCriterion::getName).containsExactly("Giao tiếp", "Tư duy");
        assertThat(saved.get(1).getWeight()).isEqualTo(new BigDecimal("20.00"));
    }

    // Plays a later write service: save, run the check, and turn its error into a rollback (the service would
    // throw a 409 there). Returns the error the check reported, or null when the write committed.
    private DataIntegrityViolationException uniqueCheckFailure(CompetencyCriterion criterion) {
        var failure = new AtomicReference<DataIntegrityViolationException>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            criteria.saveAndFlush(criterion);
            try {
                criteria.checkUniqueConstraintsNow();
            } catch (DataIntegrityViolationException exception) {
                failure.set(exception);
                status.setRollbackOnly();
            }
        });
        return failure.get();
    }

    // Same search as PositionService.translateDuplicateCode: the SQLException inside the Spring exception.
    private static String sqlState(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    // Polls from a separate connection: inside one transaction PostgreSQL would keep showing the same snapshot.
    private void awaitWaiters(int blockerPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean waiting = false;
        while (!waiting && System.nanoTime() < deadline) {
            waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity
                        WHERE ? = ANY(pg_blocking_pids(pid)) AND wait_event_type = 'Lock'
                          AND datname = current_database()
                    )
                    """, Boolean.class, blockerPid));
            if (!waiting) {
                Thread.sleep(20);
            }
        }
        assertThat(waiting).as("the second request must wait for the first writer").isTrue();
    }

    private int backendPid(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement("SELECT pg_backend_pid()");
             var row = statement.executeQuery()) {
            assertThat(row.next()).isTrue();
            return row.getInt(1);
        }
    }

    private void execute(Connection connection, String sql, Object... values) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }
}
