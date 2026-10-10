package vn.ttcs.recruitment.security;

/**
 * The business modules of the permission matrix: the ten seeded by V3, SALARY_RANGES from V7_1 and HEADCOUNT_PLANS
 * from V14. Names equal the {@code permissions.module_code} values expanded to
 * {@code <MODULE>_<READ|WRITE>_<ALL|SCOPED>}.
 * The SELF_PROFILE/SELF_SECURITY permissions do not follow that pattern and are checked directly with
 * {@code hasAuthority}, not through {@link AccessScope}.
 */
public enum PermissionModule {
    ORGANIZATION,
    REQUISITIONS,
    JOB_POSTINGS,
    CANDIDATES,
    INTERVIEWS,
    EVALUATIONS,
    OFFERS,
    NOTIFICATIONS,
    REPORTS,
    USER_ADMIN,
    SALARY_RANGES,
    HEADCOUNT_PLANS
}
