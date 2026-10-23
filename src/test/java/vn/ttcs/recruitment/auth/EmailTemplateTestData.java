package vn.ttcs.recruitment.auth;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * Messaging tests change the shared email_templates rows (texts, automatic-send flags); this keeps the rows as V66
 * seeded them before the first test and puts them back after each one, so test order never matters.
 */
final class EmailTemplateTestData {
    private final JdbcTemplate jdbc;
    private final List<Map<String, Object>> rows;

    EmailTemplateTestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.rows = jdbc.queryForList("SELECT template_type, subject, body, auto_send, version FROM email_templates");
    }

    void restore() {
        jdbc.update("UPDATE email_templates SET updated_by = NULL");
        for (Map<String, Object> row : rows) {
            jdbc.update("UPDATE email_templates SET subject = ?, body = ?, auto_send = ?, version = ? WHERE template_type = ?",
                    row.get("subject"), row.get("body"), row.get("auto_send"), row.get("version"), row.get("template_type"));
        }
    }

    void autoSend(String type, boolean enabled) {
        jdbc.update("UPDATE email_templates SET auto_send = ? WHERE template_type = ?", enabled, type);
    }
}
