package vn.ttcs.recruitment.jobposting;

import vn.ttcs.recruitment.requisition.RequisitionStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// Task 303: one row of JobPostingReadRepository.SELECT_ROW: the posting with what it shows from its requisition
// (position, department, headcount, the approved salary proposal) and the names of its catalog values and creator.
record JobPostingRow(UUID id, UUID requisitionId, RequisitionStatus requisitionStatus, JobPostingStatus status,
                     int revision, String title, UUID positionId, String positionName, UUID departmentId,
                     String departmentName, int headcount, Long proposedSalaryMin, Long proposedSalaryMax,
                     String jobDescription, String candidateRequirements, String benefits, UUID workLocationId,
                     String workLocationName, UUID employmentTypeId, String employmentTypeName,
                     LocalDate applicationDeadline, boolean showSalary, UUID createdBy, String createdByName,
                     Instant createdAt, Instant updatedAt) { }
