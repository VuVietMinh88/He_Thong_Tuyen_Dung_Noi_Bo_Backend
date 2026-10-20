package vn.ttcs.recruitment.auth;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.application.*;
import vn.ttcs.recruitment.common.ApiException;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "app.application-intake.enabled=true",
        "app.application-intake.trusted-proxies=", "app.application-intake.cleanup-delay-ms=3600000"
})
@Import({AuthIntegrationTest.DatabaseConfiguration.class, ApplicationIpQuotaIntegrationTest.WriterConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationIpQuotaIntegrationTest {
    @Autowired PostgresApplicationAttemptQuota quota;
    @Autowired ApplicationClientIpResolver resolver;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager manager;
    @Autowired Environment environment;
    @Autowired ObjectMapper json;
    @Autowired ApplicationIntakeIntegrationTest.TestWriter writer;
    @Autowired AuthIntegrationTest.MutableClock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String IP = "192.0.2.1";

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM application_ip_quotas");
        jdbc.update("DELETE FROM application_cvs"); jdbc.update("DELETE FROM job_applications");
        writer.reset(); clock.set(Instant.parse("2026-10-14T03:00:00Z"));
    }
    private MockHttpServletRequest request(String ip) {
        var request = new MockHttpServletRequest(); request.setRemoteAddr(ip); return request;
    }
    private int count() { return jdbc.queryForObject("SELECT count(*) FROM application_ip_attempts", Integer.class); }
    private int count(String ip) { return jdbc.queryForObject("SELECT count(*) FROM application_ip_attempts WHERE client_ip=CAST(? AS inet)", Integer.class, ip); }
    private void consume(String ip, int number) { for (int i=0;i<number;i++) quota.checkAndRecord(request(ip)); }

    @Test void permitsFiveAndDenialsDoNotRecordOrExtendTheWindow() {
        consume(IP,5); var before = jdbc.queryForList("SELECT * FROM application_ip_attempts ORDER BY id");
        var last = jdbc.queryForObject("SELECT last_attempt_at FROM application_ip_quotas",Timestamp.class);
        for (int i=0;i<3;i++) assertThatThrownBy(() -> consume(IP,1))
                .isInstanceOfSatisfying(ApplicationRateLimitException.class, error ->
                        assertThat(error.getRetryAfterSeconds()).isBetween(3590L,3600L));
        assertThat(jdbc.queryForList("SELECT * FROM application_ip_attempts ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT last_attempt_at FROM application_ip_quotas",Timestamp.class)).isEqualTo(last);
    }
    @Test void windowSlidesRatherThanResettingByTheHour() {
        consume(IP,5);
        jdbc.update("""
                UPDATE application_ip_attempts SET attempted_at=clock_timestamp()-INTERVAL '1 hour'-INTERVAL '1 second'
                WHERE id=(SELECT min(id) FROM application_ip_attempts)
                """);
        consume(IP,1); assertThat(count(IP)).isEqualTo(5);
        assertThatThrownBy(() -> consume(IP,1)).isInstanceOf(ApplicationRateLimitException.class);
    }
    @Test void retryAfterRoundsUpTheOldestLiveAttempt() {
        consume(IP,5);
        jdbc.update("UPDATE application_ip_attempts SET attempted_at=clock_timestamp()-INTERVAL '3590 seconds'");
        assertThatThrownBy(() -> consume(IP,1)).isInstanceOfSatisfying(ApplicationRateLimitException.class,
                error -> assertThat(error.getRetryAfterSeconds()).isBetween(9L,10L));
    }
    @Test void exactSixtyMinuteBoundaryExpiresButOneMicrosecondInsideStillCounts() {
        Instant now=Instant.parse("2026-10-14T04:00:00Z");
        var deterministicJdbc=new JdbcTemplate(dataSource) {
            @Override public <T> T queryForObject(String sql,Class<T> type) {
                return sql.equals("SELECT clock_timestamp()") ? type.cast(Timestamp.from(now)) : super.queryForObject(sql,type);
            }
        };
        var provider=new PostgresApplicationAttemptQuota(resolver,deterministicJdbc,manager);
        consume(IP,5);
        jdbc.update("UPDATE application_ip_attempts SET attempted_at=?",Timestamp.from(now.minusSeconds(3600).plusNanos(1000)));
        assertThatThrownBy(() -> provider.checkAndRecord(request(IP)))
                .isInstanceOfSatisfying(ApplicationRateLimitException.class,error -> assertThat(error.getRetryAfterSeconds()).isEqualTo(1));
        jdbc.update("UPDATE application_ip_attempts SET attempted_at=? WHERE id=(SELECT min(id) FROM application_ip_attempts)",Timestamp.from(now.minusSeconds(3600)));
        provider.checkAndRecord(request(IP)); assertThat(count(IP)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT min(attempted_at) FROM application_ip_attempts",Timestamp.class))
                .isEqualTo(Timestamp.from(now.minusSeconds(3600).plusNanos(1000)));
    }
    @Test void trustedProxyClientsAreIndependentAndForgedLeftmostDoesNotBypassTheirQuota() {
        var provider=new PostgresApplicationAttemptQuota(new ApplicationClientIpResolver("10.0.0.1", "NONE"),jdbc,manager);
        var request=request("10.0.0.1"); request.addHeader("X-Forwarded-For","198.51.100.200, 192.0.2.1");
        for(int i=0;i<5;i++)provider.checkAndRecord(request);
        request.removeHeader("X-Forwarded-For"); request.addHeader("X-Forwarded-For","198.51.100.201, 192.0.2.1");
        assertThatThrownBy(() -> provider.checkAndRecord(request)).isInstanceOf(ApplicationRateLimitException.class);
        request.removeHeader("X-Forwarded-For"); request.addHeader("X-Forwarded-For","192.0.2.2"); provider.checkAndRecord(request);
        assertThat(count(IP)).isEqualTo(5);assertThat(count("192.0.2.2")).isEqualTo(1);
        request.removeHeader("X-Forwarded-For"); request.addHeader("X-Forwarded-For","invalid.test");
        assertThatThrownBy(() -> provider.checkAndRecord(request)).isInstanceOfSatisfying(ApiException.class,error -> assertThat(error.getCode()).isEqualTo("APPLICATION_CLIENT_IP_INVALID"));
        assertThat(count()).isEqualTo(6);
    }
    @Test void differentIpsAreIndependentAndMappedIpv4SharesOneQuota() {
        consume(IP,5);
        assertThatThrownBy(() -> consume("::ffff:192.0.2.1",1)).isInstanceOf(ApplicationRateLimitException.class);
        consume("192.0.2.2",5); consume("2001:db8::1",5);
        assertThat(count()).isEqualTo(15);
    }
    @Test void quotaSurvivesOuterTransactionRollback() {
        var outer = new TransactionTemplate(manager);
        outer.executeWithoutResult(status -> { consume(IP,1); status.setRollbackOnly(); });
        assertThat(count(IP)).isEqualTo(1);
    }
    @Test void anotherProviderInstanceUsesTheSameDurableQuota() {
        consume(IP,5);
        var restarted = new PostgresApplicationAttemptQuota(new ApplicationClientIpResolver("", "NONE"),jdbc,manager);
        assertThatThrownBy(() -> restarted.checkAndRecord(request(IP))).isInstanceOf(ApplicationRateLimitException.class);
        assertThat(count(IP)).isEqualTo(5);
    }
    @Test void simultaneousFirstRequestsAcrossInstancesAdmitExactlyFive() throws Exception {
        var start = new CountDownLatch(1);
        var second = new PostgresApplicationAttemptQuota(resolver,jdbc,manager);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i=0;i<8;i++) {
                var provider = i%2==0 ? quota : second;
                futures.add(executor.submit(() -> {
                    if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException();
                    try { provider.checkAndRecord(request(IP)); return true; }
                    catch (ApplicationRateLimitException limited) { return false; }
                }));
            }
            start.countDown(); int admitted = 0;
            for(var future:futures) if(future.get(15,TimeUnit.SECONDS))admitted++;
            assertThat(admitted).isEqualTo(5); assertThat(count(IP)).isEqualTo(5);
        } finally { start.countDown(); }
    }
    @Test void readsDatabaseTimeAfterWaitingForTheIpLock() throws Exception {
        consume(IP,5);
        jdbc.update("UPDATE application_ip_attempts SET attempted_at=clock_timestamp()-INTERVAL '3599 seconds'");
        try(var blocker=dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            int pid;
            try(var command=blocker.createStatement()) {
                try(var row=command.executeQuery("SELECT pg_backend_pid()")){row.next();pid=row.getInt(1);}
                command.executeQuery("SELECT * FROM application_ip_quotas FOR UPDATE").close();
            }
            var executor=Executors.newSingleThreadExecutor();
            try {
                var future=executor.submit(() -> quota.checkAndRecord(request(IP)));
                long end=System.nanoTime()+Duration.ofSeconds(1).toNanos();
                boolean blocked=false;
                while(System.nanoTime()<end) {
                    blocked=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid)))",Boolean.class,pid);
                    if(blocked)break; Thread.sleep(10);
                }
                assertThat(blocked).isTrue();
                Thread.sleep(1100); blocker.commit(); future.get(10,TimeUnit.SECONDS);
                assertThat(count(IP)).isEqualTo(1);
            } finally { blocker.rollback(); executor.shutdownNow(); }
        }
    }
    @Test void cleanupDeletesExpiredBucketsAndAttemptsButKeepsActiveOnes() {
        consume(IP,5); consume("192.0.2.2",1);
        jdbc.update("UPDATE application_ip_quotas SET last_attempt_at=clock_timestamp()-INTERVAL '61 minutes' WHERE client_ip=CAST(? AS inet)",IP);
        jdbc.update("UPDATE application_ip_attempts SET attempted_at=clock_timestamp()-INTERVAL '61 minutes' WHERE client_ip=CAST(? AS inet)",IP);
        assertThat(quota.deleteExpired()).isEqualTo(1); assertThat(count()).isEqualTo(1);
        consume(IP,5); assertThat(count()).isEqualTo(6);
    }
    @Test void cleanupSkipsLockedIpsAndDoesNotBlockUnrelatedClients() throws Exception {
        consume(IP,1);
        jdbc.update("UPDATE application_ip_quotas SET last_attempt_at=clock_timestamp()-INTERVAL '61 minutes'");
        try(var blocker=dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            blocker.createStatement().executeQuery("SELECT * FROM application_ip_quotas FOR UPDATE").close();
            assertThat(quota.deleteExpired()).isZero(); consume("192.0.2.2",1);
            blocker.rollback();
        }
        assertThat(quota.deleteExpired()).isEqualTo(1); assertThat(count()).isEqualTo(1);
    }
    @Test void cleanupIsBoundedToFiveHundredBucketsPerRun() {
        jdbc.execute("""
                INSERT INTO application_ip_quotas(client_ip,last_attempt_at)
                SELECT '198.18.0.0'::inet+n,clock_timestamp()-INTERVAL '61 minutes' FROM generate_series(1,501) n
                """);
        assertThat(quota.deleteExpired()).isEqualTo(500);
        assertThat(quota.deleteExpired()).isEqualTo(1);assertThat(quota.deleteExpired()).isZero();
    }
    @Test void cleanupAndNewAttemptsNeverLoseLiveRecords() throws Exception {
        consume(IP,5);
        jdbc.update("UPDATE application_ip_attempts SET attempted_at=clock_timestamp()-INTERVAL '61 minutes'");
        jdbc.update("UPDATE application_ip_quotas SET last_attempt_at=clock_timestamp()-INTERVAL '61 minutes'");
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(6)) {
            var cleanup=executor.submit(() -> {start.await();return quota.deleteExpired();});
            var futures=new ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<5;i++)futures.add(executor.submit(() -> {start.await();consume(IP,1);return null;}));
            start.countDown();cleanup.get(10,TimeUnit.SECONDS);
            for(var future:futures)future.get(10,TimeUnit.SECONDS);
            assertThat(count(IP)).isEqualTo(5);
            assertThatThrownBy(() -> consume(IP,1)).isInstanceOf(ApplicationRateLimitException.class);
        } finally {start.countDown();}
    }
    @Test void migrationUpgradesV26WithoutChangingExistingAccountsOrApplicationTables() throws Exception {
        String original=jdbc.queryForObject("SELECT current_schema()",String.class);
        String schema="quota_upgrade_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE SCHEMA "+schema);
        var isolated=new org.springframework.jdbc.datasource.DelegatingDataSource(dataSource) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                var connection=super.getConnection();connection.setSchema(schema);return connection;
            }
        };
        try {
            org.flywaydb.core.Flyway.configure().dataSource(isolated).schemas(schema).defaultSchema(schema).target("26").load().migrate();
            var legacy=new JdbcTemplate(isolated);UUID user=UUID.randomUUID();
            legacy.update("INSERT INTO user_accounts(id,email,password_hash,full_name,enabled,created_at) VALUES (?,'legacy@example.test','legacy-hash','Legacy',true,clock_timestamp())",user);
            var before=legacy.queryForList("SELECT * FROM user_accounts WHERE id=?",user);
            var columns=legacy.queryForList("SELECT table_name,column_name,data_type FROM information_schema.columns WHERE table_schema=? AND table_name IN ('job_applications','application_cvs') ORDER BY table_name,ordinal_position",schema);
            org.flywaydb.core.Flyway.configure().dataSource(isolated).schemas(schema).defaultSchema(schema).target("27").load().migrate();
            assertThat(legacy.queryForList("SELECT * FROM user_accounts WHERE id=?",user)).isEqualTo(before);
            assertThat(legacy.queryForList("SELECT table_name,column_name,data_type FROM information_schema.columns WHERE table_schema=? AND table_name IN ('job_applications','application_cvs') ORDER BY table_name,ordinal_position",schema)).isEqualTo(columns);
            assertThat(legacy.queryForObject("SELECT count(*) FROM application_ip_attempts",Integer.class)).isZero();
            legacy.update("INSERT INTO application_ip_quotas VALUES ('192.0.2.7',clock_timestamp())");
            legacy.update("INSERT INTO application_ip_attempts(client_ip,attempted_at) VALUES ('192.0.2.7',clock_timestamp())");
            assertThatThrownBy(() -> legacy.update("INSERT INTO application_ip_quotas VALUES ('192.0.2.0/24',clock_timestamp())"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            legacy.update("DELETE FROM application_ip_quotas");
            assertThat(legacy.queryForObject("SELECT count(*) FROM application_ip_attempts",Integer.class)).isZero();
        } finally {jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
        assertThat(jdbc.queryForObject("SELECT current_schema()",String.class)).isEqualTo(original);
    }
    @Test void aLongHeldIpLockFailsClosedWithoutConsumingAnAttempt() throws Exception {
        consume(IP,1);
        try(var blocker=dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try(var statement=blocker.createStatement()){statement.executeQuery("SELECT * FROM application_ip_quotas FOR UPDATE").close();}
            long started=System.nanoTime();
            try { assertThatThrownBy(() -> consume(IP,1)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.getCode()).isEqualTo("APPLICATION_QUOTA_UNAVAILABLE")); }
            finally { blocker.rollback(); }
            assertThat(Duration.ofNanos(System.nanoTime()-started).toSeconds()).isBetween(1L,4L);
        }
        assertThat(count(IP)).isEqualTo(1);consume(IP,1);assertThat(count(IP)).isEqualTo(2);
    }
    @Test void failedAttemptInsertRollsBackTheBucketAndFailsClosed() {
        jdbc.execute("CREATE FUNCTION fail_quota_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'private-IP-SQL'; END $$");
        jdbc.execute("CREATE TRIGGER fail_quota_test BEFORE INSERT ON application_ip_attempts FOR EACH ROW EXECUTE FUNCTION fail_quota_test()");
        try {
            assertThatThrownBy(() -> consume(IP,1)).isInstanceOfSatisfying(ApiException.class,error -> {
                assertThat(error.getCode()).isEqualTo("APPLICATION_QUOTA_UNAVAILABLE"); assertThat(error.getCause()).isNull();
            });
            assertThat(count()).isZero(); assertThat(jdbc.queryForObject("SELECT count(*) FROM application_ip_quotas",Integer.class)).isZero();
        } finally { jdbc.execute("DROP TRIGGER fail_quota_test ON application_ip_attempts"); jdbc.execute("DROP FUNCTION fail_quota_test()"); }
        consume(IP,1); assertThat(count()).isEqualTo(1);
    }
    @Test void invalidPostsAcrossPostingsConsumeQuotaAndSpoofedHeadersCannotResetIt() throws Exception {
        for(int i=0;i<5;i++)assertThat(send("POST",path(UUID.randomUUID()),new byte[0],"application/json",
                Map.of("X-Forwarded-For","192.0.2."+(i+1))).statusCode()).isEqualTo(415);
        var limited=send("POST",path(UUID.randomUUID()),new byte[0],"application/json",Map.of("X-Forwarded-For","198.51.100.200","Origin","http://localhost:5173"));
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After").map(Long::parseLong)).hasValueSatisfying(value -> assertThat(value).isBetween(3590L,3600L));
        assertThat(limited.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(limited.headers().firstValue("Access-Control-Expose-Headers")).hasValueSatisfying(value -> assertThat(value).contains("Retry-After"));
        assertThat(json.readTree(limited.body()).get("code").asString()).isEqualTo("APPLICATION_RATE_LIMIT_EXCEEDED");
        assertThat(json.readTree(limited.body()).properties()).hasSize(3);
        assertThat(limited.body()).doesNotContain("127.0.0.1","198.51.100", "SQL", "tracking"); assertThat(count()).isEqualTo(5);
    }
    @Test void preflightGetAndRejectedCorsDoNotConsumeQuota() throws Exception {
        String path=path(UUID.randomUUID());
        assertThat(send("OPTIONS",path,new byte[0],"application/json",Map.of("Origin","http://localhost:5173",
                "Access-Control-Request-Method","POST")).statusCode()).isEqualTo(200);
        assertThat(send("POST",path,new byte[0],"application/json",Map.of("Origin","https://evil.test")).statusCode()).isEqualTo(403);
        assertThat(send("GET","/api/v1/public/application-sources",new byte[0],"application/json",Map.of()).statusCode()).isEqualTo(200);
        assertThat(count()).isZero();
    }
    @Test void missingPostingConsumesAnAttemptAndDatabaseFailureReturns503BeforeParsing() throws Exception {
        assertThat(send("POST",path(UUID.randomUUID()),new byte[0],"multipart/form-data; boundary=test",Map.of()).statusCode()).isEqualTo(404);
        assertThat(count()).isEqualTo(1);
        jdbc.execute("ALTER TABLE application_ip_attempts RENAME TO application_ip_attempts_hidden");
        try {
            var response=send("POST",path(UUID.randomUUID()),new byte[0],"application/json",Map.of());
            assertThat(response.statusCode()).isEqualTo(503);assertThat(response.body()).contains("APPLICATION_QUOTA_UNAVAILABLE")
                    .doesNotContain("application_ip_attempts", "Exception", "127.0.0.1");
        } finally { jdbc.execute("ALTER TABLE application_ip_attempts_hidden RENAME TO application_ip_attempts"); }
        assertThat(count()).isEqualTo(1);
    }
    @Test void successfulDuplicateAndReceiptRollbackEachKeepTheirAttempt() throws Exception {
        UUID posting = posting(); byte[] body = form("same@example.test");
        assertThat(send("POST",path(posting),body,"multipart/form-data; boundary=quota-test",Map.of()).statusCode()).isEqualTo(201);
        assertThat(send("POST",path(posting),body,"multipart/form-data; boundary=quota-test",Map.of()).statusCode()).isEqualTo(409);
        writer.failAfterStore=true;
        assertThat(send("POST",path(posting),form("other@example.test"),"multipart/form-data; boundary=quota-test",Map.of()).statusCode()).isEqualTo(503);
        assertThat(count()).isEqualTo(3); assertThat(jdbc.queryForObject("SELECT count(*) FROM job_applications",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_cvs",Integer.class)).isEqualTo(1);
    }
    private UUID posting() {
        Instant now=clock.instant(); UUID user=UUID.randomUUID(),department=UUID.randomUUID(),position=UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts(id,email,password_hash,full_name,enabled,created_at) VALUES (?,?,'test-only','HR',true,?)",user,user+"@example.test",Timestamp.from(now));
        jdbc.update("INSERT INTO departments(id,code,name,manager_user_id) VALUES (?,?,'IT',?)",department,department.toString(),user);
        jdbc.update("INSERT INTO positions(id,code,name,level,salary_min,salary_max,created_at,updated_at) VALUES (?,?,'Developer','Junior',0,0,?,?)",position,position.toString(),Timestamp.from(now),Timestamp.from(now));
        UUID location=catalog("WORK_LOCATION"),employment=catalog("EMPLOYMENT_TYPE"),req=UUID.randomUUID(),post=UUID.randomUUID();
        jdbc.update("INSERT INTO recruitment_requisitions(id,position_id,department_id,headcount,reason,status,created_by,created_at,updated_at) VALUES (?,?,?,1,'NEW_HEADCOUNT','APPROVED',?,?,?)",req,position,department,user,Timestamp.from(now),Timestamp.from(now));
        jdbc.update("""
                INSERT INTO job_postings(id,requisition_id,status,revision,title,job_description,candidate_requirements,
                    work_location_id,employment_type_id,application_deadline,show_salary,created_by,created_at,updated_at,published_by,published_at)
                VALUES (?,?,'PUBLISHED',1,'Developer','Description','Requirements',?,?,'2026-12-31',false,?,?,?,?,?)
                """,post,req,location,employment,user,Timestamp.from(now),Timestamp.from(now),user,Timestamp.from(now));
        return post;
    }
    private UUID catalog(String type) {
        UUID id=UUID.randomUUID();Instant now=clock.instant();
        jdbc.update("INSERT INTO recruitment_catalog_items(id,catalog_type,code,name,sort_order,active,created_at,updated_at) VALUES (?,?,?,'Visible',0,true,?,?)",id,type,id.toString(),Timestamp.from(now),Timestamp.from(now));return id;
    }
    private byte[] form(String email) throws Exception {
        var out=new ByteArrayOutputStream();
        for(var entry:Map.of("fullName","Candidate","email",email,"phone","0123456789").entrySet())out.write(("--quota-test\r\nContent-Disposition: form-data; name=\""+entry.getKey()+"\"\r\n\r\n"+entry.getValue()+"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write("--quota-test\r\nContent-Disposition: form-data; name=\"cv\"; filename=\"cv.pdf\"\r\nContent-Type: application/pdf\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        try(var document=new PDDocument()){document.addPage(new PDPage());document.save(out);}
        out.write("\r\n--quota-test--\r\n".getBytes(StandardCharsets.UTF_8)); return out.toByteArray();
    }
    private String path(UUID id){return "/api/v1/public/job-postings/"+id+"/applications";}
    private HttpResponse<String> send(String method,String path,byte[] body,String type,Map<String,String> headers) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+environment.getRequiredProperty("local.server.port")+path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type",type).method(method,HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(builder::header);return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    @TestConfiguration(proxyBeanMethods=false) static class WriterConfiguration {
        @Bean @org.springframework.context.annotation.Primary ApplicationIntakeIntegrationTest.TestWriter writer(ApplicationStorageService storage,PlatformTransactionManager manager) {
            return new ApplicationIntakeIntegrationTest.TestWriter(storage,manager);
        }
    }
}
