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

public final class OfferNegotiationRequests {
    private OfferNegotiationRequests() { }

    public record Note(
            @NotNull @Min(0) @JsonDeserialize(using = WholeVndDeserializer.class) Long expectedVersion,
            @NotNull @Pattern(regexp = "SALARY|LOCATION|OTHER_JOB|PERSONAL") String reason,
            @NotNull @Size(max = 2000) @PlainText(multiline = true) String comment) {
        public Note {
            comment = OfferRequests.text(comment);
        }

        @JsonAnySetter
        public void reject(String name, Object value) {
            throw new IllegalArgumentException("Unsupported negotiation field");
        }
    }

    public record Revision(
            @NotNull @Min(0) @JsonDeserialize(using = WholeVndDeserializer.class) Long expectedVersion,
            @NotNull @Min(0) @Max(PositionRequest.MAX_SALARY_VND)
            @JsonDeserialize(using = WholeVndDeserializer.class) Long salary,
            @NotNull @Min(0) @Max(PositionRequest.MAX_SALARY_VND)
            @JsonDeserialize(using = WholeVndDeserializer.class) Long allowance,
            @NotNull LocalDate startDate,
            @NotNull Instant responseDeadline,
            @Size(max = 2000) @PlainText(multiline = true) String explanation,
            @NotNull @Size(max = 2000) @PlainText(multiline = true) String comment) {
        public Revision {
            explanation = OfferRequests.text(explanation);
            comment = OfferRequests.text(comment);
        }

        OfferRequests.Draft draft() {
            return new OfferRequests.Draft(null, salary, allowance, startDate, responseDeadline, explanation);
        }

        @JsonAnySetter
        public void reject(String name, Object value) {
            throw new IllegalArgumentException("Unsupported revision field");
        }
    }
}
