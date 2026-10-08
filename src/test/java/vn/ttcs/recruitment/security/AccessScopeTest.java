package vn.ttcs.recruitment.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Collection;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.ttcs.recruitment.security.PermissionModule.CANDIDATES;
import static vn.ttcs.recruitment.security.PermissionModule.OFFERS;
import static vn.ttcs.recruitment.security.PermissionModule.REQUISITIONS;

class AccessScopeTest {

    @ParameterizedTest
    @EnumSource(PermissionModule.class)
    void allAuthorityGrantsFullAccessForEveryModule(PermissionModule module) {
        var authentication = authentication("PERM_" + module + "_READ_ALL", "PERM_" + module + "_WRITE_ALL");

        assertThat(AccessScope.read(authentication, module)).isEqualTo(AccessScope.ALL);
        assertThat(AccessScope.write(authentication, module)).isEqualTo(AccessScope.ALL);
    }

    @Test
    void scopedAuthorityGrantsOnlyAssignedRecords() {
        var authentication = authentication("PERM_CANDIDATES_READ_SCOPED", "PERM_CANDIDATES_WRITE_SCOPED");

        assertThat(AccessScope.read(authentication, CANDIDATES)).isEqualTo(AccessScope.SCOPED);
        assertThat(AccessScope.write(authentication, CANDIDATES)).isEqualTo(AccessScope.SCOPED);
    }

    @Test
    void allWinsWhenRolesGrantBothAllAndScoped() {
        // e.g. a HIRING_MANAGER who is also HR_MANAGER holds REQUISITIONS_*_SCOPED and REQUISITIONS_*_ALL.
        var authentication = authentication("PERM_REQUISITIONS_READ_SCOPED", "PERM_REQUISITIONS_READ_ALL",
                "PERM_REQUISITIONS_WRITE_ALL", "PERM_REQUISITIONS_WRITE_SCOPED");

        assertThat(AccessScope.read(authentication, REQUISITIONS)).isEqualTo(AccessScope.ALL);
        assertThat(AccessScope.write(authentication, REQUISITIONS)).isEqualTo(AccessScope.ALL);
    }

    @Test
    void missingModulePermissionMeansNone() {
        var authentication = authentication("PERM_SELF_PROFILE_READ", "PERM_SELF_SECURITY_WRITE");

        assertThat(AccessScope.read(authentication, OFFERS)).isEqualTo(AccessScope.NONE);
        assertThat(AccessScope.write(authentication, OFFERS)).isEqualTo(AccessScope.NONE);
    }

    @Test
    void missingOrAnonymousOrUnauthenticatedCallerHasNoAccess() {
        var unauthenticated = authentication("PERM_CANDIDATES_READ_ALL");
        unauthenticated.setAuthenticated(false);
        var anonymous = new AnonymousAuthenticationToken("key", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS", "PERM_CANDIDATES_READ_ALL"));

        assertThat(AccessScope.read((Authentication) null, CANDIDATES)).isEqualTo(AccessScope.NONE);
        assertThat(AccessScope.write((Authentication) null, CANDIDATES)).isEqualTo(AccessScope.NONE);
        assertThat(AccessScope.read(unauthenticated, CANDIDATES)).isEqualTo(AccessScope.NONE);
        assertThat(AccessScope.read(anonymous, CANDIDATES)).isEqualTo(AccessScope.NONE);
    }

    @Test
    void otherModuleAuthoritiesDoNotLeakAcrossModules() {
        var authentication = authentication("PERM_REQUISITIONS_READ_ALL", "PERM_REQUISITIONS_WRITE_ALL",
                "PERM_ORGANIZATION_READ_ALL", "PERM_USER_ADMIN_WRITE_ALL");

        assertThat(AccessScope.read(authentication, CANDIDATES)).isEqualTo(AccessScope.NONE);
        assertThat(AccessScope.write(authentication, OFFERS)).isEqualTo(AccessScope.NONE);
    }

    @Test
    void readAndWriteAreResolvedIndependently() {
        var writerOnly = authentication("PERM_REQUISITIONS_WRITE_SCOPED");
        var readerOnly = authentication("PERM_CANDIDATES_READ_ALL");

        assertThat(AccessScope.write(writerOnly, REQUISITIONS)).isEqualTo(AccessScope.SCOPED);
        assertThat(AccessScope.read(writerOnly, REQUISITIONS)).isEqualTo(AccessScope.NONE);
        assertThat(AccessScope.read(readerOnly, CANDIDATES)).isEqualTo(AccessScope.ALL);
        assertThat(AccessScope.write(readerOnly, CANDIDATES)).isEqualTo(AccessScope.NONE);
    }

    @ParameterizedTest
    @EnumSource(PermissionModule.class)
    void rolesAndUnprefixedCodesAloneGrantNothing(PermissionModule module) {
        var authentication = authentication("ROLE_ADMIN", "ROLE_HR_MANAGER",
                module + "_READ_ALL", module + "_WRITE_ALL");

        assertThat(AccessScope.read(authentication, module)).isEqualTo(AccessScope.NONE);
        assertThat(AccessScope.write(authentication, module)).isEqualTo(AccessScope.NONE);
    }

    @Test
    void freshPermissionCodesResolveWithoutAuthorityPrefix() {
        Collection<String> codes = Set.of("REQUISITIONS_READ_SCOPED", "REQUISITIONS_WRITE_SCOPED",
                "CANDIDATES_READ_ALL", "PERM_OFFERS_READ_ALL");

        assertThat(AccessScope.read(codes, REQUISITIONS)).isEqualTo(AccessScope.SCOPED);
        assertThat(AccessScope.write(codes, REQUISITIONS)).isEqualTo(AccessScope.SCOPED);
        assertThat(AccessScope.read(codes, CANDIDATES)).isEqualTo(AccessScope.ALL);
        assertThat(AccessScope.write(codes, CANDIDATES)).isEqualTo(AccessScope.NONE);
        assertThat(AccessScope.read(codes, OFFERS)).isEqualTo(AccessScope.NONE);
        assertThat(AccessScope.read((Collection<String>) null, OFFERS)).isEqualTo(AccessScope.NONE);
    }

    @Test
    void orDenyRejectsOnlyNone() {
        assertThat(AccessScope.ALL.orDeny()).isEqualTo(AccessScope.ALL);
        assertThat(AccessScope.SCOPED.orDeny()).isEqualTo(AccessScope.SCOPED);
        assertThatThrownBy(AccessScope.NONE::orDeny).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> AccessScope.read(authentication(), CANDIDATES).orDeny())
                .isInstanceOf(AccessDeniedException.class);
    }

    private static JwtAuthenticationToken authentication(String... authorities) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256").subject("user").build();
        return new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList(authorities), "user");
    }
}
