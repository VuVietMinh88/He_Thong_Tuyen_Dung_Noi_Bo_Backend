package vn.ttcs.recruitment.requisition;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 261: the progress of a round, derived from the approval history without a database.
class ApprovalRoundTest {
    private static final UUID FLOW = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-10-11T03:00:00Z");
    private final UUID head = UUID.randomUUID();
    private final UUID hr = UUID.randomUUID();
    private final UUID director = UUID.randomUUID();
    private final List<ApprovalEvent> events = new ArrayList<>();

    @Test
    void aRequisitionNeverSubmittedHasNoRound() {
        assertThat(ApprovalRound.latest(events)).isEmpty();
        assertThat(ApprovalRound.all(events)).isEmpty();
        assertThat(ApprovalRound.lastRevision(events)).isZero();
    }

    @Test
    void aSubmittedRoundWaitsForItsFirstLevel() {
        submitted(1, 3);

        var round = ApprovalRound.latest(events).orElseThrow();
        assertThat(round.round()).isEqualTo(1);
        assertThat(round.submittedBy()).isEqualTo(head);
        assertThat(round.levelCount()).isEqualTo(3);
        assertThat(round.open()).isTrue();
        assertThat(round.currentLevel()).isEqualTo(1);
        assertThat(round.reachedLevel()).isEqualTo(1);
        assertThat(round.decidedBy()).isEmpty();
        assertThat(ApprovalRound.lastRevision(events)).isEqualTo(1);
    }

    @Test
    void eachApprovalMovesToTheNextLevelAndTheLastOneClosesTheRound() {
        submitted(1, 3);
        decided(1, 1, ApprovalEventType.APPROVED, hr, RequisitionStatus.PENDING_APPROVAL);
        decided(1, 2, ApprovalEventType.APPROVED, director, RequisitionStatus.PENDING_APPROVAL);

        var round = ApprovalRound.latest(events).orElseThrow();
        assertThat(round.currentLevel()).isEqualTo(3);
        assertThat(round.reachedLevel()).isEqualTo(3);
        assertThat(round.decidedBy()).containsExactlyInAnyOrder(hr, director);
        assertThat(round.waitingSince()).isEqualTo(AT.plusSeconds(3));

        decided(1, 3, ApprovalEventType.APPROVED, UUID.randomUUID(), RequisitionStatus.APPROVED);
        round = ApprovalRound.latest(events).orElseThrow();
        assertThat(round.open()).isFalse();
        assertThat(round.currentLevel()).isNull();
        assertThat(round.reachedLevel()).isEqualTo(3);
        assertThat(round.approvedLevels()).isEqualTo(3);
    }

    @Test
    void aRejectionClosesTheRoundAtTheLevelThatRejected() {
        submitted(1, 3);
        decided(1, 1, ApprovalEventType.APPROVED, hr, RequisitionStatus.PENDING_APPROVAL);
        decided(1, 2, ApprovalEventType.REJECTED, director, RequisitionStatus.REJECTED);

        var round = ApprovalRound.latest(events).orElseThrow();
        assertThat(round.open()).isFalse();
        assertThat(round.currentLevel()).isNull();
        assertThat(round.reachedLevel()).isEqualTo(2);
        assertThat(round.decisions()).containsOnlyKeys(1, 2);
    }

    @Test
    void aResubmissionStartsAFreshRoundAndKeepsTheOldOne() {
        submitted(1, 2);
        decided(1, 1, ApprovalEventType.APPROVED, hr, RequisitionStatus.PENDING_APPROVAL);
        decided(1, 2, ApprovalEventType.INFO_REQUESTED, director, RequisitionStatus.RETURNED);
        submitted(2, 1);

        var rounds = ApprovalRound.all(events);
        assertThat(rounds).extracting(ApprovalRound::round).containsExactly(1, 2);
        assertThat(rounds.getFirst().open()).isFalse();
        assertThat(rounds.getFirst().reachedLevel()).isEqualTo(2);
        var latest = ApprovalRound.latest(events).orElseThrow();
        assertThat(latest.round()).isEqualTo(2);
        assertThat(latest.currentLevel()).isEqualTo(1);
        assertThat(latest.levelCount()).isEqualTo(1);
        // Whoever decided round 1 may decide round 2 again.
        assertThat(latest.decidedBy()).isEmpty();
        assertThat(ApprovalRound.lastRevision(events)).isEqualTo(4);
    }

    @Test
    void aCancelOfAnotherKindInterruptsTheRound() {
        submitted(1, 2);
        // Task 293: HR cancels the requisition while level 1 decides, an event without a round.
        statusChanged(ApprovalEventType.CANCELLED, RequisitionStatus.PENDING_APPROVAL, RequisitionStatus.CANCELLED);

        var round = ApprovalRound.latest(events).orElseThrow();
        assertThat(round.open()).isFalse();
        assertThat(round.currentLevel()).isNull();
        assertThat(round.interrupted()).isTrue();
        // Level 1 was reached: its approvers keep reading the requisition, and a late decision gets a 409.
        assertThat(round.reachedLevel()).isEqualTo(1);
        assertThat(ApprovalRound.lastRevision(events)).isEqualTo(2);
    }

