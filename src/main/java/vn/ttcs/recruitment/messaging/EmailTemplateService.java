package vn.ttcs.recruitment.messaging;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import vn.ttcs.recruitment.common.ApiException;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 532 (story S8-01): reading and saving the templates. Reading needs NOTIFICATIONS_READ_ALL (template settings
 * are company-wide, not a scoped record); saving also needs NOTIFICATIONS_WRITE_ALL. Both are read again from the
 * database here, the save after locking the caller's account and session and again after locking the template row.
 * <p>
 * A save is a full replacement of subject, body and automatic-send flag guarded by expectedVersion (409
 * EMAIL_TEMPLATE_VERSION_CONFLICT when another save came first). The text must pass TemplateText (400
 * EMAIL_TEMPLATE_INVALID with the field in fieldErrors): an unknown {{variable}} is refused, never stored.
 */
@Service
public class EmailTemplateService {
    static final String READ = "NOTIFICATIONS_READ_ALL";
    static final String WRITE = "NOTIFICATIONS_WRITE_ALL";
    private final EmailTemplateRepository templates;
    private final MessagingActors actors;
    private final MessagingTransactions transactions;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public EmailTemplateService(EmailTemplateRepository templates, MessagingActors actors,
                                MessagingTransactions transactions, JdbcTemplate jdbc, Clock clock) {
        this.templates = templates;
        this.actors = actors;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public List<EmailTemplateView> list(Jwt jwt) {
        actors.requireAll(actors.reader(jwt).getId(), READ);
        return templates.findAll().stream().map(this::view).toList();
    }

    public EmailTemplateView get(Jwt jwt, String type) {
        actors.requireAll(actors.reader(jwt).getId(), READ);
        EmailTemplateType parsed = type(type);
        return view(templates.find(parsed).orElseThrow(EmailTemplateService::notFound));
    }

    public EmailTemplateView update(Jwt jwt, String type, EmailTemplateRequests.Update request) {
        return transactions.write("EMAIL_TEMPLATE_BUSY", () -> {
            var actor = actors.lockWriter(jwt);
            actors.requireAll(actor.id(), READ, WRITE);
            EmailTemplateType parsed = type(type);
            Map<String, String> problems = TemplateText.problems(parsed, request.subject(), request.body());
            if (!problems.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "EMAIL_TEMPLATE_INVALID",
                        "Mẫu email chưa hợp lệ. Vui lòng kiểm tra tiêu đề và nội dung.", problems);
            }
            EmailTemplate current = templates.lock(parsed).orElseThrow(EmailTemplateService::notFound);
            // The template lock may have waited for another editor: the caller is checked again afterwards.
            actors.requireFresh(jwt, actor);
            actors.requireAll(actor.id(), READ, WRITE);
            if (current.version() != request.expectedVersion()) {
                throw new ApiException(HttpStatus.CONFLICT, "EMAIL_TEMPLATE_VERSION_CONFLICT",
                        "Mẫu email vừa được người khác cập nhật. Vui lòng tải lại trước khi lưu.");
            }
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            templates.update(parsed, request.subject(), request.body(), request.autoSend(), actor.id(), now);
            return view(templates.find(parsed).orElseThrow());
        });
    }

    static EmailTemplateType type(String value) {
        return EmailTemplateType.parse(value).orElseThrow(EmailTemplateService::notFound);
    }

    private EmailTemplateView view(EmailTemplate template) {
        String name = template.updatedBy() == null ? null : jdbc.query(
                "SELECT full_name FROM user_accounts WHERE id = ?", (row, number) -> row.getString(1),
                template.updatedBy()).stream().findFirst().orElse(null);
        return EmailTemplateView.of(template, name);
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "EMAIL_TEMPLATE_NOT_FOUND", "Không tìm thấy loại mẫu email.");
    }

    // Exposed for the preview, which shares the same lookup rules.
    static UUID requireReader(MessagingActors actors, Jwt jwt) {
        UUID reader = actors.reader(jwt).getId();
        actors.requireAll(reader, READ);
        return reader;
    }
}
