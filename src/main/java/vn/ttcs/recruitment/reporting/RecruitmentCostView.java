package vn.ttcs.recruitment.reporting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Task 573: one recruitment cost entry with who recorded it and, when voided, who voided it, when and why. */
public record RecruitmentCostView(UUID id, UUID requisitionId, UUID positionId, String positionName, UUID departmentId,
                                  String departmentName, String costType, long amountVnd, LocalDate incurredOn,
                                  String description, UUID recordedBy, String recordedByName, Instant recordedAt,
                                  boolean voided, Instant voidedAt, UUID voidedBy, String voidedByName,
                                  String voidReason) { }
