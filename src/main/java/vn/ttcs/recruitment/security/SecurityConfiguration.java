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
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.application.ApplicationIntakeFilter;
import vn.ttcs.recruitment.application.ApplicationIntakeRoutes;
import vn.ttcs.recruitment.application.ApplicationReferralRoutes;
import vn.ttcs.recruitment.application.ApplicationTrackingRequestFilter;
import vn.ttcs.recruitment.application.ApplicationTrackingRoutes;
import vn.ttcs.recruitment.invitation.http.InterviewInvitationRoutes;
import vn.ttcs.recruitment.invitation.http.InterviewInvitationRequestFilter;
import vn.ttcs.recruitment.offerdelivery.http.OfferRoutes;
import vn.ttcs.recruitment.offerdelivery.http.OfferRequestFilter;

import java.util.List;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    @Bean
    public BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver resolver = new DefaultBearerTokenResolver();
        return request -> {
            if (ApplicationIntakeRoutes.isSubmission(request)
                    || ("GET".equals(request.getMethod()) && ApplicationIntakeRoutes.SOURCES.equals(request.getServletPath()))) {
                return null;
            }
            // Task 339: a candidate has no account; the frontend attaches any stored bearer, which must not block a
            // lookup (or, from task 345, a withdrawal). The credential is the tracking code and email in the body.
            if (ApplicationTrackingRoutes.isPublicPost(request) || InterviewInvitationRoutes.isPublicPost(request)
                    || OfferRoutes.isPublicPost(request)) {
                return null;
            }
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
        // Login and forgot-password both identify the browser through X-Device-Id.
        configuration.setAllowedHeaders(List.of("Accept", "Content-Type", "Authorization", "X-Device-Id"));
        // Expose download filenames and the wait time for either browser limit to the allowed frontend.
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
        CorsConfiguration onboardingItems = new CorsConfiguration(configuration);
        onboardingItems.setAllowedMethods(List.of("PATCH", "OPTIONS"));
        source.registerCorsConfiguration("/api/v1/applications/*/onboarding/items/*", onboardingItems);
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AuthService authService,
                                                  JsonSecurityErrors errors,
                                                  PermissionService permissions,
                                                  BearerTokenResolver bearerTokenResolver,
                                                  ApplicationIntakeFilter intakeFilter,
                                                  ApplicationTrackingRequestFilter trackingFilter,
                                                  InterviewInvitationRequestFilter invitationFilter,
                                                  OfferRequestFilter offerFilter) throws Exception {
        return http
                .cors(withDefaults())
                // These APIs accept bearer headers/body tokens only, never browser authentication cookies.
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(intakeFilter, BearerTokenAuthenticationFilter.class)
                .addFilterBefore(trackingFilter, BearerTokenAuthenticationFilter.class)
                .addFilterBefore(invitationFilter, BearerTokenAuthenticationFilter.class)
                .addFilterBefore(offerFilter, BearerTokenAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/health", "/api/v1/health").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/api/health").permitAll()
                        .requestMatchers(HttpMethod.POST, ApplicationIntakeRoutes.SUBMISSION_MATCHER).permitAll()
                        .requestMatchers(HttpMethod.GET, ApplicationIntakeRoutes.SOURCES).permitAll()
                        // Task 339: the candidate's lookup; only this method and path, never a GET with the secret in it.
                        .requestMatchers(HttpMethod.POST, ApplicationTrackingRoutes.LOOKUP).permitAll()
                        // Task 345: the candidate's withdrawal; the same credential in the body, never a PATCH with the
                        // code in the path.
                        .requestMatchers(HttpMethod.POST, ApplicationTrackingRoutes.WITHDRAW).permitAll()
                        .requestMatchers(HttpMethod.POST, InterviewInvitationRoutes.LOOKUP, InterviewInvitationRoutes.RESPOND).permitAll()
                        .requestMatchers(HttpMethod.POST, OfferRoutes.LOOKUP, OfferRoutes.RESPOND).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/offers/reply",
                                "/api/v1/public/offers/reply.js").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/interview-invitations/reply",
                                "/api/v1/public/interview-invitations/reply.js").permitAll()
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
                        .requestMatchers(HttpMethod.PUT, "/api/v1/accounts/*/roles").access(AuthorizationManagers.allOf(
                                AuthorityAuthorizationManager.hasRole("ADMIN"),
                                AuthorityAuthorizationManager.hasAuthority("PERM_USER_ADMIN_WRITE_ALL")))
                        // Feedback item 10: the work an account still holds, shown by the lock dialog. Like the lock,
                        // plus REQUISITIONS_READ_ALL because it lists requisitions of every department;
                        // AccountHandoverService checks all three again.
                        .requestMatchers(HttpMethod.GET, "/api/v1/accounts/*/handover-items")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasRole("ADMIN"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_USER_ADMIN_WRITE_ALL"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_REQUISITIONS_READ_ALL")))
                        // Feedback item 10: hands the requisitions of an account over to other recruiters, and its
                        // departments to other managers. Like the list above, plus REQUISITIONS_WRITE_ALL, the
                        // permission of assigning recruiters; AccountHandoverService checks all four again after its
                        // locks, and ORGANIZATION_WRITE_ALL too when the body has departments.
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts/*/handover")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasRole("ADMIN"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_USER_ADMIN_WRITE_ALL"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_REQUISITIONS_READ_ALL"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_REQUISITIONS_WRITE_ALL")))
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
                        // Onboarding is managed by HR; responsibility for an item does not grant candidate access.
                        .requestMatchers(HttpMethod.GET, "/api/v1/onboarding/templates", "/api/v1/onboarding/templates/*")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasAnyRole("ADMIN", "HR_MANAGER"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_ORGANIZATION_READ_ALL")))
                        .requestMatchers(HttpMethod.POST, "/api/v1/onboarding/templates")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasAnyRole("ADMIN", "HR_MANAGER"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_ORGANIZATION_READ_ALL"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_ORGANIZATION_WRITE_ALL")))
                        .requestMatchers(HttpMethod.PUT, "/api/v1/onboarding/templates/*")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasAnyRole("ADMIN", "HR_MANAGER"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_ORGANIZATION_READ_ALL"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_ORGANIZATION_WRITE_ALL")))
                        .requestMatchers(HttpMethod.GET, "/api/v1/applications/*/onboarding")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasAnyRole("ADMIN", "HR_MANAGER"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_OFFERS_READ_ALL"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_CANDIDATES_READ_ALL")))
                        .requestMatchers(HttpMethod.POST, "/api/v1/applications/*/onboarding")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasAnyRole("ADMIN", "HR_MANAGER"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_OFFERS_READ_ALL"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_OFFERS_WRITE_ALL"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_CANDIDATES_READ_ALL")))
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/applications/*/onboarding/items/*")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasAnyRole("ADMIN", "HR_MANAGER"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_OFFERS_READ_ALL"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_OFFERS_WRITE_ALL"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_CANDIDATES_READ_ALL")))
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
                        // Task 290 (story S3-07): pausing, resuming, closing as filled and cancelling a requisition are
                        // HR decisions (REQUISITIONS_WRITE_ALL: ADMIN, HR_MANAGER), like recruiter assignment (matrix
                        // §7.13); RequisitionStatusService checks the code again after its locks and also requires read
                        // access.
                        .requestMatchers(HttpMethod.POST, "/api/v1/requisitions/*/status-changes")
                                .hasAuthority("PERM_REQUISITIONS_WRITE_ALL")
                        // Task 291 (story S3-07): the candidates still in the pipeline are shown in HR's close dialog,
                        // so only those who may close read them (REQUISITIONS_WRITE_ALL); RequisitionStatusService
                        // checks the code again and also requires read access.
                        .requestMatchers(HttpMethod.GET, "/api/v1/requisitions/*/active-pipeline")
                                .hasAuthority("PERM_REQUISITIONS_WRITE_ALL")
                        // Task 284, 286: who is assigned, and the history of changes, are read like the requisition
                        // itself (same scope as GET /{id}).
                        .requestMatchers(HttpMethod.GET, "/api/v1/requisitions/*/assignment",
                                "/api/v1/requisitions/*/assignment-history")
                                .hasAnyAuthority("PERM_REQUISITIONS_READ_ALL", "PERM_REQUISITIONS_READ_SCOPED")
                        // Task 267: the approval progress and the approver inbox are read like requisitions; the
                        // service applies the same scope as GET /requisitions/{id} (department, approval part or,
                        // task 285, recruiter assignment).
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
                        // JobPostingApprovalService after its locks. Task 355 (story S4-08): taking a published posting
                        // down is the composers' write too, since the story's actor is the recruiter; JobPostingService
                        // applies the recruiter scope after its locks.
                        .requestMatchers(HttpMethod.POST, "/api/v1/job-postings/*/submit",
                                "/api/v1/job-postings/*/unpublish")
                                .hasAnyAuthority("PERM_JOB_POSTINGS_WRITE_ALL", "PERM_JOB_POSTINGS_WRITE_SCOPED")
                        .requestMatchers(HttpMethod.POST, "/api/v1/job-postings/*/approvals",
                                "/api/v1/job-postings/*/publish")
                                .hasAuthority("PERM_JOB_POSTINGS_WRITE_ALL")
                        // Task 309: the authoring preview is read like the posting. Candidates read published
                        // postings without an account; the service answers 404 for every other posting.
                        .requestMatchers(HttpMethod.GET, "/api/v1/job-postings/*/preview")
                                .hasAnyAuthority("PERM_JOB_POSTINGS_WRITE_ALL", "PERM_JOB_POSTINGS_WRITE_SCOPED")
                        // Task 315: the filter values are named although "/*" covers them, so they stay public if
                        // "/*" is ever narrowed to ids.
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/job-postings",
                                "/api/v1/public/job-postings/filters", "/api/v1/public/job-postings/*").permitAll()
                        // Task 310: the review and publication history is read like the posting.
                        .requestMatchers(HttpMethod.GET, "/api/v1/job-postings/*/history")
                                .hasAnyAuthority("PERM_JOB_POSTINGS_WRITE_ALL", "PERM_JOB_POSTINGS_WRITE_SCOPED")
                        // Task 361 (story S4-09): the unified candidate profile. Both CANDIDATES read permissions
                        // pass the matcher; CandidateProfileService checks the code again before any lookup (403
                        // before 404), then returns only the applications CandidateAccess.READABLE allows (403 when
                        // none, logged).
                        .requestMatchers(HttpMethod.GET, "/api/v1/candidates/*/unified-profile")
                                .hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED")
                        // Kanban applies the same record scope and logs the profiles disclosed by its cards.
                        .requestMatchers(HttpMethod.GET, "/api/v1/application-board", "/api/v1/application-board/positions")
                                .hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED")
                        // Task 362: one application's CV, read like the profile: ApplicationCvService checks the code
                        // again before any lookup, then the scope of that application (403 outside it, logged).
                        .requestMatchers(HttpMethod.GET, "/api/v1/applications/*/cv")
                                .hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED")
                        .requestMatchers(HttpMethod.GET, "/api/v1/interviews/me/calendar", "/api/v1/interviews/*/cv",
                                "/api/v1/interviews/*/questions", "/api/v1/interviews/*/question-history")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_INTERVIEWS_READ_ALL", "PERM_INTERVIEWS_READ_SCOPED"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_EVALUATIONS_READ_ALL", "PERM_EVALUATIONS_READ_SCOPED"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_EVALUATIONS_WRITE_ALL", "PERM_EVALUATIONS_WRITE_SCOPED")))
                        .requestMatchers(HttpMethod.POST, "/api/v1/interviews/*/questions/*/asked")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_INTERVIEWS_READ_ALL", "PERM_INTERVIEWS_READ_SCOPED"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_EVALUATIONS_READ_ALL", "PERM_EVALUATIONS_READ_SCOPED"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_EVALUATIONS_WRITE_ALL", "PERM_EVALUATIONS_WRITE_SCOPED")))
                        .requestMatchers(HttpMethod.GET, "/api/v1/applications/*/evaluation-summary", "/api/v1/positions/*/candidate-comparison")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_EVALUATIONS_READ_ALL", "PERM_EVALUATIONS_READ_SCOPED")))
                        .requestMatchers(HttpMethod.POST, "/api/v1/applications/*/hiring-decisions")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasAnyRole("HIRING_MANAGER", "HR_MANAGER", "ADMIN"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_EVALUATIONS_READ_ALL", "PERM_EVALUATIONS_READ_SCOPED")))
                        // Interview scopes are checked again against the application or invited panel in the service.
                        .requestMatchers(HttpMethod.GET, "/api/v1/applications/*/interview-participants")
                                .hasAnyAuthority("PERM_INTERVIEWS_WRITE_ALL", "PERM_INTERVIEWS_WRITE_SCOPED")
                        // Offer readers are restricted again to the application scope or the named approval step.
                        .requestMatchers(HttpMethod.GET, "/api/v1/offers/approvers")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasAnyRole("ADMIN", "HR_MANAGER", "RECRUITER"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_OFFERS_READ_ALL", "PERM_OFFERS_READ_SCOPED"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_OFFERS_WRITE_ALL", "PERM_OFFERS_WRITE_SCOPED"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED")))
                        .requestMatchers(HttpMethod.GET, "/api/v1/applications/*/offers", "/api/v1/offers/*",
                                "/api/v1/offers/*/preview", "/api/v1/offers/*/history", "/api/v1/offers/*/negotiations")
                                .hasAnyAuthority("PERM_OFFERS_READ_ALL", "PERM_OFFERS_READ_SCOPED")
                        .requestMatchers(HttpMethod.GET, "/api/v1/offers/*/talent-pool-suggestion")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_OFFERS_READ_ALL", "PERM_OFFERS_READ_SCOPED"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED")))
                        .requestMatchers(HttpMethod.POST, "/api/v1/offers/*/approval")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasAnyRole("HR_MANAGER", "APPROVER"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_OFFERS_READ_ALL", "PERM_OFFERS_READ_SCOPED"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_OFFERS_WRITE_ALL", "PERM_OFFERS_WRITE_SCOPED")))
                        .requestMatchers(HttpMethod.POST, "/api/v1/applications/*/offers", "/api/v1/offers/*/submit",
                                "/api/v1/offers/*/cancel", "/api/v1/offers/*/send",
                                "/api/v1/offers/*/negotiations", "/api/v1/offers/*/revisions")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasAnyRole("ADMIN", "HR_MANAGER", "RECRUITER"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_OFFERS_READ_ALL", "PERM_OFFERS_READ_SCOPED"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_OFFERS_WRITE_ALL", "PERM_OFFERS_WRITE_SCOPED"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED")))
                        .requestMatchers(HttpMethod.PUT, "/api/v1/offers/*")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasAnyRole("ADMIN", "HR_MANAGER", "RECRUITER"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_OFFERS_READ_ALL", "PERM_OFFERS_READ_SCOPED"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_OFFERS_WRITE_ALL", "PERM_OFFERS_WRITE_SCOPED"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED")))
                        .requestMatchers(HttpMethod.GET, "/api/v1/applications/*/interviews",
                                "/api/v1/interviews/me", "/api/v1/interviews/*", "/api/v1/interviews/*/invitation")
                                .hasAnyAuthority("PERM_INTERVIEWS_READ_ALL", "PERM_INTERVIEWS_READ_SCOPED")
                        .requestMatchers(HttpMethod.POST, "/api/v1/applications/*/interviews",
                                "/api/v1/interviews/conflicts", "/api/v1/interviews/*/cancel",
                                "/api/v1/interviews/bulk", "/api/v1/interviews/bulk/preview",
                                "/api/v1/interviews/*/invitation")
                                .hasAnyAuthority("PERM_INTERVIEWS_WRITE_ALL", "PERM_INTERVIEWS_WRITE_SCOPED")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/interviews/*")
                                .hasAnyAuthority("PERM_INTERVIEWS_WRITE_ALL", "PERM_INTERVIEWS_WRITE_SCOPED")
                        .requestMatchers(HttpMethod.GET, "/api/v1/interviews/*/evaluations/me",
                                "/api/v1/interviews/*/evaluations")
                                .hasAnyAuthority("PERM_EVALUATIONS_READ_ALL", "PERM_EVALUATIONS_READ_SCOPED")
                        .requestMatchers(HttpMethod.POST, "/api/v1/interviews/*/evaluations/me",
                                "/api/v1/interviews/*/evaluations/me/submit")
                                .hasAnyAuthority("PERM_EVALUATIONS_WRITE_ALL", "PERM_EVALUATIONS_WRITE_SCOPED")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/interviews/*/evaluations/me")
                                .hasAnyAuthority("PERM_EVALUATIONS_WRITE_ALL", "PERM_EVALUATIONS_WRITE_SCOPED")
                        .requestMatchers(HttpMethod.POST, "/api/v1/interviews/*/evaluations/*/reopen")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasRole("RECRUITER"),
                                        AuthorityAuthorizationManager.hasAnyAuthority(
                                                "PERM_INTERVIEWS_WRITE_ALL", "PERM_INTERVIEWS_WRITE_SCOPED")))
                        // Recruiters can obtain the version needed to reopen without seeing the assessment answers.
                        .requestMatchers(HttpMethod.GET, "/api/v1/interviews/*/evaluations/*/status")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasRole("RECRUITER"),
                                        AuthorityAuthorizationManager.hasAnyAuthority(
                                                "PERM_INTERVIEWS_WRITE_ALL", "PERM_INTERVIEWS_WRITE_SCOPED")))
                        // Internal timeline uses the same application scope and PROFILE access audit.
                        .requestMatchers(HttpMethod.GET, "/api/v1/applications/*/timeline")
                                .hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED")
                        // Task 380 (story S5-03): moving an application to another stage is a CANDIDATES write, ALL
                        // (HR_MANAGER, ADMIN) or SCOPED (RECRUITER: the requisitions it is assigned to).
                        // ApplicationStageChangeService checks the write and the read code again after locking the
                        // account and the session, then the scope of that application under the requisition lock.
                        .requestMatchers(HttpMethod.POST, "/api/v1/applications/*/stage-changes")
                                .hasAnyAuthority("PERM_CANDIDATES_WRITE_ALL", "PERM_CANDIDATES_WRITE_SCOPED")
                        // S5-04 assessments are application-scoped. Service checks read + write and fresh assignment.
                        .requestMatchers(HttpMethod.GET, "/api/v1/applications/*/screening")
                                .hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/applications/*/screening/tags", "/api/v1/applications/*/screening/rating")
                                .hasAnyAuthority("PERM_CANDIDATES_WRITE_ALL", "PERM_CANDIDATES_WRITE_SCOPED")
                        .requestMatchers(HttpMethod.POST, "/api/v1/applications/*/screening/notes")
                                .hasAnyAuthority("PERM_CANDIDATES_WRITE_ALL", "PERM_CANDIDATES_WRITE_SCOPED")
                        // Story S5-05 (tasks 392-394): the candidate search is read like the board. The service reads
                        // the CANDIDATES read code again and returns only CandidateAccess.READABLE applications.
                        .requestMatchers(HttpMethod.GET, "/api/v1/candidate-search")
                                .hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED")
                        // Task 394: personal saved filters, owned by the JWT subject; another account's id is 404.
                        .requestMatchers(HttpMethod.GET, "/api/v1/candidate-search/saved-filters",
                                "/api/v1/candidate-search/saved-filters/*")
                                .hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED")
                        .requestMatchers(HttpMethod.POST, "/api/v1/candidate-search/saved-filters")
                                .hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/candidate-search/saved-filters/*")
                                .hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/candidate-search/saved-filters/*")
                                .hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED")
                        // Story S6-01 (tasks 423-424): the export holds exactly the rows the search would list.
                        .requestMatchers(HttpMethod.GET, "/api/v1/candidate-search/export")
                                .hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED")
                        // Story S5-08 (tasks 407-408): entering a candidate by hand is a CANDIDATES write, like a stage
                        // change. The service reads the read and write codes again, then the requisition scope (a SCOPED
                        // writer must be assigned to it) after every lock wait.
                        .requestMatchers(HttpMethod.POST, "/api/v1/requisitions/*/manual-applications",
                                "/api/v1/requisitions/*/manual-applications/duplicate-check")
                                .hasAnyAuthority("PERM_CANDIDATES_WRITE_ALL", "PERM_CANDIDATES_WRITE_SCOPED")
                        // Story S5-09 (tasks 412-414): the required criteria belong to the position catalog: read like
                        // a position, saved by ORGANIZATION_WRITE_ALL; the service reads both codes again.
                        .requestMatchers(HttpMethod.GET, "/api/v1/positions/*/required-criteria")
                                .hasAuthority("PERM_ORGANIZATION_READ_ALL")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/positions/*/required-criteria")
                                .hasAuthority("PERM_ORGANIZATION_WRITE_ALL")
                        // Task 413: an application's fit is read like its profile; recording qualifications is a
                        // CANDIDATES write checked again (with the record scope) after every lock wait.
                        .requestMatchers(HttpMethod.GET, "/api/v1/applications/*/fit")
                                .hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/applications/*/qualifications")
                                .hasAnyAuthority("PERM_CANDIDATES_WRITE_ALL", "PERM_CANDIDATES_WRITE_SCOPED")
                        // Task 414: the ranking suggestion is read like the requisition's candidates (board scope).
                        .requestMatchers(HttpMethod.GET, "/api/v1/requisitions/*/fit-ranking")
                                .hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED")
                        // Story S5-06 (tasks 398-400): the talent pool. Entries are read with the scope of their source
                        // application; adding is a CANDIDATES write about that application, checked again in the service.
                        .requestMatchers(HttpMethod.GET, "/api/v1/talent-pool", "/api/v1/talent-pool/*")
                                .hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED")
                        .requestMatchers(HttpMethod.POST, "/api/v1/talent-pool")
                                .hasAnyAuthority("PERM_CANDIDATES_WRITE_ALL", "PERM_CANDIDATES_WRITE_SCOPED")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/talent-pool/*")
                                .hasAnyAuthority("PERM_CANDIDATES_WRITE_ALL", "PERM_CANDIDATES_WRITE_SCOPED")
                        .requestMatchers(HttpMethod.GET, "/api/v1/requisitions/*/talent-pool-suggestions")
                                .hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED")
                        // Task 351 (story S4-07): every internal user may refer a candidate, like the avatar write;
                        // ApplicationReferralWriter checks the code again after locking the account and the session.
                        // Not in the bearer exemption: a stale bearer is 401. The referrer comes from the JWT.
                        .requestMatchers(HttpMethod.POST, ApplicationReferralRoutes.SUBMISSION)
                                .hasAuthority("PERM_SELF_PROFILE_WRITE")
                        // Task 352: the referrer's own list (general status only), read like a colleague's avatar;
                        // ApplicationReferralStatusService checks the code again and filters by the JWT's subject.
                        .requestMatchers(HttpMethod.GET, ApplicationReferralRoutes.MY)
                                .hasAuthority("PERM_SELF_PROFILE_READ")
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
                        // Story S8-01 (tasks 531-533): candidate email templates are company-wide settings.
                        // EmailTemplateService reads the codes again; the preview also checks the application's
                        // candidate scope (CandidateAccess) and logs the profile read.
                        .requestMatchers(HttpMethod.GET, "/api/v1/messaging/templates", "/api/v1/messaging/templates/*")
                                .hasAuthority("PERM_NOTIFICATIONS_READ_ALL")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/messaging/templates/*")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasAuthority("PERM_NOTIFICATIONS_READ_ALL"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_NOTIFICATIONS_WRITE_ALL")))
                        .requestMatchers(HttpMethod.POST, "/api/v1/messaging/templates/*/preview")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasAuthority("PERM_NOTIFICATIONS_READ_ALL"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_CANDIDATES_READ_ALL",
                                                "PERM_CANDIDATES_READ_SCOPED")))
                        // Story S8-02 (tasks 537-539): the candidate email log. EmailLogService reads the codes again
                        // and keeps the list to readable applications; a retry also needs the application's candidate
                        // write scope and, for offer and interview emails, OFFERS or INTERVIEWS write.
                        .requestMatchers(HttpMethod.GET, "/api/v1/messaging/emails", "/api/v1/messaging/emails/*")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_NOTIFICATIONS_READ_ALL",
                                                "PERM_NOTIFICATIONS_READ_SCOPED"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_CANDIDATES_READ_ALL",
                                                "PERM_CANDIDATES_READ_SCOPED")))
                        .requestMatchers(HttpMethod.POST, "/api/v1/messaging/emails/*/retry")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_NOTIFICATIONS_WRITE_ALL",
                                                "PERM_NOTIFICATIONS_WRITE_SCOPED"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_CANDIDATES_WRITE_ALL",
                                                "PERM_CANDIDATES_WRITE_SCOPED"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_CANDIDATES_READ_ALL",
                                                "PERM_CANDIDATES_READ_SCOPED")))
                        // Story S7-09 (tasks 526-527): bulk rejection letters. RejectionBatchService checks every
                        // selected application's candidate scope again before showing or queueing anything.
                        .requestMatchers(HttpMethod.POST, "/api/v1/messaging/rejections/preview")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_NOTIFICATIONS_READ_ALL",
                                                "PERM_NOTIFICATIONS_READ_SCOPED"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_CANDIDATES_READ_ALL",
                                                "PERM_CANDIDATES_READ_SCOPED")))
                        // Story S8-03 (tasks 543-545): every internal account's own inbox, whatever its other
                        // permissions (an approver must see what waits for it). NotificationService takes the recipient
                        // from the token only and reads the code again.
                        .requestMatchers(HttpMethod.GET, "/api/v1/notifications", "/api/v1/notifications/unread-count")
                                .hasAuthority("PERM_SELF_PROFILE_READ")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/notifications/*/read")
                                .hasAuthority("PERM_SELF_PROFILE_WRITE")
                        .requestMatchers(HttpMethod.POST, "/api/v1/notifications/read-all")
                                .hasAuthority("PERM_SELF_PROFILE_WRITE")
                        // Story S8-04 (tasks 549-551): the caller's own notification preferences; the service refuses
                        // turning off a mandatory type.
                        .requestMatchers(HttpMethod.GET, "/api/v1/notifications/preferences")
                                .hasAuthority("PERM_SELF_PROFILE_READ")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/notifications/preferences")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasAuthority("PERM_SELF_PROFILE_READ"),
                                        AuthorityAuthorizationManager.hasAuthority("PERM_SELF_PROFILE_WRITE")))
                        // Story S5-10 (tasks 418-420): stage SLA. Candidate readers see thresholds and flags (the
                        // flags only within their candidate scope); only CANDIDATES_WRITE_ALL changes a threshold.
                        .requestMatchers(HttpMethod.GET, "/api/v1/sla/thresholds", "/api/v1/sla/overdue")
                                .hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/sla/thresholds/*")
                                .hasAuthority("PERM_CANDIDATES_WRITE_ALL")
                        .requestMatchers(HttpMethod.POST, "/api/v1/messaging/rejections")
                                .access(AuthorizationManagers.allOf(
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_NOTIFICATIONS_WRITE_ALL",
                                                "PERM_NOTIFICATIONS_WRITE_SCOPED"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_CANDIDATES_WRITE_ALL",
                                                "PERM_CANDIDATES_WRITE_SCOPED"),
                                        AuthorityAuthorizationManager.hasAnyAuthority("PERM_CANDIDATES_READ_ALL",
                                                "PERM_CANDIDATES_READ_SCOPED")))
                        .requestMatchers(HttpMethod.POST, "/api/v1/applications/*/cv-extractions")
                                .hasAnyAuthority("PERM_CANDIDATES_WRITE_ALL", "PERM_CANDIDATES_WRITE_SCOPED")
                        .requestMatchers(HttpMethod.GET, "/api/v1/applications/*/cv-extractions/*")
                                .hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED")
                        .requestMatchers(HttpMethod.GET, "/api/v1/applications/*/confirmed-contact",
                                "/api/v1/applications/*/confirmed-contact/history")
                                .hasAnyAuthority("PERM_CANDIDATES_READ_ALL", "PERM_CANDIDATES_READ_SCOPED")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/applications/*/confirmed-contact")
                                .hasAnyAuthority("PERM_CANDIDATES_WRITE_ALL", "PERM_CANDIDATES_WRITE_SCOPED")
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
