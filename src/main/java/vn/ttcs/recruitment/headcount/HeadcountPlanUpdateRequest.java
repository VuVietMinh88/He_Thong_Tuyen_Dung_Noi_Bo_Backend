package vn.ttcs.recruitment.headcount;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.databind.annotation.JsonDeserialize;
import vn.ttcs.recruitment.position.WholeVndDeserializer;
import vn.ttcs.recruitment.requisition.WholeHeadcountDeserializer;

// Task 273: PUT replaces the two numbers of a plan. The department and the year never change: sending them fails as
// INVALID_JSON, because a plan for another department or year is another plan (POST). Leaving salaryBudget out or
// sending null removes the budget limit.
public record HeadcountPlanUpdateRequest(
        @NotNull(message = "Chỉ tiêu headcount không được để trống.")
        @PositiveOrZero(message = "Chỉ tiêu headcount không được âm.")
        @Max(value = HeadcountPlanRequest.MAX_HEADCOUNT_LIMIT, message = HeadcountPlanRequest.LIMIT_MESSAGE)
        @JsonDeserialize(using = WholeHeadcountDeserializer.class) Integer headcountLimit,
        @PositiveOrZero(message = "Ngân sách lương không được âm.")
        @Max(value = HeadcountPlanRequest.MAX_SALARY_BUDGET_VND, message = HeadcountPlanRequest.BUDGET_MESSAGE)
        @JsonDeserialize(using = WholeVndDeserializer.class) Long salaryBudget) {

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("Unsupported headcount plan field");
    }
}
