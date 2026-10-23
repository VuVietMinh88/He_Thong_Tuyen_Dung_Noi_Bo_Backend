package vn.ttcs.recruitment.pipeline;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.companyprofile.PlainText;

import java.util.UUID;

/**
 * Task 380 (story S5-03): the body of POST /api/v1/applications/{id}/stage-changes. targetStage is the stage the
 * caller moves the application to; expectedCurrentStage is the stage the caller's page shows (the column a card was
 * dragged from), so a change made on an outdated view is refused instead of applied to a stage nobody looked at. Both
 * are ApplicationStage names, exactly as written (NEW, SCREENING...); whether the move is allowed is decided after the
 * locks (ApplicationStageTransitions).
 * <p>
 * Task 381: rejectionReasonId names the REJECTION_REASON catalog value of a rejection: required with targetStage
 * REJECTED, refused with any other target (both 400 before any lock). confirmSkipInterview is an HR_MANAGER's
 * confirmation of moving SCREENING straight to OFFER; it must be the JSON value true (StrictBooleanDeserializer);
 * absent, null and false are no confirmation, and it is ignored when the move skips nothing.
 * <p>
 * Task 382: note is the staff member's own reason for the change ("lý do"), optional for every change (the
 * rejection's catalog reason, the HR exception's justification, anything else): at most 500 characters, plain text
 * with line breaks; Windows line breaks become \n and a note without any visible character counts as none
 * (StageChangeText, the withdrawal reason's rules). It is internal: stored in the history row's reason (V34), never
 * shown to the candidate. Five fields, no others.
 */
public record ApplicationStageChangeRequest(
        @NotNull(message = "Vui lòng chọn giai đoạn mới.")
        @Pattern(regexp = STAGES, message = INVALID_STAGE)
        String targetStage,
        @NotNull(message = "Thiếu giai đoạn hiện tại của hồ sơ đang xem.")
        @Pattern(regexp = STAGES, message = INVALID_STAGE)
        String expectedCurrentStage,
        UUID rejectionReasonId,
        @JsonDeserialize(using = StrictBooleanDeserializer.class)
        Boolean confirmSkipInterview,
        @Size(max = 500, message = "Ghi chú tối đa 500 ký tự.")
        @PlainText(multiline = true, message = "Ghi chú chỉ được là văn bản thuần, không chứa thẻ HTML hoặc ký tự "
                + "điều khiển.")
        String note) {

    // Every ApplicationStage name; ApplicationStageTransitionsTest fails when the enum and this list differ.
    static final String STAGES = "NEW|SCREENING|INTERVIEW|OFFER|HIRED|REJECTED|WITHDRAWN";
    private static final String INVALID_STAGE = "Giai đoạn không hợp lệ.";

    public ApplicationStageChangeRequest {
        note = StageChangeText.invisibleToNull(StageChangeText.normalizeLineBreaks(note));
    }

    // Only call them after @Valid has accepted the request: both are then stage names.
    ApplicationStage target() {
        return ApplicationStage.valueOf(targetStage);
    }

    ApplicationStage expected() {
        return ApplicationStage.valueOf(expectedCurrentStage);
    }

    // Absent and null mean no confirmation.
    boolean confirmsInterviewSkip() {
        return Boolean.TRUE.equals(confirmSkipInterview);
    }

    // The actor, the time and the stage before are decided by the server, so a client cannot send them.
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported application stage change field");
    }

    @Override public String toString() { return "ApplicationStageChangeRequest[" + targetStage + ", note redacted]"; }
}
