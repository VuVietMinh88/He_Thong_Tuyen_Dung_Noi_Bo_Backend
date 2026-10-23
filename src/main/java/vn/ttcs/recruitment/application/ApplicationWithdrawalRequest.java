package vn.ttcs.recruitment.application;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.ttcs.recruitment.companyprofile.PlainText;

/**
 * Task 345 (story S4-06, "Rút hồ sơ qua liên kết trong email xác nhận, có xác nhận lại trước khi thực hiện"): the body
 * of POST /api/v1/public/applications/withdraw. The credential is the lookup's (task 339): the tracking code of one
 * application and the email it was sent from, trimmed with the intake's rule, only in this JSON body. confirm must be
 * the JSON value true (the text "true" or the number 1 is INVALID_JSON): the frontend sends it from the dialog that
 * asks the candidate a second time, so a link opened by a mail scanner, or a form sent without that dialog, withdraws
 * nothing. reason is optional (at most 500 characters, plain text, line breaks allowed); Windows line breaks become \n
 * and a reason without any visible character counts as none. Four fields, no others.
 */
public record ApplicationWithdrawalRequest(
        @NotBlank(message = "Vui lòng nhập mã tra cứu.")
        @Pattern(regexp = TrackingCode.PATTERN, message = "Mã tra cứu gồm đúng 43 ký tự chữ, số, dấu '-' hoặc '_'.")
        String trackingCode,
        @NotBlank(message = "Vui lòng nhập email đã dùng khi nộp hồ sơ.")
        @Email(message = "Email không hợp lệ.")
        @Size(max = 254, message = "Email tối đa 254 ký tự.")
        String email,
        @Size(max = 500, message = "Lý do tối đa 500 ký tự.")
        @PlainText(multiline = true)
        String reason,
        // Missing, null and false are refused with one message: only an explicit true confirms. A text or a number
        // such as "true" or 1 is not coerced: StrictBooleanDeserializer fails it as INVALID_JSON.
        @NotNull(message = CONFIRM_REQUIRED)
        @AssertTrue(message = CONFIRM_REQUIRED)
        @JsonDeserialize(using = StrictBooleanDeserializer.class)
        Boolean confirm) {

    static final String CONFIRM_REQUIRED = "Vui lòng xác nhận rút hồ sơ.";

    public ApplicationWithdrawalRequest {
        trackingCode = ApplicationSubmission.trim(trackingCode);
        email = ApplicationSubmission.trim(email);
        reason = ApplicationText.invisibleToNull(ApplicationText.normalizeLineBreaks(reason));
    }

    // The server decides the stage, the time and the actor, so a client cannot send them or any other field.
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported application withdrawal field");
    }

    @Override public String toString() { return "ApplicationWithdrawalRequest[tracking code, email and reason redacted]"; }
}
