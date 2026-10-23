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

/** V63 upgrade and negotiation invariants on disposable PostgreSQL databases, for both supported lineages. */
class OfferNegotiationMigrationIntegrationTest {
    private static final Timestamp AT = Timestamp.from(Instant.parse("2026-10-18T03:00:00Z"));
    private static final Timestamp DEADLINE = Timestamp.from(AT.toInstant().plusSeconds(86400));
    private static final List<HikariDataSource> DATABASES = new ArrayList<>();
    private static final Map<String, JdbcTemplate> LINEAGES = new LinkedHashMap<>();
    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses", "127.0.0.1").start();
        for (String lineage : List.of("s5", "feedback")) {
            DataSource source = newDatabase();
            Flyway fresh = configured(source, lineage).target("63").load();
            assertThat(fresh.migrate().migrationsExecuted).isEqualTo(53);
            fresh.validate();
            assertThat(fresh.migrate().migrationsExecuted).isZero();
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
    void v62UpgradePreservesOfferTermsResponsesHistoryTokensAndQueuedMail(String lineage) {
        DataSource source = newDatabase();
        configured(source, lineage).target("62").load().migrate();
        var jdbc = new JdbcTemplate(source);
        for (String status : List.of("DRAFT", "CHANGES_REQUESTED", "PENDING_HR", "PENDING_HIGHER", "APPROVED",
                "SENT", "ACCEPTED", "DECLINED", "REJECTED", "EXPIRED", "CANCELLED")) {
            Fixture fixture = fixture(jdbc);
            UUID offer = legacyOffer(jdbc, fixture, status);
            jdbc.update("""
                    INSERT INTO offer_events(id,offer_id,approval_round,event_type,actor_id,actor_name,occurred_at,comment,terms_snapshot)
                    VALUES (?,?,0,?,?,'Staff',?,'Keep original evidence',CAST(? AS jsonb))
                    """, UUID.randomUUID(), offer, status, fixture.actor(), AT, "{\"salary\":15000000,\"original\":true}");
            if (status.equals("SENT") || status.equals("ACCEPTED") || status.equals("DECLINED")) {
                UUID token = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO offer_response_tokens(id,offer_id,application_id,token_hash,recipient_hash,expires_at,created_at)
                        VALUES (?,?,?,encode(pg_catalog.sha256(?),'hex'),encode(pg_catalog.sha256(?),'hex'),?,?)
                        """, token, offer, fixture.application(), token.toString().getBytes(StandardCharsets.UTF_8),
                        fixture.application().toString().getBytes(StandardCharsets.UTF_8), DEADLINE, AT);
                if (status.equals("SENT")) {
                    jdbc.update("""
                            INSERT INTO offer_delivery_outbox(id,offer_id,application_id,offer_version,token_id,key_id,nonce,ciphertext)
                            VALUES (?,?,?,0,?,'fixture-key',?,?)
                            """, UUID.randomUUID(), offer, fixture.application(), token, new byte[12], new byte[16]);
                } else {
                    jdbc.update("""
                            INSERT INTO application_stage_events(id,application_id,offer_id,stage_before,stage_after,actor_type,occurred_at)
                            VALUES (?,?,?,'OFFER',?,'CANDIDATE',?)
                            """, UUID.randomUUID(), fixture.application(), offer, status.equals("ACCEPTED") ? "HIRED" : "REJECTED", AT);
                }
            }
        }
        var before = new LinkedHashMap<String, List<String>>();
        for (String table : List.of("offers", "offer_events", "offer_response_tokens", "offer_delivery_outbox",
                "application_stage_events", "candidate_applications", "job_applications", "flyway_schema_history")) {
            before.put(table, rows(jdbc, table));
        }

        Flyway upgrade = configured(source, lineage).target("63").load();
        assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
        upgrade.validate();
        before.forEach((table, values) -> {
            if (table.equals("flyway_schema_history")) assertThat(rows(jdbc, table)).containsAll(values);
            else assertThat(rows(jdbc, table)).as("preserved %s rows", table).isEqualTo(values);
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM offers WHERE decline_reason IS NOT NULL OR previous_offer_id IS NOT NULL OR revision_number<>1",
                Integer.class)).as("legacy offers keep unknown decline reason and start independent revision chains").isZero();
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void declineReasonsAreOptionalEnumeratedAndOnlyBelongToDeclinedResponses(String lineage) {
        JdbcTemplate jdbc = LINEAGES.get(lineage);
        for (String reason : List.of("SALARY", "LOCATION", "OTHER_JOB", "PERSONAL")) {
            UUID offer = offer(jdbc, fixture(jdbc), "DECLINED", null, 1, reason);
            assertThat(jdbc.queryForObject("SELECT decline_reason FROM offers WHERE id=?", String.class, offer)).isEqualTo(reason);
        }
        UUID unspecified = offer(jdbc, fixture(jdbc), "DECLINED", null, 1, null);
        assertThat(jdbc.queryForObject("SELECT decline_reason FROM offers WHERE id=?", String.class, unspecified)).isNull();
        for (String invalid : List.of("OTHER", "salary", "", " SALARY")) {
            Fixture fixture = fixture(jdbc);
            rejected("23514", () -> offer(jdbc, fixture, "DECLINED", null, 1, invalid));
        }
        for (String state : List.of("DRAFT", "SENT", "ACCEPTED", "REJECTED")) {
            Fixture fixture = fixture(jdbc);
            rejected("23514", () -> offer(jdbc, fixture, state, null, 1, "SALARY"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void supersessionKeepsDeclineEvidenceAndCannotReleaseAnAcceptedOffer(String lineage) {
        JdbcTemplate jdbc = LINEAGES.get(lineage);
        for (String state : List.of("APPROVED", "SENT", "DECLINED")) {
            Fixture fixture = fixture(jdbc);
            UUID old = offer(jdbc, fixture, state, null, 1, state.equals("DECLINED") ? "PERSONAL" : null);
            var before = jdbc.queryForMap("SELECT salary,allowance,start_date,response_deadline,sent_at,responded_at,response_note,decline_reason FROM offers WHERE id=?", old);
            if (state.equals("DECLINED")) {
                rejected("23514", () -> jdbc.update("UPDATE offers SET status='SUPERSEDED',responded_at=NULL,response_note=NULL,decline_reason=NULL WHERE id=?", old));
                rejected("23514", () -> jdbc.update("UPDATE offers SET status='SUPERSEDED',decline_reason='SALARY' WHERE id=?", old));
            }
            jdbc.update("UPDATE offers SET status='SUPERSEDED',version=version+1 WHERE id=?", old);
            assertThat(jdbc.queryForMap("SELECT salary,allowance,start_date,response_deadline,sent_at,responded_at,response_note,decline_reason FROM offers WHERE id=?", old))
                    .isEqualTo(before);
            offer(jdbc, fixture, "DRAFT", old, 2, null);
            rejected("23514", () -> jdbc.update("UPDATE offers SET salary=16000000 WHERE id=?", old));
            rejected("23514", () -> jdbc.update("UPDATE offers SET status='DRAFT' WHERE id=?", old));
            if (state.equals("DECLINED")) {
                rejected("23514", () -> jdbc.update("UPDATE offers SET responded_at=NULL,response_note=NULL,decline_reason=NULL WHERE id=?", old));
                rejected("23514", () -> jdbc.update("UPDATE offers SET response_note='Rewritten response' WHERE id=?", old));
            }
        }
        for (String state : List.of("DRAFT", "CHANGES_REQUESTED", "PENDING_HR", "PENDING_HIGHER", "ACCEPTED", "REJECTED", "EXPIRED", "CANCELLED")) {
            Fixture fixture = fixture(jdbc);
            UUID old = offer(jdbc, fixture, state, null, 1, null);
            rejected("23514", () -> jdbc.update("UPDATE offers SET status='SUPERSEDED' WHERE id=?", old));
            assertThat(jdbc.queryForObject("SELECT status FROM offers WHERE id=?", String.class, old)).isEqualTo(state);
            if (state.equals("ACCEPTED")) {
                Fixture secondApplication = application(jdbc, fixture.actor(), fixture.candidate());
                rejected("23505", () -> offer(jdbc, secondApplication, "DRAFT", null, 1, null));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void replacementChainUsesOneSuccessorPerVersionAndStartsEachOfferAsANewDraft(String lineage) {
        JdbcTemplate jdbc = LINEAGES.get(lineage);
        Fixture fixture = fixture(jdbc);
        UUID first = offer(jdbc, fixture, "APPROVED", null, 1, null);
        jdbc.update("UPDATE offers SET status='SUPERSEDED' WHERE id=?", first);
        UUID second = offer(jdbc, fixture, "DRAFT", first, 2, null);
        assertThat(jdbc.queryForMap("SELECT version,approval_round,submitted_by,hr_approver_id,higher_approver_id,approved_at,sent_at,responded_at FROM offers WHERE id=?", second))
                .containsEntry("version", 0L).containsEntry("approval_round", 0)
                .containsEntry("submitted_by", null).containsEntry("hr_approver_id", null)
                .containsEntry("higher_approver_id", null).containsEntry("approved_at", null)
                .containsEntry("sent_at", null).containsEntry("responded_at", null);
        jdbc.update("UPDATE offers SET status='APPROVED' WHERE id=?", second);
        jdbc.update("UPDATE offers SET status='SUPERSEDED' WHERE id=?", second);
        UUID third = offer(jdbc, fixture, "DRAFT", second, 3, null);
        assertThat(jdbc.queryForList("SELECT revision_number FROM offers WHERE application_id=? ORDER BY revision_number", Integer.class, fixture.application()))
                .containsExactly(1, 2, 3);
        assertThat(jdbc.queryForObject("SELECT previous_offer_id FROM offers WHERE id=?", UUID.class, third)).isEqualTo(second);
        // Release the candidate slot so this assertion isolates predecessor uniqueness rather than active-offer uniqueness.
        jdbc.update("UPDATE offers SET status='CANCELLED' WHERE id=?", third);
        rejected("23505", () -> offer(jdbc, fixture, "DRAFT", second, 3, null));
        rejected("23503", () -> jdbc.update("DELETE FROM offers WHERE id=?", first));
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void replacementCannotBorrowAnotherIdentityOrSkipARevisionOrStartApproved(String lineage) {
        JdbcTemplate jdbc = LINEAGES.get(lineage);
        Fixture fixture = fixture(jdbc);
        UUID previous = offer(jdbc, fixture, "SENT", null, 1, null);
        // The parent must have left the active slot before a successor may exist.
        rejected("23514", () -> offer(jdbc, fixture, "DRAFT", previous, 2, null));
        jdbc.update("UPDATE offers SET status='SUPERSEDED' WHERE id=?", previous);
        for (int revision : List.of(0, 1, 3)) {
            rejected("23514", () -> offer(jdbc, fixture, "DRAFT", previous, revision, null));
        }
        rejected("23514", () -> offer(jdbc, fixture, "APPROVED", previous, 2, null));
        Fixture other = fixture(jdbc);
        rejected("23514", () -> offer(jdbc, other, "DRAFT", previous, 2, null));
        rejected("23514", () -> offer(jdbc, new Fixture(fixture.actor(), other.candidate(), fixture.application(), fixture.position()), "DRAFT", previous, 2, null));
        rejected("23514", () -> offer(jdbc, new Fixture(fixture.actor(), fixture.candidate(), fixture.application(), other.position()), "DRAFT", previous, 2, null));
        for (int revision : List.of(0, 2)) {
            rejected("23514", () -> offer(jdbc, other, "DRAFT", null, revision, null));
        }
        offer(jdbc, fixture, "DRAFT", previous, 2, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void lineageCannotBeRewrittenEvenWhileTheOfferIsStillADraft(String lineage) {
        JdbcTemplate jdbc = LINEAGES.get(lineage);
        Fixture fixture = fixture(jdbc);
        UUID previous = offer(jdbc, fixture, "APPROVED", null, 1, null);
        jdbc.update("UPDATE offers SET status='SUPERSEDED' WHERE id=?", previous);
        UUID next = offer(jdbc, fixture, "DRAFT", previous, 2, null);
        rejected("23514", () -> jdbc.update("UPDATE offers SET revision_number=3 WHERE id=?", next));
        rejected("23514", () -> jdbc.update("UPDATE offers SET previous_offer_id=NULL,revision_number=1 WHERE id=?", next));
        rejected("23514", () -> jdbc.update("UPDATE offers SET previous_offer_id=?,revision_number=3 WHERE id=?", next, previous));
        UUID independent = offer(jdbc, fixture(jdbc), "DRAFT", null, 1, null);
        rejected("23514", () -> jdbc.update("UPDATE offers SET revision_number=2 WHERE id=?", independent));
        rejected("23514", () -> jdbc.update("UPDATE offers SET previous_offer_id=?,revision_number=2 WHERE id=?", previous, independent));
        assertThat(jdbc.queryForObject("SELECT previous_offer_id FROM offers WHERE id=?", UUID.class, next)).isEqualTo(previous);
    }

    @ParameterizedTest
    @ValueSource(strings = {"s5", "feedback"})
    void negotiationRecordsAreBoundToAnOfferAndActorAndCannotBeRewrittenOrDeleted(String lineage) {
        JdbcTemplate jdbc = LINEAGES.get(lineage);
        Fixture fixture = fixture(jdbc);
        UUID offer = offer(jdbc, fixture, "DRAFT", null, 1, null);
        UUID negotiation = negotiation(jdbc, offer, fixture.actor(), "SALARY", "Candidate requests a salary discussion");
        var before = jdbc.queryForMap("SELECT * FROM offer_negotiations WHERE id=?", negotiation);
        rejected("23514", () -> jdbc.update("UPDATE offer_negotiations SET comment='Changed' WHERE id=?", negotiation));
        rejected("23514", () -> jdbc.update("UPDATE offer_negotiations SET reason='LOCATION' WHERE id=?", negotiation));
        rejected("23514", () -> jdbc.update("DELETE FROM offer_negotiations WHERE id=?", negotiation));
        rejected("23503", () -> jdbc.update("DELETE FROM offers WHERE id=?", offer));
        rejected("23503", () -> negotiation(jdbc, UUID.randomUUID(), fixture.actor(), "SALARY", "Unknown offer"));
        rejected("23503", () -> negotiation(jdbc, offer, UUID.randomUUID(), "SALARY", "Unknown actor"));
        rejected("23514", () -> negotiation(jdbc, offer, fixture.actor(), "UNKNOWN", "Invalid category"));
        rejected("23514", () -> negotiation(jdbc, offer, fixture.actor(), "SALARY", "   "));
        assertThat(jdbc.queryForMap("SELECT * FROM offer_negotiations WHERE id=?", negotiation)).isEqualTo(before);
        for (String reason : List.of("LOCATION", "OTHER_JOB", "PERSONAL")) {
            negotiation(jdbc, offer, fixture.actor(), reason, "Discussion of " + reason);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM offer_negotiations WHERE offer_id=?", Integer.class, offer)).isEqualTo(4);
    }

    private static Fixture fixture(JdbcTemplate jdbc) {
        UUID actor = UUID.randomUUID();
        UUID candidate = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts(id,email,full_name,password_hash,created_at) VALUES (?,?,'Staff','test-only',?)",
                actor, actor + "@example.test", AT);
        jdbc.update("INSERT INTO candidates(id,normalized_email,created_at) VALUES (?,?,?)", candidate, candidate + "@example.test", AT);
        return application(jdbc, actor, candidate);
    }

    private static Fixture application(JdbcTemplate jdbc, UUID actor, UUID candidate) {
        UUID department = UUID.randomUUID();
        UUID position = UUID.randomUUID();
        UUID requisition = UUID.randomUUID();
        UUID posting = UUID.randomUUID();
        UUID application = UUID.randomUUID();
        jdbc.update("INSERT INTO departments(id,code,name,manager_user_id) VALUES (?,?,'Engineering',?)", department, "IT-" + department, actor);
        jdbc.update("INSERT INTO positions(id,code,name,level,salary_min,salary_max,created_at,updated_at) VALUES (?,?,'Developer','Junior',10000000,20000000,?,?)",
                position, "DEV-" + position, AT, AT);
        jdbc.update("INSERT INTO recruitment_requisitions(id,position_id,department_id,headcount,reason,status,created_by,created_at,updated_at) VALUES (?,?,?,1,'NEW_HEADCOUNT','DRAFT',?,?,?)",
                requisition, position, department, actor, AT, AT);
        jdbc.update("INSERT INTO job_postings(id,requisition_id,status,revision,title,job_description,candidate_requirements,show_salary,created_by,created_at,updated_at) VALUES (?,?,'DRAFT',1,'Developer','Description','Requirements',false,?,?,?)",
                posting, requisition, actor, AT, AT);
        String email = candidate + "@example.test";
        jdbc.update("""
                INSERT INTO job_applications(id,job_posting_id,requisition_id,full_name,email,normalized_email,phone,stage,received_at,posting_title,tracking_code_hash)
                VALUES (?,?,?,'Candidate',?,?,'0912345678','OFFER',?,'Developer',pg_catalog.sha256(?))
                """, application, posting, requisition, email, email, AT, application.toString().getBytes(StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO candidate_applications(application_id,candidate_id,linked_at) VALUES (?,?,?)", application, candidate, AT);
        return new Fixture(actor, candidate, application, position);
    }

    private static UUID legacyOffer(JdbcTemplate jdbc, Fixture fixture, String status) {
        UUID id = UUID.randomUUID();
        boolean response = status.equals("ACCEPTED") || status.equals("DECLINED");
        jdbc.update("""
                INSERT INTO offers(id,application_id,candidate_id,position_id,candidate_name,email,position_title,salary,allowance,
                    start_date,response_deadline,band_min,band_max,outside_band,status,created_by,created_at,updated_at,sent_at,responded_at,response_note)
                VALUES (?,?,?,?,'Candidate',?,'Developer',15000000,1000000,DATE '2026-11-01',?,10000000,20000000,false,?,?,?,?,?,?,?)
                """, id, fixture.application(), fixture.candidate(), fixture.position(), fixture.candidate() + "@example.test",
                DEADLINE, status, fixture.actor(), AT, AT, status.equals("SENT") || response ? AT : null, response ? AT : null,
                response ? "Original candidate response" : null);
        return id;
    }

    private static UUID offer(JdbcTemplate jdbc, Fixture fixture, String status, UUID previous, int revision, String declineReason) {
        UUID id = UUID.randomUUID();
        boolean response = status.equals("ACCEPTED") || status.equals("DECLINED");
        jdbc.update("""
                INSERT INTO offers(id,application_id,candidate_id,position_id,candidate_name,email,position_title,salary,allowance,
                    start_date,response_deadline,band_min,band_max,outside_band,status,created_by,created_at,updated_at,
                    sent_at,responded_at,response_note,decline_reason,previous_offer_id,revision_number)
                VALUES (?,?,?,?,'Candidate',?,'Developer',15000000,1000000,DATE '2026-11-01',?,10000000,20000000,false,?,?,?,?,?,?,?,?,?,?)
                """, id, fixture.application(), fixture.candidate(), fixture.position(), fixture.candidate() + "@example.test",
                DEADLINE, status, fixture.actor(), AT, AT, status.equals("SENT") || response ? AT : null, response ? AT : null,
                response ? "Original candidate response" : null, declineReason, previous, revision);
        return id;
    }

    private static UUID negotiation(JdbcTemplate jdbc, UUID offer, UUID actor, String reason, String comment) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO offer_negotiations(id,offer_id,reason,comment,actor_id,actor_name,occurred_at) VALUES (?,?,?,?,?,'Staff',?)",
                id, offer, reason, comment, actor, AT);
        return id;
    }

    private static void rejected(String sqlState, Runnable operation) {
        assertThatThrownBy(operation::run).rootCause().isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo(sqlState));
    }

    private static List<String> rows(JdbcTemplate jdbc, String table) {
        String projection = table.equals("offers")
                ? "(to_jsonb(t)-'decline_reason'-'previous_offer_id'-'revision_number')::text" : "to_jsonb(t)::text";
        return jdbc.queryForList("SELECT " + projection + " FROM " + table + " t ORDER BY 1", String.class);
    }

    private static FluentConfiguration configured(DataSource source, String lineage) {
        FluentConfiguration configuration = Flyway.configure().dataSource(source);
        new MigrationLineageConfiguration(lineage).customize(configuration);
        return configuration;
    }

    private static DataSource newDatabase() {
        String name = "negotiation_migration_" + UUID.randomUUID().toString().replace("-", "");
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
