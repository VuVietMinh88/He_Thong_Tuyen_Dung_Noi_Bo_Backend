package vn.ttcs.recruitment.candidate;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import vn.ttcs.recruitment.application.ApplicationStage;

import java.time.Instant;
import java.util.UUID;

/**
 * Task 361 (story S4-09): one application of the unified profile, with the frontend's ApplicationRecord names. Only an
 * application the reader may read (CandidateAccess.READABLE) ever becomes one of these.
 * <p>
 * It carries the application's own snapshot: the posting title when it was received (jobTitle), the name, the email
 * as typed and the phone the submitter entered, the cover letter. Linking uses an unverified email (task 360), so these
 * values belong to this application only and the profile never copies one application's contact data onto another.
 * departmentName is the department's current name. recruiterId/recruiterName name the requisition's current PRIMARY
 * recruiter and are left out when it has none; hiringManagerId/hiringManagerName name the direct manager of the
 * requisition's department. Staff appear as id and full name, never an email. The CV is metadata only: the file name
 * as uploaded, pdf or docx from the stored type, the size in bytes; the bytes are task 362's GET
 * /applications/{id}/cv.
 * <p>
 * Task 345 (story S4-06): withdrawnAt and withdrawalReason (the frontend's ApplicationRecord name) come from the
 * candidate's withdrawal (V34); a reader sees them only for an application in their scope, like everything here.
 * <p>
 * Never here: a CV URL, the CV bytes or hash, the tracking code or its hash, the requisition id, the normalized email,
 * any staff email, a candidate code or a rating. NON_NULL leaves out what an application does not have (cover letter,
 * recruiter, CV, withdrawal). toString shows ids only.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicationDetail(
        UUID id,
        // The newest application of the profile (received_at, then id), the one the frontend opens first.
        @JsonProperty("isCurrent") boolean isCurrent,
        UUID jobPostingId,
        String jobTitle,
        String departmentName,
        Instant appliedAt,
        ApplicationStage stage,
        String stageName,
        // Task 345: when the candidate withdrew the application and the reason they gave (optional, at most 500
        // characters, as typed, line breaks included); left out unless the candidate withdrew it.
        Instant withdrawnAt,
        String withdrawalReason,
        String fullName,
        String email,
        String phone,
        ApplicationSource source,
        UUID recruiterId,
        String recruiterName,
        UUID hiringManagerId,
        String hiringManagerName,
        String coverLetter,
        String cvFileName,
        String cvFileType,
        Integer cvFileSize) {

    @Override
    public String toString() {
        return "ApplicationDetail[" + id + ", stage=" + stage + ", personal data redacted]";
    }
}
