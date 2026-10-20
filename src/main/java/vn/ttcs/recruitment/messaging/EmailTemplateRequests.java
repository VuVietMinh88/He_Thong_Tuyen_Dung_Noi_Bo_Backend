package vn.ttcs.recruitment.messaging;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.ttcs.recruitment.companyprofile.PlainText;
import vn.ttcs.recruitment.position.WholeVndDeserializer;

import java.util.UUID;

/** Task 532: the bodies of the template API. Unknown fields are refused (INVALID_JSON). */
public final class EmailTemplateRequests {
    private EmailTemplateRequests() {
    }

    /**
     * A complete replacement of the editable part. The subject keeps its characters exactly (a CR or LF is refused);
     * Windows line breaks of the body are stored as \n.
     */
    public record Update(
            @NotNull @Min(0) @JsonDeserialize(using = WholeVndDeserializer.class) Long expectedVersion,
            @NotBlank @Size(max = TemplateText.MAX_SUBJECT_LENGTH) @PlainText String subject,
            @NotBlank @Size(max = TemplateText.MAX_BODY_LENGTH) @PlainText(multiline = true) String body,
            @NotNull @JsonDeserialize(using = MessagingBooleanDeserializer.class) Boolean autoSend) {
        public Update {
            body = lines(body);
        }

        @JsonAnySetter public void reject(String name, Object value) { throw unknown(); }
    }

    /**
     * A preview with the real data of one application (and one of its interviews for the interview types). subject
     * and body are an unsaved draft; when absent the stored text is used.
     */
    public record Preview(
            @NotNull UUID applicationId,
            UUID interviewId,
            @Size(max = TemplateText.MAX_SUBJECT_LENGTH) @PlainText String subject,
            @Size(max = TemplateText.MAX_BODY_LENGTH) @PlainText(multiline = true) String body) {
        public Preview {
            body = lines(body);
        }

        @JsonAnySetter public void reject(String name, Object value) { throw unknown(); }
    }

    private static String lines(String value) {
        return value == null ? null : value.replace("\r\n", "\n");
    }

    private static IllegalArgumentException unknown() {
        return new IllegalArgumentException("Unsupported email template field");
    }
}
