package vn.ttcs.recruitment.invitation;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.auth.passwordreset.ResetTokenGenerator;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateAccessKind;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Candidate capability access is limited to one invitation, never an account or a candidate profile. */
@Service
public class InterviewInvitationService {
    private static final String SELECT = """
            SELECT n.*, i.starts_at,i.ends_at,i.round_name,i.mode,i.room,i.meeting_url,i.preparation_instructions,
                   i.status AS interview_status,i.version AS current_version,a.posting_title,a.stage,r.status AS requisition_status
              FROM interview_invitations n
              JOIN interview_sessions i ON i.id=n.interview_id AND i.application_id=n.application_id
              JOIN job_applications a ON a.id=n.application_id
              JOIN recruitment_requisitions r ON r.id=a.requisition_id
             WHERE n.token_hash=?
            """;
    private final JdbcTemplate jdbc;
    private final ResetTokenGenerator tokens;
    private final InterviewInvitationSettings settings;
    private final Clock clock;
    private final CandidateAccess candidates;
    private final PermissionService permissions;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    public InterviewInvitationService(JdbcTemplate jdbc, ResetTokenGenerator tokens, InterviewInvitationSettings settings,
            Clock clock, CandidateAccess candidates, PermissionService permissions, PlatformTransactionManager transactions) {
        this.jdbc = jdbc; this.tokens = tokens; this.settings = settings; this.clock = clock;
        this.candidates = candidates; this.permissions = permissions;
        reads = new TransactionTemplate(transactions);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(5);
        writes = new TransactionTemplate(transactions);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(5);
    }

    public Details lookup(String token) {
        settings.requireReady(); String hash = hash(token);
        return reads.execute(tx -> valid(hash).details());
    }

