package vn.ttcs.recruitment.application;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.catalog.RecruitmentCatalogItemRepository;
import vn.ttcs.recruitment.catalog.RecruitmentCatalogType;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.jobposting.JobPostingService;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ApplicationIntakeService {
    private final ApplicationIntakeAvailability availability;
    private final ApplicationMultipartDecoder decoder;
    private final CvValidator cvs;
    private final JobPostingService postings;
    private final RecruitmentCatalogItemRepository catalogs;

    public ApplicationIntakeService(ApplicationIntakeAvailability availability, ApplicationMultipartDecoder decoder,
            CvValidator cvs, JobPostingService postings, RecruitmentCatalogItemRepository catalogs) {
        this.availability = availability; this.decoder = decoder; this.cvs = cvs;
        this.postings = postings; this.catalogs = catalogs;
    }

    // Called by the guard before body parsing. The writer must still use storage's locked final checks.
    void requirePosting(UUID postingId) { postings.getPublic(postingId); }

    // Deliberately not transactional: multipart/CV parsing must finish before the receipt/outbox transaction starts.
    public ApplicationReceipt submit(UUID postingId, HttpServletRequest request) {
        availability.requireReady();
        try {
            var decoded = decoder.decode(request);
            var cv = cvs.validate(decoded.filename(), new ByteArrayInputStream(decoded.bytes()));
            return Objects.requireNonNull(availability.writer().accept(postingId, decoded.submission(), cv));
        } catch (ApiException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // Exact duplicate mapping is a separate integration step. Never leak SQL, email or a parser stack.
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "APPLICATION_SUBMISSION_UNAVAILABLE",
                    "Chưa thể nhận hồ sơ lúc này. Vui lòng thử lại sau.");
        }
    }

    @Transactional(readOnly = true)
    public List<SourceOption> sources() {
        return catalogs.findForDisplay(RecruitmentCatalogType.CANDIDATE_SOURCE, List.of(true)).stream()
                .map(item -> new SourceOption(item.getId(), item.getName())).toList();
    }

    public record SourceOption(UUID id, String name) { }
}
