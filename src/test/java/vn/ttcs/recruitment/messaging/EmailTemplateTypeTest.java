package vn.ttcs.recruitment.messaging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTemplateTypeTest {
    @ParameterizedTest
    @EnumSource(EmailTemplateType.class)
    void everyTypeNamesTheCandidateAndThePositionAndOnlyInterviewTypesTheirTime(EmailTemplateType type) {
        boolean interview = switch (type) {
            case INTERVIEW_INVITATION, INTERVIEW_THANKS -> true;
            case APPLICATION_RECEIPT, REJECTION, OFFER -> false;
        };
        assertThat(type.variables()).contains(TemplateVariable.CANDIDATE_NAME, TemplateVariable.POSITION_TITLE,
                TemplateVariable.COMPANY_NAME);
        assertThat(type.needsInterview()).isEqualTo(interview);
        assertThat(type.displayName()).isNotBlank();
        assertThat(type.mandatoryContent()).isNotBlank();
        assertThat(EmailTemplateType.parse(type.name())).contains(type);
    }

    @Test
    void parseAcceptsOnlyExactNames() {
        assertThat(EmailTemplateType.parse("offer")).isEmpty();
        assertThat(EmailTemplateType.parse("CANDIDATE_SOURCE")).isEmpty();
        assertThat(EmailTemplateType.parse(null)).isEmpty();
        assertThat(EmailTemplateType.values()).hasSize(5);
    }

    @ParameterizedTest
    @EnumSource(TemplateVariable.class)
    void everyVariableIsFoundByItsPlaceholderOnly(TemplateVariable variable) {
        assertThat(TemplateVariable.byPlaceholder(variable.placeholder())).contains(variable);
        assertThat(TemplateVariable.byPlaceholder(variable.name())).isEmpty();
        assertThat(variable.label()).isNotBlank();
    }
}
