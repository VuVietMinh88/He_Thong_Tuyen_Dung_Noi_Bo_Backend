package vn.ttcs.recruitment.requisition;

// Task 283: the role of a recruiter on a requisition. Stored as text; V16 CHECK valid_requisition_recruiter_role allows
// exactly these values.
public enum RequisitionRecruiterRole {
    // The one recruiter in charge of the requisition.
    PRIMARY,
    // A recruiter who helps the primary one.
    SUPPORTING;

    // Task 284: the role codes a request may send, for @Pattern (the request keeps the role as text so that a wrong
    // code is a VALIDATION_ERROR on "role", not a bare INVALID_JSON). RequisitionRecruiterRoleTest keeps it in step.
    static final String CODES = "PRIMARY|SUPPORTING";
}
