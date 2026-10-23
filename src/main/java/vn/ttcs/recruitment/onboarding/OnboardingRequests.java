package vn.ttcs.recruitment.onboarding;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.ttcs.recruitment.companyprofile.PlainText;
import vn.ttcs.recruitment.position.WholeVndDeserializer;
import vn.ttcs.recruitment.requisition.WholeHeadcountDeserializer;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The client sends business values only; snapshot identity and completion metadata are server-owned. */
public final class OnboardingRequests {
    private OnboardingRequests() { }

    public record TemplateItem(
            @NotBlank @Size(max = 200) @PlainText String title,
            @NotNull UUID assigneeId,
            @NotNull @Min(-30) @Max(365) @JsonDeserialize(using = WholeHeadcountDeserializer.class) Integer dueOffsetDays) {
        public TemplateItem { title = text(title); }
        @JsonAnySetter public void reject(String name, Object value) { throw unknown(); }
    }

    public record CreateTemplate(
            @NotNull UUID departmentId,
            @NotBlank @Size(max = 120) @PlainText String name,
            @NotNull @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean active,
            @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid TemplateItem> items) {
        public CreateTemplate { name = text(name); }
        @JsonAnySetter public void reject(String name, Object value) { throw unknown(); }
    }

    public record UpdateTemplate(
            @NotNull @Min(0) @JsonDeserialize(using = WholeVndDeserializer.class) Long expectedVersion,
            @NotBlank @Size(max = 120) @PlainText String name,
            @NotNull @JsonDeserialize(using = StrictBooleanDeserializer.class) Boolean active,
            @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid TemplateItem> items) {
        public UpdateTemplate { name = text(name); }
        @JsonAnySetter public void reject(String name, Object value) { throw unknown(); }
    }

    public record CreateChecklist(
            @NotNull UUID templateId,
            @NotNull @Min(0) @JsonDeserialize(using = WholeVndDeserializer.class) Long expectedTemplateVersion,
            @NotNull LocalDate startDate) {
        @JsonAnySetter public void reject(String name, Object value) { throw unknown(); }
    }

    public record UpdateItem(
            @NotNull @Min(0) @JsonDeserialize(using = WholeVndDeserializer.class) Long expectedVersion,
            @NotBlank @Size(max = 200) @PlainText String title,
            @NotNull UUID assigneeId,
            @NotNull LocalDate dueDate,
            @NotNull @Pattern(regexp = "PENDING|IN_PROGRESS|DONE") String status) {
        public UpdateItem { title = text(title); }
        @JsonAnySetter public void reject(String name, Object value) { throw unknown(); }
    }

    private static String text(String value) {
        return value == null ? null : value.strip();
    }

    private static IllegalArgumentException unknown() {
        return new IllegalArgumentException("Unsupported onboarding field");
    }
}
