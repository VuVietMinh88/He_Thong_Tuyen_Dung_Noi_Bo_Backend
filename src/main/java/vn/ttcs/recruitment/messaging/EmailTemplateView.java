package vn.ttcs.recruitment.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Task 532: one template as the editor shows it, with the variables it may use and what the system always adds. */
public record EmailTemplateView(String type, String displayName, String subject, String body, boolean autoSend,
                                List<Variable> variables, String mandatoryContent, long version, Instant updatedAt,
                                UUID updatedBy, String updatedByName) {
    public record Variable(String name, String label) {
    }

    static EmailTemplateView of(EmailTemplate template, String updatedByName) {
        return new EmailTemplateView(template.type().name(), template.type().displayName(), template.subject(),
                template.body(), template.autoSend(), template.variables().stream()
                .map(variable -> new Variable(variable.placeholder(), variable.label())).toList(),
                template.type().mandatoryContent(), template.version(), template.updatedAt(), template.updatedBy(),
                updatedByName);
    }
}
