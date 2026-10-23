package vn.ttcs.recruitment.pipeline;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import vn.ttcs.recruitment.application.ApplicationStage;

/**
 * Task 380 (story S5-03): the body of POST /api/v1/applications/{id}/stage-changes. targetStage is the stage the
 * caller moves the application to; expectedCurrentStage is the stage the caller's page shows (the column a card was
 * dragged from), so a change made on an outdated view is refused instead of applied to a stage nobody looked at. Both
 * are ApplicationStage names, exactly as written (NEW, SCREENING...); whether the move is allowed is decided after the
 * locks (ApplicationStageTransitions). Two fields, no others.
 */
public record ApplicationStageChangeRequest(
        @NotNull(message = "Vui lòng chọn giai đoạn mới.")
        @Pattern(regexp = STAGES, message = INVALID_STAGE)
        String targetStage,
        @NotNull(message = "Thiếu giai đoạn hiện tại của hồ sơ đang xem.")
        @Pattern(regexp = STAGES, message = INVALID_STAGE)
        String expectedCurrentStage) {

    // Every ApplicationStage name; ApplicationStageTransitionsTest fails when the enum and this list differ.
    static final String STAGES = "NEW|SCREENING|INTERVIEW|OFFER|HIRED|REJECTED|WITHDRAWN";
    private static final String INVALID_STAGE = "Giai đoạn không hợp lệ.";

    // Only call them after @Valid has accepted the request: both are then stage names.
    ApplicationStage target() {
        return ApplicationStage.valueOf(targetStage);
    }

    ApplicationStage expected() {
        return ApplicationStage.valueOf(expectedCurrentStage);
    }

    // The actor, the time and the stage before are decided by the server, so a client cannot send them.
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported application stage change field");
    }
}
