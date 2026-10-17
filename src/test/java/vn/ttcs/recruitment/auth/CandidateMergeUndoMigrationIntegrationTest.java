package vn.ttcs.recruitment.auth;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import vn.ttcs.recruitment.candidateduplicate.DuplicateDetectionWorker;
import vn.ttcs.recruitment.candidatemerge.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.config.import=", "app.bootstrap.enabled=false", "logging.level.root=WARN", "debug=false",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "app.candidate-duplicates.enabled=true",
        "app.candidate-duplicates.poll-delay-ms=3600000",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.poll-delay-ms=3600000"})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class CandidateMergeUndoMigrationIntegrationTest extends CvApplicationTestSupport {
    @Autowired DuplicateDetectionWorker detector;
    @Autowired CandidateMergeJournal journal;
    @Test void upgradeFromV32PreservesExistingMergeAuditAndMemberships() throws Exception {
        UUID second=application("second@example.test");while(detector.processBatch()>0) { }
        tx.executeWithoutResult(ignored->{
            var snapshot=journal.snapshot(app,second);
            journal.append(new CandidateMergeCommand(recruiter,UUID.randomUUID(),app,second,app,app,snapshot.fingerprint()));
        });
        String original=jdbc.queryForObject("SELECT current_schema()",String.class);
        String schema="undo_upgrade_"+UUID.randomUUID().toString().replace("-","");jdbc.execute("CREATE SCHEMA "+schema);
        var isolated=new org.springframework.jdbc.datasource.DelegatingDataSource(dataSource) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                var connection=super.getConnection();connection.setSchema(schema);return connection;
            }
        };
        try {
            var old=new JdbcTemplate(isolated);
            Flyway.configure().dataSource(isolated).schemas(schema).defaultSchema(schema).target("32").load().migrate();
            for(String table:List.of("user_accounts","departments","positions","recruitment_catalog_items","recruitment_requisitions",
                    "job_postings","job_applications","application_cvs"))
                old.execute("INSERT INTO "+schema+"."+table+" SELECT * FROM "+original+"."+table);
            // Copy an actual valid post-merge state; audit's stamping trigger validates its complete partition.
            var isolatedTx=new org.springframework.transaction.support.TransactionTemplate(
                    new org.springframework.jdbc.datasource.DataSourceTransactionManager(isolated));
            isolatedTx.executeWithoutResult(ignored->{
                old.execute("INSERT INTO candidate_profiles(id,revision,created_at) SELECT id,revision,created_at FROM "+original+".candidate_profiles");
                old.execute("INSERT INTO candidate_memberships SELECT * FROM "+original+".candidate_memberships");
                old.execute("UPDATE candidate_profiles p SET representative_application_id=s.representative_application_id,merge_head_id=s.merge_head_id FROM "+original+".candidate_profiles s WHERE p.id=s.id");
                old.execute("INSERT INTO candidate_merge_operations SELECT * FROM "+original+".candidate_merge_operations");
            });
            var audit=rows(old,"candidate_merge_operations");var groups=rows(old,"candidate_profiles");
            var members=rows(old,"candidate_memberships");var applications=rows(old,"job_applications");
            var upgrade=Flyway.configure().dataSource(isolated).schemas(schema).defaultSchema(schema).target("33").load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);upgrade.validate();
            assertThat(rows(old,"candidate_merge_operations")).isEqualTo(audit);
            assertThat(rows(old,"candidate_profiles")).isEqualTo(groups);
            assertThat(rows(old,"candidate_memberships")).isEqualTo(members);
            assertThat(rows(old,"job_applications")).isEqualTo(applications);
            assertThat(old.queryForObject("SELECT count(*) FROM candidate_merge_undo_events",Integer.class)).isZero();
        } finally { jdbc.execute("DROP SCHEMA "+schema+" CASCADE"); }
        assertThat(jdbc.queryForObject("SELECT current_schema()",String.class)).isEqualTo(original);
    }
    private List<String> rows(JdbcTemplate database,String table) {
        return database.queryForList("SELECT row_to_json(t)::text FROM "+table+" t ORDER BY 1",String.class);
    }
}
