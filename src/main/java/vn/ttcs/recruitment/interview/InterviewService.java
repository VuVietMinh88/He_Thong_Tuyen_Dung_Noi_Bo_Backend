package vn.ttcs.recruitment.interview;

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
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthSession;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.candidate.ApplicationAccess;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateAccessKind;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Appointment logistics. Invited panel access never expands the shared candidate/CV authorization rule. */
@Service
public class InterviewService {
    private final InterviewRepository data;
    private final AuthService auth;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final PermissionService permissions;
    private final CandidateAccess candidates;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final InterviewNotifications notifications;
    private final ObjectMapper json;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    public InterviewService(InterviewRepository data, AuthService auth, AccountRepository accounts,
            AuthSessionRepository sessions, PermissionService permissions, CandidateAccess candidates,
            JdbcTemplate jdbc, Clock clock, PlatformTransactionManager transactions,
            InterviewNotifications notifications, ObjectMapper json) {
        this.data = data; this.auth = auth; this.accounts = accounts; this.sessions = sessions;
        this.permissions = permissions; this.candidates = candidates; this.jdbc = jdbc; this.clock = clock;
        this.notifications = notifications; this.json = json;
        reads = new TransactionTemplate(transactions);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        writes = new TransactionTemplate(transactions);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(5);
    }

    public InterviewView get(Jwt jwt, UUID id) {
        return read(() -> {
            Account actor = reader(jwt);
            InterviewView view = data.find(id).orElseThrow(InterviewService::notFound);
            if (!data.participant(id, actor.getId())) {
                ApplicationAccess application = candidate(jwt, view.applicationId());
                audit(jwt, application);
            }
            return view;
        });
    }

    public InterviewPage list(Jwt jwt, UUID applicationId, int page, int size) {
        return read(() -> {
            reader(jwt); paging(page, size);
            ApplicationAccess application = candidate(jwt, applicationId);
            InterviewPage result = data.page(applicationId, null, page, size);
            audit(jwt, application);
            return result;
        });
    }

    public InterviewPage mine(Jwt jwt, int page, int size) {
        return read(() -> {
            Account actor = reader(jwt); paging(page, size);
            return data.page(null, actor.getId(), page, size);
        });
    }

    public InterviewParticipantPage participants(Jwt jwt, UUID applicationId, String query, int page, int size) {
        return read(() -> {
            reader(jwt); paging(page, size);
            ApplicationAccess application = candidate(jwt, applicationId, true);
            String normalizedQuery = text(query, 120, false, "q");
            InterviewParticipantPage result = data.eligibleParticipants(normalizedQuery == null ? "" : normalizedQuery, page, size);
            audit(jwt, application, true);
            return result;
        });
    }

    public InterviewView create(Jwt jwt, UUID applicationId, InterviewScheduleRequest raw) {
        InterviewScheduleRequest request = normalized(raw);
        if (request.expectedVersion() != null) throw invalid("expectedVersion", "Chỉ gửi phiên bản khi sửa lịch.");
        return write(() -> {
            Writer writer = lockWriter(jwt, request.participantIds());
            lockApplication(jwt, writer, applicationId);
            data.serializeConflictCheck();
            freshScope(jwt, writer, applicationId);
            eligible(writer, request.participantIds());
            Instant now = now();
            future(request, now);
            Instant end = end(request);
            List<InterviewConflict> conflicts = data.conflicts(null, request.startsAt(), end,
                    request.participantIds(), roomKey(request));
            boolean override = override(writer, request, conflicts);
            UUID id = UUID.randomUUID();
            data.insert(id, applicationId, request, roomKey(request), writer.account().getId(), now, end);
            InterviewView result = data.find(id).orElseThrow();
            data.event(result, "INTERVIEW_SCHEDULED", writer.account().getId(), now, null, override,
                    override ? request.overrideReason() : null);
            notifications.scheduled(result);
            audit(jwt, candidate(jwt, applicationId, true), true);
            return result;
        });
    }

