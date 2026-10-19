package vn.ttcs.recruitment.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class FlywayDuplicateVersionGuardTest {
    @Test
    void classpathHasUniqueFlywayVersionsAndExpectedPostingApplicationMigrations() throws IOException {
        var resources = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__*.sql");
        assertThat(resources).as("Packaged Flyway SQL resources").isNotEmpty();
        Map<MigrationVersion, List<String>> versions = new TreeMap<>();
        for (var resource : resources) {
            String filename = resource.getFilename();
            assertThat(filename).isNotNull();
            var version = MigrationVersion.fromVersion(filename.substring(1, filename.indexOf("__")));
            versions.computeIfAbsent(version, ignored -> new ArrayList<>()).add(filename);
        }
        versions.forEach((version, files) -> assertThat(files)
                .as("Flyway version %s must have exactly one resource: %s", version, files).hasSize(1));
        assertThat(versions.get(MigrationVersion.fromVersion("26")))
                .containsExactly("V26__unpublish_job_postings.sql");
        assertThat(versions.get(MigrationVersion.fromVersion("26.1")))
                .containsExactly("V26_1__create_job_applications_and_cvs.sql");
        assertThat(versions.get(MigrationVersion.fromVersion("27")))
                .containsExactly("V27__expire_job_postings.sql");
        assertThat(versions.get(MigrationVersion.fromVersion("27.1")))
                .containsExactly("V27_1__create_application_ip_quotas.sql");
        assertThat(versions.get(MigrationVersion.fromVersion("28")))
                .containsExactly("V28__create_application_email_outbox.sql");
    }
}
