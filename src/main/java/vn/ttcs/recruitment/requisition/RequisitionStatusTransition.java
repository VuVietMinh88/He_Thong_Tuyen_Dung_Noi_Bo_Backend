package vn.ttcs.recruitment.requisition;

import java.time.Instant;
import java.util.UUID;

// Task 290: one status change as a RequisitionStatusListener sees it. reason: as stored in the history (null when it
// has no visible character); at: the history row's time, truncated to microseconds.
public record RequisitionStatusTransition(UUID requisitionId, RequisitionStatusChange change, RequisitionStatus before,
                                          RequisitionStatus after, UUID actorId, Instant at, String reason) { }
