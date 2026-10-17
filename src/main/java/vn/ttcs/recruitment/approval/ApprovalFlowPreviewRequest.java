package vn.ttcs.recruitment.approval;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

public record ApprovalFlowPreviewRequest(
        @NotNull UUID departmentId,
        @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 0) BigDecimal proposedSalary) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) { throw new IllegalArgumentException("Unknown preview field"); }
}
