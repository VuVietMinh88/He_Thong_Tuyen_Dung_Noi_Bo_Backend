package vn.ttcs.recruitment.jobposting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// Task 308: what each review decision does. A new JobPostingStatus makes the switches fail to compile until someone
// decides whether each decision may be taken in it (JobPostingDecision.from).
class JobPostingDecisionTest {

    @Test
    void theCodesOfTheRequestAreExactlyTheDecisions() {
        assertThat(JobPostingDecision.CODES).isEqualTo(Arrays.stream(JobPostingDecision.values())
                .map(Enum::name).collect(Collectors.joining("|")));
        assertThat("APPROVE").matches(JobPostingDecision.CODES);
        assertThat("REQUEST_REVISION").matches(JobPostingDecision.CODES);
        assertThat("REJECT").doesNotMatch(JobPostingDecision.CODES);
        assertThat("approve").doesNotMatch(JobPostingDecision.CODES);
    }

    @Test
    void eachDecisionLeadsToItsStatusAndEvent() {
        assertThat(JobPostingDecision.APPROVE.to()).isEqualTo(JobPostingStatus.APPROVED);
        assertThat(JobPostingDecision.APPROVE.eventType()).isEqualTo(JobPostingEventType.APPROVED);
        assertThat(JobPostingDecision.APPROVE.requiresComment()).isFalse();
        assertThat(JobPostingDecision.REQUEST_REVISION.to()).isEqualTo(JobPostingStatus.REVISION_REQUESTED);
        assertThat(JobPostingDecision.REQUEST_REVISION.eventType()).isEqualTo(JobPostingEventType.REVISION_REQUESTED);
        assertThat(JobPostingDecision.REQUEST_REVISION.requiresComment()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(JobPostingStatus.class)
    void approvingIsOnlyForPostingsWaitingForReview(JobPostingStatus status) {
        boolean allowed = switch (status) {
            case PENDING_APPROVAL -> true;
            case DRAFT, REVISION_REQUESTED, APPROVED, PUBLISHED, CLOSED -> false;
        };

        assertThat(JobPostingDecision.APPROVE.from().contains(status)).isEqualTo(allowed);
    }

    @ParameterizedTest
    @EnumSource(JobPostingStatus.class)
    void changesMayBeAskedForUntilThePostingIsPublished(JobPostingStatus status) {
        boolean allowed = switch (status) {
            case PENDING_APPROVAL, APPROVED -> true;
            case DRAFT, REVISION_REQUESTED, PUBLISHED, CLOSED -> false;
        };

        assertThat(JobPostingDecision.REQUEST_REVISION.from().contains(status)).isEqualTo(allowed);
    }

    @Test
    void theStatusesOfADecisionCannotBeChangedFromOutside() {
        JobPostingDecision.APPROVE.from().add(JobPostingStatus.DRAFT);

        assertThat(JobPostingDecision.APPROVE.from()).containsExactly(JobPostingStatus.PENDING_APPROVAL);
    }
}
