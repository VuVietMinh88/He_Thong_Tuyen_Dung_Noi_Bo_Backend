package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.messaging.EmailTemplate;
import vn.ttcs.recruitment.messaging.EmailTemplateRepository;
import vn.ttcs.recruitment.messaging.EmailTemplateType;
import vn.ttcs.recruitment.messaging.EmailTemplates;
import vn.ttcs.recruitment.messaging.TemplateText;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Task 531: V66 stores one template per candidate email type, with its variables and its automatic-send flag. */
class EmailTemplateStorageIntegrationTest extends ScreeningTestSupport {
    @Autowired EmailTemplateRepository repository;
    @Autowired EmailTemplates templates;
    EmailTemplateTestData seeded;

    @BeforeEach void keepSeed() { seeded = new EmailTemplateTestData(jdbc); }
    @AfterEach void restoreSeed() { seeded.restore(); }

    @Test
    void migrationSeedsEveryTypeWithValidTextAndTheVariablesTheCodeSupports() {
        var stored = repository.findAll();
        assertThat(stored).extracting(EmailTemplate::type).containsExactlyInAnyOrder(EmailTemplateType.values());
        for (EmailTemplate template : stored) {
            assertThat(template.variables()).as(template.type().name()).containsExactlyElementsOf(template.type().variables());
            assertThat(TemplateText.problems(template.type(), template.subject(), template.body())).as(template.type().name()).isEmpty();
            assertThat(template.version()).isZero();
            assertThat(template.updatedBy()).isNull();
        }
        // Existing emails keep sending; the two new ones wait until HR switches them on.
        assertThat(Arrays.stream(EmailTemplateType.values()).filter(templates::autoSend))
                .containsExactlyInAnyOrder(EmailTemplateType.APPLICATION_RECEIPT, EmailTemplateType.INTERVIEW_INVITATION,
                        EmailTemplateType.OFFER);
        assertThat(templates.current(EmailTemplateType.APPLICATION_RECEIPT).subject())
                .isEqualTo("Xác nhận nhận hồ sơ ứng tuyển — Hệ thống tuyển dụng nội bộ");
    }

    @Test
    void updateStoresTextFlagEditorAndANewVersionAndAnEditorRemovalKeepsTheTemplate() {
        UUID editor = applications.account("Temp HR", "temp-hr@example.test", Role.HR_MANAGER);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(repository.lock(EmailTemplateType.REJECTION)).isPresent();
            repository.update(EmailTemplateType.REJECTION, "Kết quả {{positionTitle}}", "Chào {{candidateName}}", true,
                    editor, ApplicationTestSupport.NOW);
        });
        EmailTemplate saved = templates.current(EmailTemplateType.REJECTION);
        assertThat(saved.subject()).isEqualTo("Kết quả {{positionTitle}}");
        assertThat(saved.autoSend()).isTrue();
        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.updatedBy()).isEqualTo(editor);
        assertThat(saved.updatedAt()).isEqualTo(ApplicationTestSupport.NOW);
        assertThat(saved.render(Map.of()).subject()).isEqualTo("Kết quả");

        jdbc.update("DELETE FROM user_roles WHERE user_id = ?", editor);
        jdbc.update("DELETE FROM user_accounts WHERE id = ?", editor);
        assertThat(templates.current(EmailTemplateType.REJECTION).updatedBy()).isNull();
    }

    @Test
    void lockOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> repository.lock(EmailTemplateType.OFFER))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void databaseRefusesHeaderBreaksControlCharactersBlankTextAndUnknownTypes() {
        for (String sql : new String[] {
                "UPDATE email_templates SET subject = E'Thư\\r\\nBcc: x@evil.test' WHERE template_type = 'OFFER'",
                "UPDATE email_templates SET subject = '   ' WHERE template_type = 'OFFER'",
                "UPDATE email_templates SET body = E'Nội dung\\rkhác' WHERE template_type = 'OFFER'",
                "UPDATE email_templates SET version = -1 WHERE template_type = 'OFFER'",
                "UPDATE email_templates SET supported_variables = ARRAY[]::varchar[] WHERE template_type = 'OFFER'",
                "INSERT INTO email_templates(template_type,subject,body,supported_variables,auto_send,updated_at)"
                        + " VALUES ('WELCOME','A','B',ARRAY['candidateName'],true,now())"}) {
            assertThatThrownBy(() -> jdbc.update(sql)).as(sql).isInstanceOf(DataIntegrityViolationException.class);
        }
        // Line breaks and tabs are the body's only control characters.
        assertThat(jdbc.update("UPDATE email_templates SET body = E'Dòng 1\\nDòng 2\\tcột' WHERE template_type = 'OFFER'"))
                .isEqualTo(1);
    }
}
