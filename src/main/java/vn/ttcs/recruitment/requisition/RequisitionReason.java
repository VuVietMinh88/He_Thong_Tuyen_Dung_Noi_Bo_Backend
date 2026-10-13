package vn.ttcs.recruitment.requisition;

// Why the department needs people: replace someone who left, or open a new headcount.
// Stored as text; V13 CHECK valid_requisition_reason allows exactly these two values.
public enum RequisitionReason {
    REPLACEMENT, NEW_HEADCOUNT;

    // Task 246: the same two codes as one regular expression, for @Pattern in RequisitionRequest. An annotation
    // needs a fixed text, so it cannot be built from values(); RequisitionReasonTest fails if the two drift apart.
    static final String CODES = "REPLACEMENT|NEW_HEADCOUNT";
}
