package vn.ttcs.recruitment.requisition;

import org.junit.jupiter.api.Test;
import vn.ttcs.recruitment.common.ApiException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 283: the rules for the recruiters of one requisition, without a database.
class RequisitionAssignmentTest {
    private static final UUID REQUISITION = UUID.randomUUID();
    private static final UUID HR = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-10-10T02:00:00Z");
    private final UUID anna = UUID.randomUUID();
    private final UUID binh = UUID.randomUUID();
    private final UUID chi = UUID.randomUUID();

    @Test
    void firstPrimaryIsAssigned() {
        var change = assignment().assign(anna, RequisitionRecruiterRole.PRIMARY);

        assertThat(change).isEqualTo(new RecruiterChange(RecruiterChangeType.PRIMARY_ASSIGNED, anna, null));
    }

    @Test
    void holdsKnowsWhoHasWhichRole() {
        var assignment = assignment(primary(anna), supporting(binh));

        assertThat(assignment.holds(anna, RequisitionRecruiterRole.PRIMARY)).isTrue();
        assertThat(assignment.holds(anna, RequisitionRecruiterRole.SUPPORTING)).isFalse();
        assertThat(assignment.holds(binh, RequisitionRecruiterRole.SUPPORTING)).isTrue();
        assertThat(assignment.holds(binh, RequisitionRecruiterRole.PRIMARY)).isFalse();
        assertThat(assignment().holds(anna, RequisitionRecruiterRole.PRIMARY)).isFalse();
    }

    @Test
    void anotherPrimaryIsAHandoverNamingThePreviousPrimary() {
        var change = assignment(primary(anna)).assign(binh, RequisitionRecruiterRole.PRIMARY);

        assertThat(change).isEqualTo(new RecruiterChange(RecruiterChangeType.PRIMARY_HANDED_OVER, binh, anna));
    }

    @Test
    void promotingASupportingRecruiterIsAHandover() {
        var change = assignment(primary(anna), supporting(binh)).assign(binh, RequisitionRecruiterRole.PRIMARY);

        assertThat(change).isEqualTo(new RecruiterChange(RecruiterChangeType.PRIMARY_HANDED_OVER, binh, anna));
    }

    @Test
    void supportingNeedsAPrimary() {
        assertThatThrownBy(() -> assignment().assign(anna, RequisitionRecruiterRole.SUPPORTING))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("REQUISITION_PRIMARY_RECRUITER_REQUIRED");
    }

    @Test
    void thePrimaryCannotAlsoBeSupporting() {
        assertThatThrownBy(() -> assignment(primary(anna)).assign(anna, RequisitionRecruiterRole.SUPPORTING))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("REQUISITION_RECRUITER_ALREADY_PRIMARY");
    }

    @Test
    void addsSupportingRecruitersUpToTenAndRefusesTheEleventh() {
        var rows = new ArrayList<RequisitionRecruiter>();
        rows.add(primary(anna));
        for (int i = 0; i < RequisitionAssignment.MAX_SUPPORTING_RECRUITERS - 1; i++) {
            rows.add(supporting(UUID.randomUUID()));
        }
        assertThat(assignment(rows).assign(binh, RequisitionRecruiterRole.SUPPORTING))
                .isEqualTo(new RecruiterChange(RecruiterChangeType.SUPPORTING_ADDED, binh, null));

        rows.add(supporting(binh));
        assertThatThrownBy(() -> assignment(rows).assign(chi, RequisitionRecruiterRole.SUPPORTING))
                .isInstanceOf(ApiException.class)
                .hasMessage("Mỗi yêu cầu tuyển dụng có tối đa 10 recruiter hỗ trợ.")
                .extracting("code").isEqualTo("REQUISITION_SUPPORTING_RECRUITER_LIMIT");
        // A handover never needs a free supporting place.
        assertThat(assignment(rows).assign(chi, RequisitionRecruiterRole.PRIMARY).type())
                .isEqualTo(RecruiterChangeType.PRIMARY_HANDED_OVER);
    }

    @Test
    void removingASupportingRecruiter() {
        assertThat(assignment(primary(anna), supporting(binh)).unassign(binh))
                .contains(new RecruiterChange(RecruiterChangeType.SUPPORTING_REMOVED, binh, null));
    }

    @Test
    void removingSomeoneNotAssignedIsANoOp() {
        assertThat(assignment(primary(anna)).unassign(binh)).isEmpty();
        assertThat(assignment().unassign(binh)).isEmpty();
    }

    @Test
    void thePrimaryCannotBeRemoved() {
        assertThatThrownBy(() -> assignment(primary(anna), supporting(binh)).unassign(anna))
                .isInstanceOf(ApiException.class)
                .hasMessage("Không thể bỏ recruiter chính. Hãy chuyển giao cho recruiter khác.")
                .extracting("code").isEqualTo("REQUISITION_PRIMARY_RECRUITER_REQUIRED");
    }

    @Test
    void ofRefusesTwoPrimariesAndSupportingWithoutPrimary() {
        assertThatThrownBy(() -> assignment(primary(anna), primary(binh))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> assignment(supporting(binh))).isInstanceOf(IllegalStateException.class);
        var assignment = assignment(supporting(binh), primary(anna));
        assertThat(assignment.primary().recruiterId()).isEqualTo(anna);
        assertThat(assignment.supporting()).extracting(RequisitionRecruiter::recruiterId).containsExactly(binh);
    }

    @Test
    void aChangeNamesThePreviousPrimaryOnlyForAHandover() {
        assertThatThrownBy(() -> new RecruiterChange(RecruiterChangeType.PRIMARY_HANDED_OVER, anna, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecruiterChange(RecruiterChangeType.SUPPORTING_ADDED, anna, binh))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RequisitionRecruiter primary(UUID id) {
        return new RequisitionRecruiter(id, RequisitionRecruiterRole.PRIMARY, HR, AT);
    }

    private static RequisitionRecruiter supporting(UUID id) {
        return new RequisitionRecruiter(id, RequisitionRecruiterRole.SUPPORTING, HR, AT);
    }

    private static RequisitionAssignment assignment(RequisitionRecruiter... rows) {
        return RequisitionAssignment.of(REQUISITION, List.of(rows));
    }

    private static RequisitionAssignment assignment(List<RequisitionRecruiter> rows) {
        return RequisitionAssignment.of(REQUISITION, rows);
    }
}
