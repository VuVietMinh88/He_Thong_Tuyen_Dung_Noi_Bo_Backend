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
import vn.ttcs.recruitment.jobposting.JobPostingApplicationGate;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.UUID;

// Internal persistence boundary for task 326. No public endpoint or receipt email is exposed by task 325.
@Service
public class ApplicationStorageService {
    private final JdbcTemplate jdbc;
    private final JobPostingApplicationGate postingGate;
    private final ApplicationRepository applications;
    private final ApplicationCvRepository cvs;
    private final CandidateLinks candidates;
    private final RecruitmentCatalogItemRepository catalogs;
    private final Validator validator;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public ApplicationStorageService(JdbcTemplate jdbc, JobPostingApplicationGate postingGate,
            ApplicationRepository applications, ApplicationCvRepository cvs,
            CandidateLinks candidates, RecruitmentCatalogItemRepository catalogs, Validator validator,
            BusinessCalendar calendar, Clock clock) {
        this.jdbc = jdbc;
        this.postingGate = postingGate;
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
        return store(postingId, submission, cv, trackingHash, () -> { });
    }

    // Referrals recheck their authenticated caller after every lock wait. Anonymous intake uses the overload above.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public StoredApplication store(UUID postingId, ApplicationSubmission submission, ValidatedCv cv,
                                   byte[] trackingHash, Runnable callerCheck) {
        if (postingId == null || submission == null || cv == null || trackingHash == null || trackingHash.length != 32) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Dữ liệu hồ sơ hoặc tệp CV không hợp lệ.");
        }
        var errors = new LinkedHashMap<String, String>();
        validator.validate(submission).forEach(error -> errors.putIfAbsent(error.getPropertyPath().toString(), "Trường không hợp lệ."));
        if (!errors.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra thông tin hồ sơ.", errors);

        var posting = postingGate.requireOpen(postingId, callerCheck);
        // The gate holds the posting FOR SHARE, so its title cannot change before this transaction commits.
        String title = jdbc.queryForObject("SELECT title FROM job_postings WHERE id = ?", String.class, postingId);
        boolean sourceActive = submission.sourceId() == null
                || catalogs.findActiveForShare(submission.sourceId(), "CANDIDATE_SOURCE").orElse(false);
        callerCheck.run();
        if (!sourceActive) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Nguồn biết tin không còn áp dụng.");
        }
        // Recheck the date after the requisition, posting and source catalog waits. The application and candidate
        // arbiters below can wait too, so a second check follows the candidate link.
        var receivedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (posting.applicationDeadline().isBefore(calendar.dateOf(receivedAt))) throw JobPostingApplicationGate.expired();
        var application = new StoredApplication(UUID.randomUUID(), postingId, posting.requisitionId(), ApplicationStage.NEW,
                receivedAt);
        applications.insert(application, submission, title, trackingHash.clone());
        // Task 360: after the duplicate arbiter (a 409 links nothing), in this transaction: one profile per email.
        candidates.link(application.id(), submission.normalizedEmail(), receivedAt);
        callerCheck.run();
        // The link is the last statement that can wait (on a concurrent first application of the same email; the
        // arbiter above on one that rolls back): an application whose last wait ends after the deadline's business
        // midnight is refused like a late one. receivedAt stays the first check's time; the refusal rolls back the
        // application and its link, and with a joined caller transaction its referral and outbox rows too.
        if (posting.applicationDeadline().isBefore(calendar.dateOf(clock.instant()))) throw JobPostingApplicationGate.expired();
        cvs.insert(application, cv);
        return application;
    }

}
