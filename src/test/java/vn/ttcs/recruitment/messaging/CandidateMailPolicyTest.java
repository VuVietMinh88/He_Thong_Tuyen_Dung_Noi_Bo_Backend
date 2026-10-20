package vn.ttcs.recruitment.messaging;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateMailPolicyTest {
    private static final Instant START = Instant.parse("2026-10-20T02:00:00Z");

    @ParameterizedTest
    @EnumSource(EmailTemplateType.class)
    void onlyTemplateOnlyEmailsInTheirValidStateMayGoOut(EmailTemplateType type) {
        String valid = switch (type) {
            case REJECTION -> reason(type, "REJECTED", UUID.randomUUID(), START, "a@example.test");
            case INTERVIEW_THANKS -> reason(type, "INTERVIEW", UUID.randomUUID(), START, "a@example.test");
            case APPLICATION_RECEIPT, INTERVIEW_INVITATION, OFFER -> "never in this queue";
        };
        if (type == EmailTemplateType.REJECTION || type == EmailTemplateType.INTERVIEW_THANKS) assertThat(valid).isNull();
        else assertThat(reason(type, "REJECTED", UUID.randomUUID(), START, "a@example.test")).isEqualTo("OBSOLETE");
    }

    @ParameterizedTest
    @EnumSource(value = EmailTemplateType.class, names = {"REJECTION", "INTERVIEW_THANKS"})
    void aChangedAddressIsNeverMailed(EmailTemplateType type) {
        String stage = type == EmailTemplateType.REJECTION ? "REJECTED" : "OFFER";
        assertThat(reason(type, stage, UUID.randomUUID(), START, "new@example.test")).isEqualTo("RECIPIENT_UNAVAILABLE");
    }

    @org.junit.jupiter.api.Test
    void aLetterForAnApplicationNoLongerRejectedOrAThankYouAfterAFinalStageOrWithoutInterviewIsObsolete() {
        for (String stage : new String[] {"NEW", "SCREENING", "INTERVIEW", "OFFER", "HIRED", "ONBOARDED", "WITHDRAWN"}) {
            assertThat(reason(EmailTemplateType.REJECTION, stage, null, null, "a@example.test")).as(stage).isEqualTo("OBSOLETE");
        }
        // A thank-you never follows a final decision or a withdrawal.
        for (String stage : new String[] {"REJECTED", "HIRED", "ONBOARDED", "WITHDRAWN"}) {
            assertThat(reason(EmailTemplateType.INTERVIEW_THANKS, stage, UUID.randomUUID(), START, "a@example.test"))
                    .as(stage).isEqualTo("OBSOLETE");
        }
        for (String stage : new String[] {"NEW", "SCREENING", "INTERVIEW", "OFFER"}) {
            assertThat(reason(EmailTemplateType.INTERVIEW_THANKS, stage, UUID.randomUUID(), START, "a@example.test"))
                    .as(stage).isNull();
        }
        assertThat(reason(EmailTemplateType.INTERVIEW_THANKS, "INTERVIEW", null, null, "a@example.test")).isEqualTo("OBSOLETE");
    }

    private static String reason(EmailTemplateType type, String stage, UUID interview, Instant start, String current) {
        return CandidateMailPolicy.unavailableReason(new CandidateMailOutboxRepository.Content(type, "a@example.test",
                current, "Nguyễn Văn A", "Java", stage, interview, start));
    }
}
