package vn.ttcs.recruitment.auth;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.postgresql.ds.PGSimpleDataSource;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.UUID;

// Opt-in only. Never accepts the development database or a remote server; each Spring context gets a fresh schema.
final class DockerTestDataSource {
    private DockerTestDataSource() { }

    static DataSource create(String url) throws SQLException {
        if (!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/task325_test")) {
            throw new IllegalArgumentException("Docker tests require an isolated localhost task325_test database");
        }
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(url);
        dataSource.setUser("postgres");
        dataSource.setPassword("task325-test-only");
        String schema = "task325_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = dataSource.getConnection(); var command = connection.createStatement()) {
            command.execute("CREATE SCHEMA " + schema);
        }
        dataSource.setCurrentSchema(schema);
        // Reuse loopback sockets during the complete suite on Windows. A raw PGSimpleDataSource opens a new
        // connection for every repository operation and can hit transient local socket binding failures.
        var pool = new HikariConfig();
        pool.setDataSource(dataSource);
        // Restore this schema when an upgrade test temporarily changes a borrowed connection's schema.
        pool.setSchema(schema);
        pool.setPoolName(schema);
        pool.setMaximumPoolSize(10);
        pool.setMinimumIdle(0);
        pool.setIdleTimeout(10_000);
        pool.setConnectionTimeout(10_000);
        return new HikariDataSource(pool);
    }
}
