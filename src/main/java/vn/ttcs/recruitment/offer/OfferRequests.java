package vn.ttcs.recruitment.offer;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.ttcs.recruitment.companyprofile.PlainText;
import vn.ttcs.recruitment.position.PositionRequest;
import vn.ttcs.recruitment.position.WholeVndDeserializer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class OfferRequests {
    private OfferRequests() {
    }

    public record Draft(@Min(0) @JsonDeserialize(using = WholeVndDeserializer.class) Long expectedVersion,
        @NotNull @Min(0) @Max(PositionRequest.MAX_SALARY_VND) @JsonDeserialize(using = WholeVndDeserializer.class) Long salary,
        @NotNull @Min(0) @Max(PositionRequest.MAX_SALARY_VND) @JsonDeserialize(using = WholeVndDeserializer.class) Long allowance,
        @NotNull LocalDate startDate, @NotNull Instant responseDeadline,
        @Size(max = 2000) @PlainText(multiline = true) String explanation) {
        public Draft {
            explanation = text(explanation);
        }
        @JsonAnySetter public void reject(String name, Object value) {
            throw new IllegalArgumentException("Unsupported offer field");
        }
    }

    public record Submit(@NotNull @Min(0) @JsonDeserialize(using = WholeVndDeserializer.class) Long expectedVersion,
        @NotNull UUID hrApproverId, UUID higherApproverId) {
        @JsonAnySetter public void reject(String name, Object value) {
            throw new IllegalArgumentException("Unsupported submission field");
        }
    }

    public record Decision(@NotNull @Min(0) @JsonDeserialize(using = WholeVndDeserializer.class) Long expectedVersion,
        @NotNull @Pattern(regexp = "APPROVE|REJECT|REQUEST_CHANGES") String action,
        @Size(max = 2000) @PlainText(multiline = true) String comment) {
        public Decision {
            comment = text(comment);
        }
        @JsonAnySetter public void reject(String name, Object value) {
            throw new IllegalArgumentException("Unsupported decision field");
        }
    }

    public record Cancel(@NotNull @Min(0) @JsonDeserialize(using = WholeVndDeserializer.class) Long expectedVersion,
        @Size(max = 2000) @PlainText(multiline = true) String reason) {
        public Cancel {
            reason = text(reason);
        }
        @JsonAnySetter public void reject(String name, Object value) {
            throw new IllegalArgumentException("Unsupported cancellation field");
        }
    }

    static String text(String value) {
        if (value == null) return null;
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n').strip();
        return normalized.codePoints().anyMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c)
            && !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT) ? normalized : null;
    }
}
