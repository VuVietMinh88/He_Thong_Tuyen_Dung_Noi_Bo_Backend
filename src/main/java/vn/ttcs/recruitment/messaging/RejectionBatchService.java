package vn.ttcs.recruitment.messaging;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateAccessKind;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.companyprofile.PlainText;
import vn.ttcs.recruitment.mail.MailLayout;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 526 (story S7-09, "Xem trước nội dung thư với dữ liệu thật của từng người trước khi gửi"): the personalised
 * rejection letter of each selected application, without sending anything.
 * <p>
 * Who: a NOTIFICATIONS read permission and a CANDIDATES read permission, read again from the database, then every
 * selected application must be readable by the caller (CandidateAccess.READABLE); one application outside the scope
 * refuses the whole batch with 403 before anything is shown (logged as a refused read), so a batch never leaks one
 * candidate among others. All of them must be in the same, rejected state (409 REJECTION_BATCH_NOT_REJECTED with the
 * offending ids): the letter is about a rejection that happened. Each entry shows the letter filled in with that
 * candidate's own data (the saved REJECTION template, or the unsaved draft of the request) and whether the candidate
 * was already notified (a rejection letter queued, being sent or sent) or has a failed one (to be retried, task 539).
 */
@Service
public class RejectionBatchService {
    static final int MAX_BATCH = 100;
    private final CandidateAccess candidates;
    private final MessagingActors actors;
    private final MessagingTransactions transactions;
    private final EmailTemplateRepository templates;
    private final NamedParameterJdbcTemplate jdbc;
    private final MailLayout layout;
    private final String companyName;

    public RejectionBatchService(CandidateAccess candidates, MessagingActors actors, MessagingTransactions transactions,
                                 EmailTemplateRepository templates, NamedParameterJdbcTemplate jdbc, MailLayout layout,
                                 @org.springframework.beans.factory.annotation.Value("${app.mail.company-name}") String companyName) {
        this.candidates = candidates;
        this.actors = actors;
        this.transactions = transactions;
        this.templates = templates;
        this.jdbc = jdbc;
        this.layout = layout;
        this.companyName = companyName;
    }

    public record PreviewRequest(
            @NotNull @Size(min = 1, max = MAX_BATCH) List<@NotNull UUID> applicationIds,
            @Size(max = TemplateText.MAX_SUBJECT_LENGTH) @PlainText String subject,
            @Size(max = TemplateText.MAX_BODY_LENGTH) @PlainText(multiline = true) String body) {
        public PreviewRequest {
            body = body == null ? null : body.replace("\r\n", "\n");
        }

        @JsonAnySetter public void reject(String name, Object value) { throw new IllegalArgumentException("Unsupported field"); }
    }

    /** One candidate's letter and whether sending would skip it. */
    public record PreviewEntry(UUID applicationId, String candidateName, String positionTitle, String recipientEmail,
                               String subject, String text, String html, String rejectionLetter) {
    }

    public record Preview(boolean draft, long templateVersion, List<PreviewEntry> entries) {
    }

