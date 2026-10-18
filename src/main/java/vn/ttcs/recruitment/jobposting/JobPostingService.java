package vn.ttcs.recruitment.jobposting;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.catalog.RecruitmentCatalogItemRepository;
import vn.ttcs.recruitment.catalog.RecruitmentCatalogType;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.common.BusinessCalendar;
import vn.ttcs.recruitment.jobposting.JobPostingAccess.Caller;
import vn.ttcs.recruitment.jobposting.JobPostingAccess.Writer;
import vn.ttcs.recruitment.jobposting.JobPostingSourceRepository.SourceRequisition;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Task 303 (story S3-09): job postings made from approved requisitions, saved as drafts by their authors.
 * <p>
 * Lock order of every write: the actor's account, the actor's session (JobPostingAccess.lockWriter), the requisition
 * row (JobPostingSourceRepository.lockSource), the posting row, then the chosen catalog values FOR SHARE (work
 * location, then employment type). A posting is never locked before its requisition. Every lock can wait, so the caller
 * is checked again after each of them (JobPostingAccess.requireComposer, permissions read again), and the scope is
 * checked under the requisition lock. The requisition is only read, never written: posting code never loads the
 * RecruitmentRequisition entity.
 */
@Service
public class JobPostingService {
    // Task 303: the statuses of a requisition that postings are made from and may still be changed for.
    // RequisitionStatus asks every new status to be classified here; JobPostingSourceStatusTest does not compile until
    // it is. Closing postings when their requisition closes is task 292.
    static final Set<RequisitionStatus> SOURCE_STATUSES = EnumSet.of(RequisitionStatus.APPROVED);

    // Task 303: the statuses in which a posting may still be edited (PUT). JobPostingStatus asks every new status to be
    // classified here; JobPostingStatusTest does not compile until it is.
    static final Set<JobPostingStatus> EDITABLE_STATUSES = EnumSet.of(JobPostingStatus.DRAFT);

    private final JobPostingAccess access;
    private final JobPostingRepository postings;
    private final JobPostingSourceRepository sources;
    private final JobPostingReadRepository reads;
    private final JobPostingAuthorRepository authors;
    private final RecruitmentCatalogItemRepository catalogItems;
    private final BusinessCalendar calendar;

    public JobPostingService(JobPostingAccess access, JobPostingRepository postings,
                             JobPostingSourceRepository sources, JobPostingReadRepository reads,
                             JobPostingAuthorRepository authors, RecruitmentCatalogItemRepository catalogItems,
                             BusinessCalendar calendar) {
        this.access = access;
        this.postings = postings;
        this.sources = sources;
        this.reads = reads;
        this.authors = authors;
        this.catalogItems = catalogItems;
        this.calendar = calendar;
    }

    /**
     * A new DRAFT made from an approved requisition. The title, the job description and the candidate requirements the
     * body leaves out are taken from the requisition (the position name, then a copy of each text); the requisition
     * itself is never written.
     * Checks in order: write and read access (401/403, before any lookup), the requisition exists (400, a form error
     * of requisitionId), it is assigned to a SCOPED caller (403), it is approved (409), it has no posting yet (409),
     * the catalog values exist in their own catalog (400). Creating a posting does not need a REQUISITIONS permission:
     * a recruiter may compose postings of requisitions they cannot read (task 285).
     */
    @Transactional // READ COMMITTED: FOR SHARE after a wait must see new commits
    public JobPostingView create(Jwt jwt, JobPostingCreateRequest request) {
        Writer writer = access.lockWriter(jwt);
        access.requireComposer(jwt, writer);
        Optional<SourceRequisition> locked = sources.lockSource(request.requisitionId());
        Caller caller = access.requireComposer(jwt, writer);
        // An id in the body is a form error, like an unknown position on a requisition (404 before 403).
        SourceRequisition source = locked.orElseThrow(() -> invalidField("INVALID_JOB_POSTING_REQUISITION",
                "requisitionId", "Yêu cầu tuyển dụng không tồn tại."));
        access.requireInScope(caller, source.id());
        requireSourceApproved(source);
        // The requisition lock makes a second create of the same requisition wait and then find the first posting;
        // job_postings_requisition_id_key is the last line.
        if (postings.existsByRequisitionId(source.id())) {
            throw new ApiException(HttpStatus.CONFLICT, "JOB_POSTING_ALREADY_EXISTS",
                    "Yêu cầu tuyển dụng này đã có tin tuyển dụng.");
        }
        JobPostingContent content = inherit(request, source);
        requireSaveable(content, calendar.today());
        // The FOR SHARE locks above can wait for HR's change of a catalog value, so the caller is checked again.
        caller = access.requireComposer(jwt, writer);
        access.requireInScope(caller, source.id());
        // An assigned id makes Spring Data merge: the returned instance is the managed one.
        JobPosting posting = postings.saveAndFlush(new JobPosting(source.id(), content, caller.id(), access.now()));
        // The creator is the first author (task 308 refuses APPROVE to every author).
        authors.add(posting.getId(), caller.id());
        return view(posting.getId(), caller);
    }

