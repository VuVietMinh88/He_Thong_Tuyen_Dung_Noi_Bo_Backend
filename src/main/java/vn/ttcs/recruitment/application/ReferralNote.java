package vn.ttcs.recruitment.application;

import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.companyprofile.PlainText;

import java.util.Map;

/**
 * Task 350 (story S4-07): the rule of the referral note, the referrer's free text about the candidate. Shared by the
 * storage (ReferralApplicationStorage) and, from task 351, the referral API, which checks it before its attempt quota.
 * <p>
 * Windows (\r\n) and old Mac (\r) line breaks become \n; a note without a visible character counts as no note
 * (ApplicationText, the withdrawal reason's rules); the note is required, at most 2000 characters (Java's length, the
 * frontend's count; V36's VARCHAR(2000) counts characters, never more) and plain text with line breaks
 * (@PlainText(multiline = true): no HTML tag start, no control character other than \n and tab, no bidi control). One
 * message per failure, in that order, under the field name of the referral form.
 */
final class ReferralNote {
    static final String FIELD = "referralNote";
    static final int MAX_LENGTH = 2000;
    static final String REQUIRED = "Vui lòng nhập ghi chú giới thiệu.";
    static final String TOO_LONG = "Ghi chú giới thiệu tối đa 2.000 ký tự.";
    static final String NOT_PLAIN = "Ghi chú giới thiệu chỉ được là văn bản thuần, không chứa thẻ HTML hoặc ký tự "
            + "điều khiển.";

    private ReferralNote() {
    }

    // The note as stored, or 400 VALIDATION_ERROR naming referralNote. The error never echoes the note.
    static String validated(Validator validator, String raw) {
        String note = ApplicationText.invisibleToNull(ApplicationText.normalizeLineBreaks(raw));
        String error;
        if (note == null) {
            error = REQUIRED;
        } else if (note.length() > MAX_LENGTH) {
            error = TOO_LONG;
        } else if (!validator.validate(new Text(note)).isEmpty()) {
            error = NOT_PLAIN;
        } else {
            return note;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra ghi chú giới thiệu.",
                Map.of(FIELD, error));
    }

    // The house's plain-text rule, run through the Bean Validation of the caller.
    private record Text(@PlainText(multiline = true) String value) {
        @Override public String toString() { return "ReferralNote.Text[redacted]"; }
    }
}
