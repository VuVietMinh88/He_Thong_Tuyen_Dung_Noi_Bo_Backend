package vn.ttcs.recruitment.requisition;

import org.springframework.http.HttpStatus;
import vn.ttcs.recruitment.common.ApiException;

import java.util.Map;

// Task 291: 409 REQUISITION_HAS_ACTIVE_CANDIDATES. ApiExceptionHandler answers with the three keys of every error and
// the pipeline, so the dialog can show who is left without asking again.
public class ActivePipelineConflictException extends ApiException {
    public static final String CODE = "REQUISITION_HAS_ACTIVE_CANDIDATES";

    private final ActivePipeline activePipeline;

    public ActivePipelineConflictException(RequisitionStatusChange change, ActivePipeline activePipeline) {
        super(HttpStatus.CONFLICT, CODE, message(change, activePipeline.count()));
        this.activePipeline = activePipeline;
    }

    public ActivePipeline activePipeline() {
        return activePipeline;
    }

    public Body body() {
        return new Body(getCode(), getMessage(), getFieldErrors(), activePipeline);
    }

    // The response: the three keys of ApiError, then the pipeline.
    public record Body(String code, String message, Map<String, String> fieldErrors, ActivePipeline activePipeline) { }

    static String message(RequisitionStatusChange change, int count) {
        String closing = switch (change) {
            case FILL -> "đóng yêu cầu vì đã tuyển đủ";
            case CANCEL -> "hủy yêu cầu";
            case PAUSE, RESUME -> throw new IllegalArgumentException(change + " needs no confirmation");
        };
        // "các" marks a plural, so one candidate is "ứng viên này".
        String candidates = count == 1 ? "ứng viên này" : "các ứng viên này";
        return "Yêu cầu tuyển dụng còn " + count + " ứng viên đang trong quy trình tuyển dụng. Vui lòng xử lý "
                + candidates + " trước, hoặc xác nhận vẫn " + closing + ".";
    }
}