    @Test
    void onlyARoundThatStillWaitedIsInterrupted() {
        // Open: nothing came after it.
        submitted(1, 3);
        decided(1, 1, ApprovalEventType.APPROVED, hr, RequisitionStatus.PENDING_APPROVAL);
        var open = ApprovalRound.latest(events).orElseThrow();
        assertThat(open.interrupted()).isFalse();
        assertThat(open.reachedLevel()).isEqualTo(2);

        // Cancelled while level 2 waited.
        events.clear();
        submitted(1, 2);
        decided(1, 1, ApprovalEventType.APPROVED, hr, RequisitionStatus.PENDING_APPROVAL);
        statusChanged(ApprovalEventType.CANCELLED, RequisitionStatus.PENDING_APPROVAL, RequisitionStatus.CANCELLED);
        var cancelled = ApprovalRound.latest(events).orElseThrow();
        assertThat(cancelled.interrupted()).isTrue();
        assertThat(cancelled.reachedLevel()).isEqualTo(2);

        // Complete, then paused: every level was reached already.
        events.clear();
        submitted(1, 2);
        decided(1, 1, ApprovalEventType.APPROVED, hr, RequisitionStatus.PENDING_APPROVAL);
        decided(1, 2, ApprovalEventType.APPROVED, director, RequisitionStatus.APPROVED);
        statusChanged(ApprovalEventType.PAUSED, RequisitionStatus.APPROVED, RequisitionStatus.PAUSED);
        var paused = ApprovalRound.latest(events).orElseThrow();
        assertThat(paused.interrupted()).isFalse();
        assertThat(paused.reachedLevel()).isEqualTo(paused.levelCount());

        // Ended by asking for more information, then submitted again: round 1 stopped at the level that asked.
        events.clear();
        submitted(1, 2);
        decided(1, 1, ApprovalEventType.APPROVED, hr, RequisitionStatus.PENDING_APPROVAL);
        decided(1, 2, ApprovalEventType.INFO_REQUESTED, director, RequisitionStatus.RETURNED);
        submitted(2, 1);
        var returned = ApprovalRound.all(events).getFirst();
        assertThat(returned.interrupted()).isFalse();
        assertThat(returned.reachedLevel()).isEqualTo(2);

        // Ended by a rejection.
        events.clear();
        submitted(1, 3);
        decided(1, 1, ApprovalEventType.REJECTED, hr, RequisitionStatus.REJECTED);
        var rejected = ApprovalRound.latest(events).orElseThrow();
        assertThat(rejected.interrupted()).isFalse();
        assertThat(rejected.reachedLevel()).isEqualTo(1);
    }

    @Test
    void aRoundThatDoesNotStartWithASubmissionIsABug() {
        decided(1, 1, ApprovalEventType.APPROVED, hr, RequisitionStatus.PENDING_APPROVAL);

        assertThatThrownBy(() -> ApprovalRound.all(events)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theContextExcludesTheCreatorTheSubmitterAndTheDecidersOfTheRound() {
        submitted(1, 2);
        decided(1, 1, ApprovalEventType.APPROVED, hr, RequisitionStatus.PENDING_APPROVAL);
        var requisition = new RecruitmentRequisition(UUID.randomUUID(), UUID.randomUUID(), 1,
                RequisitionReason.NEW_HEADCOUNT, null, null, null, null, null, null, UUID.randomUUID(), AT);
        var levels = List.of(new ApprovalLevel(1, null, "HR_MANAGER", false),
                new ApprovalLevel(2, director, null, true));

        var context = ApprovalRound.latest(events).orElseThrow().context(requisition, java.util.Set.of("APPROVER"), levels);
        assertThat(context.departmentId()).isEqualTo(requisition.getDepartmentId());
        assertThat(context.createdBy()).isEqualTo(requisition.getCreatedBy());
        assertThat(context.submittedBy()).isEqualTo(head);
        assertThat(context.decidedInRound()).containsExactly(hr);
        assertThat(context.namedUserIds()).containsExactly(director);
    }

    private void submitted(int round, int levelCount) {
        events.add(new ApprovalEvent(UUID.randomUUID(), events.size() + 1, ApprovalEventType.SUBMITTED,
                round == 1 ? RequisitionStatus.DRAFT : RequisitionStatus.RETURNED, RequisitionStatus.PENDING_APPROVAL,
                round, null, FLOW, 1, levelCount, 30_000_000L, head, "Trưởng IT", AT.plusSeconds(events.size() + 1),
                null));
    }

    private void decided(int round, int level, ApprovalEventType type, UUID actor, RequisitionStatus after) {
        events.add(new ApprovalEvent(UUID.randomUUID(), events.size() + 1, type, RequisitionStatus.PENDING_APPROVAL,
                after, round, level, null, null, null, null, actor, "Người duyệt", AT.plusSeconds(events.size() + 1),
                type == ApprovalEventType.APPROVED ? null : "Lý do"));
    }

    // Task 293: a status change of HR has no round, level or snapshot; its comment is the reason.
    private void statusChanged(ApprovalEventType type, RequisitionStatus before, RequisitionStatus after) {
        events.add(new ApprovalEvent(UUID.randomUUID(), events.size() + 1, type, before, after, null, null, null, null,
                null, null, hr, "HR", AT.plusSeconds(events.size() + 1), "Không còn nhu cầu"));
    }
}
