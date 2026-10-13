package vn.ttcs.recruitment.requisition;

// Task 283: the role of a recruiter on a requisition. Stored as text; V16 CHECK valid_requisition_recruiter_role allows
// exactly these values.
public enum RequisitionRecruiterRole {
    // The one recruiter in charge of the requisition.
    PRIMARY,
    // A recruiter who helps the primary one.
    SUPPORTING
}
