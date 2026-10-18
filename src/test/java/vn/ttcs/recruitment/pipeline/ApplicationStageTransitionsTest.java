package vn.ttcs.recruitment.pipeline;

import org.junit.jupiter.api.Test;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.pipeline.ApplicationStageTransitions.Decision;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.application.ApplicationStage.HIRED;
import static vn.ttcs.recruitment.application.ApplicationStage.INTERVIEW;
import static vn.ttcs.recruitment.application.ApplicationStage.NEW;
import static vn.ttcs.recruitment.application.ApplicationStage.OFFER;
import static vn.ttcs.recruitment.application.ApplicationStage.REJECTED;
import static vn.ttcs.recruitment.application.ApplicationStage.SCREENING;
import static vn.ttcs.recruitment.application.ApplicationStage.WITHDRAWN;
import static vn.ttcs.recruitment.pipeline.ApplicationStageTransitions.Decision.ALLOWED;
import static vn.ttcs.recruitment.pipeline.ApplicationStageTransitions.Decision.INTERVIEW_REQUIRED;
import static vn.ttcs.recruitment.pipeline.ApplicationStageTransitions.Decision.INTERVIEW_SKIPPED;
import static vn.ttcs.recruitment.pipeline.ApplicationStageTransitions.Decision.INTERVIEW_SKIP_UNCONFIRMED;

/**
 * Tasks 380/381 (story S5-03): the whole 7 × 7 matrix of staff stage moves for the four callers that matter (an
 * HR_MANAGER or anyone else, confirming the interview skip or not), written out by hand so a changed rule fails here
 * rather than slipping through. Every pair not listed is NOT_ALLOWED.
 */
class ApplicationStageTransitionsTest {
    // The decision that differs from NOT_ALLOWED, for each caller: [hrManager][confirms].
    private static final Map<String, Decision[][]> EXPECTED = Map.ofEntries(
            entry(NEW, SCREENING, ALLOWED),
            entry(NEW, REJECTED, ALLOWED),
            entry(SCREENING, INTERVIEW, ALLOWED),
            entry(SCREENING, REJECTED, ALLOWED),
            // The story's exception: only a confirming HR_MANAGER goes straight to OFFER.
            Map.entry(key(SCREENING, OFFER), new Decision[][] {
                    {INTERVIEW_REQUIRED, INTERVIEW_REQUIRED}, {INTERVIEW_SKIP_UNCONFIRMED, INTERVIEW_SKIPPED}}),
            entry(INTERVIEW, OFFER, ALLOWED),
            entry(INTERVIEW, REJECTED, ALLOWED),
            entry(OFFER, HIRED, ALLOWED),
            entry(OFFER, REJECTED, ALLOWED));

    @Test
    void everyMoveOfEveryCallerIsDecidedAsTheStorySays() {
        for (ApplicationStage from : ApplicationStage.values()) {
            for (ApplicationStage to : ApplicationStage.values()) {
                Decision[][] expected = EXPECTED.get(key(from, to));
                for (int hr = 0; hr < 2; hr++) {
                    for (int confirms = 0; confirms < 2; confirms++) {
                        assertThat(ApplicationStageTransitions.decide(from, to, hr == 1, confirms == 1))
                                .as("%s -> %s, hrManager=%s, confirms=%s", from, to, hr == 1, confirms == 1)
                                .isEqualTo(expected == null ? Decision.NOT_ALLOWED : expected[hr][confirms]);
                    }
                }
            }
        }
    }

    // Final in this version: nothing reopens a hired, rejected or withdrawn application, not even a confirming HR.
    @Test
    void finalStagesLeadNowhere() {
        for (ApplicationStage end : new ApplicationStage[] {HIRED, REJECTED, WITHDRAWN}) {
            assertThat(ApplicationStageTransitions.next(end)).isNull();
            for (ApplicationStage to : ApplicationStage.values()) {
                assertThat(ApplicationStageTransitions.decide(end, to, true, true)).isEqualTo(Decision.NOT_ALLOWED);
            }
        }
    }

    // The forward path in the order of the story.
    @Test
    void theForwardPathIsTheStoryOrder() {
        Map<ApplicationStage, ApplicationStage> next = new EnumMap<>(ApplicationStage.class);
        for (ApplicationStage stage : new ApplicationStage[] {NEW, SCREENING, INTERVIEW, OFFER}) {
            next.put(stage, ApplicationStageTransitions.next(stage));
        }
        assertThat(next).containsExactly(Map.entry(NEW, SCREENING), Map.entry(SCREENING, INTERVIEW),
                Map.entry(INTERVIEW, OFFER), Map.entry(OFFER, HIRED));
    }

    // The request's pattern must name exactly the stages of the enum, or a valid stage would fail validation.
    @Test
    void theRequestPatternListsEveryStage() {
        assertThat(ApplicationStageChangeRequest.STAGES)
                .isEqualTo(Arrays.stream(ApplicationStage.values()).map(Enum::name).collect(Collectors.joining("|")));
    }

    private static Map.Entry<String, Decision[][]> entry(ApplicationStage from, ApplicationStage to, Decision all) {
        return Map.entry(key(from, to), new Decision[][] {{all, all}, {all, all}});
    }

    private static String key(ApplicationStage from, ApplicationStage to) {
        return from + ">" + to;
    }
}
