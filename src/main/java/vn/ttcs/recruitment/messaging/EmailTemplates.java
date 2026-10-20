package vn.ttcs.recruitment.messaging;

import org.springframework.stereotype.Component;

/**
 * Task 531: the current template of a type, for the code that sends or decides to send an email. Missing rows are a
 * deployment error (V66 seeds all five), never a reason to send an email without its configured text.
 */
@Component
public class EmailTemplates {
    private final EmailTemplateRepository repository;

    public EmailTemplates(EmailTemplateRepository repository) {
        this.repository = repository;
    }

    public EmailTemplate current(EmailTemplateType type) {
        return repository.find(type).orElseThrow(() -> new IllegalStateException("Email template " + type + " is missing"));
    }

    /** Whether the business event of this type sends its email automatically (read in the caller's transaction). */
    public boolean autoSend(EmailTemplateType type) {
        return current(type).autoSend();
    }
}
