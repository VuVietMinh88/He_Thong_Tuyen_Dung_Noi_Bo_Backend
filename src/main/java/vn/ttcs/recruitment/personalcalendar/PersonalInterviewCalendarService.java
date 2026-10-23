package vn.ttcs.recruitment.personalcalendar;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateAccessKind;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** An invitation grants preparation access to this appointment only, never general candidate access. */
@Service
public class PersonalInterviewCalendarService {
    private final PersonalInterviewCalendarRepository data;
    private final AuthService auth;
    private final PermissionService permissions;
    private final CandidateAccess candidateAudit;
    private final Clock clock;
    private final TransactionTemplate reads;

    public PersonalInterviewCalendarService(PersonalInterviewCalendarRepository data, AuthService auth,
            PermissionService permissions, CandidateAccess candidateAudit, Clock clock,
            PlatformTransactionManager transactions) {
        this.data = data; this.auth = auth; this.permissions = permissions;
        this.candidateAudit = candidateAudit; this.clock = clock;
        reads = new TransactionTemplate(transactions);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        // The only writes are the private-data access audit, in the same snapshot as membership and CV content.
        reads.setTimeout(5);
    }

    public PersonalInterviewCalendar calendar(Jwt jwt, Instant requestedFrom, Instant requestedTo,
                                              boolean includePending, int page, int size) {
        return Objects.requireNonNull(reads.execute(transaction -> {
            CandidateReader actor = reader(jwt);
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Instant from = requestedFrom == null ? (includePending ? now.minus(90, ChronoUnit.DAYS) : now) : requestedFrom;
            Instant to = requestedTo == null ? now.plus(30, ChronoUnit.DAYS) : requestedTo;
            range(from, to, page, size);
            // An explicit historical window is a calendar view, including completed appointments.
            var result = data.page(actor.id(), from, to, now, includePending, requestedFrom != null, page, size);
            var audited = new HashSet<UUID>();
            for (var row : result.rows()) {
                if (audited.add(row.item().applicationId())) {
                    candidateAudit.recordGranted(actor, CandidateAccessKind.PROFILE, row.candidateId(),
                            List.of(row.item().applicationId()));
                }
            }
            return new PersonalInterviewCalendar(result.rows().stream().map(PersonalInterviewCalendarRepository.Row::item).toList(),
                    result.total(), page, size, from, to, includePending);
        }));
    }

    public CvContent cv(Jwt jwt, UUID interview) {
        CvRead result = Objects.requireNonNull(reads.execute(transaction -> {
            CandidateReader actor = reader(jwt);
            var context = data.cvContext(interview, actor.id()).orElseThrow(() ->
                    new ApiException(HttpStatus.NOT_FOUND, "INTERVIEW_NOT_FOUND", "Không tìm thấy lịch phỏng vấn."));
            if (!context.invited() || !context.scheduled() || !context.preparationActive()) {
                transaction.setRollbackOnly();
                return new CvRead(null, actor, context);
            }
            if (context.contentType() == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_CV_NOT_FOUND", "Hồ sơ ứng tuyển này không có tệp CV.");
            }
            // No byte query runs before the current invitation was checked in this same transaction.
            byte[] bytes = data.cvContent(context.applicationId());
            candidateAudit.recordGranted(actor, CandidateAccessKind.CV, context.candidateId(), List.of(context.applicationId()));
            return new CvRead(new CvContent(context.filename(), context.contentType(), bytes), actor, context);
        }));
        if (result.content() == null) {
            // Log a refusal only after the original transaction releases its connection.
            candidateAudit.recordDenied(result.actor(), CandidateAccessKind.CV,
                    result.context().candidateId(), result.context().applicationId());
            throw new AccessDeniedException("Only a current invited interviewer can prepare this appointment");
        }
        return result.content();
    }

    private CandidateReader reader(Jwt jwt) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant())) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        UUID actor = auth.requireActiveAccount(jwt).getId();
        var grants = permissions.forUser(actor);
        AccessScope.read(grants, PermissionModule.INTERVIEWS).orDeny();
        AccessScope.read(grants, PermissionModule.EVALUATIONS).orDeny();
        AccessScope.write(grants, PermissionModule.EVALUATIONS).orDeny();
        // SCOPED describes this appointment invitation. It is audit context, not a CANDIDATES grant or scope bypass.
        return new CandidateReader(actor, AccessScope.SCOPED);
    }

    private static void range(Instant from, Instant to, int page, int size) {
        Instant earliest = Instant.parse("1900-01-01T00:00:00Z");
        Instant latest = Instant.parse("9999-12-31T23:59:59Z");
        if (from.isBefore(earliest) || to.isAfter(latest) || !from.isBefore(to)
                || Duration.between(from, to).compareTo(Duration.ofDays(366)) > 0) {
            throw invalid("from", "Khoảng thời gian phải tăng dần và không quá 366 ngày.");
        }
        if (page < 0 || page > 1_000_000) throw invalid("page", "Trang phải từ 0 đến 1000000.");
        if (size < 1 || size > 100) throw invalid("size", "Mỗi trang phải có từ 1 đến 100 lịch.");
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, Map.of(field, message));
    }

    public record CvContent(String filename, String contentType, byte[] content) {
        @Override public String toString() { return "CvContent[filename and bytes redacted]"; }
    }
    private record CvRead(CvContent content, CandidateReader actor,
                          PersonalInterviewCalendarRepository.CvContext context) { }
}
