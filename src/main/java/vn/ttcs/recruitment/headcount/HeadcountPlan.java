package vn.ttcs.recruitment.headcount;

import java.time.Instant;
import java.util.UUID;

// Task 272: one row of headcount_plans (V14). salaryBudget is a yearly amount in VND; null means no budget limit.
public record HeadcountPlan(UUID id, UUID departmentId, int year, int headcountLimit, Long salaryBudget,
                            Instant createdAt, Instant updatedAt, UUID updatedBy) {
}