    /** Send an existing appointment's first invitation, or replace an expired one; valid retries do not resend. */
    public void invite(Jwt jwt, UUID id, long expectedVersion) {
        write(() -> {
            Writer writer = lockWriter(jwt, List.of());
            notifications.requireReady();
            UUID application = applicationOf(id);
            lockApplication(jwt, writer, application);
            InterviewView current = data.lock(id).orElseThrow(InterviewService::notFound);
            freshScope(jwt, writer, application);
            editable(current, expectedVersion);
            if (!current.startsAt().isAfter(now())) {
                throw conflict("INTERVIEW_ALREADY_STARTED", "Không thể gửi lời mời cho lịch phỏng vấn đã bắt đầu.");
            }
            Boolean existing = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM interview_invitations WHERE interview_id=?
                        AND schedule_version=? AND revoked_at IS NULL AND expires_at>?)
                    """, Boolean.class, id, current.version(), java.sql.Timestamp.from(now()));
            if (!Boolean.TRUE.equals(existing)) notifications.scheduled(current);
            audit(jwt, candidate(jwt, application, true), true);
            return Boolean.TRUE;
        });
    }

    public InterviewView update(Jwt jwt, UUID id, InterviewScheduleRequest raw) {
        InterviewScheduleRequest request = normalized(raw);
        if (request.expectedVersion() == null) throw invalid("expectedVersion", "Cần phiên bản lịch đang hiển thị.");
        return write(() -> {
            Writer writer = lockWriter(jwt, request.participantIds());
            UUID application = applicationOf(id);
            lockApplication(jwt, writer, application);
            InterviewView current = data.lock(id).orElseThrow(InterviewService::notFound);
            freshScope(jwt, writer, application);
            editable(current, request.expectedVersion());
            data.serializeConflictCheck();
            freshScope(jwt, writer, application);
            eligible(writer, request.participantIds());
            Instant now = now();
            future(request, now);
            List<InterviewConflict> conflicts = data.conflicts(id, request.startsAt(), end(request),
                    request.participantIds(), roomKey(request));
            boolean override = override(writer, request, conflicts);
            data.update(id, request, roomKey(request), now, end(request));
            InterviewView result = data.find(id).orElseThrow();
            data.event(result, "INTERVIEW_RESCHEDULED", writer.account().getId(), now, null, override,
                    override ? request.overrideReason() : null);
            notifications.changed(current, result);
            audit(jwt, candidate(jwt, application, true), true);
            return result;
        });
    }

    public InterviewView cancel(Jwt jwt, UUID id, InterviewCancelRequest request) {
        String reason = text(request.reason(), 2000, true, "reason");
        return write(() -> {
            Writer writer = lockWriter(jwt, List.of());
            UUID application = applicationOf(id);
            // Cancellation frees resources even when recruiting stopped after the invitation was scheduled.
            lockApplication(jwt, writer, application, false);
            InterviewView current = data.lock(id).orElseThrow(InterviewService::notFound);
            freshScope(jwt, writer, application);
            editable(current, request.expectedVersion());
            // Serialize cancellation with a scheduler checking this session's old slot.
            data.serializeConflictCheck();
            freshScope(jwt, writer, application);
            Instant now = now();
            data.cancel(id, now);
            InterviewView result = data.find(id).orElseThrow();
            data.event(result, "INTERVIEW_CANCELLED", writer.account().getId(), now, reason, false, null);
            notifications.cancelled(current, result, reason);
            audit(jwt, candidate(jwt, application, true), true);
            return result;
        });
    }

    public List<InterviewConflict> conflicts(Jwt jwt, InterviewConflictRequest raw) {
        InterviewScheduleRequest request = normalized(raw.schedule());
        return write(() -> {
            Writer writer = lockWriter(jwt, request.participantIds());
            lockApplication(jwt, writer, raw.applicationId());
            if (raw.interviewId() != null && !applicationOf(raw.interviewId()).equals(raw.applicationId())) {
                throw invalid("interviewId", "Lịch cần sửa phải thuộc hồ sơ này.");
            }
            eligible(writer, request.participantIds());
            future(request, now());
            // Preview is advisory. Saving repeats this query under the global transaction lock.
            return data.conflicts(raw.interviewId(), request.startsAt(), end(request), request.participantIds(), roomKey(request));
        });
    }

    public InterviewBulkPreview previewBulk(Jwt jwt, InterviewBulkRequest raw) {
        BulkPlan plan = bulkPlan(raw);
        return write(() -> {
            Writer writer = lockWriter(jwt, plan.request().participantIds());
            notifications.requireReady();
            lockApplications(jwt, writer, plan.request().applicationIds(), true);
            data.serializeConflictCheck();
            refreshBulk(jwt, writer, plan.request().applicationIds());
            eligible(writer, plan.request().participantIds());
            List<InterviewBulkPreview.Slot> slots = previewSlots(writer, plan, now());
            for (UUID application : plan.request().applicationIds()) audit(jwt, candidate(jwt, application, true), true);
            boolean canCreate = Boolean.TRUE.equals(plan.request().overrideConflicts())
                    || slots.stream().allMatch(slot -> slot.conflicts().isEmpty());
            return new InterviewBulkPreview(canCreate, slots);
        });
    }

    public InterviewBulkResult createBulk(Jwt jwt, InterviewBulkRequest raw) {
        BulkPlan plan = bulkPlan(raw);
        String hash = hash(json.writeValueAsString(plan.request()));
        return write(() -> {
            Writer writer = lockWriter(jwt, plan.request().participantIds());
            // The caller's locked account serializes retries from all of that caller's sessions.
            var previous = data.bulkRequest(writer.account().getId(), plan.request().requestId());
            if (previous.isPresent()) {
                if (!previous.get().requestHash().equals(hash)) {
                    throw conflict("INTERVIEW_BULK_REQUEST_CHANGED", "Mã yêu cầu đã dùng cho dữ liệu khác. Hãy tạo mã mới.");
                }
                // Replaying an earlier success must not require its slots still to be in the future.
                // Current authorization still applies before returning any saved appointment details.
                lockApplications(jwt, writer, plan.request().applicationIds(), false);
                refreshBulk(jwt, writer, plan.request().applicationIds());
                for (UUID application : plan.request().applicationIds()) audit(jwt, candidate(jwt, application, true), true);
                return json.readValue(previous.get().responseJson(), InterviewBulkResult.class);
            }
            notifications.requireReady();
            lockApplications(jwt, writer, plan.request().applicationIds(), true);
            data.serializeConflictCheck();
            refreshBulk(jwt, writer, plan.request().applicationIds());
            eligible(writer, plan.request().participantIds());
            Instant now = now();
            List<InterviewBulkPreview.Slot> slots = previewSlots(writer, plan, now);
            // Validate every slot before creating any appointment or queuing any invitation.
            for (int i = 0; i < slots.size(); i++) override(writer, plan.schedules().get(i), slots.get(i).conflicts());
            List<InterviewView> created = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++) {
                var slot = slots.get(i);
                var request = plan.schedules().get(i);
                UUID id = UUID.randomUUID();
                data.insert(id, slot.applicationId(), request, roomKey(request), writer.account().getId(), now, slot.endsAt());
                InterviewView view = data.find(id).orElseThrow();
                boolean overridden = !slot.conflicts().isEmpty();
                data.event(view, "INTERVIEW_SCHEDULED", writer.account().getId(), now, null, overridden,
                        overridden ? request.overrideReason() : null);
                notifications.scheduled(view);
                audit(jwt, candidate(jwt, slot.applicationId(), true), true);
                created.add(view);
            }
            InterviewBulkResult result = new InterviewBulkResult(plan.request().requestId(), List.copyOf(created));
            data.rememberBulk(writer.account().getId(), result.requestId(), hash, json.writeValueAsString(result), now);
            return result;
        });
    }

    private record BulkPlan(InterviewBulkRequest request, List<InterviewScheduleRequest> schedules) { }

    private static BulkPlan bulkPlan(InterviewBulkRequest raw) {
        if (raw.requestId() == null) throw invalid("requestId", "Cần mã UUID để thử lại mà không đặt lịch trùng.");
        if (raw.applicationIds() == null || raw.applicationIds().isEmpty() || raw.applicationIds().size() > 50
                || raw.applicationIds().stream().anyMatch(Objects::isNull)
                || new HashSet<>(raw.applicationIds()).size() != raw.applicationIds().size()) {
            throw invalid("applicationIds", "Chọn từ 1 đến 50 hồ sơ, không trùng, theo thứ tự phỏng vấn.");
        }
        var first = normalized(new InterviewScheduleRequest(raw.roundName(), raw.windowStart(), raw.durationMinutes(),
                raw.mode(), raw.room(), raw.meetingUrl(), raw.participantIds(), null, raw.overrideConflicts(),
                raw.overrideReason(), raw.preparationInstructions()));
        if (raw.windowEnd() == null) throw invalid("windowEnd", "Cần giờ kết thúc khung phỏng vấn.");
        Instant windowEnd = raw.windowEnd().truncatedTo(ChronoUnit.MICROS);
        Duration window = Duration.between(first.startsAt(), windowEnd);
        if (window.isNegative() || window.isZero() || window.compareTo(Duration.ofHours(24)) > 0) {
            throw invalid("windowEnd", "Khung phỏng vấn phải lớn hơn 0 và không quá 24 giờ.");
        }
        long totalMinutes = (long) first.durationMinutes() * raw.applicationIds().size();
        if (window.compareTo(Duration.ofMinutes(totalMinutes)) < 0) {
            throw invalid("windowEnd", "Khung giờ không đủ cho tất cả hồ sơ theo thời lượng đã chọn.");
        }
        List<InterviewScheduleRequest> schedules = new ArrayList<>();
        for (int i = 0; i < raw.applicationIds().size(); i++) {
            Instant start;
            try { start = first.startsAt().plusSeconds((long) i * first.durationMinutes() * 60); }
            catch (RuntimeException failure) { throw invalid("windowStart", "Thời gian nằm ngoài phạm vi hỗ trợ."); }
            schedules.add(new InterviewScheduleRequest(first.roundName(), start, first.durationMinutes(), first.mode(),
                    first.room(), first.meetingUrl(), first.participantIds(), null, first.overrideConflicts(),
                    first.overrideReason(), first.preparationInstructions()));
        }
        var normalized = new InterviewBulkRequest(raw.requestId(), List.copyOf(raw.applicationIds()), first.roundName(),
                first.startsAt(), windowEnd, first.durationMinutes(), first.mode(), first.room(), first.meetingUrl(),
                first.participantIds(), Boolean.TRUE.equals(first.overrideConflicts()), first.overrideReason(),
                first.preparationInstructions());
        return new BulkPlan(normalized, List.copyOf(schedules));
    }

    private void lockApplications(Jwt jwt, Writer writer, List<UUID> applicationIds, boolean requireActive) {
        Map<UUID, UUID> requisitions = new HashMap<>();
        for (UUID application : applicationIds) {
            UUID requisition = data.requisitionOf(application);
            if (requisition == null) throw applicationNotFound();
            requisitions.put(application, requisition);
        }
        Map<UUID, String> statuses = new HashMap<>();
        // Lock all parents first: input order must never invert another batch's resource order.
        for (UUID requisition : requisitions.values().stream().distinct().sorted().toList()) {
            statuses.put(requisition, data.lockRequisition(requisition));
            fresh(jwt, writer);
        }
        for (UUID application : applicationIds.stream().sorted().toList()) {
            String stage = data.lockApplication(application);
            freshScope(jwt, writer, application);
            if (stage == null) throw applicationNotFound();
            if (requireActive && (!"APPROVED".equals(statuses.get(requisitions.get(application)))
                    || !ApplicationStage.valueOf(stage).active())) {
                throw conflict("INTERVIEW_APPLICATION_INACTIVE", "Hồ sơ hoặc yêu cầu tuyển dụng đã ngừng xử lý.");
            }
        }
    }

    private void refreshBulk(Jwt jwt, Writer writer, List<UUID> applications) {
        for (UUID application : applications) freshScope(jwt, writer, application);
    }

    private List<InterviewBulkPreview.Slot> previewSlots(Writer writer, BulkPlan plan, Instant now) {
        List<InterviewBulkPreview.Slot> slots = new ArrayList<>();
        for (int i = 0; i < plan.schedules().size(); i++) {
            var schedule = plan.schedules().get(i);
            future(schedule, now);
            // Validate explicit override authority even when this advisory preview has no conflicts.
            override(writer, schedule, List.of());
            Instant end = end(schedule);
            slots.add(new InterviewBulkPreview.Slot(plan.request().applicationIds().get(i), schedule.startsAt(), end,
                    data.conflicts(null, schedule.startsAt(), end, schedule.participantIds(), roomKey(schedule))));
        }
        return List.copyOf(slots);
    }

    private static String hash(String payload) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 is required by the JVM", failure); }
    }

    private Account reader(Jwt jwt) {
        validJwt(jwt);
        Account actor = auth.requireActiveAccount(jwt);
        AccessScope.read(permissions.forUser(actor.getId()), PermissionModule.INTERVIEWS).orDeny();
        return actor;
    }

    private record Writer(Account account, AuthSession session, Map<UUID, Account> lockedAccounts) { }

    private Writer lockWriter(Jwt jwt, List<UUID> panel) {
        jdbc.execute("SET LOCAL lock_timeout='2s'");
        jdbc.execute("SET LOCAL statement_timeout='3s'");
        validJwt(jwt);
        UUID actor; UUID sessionId;
        try { actor = UUID.fromString(jwt.getSubject()); sessionId = UUID.fromString(jwt.getId()); }
        catch (IllegalArgumentException | NullPointerException failure) { throw AuthenticationFailureException.sessionInvalid(); }
        // All accounts are locked in UUID order, including the caller, to avoid panel-member cross-lock deadlocks.
        Set<UUID> ids = new HashSet<>(panel); ids.add(actor);
        Map<UUID, Account> locked = accounts.findAllByIdForUpdate(ids).stream()
                .collect(Collectors.toMap(Account::getId, account -> account));
        Account account = locked.get(actor);
        if (account == null || !account.isAccessAllowed()) throw AuthenticationFailureException.sessionInvalid();
        AuthSession session = sessions.findByIdForUpdate(sessionId).orElseThrow(AuthenticationFailureException::sessionInvalid);
        Writer writer = new Writer(account, session, locked);
        fresh(jwt, writer);
        return writer;
    }

    private void fresh(Jwt jwt, Writer writer) {
        validJwt(jwt);
        if (!writer.session().getUserId().equals(writer.account().getId()) || !writer.session().isActive(clock.instant())) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        Set<String> granted = permissions.forUser(writer.account().getId());
        AccessScope.read(granted, PermissionModule.INTERVIEWS).orDeny();
        AccessScope.write(granted, PermissionModule.INTERVIEWS).orDeny();
    }

    private void freshScope(Jwt jwt, Writer writer, UUID application) {
        fresh(jwt, writer);
        candidate(jwt, application, true);
    }

    private void lockApplication(Jwt jwt, Writer writer, UUID application) {
        lockApplication(jwt, writer, application, true);
    }

    private void lockApplication(Jwt jwt, Writer writer, UUID application, boolean requireActive) {
        UUID requisition = data.requisitionOf(application);
        if (requisition == null) throw applicationNotFound();
        String status = data.lockRequisition(requisition);
        freshScope(jwt, writer, application);
        String stage = data.lockApplication(application);
        freshScope(jwt, writer, application);
        if (stage == null) throw applicationNotFound();
        if (requireActive && (!"APPROVED".equals(status) || !ApplicationStage.valueOf(stage).active())) {
            throw conflict("INTERVIEW_APPLICATION_INACTIVE", "Hồ sơ hoặc yêu cầu tuyển dụng đã ngừng xử lý.");
        }
    }

    private void eligible(Writer writer, List<UUID> participants) {
        for (UUID id : participants) {
            Account account = writer.lockedAccounts().get(id);
            if (account == null || !account.isAccessAllowed()
                    || AccessScope.write(permissions.forUser(id), PermissionModule.EVALUATIONS) == AccessScope.NONE) {
                throw invalid("participantIds", "Người phỏng vấn phải có tài khoản hoạt động và quyền đánh giá.");
            }
        }
    }

    private ApplicationAccess candidate(Jwt jwt, UUID application) {
        return candidate(jwt, application, false);
    }

    private ApplicationAccess candidate(Jwt jwt, UUID application, boolean writing) {
        CandidateReader reader = candidateReader(jwt, writing);
        ApplicationAccess found = candidates.findApplication(reader, application).orElseThrow(InterviewService::applicationNotFound);
        if (!found.readable()) throw new ScopeDenied(reader, found.candidateId(), application);
        return found;
    }

    // Permissions intersect: a wide CANDIDATES grant cannot undo an INTERVIEWS scope restriction, and vice versa.
    // The existing scoped predicate is current recruiter assignment or managed department, without panel expansion.
    private CandidateReader candidateReader(Jwt jwt, boolean writing) {
        CandidateReader candidateReader = candidates.requireReader(jwt);
        Set<String> granted = permissions.forUser(candidateReader.id());
        AccessScope interviewRead = AccessScope.read(granted, PermissionModule.INTERVIEWS).orDeny();
        AccessScope interviewWrite = writing ? AccessScope.write(granted, PermissionModule.INTERVIEWS).orDeny() : AccessScope.ALL;
        AccessScope scope = candidateReader.scope() == AccessScope.SCOPED || interviewRead == AccessScope.SCOPED
                || interviewWrite == AccessScope.SCOPED ? AccessScope.SCOPED : AccessScope.ALL;
        return new CandidateReader(candidateReader.id(), scope);
    }

    private void audit(Jwt jwt, ApplicationAccess application) {
        audit(jwt, application, false);
    }

    private void audit(Jwt jwt, ApplicationAccess application, boolean writing) {
        candidates.recordGranted(candidateReader(jwt, writing), CandidateAccessKind.PROFILE,
                application.candidateId(), List.of(application.applicationId()));
    }

    private UUID applicationOf(UUID id) {
        UUID application = data.applicationOf(id);
        if (application == null) throw notFound();
        return application;
    }

    private static void editable(InterviewView current, long expected) {
        if (current.version() != expected) throw conflict("INTERVIEW_VERSION_CHANGED", "Lịch vừa thay đổi. Vui lòng tải lại.");
        if (!"SCHEDULED".equals(current.status())) throw conflict("INTERVIEW_CANCELLED", "Lịch đã bị hủy.");
        if (current.everSubmittedAt() != null) {
            throw conflict("INTERVIEW_EVALUATION_SUBMITTED", "Đã có đánh giá được nộp; không thể đổi hoặc hủy lịch.");
        }
    }

    private static boolean override(Writer writer, InterviewScheduleRequest request, List<InterviewConflict> conflicts) {
        boolean requested = Boolean.TRUE.equals(request.overrideConflicts());
        if (requested && !writer.account().getRoles().contains(Role.RECRUITER)) {
            throw new AccessDeniedException("Only a recruiter may explicitly override scheduling conflicts");
        }
        if (!conflicts.isEmpty() && !requested) {
            throw conflict("INTERVIEW_CONFLICT", "Trùng lịch người phỏng vấn hoặc phòng. Kiểm tra xung đột trước khi đặt lịch.");
        }
        return requested && !conflicts.isEmpty();
    }

    private static InterviewScheduleRequest normalized(InterviewScheduleRequest request) {
        String round = text(request.roundName(), 120, true, "roundName");
        String room = text(request.room(), 200, false, "room");
        // Unicode whitespace, including NBSP, must not create another resource key for the same room.
        if (room != null) {
            room = room.replaceAll("(?U)\\s+", " ").strip();
            if (room.isEmpty()) room = null;
            else if (room.toLowerCase(Locale.ROOT).length() > 200) throw invalid("room", "Tên phòng vượt độ dài cho phép.");
        }
        String url = text(request.meetingUrl(), 2000, false, "meetingUrl");
        String reason = text(request.overrideReason(), 2000, false, "overrideReason");
        String preparation = text(request.preparationInstructions(), 2000, false, "preparationInstructions");
        if (preparation == null) preparation = "Vui lòng chuẩn bị CV và các thông tin liên quan đến kinh nghiệm của bạn.";
        if (request.startsAt() == null || request.durationMinutes() == null || request.durationMinutes() < 1
                || request.durationMinutes() > 480 || request.mode() == null) {
            throw invalid("startsAt", "Cần thời gian, hình thức và thời lượng từ 1 đến 480 phút.");
        }
        if (request.participantIds() == null || request.participantIds().isEmpty() || request.participantIds().size() > 20
                || request.participantIds().stream().anyMatch(Objects::isNull)
                || new HashSet<>(request.participantIds()).size() != request.participantIds().size()) {
            throw invalid("participantIds", "Chọn từ 1 đến 20 người phỏng vấn, không trùng nhau.");
        }
        if (request.mode() == InterviewMode.ONSITE && (room == null || url != null)) {
            throw invalid("room", "Phỏng vấn trực tiếp cần phòng và không có đường dẫn họp.");
        }
        if (request.mode() == InterviewMode.ONLINE) {
            if (room != null || url == null) throw invalid("meetingUrl", "Phỏng vấn trực tuyến cần đường dẫn HTTPS và không có phòng.");
            try {
                URI uri = URI.create(url);
                if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getPort() > 65535) throw new IllegalArgumentException();
            } catch (IllegalArgumentException failure) { throw invalid("meetingUrl", "Đường dẫn họp phải là HTTPS hợp lệ."); }
        }
        if (Boolean.TRUE.equals(request.overrideConflicts()) && reason == null) {
            throw invalid("overrideReason", "Cần lý do khi xác nhận đặt lịch trùng.");
        }
        if (!Boolean.TRUE.equals(request.overrideConflicts()) && reason != null) {
            throw invalid("overrideReason", "Chỉ gửi lý do khi xác nhận đặt lịch trùng.");
        }
        if (request.expectedVersion() != null && request.expectedVersion() < 0) {
            throw invalid("expectedVersion", "Phiên bản phải từ 0 trở lên.");
        }
        return new InterviewScheduleRequest(round, request.startsAt().truncatedTo(ChronoUnit.MICROS),
                request.durationMinutes(), request.mode(), room, url, List.copyOf(request.participantIds()),
                request.expectedVersion(), request.overrideConflicts(), reason, preparation);
    }

    private static String text(String value, int max, boolean required, String field) {
        String result = value == null ? null : Normalizer.normalize(value, Normalizer.Form.NFC)
                .replaceAll("(?U)^\\s+|\\s+$", "");
        if (result != null && result.isEmpty()) result = null;
        if (required && result == null || result != null && (result.length() > max || result.codePoints().anyMatch(Character::isISOControl))) {
            throw invalid(field, "Nội dung không hợp lệ hoặc vượt độ dài cho phép.");
        }
        return result;
    }

    private static String roomKey(InterviewScheduleRequest request) {
        return request.room() == null ? null : request.room().replaceAll("(?U)\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }
    private static Instant end(InterviewScheduleRequest request) {
        try { return request.startsAt().plusSeconds(request.durationMinutes() * 60L); }
        catch (RuntimeException failure) { throw invalid("startsAt", "Thời gian nằm ngoài phạm vi hỗ trợ."); }
    }
    private static void future(InterviewScheduleRequest request, Instant now) {
        if (!request.startsAt().isAfter(now)) throw invalid("startsAt", "Lịch phỏng vấn phải bắt đầu trong tương lai.");
        // JDBC/PostgreSQL timestamps use a smaller practical range than java.time.Instant.
        if (request.startsAt().isAfter(Instant.parse("9999-12-30T00:00:00Z"))) throw invalid("startsAt", "Thời gian không hợp lệ.");
    }
    private void validJwt(Jwt jwt) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant())) {
            throw AuthenticationFailureException.sessionInvalid();
        }
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static void paging(int page, int size) {
        if (page < 0 || page > 1_000_000 || size < 1 || size > 100) throw invalid("page", "Trang từ 0 đến 1000000; kích thước từ 1 đến 100.");
    }
    private <T> T read(Supplier<T> action) { return execute(reads, action); }
    private <T> T write(Supplier<T> action) { return execute(writes, action); }
    private <T> T execute(TransactionTemplate transaction, Supplier<T> action) {
        try { return Objects.requireNonNull(transaction.execute(status -> action.get())); }
        catch (ScopeDenied denied) {
            candidates.recordDenied(denied.reader, CandidateAccessKind.PROFILE, denied.candidate, denied.application);
            throw new AccessDeniedException("Application is outside the reader's scope");
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof TransientDataAccessException || cause instanceof TransactionTimedOutException
                        || cause instanceof SQLException sql && Set.of("55P03", "57014", "40P01").contains(sql.getSQLState())) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INTERVIEW_BUSY", "Lịch đang được xử lý. Vui lòng thử lại.");
                }
            }
            throw failure;
        }
    }
    private static final class ScopeDenied extends RuntimeException {
        final CandidateReader reader; final UUID candidate; final UUID application;
        ScopeDenied(CandidateReader reader, UUID candidate, UUID application) {
            this.reader = reader; this.candidate = candidate; this.application = application;
        }
    }
    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra dữ liệu đã nhập.", Map.of(field, message));
    }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "INTERVIEW_NOT_FOUND", "Không tìm thấy lịch phỏng vấn."); }
    private static ApiException applicationNotFound() { return new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", "Không tìm thấy hồ sơ ứng tuyển."); }
}
