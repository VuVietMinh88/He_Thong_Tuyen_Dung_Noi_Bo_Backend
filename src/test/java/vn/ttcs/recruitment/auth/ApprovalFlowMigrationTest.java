package vn.ttcs.recruitment.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalFlowMigrationTest {
    @Test
    void upgradesV6AndEnforcesCompleteImmutableVersionsAndScope() throws Exception {
        try (var pg = EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses", "127.0.0.1").start()) {
            var ds = pg.getPostgresDatabase();
            Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("6").load().migrate();
            var jdbc = new JdbcTemplate(ds);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            UUID actor = UUID.randomUUID(), department = UUID.randomUUID(), flow = UUID.randomUUID();
            jdbc.update("INSERT INTO user_accounts(id,email,full_name,password_hash,created_at) VALUES (?,?,?,'hash',now())",
                    actor, "migration@example.test", "Migration");
            jdbc.update("INSERT INTO departments(id,code,name,manager_user_id) VALUES (?,'HR','HR',?)", department, actor);
            var before = jdbc.queryForMap("SELECT * FROM departments WHERE id=?", department);
            var flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();
            assertThat(jdbc.queryForMap("SELECT * FROM departments WHERE id=?", department)).isEqualTo(before);

            tx.executeWithoutResult(s -> {
                jdbc.update("INSERT INTO approval_flows VALUES (?,?,1)", flow, department);
                version(jdbc, flow, 1, actor);
                step(jdbc, flow, 1, 1, null, "HIRING_MANAGER");
                step(jdbc, flow, 1, 2, 20000000L, "HR_MANAGER");
                seal(jdbc, flow, 1);
            });
            assertThatThrownBy(() -> jdbc.update("UPDATE approval_flow_versions SET name='changed' WHERE flow_id=?", flow))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM approval_flow_steps WHERE flow_id=?", flow))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> step(jdbc, flow, 1, 3, 30000000L, "ADMIN"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> version(jdbc, flow, 2, actor)))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                version(jdbc, flow, 2, actor);
                step(jdbc, flow, 2, 1, 100L, "ADMIN");
                seal(jdbc, flow, 2);
            })).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_flow_versions", Integer.class)).isEqualTo(1);
            tx.executeWithoutResult(s -> {
                version(jdbc, flow, 2, actor);
                step(jdbc, flow, 2, 1, null, "ADMIN");
                seal(jdbc, flow, 2);
                jdbc.update("UPDATE approval_flows SET current_version=2 WHERE id=?", flow);
            });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_flow_versions", Integer.class)).isEqualTo(2);
            assertThatThrownBy(() -> jdbc.update("UPDATE approval_flows SET current_version=1 WHERE id=?", flow))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                jdbc.update("INSERT INTO approval_flows VALUES (?,?,1)", UUID.randomUUID(), department);
            })).isInstanceOf(RuntimeException.class);
        }
    }

    private static void version(JdbcTemplate jdbc, UUID flow, int version, UUID actor) {
        jdbc.update("INSERT INTO approval_flow_versions(flow_id,version,name,created_by,created_at) VALUES (?,?,'Flow',?,now())",
                flow, version, actor);
    }

    private static void step(JdbcTemplate jdbc, UUID flow, int version, int position, Long threshold, String role) {
        jdbc.update("INSERT INTO approval_flow_steps(flow_id,version,position,salary_threshold,approver_role) VALUES (?,?,?,?,?)",
                flow, version, position, threshold, role);
    }

    private static void seal(JdbcTemplate jdbc, UUID flow, int version) {
        jdbc.update("UPDATE approval_flow_versions SET sealed=true WHERE flow_id=? AND version=?", flow, version);
    }
}
