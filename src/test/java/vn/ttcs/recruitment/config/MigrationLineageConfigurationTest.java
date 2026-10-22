package vn.ttcs.recruitment.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationLineageConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(MigrationLineageConfiguration.class);

    @ParameterizedTest
    @CsvSource({"s5,classpath:db/migration", "feedback,classpath:db/migration-feedback"})
    void springBindsTheChosenLineageAndIncludesFutureSharedMigrations(String lineage, String location) {
        context.withPropertyValues("app.migration.lineage=" + lineage).run(application -> {
            assertThat(application).hasNotFailed();
            var configuration = Flyway.configure();
            application.getBean(FlywayConfigurationCustomizer.class).customize(configuration);
            assertThat(configuration.getLocations()).extracting(Location::getDescriptor)
                    .containsExactly(location, "classpath:db/migration-shared");
            assertThat(configuration.isOutOfOrder()).isFalse();
            assertThat(configuration.isBaselineOnMigrate()).isFalse();
            assertThat(configuration.isCleanDisabled()).isTrue();
            assertThat(configuration.isValidateOnMigrate()).isTrue();
            assertThat(configuration.getIgnoreMigrationPatterns()).isEmpty();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "unknown", "S5", "s5,feedback", "../migration"})
    void unknownLineageFailsStartupInsteadOfFallingBackToAnotherHistory(String lineage) {
        context.withPropertyValues("app.migration.lineage=" + lineage).run(application ->
                assertThat(application).hasFailed().getFailure()
                        .hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .hasRootCauseMessage("APP_MIGRATION_LINEAGE must be s5 or feedback; choose the existing "
                                + "V26/V27 history. See docs/database/migration-lineages.md"));
    }
}
