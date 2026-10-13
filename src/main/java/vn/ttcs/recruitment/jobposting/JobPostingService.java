package vn.ttcs.recruitment.jobposting;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Task 303 (story S3-09): job postings made from approved requisitions, saved as drafts by their authors. Task 308
 * (story S3-10): the authors send them for review (submit); HR's decisions and the publication are in
 * JobPostingApprovalService. Task 309: the preview, and the public portal of published postings; both answer with
 * one builder (PublicJobPostingView.of) of one row query (JobPostingReadRepository.SELECT_ROW).
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
    // it is. JobPostingRequisitionSync closes the postings of a filled or cancelled requisition (task 292).
    static final Set<RequisitionStatus> SOURCE_STATUSES = EnumSet.of(RequisitionStatus.APPROVED);

    // Task 303: the statuses in which a posting may still be edited (PUT). JobPostingStatus asks every new status to be
    // classified here; JobPostingStatusTest does not compile until it is. Task 308: also when HR asked for changes;
    // a posting under review, approved or published is frozen, so HR reviews and publishes exactly the saved text.
    static final Set<JobPostingStatus> EDITABLE_STATUSES =
            EnumSet.of(JobPostingStatus.DRAFT, JobPostingStatus.REVISION_REQUESTED);

    // Task 308: the statuses from which the authors may send a posting for review. JobPostingStatus asks every new
    // status to be classified here; JobPostingStatusTest does not compile until it is.
    static final Set<JobPostingStatus> SUBMITTABLE_STATUSES =
            EnumSet.of(JobPostingStatus.DRAFT, JobPostingStatus.REVISION_REQUESTED);

    // Task 309: the statuses in which candidates see a posting on the public portal, while its application deadline
    // has not passed and its requisition is still in SOURCE_STATUSES. JobPostingStatus asks every new status to be
    // classified here; JobPostingStatusTest does not compile until it is.
    static final Set<JobPostingStatus> PUBLIC_STATUSES = EnumSet.of(JobPostingStatus.PUBLISHED);

    // Task 309: the two sets above as the names the public SQL filter binds (PgJDBC cannot bind a Java enum).
    // Derived from the sets, so the filter and the sets cannot drift apart.
    static final List<String> PUBLIC_STATUS_NAMES = PUBLIC_STATUSES.stream().map(Enum::name).toList();
    static final List<String> SOURCE_STATUS_NAMES = SOURCE_STATUSES.stream().map(Enum::name).toList();

    private final JobPostingAccess access;
    private final JobPostingRepository postings;
    private final JobPostingSourceRepository sources;
    private final JobPostingReadRepository reads;
    private final JobPostingAuthorRepository authors;
    private final JobPostingEventRepository events;
    private final RecruitmentCatalogItemRepository catalogItems;
    private final Validator validator;
    private final BusinessCalendar calendar;

    public JobPostingService(JobPostingAccess access, JobPostingRepository postings,
                             JobPostingSourceRepository sources, JobPostingReadRepository reads,
                             JobPostingAuthorRepository authors, JobPostingEventRepository events,
                             RecruitmentCatalogItemRepository catalogItems, Validator validator,
                             BusinessCalendar calendar) {
        this.access = access;
        this.postings = postings;
        this.sources = sources;
        this.reads = reads;
        this.authors = authors;
        this.events = events;
        this.catalogItems = catalogItems;
        this.validator = validator;
        this.calendar = calendar;
    }

    /**
     * A new DRAFT made from an approved requisition. The title, the job description and the candidate requirements the
     * body leaves out are taken from the requisition (the position name, then a copy of each text); the requisition
     * itself is never written.
     * Checks in order: write and read access (401/403, before any lookup), the requisition exists (400, a form error
     * of requisitionId), it is assigned to a SCOPED caller (403), it is approved (409), it has no posting yet (409),
     * the application deadline is not in the past (400, task 304), the catalog values exist in their own catalog and
     * are still in use (400). Creating a posting does not need a REQUISITIONS permission: a recruiter may compose
     * postings of requisitions they cannot read (task 285).
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
     * posting exists (404), it is in the caller's scope (403), it is not closed (409, task 292), it is editable (409),
     * the caller saw its latest revision (409), its requisition is still approved (409), the application deadline and
     * the catalog values (400). A successful save makes the caller an author of the posting.
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
        requireNotClosed(posting);
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

    /**
     * Task 308: the authors send the saved posting for review (story S3-10). Everything sent comes from the saved
     * posting, and from now on it is frozen, so HR reviews, then publishes, exactly what was saved.
     * Checks in order: write and read access (401/403, before any lookup), the posting exists (404), it is in the
     * caller's scope (403), it is not closed (409, task 292), it may be submitted (409), the caller saw its latest
     * revision (409), its requisition is still approved (409), the portal details are filled in and the saved texts
     * still pass the rules of a save (400 JOB_POSTING_INCOMPLETE, listing every field), the application deadline is
     * not in the past and the catalog values are still in use (400, the checks of a save). Lock order and re-checks
     * like update.
     * The posting moves to PENDING_APPROVAL with a new revision, and a SUBMITTED event records who sent it, when, and
     * the optional comment. updatedAt (the last content edit) does not change. Sending does not make the caller an
     * author, but the latest submitter may not approve the posting (JobPostingReviewRule).
     */
    @Transactional
    public JobPostingView submit(Jwt jwt, UUID id, JobPostingSubmitRequest request) {
        Writer writer = access.lockWriter(jwt);
        access.requireComposer(jwt, writer);
        UUID requisitionId = postings.findRequisitionIdById(id).orElseThrow(JobPostingService::notFound);
        SourceRequisition source = sources.lockSource(requisitionId).orElseThrow(JobPostingService::notFound);
        access.requireComposer(jwt, writer);
        var locked = postings.findByIdForUpdate(id);
        Caller caller = access.requireComposer(jwt, writer);
        JobPosting posting = locked.orElseThrow(JobPostingService::notFound);
        access.requireInScope(caller, requisitionId);
        requireNotClosed(posting);
        if (!SUBMITTABLE_STATUSES.contains(posting.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "JOB_POSTING_NOT_SUBMITTABLE",
                    "Chỉ gửi duyệt được tin tuyển dụng đang ở trạng thái nháp hoặc cần chỉnh sửa.");
        }
        requireRevision(posting, request.expectedRevision());
        requireSourceApproved(source);
        JobPostingContent content = contentOf(posting);
        requireComplete(posting.getRevision(), content);
        requireSaveable(content, calendar.today());
        // The FOR SHARE locks of the catalog values can wait, so the caller is checked again.
        caller = access.requireComposer(jwt, writer);
        access.requireInScope(caller, requisitionId);
        JobPostingStatus before = posting.getStatus();
        posting.moveTo(before, JobPostingStatus.PENDING_APPROVAL);
        postings.flush();
        events.append(id, posting.getRevision(), JobPostingEventType.SUBMITTED, before,
                JobPostingStatus.PENDING_APPROVAL, caller.id(), access.now(), request.comment());
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

    /**
     * Task 309 (story S3-10): the posting exactly as candidates will see it, in any status, for the people who read
     * it like get (the same checks: access 401/403, the posting exists 404, the scope 403). content is built by
     * PublicJobPostingView.of from the same row query as the public posting. HR approves and publishes with
     * expectedRevision = revision, so a change made after the preview is refused (409 JOB_POSTING_CHANGED). Writes
     * nothing.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public JobPostingPreview preview(Jwt jwt, UUID id) {
        Caller caller = access.requireReader(jwt);
        JobPostingRow row = reads.findRow(id).orElseThrow(JobPostingService::notFound);
        access.requireInScope(caller, row.requisitionId());
        return new JobPostingPreview(row.id(), row.status(), row.revision(), PublicJobPostingView.of(row));
    }

    /**
     * Task 309: a posting on the public portal, for anyone, without logging in. Only a posting in PUBLIC_STATUSES
     * whose application deadline is today or later (business date) and whose requisition is still in SOURCE_STATUSES;
     * every other posting answers 404 exactly like an id that does not exist, so the portal tells nothing about
     * drafts, postings under review or expired ones.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PublicJobPostingView getPublic(UUID id) {
        return reads.findPublic(id, calendar.today()).map(PublicJobPostingView::of)
                .orElseThrow(JobPostingService::notFound);
    }

    // Task 309: one page of the postings getPublic shows, newest publication first. REPEATABLE_READ: the count and
    // the page come from the same snapshot.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PublicJobPostingPage publicPage(int page, int size) {
        requireValidPage(page, size, "Trang hoặc số lượng tin tuyển dụng không hợp lệ.");
        return reads.publicPage(calendar.today(), page, size);
    }

    // The body's content, with the title, the job description and the candidate requirements it leaves out taken from
    // the requisition. The copy is plain text read through JDBC; nothing is written back to the requisition.
    // Task 304: the copied texts get \n line breaks like a text typed into the posting (the requisition API keeps
    // \r\n). They are not checked for plain text: a requisition may contain HTML, and the recruiter cannot read the
    // requisition to retype it. The copy is stored as it is, and the next save of the posting reports it. The position
    // name has no spaces at its ends already (V7 valid_position_name).
    private static JobPostingContent inherit(JobPostingCreateRequest request, SourceRequisition source) {
        return new JobPostingContent(
                request.title() != null ? request.title() : source.positionName(),
                request.jobDescription() != null ? request.jobDescription()
                        : JobPostingText.normalizeLineBreaks(source.jobDescription()),
                request.candidateRequirements() != null ? request.candidateRequirements()
                        : JobPostingText.normalizeLineBreaks(source.candidateRequirements()),
                request.benefits(), request.workLocationId(), request.employmentTypeId(),
                request.applicationDeadline(), Boolean.TRUE.equals(request.showSalary()));
    }

    // The rules a save needs besides the body checks, in this order. today: the business date, read once the
    // requisition (create) or the posting (update) is locked.
    // Task 304: candidates cannot apply before today, so the deadline is today or later. "Today" is the date in the
    // business time zone (BusinessCalendar, Vietnam by default), not in UTC: at 2026-10-11T17:30Z it is already
    // 2026-10-12 in Vietnam, so 2026-10-11 is refused. Today itself is allowed, and a draft may leave the deadline
    // empty. It is compared on every save, so a draft whose deadline has passed is saved again only with a new date or
    // none. It runs before the FOR SHARE locks on the catalog values, so the deadline error comes first; a wait on
    // those locks past midnight can still save yesterday's date (sending for review checks it again, task 308).
    // The catalog values are read FOR SHARE, work location first: HR cannot delete or deactivate them before this
    // transaction commits, and a delete or deactivation committed while this request waited is seen here as a 400,
    // never as a 500 from the foreign key. Task 304: a value HR stopped using (active = false) cannot be chosen; a
    // draft that already holds one is saved again only after the author picks another value (or HR turns it back on).
    private void requireSaveable(JobPostingContent content, LocalDate today) {
        LocalDate deadline = content.applicationDeadline();
        if (deadline != null && deadline.isBefore(today)) {
            throw invalidField("JOB_POSTING_DEADLINE_IN_PAST", "applicationDeadline",
                    "Hạn nộp hồ sơ không được trước ngày hôm nay.");
        }
        if (content.workLocationId() != null) {
            boolean active = catalogItems.findActiveForShare(content.workLocationId(),
                    RecruitmentCatalogType.WORK_LOCATION.name()).orElseThrow(() -> invalidField(
                    "INVALID_JOB_POSTING_WORK_LOCATION", "workLocationId", "Địa điểm làm việc không tồn tại."));
            if (!active) {
                throw invalidField("JOB_POSTING_WORK_LOCATION_INACTIVE", "workLocationId",
                        "Địa điểm làm việc đã ngừng sử dụng, vui lòng chọn giá trị khác.");
            }
        }
        if (content.employmentTypeId() != null) {
            boolean active = catalogItems.findActiveForShare(content.employmentTypeId(),
                    RecruitmentCatalogType.EMPLOYMENT_TYPE.name()).orElseThrow(() -> invalidField(
                    "INVALID_JOB_POSTING_EMPLOYMENT_TYPE", "employmentTypeId", "Hình thức làm việc không tồn tại."));
            if (!active) {
                throw invalidField("JOB_POSTING_EMPLOYMENT_TYPE_INACTIVE", "employmentTypeId",
                        "Hình thức làm việc đã ngừng sử dụng, vui lòng chọn giá trị khác.");
            }
        }
    }

    // Task 308: what is sent for review is what was saved.
    private static JobPostingContent contentOf(JobPosting posting) {
        return new JobPostingContent(posting.getTitle(), posting.getJobDescription(),
                posting.getCandidateRequirements(), posting.getBenefits(), posting.getWorkLocationId(),
                posting.getEmploymentTypeId(), posting.getApplicationDeadline(), posting.isShowSalary());
    }

    // Task 308: a draft may leave the portal details empty (task 303), a posting sent for review may not (V22
    // job_posting_details_required is the last line). The saved texts are checked again with the rules of a save
    // (JobPostingRequest): a text copied from the requisition is stored without them (see inherit), and an author who
    // never rewrote it must do so before HR sees it. Every problem is reported at once, like a form: a text with the
    // message of a save, a missing detail with its own message.
    private void requireComplete(int revision, JobPostingContent content) {
        Map<String, String> errors = new LinkedHashMap<>();
        var saved = new JobPostingRequest(revision, content.title(), content.jobDescription(),
                content.candidateRequirements(), content.benefits(), content.workLocationId(),
                content.employmentTypeId(), content.applicationDeadline(), content.showSalary());
        for (ConstraintViolation<JobPostingRequest> violation : validator.validate(saved)) {
            errors.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage());
        }
        if (content.workLocationId() == null) {
            errors.put("workLocationId", "Vui lòng chọn địa điểm làm việc trước khi gửi duyệt.");
        }
        if (content.employmentTypeId() == null) {
            errors.put("employmentTypeId", "Vui lòng chọn hình thức làm việc trước khi gửi duyệt.");
        }
        if (content.applicationDeadline() == null) {
            errors.put("applicationDeadline", "Vui lòng chọn hạn nộp hồ sơ trước khi gửi duyệt.");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "JOB_POSTING_INCOMPLETE",
                    "Tin tuyển dụng chưa đủ thông tin hợp lệ để gửi duyệt.", errors);
        }
    }

    // Postings are made from, and changed only for, a requisition in SOURCE_STATUSES. Task 308: also reviewed and
    // published only then (JobPostingApprovalService).
    static void requireSourceApproved(SourceRequisition source) {
        if (!SOURCE_STATUSES.contains(source.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "JOB_POSTING_REQUISITION_NOT_APPROVED",
                    "Chỉ tạo và xử lý được tin tuyển dụng của yêu cầu tuyển dụng đã duyệt.");
        }
    }

    // Task 292: a posting closed with its requisition never changes again; said before the step's own status check, so
    // a write that lost the race to the closure gets this message, not "chưa được duyệt" or "không sửa được".
    static void requireNotClosed(JobPosting posting) {
        if (posting.getStatus() == JobPostingStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "JOB_POSTING_CLOSED", "Tin tuyển dụng đã đóng cùng yêu cầu "
                    + "tuyển dụng nên không thể sửa, gửi duyệt, duyệt hay xuất bản nữa.");
        }
    }

    private static void requireEditable(JobPosting posting) {
        if (!EDITABLE_STATUSES.contains(posting.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "JOB_POSTING_NOT_EDITABLE",
                    "Chỉ sửa được tin tuyển dụng đang ở trạng thái nháp hoặc cần chỉnh sửa.");
        }
    }

    // A change made on an outdated view (a second tab, a change someone else saved meanwhile) is refused. Compared
    // under the posting row lock, so two saves of the same revision cannot both pass. Task 308: every workflow step
    // (submit, decisions, publication) is compared the same way.
    static void requireRevision(JobPosting posting, int expectedRevision) {
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

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "JOB_POSTING_NOT_FOUND", "Không tìm thấy tin tuyển dụng.");
    }
}