    /**
     * Saves the draft again with the whole new content (PUT). Checks in order: write and read access (401/403), the
     * posting exists (404), it is in the caller's scope (403), it is editable (409), the caller saw its latest revision
     * (409), its requisition is still approved (409), the catalog values (400). A successful save makes the caller an
     * author of the posting.
     */
    @Transactional
    public JobPostingView update(Jwt jwt, UUID id, JobPostingRequest request) {
        Writer writer = access.lockWriter(jwt);
        access.requireComposer(jwt, writer);
        // The requisition of a posting never changes, so it can be read before any lock, and locked first.
        UUID requisitionId = postings.findRequisitionIdById(id).orElseThrow(JobPostingService::notFound);
        // No API deletes a requisition (V21 RESTRICT), so the posting's requisition is always there.
        SourceRequisition source = sources.lockSource(requisitionId).orElseThrow(JobPostingService::notFound);
        access.requireComposer(jwt, writer);
        var locked = postings.findByIdForUpdate(id);
        Caller caller = access.requireComposer(jwt, writer);
        JobPosting posting = locked.orElseThrow(JobPostingService::notFound);
        access.requireInScope(caller, requisitionId);
        requireEditable(posting);
        requireRevision(posting, request.expectedRevision());
        requireSourceApproved(source);
        JobPostingContent content = request.content();
        requireSaveable(content, calendar.today());
        caller = access.requireComposer(jwt, writer);
        access.requireInScope(caller, requisitionId);
        posting.replaceContent(content, access.now());
        postings.flush();
        // Still under the posting lock; saving again adds nobody new.
        authors.add(id, caller.id());
        return view(id, caller);
    }

