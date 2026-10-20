package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
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
import vn.ttcs.recruitment.notification.NotificationWorker;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.auth.ApprovalTestSupport.step;

/**
 * Task 544: a submitted requisition notifies the people who may decide its current level, after the submission
 * committed; each approval moves the notification to the next level's approvers, and processing an event again, or a
 * second event about the same level, never notifies anyone twice.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "app.notifications.poll-delay-ms=3600000",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApprovalNotificationIntegrationTest {
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");
    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private NotificationWorker worker;
    private ApprovalTestSupport api;
    private String passwordHash;
    private UUID hrId, approverA, approverB, it, position;
    private String hrToken, itHeadToken;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
    }

    @BeforeEach
    void fixture() throws Exception {
        clock.set(START);
        api = new ApprovalTestSupport(jdbc, json, transactions, Integer.parseInt(environment.getRequiredProperty("local.server.port")));
        jdbc.execute("TRUNCATE notifications, notification_events CASCADE");
        api.reset();
        bootstrap.run(new DefaultApplicationArguments());
        UUID adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        hrId = account("hr@example.test", "Nguyễn Thị Hoa", Role.HR_MANAGER);
        UUID itHead = account("it@example.test", "Trưởng IT", Role.HIRING_MANAGER);
        approverA = account("a@example.test", "Giám đốc A", Role.APPROVER);
        approverB = account("b@example.test", "Giám đốc B", Role.APPROVER);
        hrToken = api.token("hr@example.test");
        itHeadToken = api.token("it@example.test");
        it = api.department("IT", itHead, null, START);
        position = api.position(START);
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"), step(2, 20_000_000L, null, "APPROVER"));
    }

    @Test
    void eachLevelsApproversAreNotifiedOnceAfterTheDecisionCommits() throws Exception {
        UUID requisition = api.submitted(position, it, 30_000_000L, itHeadToken, itHeadToken);
        // The submission only recorded an event; nobody is notified before the worker runs.
        assertThat(count("notifications")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_events WHERE status='PENDING'", Long.class)).isEqualTo(1);
        drain();
        assertThat(notified()).containsExactly(Map.of("recipient_id", hrId, "notification_type", "APPROVAL_PENDING",
                "link_type", "REQUISITION", "link_id", requisition));

        // The same event processed again (as after a crash) and a second event about the same level add nothing.
        jdbc.update("UPDATE notification_events SET status='PENDING', processed_at=NULL");
        drain();
        assertThat(count("notifications")).isEqualTo(1);

        clock.set(START.plusSeconds(60));
        api.expect(api.decide(requisition, "APPROVE", null, 1, hrToken), 200);
        drain();
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notifications WHERE link_id=? ORDER BY recipient_id",
                UUID.class, requisition)).containsExactlyInAnyOrderElementsOf(List.of(hrId, approverA, approverB));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_events WHERE status='DONE'", Long.class)).isEqualTo(2);
    }

    @Test
    void aRolledBackDecisionNotifiesNobodyAndALockedApproverIsSkipped() throws Exception {
        UUID requisition = api.submitted(position, it, 30_000_000L, itHeadToken, itHeadToken);
        drain();
        jdbc.update("UPDATE user_accounts SET admin_locked_at=?, admin_lock_reason='Nghỉ', admin_locked_by=? WHERE id=?",
                java.sql.Timestamp.from(START), hrId, approverB);
        // A stale revision is refused: the decision rolls back and records no event.
        api.expect(api.decide(requisition, "APPROVE", null, 5, hrToken), 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_events", Long.class)).isEqualTo(1);
        api.expect(api.decide(requisition, "APPROVE", null, 1, hrToken), 200);
        drain();
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notifications WHERE notification_type='APPROVAL_PENDING'"
                + " AND recipient_id <> ?", UUID.class, hrId)).containsExactly(approverA);
    }

    private void drain() {
        for (int i = 0; i < 20 && worker.processNext(); i++) { /* drain */ }
    }

    private List<Map<String, Object>> notified() {
        return jdbc.queryForList("SELECT recipient_id, notification_type, link_type, link_id FROM notifications");
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private UUID account(String email, String name, Role... roles) {
        return accounts.saveAndFlush(new Account(email, name, passwordHash, Set.of(roles), START)).getId();
    }
}
