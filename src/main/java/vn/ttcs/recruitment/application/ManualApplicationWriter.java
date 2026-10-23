package vn.ttcs.recruitment.application;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateAccessKind;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.candidate.CandidateWriteGuard;
import vn.ttcs.recruitment.common.ApiException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 407 (story S5-08): the write of one manual application, after the form and the CV were checked
 * (ManualApplicationService). One REQUIRES_NEW READ COMMITTED transaction of at most 5 s with lock_timeout 2 s and
 * statement_timeout 3 s, in the house's lock order:
 * <ol>
 * <li>the caller's account, then its session (CandidateWriteGuard), then CANDIDATES read and write read again;</li>
 * <li>the requisition: 404 REQUISITION_NOT_FOUND when unknown, 403 when the caller may not read its candidates or, with
 * a SCOPED write permission, is not one of its recruiters now; then its posting (one per requisition, V21), 404
 * JOB_POSTING_NOT_FOUND when it has none;</li>
 * <li>ApplicationStorageService.store, joining this transaction: the shared posting gate (requisition FOR NO KEY
 * UPDATE, then posting FOR SHARE; APPROVED, PUBLISHED and the deadline: 404/409), the active CANDIDATE_SOURCE, the
 * shared duplicate arbiter (409 for the same email in the same requisition), the candidate link by email and the CV
 * row. The caller is checked again after every one of its waits. A manual intake therefore never bypasses a rule of
 * the public form;</li>
 * <li>the manual_application_intakes row naming the caller, and the candidate's confirmation queued exactly as the
 * referral queues it (encrypted, joined enqueue): the candidate, not the staff member, receives the tracking code;</li>
 * <li>task 408: the duplicate report (ManualApplicationDuplicates) of the stored email and phone, and the PROFILE
 * disclosure log of every application the answer shows.</li>
 * </ol>
 * The token bytes are zeroed in finally.
 */
@Component
class ManualApplicationWriter {
    private final CandidateWriteGuard guard;
    private final CandidateAccess access;
    private final ManualApplicationAccess scope;
    private final ManualApplicationDuplicates duplicates;
    private final ApplicationStorageService storage;
    private final ApplicationReceiptDispatch receipts;
    private final ApplicationConfirmationSettings settings;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final SecureRandom random = new SecureRandom();

    ManualApplicationWriter(CandidateWriteGuard guard, CandidateAccess access, ManualApplicationAccess scope,
                            ManualApplicationDuplicates duplicates, ApplicationStorageService storage,
                            ApplicationReceiptDispatch receipts, ApplicationConfirmationSettings settings, JdbcTemplate jdbc,
                            PlatformTransactionManager manager) {
        this.guard = guard;
        this.access = access;
        this.scope = scope;
        this.duplicates = duplicates;
        this.storage = storage;
        this.receipts = receipts;
        this.settings = settings;
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(5);
    }

    ManualApplicationView enter(Jwt jwt, UUID requisitionId, ApplicationMultipartDecoder.ManualForm form, ValidatedCv cv) {
        if (!settings.ready()) throw ManualApplicationService.unavailable();
        byte[] token = new byte[32];
        random.nextBytes(token);
        try {
            String code = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token);
            return Objects.requireNonNull(transaction.execute(status ->
                    enterLocked(jwt, requisitionId, form, cv, code, hash)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        } finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    private ManualApplicationView enterLocked(Jwt jwt, UUID requisitionId, ApplicationMultipartDecoder.ManualForm form,
                                              ValidatedCv cv, String code, byte[] hash) {
        CandidateWriteGuard.Writer writer = guard.lockWriter(jwt);
        requireWriter(jwt, writer, requisitionId);
        UUID postingId = jdbc.query("SELECT id FROM job_postings WHERE requisition_id = ?",
                (row, number) -> row.getObject(1, UUID.class), requisitionId).stream().findFirst()
                .orElseThrow(ManualApplicationWriter::noPosting);
        StoredApplication stored = storage.store(postingId, form.submission(), cv, hash,
                () -> requireWriter(jwt, writer, requisitionId));
        CandidateReader reader = requireWriter(jwt, writer, requisitionId);
        jdbc.update("INSERT INTO manual_application_intakes(application_id, entered_by, entered_at) VALUES (?,?,?)",
                stored.id(), writer.id(), Timestamp.from(stored.receivedAt()));
        var saved = jdbc.queryForMap("""
                SELECT a.full_name, a.posting_title, ca.candidate_id
                FROM job_applications a JOIN candidate_applications ca ON ca.application_id = a.id WHERE a.id = ?
                """, stored.id());
        String title = (String) saved.get("posting_title");
        UUID candidateId = (UUID) saved.get("candidate_id");
        var receipt = new ApplicationReceipt(stored.id(), stored.receivedAt(), code, settings.trackingUrl(code));
        var payload = new ApplicationConfirmationPayload(form.submission().email(), form.submission().fullName(), title,
                receipt.receivedAt(), code, receipt.trackingUrl(), settings.from());
        // Task 533: queued like the referral's receipt, or recorded as suppressed when automatic sending is off.
        receipts.decide(stored.id(), payload);
        access.recordGranted(reader, CandidateAccessKind.PROFILE, candidateId, List.of(stored.id()));
        var report = duplicates.report(reader, requisitionId, form.submission().normalizedEmail(),
                form.submission().phone(), stored.id());
        ManualApplicationDuplicateService.recordDisclosed(access, reader, report);
        return new ManualApplicationView(stored.id(), candidateId, requisitionId, title, (String) saved.get("full_name"),
                stored.stage(), stored.stage().displayName(), stored.receivedAt(),
                new ManualApplicationView.Staff(writer.id(), writer.fullName()), report);
    }

    // Called after every lock wait: the token, the session and the permissions read again, then the requisition's
    // scope with those permissions.
    private CandidateReader requireWriter(Jwt jwt, CandidateWriteGuard.Writer writer, UUID requisitionId) {
        return scope.requireRequisitionWriter(writer.id(), guard.freshGrants(jwt, writer), requisitionId);
    }

    private static ApiException noPosting() {
        return new ApiException(HttpStatus.NOT_FOUND, "JOB_POSTING_NOT_FOUND",
                "Yêu cầu tuyển dụng chưa có tin tuyển dụng đang nhận hồ sơ.");
    }
}
