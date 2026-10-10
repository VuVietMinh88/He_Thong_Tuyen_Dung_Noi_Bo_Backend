package vn.ttcs.recruitment.requisition;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// Task 284: RequisitionAssignRequest checks the role text with RequisitionRecruiterRole.CODES. An annotation cannot
// build that pattern from values(), so this test fails when someone adds or renames a role in only one place.
class RequisitionRecruiterRoleTest {

    @Test
    void rolePatternListsExactlyTheEnumValuesInOrder() {
        String fromEnum = Arrays.stream(RequisitionRecruiterRole.values()).map(Enum::name)
                .collect(Collectors.joining("|"));

        assertThat(RequisitionRecruiterRole.CODES).isEqualTo(fromEnum);
    }
}
