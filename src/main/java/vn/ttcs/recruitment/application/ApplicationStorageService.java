package vn.ttcs.recruitment.application;

import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.catalog.RecruitmentCatalogItemRepository;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.common.BusinessCalendar;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.UUID;

// Internal persistence boundary for task 326. No public endpoint or receipt email is exposed by task 325.
@Service
public class ApplicationStorageService {
    private final JdbcTemplate jdbc;
    private final ApplicationRepository applications;
    private final ApplicationCvRepository cvs;
    private final CandidateLinks candidates;
    private final RecruitmentCatalogItemRepository catalogs;
    private final Validator validator;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public ApplicationStorageService(JdbcTemplate jdbc, ApplicationRepository applications, ApplicationCvRepository cvs,
            CandidateLinks candidates, RecruitmentCatalogItemRepository catalogs, Validator validator,
            BusinessCalendar calendar, Clock clock) {
        this.jdbc = jdbc;
        this.applications = applications;
        this.cvs = cvs;
        this.candidates = candidates;
        this.catalogs = catalogs;
        this.validator = validator;
        this.calendar = calendar;
        this.clock = clock;
    }

    // READ_COMMITTED sees changes committed while waiting. Locks conflict with pause/closure and unpublication.
    // The future reception service must call this through its Spring proxy, in the same transaction as its outbox.
    // Task 360: the candidate link needs READ_COMMITTED too (CandidateLinks); a caller must not run this inside
    // REPEATABLE READ, or two concurrent first applications of one email would end in a serialization failure.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StoredApplication store(UUID postingId, ApplicationSubmission submission, ValidatedCv cv, byte[] trackingHash) {
        if (postingId == null || submission == null || cv == null || trackingHash == null || trackingHash.length != 32) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Dữ liệu hồ sơ hoặc tệp CV không hợp lệ.");
        }
        var errors = new LinkedHashMap<String, String>();
        validator.validate(submission).forEach(error -> errors.putIfAbsent(error.getPropertyPath().toString(), "Trường không hợp lệ."));
        if (!errors.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra thông tin hồ sơ.", errors);

        var sources = jdbc.query("SELECT requisition_id FROM job_postings WHERE id = ?",
                (row, number) -> row.getObject(1, UUID.class), postingId);
        if (sources.isEmpty()) throw unavailable();
        UUID requisitionId = sources.getFirst();
        var statuses = jdbc.query("SELECT status FROM recruitment_requisitions WHERE id = ? FOR NO KEY UPDATE",
                (row, number) -> row.getString(1), requisitionId);
        if (statuses.isEmpty() || !"APPROVED".equals(statuses.getFirst())) throw unavailable();
        var postings = jdbc.query("""
                SELECT requisition_id, status, application_deadline, title FROM job_postings WHERE id = ? FOR SHARE
                """, (row, number) -> new Posting(row.getObject(1, UUID.class), row.getString(2),
                row.getObject(3, LocalDate.class), row.getString(4)), postingId);
        if (postings.isEmpty()) throw unavailable();
        Posting posting = postings.getFirst();
        if (!posting.requisitionId().equals(requisitionId) || !"PUBLISHED".equals(posting.status())
                || posting.deadline() == null || posting.deadline().isBefore(calendar.today())) throw unavailable();
        if (submission.sourceId() != null && !catalogs.findActiveForShare(submission.sourceId(), "CANDIDATE_SOURCE").orElse(false)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Nguồn biết tin không còn áp dụng.");
        }
        // Recheck the date after the requisition, posting and source catalog waits. The application and candidate
        // arbiters below can wait too, so a second check follows the candidate link.
        var receivedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (posting.deadline().isBefore(calendar.dateOf(receivedAt))) throw unavailable();
        var application = new StoredApplication(UUID.randomUUID(), postingId, requisitionId, ApplicationStage.NEW,
                receivedAt);
        applications.insert(application, submission, posting.title(), trackingHash.clone());
        // Task 360: after the duplicate arbiter (a 409 links nothing), in this transaction: one profile per email.
        candidates.link(application.id(), submission.normalizedEmail(), receivedAt);
        // The link is the last statement that can wait (on a concurrent first application of the same email; the
        // arbiter above on one that rolls back): an application whose last wait ends after the deadline's business
        // midnight is refused like a late one. receivedAt stays the first check's time; the refusal rolls back the
        // application and its link, and with a joined caller transaction its referral and outbox rows too.
        if (posting.deadline().isBefore(calendar.dateOf(clock.instant()))) throw unavailable();
        cvs.insert(application, cv);
        return application;
    }

    private record Posting(UUID requisitionId, String status, LocalDate deadline, String title) { }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.NOT_FOUND, "JOB_POSTING_NOT_FOUND", "Tin tuyển dụng không còn nhận hồ sơ.");
    }
}
