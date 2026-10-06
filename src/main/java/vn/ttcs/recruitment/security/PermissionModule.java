package vn.ttcs.recruitment.security;

/**
 * The ten business modules of the permission matrix. Names equal the {@code permissions.module_code} values
 * that V3 expands to {@code <MODULE>_<READ|WRITE>_<ALL|SCOPED>}. The SELF_PROFILE/SELF_SECURITY permissions
 * do not follow that pattern and are checked directly with {@code hasAuthority}, not through {@link AccessScope}.
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
    USER_ADMIN
}
