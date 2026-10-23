package vn.ttcs.recruitment.candidate;

import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.screening.ScreeningView;

import java.time.Instant;
import java.util.UUID;

/**
 * Task 361 (story S4-09): one row of CandidateProfileRepository.readableApplications, an application the reader may
 * read, as stored. CandidateProfileView.of turns the rows into the response. No CV bytes or hash, tracking hash,
 * requisition id, normalized email or staff email is ever selected. toString shows ids only.
 */
record ReadableApplication(
        UUID id,
        UUID jobPostingId,
        String postingTitle,
        Instant receivedAt,
        ApplicationStage stage,
        String fullName,
        String email,
        String phone,
        String coverLetter,
        String departmentName,
        UUID managerId,
        String managerName,
        UUID primaryRecruiterId,
        String primaryRecruiterName,
        // The candidate-source catalog value's name, null when the candidate chose none.
        String sourceName,
        String cvFilename,
        String cvContentType,
        Integer cvSizeBytes,
        // Task 345: when the candidate withdrew and the optional reason, from the WITHDRAWN stage event; both null when
        // the application was not withdrawn by the candidate.
        Instant withdrawnAt,
        String withdrawalReason,
        // Task 350: the referral row (V36): the referrer's account id and current full name and the note; all three
        // null for an application that was not referred.
        UUID referrerId,
        String referrerName,
        String referralNote) {

    private static final String PDF = "application/pdf";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    ApplicationDetail detail(boolean current) {
        return detail(current, null);
    }

    ApplicationDetail detail(boolean current, ScreeningView screening) {
        return new ApplicationDetail(id, current, jobPostingId, postingTitle, departmentName, receivedAt, stage,
                stage.displayName(), withdrawnAt, withdrawalReason, fullName, email, phone, source(),
                primaryRecruiterId, primaryRecruiterName, managerId, managerName, coverLetter, cvFilename,
                cvFileType(cvContentType), cvSizeBytes, screening != null && screening.hasAssessment() ? screening : null);
    }

    // Task 350: a referral row makes the application an internal referral; otherwise it came through the career page.
    private ApplicationSource source() {
        return referrerId != null ? ApplicationSource.referral(referrerId, referrerName, referralNote)
                : ApplicationSource.careerPage(sourceName);
    }

    // The two types V26's CHECK application_cvs_valid_type allows; null when the application has no CV row. Any other
    // value means the schema changed without this mapping, so it fails loudly instead of showing a wrong type.
    static String cvFileType(String contentType) {
        if (contentType == null) {
            return null;
        }
        return switch (contentType) {
            case PDF -> "pdf";
            case DOCX -> "docx";
            default -> throw new IllegalStateException("Unknown stored CV type");
        };
    }

    @Override
    public String toString() {
        return "ReadableApplication[" + id + ", stage=" + stage + ", personal data redacted]";
    }
}