    public Preview preview(Jwt jwt, PreviewRequest request) {
        CandidateReader reader = candidates.requireReader(jwt);
        if (AccessScope.read(actors.grants(reader.id()), PermissionModule.NOTIFICATIONS) == AccessScope.NONE) {
            throw new AccessDeniedException("No NOTIFICATIONS read permission");
        }
        List<UUID> ids = distinct(request.applicationIds());
        try {
            return transactions.read(() -> {
                EmailTemplate stored = templates.find(EmailTemplateType.REJECTION).orElseThrow(EmailTemplateService::notFound);
                String subject = request.subject() == null ? stored.subject() : request.subject();
                String body = request.body() == null ? stored.body() : request.body();
                Map<String, String> problems = TemplateText.problems(EmailTemplateType.REJECTION, subject, body);
                if (!problems.isEmpty()) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "EMAIL_TEMPLATE_INVALID",
                            "Mẫu thư từ chối chưa hợp lệ. Vui lòng kiểm tra tiêu đề và nội dung.", problems);
                }
                List<Selected> selected = select(reader, ids);
                requireRejected(selected);
                EmailTemplate draft = new EmailTemplate(EmailTemplateType.REJECTION, subject, body, stored.variables(),
                        stored.autoSend(), stored.version(), stored.updatedBy(), stored.updatedAt());
                List<PreviewEntry> entries = new ArrayList<>();
                for (Selected application : selected) {
                    Map<TemplateVariable, String> values = new EnumMap<>(TemplateVariable.class);
                    values.put(TemplateVariable.CANDIDATE_NAME, application.fullName());
                    values.put(TemplateVariable.POSITION_TITLE, application.postingTitle());
                    values.put(TemplateVariable.COMPANY_NAME, companyName);
                    EmailTemplate.Rendered rendered = draft.render(values);
                    MailLayout.Rendered mail = layout.render(CandidateNoticeMailContent.body(rendered.paragraphs()));
                    entries.add(new PreviewEntry(application.id(), application.fullName(), application.postingTitle(),
                            application.email(), rendered.subject(), mail.plainText(), mail.html(), application.letter()));
                    candidates.recordGranted(reader, CandidateAccessKind.PROFILE, application.candidateId(),
                            List.of(application.id()));
                }
                return new Preview(request.subject() != null || request.body() != null, stored.version(), entries);
            });
        } catch (ScopeDenied denied) {
            candidates.recordDenied(reader, CandidateAccessKind.PROFILE, denied.candidateId, denied.applicationId);
            throw new AccessDeniedException("A selected application is outside the caller's candidate scope");
        }
    }

    /**
     * The selected applications with their scope and rejection-letter state, in a fixed order; 404 when one does
     * not exist, ScopeDenied when one is not readable (checked before anything else is decided).
     */
    List<Selected> select(CandidateReader reader, List<UUID> ids) {
        List<Selected> found = jdbc.query("""
                SELECT a.id, ca.candidate_id, a.requisition_id, a.full_name, a.email, a.posting_title, a.stage,
                    (SELECT CASE WHEN bool_or(m.status IN ('QUEUED', 'SENDING', 'RETRY_WAIT', 'SENT')) THEN 'NOTIFIED'
                                 WHEN bool_or(m.status = 'FAILED') THEN 'FAILED' ELSE 'NONE' END
                     FROM email_messages m WHERE m.application_id = a.id AND m.template_type = 'REJECTION') AS letter,
                """ + CandidateAccess.READABLE + """
                 AS readable
                FROM job_applications a
                JOIN recruitment_requisitions r ON r.id = a.requisition_id
                JOIN candidate_applications ca ON ca.application_id = a.id
                WHERE a.id IN (:ids)
                ORDER BY a.requisition_id, a.id
                """, candidates.parameters(reader).addValue("ids", ids), (row, number) -> new Selected(
                row.getObject("id", UUID.class), row.getObject("candidate_id", UUID.class),
                row.getObject("requisition_id", UUID.class), row.getString("full_name"), row.getString("email"),
                row.getString("posting_title"), row.getString("stage"), row.getString("letter"),
                row.getBoolean("readable")));
        if (found.size() != ids.size()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", "Không tìm thấy một hoặc nhiều hồ sơ ứng tuyển.");
        }
        for (Selected application : found) {
            if (!application.readable()) throw new ScopeDenied(application.candidateId(), application.id());
        }
        return found;
    }

    static void requireRejected(List<Selected> selected) {
        List<String> others = selected.stream().filter(application -> !"REJECTED".equals(application.stage()))
                .map(application -> application.id().toString()).toList();
        if (!others.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "REJECTION_BATCH_NOT_REJECTED",
                    "Chỉ gửi thư từ chối cho các hồ sơ cùng ở trạng thái Loại. Hồ sơ chưa ở trạng thái Loại: "
                            + String.join(", ", others) + ".", Map.of("applicationIds", String.join(",", others)));
        }
    }

    static List<UUID> distinct(List<UUID> ids) {
        var distinct = new LinkedHashSet<>(ids);
        if (distinct.size() != ids.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Mỗi hồ sơ chỉ chọn một lần.",
                    Map.of("applicationIds", "Mỗi hồ sơ chỉ chọn một lần."));
        }
        return List.copyOf(distinct);
    }

    record Selected(UUID id, UUID candidateId, UUID requisitionId, String fullName, String email, String postingTitle,
                    String stage, String letter, boolean readable) {
        @Override public String toString() { return "RejectionSelection[" + id + ", personal data redacted]"; }
    }

    static final class ScopeDenied extends RuntimeException {
        final UUID candidateId;
        final UUID applicationId;

        ScopeDenied(UUID candidateId, UUID applicationId) {
            super(null, null, false, false);
            this.candidateId = candidateId;
            this.applicationId = applicationId;
        }
    }
}
