package vn.ttcs.recruitment.headcount;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.ttcs.recruitment.position.WholeVndDeserializer;
import vn.ttcs.recruitment.requisition.WholeHeadcountDeserializer;

import java.util.UUID;

// Task 273: what HR types to declare the plan of one department for one year (POST). Numbers must be JSON whole
// numbers: 2026.5 or "5" fail as INVALID_JSON instead of being rounded. salaryBudget may be left out or null,
// which means the year has no budget limit.
public record HeadcountPlanRequest(
        @NotNull(message = "Phòng ban không được để trống.") UUID departmentId,
        @NotNull(message = "Năm kế hoạch không được để trống.")
        @Min(value = HeadcountPlanRequest.MIN_YEAR, message = HeadcountPlanRequest.YEAR_MESSAGE)
        @Max(value = HeadcountPlanRequest.MAX_YEAR, message = HeadcountPlanRequest.YEAR_MESSAGE)
        @JsonDeserialize(using = WholeHeadcountDeserializer.class) Integer year,
        @NotNull(message = "Chỉ tiêu headcount không được để trống.")
        @PositiveOrZero(message = "Chỉ tiêu headcount không được âm.")
        @Max(value = HeadcountPlanRequest.MAX_HEADCOUNT_LIMIT, message = HeadcountPlanRequest.LIMIT_MESSAGE)
        @JsonDeserialize(using = WholeHeadcountDeserializer.class) Integer headcountLimit,
        @PositiveOrZero(message = "Ngân sách lương không được âm.")
        @Max(value = HeadcountPlanRequest.MAX_SALARY_BUDGET_VND, message = HeadcountPlanRequest.BUDGET_MESSAGE)
        @JsonDeserialize(using = WholeVndDeserializer.class) Long salaryBudget) {

    // The years V14 accepts (CHECK valid_headcount_plan_year).
    static final int MIN_YEAR = 2000;
    static final int MAX_YEAR = 2100;
    static final String YEAR_MESSAGE = "Năm kế hoạch phải từ 2000 đến 2100.";
    // A typing guard like the 999 people of one requisition: no department plans 100 000 people a year.
    static final int MAX_HEADCOUNT_LIMIT = 99_999;
    static final String LIMIT_MESSAGE = "Chỉ tiêu headcount tối đa 99.999 người.";
    // 1 triệu tỷ đồng a year. Far below Long.MAX_VALUE, so "budget - used" never overflows, and still exact as a
    // JavaScript number in the frontend (below 2^53).
    static final long MAX_SALARY_BUDGET_VND = 1_000_000_000_000_000L;
    static final String BUDGET_MESSAGE = "Ngân sách lương tối đa 1.000.000 tỷ đồng.";

    // id, usage, updatedBy... are decided by the server, so a client cannot send them.
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported headcount plan field");
    }
}
