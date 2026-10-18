package vn.ttcs.recruitment.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import static org.assertj.core.api.Assertions.assertThat;

class MigrationResourcesTest {
    @Test
    void packagedMigrationsHaveUniqueFlywayVersionsAndKeepPostingAndApplicationChangesSeparate() throws Exception {
        var resources = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__*.sql");
        var migrations = new LinkedHashMap<MigrationVersion, List<String>>();
        for (var resource : resources) {
            String name = resource.getFilename();
            var version = MigrationVersion.fromVersion(name.substring(1, name.indexOf("__")));
            migrations.computeIfAbsent(version, ignored -> new ArrayList<>()).add(resource.getURL().toString());
        }
        assertThat(migrations).isNotEmpty();
        migrations.forEach((version, locations) -> assertThat(locations)
                .as("Migration version %s must have exactly one resource: %s", version, locations).hasSize(1));
        assertThat(migrations.get(MigrationVersion.fromVersion("26"))).singleElement()
                .asString().endsWith("V26__unpublish_job_postings.sql");
        assertThat(migrations.get(MigrationVersion.fromVersion("26.1"))).singleElement()
                .asString().endsWith("V26_1__create_job_applications_and_cvs.sql");
        assertThat(migrations.get(MigrationVersion.fromVersion("27"))).singleElement()
                .asString().endsWith("V27__expire_job_postings.sql");
    }
}
