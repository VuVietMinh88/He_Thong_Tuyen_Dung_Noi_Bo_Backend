package vn.ttcs.recruitment.config;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationHistoryIntegrityTest {
    @Test
    void allHistoricalScriptsKeepTheBytesPinnedFromBothOriginalCommits() throws Exception {
        String manifest = new String(read("migration-history.sha256"), StandardCharsets.UTF_8);
        int checked = 0;
        for (String line : manifest.lines().filter(value -> !value.startsWith("#") && !value.isBlank()).toList()) {
            String[] fields = line.split(" ", 2);
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(read(fields[1])));
            assertThat(actual).as(fields[1]).isEqualTo(fields[0]);
            checked++;
        }
        assertThat(checked).isEqualTo(71);
    }

    @Test
    void bridgesReuseOriginalSqlExactlyAndKeepTheirDependencyOrder() throws Exception {
        Map<String, String> s5 = Map.of(
                "V42__unpublish_job_postings.sql", "V26__unpublish_job_postings.sql",
                "V43__expire_job_postings.sql", "V27__expire_job_postings.sql",
                "V44__add_unique_account_phone.sql", "V38__add_unique_account_phone.sql",
                "V45__track_password_reset_requests_by_browser.sql", "V39__track_password_reset_requests_by_browser.sql");
        for (var bridge : s5.entrySet()) {
            assertThat(read("db/migration/" + bridge.getKey())).as(bridge.getKey())
                    .isEqualTo(read("db/migration-feedback/" + bridge.getValue()));
        }
        Map<String, String> feedback = Map.ofEntries(
                Map.entry("V42__create_job_applications_and_cvs.sql", "V26__create_job_applications_and_cvs.sql"),
                Map.entry("V43__create_application_ip_quotas.sql", "V27__create_application_ip_quotas.sql"),
                Map.entry("V44__create_application_email_outbox.sql", "V28__create_application_email_outbox.sql"),
                Map.entry("V45__create_application_tracking_ip_quotas.sql", "V31__create_application_tracking_ip_quotas.sql"),
                Map.entry("V46__create_candidates.sql", "V32__create_candidates.sql"),
                Map.entry("V47__create_candidate_access_events.sql", "V33__create_candidate_access_events.sql"),
                Map.entry("V48__scope_approver_candidate_read.sql", "V33_1__scope_approver_candidate_read.sql"),
                Map.entry("V49__create_application_stage_events.sql", "V34__create_application_stage_events.sql"),
                Map.entry("V50__create_application_notice_outbox.sql", "V35__create_application_notice_outbox.sql"),
                Map.entry("V51__create_application_referrals.sql", "V36__create_application_referrals.sql"),
                Map.entry("V52__create_referral_attempts.sql", "V37__create_referral_attempts.sql"),
                Map.entry("V53__add_staff_stage_change_details.sql", "V40__add_staff_stage_change_details.sql"),
                Map.entry("V54__create_application_screening.sql", "V41__create_application_screening.sql"));
        for (var bridge : feedback.entrySet()) {
            assertThat(read("db/migration-feedback/" + bridge.getKey())).as(bridge.getKey())
                    .isEqualTo(read("db/migration/" + bridge.getValue()));
        }
    }

    @Test
    void sharedFutureVersionsDoNotReuseAnyLegacyOrBridgeNumber() throws Exception {
        for (var resource : new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration-shared/V*__*.sql")) {
            String filename = resource.getFilename();
            String version = filename.substring(1, filename.indexOf("__"));
            assertThat(MigrationVersion.fromVersion(version)).as(filename)
                    .isGreaterThanOrEqualTo(MigrationVersion.fromVersion("55"));
        }
    }

    private static byte[] read(String path) throws Exception {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return input.readAllBytes();
        }
    }
}
