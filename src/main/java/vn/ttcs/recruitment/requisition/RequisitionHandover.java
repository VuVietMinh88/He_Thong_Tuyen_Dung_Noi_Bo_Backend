package vn.ttcs.recruitment.requisition;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.common.ApiException;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The requisition side of handing over the work of an account (locking an account, feedback item 10): the
 * requisitions whose team (requisition_recruiters, V16) has the account as the PRIMARY or a SUPPORTING recruiter and
 * whose recruiters may still change, and the approval rounds in progress that still need a level the account is named
 * for. The statuses are RequisitionRecruiterService.ASSIGNABLE_STATUSES, so a new status decided there is listed here
 * too, and a requisition that can no longer be handed over (filled, cancelled, rejected) is never offered for a
 * handover. The rounds follow ApprovalRound, the same rules as the decision and the inbox.
 * <p>
 * The reads never lock and have no isolation of their own: they read in the caller's transaction (MANDATORY), like
 * RecruiterAssignmentAccess, so the account administration that calls them decides the snapshot. transfer hands the
 * requisitions over: it locks them and changes their teams with the rules and the history of RequisitionRecruiterService.
 */
@Component
public class RequisitionHandover {
    // The statuses bound as text: PgJDBC cannot bind a Java enum.
    private static final List<String> ASSIGNABLE = RequisitionRecruiterService.ASSIGNABLE_STATUSES.stream()
            .map(Enum::name).toList();

    private final NamedParameterJdbcTemplate jdbc;
    private final RequisitionApprovalRepository approvals;
    private final RecruitmentRequisitionRepository requisitions;
    private final RequisitionRecruiterRepository recruiters;
    private final RequisitionRecruiterService assignments;

    public RequisitionHandover(NamedParameterJdbcTemplate jdbc, RequisitionApprovalRepository approvals,
                               RecruitmentRequisitionRepository requisitions, RequisitionRecruiterRepository recruiters,
                               RequisitionRecruiterService assignments) {
        this.jdbc = jdbc;
        this.approvals = approvals;
        this.requisitions = requisitions;
        this.recruiters = recruiters;
        this.assignments = assignments;
    }

    // One requisition the account still holds, with what the lock dialog shows. Names are read now, not stored.
    public record HeldRequisition(UUID requisitionId, String positionCode, String positionName, UUID departmentId,
                                  String departmentName, RequisitionStatus status, RequisitionRecruiterRole role,
                                  int headcount, LocalDate neededBy, Instant assignedAt) { }

    // One level of a round in progress that names the account and is not decided yet. current: the round waits for
    // this level now; otherwise an earlier level decides first. flowId and flowVersion are the round's snapshot.
    // waitingSince: when the round's last event happened, as in the approver inbox.
    public record AwaitedApproval(UUID requisitionId, String positionCode, String positionName, UUID departmentId,
                                  String departmentName, int round, int level, boolean current, UUID flowId,
                                  int flowVersion, Instant waitingSince) { }

    // One requisition to hand over and who receives it. field is the request field of this item, such as
    // "requisitions[0]": an error about the item names field.requisitionId or field.toUserId in fieldErrors.
    public record Transfer(String field, UUID requisitionId, UUID toUserId) { }

    // PRIMARY before SUPPORTING (the text sorts that way), then the newest requisition first. Served by
    // requisition_recruiters_recruiter_id_idx (V16).
    @Transactional(propagation = Propagation.MANDATORY)
    public List<HeldRequisition> heldBy(UUID userId) {
        return jdbc.query("""
                SELECT r.id, r.status, r.headcount, r.needed_by, r.department_id, d.name AS department_name,
                       p.code AS position_code, p.name AS position_name, rr.assignment_role, rr.assigned_at
                FROM requisition_recruiters rr
                JOIN recruitment_requisitions r ON r.id = rr.requisition_id
                JOIN positions p ON p.id = r.position_id
                JOIN departments d ON d.id = r.department_id
                WHERE rr.recruiter_id = :userId AND r.status IN (:assignable)
                ORDER BY rr.assignment_role, r.created_at DESC, r.id
                """, new MapSqlParameterSource("userId", userId).addValue("assignable", ASSIGNABLE),
                (row, number) -> new HeldRequisition(row.getObject("id", UUID.class),
                        row.getString("position_code"), row.getString("position_name"),
                        row.getObject("department_id", UUID.class), row.getString("department_name"),
                        RequisitionStatus.valueOf(row.getString("status")),
                        RequisitionRecruiterRole.valueOf(row.getString("assignment_role")), row.getInt("headcount"),
                        row.getObject("needed_by", LocalDate.class),
                        row.getObject("assigned_at", OffsetDateTime.class).toInstant()));
    }

