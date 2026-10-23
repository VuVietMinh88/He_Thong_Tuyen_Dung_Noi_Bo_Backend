package vn.ttcs.recruitment.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Task 531/533: the current template of a type, for the code that sends or decides to send an email. Missing rows are
 * a deployment error (V66 seeds all five), never a reason to send an email without its configured text. render adds
 * the company name (app.mail.company-name, the name of the email footer) to the caller's values.
 */
@Component
public class EmailTemplates {
    private final EmailTemplateRepository repository;
    private final String companyName;

    public EmailTemplates(EmailTemplateRepository repository, @Value("${app.mail.company-name}") String companyName) {
        this.repository = repository;
        this.companyName = companyName;
    }

    public EmailTemplate current(EmailTemplateType type) {
        return repository.find(type).orElseThrow(() -> new IllegalStateException("Email template " + type + " is missing"));
    }

    /** Whether the business event of this type sends its email automatically (read in the caller's transaction). */
    public boolean autoSend(EmailTemplateType type) {
        return current(type).autoSend();
    }

    /** The current template of the type filled in with these values and the company name. */
    public EmailTemplate.Rendered render(EmailTemplateType type, Map<TemplateVariable, String> values) {
        Map<TemplateVariable, String> all = new EnumMap<>(TemplateVariable.class);
        all.putAll(values);
        all.put(TemplateVariable.COMPANY_NAME, companyName);
        return current(type).render(all);
    }
}
