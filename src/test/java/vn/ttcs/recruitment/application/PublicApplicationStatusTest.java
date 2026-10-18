package vn.ttcs.recruitment.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.application.ApplicationStage.*;

// Task 340 (story S4-05): a candidate sees one of the four general statuses of the story, never an internal stage.
// A new ApplicationStage makes the switch below fail to compile, like the one in PublicApplicationStatus.of, until
// someone decides which public status shows it.
class PublicApplicationStatusTest {

    @ParameterizedTest
    @EnumSource(ApplicationStage.class)
    void everyStageShowsTheDecidedPublicStatus(ApplicationStage stage) {
        PublicApplicationStatus expected = switch (stage) {
            case NEW -> PublicApplicationStatus.RECEIVED;
            case SCREENING -> PublicApplicationStatus.IN_REVIEW;
            case INTERVIEW, OFFER -> PublicApplicationStatus.INTERVIEWING;
            case HIRED, REJECTED, WITHDRAWN -> PublicApplicationStatus.COMPLETED;
        };

        assertThat(PublicApplicationStatus.of(stage)).isEqualTo(expected);
    }

    // The story names exactly these: "Đã nhận, Đang xem xét, Mời phỏng vấn, Kết thúc", in the order a candidate
    // goes through them (thePublicStepNeverGoesBack relies on that order). The codes are the frontend's referral codes.
    @Test
    void thereAreExactlyTheFourStatusesOfTheStoryInTheirOrder() {
        assertThat(Arrays.stream(PublicApplicationStatus.values())
                .map(status -> status.name() + " " + status.displayName()).toList())
                .containsExactly("RECEIVED Đã nhận", "IN_REVIEW Đang xem xét", "INTERVIEWING Mời phỏng vấn",
                        "COMPLETED Kết thúc");
    }

    // The intake's 201 answers this for the NEW application it stores; ApplicationIntakeIntegrationTest pins the JSON.
    @Test
    void aNewApplicationIsReceived() {
        PublicApplicationStatus status = PublicApplicationStatus.of(NEW);

        assertThat(status.name()).isEqualTo("RECEIVED");
        assertThat(status.displayName()).isEqualTo("Đã nhận");
    }

    @ParameterizedTest
    @EnumSource(ApplicationStage.class)
    void onlyAFinalStageIsCompleted(ApplicationStage stage) {
        assertThat(PublicApplicationStatus.of(stage) == PublicApplicationStatus.COMPLETED).isEqualTo(!stage.active());
    }

    // A candidate goes NEW, SCREENING, INTERVIEW, OFFER and may leave the pipeline from any of them. The public step
    // never goes back on the way, and reaching a final stage always shows a step forward.
    @Test
    void thePublicStepNeverGoesBack() {
        List<ApplicationStage> pipeline = List.of(NEW, SCREENING, INTERVIEW, OFFER);
        for (int i = 1; i < pipeline.size(); i++) {
            assertThat(step(pipeline.get(i))).as(pipeline.get(i - 1) + " -> " + pipeline.get(i))
                    .isGreaterThanOrEqualTo(step(pipeline.get(i - 1)));
        }
        for (ApplicationStage from : pipeline) {
            for (ApplicationStage outcome : List.of(HIRED, REJECTED, WITHDRAWN)) {
                assertThat(step(outcome)).as(from + " -> " + outcome).isGreaterThan(step(from));
            }
        }
    }

    // ApplicationStage and its display names are the recruiting team's words; no public code or label is one of them.
    @Test
    void noPublicCodeOrLabelIsAnInternalOne() {
        for (PublicApplicationStatus status : PublicApplicationStatus.values()) {
            for (ApplicationStage stage : ApplicationStage.values()) {
                assertThat(status.name()).as(status + " / " + stage).isNotEqualToIgnoringCase(stage.name());
                assertThat(status.displayName()).as(status + " / " + stage)
                        .isNotEqualToIgnoringCase(stage.displayName());
            }
        }
    }

    private static int step(ApplicationStage stage) {
        return PublicApplicationStatus.of(stage).ordinal();
    }
}
