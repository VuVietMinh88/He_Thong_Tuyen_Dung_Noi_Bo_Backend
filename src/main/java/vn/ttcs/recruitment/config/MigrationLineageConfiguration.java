package vn.ttcs.recruitment.config;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Configuration;

/**
 * Both local histories used V26/V27 for different features. Keep their old scripts unchanged, select exactly one
 * history, and append its missing features above V41. Both paths end with the same business schema and application.
 * This class never inspects or rewrites history: Flyway validates every applied script before running any bridge.
 */
@Configuration(proxyBeanMethods = false)
public class MigrationLineageConfiguration implements FlywayConfigurationCustomizer {
    private final String legacyLocation;

    public MigrationLineageConfiguration(@Value("${app.migration.lineage:s5}") String lineage) {
        legacyLocation = switch (lineage) {
            case "s5" -> "classpath:db/migration";
            case "feedback" -> "classpath:db/migration-feedback";
            default -> throw new IllegalArgumentException(
                    "APP_MIGRATION_LINEAGE must be s5 or feedback; choose the existing V26/V27 history. "
                            + "See docs/database/migration-lineages.md");
        };
    }

    @Override
    public void customize(FluentConfiguration configuration) {
        configuration.locations(legacyLocation, "classpath:db/migration-shared")
                .failOnMissingLocations(true)
                .validateMigrationNaming(true)
                .validateOnMigrate(true)
                .outOfOrder(false)
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                // Even an unknown future migration requires investigation instead of silently accepting that DB.
                .ignoreMigrationPatterns(new String[0]);
    }
}
