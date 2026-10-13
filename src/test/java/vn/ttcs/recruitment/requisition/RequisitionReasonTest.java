package vn.ttcs.recruitment.requisition;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// Task 246: RequisitionRequest checks the reason text with RequisitionReason.CODES. An annotation cannot build that
// pattern from values(), so this test fails when someone adds or renames a reason in only one of the two places.
class RequisitionReasonTest {

    @Test
    void reasonPatternListsExactlyTheEnumValuesInOrder() {
        String fromEnum = Arrays.stream(RequisitionReason.values()).map(Enum::name)
                .collect(Collectors.joining("|"));

        assertThat(RequisitionReason.CODES).isEqualTo(fromEnum);
        assertThat(RequisitionReason.CODES).isEqualTo("REPLACEMENT|NEW_HEADCOUNT");
    }
}
