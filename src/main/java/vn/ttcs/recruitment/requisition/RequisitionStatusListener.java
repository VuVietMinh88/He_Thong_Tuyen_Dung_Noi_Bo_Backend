package vn.ttcs.recruitment.requisition;

/**
 * Tasks 290/292 (story S3-07): what other modules do when HR changes a requisition's status. Both methods run in the
 * change's transaction while the requisition row is locked; an exception (an ApiException for a 4xx) rolls the whole
 * change back, history row included. beforeChange runs after every check of the change and before anything is
 * written: task 291 will refuse a closure here while candidates are still in the pipeline unless the caller confirmed
 * (it then adds its own request field). afterChange runs once the history row and the new status are written and
 * flushed: task 292 closes the requisition's job postings. Implementations lock only rows every writer locks after the
 * requisition (job postings), never an account, a session or a department.
 */
public interface RequisitionStatusListener {
    default void beforeChange(RequisitionStatusTransition transition) { }

    default void afterChange(RequisitionStatusTransition transition) { }
}
