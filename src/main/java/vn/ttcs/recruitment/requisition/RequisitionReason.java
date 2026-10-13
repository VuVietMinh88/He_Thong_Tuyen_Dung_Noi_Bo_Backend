package vn.ttcs.recruitment.requisition;

// Why the department needs people: replace someone who left, or open a new headcount.
// Stored as text; V13 CHECK valid_requisition_reason allows exactly these two values.
public enum RequisitionReason {
    REPLACEMENT, NEW_HEADCOUNT
}