    // The posting as its authors see it. A SCOPED reader must be assigned to its requisition. The unknown id is 404
    // before the scope, for every caller.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public JobPostingView get(Jwt jwt, UUID id) {
        Caller caller = access.requireReader(jwt);
        JobPostingRow row = reads.findRow(id).orElseThrow(JobPostingService::notFound);
        access.requireInScope(caller, row.requisitionId());
        return JobPostingView.from(row, caller, authors.findAuthors(id));
    }

    // One page of the postings the caller may read, newest first. status = null means every status.
    // REPEATABLE_READ: the count and the page come from the same snapshot.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public JobPostingPage list(Jwt jwt, JobPostingStatus status, int page, int size) {
        Caller caller = access.requireReader(jwt);
        requireValidPage(page, size, "Trang hoặc số lượng tin tuyển dụng không hợp lệ.");
        return reads.page(caller.scope(), caller.id(), status, page, size);
    }

    // The approved requisitions the caller may still make a posting from: no posting yet, and for a recruiter only
    // the requisitions assigned to them. Newest first.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SourceRequisitionPage sources(Jwt jwt, int page, int size) {
        Caller caller = access.requireReader(jwt);
        requireValidPage(page, size, "Trang hoặc số lượng yêu cầu tuyển dụng không hợp lệ.");
        return sources.findSources(caller.scope(), caller.id(), SOURCE_STATUSES, page, size);
    }

    // The body's content, with the title, the job description and the candidate requirements it leaves out taken from
    // the requisition. The copy is plain text read through JDBC; nothing is written back to the requisition.
    private static JobPostingContent inherit(JobPostingCreateRequest request, SourceRequisition source) {
        return new JobPostingContent(
                request.title() != null ? request.title() : source.positionName(),
                request.jobDescription() != null ? request.jobDescription() : source.jobDescription(),
                request.candidateRequirements() != null ? request.candidateRequirements()
                        : source.candidateRequirements(),
                request.benefits(), request.workLocationId(), request.employmentTypeId(),
                request.applicationDeadline(), Boolean.TRUE.equals(request.showSalary()));
    }

    // The rules a save needs besides the body checks. today: the business date, read once the requisition (create) or
    // the posting (update) is locked; the deadline rule of task 304 compares with it.
    // The catalog values are read FOR SHARE, work location first: HR cannot delete or deactivate them before this
    // transaction commits, and a delete committed while this request waited is seen here as a 400, never as a 500
    // from the foreign key. An inactive value is still accepted (task 304 refuses it).
    private void requireSaveable(JobPostingContent content, LocalDate today) {
        if (content.workLocationId() != null && catalogItems.findActiveForShare(content.workLocationId(),
                RecruitmentCatalogType.WORK_LOCATION.name()).isEmpty()) {
            throw invalidField("INVALID_JOB_POSTING_WORK_LOCATION", "workLocationId",
                    "Địa điểm làm việc không tồn tại.");
        }
        if (content.employmentTypeId() != null && catalogItems.findActiveForShare(content.employmentTypeId(),
                RecruitmentCatalogType.EMPLOYMENT_TYPE.name()).isEmpty()) {
            throw invalidField("INVALID_JOB_POSTING_EMPLOYMENT_TYPE", "employmentTypeId",
                    "Hình thức làm việc không tồn tại.");
        }
    }

    // Postings are made from, and changed only for, a requisition in SOURCE_STATUSES.
    private static void requireSourceApproved(SourceRequisition source) {
        if (!SOURCE_STATUSES.contains(source.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "JOB_POSTING_REQUISITION_NOT_APPROVED",
                    "Chỉ tạo và xử lý được tin tuyển dụng của yêu cầu tuyển dụng đã duyệt.");
        }
    }

    private static void requireEditable(JobPosting posting) {
        if (!EDITABLE_STATUSES.contains(posting.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "JOB_POSTING_NOT_EDITABLE",
                    "Chỉ sửa được tin tuyển dụng đang ở trạng thái nháp hoặc cần chỉnh sửa.");
        }
    }

    // A change made on an outdated view (a second tab, a change someone else saved meanwhile) is refused. Compared
    // under the posting row lock, so two saves of the same revision cannot both pass.
    private static void requireRevision(JobPosting posting, int expectedRevision) {
        if (posting.getRevision() != expectedRevision) {
            throw new ApiException(HttpStatus.CONFLICT, "JOB_POSTING_CHANGED",
                    "Tin tuyển dụng đã thay đổi (có thể người khác vừa sửa hoặc xử lý). "
                            + "Vui lòng tải lại trước khi thao tác.");
        }
    }

    // A long OFFSET above Integer.MAX_VALUE is rejected here as a normal 400. The long multiplication cannot overflow.
    private static void requireValidPage(int page, int size, String message) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
        }
    }

    // The answer of a write: the row as it is now saved, read in the same transaction.
    private JobPostingView view(UUID id, Caller caller) {
        return JobPostingView.from(reads.findRow(id).orElseThrow(), caller, authors.findAuthors(id));
    }

    // A 400 about one request field: the same text is the message and the form error of that field.
    private static ApiException invalidField(String code, String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message, Map.of(field, message));
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "JOB_POSTING_NOT_FOUND", "Không tìm thấy tin tuyển dụng.");
    }
}
