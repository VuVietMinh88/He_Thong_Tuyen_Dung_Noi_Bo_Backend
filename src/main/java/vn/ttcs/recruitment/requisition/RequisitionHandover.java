package vn.ttcs.recruitment.requisition;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

/**
 * The requisition side of handing over the work of an account (locking an account, feedback item 10): the
 * requisitions whose team (requisition_recruiters, V16) has the account as the PRIMARY or a SUPPORTING recruiter and
 * whose recruiters may still change, and the approval rounds in progress that still need a level the account is named
 * for. The statuses are RequisitionRecruiterService.ASSIGNABLE_STATUSES, so a new status decided there is listed here
 * too, and a requisition that can no longer be handed over (filled, cancelled, rejected) is never offered for a
 * handover. The rounds follow ApprovalRound, the same rules as the decision and the inbox.
 * <p>
 * It never locks and has no isolation of its own: it reads in the caller's transaction (MANDATORY), like
 * RecruiterAssignmentAccess, so the account administration that calls it decides the snapshot.
 */
@Component
public class RequisitionHandover {
    // The statuses bound as text: PgJDBC cannot bind a Java enum.
    private static final List<String> ASSIGNABLE = RequisitionRecruiterService.ASSIGNABLE_STATUSES.stream()
            .map(Enum::name).toList();

    private final NamedParameterJdbcTemplate jdbc;
    private final RequisitionApprovalRepository approvals;

    public RequisitionHandover(NamedParameterJdbcTemplate jdbc, RequisitionApprovalRepository approvals) {
        this.jdbc = jdbc;
        this.approvals = approvals;
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
}
