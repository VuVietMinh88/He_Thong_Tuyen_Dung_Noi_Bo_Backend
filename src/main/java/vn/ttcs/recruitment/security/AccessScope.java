package vn.ttcs.recruitment.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * How much of a module the caller may read or write. {@link #SCOPED} means only records assigned to the
 * caller (own requisition, position, interview round), so the service must filter at query level;
 * a URL matcher alone cannot enforce it.
 */
public enum AccessScope {
    NONE,
    SCOPED,
    ALL;

    private static final String AUTHORITY_PREFIX = "PERM_";

    /** Uses the per-request {@code PERM_*} authorities built by {@link SecurityConfiguration}. */
    public static AccessScope read(Authentication authentication, PermissionModule module) {
        return resolve(codes(authentication), module, "READ");
    }

    public static AccessScope write(Authentication authentication, PermissionModule module) {
        return resolve(codes(authentication), module, "WRITE");
    }

    /** Uses codes from {@link PermissionService#forUser}, for services that re-check after locking rows. */
    public static AccessScope read(Collection<String> permissionCodes, PermissionModule module) {
        return resolve(permissionCodes, module, "READ");
    }

    public static AccessScope write(Collection<String> permissionCodes, PermissionModule module) {
        return resolve(permissionCodes, module, "WRITE");
    }

    /** Throws so the configured accessDeniedHandler renders the standard 403 FORBIDDEN response. */
    public AccessScope orDeny() {
        if (this == NONE) {
            throw new AccessDeniedException("No access scope for this operation");
        }
        return this;
    }

    // WRITE never implies READ: V3 grants each action as its own row, so READ must be granted explicitly.
    private static AccessScope resolve(Collection<String> codes, PermissionModule module, String action) {
        Objects.requireNonNull(module, "module");
        if (codes == null) {
            return NONE;
        }
        String prefix = module.name() + "_" + action + "_";
        if (codes.contains(prefix + "ALL")) {
            return ALL;
        }
        return codes.contains(prefix + "SCOPED") ? SCOPED : NONE;
    }

    private static Set<String> codes(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Set.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority != null && authority.startsWith(AUTHORITY_PREFIX))
                .map(authority -> authority.substring(AUTHORITY_PREFIX.length()))
                .collect(Collectors.toUnmodifiableSet());
    }
}
