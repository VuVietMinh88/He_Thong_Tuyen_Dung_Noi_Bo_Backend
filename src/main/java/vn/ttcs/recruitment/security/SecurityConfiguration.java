package vn.ttcs.recruitment.security;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authorization.AuthorizationManagers;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.auth.AuthService;

import java.util.List;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    public BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver resolver = new DefaultBearerTokenResolver();
        return request -> {
            // These endpoints authenticate their body; a stale bearer must not block recovery.
            if ("POST".equals(request.getMethod())
                    && ("/api/v1/auth/refresh".equals(request.getServletPath())
                    || "/api/v1/auth/login".equals(request.getServletPath())
                    || "/api/v1/auth/forgot-password".equals(request.getServletPath())
                    || "/api/v1/auth/reset-password".equals(request.getServletPath())
                    || "/api/v1/auth/activate-account".equals(request.getServletPath()))) {
                return null;
            }
            return resolver.resolve(request);
        };
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Accept", "Content-Type", "Authorization", "X-Device-Id"));
        // Expose download filenames and browser-login lock duration to the allowed frontend.
        configuration.setExposedHeaders(List.of("Content-Disposition", "Retry-After"));
        configuration.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration legacyHealth = new CorsConfiguration();
        legacyHealth.setAllowedOrigins(allowedOrigins);
        legacyHealth.setAllowedMethods(List.of("GET", "HEAD", "OPTIONS"));
        legacyHealth.setAllowedHeaders(List.of("Accept", "Content-Type"));
        legacyHealth.setAllowCredentials(false);
        legacyHealth.setMaxAge(3600L);
        source.registerCorsConfiguration("/api/health", legacyHealth);
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AuthService authService,
                                                  JsonSecurityErrors errors,
                                                  PermissionService permissions,
                                                  BearerTokenResolver bearerTokenResolver) throws Exception {
        return http
                .cors(withDefaults())
                // These APIs accept bearer headers/body tokens only, never browser authentication cookies.
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/health", "/api/v1/health").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/api/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password",
                                "/api/v1/auth/activate-account").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasRole("ADMIN"),
                                AuthorityAuthorizationManager.hasAuthority("PERM_USER_ADMIN_WRITE_ALL")))
                        .requestMatchers(HttpMethod.PUT, "/api/v1/accounts/*/roles/*").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasRole("ADMIN"),
                                AuthorityAuthorizationManager.hasAuthority("PERM_USER_ADMIN_WRITE_ALL")))
                        .requestMatchers(HttpMethod.PUT, "/api/v1/accounts/*/lock").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasRole("ADMIN"),
                                AuthorityAuthorizationManager.hasAuthority("PERM_USER_ADMIN_WRITE_ALL")))
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/accounts/*/lock").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasRole("ADMIN"),
                                AuthorityAuthorizationManager.hasAuthority("PERM_USER_ADMIN_WRITE_ALL")))
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/accounts/*/roles/*").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasRole("ADMIN"),
                                AuthorityAuthorizationManager.hasAuthority("PERM_USER_ADMIN_WRITE_ALL")))
                        .requestMatchers(HttpMethod.PUT, "/api/v1/accounts/*").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasRole("ADMIN"),
                                AuthorityAuthorizationManager.hasAuthority("PERM_USER_ADMIN_WRITE_ALL")))
                        .requestMatchers(HttpMethod.GET, "/api/v1/accounts", "/api/v1/accounts/*")
                                .hasAuthority("PERM_USER_ADMIN_READ_ALL")
                        .requestMatchers(HttpMethod.GET, "/api/v1/departments", "/api/v1/departments/*")
                                .hasAuthority("PERM_ORGANIZATION_READ_ALL")
                        .requestMatchers(HttpMethod.POST, "/api/v1/departments")
                                .hasAuthority("PERM_ORGANIZATION_WRITE_ALL")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/departments/*")
                                .hasAuthority("PERM_ORGANIZATION_WRITE_ALL")
                        .requestMatchers(HttpMethod.GET, "/api/v1/approval-flows", "/api/v1/approval-flows/*")
                        .access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasAnyRole("ADMIN", "HR_MANAGER"),
                                AuthorityAuthorizationManager.hasAuthority("PERM_REQUISITIONS_READ_ALL")))
                        .requestMatchers(HttpMethod.POST, "/api/v1/approval-flows/preview")
                        .access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasAnyRole("ADMIN", "HR_MANAGER"),
                                AuthorityAuthorizationManager.hasAuthority("PERM_REQUISITIONS_READ_ALL")))
                        .requestMatchers(HttpMethod.POST, "/api/v1/approval-flows")
                        .access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasAnyRole("ADMIN", "HR_MANAGER"),
                                AuthorityAuthorizationManager.hasAuthority("PERM_REQUISITIONS_WRITE_ALL")))
                        .requestMatchers(HttpMethod.PUT, "/api/v1/approval-flows/*")
                        .access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasAnyRole("ADMIN", "HR_MANAGER"),
                                AuthorityAuthorizationManager.hasAuthority("PERM_REQUISITIONS_WRITE_ALL")))
                        .requestMatchers(HttpMethod.GET, "/api/v1/profile").hasAuthority("PERM_SELF_PROFILE_READ")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/profile").hasAuthority("PERM_SELF_PROFILE_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me",
                                "/api/v1/auth/permissions").hasAuthority("PERM_SELF_PROFILE_READ")
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout",
                                "/api/v1/auth/change-password").hasAuthority("PERM_SELF_SECURITY_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/v1/positions", "/api/v1/positions/*")
                                .hasAuthority("PERM_ORGANIZATION_READ_ALL")
                        // Both salaries are required on a position write, so writers also need the salary permission.
                        .requestMatchers(HttpMethod.POST, "/api/v1/positions").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasAuthority("PERM_ORGANIZATION_WRITE_ALL"),
                                AuthorityAuthorizationManager.hasAuthority("PERM_SALARY_RANGES_WRITE_ALL")))
                        .requestMatchers(HttpMethod.PUT, "/api/v1/positions/*").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasAuthority("PERM_ORGANIZATION_WRITE_ALL"),
                                AuthorityAuthorizationManager.hasAuthority("PERM_SALARY_RANGES_WRITE_ALL")))
                        // Competency frameworks are organization data: every internal role reads them (interviewers
                        // score with them), and only organization writers create or edit them.
                        .requestMatchers(HttpMethod.GET, "/api/v1/competency-frameworks",
                                "/api/v1/competency-frameworks/*").hasAuthority("PERM_ORGANIZATION_READ_ALL")
                        .requestMatchers(HttpMethod.POST, "/api/v1/competency-frameworks")
                                .hasAuthority("PERM_ORGANIZATION_WRITE_ALL")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/competency-frameworks/*")
                                .hasAuthority("PERM_ORGANIZATION_WRITE_ALL")
                        // Choosing the shared competency framework of a position never touches its salary band,
                        // so organization writers do it without the salary permission.
                        .requestMatchers(HttpMethod.PUT, "/api/v1/positions/*/competency-framework")
                                .hasAuthority("PERM_ORGANIZATION_WRITE_ALL")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/positions/*/competency-framework")
                                .hasAuthority("PERM_ORGANIZATION_WRITE_ALL")
                        // The evaluation criteria of a position carry no salary data, so every internal role reads
                        // them: interviewers score candidates with these criteria.
                        .requestMatchers(HttpMethod.GET, "/api/v1/positions/*/evaluation-criteria")
                                .hasAuthority("PERM_ORGANIZATION_READ_ALL")
                        // The interview question bank belongs to the competency frameworks: every internal role reads
                        // the questions (interviewers ask them), and only organization writers create or edit them.
                        .requestMatchers(HttpMethod.GET, "/api/v1/interview-questions",
                                "/api/v1/interview-questions/*").hasAuthority("PERM_ORGANIZATION_READ_ALL")
                        .requestMatchers(HttpMethod.POST, "/api/v1/interview-questions")
                                .hasAuthority("PERM_ORGANIZATION_WRITE_ALL")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/interview-questions/*")
                                .hasAuthority("PERM_ORGANIZATION_WRITE_ALL")
                        // Requisitions: ALL and SCOPED callers both reach RequisitionService, which uses AccessScope.
                        .requestMatchers(HttpMethod.POST, "/api/v1/requisitions")
                                .hasAnyAuthority("PERM_REQUISITIONS_WRITE_ALL", "PERM_REQUISITIONS_WRITE_SCOPED")
                        // Task 278: a copy is a new draft; RequisitionService also checks that the caller may read
                        // the source (REQUISITIONS_READ_* and its department).
                        .requestMatchers(HttpMethod.POST, "/api/v1/requisitions/*/copy")
                                .hasAnyAuthority("PERM_REQUISITIONS_WRITE_ALL", "PERM_REQUISITIONS_WRITE_SCOPED")
                        // Task 257: sending a requisition for approval changes its status, a write of its department;
                        // RequisitionService also checks that the caller may read it (the answer shows it).
                        .requestMatchers(HttpMethod.POST, "/api/v1/requisitions/*/submit")
                                .hasAnyAuthority("PERM_REQUISITIONS_WRITE_ALL", "PERM_REQUISITIONS_WRITE_SCOPED")
                        // Task 262: a decision of the current approval level changes the requisition's status, a write;
                        // RequisitionApprovalService decides who may take the level (ApproverRule), not the scope.
                        .requestMatchers(HttpMethod.POST, "/api/v1/requisitions/*/approvals")
                                .hasAnyAuthority("PERM_REQUISITIONS_WRITE_ALL", "PERM_REQUISITIONS_WRITE_SCOPED")
                        // Task 284: assigning or handing over recruiters is for ALL writers only (HR_MANAGER, ADMIN); a
                        // department head (REQUISITIONS_WRITE_SCOPED) is refused. RequisitionRecruiterService checks the
                        // code again after its locks.
                        .requestMatchers(HttpMethod.POST, "/api/v1/requisitions/*/assign",
                                "/api/v1/requisitions/*/unassign")
                                .hasAuthority("PERM_REQUISITIONS_WRITE_ALL")
                        // Task 284, 286: who is assigned, and the history of changes, are read like the requisition
                        // itself (same scope as GET /{id}).
                        .requestMatchers(HttpMethod.GET, "/api/v1/requisitions/*/assignment",
                                "/api/v1/requisitions/*/assignment-history")
                                .hasAnyAuthority("PERM_REQUISITIONS_READ_ALL", "PERM_REQUISITIONS_READ_SCOPED")
                        // Task 267: the approval progress and the approver inbox are read like requisitions; the
                        // service applies the same scope as GET /requisitions/{id} (department or approval part).
                        .requestMatchers(HttpMethod.GET, "/api/v1/requisitions/*/approvals",
                                "/api/v1/requisitions/approval-inbox")
                                .hasAnyAuthority("PERM_REQUISITIONS_READ_ALL", "PERM_REQUISITIONS_READ_SCOPED")
                        .requestMatchers(HttpMethod.GET, "/api/v1/requisitions", "/api/v1/requisitions/*",
                                "/api/v1/requisitions/*/headcount-overrides")
                                .hasAnyAuthority("PERM_REQUISITIONS_READ_ALL", "PERM_REQUISITIONS_READ_SCOPED")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/requisitions/*")
                                .hasAnyAuthority("PERM_REQUISITIONS_WRITE_ALL", "PERM_REQUISITIONS_WRITE_SCOPED")
                        // Task 273: what is left of a plan is shown on the requisition form, so requisition readers
                        // may ask too; HeadcountPlanService limits SCOPED readers to their departments and shows the
                        // salary budget to HEADCOUNT_PLANS_READ_ALL only. Listed before "/*", which it would match.
                        .requestMatchers(HttpMethod.GET, "/api/v1/headcount-plans/remaining")
                                .hasAnyAuthority("PERM_HEADCOUNT_PLANS_READ_ALL", "PERM_REQUISITIONS_READ_ALL",
                                        "PERM_REQUISITIONS_READ_SCOPED")
                        .requestMatchers(HttpMethod.GET, "/api/v1/headcount-plans", "/api/v1/headcount-plans/*")
                                .hasAuthority("PERM_HEADCOUNT_PLANS_READ_ALL")
                        .requestMatchers(HttpMethod.POST, "/api/v1/headcount-plans")
                                .hasAuthority("PERM_HEADCOUNT_PLANS_WRITE_ALL")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/headcount-plans/*")
                                .hasAuthority("PERM_HEADCOUNT_PLANS_WRITE_ALL")
                        // Task 197: deleting a department is a department write like POST and PUT.
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/departments/*")
                                .hasAuthority("PERM_ORGANIZATION_WRITE_ALL")
                        .requestMatchers(HttpMethod.GET, "/api/v1/recruitment-catalogs/*/items",
                                "/api/v1/recruitment-catalogs/*/items/*").hasAuthority("PERM_ORGANIZATION_READ_ALL")
                        .requestMatchers(HttpMethod.POST, "/api/v1/recruitment-catalogs/*/items")
                                .hasAuthority("PERM_ORGANIZATION_WRITE_ALL")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/recruitment-catalogs/*/items/*")
                                .hasAuthority("PERM_ORGANIZATION_WRITE_ALL")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/recruitment-catalogs/*/items/*")
                                .hasAuthority("PERM_ORGANIZATION_WRITE_ALL")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/recruitment-catalogs/*/order")
                                .hasAuthority("PERM_ORGANIZATION_WRITE_ALL")
                        .requestMatchers(HttpMethod.GET, "/api/v1/company-profile")
                                .hasAuthority("PERM_JOB_POSTINGS_WRITE_ALL")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/company-profile")
                                .hasAuthority("PERM_JOB_POSTINGS_WRITE_ALL")
                        .requestMatchers(HttpMethod.POST, "/api/v1/company-profile/preview")
                                .hasAuthority("PERM_JOB_POSTINGS_WRITE_ALL")
                        // Candidates read the recruitment portal without an account.
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/company-profile").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/company-profile/media")
                                .hasAuthority("PERM_JOB_POSTINGS_WRITE_ALL")
                        .requestMatchers(HttpMethod.GET, "/api/v1/company-profile/media/*")
                                .hasAuthority("PERM_JOB_POSTINGS_WRITE_ALL")
                        // Pictures of the saved page; the service answers 404 for every other picture.
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/company-media/*").permitAll()
                        // Tasks 302-304 (story S3-09): job postings. Composing and every internal read (list, detail,
                        // preview, history) need a JOB_POSTINGS write permission: ALL (HR_MANAGER, ADMIN) or SCOPED
                        // (RECRUITER: postings of the requisitions they are assigned to). JobPostingService also
                        // requires the matching JOB_POSTINGS READ permission and applies the scope.
                        // JOB_POSTINGS_READ_ALL alone (HIRING_MANAGER, APPROVER) only reads the public portal, like
                        // the company page. Listed before "/api/v1/job-postings/*", which it would match.
                        .requestMatchers(HttpMethod.GET, "/api/v1/job-postings/source-requisitions")
                                .hasAnyAuthority("PERM_JOB_POSTINGS_WRITE_ALL", "PERM_JOB_POSTINGS_WRITE_SCOPED")
                        .requestMatchers(HttpMethod.POST, "/api/v1/job-postings")
                                .hasAnyAuthority("PERM_JOB_POSTINGS_WRITE_ALL", "PERM_JOB_POSTINGS_WRITE_SCOPED")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/job-postings/*")
                                .hasAnyAuthority("PERM_JOB_POSTINGS_WRITE_ALL", "PERM_JOB_POSTINGS_WRITE_SCOPED")
                        .requestMatchers(HttpMethod.GET, "/api/v1/job-postings", "/api/v1/job-postings/*")
                                .hasAnyAuthority("PERM_JOB_POSTINGS_WRITE_ALL", "PERM_JOB_POSTINGS_WRITE_SCOPED")
                        // Task 308 (story S3-10): sending for review is the authors' write; reviewing and publishing
                        // are HR decisions (JOB_POSTINGS_WRITE_ALL = ADMIN, HR_MANAGER), checked again by
                        // JobPostingApprovalService after its locks.
                        .requestMatchers(HttpMethod.POST, "/api/v1/job-postings/*/submit")
                                .hasAnyAuthority("PERM_JOB_POSTINGS_WRITE_ALL", "PERM_JOB_POSTINGS_WRITE_SCOPED")
                        .requestMatchers(HttpMethod.POST, "/api/v1/job-postings/*/approvals",
                                "/api/v1/job-postings/*/publish")
                                .hasAuthority("PERM_JOB_POSTINGS_WRITE_ALL")
                        // Task 309: the authoring preview is read like the posting. Candidates read published
                        // postings without an account; the service answers 404 for every other posting.
                        .requestMatchers(HttpMethod.GET, "/api/v1/job-postings/*/preview")
                                .hasAnyAuthority("PERM_JOB_POSTINGS_WRITE_ALL", "PERM_JOB_POSTINGS_WRITE_SCOPED")
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/job-postings",
                                "/api/v1/public/job-postings/*").permitAll()
                        // Task 310: the review and publication history is read like the posting.
                        .requestMatchers(HttpMethod.GET, "/api/v1/job-postings/*/history")
                                .hasAnyAuthority("PERM_JOB_POSTINGS_WRITE_ALL", "PERM_JOB_POSTINGS_WRITE_SCOPED")
                        // Avatars: every internal user may see a colleague's picture; only the owner may change it.
                        .requestMatchers(HttpMethod.GET, "/api/v1/profile/avatar", "/api/v1/accounts/*/avatar")
                                .hasAuthority("PERM_SELF_PROFILE_READ")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/profile/avatar")
                                .hasAuthority("PERM_SELF_PROFILE_WRITE")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/profile/avatar")
                                .hasAuthority("PERM_SELF_PROFILE_WRITE")
                        .requestMatchers(HttpMethod.GET, "/api/v1/accounts/import/template")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasRole("ADMIN"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_USER_ADMIN_WRITE_ALL")))
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts/import/preview")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasRole("ADMIN"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_USER_ADMIN_WRITE_ALL")))
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts/import")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasRole("ADMIN"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_USER_ADMIN_WRITE_ALL")))
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> errors.unauthorized(response))
                        .accessDeniedHandler((request, response, exception) -> errors.forbidden(response)))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(bearerTokenResolver)
                        .authenticationEntryPoint((request, response, exception) -> errors.unauthorized(response))
                        .accessDeniedHandler((request, response, exception) -> errors.forbidden(response))
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(token -> {
                            Account account = authService.requireActiveAccount(token);
                            var authorities = new java.util.ArrayList<>(account.getRoles().stream()
                                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList());
                            permissions.forUser(account.getId()).stream()
                                    .map(code -> new SimpleGrantedAuthority("PERM_" + code))
                                    .forEach(authorities::add);
                            return new JwtAuthenticationToken(token, authorities, account.getId().toString());
                        })))
                .build();
    }
}
