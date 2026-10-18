package vn.ttcs.recruitment.pipeline;

import org.junit.jupiter.api.Test;
import vn.ttcs.recruitment.application.ApplicationStage;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.application.ApplicationStage.HIRED;
import static vn.ttcs.recruitment.application.ApplicationStage.INTERVIEW;
import static vn.ttcs.recruitment.application.ApplicationStage.NEW;
import static vn.ttcs.recruitment.application.ApplicationStage.OFFER;
import static vn.ttcs.recruitment.application.ApplicationStage.REJECTED;
import static vn.ttcs.recruitment.application.ApplicationStage.SCREENING;
import static vn.ttcs.recruitment.application.ApplicationStage.WITHDRAWN;

/**
 * Task 380 (story S5-03): the whole 7 × 7 matrix of staff stage moves, written out by hand so a changed rule fails here
 * rather than slipping through: forward one step at a time, nothing back, nothing out of a final stage, never to
 * WITHDRAWN.
 */
class ApplicationStageTransitionsTest {
    @Test
    void onlyTheNextStageOnTheForwardPathIsAllowed() {
        assertAllowedTargets(NEW, EnumSet.of(SCREENING));
        assertAllowedTargets(SCREENING, EnumSet.of(INTERVIEW));
        assertAllowedTargets(INTERVIEW, EnumSet.of(OFFER));
        assertAllowedTargets(OFFER, EnumSet.of(HIRED));
        // Final in this version: nothing reopens a hired, rejected or withdrawn application.
        assertAllowedTargets(HIRED, EnumSet.noneOf(ApplicationStage.class));
        assertAllowedTargets(REJECTED, EnumSet.noneOf(ApplicationStage.class));
        assertAllowedTargets(WITHDRAWN, EnumSet.noneOf(ApplicationStage.class));
    }

    @Test
    void noStaffMoveLeadsBackToNewOrToWithdrawn() {
        for (ApplicationStage from : ApplicationStage.values()) {
            assertThat(ApplicationStageTransitions.allowed(from, NEW)).as(from + " -> NEW").isFalse();
            assertThat(ApplicationStageTransitions.allowed(from, WITHDRAWN)).as(from + " -> WITHDRAWN").isFalse();
            assertThat(ApplicationStageTransitions.allowed(from, from)).as(from + " -> itself").isFalse();
        }
    }

    // The request's pattern must name exactly the stages of the enum, or a valid stage would fail validation.
    @Test
    void theRequestPatternListsEveryStage() {
        assertThat(ApplicationStageChangeRequest.STAGES)
                .isEqualTo(Arrays.stream(ApplicationStage.values()).map(Enum::name).collect(Collectors.joining("|")));
    }

    private static void assertAllowedTargets(ApplicationStage from, Set<ApplicationStage> expected) {
        Set<ApplicationStage> allowed = EnumSet.noneOf(ApplicationStage.class);
        for (ApplicationStage to : ApplicationStage.values()) {
            if (ApplicationStageTransitions.allowed(from, to)) allowed.add(to);
        }
        assertThat(allowed).as("targets from " + from).isEqualTo(expected);
    }
}