    // The requisitions waiting for approval whose open round still needs a level that names this account: the
    // current level or one after it, up to the round's level count. A round keeps the flow version it was submitted
    // with (the SUBMITTED snapshot, V19), so saving a new version of the flow does not change who it waits for, and
    // ApproverRule gives nobody an override of a user step. Levels that name a role are not listed. Waiting longest
    // first; like the inbox, the selection runs in memory over the requisitions waiting for approval.
    @Transactional(propagation = Propagation.MANDATORY)
    public List<AwaitedApproval> awaitingDecisionOf(UUID userId) {
        record Awaited(UUID requisitionId, ApprovalRound round, int level) { }
        List<Awaited> awaited = new ArrayList<>();
        Map<String, List<ApprovalLevel>> chains = new HashMap<>();
        approvals.findEventsOfPendingRequisitions().forEach((requisitionId, events) -> {
            var latest = ApprovalRound.latest(events).filter(ApprovalRound::open);
            if (latest.isEmpty()) {
                return;
            }
            ApprovalRound round = latest.get();
            List<ApprovalLevel> levels = chains.computeIfAbsent(round.flowId() + "/" + round.flowVersion() + "/"
                    + round.levelCount(), key -> approvals.findChainLevels(round.flowId(), round.flowVersion(),
                    round.levelCount()).stream().map(ChainLevelRow::level).toList());
            for (ApprovalLevel level : levels) {
                if (level.position() >= round.currentLevel() && userId.equals(level.approverUserId())) {
                    awaited.add(new Awaited(requisitionId, round, level.position()));
                }
            }
        });
        if (awaited.isEmpty()) {
            return List.of();
        }
        record Names(String positionCode, String positionName, UUID departmentId, String departmentName) { }
        Map<UUID, Names> names = new HashMap<>();
        jdbc.query("""
                SELECT r.id, r.department_id, d.name AS department_name, p.code AS position_code,
                       p.name AS position_name
                FROM recruitment_requisitions r
                JOIN positions p ON p.id = r.position_id
                JOIN departments d ON d.id = r.department_id
                WHERE r.id IN (:ids)
                """, new MapSqlParameterSource("ids", awaited.stream().map(Awaited::requisitionId).distinct().toList()),
                (ResultSet row) -> {
                    names.put(row.getObject("id", UUID.class), new Names(row.getString("position_code"),
                            row.getString("position_name"), row.getObject("department_id", UUID.class),
                            row.getString("department_name")));
                });
        return awaited.stream().map(item -> {
            Names name = names.get(item.requisitionId());
            ApprovalRound round = item.round();
            return new AwaitedApproval(item.requisitionId(), name.positionCode(), name.positionName(),
                    name.departmentId(), name.departmentName(), round.round(), item.level(),
                    item.level() == round.currentLevel(), round.flowId(), round.flowVersion(), round.waitingSince());
        }).sorted(Comparator.comparing(AwaitedApproval::waitingSince).thenComparing(AwaitedApproval::requisitionId)
                .thenComparingInt(AwaitedApproval::level)).toList();
    }

