package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.requisition.RequisitionStatus;
import vn.ttcs.recruitment.requisition.RequisitionStatusChange;
import vn.ttcs.recruitment.requisition.RequisitionStatusListener;
import vn.ttcs.recruitment.requisition.RequisitionStatusTransition;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

// Task 290: the seam for tasks 291 and 292. A listener runs inside the status change's transaction while the
// requisition is locked: what it throws reaches the caller and rolls the whole change back, and afterChange already
// sees the new status and the history row.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import({AuthIntegrationTest.DatabaseConfiguration.class,
        RequisitionStatusListenerIntegrationTest.ListenerConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionStatusListenerIntegrationTest {
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");
    private static final Consumer<RequisitionStatusTransition> NOTHING = transition -> { };
    // What the test listener does; each test sets them, and they are reset after it.
    private static final AtomicReference<Consumer<RequisitionStatusTransition>> BEFORE = new AtomicReference<>(NOTHING);
    private static final AtomicReference<Consumer<RequisitionStatusTransition>> AFTER = new AtomicReference<>(NOTHING);

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;

    private RequisitionStatusTestSupport status;
    private JobPostingTestSupport postings;
    private UUID adminId;
    private UUID hrId;
    private String hrToken;
    private UUID department;
    private UUID position;

    @TestConfiguration(proxyBeanMethods = false)
    static class ListenerConfiguration {

        @Bean
        RequisitionStatusListener testListener() {
            return new RequisitionStatusListener() {
                @Override
                public void beforeChange(RequisitionStatusTransition transition) {
                    BEFORE.get().accept(transition);
                }

                @Override
                public void afterChange(RequisitionStatusTransition transition) {
                    AFTER.get().accept(transition);
                }
            };
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
    }

    @BeforeEach
    void resetFixture() throws Exception {
        clock.set(START);
        status = new RequisitionStatusTestSupport(jdbc, json, transactions,
                Integer.parseInt(environment.getRequiredProperty("local.server.port")));
        postings = status.postings;
        status.reset();
        bootstrap.run(new DefaultApplicationArguments());
        adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        String passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        hrId = accounts.saveAndFlush(new Account("hr@example.test", "Nguyễn Thị Hoa", passwordHash,
                Set.of(Role.HR_MANAGER), START)).getId();
        hrToken = status.api.token("hr@example.test");
        department = status.api.department("IT", hrId, null, START);
        position = status.api.position(START);
    }

    @AfterEach
    void resetListener() {
        BEFORE.set(NOTHING);
        AFTER.set(NOTHING);
    }

    // Task 291 will refuse a closure here: nothing is written.
    @Test
    void aRefusalBeforeTheChangeWritesNothing() throws Exception {
        UUID id = requisition(RequisitionStatus.APPROVED);
        Map<String, Object> rowBefore = status.row(id);
        List<RequisitionStatusTransition> seen = new ArrayList<>();
        BEFORE.set(transition -> {
            seen.add(transition);
            assertThat(eventCount(transition.requisitionId())).isZero();
            throw new ApiException(HttpStatus.CONFLICT, "TEST_REFUSED", "x");
        });

        postings.error(status.change(id, "FILLED", "Đã tuyển đủ", 0, hrToken), 409, "TEST_REFUSED");

        assertThat(seen).hasSize(1);
        assertThat(status.row(id)).isEqualTo(rowBefore);
        assertThat(status.events(id)).isEmpty();
    }

    // Task 292 closes the postings here: the new status and the history row are already written, in the same
    // transaction, and the transition carries what was stored.
    @Test
    void bothSeeTheChangeAsItIsStored() throws Exception {
        UUID id = requisition(RequisitionStatus.PAUSED);
        clock.set(START.plusSeconds(45).plusNanos(456_789_999));
        List<RequisitionStatusTransition> before = new ArrayList<>();
        List<RequisitionStatusTransition> after = new ArrayList<>();
        List<Object> seenAfter = new ArrayList<>();
        BEFORE.set(transition -> {
            before.add(transition);
            assertThat(statusOf(transition.requisitionId())).isEqualTo("PAUSED");
            assertThat(eventCount(transition.requisitionId())).isZero();
        });
        AFTER.set(transition -> {
            after.add(transition);
            seenAfter.add(statusOf(transition.requisitionId()));
            seenAfter.add(eventCount(transition.requisitionId()));
        });

        status.ok(status.change(id, "CANCELLED", "Không còn nhu cầu\nquý 4", 0, hrToken));

        RequisitionStatusTransition expected = new RequisitionStatusTransition(id, RequisitionStatusChange.CANCEL,
                RequisitionStatus.PAUSED, RequisitionStatus.CANCELLED, hrId,
                Instant.parse("2026-10-11T03:00:45.456789Z"), "Không còn nhu cầu\nquý 4");
        assertThat(before).containsExactly(expected);
        assertThat(after).containsExactly(expected);
        assertThat(seenAfter).containsExactly("CANCELLED", 1);
        Map<String, Object> event = status.events(id).getFirst();
        assertThat(((Timestamp) event.get("occurred_at")).toInstant()).isEqualTo(expected.at());
        assertThat(event.get("comment")).isEqualTo(expected.reason());
        assertThat(event.get("actor_id")).isEqualTo(expected.actorId());

        // Resuming without a reason: the transition's reason is null, as stored.
        UUID resumed = requisition(RequisitionStatus.PAUSED);
        status.ok(status.change(resumed, "APPROVED", "  ", 0, hrToken));
        assertThat(after).last().satisfies(transition -> {
            assertThat(transition.change()).isEqualTo(RequisitionStatusChange.RESUME);
            assertThat(transition.after()).isEqualTo(RequisitionStatus.APPROVED);
            assertThat(transition.reason()).isNull();
        });
    }

    // One transaction, and no swallowed exception: a refusal after the change undoes the status and the history row.
    @Test
    void aRefusalAfterTheChangeRollsItBack() throws Exception {
        UUID id = requisition(RequisitionStatus.APPROVED);
        Map<String, Object> rowBefore = status.row(id);
        AFTER.set(transition -> {
            assertThat(statusOf(transition.requisitionId())).isEqualTo("FILLED");
            throw new ApiException(HttpStatus.CONFLICT, "TEST_REFUSED", "x");
        });

        postings.error(status.change(id, "FILLED", "Đã tuyển đủ", 0, hrToken), 409, "TEST_REFUSED");

        assertThat(status.row(id)).isEqualTo(rowBefore);
        assertThat(status.events(id)).isEmpty();
        // The requisition is not left locked or half changed: the same change succeeds once the listener agrees.
        AFTER.set(NOTHING);
        assertThat(status.ok(status.change(id, "FILLED", "Đã tuyển đủ", 0, hrToken)).path("status").asText())
                .isEqualTo("FILLED");
        assertThat(status.events(id)).hasSize(1);
    }

    private UUID requisition(RequisitionStatus requisitionStatus) {
        return postings.requisition(position, department, adminId, requisitionStatus, START);
    }

    // Read with the JdbcTemplate of the application: inside a listener it joins the change's transaction.
    private String statusOf(UUID requisition) {
        return jdbc.queryForObject("SELECT status FROM recruitment_requisitions WHERE id = ?", String.class,
                requisition);
    }

    private int eventCount(UUID requisition) {
        return jdbc.queryForObject("SELECT count(*) FROM requisition_workflow_events WHERE requisition_id = ?",
                Integer.class, requisition);
    }
}
