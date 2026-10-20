package vn.ttcs.recruitment.messaging;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 531: one stored template (a row of email_templates). render fills it in for one email; the caller adds the
 * mandatory content of its type after the rendered paragraphs.
 */
public record EmailTemplate(EmailTemplateType type, String subject, String body, List<TemplateVariable> variables,
                            boolean autoSend, long version, UUID updatedBy, Instant updatedAt) {
    public EmailTemplate {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(body, "body");
        variables = List.copyOf(variables);
    }

    public Rendered render(Map<TemplateVariable, String> values) {
        return new Rendered(type, version, TemplateText.renderSubject(subject, values),
                TemplateText.renderBody(body, values));
    }

    /** A filled-in template: the subject line and the paragraphs of the customizable part of the body. */
    public record Rendered(EmailTemplateType type, long version, String subject, List<String> paragraphs) {
        public Rendered {
            paragraphs = List.copyOf(paragraphs);
        }

        @Override
        public String toString() {
            // The text contains the candidate's name: logs only learn which template version was used.
            return "RenderedTemplate[" + type + " v" + version + "]";
        }
    }
}
