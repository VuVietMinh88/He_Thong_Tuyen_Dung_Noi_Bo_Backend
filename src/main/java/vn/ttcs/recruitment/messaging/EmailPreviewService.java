package vn.ttcs.recruitment.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import vn.ttcs.recruitment.application.ApplicationConfirmationMailSender;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateAccessKind;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.invitation.InterviewInvitationSettings;
import vn.ttcs.recruitment.invitation.InterviewMailContent;
import vn.ttcs.recruitment.invitation.InterviewMailPayload;
import vn.ttcs.recruitment.mail.MailBody;
import vn.ttcs.recruitment.mail.MailLayout;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 532 (story S8-01, "Xem trước với dữ liệu thật trước khi lưu"): a template, saved or an unsaved draft, filled in
 * with the real data of one application the caller may read.
 * <p>
 * Who: NOTIFICATIONS_READ_ALL (templates) and a CANDIDATES read permission, both read again from the database, then the
 * application's scope (CandidateAccess.READABLE): 404 for an unknown application, 403 for one outside the caller's
 * scope (logged as a refused profile read); a shown preview is logged as a granted profile read, like every disclosure
 * of candidate data. The draft must pass the same rules as a save (400 EMAIL_TEMPLATE_INVALID). Interview types use
 * the given interview of that application (404 INTERVIEW_NOT_FOUND otherwise), else its next scheduled interview, else
 * its latest one; an application without any interview is 409 EMAIL_PREVIEW_INTERVIEW_REQUIRED.
 * <p>
 * The text below the template is the same mandatory part the real email adds, except that the candidate's personal
 * code and links, the meeting link and the offer terms are replaced by bracketed placeholders: the preview is
 * readable by people who may not hold OFFERS permissions and is never a way to obtain a capability. Nothing is sent and
 * nothing is stored apart from the access log.
 */
@Service
public class EmailPreviewService {
    static final String TRACKING_CODE = "[mã tra cứu riêng của ứng viên]";
    static final String TRACKING_LINK = "[liên kết tra cứu riêng của ứng viên]";
    static final String WITHDRAWAL_LINK = "[liên kết rút hồ sơ riêng của ứng viên]";
    static final String OFFER_TERM = "[theo offer đã được duyệt]";

    private final CandidateAccess candidates;
    private final MessagingActors actors;
    private final MessagingTransactions transactions;
    private final EmailTemplateRepository templates;
    private final NamedParameterJdbcTemplate jdbc;
    private final MailLayout layout;
    private final InterviewInvitationSettings invitations;
    private final Clock clock;
    private final ZoneId zone;
    private final String companyName;
    private final String from;

    public EmailPreviewService(CandidateAccess candidates, MessagingActors actors, MessagingTransactions transactions,
                               EmailTemplateRepository templates, NamedParameterJdbcTemplate jdbc, MailLayout layout,
                               InterviewInvitationSettings invitations, Clock clock,
                               @Value("${app.business-zone}") ZoneId zone,
                               @Value("${app.mail.company-name}") String companyName,
                               @Value("${app.mail.from}") String from) {
        this.candidates = candidates;
        this.actors = actors;
        this.transactions = transactions;
        this.templates = templates;
        this.jdbc = jdbc;
        this.layout = layout;
        this.invitations = invitations;
        this.clock = clock;
        this.zone = zone;
        this.companyName = companyName;
        this.from = from;
    }

    public EmailPreviewView preview(Jwt jwt, String typeName, EmailTemplateRequests.Preview request) {
        CandidateReader reader = candidates.requireReader(jwt);
        actors.requireAll(reader.id(), EmailTemplateService.READ);
        EmailTemplateType type = EmailTemplateService.type(typeName);
        try {
            return transactions.read(() -> previewInSnapshot(reader, type, request));
        } catch (ScopeDenied denied) {
            candidates.recordDenied(reader, CandidateAccessKind.PROFILE, denied.candidateId, request.applicationId());
            throw new AccessDeniedException("The application is outside the caller's candidate scope");
        }
    }

