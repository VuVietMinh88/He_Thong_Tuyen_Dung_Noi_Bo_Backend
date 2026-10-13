package vn.ttcs.recruitment.requisition;

// V13 CHECK valid_requisition_status only allows DRAFT. A new status needs a migration that widens the CHECK
// at the same time as the new enum value, otherwise saving it fails in PostgreSQL.
public enum RequisitionStatus {
    DRAFT
}
