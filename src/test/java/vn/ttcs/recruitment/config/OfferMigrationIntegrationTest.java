package vn.ttcs.recruitment.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V61/V62 invariants on both historical upgrade paths, using only disposable PostgreSQL databases. */
class OfferMigrationIntegrationTest {
    private static final Timestamp AT = Timestamp.from(Instant.parse("2026-10-18T03:00:00Z"));
    private static final Timestamp DEADLINE = Timestamp.from(AT.toInstant().plusSeconds(86400));
    private static final List<HikariDataSource> DATABASES = new ArrayList<>();
    private static final Map<String, JdbcTemplate> LINEAGES = new LinkedHashMap<>();
    private static final List<String> ACTIVE = List.of("DRAFT", "CHANGES_REQUESTED", "PENDING_HR", "PENDING_HIGHER", "APPROVED", "SENT", "ACCEPTED");
    private static final List<String> RELEASED = List.of("DECLINED", "REJECTED", "EXPIRED", "CANCELLED");
    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses", "127.0.0.1").start();
        for (String lineage : List.of("s5", "feedback")) {
            DataSource source = newDatabase();
            configured(source, lineage).target("62").load().migrate();
            LINEAGES.put(lineage, new JdbcTemplate(source));
        }
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        DATABASES.forEach(HikariDataSource::close);
        if (postgres == null) return;
        try (var connection = postgres.getPostgresDatabase().getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(60);
            statement.execute("CHECKPOINT");
        } finally {
            postgres.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void interviewReleaseUpgradePreservesApplicationsLinksAndExistingWithdrawal(String lineage) {
        DataSource source = newDatabase();
        configured(source, lineage).target("60").load().migrate();
        var jdbc = new JdbcTemplate(source);
        Fixture fixture = fixture(jdbc);
        jdbc.update("""
                INSERT INTO application_stage_events(id,application_id,stage_before,stage_after,actor_type,occurred_at)
                VALUES (?,?,'OFFER','WITHDRAWN','CANDIDATE',?)
                """, UUID.randomUUID(), fixture.application(), AT);
        var before = new LinkedHashMap<String, List<String>>();
        for (String table : List.of("user_accounts", "recruitment_requisitions", "job_postings", "job_applications", "candidates", "candidate_applications")) {
            before.put(table, rows(jdbc, table));
        }
        var withdrawal = jdbc.queryForList("SELECT id,application_id,stage_before,stage_after,actor_type,actor_id,reason,occurred_at FROM application_stage_events");
        var history = rows(jdbc, "flyway_schema_history");

        Flyway upgrade = configured(source, lineage).target("62").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(2);
        upgrade.validate();
        before.forEach((table, values) -> assertThat(rows(jdbc, table)).as(table).isEqualTo(values));
        assertThat(jdbc.queryForList("SELECT id,application_id,stage_before,stage_after,actor_type,actor_id,reason,occurred_at FROM application_stage_events"))
                .isEqualTo(withdrawal);
        assertThat(jdbc.queryForObject("SELECT offer_id IS NULL FROM application_stage_events", Boolean.class)).isTrue();
        assertThat(rows(jdbc, "flyway_schema_history")).containsAll(history);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void effectiveOffersReserveTheCandidateAcrossApplicationsIncludingAnAcceptedOffer(String lineage) {
        JdbcTemplate jdbc = LINEAGES.get(lineage);
        for (String active : ACTIVE) {
            Fixture first = fixture(jdbc);
            Fixture second = anotherApplication(jdbc, first);
            UUID offer = offer(jdbc, first, active);
            rejected("23505", () -> offer(jdbc, second, "DRAFT"));
            // An accepted offer remains effective; this slice has no rescission action that could release it.
            if (active.equals("ACCEPTED")) continue;
            jdbc.update("UPDATE offers SET status='CANCELLED' WHERE id=?", offer);
            offer(jdbc, second, "DRAFT");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM offers WHERE candidate_id=?", Integer.class, first.candidate())).isEqualTo(2);
        }
        for (String terminal : RELEASED) {
            Fixture first = fixture(jdbc);
            offer(jdbc, first, terminal);
            offer(jdbc, anotherApplication(jdbc, first), "DRAFT");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void approvalEvidenceCannotBeChangedDeletedOrDetachedFromItsOffer(String lineage) {
        JdbcTemplate jdbc = LINEAGES.get(lineage);
        Fixture fixture = fixture(jdbc);
        UUID offer = offer(jdbc, fixture, "DRAFT");
        UUID event = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO offer_events(id,offer_id,approval_round,event_type,actor_id,actor_name,occurred_at,comment,terms_snapshot)
                VALUES (?,?,1,'APPROVED',?,'Reviewer',?,'Approved terms',CAST(? AS jsonb))
                """, event, offer, fixture.actor(), AT, "{\"salary\":15000000,\"round\":1}");
        String snapshot = jdbc.queryForObject("SELECT terms_snapshot::text FROM offer_events WHERE id=?", String.class, event);
        rejected("23514", () -> jdbc.update("UPDATE offer_events SET comment='Changed' WHERE id=?", event));
        rejected("23514", () -> jdbc.update("DELETE FROM offer_events WHERE id=?", event));
        rejected("23503", () -> jdbc.update("DELETE FROM offers WHERE id=?", offer));
        assertThat(jdbc.queryForObject("SELECT terms_snapshot::text FROM offer_events WHERE id=?", String.class, event)).isEqualTo(snapshot);
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void submittedTermsStayFrozenUntilReviewRequestsChanges(String lineage) {
        JdbcTemplate jdbc = LINEAGES.get(lineage);
        for (String frozen : List.of("PENDING_HR", "PENDING_HIGHER", "APPROVED", "SENT", "ACCEPTED", "DECLINED", "REJECTED", "EXPIRED", "CANCELLED")) {
            UUID offer = offer(jdbc, fixture(jdbc), frozen);
            rejected("23514", () -> jdbc.update("UPDATE offers SET salary=16000000 WHERE id=?", offer));
            rejected("23514", () -> jdbc.update("UPDATE offers SET email='another@example.test' WHERE id=?", offer));
            rejected("23514", () -> jdbc.update("UPDATE offers SET response_deadline=response_deadline+INTERVAL '1 day' WHERE id=?", offer));
        }
        UUID editable = offer(jdbc, fixture(jdbc), "DRAFT");
        jdbc.update("UPDATE offers SET salary=16000000 WHERE id=?", editable);
        jdbc.update("UPDATE offers SET status='PENDING_HR' WHERE id=?", editable);
        rejected("23514", () -> jdbc.update("UPDATE offers SET salary=17000000 WHERE id=?", editable));
        jdbc.update("UPDATE offers SET status='CHANGES_REQUESTED' WHERE id=?", editable);
        jdbc.update("UPDATE offers SET salary=17000000,version=version+1 WHERE id=?", editable);
        assertThat(jdbc.queryForObject("SELECT salary FROM offers WHERE id=?", Long.class, editable)).isEqualTo(17000000L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void candidateOfferTransitionsRequireTheSameApplicationAndOnlyOneResponse(String lineage) {
        JdbcTemplate jdbc = LINEAGES.get(lineage);
        Fixture first = fixture(jdbc);
        Fixture second = anotherApplication(jdbc, first);
        UUID accepted = offer(jdbc, first, "ACCEPTED");
        UUID declined = offer(jdbc, second, "DECLINED");

        rejected("23503", () -> stageEvent(jdbc, first, declined, "OFFER", "HIRED", "CANDIDATE", null));
        rejected("23514", () -> stageEvent(jdbc, first, null, "OFFER", "HIRED", "CANDIDATE", null));
        rejected("23514", () -> stageEvent(jdbc, first, accepted, "INTERVIEW", "HIRED", "CANDIDATE", null));
        rejected("23514", () -> stageEvent(jdbc, first, accepted, "OFFER", "HIRED", "STAFF", first.actor()));
        rejected("23514", () -> stageEvent(jdbc, first, accepted, "OFFER", "HIRED", "CANDIDATE", first.actor()));
        stageEvent(jdbc, first, accepted, "OFFER", "HIRED", "CANDIDATE", null);
        rejected("23505", () -> stageEvent(jdbc, first, accepted, "OFFER", "REJECTED", "CANDIDATE", null));
        stageEvent(jdbc, second, declined, "OFFER", "REJECTED", "CANDIDATE", null);
        // An ordinary withdrawal still needs no offer and keeps its original final-stage restriction.
        Fixture withdrawal = fixture(jdbc);
        stageEvent(jdbc, withdrawal, null, "OFFER", "WITHDRAWN", "CANDIDATE", null);
        rejected("23514", () -> stageEvent(jdbc, fixture(jdbc), null, "HIRED", "WITHDRAWN", "CANDIDATE", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void responseTokensRequireBoundIdentityValidHashesAndAFutureExpiry(String lineage) {
        JdbcTemplate jdbc = LINEAGES.get(lineage);
        Fixture first = fixture(jdbc);
        Fixture second = fixture(jdbc);
        UUID firstOffer = offer(jdbc, first, "SENT");
        UUID secondOffer = offer(jdbc, second, "SENT");
        UUID firstToken = token(jdbc, firstOffer, first.application());
        UUID secondToken = token(jdbc, secondOffer, second.application());
        rejected("23514", () -> jdbc.update("UPDATE offer_response_tokens SET token_hash=? WHERE id=?", "g".repeat(64), firstToken));
        rejected("23514", () -> jdbc.update("UPDATE offer_response_tokens SET recipient_hash='short' WHERE id=?", firstToken));
        rejected("23514", () -> jdbc.update("UPDATE offer_response_tokens SET expires_at=created_at WHERE id=?", firstToken));
        rejected("23503", () -> jdbc.update("UPDATE offer_response_tokens SET application_id=? WHERE id=?", second.application(), firstToken));
        rejected("23505", () -> token(jdbc, firstOffer, first.application()));
        rejected("23505", () -> jdbc.update("UPDATE offer_response_tokens SET token_hash=(SELECT token_hash FROM offer_response_tokens WHERE id=?) WHERE id=?", firstToken, secondToken));
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void outboxCannotAttachAnotherOffersTokenOrApplicationAndIsUniquePerOffer(String lineage) {
        JdbcTemplate jdbc = LINEAGES.get(lineage);
        Fixture first = fixture(jdbc);
        Fixture second = fixture(jdbc);
        UUID firstOffer = offer(jdbc, first, "SENT");
        UUID secondOffer = offer(jdbc, second, "SENT");
        UUID firstToken = token(jdbc, firstOffer, first.application());
        UUID secondToken = token(jdbc, secondOffer, second.application());
        rejected("23503", () -> outbox(jdbc, firstOffer, first.application(), secondToken));
        rejected("23503", () -> outbox(jdbc, firstOffer, second.application(), firstToken));
        UUID outbox = outbox(jdbc, firstOffer, first.application(), firstToken);
        rejected("23505", () -> outbox(jdbc, firstOffer, first.application(), firstToken));
        rejected("23503", () -> jdbc.update("UPDATE offer_delivery_outbox SET token_id=? WHERE id=?", secondToken, outbox));
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void outboxRequiresEncryptedPendingPayloadAndFencedProcessingThenErasesTerminalPayload(String lineage) {
        JdbcTemplate jdbc = LINEAGES.get(lineage);
        Fixture fixture = fixture(jdbc);
        UUID offer = offer(jdbc, fixture, "SENT");
        UUID token = token(jdbc, offer, fixture.application());
        UUID outbox = outbox(jdbc, offer, fixture.application(), token);
        rejected("23514", () -> jdbc.update("UPDATE offer_delivery_outbox SET key_id=NULL WHERE id=?", outbox));
        rejected("23514", () -> jdbc.update("UPDATE offer_delivery_outbox SET nonce=? WHERE id=?", new byte[11], outbox));
        rejected("23514", () -> jdbc.update("UPDATE offer_delivery_outbox SET ciphertext=? WHERE id=?", new byte[15], outbox));
        rejected("23514", () -> jdbc.update("UPDATE offer_delivery_outbox SET ciphertext=? WHERE id=?", new byte[32769], outbox));
        rejected("23514", () -> jdbc.update("UPDATE offer_delivery_outbox SET status='PROCESSING' WHERE id=?", outbox));
        rejected("23514", () -> jdbc.update("UPDATE offer_delivery_outbox SET attempts=7 WHERE id=?", outbox));
        rejected("23514", () -> jdbc.update("UPDATE offer_delivery_outbox SET offer_version=-1 WHERE id=?", outbox));
        jdbc.update("UPDATE offer_delivery_outbox SET status='PROCESSING',lease_token=?,lease_until=? WHERE id=?", UUID.randomUUID(), DEADLINE, outbox);
        rejected("23514", () -> jdbc.update("UPDATE offer_delivery_outbox SET status='RETRY_WAIT' WHERE id=?", outbox));
        jdbc.update("UPDATE offer_delivery_outbox SET status='RETRY_WAIT',lease_token=NULL,lease_until=NULL WHERE id=?", outbox);
        rejected("23514", () -> jdbc.update("UPDATE offer_delivery_outbox SET status='SENT',sent_at=? WHERE id=?", AT, outbox));
        rejected("23514", () -> jdbc.update("UPDATE offer_delivery_outbox SET status='SENT',key_id=NULL,nonce=NULL,ciphertext=NULL WHERE id=?", outbox));
        jdbc.update("UPDATE offer_delivery_outbox SET status='SENT',sent_at=?,key_id=NULL,nonce=NULL,ciphertext=NULL WHERE id=?", AT, outbox);
        rejected("23514", () -> jdbc.update("UPDATE offer_delivery_outbox SET sent_at=NULL WHERE id=?", outbox));
        rejected("23514", () -> jdbc.update("UPDATE offer_delivery_outbox SET ciphertext=? WHERE id=?", new byte[16], outbox));
        for (String terminal : List.of("FAILED", "CANCELLED")) {
            Fixture other = fixture(jdbc);
            UUID otherOffer = offer(jdbc, other, "SENT");
            UUID otherOutbox = outbox(jdbc, otherOffer, other.application(), token(jdbc, otherOffer, other.application()));
            rejected("23514", () -> jdbc.update("UPDATE offer_delivery_outbox SET status=? WHERE id=?", terminal, otherOutbox));
            jdbc.update("UPDATE offer_delivery_outbox SET status=?,key_id=NULL,nonce=NULL,ciphertext=NULL WHERE id=?", terminal, otherOutbox);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void acceptedOrDeclinedOfferMustRetainItsSendAndResponseTimestamps(String lineage) {
        JdbcTemplate jdbc = LINEAGES.get(lineage);
        for (String status : List.of("ACCEPTED", "DECLINED")) {
            Fixture fixture = fixture(jdbc);
            UUID offer = offer(jdbc, fixture, "SENT");
            rejected("23514", () -> jdbc.update("UPDATE offers SET status=? WHERE id=?", status, offer));
            jdbc.update("UPDATE offers SET status=?,responded_at=? WHERE id=?", status, AT, offer);
            rejected("23514", () -> jdbc.update("UPDATE offers SET sent_at=NULL WHERE id=?", offer));
            rejected("23514", () -> jdbc.update("UPDATE offers SET responded_at=NULL WHERE id=?", offer));
        }
        Fixture pending = fixture(jdbc);
        UUID offer = offer(jdbc, pending, "DRAFT");
        rejected("23514", () -> jdbc.update("UPDATE offers SET response_note='Unexpected response' WHERE id=?", offer));
    }

    private static Fixture fixture(JdbcTemplate jdbc) {
        UUID actor = UUID.randomUUID();
        UUID candidate = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts(id,email,full_name,password_hash,created_at) VALUES (?,?,'Nhân sự','test-only',?)", actor, actor + "@example.test", AT);
        jdbc.update("INSERT INTO candidates(id,normalized_email,created_at) VALUES (?,?,?)", candidate, candidate + "@example.test", AT);
        return application(jdbc, actor, candidate);
    }

    private static Fixture anotherApplication(JdbcTemplate jdbc, Fixture first) {
        return application(jdbc, first.actor(), first.candidate());
    }

    private static Fixture application(JdbcTemplate jdbc, UUID actor, UUID candidate) {
        UUID department = UUID.randomUUID();
        UUID position = UUID.randomUUID();
        UUID requisition = UUID.randomUUID();
        UUID posting = UUID.randomUUID();
        UUID application = UUID.randomUUID();
        jdbc.update("INSERT INTO departments(id,code,name,manager_user_id) VALUES (?,?,'Phòng kỹ thuật',?)", department, "IT-" + department, actor);
        jdbc.update("INSERT INTO positions(id,code,name,level,salary_min,salary_max,created_at,updated_at) VALUES (?,?,'Lập trình viên','Junior',10000000,20000000,?,?)", position, "DEV-" + position, AT, AT);
        jdbc.update("INSERT INTO recruitment_requisitions(id,position_id,department_id,headcount,reason,status,created_by,created_at,updated_at) VALUES (?,?,?,1,'NEW_HEADCOUNT','DRAFT',?,?,?)", requisition, position, department, actor, AT, AT);
        jdbc.update("INSERT INTO job_postings(id,requisition_id,status,revision,title,job_description,candidate_requirements,show_salary,created_by,created_at,updated_at) VALUES (?,?,'DRAFT',1,'Lập trình viên','Mô tả','Yêu cầu',false,?,?,?)", posting, requisition, actor, AT, AT);
        String email = candidate + "@example.test";
        jdbc.update("""
                INSERT INTO job_applications(id,job_posting_id,requisition_id,full_name,email,normalized_email,phone,stage,received_at,posting_title,tracking_code_hash)
                VALUES (?,?,?,'Ứng viên',?,?,'0912345678','OFFER',?,'Lập trình viên',pg_catalog.sha256(?))
                """, application, posting, requisition, email, email, AT, application.toString().getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO candidate_applications(application_id,candidate_id,linked_at) VALUES (?,?,?)", application, candidate, AT);
        return new Fixture(actor, candidate, application, position);
    }

    private static UUID offer(JdbcTemplate jdbc, Fixture fixture, String status) {
        UUID id = UUID.randomUUID();
        boolean response = status.equals("ACCEPTED") || status.equals("DECLINED");
        jdbc.update("""
                INSERT INTO offers(id,application_id,candidate_id,position_id,candidate_name,email,position_title,salary,allowance,
                    start_date,response_deadline,band_min,band_max,outside_band,status,created_by,created_at,updated_at,sent_at,responded_at)
                VALUES (?,?,?,?,'Ứng viên',?,'Lập trình viên',15000000,1000000,DATE '2026-11-01',?,10000000,20000000,false,?,?,?,?,?,?)
                """, id, fixture.application(), fixture.candidate(), fixture.position(), fixture.candidate() + "@example.test",
                DEADLINE, status, fixture.actor(), AT, AT, status.equals("SENT") || response ? AT : null, response ? AT : null);
        return id;
    }

    private static UUID token(JdbcTemplate jdbc, UUID offer, UUID application) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO offer_response_tokens(id,offer_id,application_id,token_hash,recipient_hash,expires_at,created_at)
                VALUES (?,?,?,encode(pg_catalog.sha256(?),'hex'),encode(pg_catalog.sha256(?),'hex'),?,?)
                """, id, offer, application, id.toString().getBytes(StandardCharsets.UTF_8), application.toString().getBytes(StandardCharsets.UTF_8), DEADLINE, AT);
        return id;
    }

    private static UUID outbox(JdbcTemplate jdbc, UUID offer, UUID application, UUID token) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO offer_delivery_outbox(id,offer_id,application_id,offer_version,token_id,key_id,nonce,ciphertext) VALUES (?,?,?,0,?,'test-key',?,?)", id, offer, application, token, new byte[12], new byte[16]);
        return id;
    }

    private static void stageEvent(JdbcTemplate jdbc, Fixture fixture, UUID offer, String before, String after, String actorType, UUID actor) {
        jdbc.update("INSERT INTO application_stage_events(id,application_id,offer_id,stage_before,stage_after,actor_type,actor_id,occurred_at) VALUES (?,?,?,?,?,?,?,?)", UUID.randomUUID(), fixture.application(), offer, before, after, actorType, actor, AT);
    }

    private static void rejected(String sqlState, Runnable operation) {
        assertThatThrownBy(operation::run).rootCause().isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo(sqlState));
    }

    private static List<String> rows(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("SELECT t::text FROM " + table + " t ORDER BY t::text", String.class);
    }

    private static FluentConfiguration configured(DataSource source, String lineage) {
        FluentConfiguration configuration = Flyway.configure().dataSource(source);
        new MigrationLineageConfiguration(lineage).customize(configuration);
        return configuration;
    }

    private static DataSource newDatabase() {
        String name = "offer_migration_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(postgres.getPostgresDatabase()).execute("CREATE DATABASE " + name);
        var configuration = new HikariConfig();
        configuration.setDataSource(postgres.getDatabase("postgres", name));
        configuration.setMaximumPoolSize(3);
        configuration.setMinimumIdle(0);
        var source = new HikariDataSource(configuration);
        DATABASES.add(source);
        return source;
    }

    private record Fixture(UUID actor, UUID candidate, UUID application, UUID position) { }
}