    public Response respond(String token, String action, String rawNote) {
        settings.requireReady(); String hash = hash(token);
        String status = switch (action == null ? "" : action) {
            case "CONFIRM" -> "CONFIRMED";
            case "REQUEST_RESCHEDULE" -> "RESCHEDULE_REQUESTED";
            default -> throw validation("Chọn xác nhận hoặc đề nghị đổi lịch.");
        };
        String note = normalizeNote(rawNote);
        if (note != null && (note.length() > 2000 || note.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t'))) {
            throw validation("Ghi chú không quá 2000 ký tự và không chứa ký tự điều khiển.");
        }
        if ("RESCHEDULE_REQUESTED".equals(status) && note == null) throw validation("Vui lòng cho biết lý do cần đổi lịch.");
        try {
            return writes.execute(tx -> {
                jdbc.execute("SET LOCAL lock_timeout='2s'");
                jdbc.execute("SET LOCAL statement_timeout='3s'");
                Invitation before = valid(hash);
                // The same order as scheduling/withdrawal: requisition, application, interview, invitation.
                jdbc.queryForList("""
                        SELECT r.id FROM recruitment_requisitions r JOIN job_applications a ON a.requisition_id=r.id
                        WHERE a.id=? FOR NO KEY UPDATE OF r
                        """, before.applicationId());
                jdbc.queryForList("SELECT id FROM job_applications WHERE id=? FOR NO KEY UPDATE", before.applicationId());
                jdbc.queryForList("SELECT id FROM interview_sessions WHERE id=? FOR UPDATE", before.interviewId());
                jdbc.queryForList("SELECT id FROM interview_invitations WHERE id=? FOR UPDATE", before.id());
                Invitation current = valid(hash); // Fresh READ COMMITTED view after every potentially blocking lock.
                if (!"PENDING".equals(current.status())) {
                    if (status.equals(current.status()) && Objects.equals(note, current.note())) {
                        return new Response(current.status(), current.respondedAt());
                    }
                    throw new ApiException(HttpStatus.CONFLICT, "INTERVIEW_INVITATION_ALREADY_RESPONDED",
                            "Bạn đã phản hồi lời mời này. Vui lòng liên hệ nhà tuyển dụng để thay đổi.");
                }
                Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                jdbc.update("UPDATE interview_invitations SET status=?,response_note=?,responded_at=? WHERE id=?",
                        status, note, Timestamp.from(now), current.id());
                return new Response(status, now);
            });
        } catch (TransientDataAccessException | TransactionTimedOutException failure) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INTERVIEW_INVITATION_BUSY",
                    "Lịch phỏng vấn đang được cập nhật. Vui lòng thử lại.");
        }
    }

    /** Candidate permission and interview permission intersect; appointment panel membership does not expose notes. */
    public Status status(Jwt jwt, UUID interviewId) {
        try {
            return reads.execute(tx -> {
                CandidateReader candidateReader = candidates.requireReader(jwt);
                AccessScope interviewScope = AccessScope.read(permissions.forUser(candidateReader.id()), PermissionModule.INTERVIEWS).orDeny();
                CandidateReader reader = new CandidateReader(candidateReader.id(),
                        candidateReader.scope() == AccessScope.SCOPED || interviewScope == AccessScope.SCOPED ? AccessScope.SCOPED : AccessScope.ALL);
                var ids = jdbc.query("SELECT application_id FROM interview_sessions WHERE id=?",
                        (rs, row) -> rs.getObject(1, UUID.class), interviewId);
                if (ids.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "INTERVIEW_NOT_FOUND", "Không tìm thấy lịch phỏng vấn.");
                var application = candidates.findApplication(reader, ids.getFirst()).orElseThrow(InterviewInvitationService::invalid);
                if (!application.readable()) throw new ScopeDenied(reader, application.candidateId(), application.applicationId());
                List<Status> found = jdbc.query("""
                        SELECT status,responded_at,response_note,expires_at FROM interview_invitations
                         WHERE interview_id=? AND revoked_at IS NULL
                        """, (rs, row) -> new Status(rs.getString("status"), instant(rs, "responded_at"),
                        rs.getString("response_note"), instant(rs, "expires_at")), interviewId);
                candidates.recordGranted(reader, CandidateAccessKind.PROFILE, application.candidateId(), List.of(application.applicationId()));
                return found.isEmpty() ? new Status("NOT_SENT", null, null, null) : found.getFirst();
            });
        } catch (ScopeDenied denied) {
            candidates.recordDenied(denied.reader, CandidateAccessKind.PROFILE, denied.candidateId, denied.applicationId);
            throw denied;
        }
    }

    private Invitation valid(String hash) {
        var found = jdbc.query(SELECT, (rs, row) -> {
            Instant expires = instant(rs, "expires_at");
            Instant starts = instant(rs, "starts_at");
            boolean valid = rs.getTimestamp("revoked_at") == null && "SCHEDULED".equals(rs.getString("interview_status"))
                    && rs.getLong("schedule_version") == rs.getLong("current_version")
                    && expires.isAfter(clock.instant()) && starts.isAfter(clock.instant())
                    && "APPROVED".equals(rs.getString("requisition_status"))
                    && ApplicationStage.valueOf(rs.getString("stage")).active();
            if (!valid) throw invalid();
            Details details = new Details(rs.getString("posting_title"), rs.getString("round_name"), starts,
                    instant(rs, "ends_at"), rs.getString("mode"), rs.getString("room"), rs.getString("meeting_url"),
                    rs.getString("preparation_instructions"), rs.getString("status"), instant(rs, "responded_at"), expires);
            return new Invitation(rs.getObject("id", UUID.class), rs.getObject("interview_id", UUID.class),
                    rs.getObject("application_id", UUID.class), rs.getString("status"), rs.getString("response_note"),
                    instant(rs, "responded_at"), details);
        }, hash);
        if (found.isEmpty()) throw invalid();
        return found.getFirst();
    }

    private String hash(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        return tokens.hash(token);
    }
    private static String normalizeNote(String raw) {
        if (raw == null) return null;
        String text = raw.replace("\r\n", "\n").replace('\r', '\n').strip();
        boolean visible = text.codePoints().anyMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c)
                && !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT && c != 0x0085 && c != 0x180e);
        return visible ? text : null;
    }
    private static Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(name); return timestamp == null ? null : timestamp.toInstant();
    }
    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INTERVIEW_INVITATION_INVALID",
                "Lời mời không còn hiệu lực. Vui lòng liên hệ nhà tuyển dụng để nhận lời mời mới.");
    }
    private static ApiException validation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INTERVIEW_INVITATION_VALIDATION", message);
    }
    private static final class ScopeDenied extends AccessDeniedException {
        private final CandidateReader reader; private final UUID candidateId; private final UUID applicationId;
        ScopeDenied(CandidateReader reader, UUID candidateId, UUID applicationId) {
            super("Outside current interview candidate scope");
            this.reader = reader; this.candidateId = candidateId; this.applicationId = applicationId;
        }
    }
    private record Invitation(UUID id, UUID interviewId, UUID applicationId, String status, String note,
                              Instant respondedAt, Details details) { }
    public record Details(String positionTitle, String roundName, Instant startsAt, Instant endsAt, String mode,
                          String room, String meetingUrl, String preparationInstructions, String status,
                          Instant respondedAt, Instant expiresAt) { }
    public record Response(String status, Instant respondedAt) { }
    public record Status(String status, Instant respondedAt, String note, Instant expiresAt) {
        @Override public String toString() { return "InvitationStatus[" + status + "]"; }
    }
}
