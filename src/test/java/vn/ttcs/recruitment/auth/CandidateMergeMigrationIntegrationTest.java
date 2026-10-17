package vn.ttcs.recruitment.auth;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import vn.ttcs.recruitment.application.ApplicationSubmission;
import vn.ttcs.recruitment.application.PostgresApplicationReceiptWriter;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.config.import=", "app.bootstrap.enabled=false", "logging.level.root=WARN", "debug=false",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "app.cv-contact-confirmation.enabled=true",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.poll-delay-ms=3600000"})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class CandidateMergeMigrationIntegrationTest extends CvApplicationTestSupport {
    @Autowired PostgresApplicationReceiptWriter receipts;

    @Test void upgradingV31KeepsLegacyMembershipsEmptyGroupsContactsCvAndReceipt() throws Exception {
        UUID second=application("second@example.test"),third=application("third@example.test");
        String cvHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(pdf));
        api.expect(api.request("PUT","/api/v1/applications/"+app+"/confirmed-contact", """
                {"fullName":"Confirmed Name","email":"confirmed@example.test","phone":"0912345678",
                 "nameSource":"MANUAL","emailSource":"MANUAL","phoneSource":"MANUAL","expectedRevision":0,
                 "expectedCvHash":"%s"}
                """.formatted(cvHash),recruiterToken),200);
        receipts.accept(posting,new ApplicationSubmission("Receipt","receipt@example.test","0912345678",null,null),
                validator.validate("cv.pdf",new ByteArrayInputStream(pdf)));
        String original=jdbc.queryForObject("SELECT current_schema()",String.class);
        String schema="merge_upgrade_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE SCHEMA "+schema);
        var isolated=new org.springframework.jdbc.datasource.DelegatingDataSource(dataSource) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                var c=super.getConnection();c.setSchema(schema);return c;
            }
        };
        try {
            var old=new JdbcTemplate(isolated);
            Flyway.configure().dataSource(isolated).schemas(schema).defaultSchema(schema).target("31").load().migrate();
            for(String table:List.of("user_accounts","departments","positions","recruitment_catalog_items","recruitment_requisitions",
                    "job_postings","job_applications","application_cvs","application_email_outbox","application_confirmed_contacts","application_contact_history"))
                old.execute("INSERT INTO "+schema+"."+table+" SELECT * FROM "+original+"."+table);
            UUID group=UUID.randomUUID(),single=UUID.randomUUID(),empty=UUID.randomUUID();
            for(UUID id:List.of(group,single,empty))old.update("INSERT INTO candidate_profiles(id) VALUES (?)",id);
            old.update("INSERT INTO candidate_memberships(application_id,candidate_id) VALUES (?,?),(?,?),(?,?)",app,group,second,group,third,single);
            var memberships=old.queryForList("SELECT * FROM candidate_memberships ORDER BY application_id");
            var submissions=rows(old,"job_applications");var confirmed=rows(old,"application_confirmed_contacts");var history=rows(old,"application_contact_history");
            byte[] ciphertext=old.queryForObject("SELECT ciphertext FROM application_email_outbox",byte[].class);
            Flyway.configure().dataSource(isolated).schemas(schema).defaultSchema(schema).target("32").load().migrate();
            assertThat(old.queryForList("SELECT * FROM candidate_memberships ORDER BY application_id")).isEqualTo(memberships);
            UUID expected=app.toString().compareTo(second.toString())<0?app:second;
            assertThat(old.queryForObject("SELECT representative_application_id FROM candidate_profiles WHERE id=?",UUID.class,group)).isEqualTo(expected);
            assertThat(old.queryForObject("SELECT representative_application_id FROM candidate_profiles WHERE id=?",UUID.class,single)).isEqualTo(third);
            assertThat(old.queryForObject("SELECT representative_application_id FROM candidate_profiles WHERE id=?",UUID.class,empty)).isNull();
            assertThat(old.queryForList("SELECT revision FROM candidate_profiles",Long.class)).containsOnly(1L);
            assertThat(old.queryForObject("SELECT count(*) FROM candidate_merge_operations",Integer.class)).isZero();
            assertThat(rows(old,"job_applications")).isEqualTo(submissions);
            assertThat(rows(old,"application_confirmed_contacts")).isEqualTo(confirmed);
            assertThat(rows(old,"application_contact_history")).isEqualTo(history);
            assertThat(old.queryForObject("SELECT ciphertext FROM application_email_outbox",byte[].class)).isEqualTo(ciphertext);
            assertThat(old.queryForObject("SELECT content FROM application_cvs WHERE application_id=?",byte[].class,app)).isEqualTo(pdf);
            Flyway.configure().dataSource(isolated).schemas(schema).defaultSchema(schema).target("32").load().validate();
            var next=Flyway.configure().dataSource(isolated).schemas(schema).defaultSchema(schema).target("33").load();
            assertThat(next.migrate().migrationsExecuted).isEqualTo(1);next.validate();
            assertThat(rows(old,"job_applications")).isEqualTo(submissions);
            assertThat(old.queryForList("SELECT * FROM candidate_memberships ORDER BY application_id")).isEqualTo(memberships);
            assertThat(old.queryForObject("SELECT candidate_merge_undo_open(clock_timestamp(),clock_timestamp()+INTERVAL '1 second')",Boolean.class)).isFalse();
        } finally {jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
        assertThat(jdbc.queryForObject("SELECT current_schema()",String.class)).isEqualTo(original);
    }

    @Test void validatorRejectsMalformedDuplicatePartialAndExtraneousPersonalData() {
        String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString();
        String first="{\"applicationId\":\""+a+"\",\"candidateId\":\""+a+"\",\"contactRevision\":0}";
        String second="{\"applicationId\":\""+b+"\",\"candidateId\":\""+b+"\",\"contactRevision\":1}";
        String valid="["+first+","+second+"]";
        assertThat(jdbc.queryForObject("SELECT valid_candidate_merge_members(?::jsonb,?::uuid,?::uuid)",Boolean.class,valid,a,b)).isTrue();
        for(String invalid:List.of("{}","[]","["+first+"]","["+first+","+first+"]",
                valid.replace("contactRevision\":1","contactRevision\":-1"),
                valid.replace("contactRevision\":1","contactRevision\":1.2"),
                valid.replace("contactRevision\":1","contactRevision\":9223372036854775808"),
                valid.replace("contactRevision\":1","contactRevision\":1,\"email\":\"private@example.test\""),
                valid.replace(b,"not-a-uuid")))
            assertThat(jdbc.queryForObject("SELECT valid_candidate_merge_members(?::jsonb,?::uuid,?::uuid)",Boolean.class,invalid,a,b)).isFalse();
    }
    private List<String> rows(JdbcTemplate db,String table){return db.queryForList("SELECT row_to_json(t)::text FROM "+table+" t ORDER BY 1",String.class);}
}
