package vn.ttcs.recruitment.requisition;

import java.util.UUID;

/**
 * Task 291 (story S3-07): the candidates still in a requisition's recruitment pipeline, asked before HR closes it
 * (FILLED, CANCELLED) and shown in the close dialog (GET /requisitions/{id}/active-pipeline).
 * <p>
 * Contract for implementations (the candidate module):
 * - "active" = applications to this requisition that are not in a final state; the module defines its stages;
 * - runs inside the caller's transaction: under the requisition row lock (FOR NO KEY UPDATE, READ COMMITTED) during a
 *   status change, in a read-only REPEATABLE_READ snapshot for the GET; it only reads and locks nothing, so nothing
 *   waits after the requisition lock;
 * - returns ActivePipeline.NONE for a requisition it knows nothing about and never throws for one;
 * - any write that makes an application active must first lock the requisition row FOR NO KEY UPDATE in the order
 *   account, session, requisition, then its own rows, and refuse a FILLED or CANCELLED requisition, as posting
 *   writers do; otherwise an application added after the count was read could slip past the check. FOR KEY SHARE
 *   does not conflict with the status change, and FOR SHARE must not be used: while a row holds only share locks,
 *   PostgreSQL grants another share lock at once, ahead of a status change or posting write already waiting, so
 *   overlapping writes could keep it waiting indefinitely. A write that covers several requisitions locks them in
 *   id order. An application to a job posting calls JobPostingApplicationGate.requireOpen instead (task 321), which
 *   takes this lock and refuses a requisition that is no longer approved; it must not lock the requisition itself
 *   before that call.
 * Exactly one production bean: ApplicationPipelineSource reads stored applications.
 */
public interface RequisitionPipelineSource {
    ActivePipeline activePipeline(UUID requisitionId);
}