    private EmailPreviewView previewInSnapshot(CandidateReader reader, EmailTemplateType type,
                                               EmailTemplateRequests.Preview request) {
        EmailTemplate stored = templates.find(type).orElseThrow(EmailTemplateService::notFound);
        String subject = request.subject() == null ? stored.subject() : request.subject();
        String body = request.body() == null ? stored.body() : request.body();
        Map<String, String> problems = TemplateText.problems(type, subject, body);
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMAIL_TEMPLATE_INVALID",
                    "Mẫu email chưa hợp lệ. Vui lòng kiểm tra tiêu đề và nội dung.", problems);
        }
        Application application = application(reader, request.applicationId());
        if (!application.readable()) throw new ScopeDenied(application.candidateId());
        Interview interview = type.needsInterview() ? interview(application.id(), request.interviewId()) : null;

        Map<TemplateVariable, String> values = new EnumMap<>(TemplateVariable.class);
        values.put(TemplateVariable.CANDIDATE_NAME, application.fullName());
        values.put(TemplateVariable.POSITION_TITLE, application.postingTitle());
        values.put(TemplateVariable.COMPANY_NAME, companyName);
        if (interview != null) {
            values.put(TemplateVariable.INTERVIEW_TIME, CandidateMailFormat.interviewTime(interview.startsAt(), zone));
        }
        EmailTemplate.Rendered rendered = new EmailTemplate(type, subject, body, stored.variables(), stored.autoSend(),
                stored.version(), stored.updatedBy(), stored.updatedAt()).render(values);

        String text;
        String html = null;
        if (type == EmailTemplateType.APPLICATION_RECEIPT) {
            text = ReceiptMailText.compose(rendered.paragraphs(),
                    application.referred() ? ApplicationConfirmationMailSender.referralParagraph(from) : null,
                    application.postingTitle(), CandidateMailFormat.receivedAt(application.receivedAt(), zone),
                    TRACKING_CODE, TRACKING_LINK, WITHDRAWAL_LINK);
        } else {
            MailLayout.Rendered mail = layout.render(body(type, rendered, application, interview));
            text = mail.plainText();
            html = mail.html();
        }
        candidates.recordGranted(reader, CandidateAccessKind.PROFILE, application.candidateId(),
                List.of(application.id()));
        return new EmailPreviewView(type.name(), application.id(), interview == null ? null : interview.id(),
                request.subject() != null || request.body() != null, stored.autoSend(), stored.version(),
                rendered.subject(), text, html);
    }

    private MailBody body(EmailTemplateType type, EmailTemplate.Rendered rendered, Application application,
                          Interview interview) {
        return switch (type) {
            case APPLICATION_RECEIPT -> throw new IllegalArgumentException("The receipt is a plain-text email");
            case INTERVIEW_INVITATION -> {
                var payload = new InterviewMailPayload(null, application.fullName(), application.postingTitle(),
                        interview.roundName(), interview.startsAt(), interview.endsAt(), interview.mode(),
                        interview.room(), null, interview.preparation(), "INVITATION", null, null);
                List<MailBody.Block> blocks = new ArrayList<>(
                        InterviewMailContent.body(payload, invitations.zone(), rendered.paragraphs()).blocks());
                blocks.add(new MailBody.Paragraph("[Phần riêng của ứng viên, chỉ có trong email thật: liên kết phản hồi"
                        + " lời mời" + ("ONLINE".equals(interview.mode()) ? " và liên kết phòng họp trực tuyến" : "")
                        + ".]"));
                yield new MailBody(blocks);
            }
            case INTERVIEW_THANKS, REJECTION -> CandidateNoticeMailContent.body(rendered.paragraphs());
            case OFFER -> {
                MailBody.Builder offer = MailBody.builder();
                rendered.paragraphs().forEach(offer::paragraph);
                yield offer.field("Vị trí", TemplateText.oneLine(application.postingTitle()))
                        .field("Lương cơ bản hằng tháng", OFFER_TERM)
                        .field("Phụ cấp hằng tháng", OFFER_TERM)
                        .field("Ngày bắt đầu làm việc", OFFER_TERM)
                        .field("Hạn phản hồi", OFFER_TERM)
                        .paragraph("Lương cơ bản và phụ cấp ở trên là số tiền theo tháng, tính bằng đồng Việt Nam (VND).")
                        .paragraph("Vui lòng cho biết bạn chấp nhận hoặc từ chối lời mời nhận việc này trước hạn phản"
                                + " hồi ở trên.")
                        .paragraph("[Phần riêng của ứng viên, chỉ có trong email thật: liên kết chấp nhận hoặc từ chối"
                                + " lời mời.]")
                        .paragraph("Nếu có thắc mắc, hãy liên hệ bộ phận tuyển dụng qua thông tin hỗ trợ ở cuối email."
                                + " Vui lòng không chuyển tiếp email này cho người khác.")
                        .build();
            }
        };
    }

    private Application application(CandidateReader reader, UUID applicationId) {
        return jdbc.query("""
                SELECT a.id, ca.candidate_id, a.full_name, a.posting_title, a.received_at,
                    EXISTS (SELECT 1 FROM application_referrals f WHERE f.application_id = a.id) AS referred,
                    """ + CandidateAccess.READABLE + """
                     AS readable
                FROM job_applications a
                JOIN recruitment_requisitions r ON r.id = a.requisition_id
                JOIN candidate_applications ca ON ca.application_id = a.id
                WHERE a.id = :application
                """, candidates.parameters(reader).addValue("application", applicationId),
                (row, number) -> new Application(row.getObject("id", UUID.class),
                        row.getObject("candidate_id", UUID.class), row.getString("full_name"),
                        row.getString("posting_title"), row.getTimestamp("received_at").toInstant(),
                        row.getBoolean("referred"), row.getBoolean("readable")))
                .stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND",
                        "Không tìm thấy hồ sơ ứng tuyển."));
    }

    private Interview interview(UUID applicationId, UUID requested) {
        List<Interview> interviews = jdbc.query("""
                SELECT id, round_name, starts_at, ends_at, mode, room, preparation_instructions, status
                FROM interview_sessions WHERE application_id = :application
                ORDER BY starts_at, id LIMIT 200
                """, Map.of("application", applicationId), (row, number) -> new Interview(
                row.getObject("id", UUID.class), row.getString("round_name"), row.getTimestamp("starts_at").toInstant(),
                row.getTimestamp("ends_at").toInstant(), row.getString("mode"), row.getString("room"),
                row.getString("preparation_instructions"), row.getString("status")));
        if (requested != null) {
            return interviews.stream().filter(interview -> interview.id().equals(requested)).findFirst()
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "INTERVIEW_NOT_FOUND",
                            "Không tìm thấy lịch phỏng vấn của hồ sơ này."));
        }
        Instant now = clock.instant();
        return interviews.stream()
                .filter(interview -> "SCHEDULED".equals(interview.status()) && interview.startsAt().isAfter(now))
                .findFirst()
                .or(() -> interviews.stream().max(Comparator.comparing(Interview::startsAt)))
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "EMAIL_PREVIEW_INTERVIEW_REQUIRED",
                        "Hồ sơ chưa có lịch phỏng vấn nên chưa có dữ liệu thời gian phỏng vấn để xem trước."));
    }

    private record Application(UUID id, UUID candidateId, String fullName, String postingTitle, Instant receivedAt,
                               boolean referred, boolean readable) {
        @Override public String toString() { return "PreviewApplication[" + id + ", personal data redacted]"; }
    }

    private record Interview(UUID id, String roundName, Instant startsAt, Instant endsAt, String mode, String room,
                             String preparation, String status) {
    }

    private static final class ScopeDenied extends RuntimeException {
        private final UUID candidateId;

        ScopeDenied(UUID candidateId) {
            super(null, null, false, false);
            this.candidateId = candidateId;
        }
    }
}
