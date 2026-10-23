package vn.ttcs.recruitment.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 531: reads and writes email_templates (V66). A read outside a transaction is one autocommit SELECT, which is
 * what the mail workers need at send time; writes join the caller's transaction.
 */
@Repository
public class EmailTemplateRepository {
    private static final String COLUMNS =
            "template_type, subject, body, supported_variables, auto_send, version, updated_by, updated_at";
    private final JdbcTemplate jdbc;
    private final RowMapper<EmailTemplate> mapper = (row, number) -> new EmailTemplate(
            EmailTemplateType.valueOf(row.getString("template_type")), row.getString("subject"), row.getString("body"),
            variables(row.getArray("supported_variables")), row.getBoolean("auto_send"), row.getLong("version"),
            row.getObject("updated_by", UUID.class), row.getTimestamp("updated_at").toInstant());

    public EmailTemplateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<EmailTemplate> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM email_templates ORDER BY template_type", mapper);
    }

    public Optional<EmailTemplate> find(EmailTemplateType type) {
        return jdbc.query("SELECT " + COLUMNS + " FROM email_templates WHERE template_type = ?", mapper, type.name())
                .stream().findFirst();
    }

    /** The template locked FOR UPDATE until the caller's transaction ends: concurrent edits run one after the other. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<EmailTemplate> lock(EmailTemplateType type) {
        return jdbc.query("SELECT " + COLUMNS + " FROM email_templates WHERE template_type = ? FOR UPDATE", mapper,
                type.name()).stream().findFirst();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void update(EmailTemplateType type, String subject, String body, boolean autoSend, UUID actor, Instant at) {
        int changed = jdbc.update("""
                UPDATE email_templates SET subject = ?, body = ?, auto_send = ?, version = version + 1,
                    updated_by = ?, updated_at = ?
                WHERE template_type = ?
                """, subject, body, autoSend, actor, Timestamp.from(at), type.name());
        if (changed != 1) throw new IllegalStateException("Email template row is missing");
    }

    private static List<TemplateVariable> variables(Array array) throws SQLException {
        List<TemplateVariable> variables = new ArrayList<>();
        for (Object name : (Object[]) array.getArray()) {
            variables.add(TemplateVariable.byPlaceholder(String.valueOf(name))
                    .orElseThrow(() -> new IllegalStateException("Unknown variable stored for an email template")));
        }
        return variables;
    }
}
