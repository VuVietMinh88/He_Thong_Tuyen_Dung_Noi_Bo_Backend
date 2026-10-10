package vn.ttcs.recruitment.requisition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Task 261: one approval round of a requisition, derived from its history (requisition_workflow_events). Nothing about
 * the progress is stored besides the history and the requisition status: the round's SUBMITTED event is its snapshot
 * (flow version, number of levels, salary), and its decisions say how far it got. Levels are decided in order, one
 * decision per level; the round is open while every decision so far approved, not every level has, and nothing
 * happened after it (a later round, or a later event of another kind such as closing the requisition).
 */
public final class ApprovalRound {
    private final ApprovalEvent submission;
    private final Map<Integer, ApprovalEvent> decisions;
    private final boolean followedByLaterEvent;
    private final Instant waitingSince;

    private ApprovalRound(ApprovalEvent submission, List<ApprovalEvent> decisions, boolean followedByLaterEvent,
                          Instant waitingSince) {
        this.submission = submission;
        Map<Integer, ApprovalEvent> byLevel = new TreeMap<>();
        for (ApprovalEvent decision : decisions) {
            byLevel.put(decision.level(), decision);
        }
        this.decisions = Collections.unmodifiableMap(byLevel);
        this.followedByLaterEvent = followedByLaterEvent;
        this.waitingSince = waitingSince;
    }

    // The revision a decision must quote (0 when nothing happened yet): the last event of the requisition.
    public static int lastRevision(List<ApprovalEvent> events) {
        return events.stream().mapToInt(ApprovalEvent::revision).max().orElse(0);
    }

    // Every round, oldest first. events: the whole history of one requisition.
    public static List<ApprovalRound> all(List<ApprovalEvent> events) {
        int lastRevision = lastRevision(events);
        Map<Integer, List<ApprovalEvent>> byRound = new LinkedHashMap<>();
        events.stream().filter(event -> event.round() != null)
                .sorted(Comparator.comparingInt(ApprovalEvent::revision))
                .forEach(event -> byRound.computeIfAbsent(event.round(), round -> new ArrayList<>()).add(event));
        List<ApprovalRound> rounds = new ArrayList<>();
        byRound.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            List<ApprovalEvent> roundEvents = entry.getValue();
            ApprovalEvent first = roundEvents.getFirst();
            if (first.type() != ApprovalEventType.SUBMITTED) {
                throw new IllegalStateException("Approval round " + entry.getKey() + " does not start with SUBMITTED");
            }
            ApprovalEvent last = roundEvents.getLast();
            rounds.add(new ApprovalRound(first, roundEvents.subList(1, roundEvents.size()),
                    last.revision() < lastRevision, last.occurredAt()));
        });
        return List.copyOf(rounds);
    }

    // The round the requisition is in, or was last in; empty when it was never submitted.
    public static Optional<ApprovalRound> latest(List<ApprovalEvent> events) {
        List<ApprovalRound> rounds = all(events);
        return rounds.isEmpty() ? Optional.empty() : Optional.of(rounds.getLast());
    }

    public int round() { return submission.round(); }
    public UUID submittedBy() { return submission.actorId(); }
    public String submittedByName() { return submission.actorName(); }
    public Instant submittedAt() { return submission.occurredAt(); }
    public UUID flowId() { return submission.flowId(); }
    public int flowVersion() { return submission.flowVersion(); }
    public int levelCount() { return submission.levelCount(); }
    public long salaryBasis() { return submission.salaryBasis(); }
    // The decisions of this round by level.
    public Map<Integer, ApprovalEvent> decisions() { return decisions; }
    // When the round's last event happened: since then it waits for its current level.
    public Instant waitingSince() { return waitingSince; }

    public int approvedLevels() {
        return (int) decisions.values().stream().filter(event -> event.type() == ApprovalEventType.APPROVED).count();
    }

    public boolean open() {
        return !followedByLaterEvent && approvedLevels() == decisions.size() && approvedLevels() < levelCount();
    }

    // The level that decides next, while the round is open.
    public Integer currentLevel() {
        return open() ? approvedLevels() + 1 : null;
    }

    // The levels this round actually got to: every decided level, plus the current one while open.
    public int reachedLevel() {
        return decisions.size() + (open() ? 1 : 0);
    }

    // Who decided a level of this round (one level per person and round).
    public Set<UUID> decidedBy() {
        return decisions.values().stream().map(ApprovalEvent::actorId).collect(Collectors.toUnmodifiableSet());
    }

    // What ApproverRule needs for this round. levels: the round's chain (levels 1 to levelCount).
    public ApprovalContext context(RecruitmentRequisition requisition, Set<String> companyWideRoles,
                                   List<ApprovalLevel> levels) {
        return new ApprovalContext(requisition.getDepartmentId(), requisition.getCreatedBy(), submittedBy(),
                ApprovalContext.namedUsers(levels), decidedBy(), companyWideRoles);
    }
}
