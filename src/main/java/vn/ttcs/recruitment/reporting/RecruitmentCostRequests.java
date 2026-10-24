package vn.ttcs.recruitment.reporting;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.ttcs.recruitment.companyprofile.PlainText;
import vn.ttcs.recruitment.position.WholeVndDeserializer;

import java.time.LocalDate;
import java.util.UUID;

/** Task 573: the bodies of the cost API. The recorder and the times come from the server, never from the client. */
public final class RecruitmentCostRequests {
    static final long MAX_AMOUNT_VND = 100_000_000_000L;

    private RecruitmentCostRequests() {
    }

    public record Create(
            @NotNull(message = "Yêu cầu tuyển dụng không được để trống.") UUID requisitionId,
            @NotNull(message = "Loại chi phí không được để trống.")
            @Pattern(regexp = "ADVERTISING|REFERRAL_BONUS", message = "Loại chi phí phải là ADVERTISING hoặc REFERRAL_BONUS.")
            String costType,
            @NotNull(message = "Số tiền không được để trống.")
            @Min(value = 1, message = "Số tiền phải lớn hơn 0.")
            @Max(value = MAX_AMOUNT_VND, message = "Số tiền tối đa 100.000.000.000 VND mỗi khoản.")
            @JsonDeserialize(using = WholeVndDeserializer.class) Long amountVnd,
            @NotNull(message = "Ngày phát sinh không được để trống.") LocalDate incurredOn,
            @Size(max = 500, message = "Mô tả tối đa 500 ký tự.") @PlainText String description) {
        public Create {
            description = description == null || description.strip().isEmpty() ? null : description.strip();
        }

        @JsonAnySetter
        public void reject(String name, Object value) {
            throw new IllegalArgumentException("Unsupported recruitment cost field");
        }
    }

    public record VoidCost(
            @NotBlank(message = "Lý do hủy không được để trống.")
            @Size(max = 500, message = "Lý do hủy tối đa 500 ký tự.") @PlainText String reason) {
        public VoidCost {
            reason = reason == null ? null : reason.strip();
        }

        @JsonAnySetter
        public void reject(String name, Object value) {
            throw new IllegalArgumentException("Unsupported recruitment cost field");
        }
    }
}
