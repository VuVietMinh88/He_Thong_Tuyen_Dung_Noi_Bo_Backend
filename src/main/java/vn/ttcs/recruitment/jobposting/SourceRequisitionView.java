package vn.ttcs.recruitment.jobposting;

import java.time.LocalDate;
import java.util.UUID;

// Task 303: an approved requisition a posting may still be made from. Only what the author needs to choose it: the
// job description, the requirements and the salary come with the draft once it is created.
public record SourceRequisitionView(UUID requisitionId, UUID positionId, String positionName, UUID departmentId,
                                    String departmentName, int headcount, LocalDate neededBy) { }
