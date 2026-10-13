package vn.ttcs.recruitment.catalog;

// Must match the valid_recruitment_catalog_type CHECK in V10__create_recruitment_catalogs.sql.
public enum RecruitmentCatalogType {
    CANDIDATE_SOURCE,
    REJECTION_REASON,
    WORK_LOCATION,
    EMPLOYMENT_TYPE
}
