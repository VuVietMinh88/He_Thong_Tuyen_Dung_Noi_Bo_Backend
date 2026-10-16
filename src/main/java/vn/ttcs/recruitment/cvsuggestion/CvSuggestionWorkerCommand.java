package vn.ttcs.recruitment.cvsuggestion;

import java.nio.file.Path;
import java.util.Properties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Start with PropertiesLauncher and loader.main; credentials only via worker-specific environment. */
public final class CvSuggestionWorkerCommand {
    private CvSuggestionWorkerCommand() { }
    public static void main(String[] args) {
        try {
            // PostgreSQL installations need not ship every legacy Windows/JVM zone alias (e.g. Asia/Saigon).
            // This standalone process stores instants; business date rules remain in the web application's zone.
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
            var source=new DriverManagerDataSource(required("CV_WORKER_DB_URL"),required("CV_WORKER_DB_USER"),required("CV_WORKER_DB_PASSWORD"));
            var properties=new Properties();
            properties.setProperty("connectTimeout","5");properties.setProperty("socketTimeout","10");
            source.setConnectionProperties(properties);
            var jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(5);
            var tx=new TransactionTemplate(new DataSourceTransactionManager(source));tx.setTimeout(5);
            var sandbox=new CvSandboxProcess(required("CV_WORKER_PYTHON"),Path.of(required("CV_WORKER_RUNNER")),
                    required("CV_WORKER_IMAGE"),Path.of(required("CV_WORKER_TEMP")));
            var worker=new CvSuggestionWorker(new CvSuggestionRepository(jdbc),tx,sandbox::extract,
                    Integer.parseInt(System.getenv().getOrDefault("CV_WORKER_MAX_RUNNING","1")));
            // Verifies schema before claiming anything; Flyway belongs to the web deployment step.
            jdbc.queryForObject("SELECT count(*) FROM cv_extraction_jobs WHERE false",Integer.class);
            if ("true".equals(System.getenv("CV_WORKER_ONCE"))) {
                sandbox.cleanupAbandoned();worker.processNext();return;
            }
            do {
                try { sandbox.cleanupAbandoned();if (!worker.processNext()) Thread.sleep(2000); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt();break; }
                catch (Exception exception) { System.err.println("CV worker temporarily unavailable");Thread.sleep(2000); }
            } while (!Thread.currentThread().isInterrupted());
        } catch (Exception exception) { System.err.println("CV worker stopped; verify configuration and dependencies");System.exit(1); }
    }
    private static String required(String name) {
        String value=System.getenv(name);
        if (value==null || value.isBlank()) throw new IllegalArgumentException("Missing worker configuration");
        return value;
    }
}
