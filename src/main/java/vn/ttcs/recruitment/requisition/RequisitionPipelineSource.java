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
 * - any write that makes an application active must first lock the requisition row FOR SHARE or FOR NO KEY UPDATE
 *   (FOR KEY SHARE does not conflict with the status change) in the order account, session, requisition, then its own
 *   rows, and refuse a FILLED or CANCELLED requisition, as posting writers do; otherwise an application added after the
 *   count was read could slip past the check.
 * Exactly one production bean: ApplicationPipelineSource (task 325) reads stored applications.
 */
public interface RequisitionPipelineSource {
    ActivePipeline activePipeline(UUID requisitionId);
}
