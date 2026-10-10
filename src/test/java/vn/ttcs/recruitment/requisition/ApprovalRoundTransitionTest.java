package vn.ttcs.recruitment.requisition;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 262: what a decision of the current level does to the round, without a database.
class ApprovalRoundTransitionTest {
    private static final Instant AT = Instant.parse("2026-10-11T03:00:00Z");
    private final List<ApprovalEvent> events = new ArrayList<>();

    @Test
    void aRejectionEndsTheRequisitionAtTheCurrentLevel() {
        submitted(3);
        assertThat(round().apply(ApprovalAction.REJECT)).isEqualTo(new ApprovalOutcome(ApprovalEventType.REJECTED, 1,
                RequisitionStatus.PENDING_APPROVAL, RequisitionStatus.REJECTED));

        approved(1, RequisitionStatus.PENDING_APPROVAL);
        assertThat(round().apply(ApprovalAction.REJECT).level()).isEqualTo(2);
    }

    @Test
    void aClosedRoundIsNeverDecided() {
        submitted(1);
        events.add(event(ApprovalEventType.REJECTED, 1, RequisitionStatus.REJECTED, "Không"));

        assertThatThrownBy(() -> round().apply(ApprovalAction.REJECT)).isInstanceOf(IllegalStateException.class);
    }

    private ApprovalRound round() {
        return ApprovalRound.latest(events).orElseThrow();
    }

    private void submitted(int levelCount) {
        events.add(new ApprovalEvent(UUID.randomUUID(), events.size() + 1, ApprovalEventType.SUBMITTED,
                RequisitionStatus.DRAFT, RequisitionStatus.PENDING_APPROVAL, 1, null, UUID.randomUUID(), 1, levelCount,
                30_000_000L, UUID.randomUUID(), "Trưởng IT", AT, null));
    }

    private void approved(int level, RequisitionStatus after) {
        events.add(event(ApprovalEventType.APPROVED, level, after, null));
    }

    private ApprovalEvent event(ApprovalEventType type, int level, RequisitionStatus after, String comment) {
        return new ApprovalEvent(UUID.randomUUID(), events.size() + 1, type, RequisitionStatus.PENDING_APPROVAL, after,
                1, level, null, null, null, null, UUID.randomUUID(), "Người duyệt", AT, comment);
    }
}