    /**
     * Hands requisitions of fromUserId over to other recruiters, all or nothing: one failing item rolls the caller's
     * transaction (MANDATORY) back with every change made before it.
     * <p>
     * Lock order: the caller has already locked its own account, the account handed over and every recipient in id
     * order (lockedAccounts, read under those locks), then its session, then the department tree when it also hands
     * departments over (DepartmentManagerHandover). This locks the requisition rows next, in id order and in one
     * statement: the order of RequisitionRecruiterService.assign (accounts, session, requisition), so
     * an assignment and a handover cannot deadlock, and two handovers that share requisitions cannot either. Taking a
     * row may wait for another write of that requisition, so callerCheck runs once all of them are held, and throws to
     * refuse the caller. Nothing waits after that: the writes only check foreign keys of rows this transaction holds.
     * <p>
     * Each item, in the order of the request: the requisition exists (otherwise 409 HANDOVER_ITEM_NOT_HELD), its status
     * allows changes (409 REQUISITION_NOT_ASSIGNABLE) and fromUserId is on its team (409 HANDOVER_ITEM_NOT_HELD). Then:
     * <ul>
     * <li>fromUserId is the PRIMARY: the recipient must be eligible (400) and becomes the primary
     * (PRIMARY_HANDED_OVER, which also promotes a supporting recipient);</li>
     * <li>fromUserId is SUPPORTING: it leaves the team (SUPPORTING_REMOVED), and a recipient not on the team yet must be
     * eligible (400) and joins as supporting (SUPPORTING_ADDED). Removing first keeps the team within its limit. A
     * recipient already on the team keeps its role and is not checked again, like an assignment that changes
     * nothing.</li>
     * </ul>
     * Every change goes through RequisitionRecruiterService.apply with the note, so the history (V17) records it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void transfer(UUID fromUserId, List<Transfer> transfers, Map<UUID, Account> lockedAccounts, UUID actorId,
                         String note, Runnable callerCheck) {
        Map<UUID, RecruitmentRequisition> locked = new HashMap<>();
        requisitions.findAllByIdForUpdate(transfers.stream().map(Transfer::requisitionId).collect(Collectors.toSet()))
                .forEach(requisition -> locked.put(requisition.getId(), requisition));
        callerCheck.run();
        for (Transfer item : transfers) {
            RecruitmentRequisition requisition = locked.get(item.requisitionId());
            if (requisition == null) {
                throw notHeld(item);
            }
            RequisitionRecruiterService.requireAssignable(requisition, item.field() + ".requisitionId");
            UUID id = item.requisitionId();
            UUID recipient = item.toUserId();
            String recipientField = item.field() + ".toUserId";
            RequisitionAssignment team = recruiters.findByRequisition(id);
            if (team.holds(fromUserId, RequisitionRecruiterRole.PRIMARY)) {
                RequisitionRecruiterService.requireEligible(lockedAccounts.get(recipient), recipientField);
                assignments.apply(id, team.assign(recipient, RequisitionRecruiterRole.PRIMARY), actorId, note);
            } else if (team.holds(fromUserId, RequisitionRecruiterRole.SUPPORTING)) {
                boolean onTeam = team.holds(recipient, RequisitionRecruiterRole.PRIMARY)
                        || team.holds(recipient, RequisitionRecruiterRole.SUPPORTING);
                if (!onTeam) {
                    RequisitionRecruiterService.requireEligible(lockedAccounts.get(recipient), recipientField);
                }
                assignments.apply(id, team.unassign(fromUserId).orElseThrow(), actorId, note);
                if (!onTeam) {
                    // The team read again, without fromUserId.
                    assignments.apply(id, recruiters.findByRequisition(id)
                            .assign(recipient, RequisitionRecruiterRole.SUPPORTING), actorId, note);
                }
            } else {
                throw notHeld(item);
            }
        }
    }

    private static ApiException notHeld(Transfer item) {
        String message = "Công việc này không còn thuộc tài khoản cần bàn giao. Vui lòng tải lại danh sách.";
        return new ApiException(HttpStatus.CONFLICT, "HANDOVER_ITEM_NOT_HELD", message,
                Map.of(item.field() + ".requisitionId", message));
    }
}
