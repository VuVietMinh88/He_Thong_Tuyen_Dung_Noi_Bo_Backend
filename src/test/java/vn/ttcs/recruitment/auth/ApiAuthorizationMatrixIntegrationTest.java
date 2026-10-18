package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.RecruitmentApplication;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.common.ApiError;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Independent authorization specification for every HTTP API. Each request goes straight to the server,
 * exactly as a user who bypasses the UI would send it. The frontend builds its menu from
 * GET /api/v1/auth/permissions, but hiding a menu item never replaces these server-side checks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true", "app.application-intake.enabled=false",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiAuthorizationMatrixIntegrationTest {
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-10-06T00:00:00Z");
    private static final UUID UNKNOWN_ID = UUID.randomUUID();
    private static final String INVALID_BODY = "{}";
    // A real catalog type, so permitted calls reach the item lookup or body validation.
    private static final String CATALOG_TYPE = "CANDIDATE_SOURCE";
    private static final String CATALOG_ITEMS = "/api/v1/recruitment-catalogs/" + CATALOG_TYPE + "/items";
    private static final ApiError FORBIDDEN_ERROR = ApiError.of("FORBIDDEN", "Bạn không có quyền thực hiện thao tác này.");

    private static final String SELF_PROFILE_READ = "SELF_PROFILE_READ";
    private static final String SELF_PROFILE_WRITE = "SELF_PROFILE_WRITE";
    private static final String SELF_SECURITY_WRITE = "SELF_SECURITY_WRITE";
    private static final String USER_ADMIN_READ_ALL = "USER_ADMIN_READ_ALL";
    private static final String USER_ADMIN_WRITE_ALL = "USER_ADMIN_WRITE_ALL";
    private static final String ORGANIZATION_READ_ALL = "ORGANIZATION_READ_ALL";
    private static final String ORGANIZATION_WRITE_ALL = "ORGANIZATION_WRITE_ALL";
    private static final String SALARY_RANGES_READ_ALL = "SALARY_RANGES_READ_ALL";
    private static final String SALARY_RANGES_WRITE_ALL = "SALARY_RANGES_WRITE_ALL";
    private static final String REQUISITIONS_READ_ALL = "REQUISITIONS_READ_ALL";
    private static final String REQUISITIONS_READ_SCOPED = "REQUISITIONS_READ_SCOPED";
    private static final String REQUISITIONS_WRITE_ALL = "REQUISITIONS_WRITE_ALL";
    private static final String REQUISITIONS_WRITE_SCOPED = "REQUISITIONS_WRITE_SCOPED";
    private static final String JOB_POSTINGS_WRITE_ALL = "JOB_POSTINGS_WRITE_ALL";
    private static final String JOB_POSTINGS_WRITE_SCOPED = "JOB_POSTINGS_WRITE_SCOPED";
    private static final String HEADCOUNT_PLANS_READ_ALL = "HEADCOUNT_PLANS_READ_ALL";
    private static final String HEADCOUNT_PLANS_WRITE_ALL = "HEADCOUNT_PLANS_WRITE_ALL";
    private static final String CANDIDATES_READ_ALL = "CANDIDATES_READ_ALL";
    private static final String CANDIDATES_READ_SCOPED = "CANDIDATES_READ_SCOPED";
    private static final String CANDIDATES_WRITE_ALL = "CANDIDATES_WRITE_ALL";
    private static final String CANDIDATES_WRITE_SCOPED = "CANDIDATES_WRITE_SCOPED";
    private static final String INTERVIEWS_READ_ALL = "INTERVIEWS_READ_ALL";
    private static final String INTERVIEWS_READ_SCOPED = "INTERVIEWS_READ_SCOPED";
    private static final String INTERVIEWS_WRITE_ALL = "INTERVIEWS_WRITE_ALL";
    private static final String INTERVIEWS_WRITE_SCOPED = "INTERVIEWS_WRITE_SCOPED";
    private static final String EVALUATIONS_READ_ALL = "EVALUATIONS_READ_ALL";
    private static final String EVALUATIONS_READ_SCOPED = "EVALUATIONS_READ_SCOPED";
    private static final String EVALUATIONS_WRITE_ALL = "EVALUATIONS_WRITE_ALL";
    private static final String EVALUATIONS_WRITE_SCOPED = "EVALUATIONS_WRITE_SCOPED";

    private static final Rule PUBLIC = new Rule(List.of(), false, false);

    // One row per handler mapping. Path variables get an unknown id, and write samples carry an invalid body,
    // so a call that passes authorization stops at 404 or validation instead of changing data.
    private static final List<Endpoint> ENDPOINTS = List.of(
            endpoint("POST", "/api/v1/applications/{id}/interviews", anyOf(INTERVIEWS_WRITE_ALL, INTERVIEWS_WRITE_SCOPED), INVALID_BODY, 400),
            endpoint("GET", "/api/health", PUBLIC, null, 200),
            endpoint("GET", "/api/v1/health", PUBLIC, null, 200),
            endpoint("POST", "/api/v1/auth/login", PUBLIC, INVALID_BODY, 400),
            endpoint("POST", "/api/v1/auth/refresh", PUBLIC, INVALID_BODY, 400),
            endpoint("POST", "/api/v1/auth/forgot-password", PUBLIC, INVALID_BODY, 400),
            endpoint("POST", "/api/v1/auth/reset-password", PUBLIC, INVALID_BODY, 400),
            endpoint("POST", "/api/v1/auth/activate-account", PUBLIC, INVALID_BODY, 400),
            endpoint("GET", "/api/v1/auth/me", permission(SELF_PROFILE_READ), null, 200),
            endpoint("GET", "/api/v1/auth/permissions", permission(SELF_PROFILE_READ), null, 200),
            endpoint("GET", "/api/v1/profile", permission(SELF_PROFILE_READ), null, 200),
            endpoint("PUT", "/api/v1/profile", permission(SELF_PROFILE_WRITE), INVALID_BODY, 400),
            // A permitted logout revokes the session it uses, so every call logs in again first.
            new Endpoint("POST", "/api/v1/auth/logout", permission(SELF_SECURITY_WRITE), null, 204, true),
            endpoint("POST", "/api/v1/auth/change-password", permission(SELF_SECURITY_WRITE), INVALID_BODY, 400),
            endpoint("GET", "/api/v1/accounts", permission(USER_ADMIN_READ_ALL), null, 200),
            endpoint("GET", "/api/v1/accounts/{id}", permission(USER_ADMIN_READ_ALL), null, 404),
            endpoint("POST", "/api/v1/accounts", adminWith(USER_ADMIN_WRITE_ALL), INVALID_BODY, 400),
            endpoint("PUT", "/api/v1/accounts/{id}", adminWith(USER_ADMIN_WRITE_ALL), INVALID_BODY, 400),
            endpoint("PUT", "/api/v1/accounts/{id}/roles/{role}", adminWith(USER_ADMIN_WRITE_ALL), null, 404),
            endpoint("DELETE", "/api/v1/accounts/{id}/roles/{role}", adminWith(USER_ADMIN_WRITE_ALL), null, 404),
            // Feedback item 8: the complete role set in one request; "{}" has neither roles nor expectedRoles, so it
            // stops at validation.
            endpoint("PUT", "/api/v1/accounts/{id}/roles", adminWith(USER_ADMIN_WRITE_ALL), INVALID_BODY, 400),
            endpoint("PUT", "/api/v1/accounts/{id}/lock", adminWith(USER_ADMIN_WRITE_ALL), INVALID_BODY, 400),
            endpoint("DELETE", "/api/v1/accounts/{id}/lock", adminWith(USER_ADMIN_WRITE_ALL), null, 404),
            // Feedback item 10: the work an account still holds, for the lock dialog; the unknown id stops a permitted
            // call at 404 ACCOUNT_NOT_FOUND.
            endpoint("GET", "/api/v1/accounts/{id}/handover-items", adminWithAll(USER_ADMIN_WRITE_ALL, REQUISITIONS_READ_ALL), null, 404),
            // The handover itself also needs REQUISITIONS_WRITE_ALL; the empty body stops a permitted call at 400 before
            // any lock.
            endpoint("POST", "/api/v1/accounts/{id}/handover", adminWithAll(USER_ADMIN_WRITE_ALL, REQUISITIONS_READ_ALL, REQUISITIONS_WRITE_ALL), INVALID_BODY, 400),
            endpoint("GET", "/api/v1/departments", permission(ORGANIZATION_READ_ALL), null, 200),
            endpoint("GET", "/api/v1/departments/tree", permission(ORGANIZATION_READ_ALL), null, 200),
            endpoint("GET", "/api/v1/departments/{id}", permission(ORGANIZATION_READ_ALL), null, 404),
            endpoint("POST", "/api/v1/departments", permission(ORGANIZATION_WRITE_ALL), INVALID_BODY, 400),
            endpoint("PUT", "/api/v1/departments/{id}", permission(ORGANIZATION_WRITE_ALL), INVALID_BODY, 400),
            endpoint("GET", "/api/v1/positions", permission(ORGANIZATION_READ_ALL), null, 200),
            endpoint("GET", "/api/v1/positions/{id}", permission(ORGANIZATION_READ_ALL), null, 404),
            endpoint("POST", "/api/v1/positions", allOf(ORGANIZATION_WRITE_ALL, SALARY_RANGES_WRITE_ALL), INVALID_BODY, 400),
            endpoint("PUT", "/api/v1/positions/{id}", allOf(ORGANIZATION_WRITE_ALL, SALARY_RANGES_WRITE_ALL), INVALID_BODY, 400),
            endpoint("GET", "/api/v1/competency-frameworks", permission(ORGANIZATION_READ_ALL), null, 200),
            endpoint("GET", "/api/v1/competency-frameworks/{id}", permission(ORGANIZATION_READ_ALL), null, 404),
            endpoint("POST", "/api/v1/competency-frameworks", permission(ORGANIZATION_WRITE_ALL), INVALID_BODY, 400),
            endpoint("PUT", "/api/v1/competency-frameworks/{id}", permission(ORGANIZATION_WRITE_ALL), INVALID_BODY, 400),
            endpoint("PUT", "/api/v1/positions/{id}/competency-framework", permission(ORGANIZATION_WRITE_ALL), INVALID_BODY, 400),
            endpoint("DELETE", "/api/v1/positions/{id}/competency-framework", permission(ORGANIZATION_WRITE_ALL), null, 404),
            endpoint("GET", "/api/v1/positions/{id}/evaluation-criteria", permission(ORGANIZATION_READ_ALL), null, 404),
            endpoint("GET", "/api/v1/interview-questions/{id}", permission(ORGANIZATION_READ_ALL), null, 404),
            endpoint("POST", "/api/v1/interview-questions", permission(ORGANIZATION_WRITE_ALL), INVALID_BODY, 400),
            endpoint("PUT", "/api/v1/interview-questions/{id}", permission(ORGANIZATION_WRITE_ALL), INVALID_BODY, 400),
            endpoint("GET", "/api/v1/interview-questions", permission(ORGANIZATION_READ_ALL), null, 200),
            // Requisitions: ALL and SCOPED writers both pass the matcher; RequisitionService applies the scope.
            endpoint("POST", "/api/v1/requisitions", anyOf(REQUISITIONS_WRITE_ALL, REQUISITIONS_WRITE_SCOPED), INVALID_BODY, 400),
            endpoint("GET", "/api/v1/requisitions", anyOf(REQUISITIONS_READ_ALL, REQUISITIONS_READ_SCOPED), null, 200),
            endpoint("GET", "/api/v1/requisitions/{id}", anyOf(REQUISITIONS_READ_ALL, REQUISITIONS_READ_SCOPED), null, 404),
            endpoint("PUT", "/api/v1/requisitions/{id}", anyOf(REQUISITIONS_WRITE_ALL, REQUISITIONS_WRITE_SCOPED), INVALID_BODY, 400),
            // Task 278: copying; the empty JSON object is a valid body, so a permitted call stops at the unknown id.
            endpoint("POST", "/api/v1/requisitions/{id}/copy", anyOf(REQUISITIONS_WRITE_ALL, REQUISITIONS_WRITE_SCOPED), INVALID_BODY, 404),
            // Task 257: submitting; the empty body is valid, so a permitted call stops at the unknown id.
            endpoint("POST", "/api/v1/requisitions/{id}/submit", anyOf(REQUISITIONS_WRITE_ALL, REQUISITIONS_WRITE_SCOPED), INVALID_BODY, 404),
            // Task 262: deciding is a REQUISITIONS write; the empty body stops a permitted call at 400 (no action).
            endpoint("POST", "/api/v1/requisitions/{id}/approvals", anyOf(REQUISITIONS_WRITE_ALL, REQUISITIONS_WRITE_SCOPED), INVALID_BODY, 400),
            // Task 267: the progress of an unknown requisition is 404; the inbox of every allowed caller is a page.
            endpoint("GET", "/api/v1/requisitions/{id}/approvals", anyOf(REQUISITIONS_READ_ALL, REQUISITIONS_READ_SCOPED), null, 404),
            endpoint("GET", "/api/v1/requisitions/approval-inbox", anyOf(REQUISITIONS_READ_ALL, REQUISITIONS_READ_SCOPED), null, 200),
            // Task 290: only REQUISITIONS_WRITE_ALL changes a requisition's status; the empty body stops a permitted
            // call at 400 before any lookup.
            endpoint("POST", "/api/v1/requisitions/{id}/status-changes", permission(REQUISITIONS_WRITE_ALL), INVALID_BODY, 400),
            // Task 291: the candidates still in the pipeline, for HR's close dialog; the unknown id makes a permitted call stop at 404.
            endpoint("GET", "/api/v1/requisitions/{id}/active-pipeline", permission(REQUISITIONS_WRITE_ALL), null, 404),
            // Task 284: only ALL writers assign; the empty body stops a permitted call at 400 before any lookup.
            endpoint("POST", "/api/v1/requisitions/{id}/assign", permission(REQUISITIONS_WRITE_ALL), INVALID_BODY, 400),
            endpoint("POST", "/api/v1/requisitions/{id}/unassign", permission(REQUISITIONS_WRITE_ALL), INVALID_BODY, 400),
            endpoint("GET", "/api/v1/requisitions/{id}/assignment", anyOf(REQUISITIONS_READ_ALL, REQUISITIONS_READ_SCOPED), null, 404),
            // Task 286: the history of recruiter changes, read like the requisition.
            endpoint("GET", "/api/v1/requisitions/{id}/assignment-history", anyOf(REQUISITIONS_READ_ALL, REQUISITIONS_READ_SCOPED), null, 404),
            // Task 275: the HR exceptions of a requisition; the unknown id makes a permitted call stop at 404.
            endpoint("GET", "/api/v1/requisitions/{id}/headcount-overrides", anyOf(REQUISITIONS_READ_ALL, REQUISITIONS_READ_SCOPED), null, 404),
            // Task 197: deleting a department. The unknown id makes a permitted call stop at 404.
            endpoint("DELETE", "/api/v1/departments/{id}", permission(ORGANIZATION_WRITE_ALL), null, 404),
            endpoint("GET", "/api/v1/recruitment-catalogs/{type}/items", permission(ORGANIZATION_READ_ALL), null, 200),
            endpoint("GET", "/api/v1/recruitment-catalogs/{type}/items/{id}", permission(ORGANIZATION_READ_ALL), null, 404),
            endpoint("POST", "/api/v1/recruitment-catalogs/{type}/items", permission(ORGANIZATION_WRITE_ALL), INVALID_BODY, 400),
            endpoint("PUT", "/api/v1/recruitment-catalogs/{type}/items/{id}", permission(ORGANIZATION_WRITE_ALL), INVALID_BODY, 400),
            endpoint("DELETE", "/api/v1/recruitment-catalogs/{type}/items/{id}", permission(ORGANIZATION_WRITE_ALL), null, 404),
            endpoint("PUT", "/api/v1/recruitment-catalogs/{type}/order", permission(ORGANIZATION_WRITE_ALL), INVALID_BODY, 400),
            // No company page is saved in this test, so both reads stop at 404.
            endpoint("GET", "/api/v1/company-profile", permission(JOB_POSTINGS_WRITE_ALL), null, 404),
            endpoint("PUT", "/api/v1/company-profile", permission(JOB_POSTINGS_WRITE_ALL), INVALID_BODY, 400),
            endpoint("POST", "/api/v1/company-profile/preview", permission(JOB_POSTINGS_WRITE_ALL), INVALID_BODY, 400),
            endpoint("GET", "/api/v1/public/company-profile", PUBLIC, null, 404),
            // The upload only accepts multipart/form-data, so the JSON sample stops at 415 and stores nothing.
            endpoint("POST", "/api/v1/company-profile/media", permission(JOB_POSTINGS_WRITE_ALL), INVALID_BODY, 415),
            endpoint("GET", "/api/v1/company-profile/media/{id}", permission(JOB_POSTINGS_WRITE_ALL), null, 404),
            endpoint("GET", "/api/v1/public/company-media/{id}", PUBLIC, null, 404),
            // No fixture account has an avatar, so reads stop at 404 and DELETE has nothing to remove. The JSON
            // sample carries no file part, so an allowed upload stops at AVATAR_FILE_REQUIRED.
            endpoint("GET", "/api/v1/profile/avatar", permission(SELF_PROFILE_READ), null, 404),
            endpoint("PUT", "/api/v1/profile/avatar", permission(SELF_PROFILE_WRITE), INVALID_BODY, 400),
            endpoint("DELETE", "/api/v1/profile/avatar", permission(SELF_PROFILE_WRITE), null, 204),
            endpoint("GET", "/api/v1/accounts/{id}/avatar", permission(SELF_PROFILE_READ), null, 404),
            endpoint("GET", "/api/v1/accounts/import/template", adminWith(USER_ADMIN_WRITE_ALL), null, 200),
            // The JSON sample carries no file part, so an allowed call stops at IMPORT_FILE_REQUIRED.
            endpoint("POST", "/api/v1/accounts/import/preview", adminWith(USER_ADMIN_WRITE_ALL), INVALID_BODY, 400),
            // The import itself stops at IMPORT_FILE_REQUIRED too, so an allowed call creates nothing.
            endpoint("POST", "/api/v1/accounts/import", adminWith(USER_ADMIN_WRITE_ALL), INVALID_BODY, 400),
            // Task 273: headcount plans. The remaining lookup without departmentId stops at 400 for every allowed
            // caller, before the department scope of REQUISITIONS_READ_SCOPED is checked.
            endpoint("GET", "/api/v1/headcount-plans", permission(HEADCOUNT_PLANS_READ_ALL), null, 200),
            endpoint("GET", "/api/v1/headcount-plans/remaining",
                    anyOf(HEADCOUNT_PLANS_READ_ALL, REQUISITIONS_READ_ALL, REQUISITIONS_READ_SCOPED), null, 400),
            endpoint("GET", "/api/v1/headcount-plans/{id}", permission(HEADCOUNT_PLANS_READ_ALL), null, 404),
            endpoint("POST", "/api/v1/headcount-plans", permission(HEADCOUNT_PLANS_WRITE_ALL), INVALID_BODY, 400),
            endpoint("PUT", "/api/v1/headcount-plans/{id}", permission(HEADCOUNT_PLANS_WRITE_ALL), INVALID_BODY, 400),
            // Tasks 254/255: approval-flow configuration. The matcher also requires role ADMIN or HR_MANAGER, which
            // every holder of these codes has in the seed. The empty body stops a permitted write or preview at 400.
            endpoint("GET", "/api/v1/approval-flows", permission(REQUISITIONS_READ_ALL), null, 200),
            endpoint("GET", "/api/v1/approval-flows/{id}", permission(REQUISITIONS_READ_ALL), null, 404),
            endpoint("POST", "/api/v1/approval-flows", permission(REQUISITIONS_WRITE_ALL), INVALID_BODY, 400),
            endpoint("PUT", "/api/v1/approval-flows/{id}", permission(REQUISITIONS_WRITE_ALL), INVALID_BODY, 400),
            endpoint("POST", "/api/v1/approval-flows/preview", permission(REQUISITIONS_READ_ALL), INVALID_BODY, 400),
            // Tasks 302-304: job postings. ALL and SCOPED writers pass the matcher; JobPostingService also needs a JOB_POSTINGS
            // READ permission (every writer has one in the seed) and applies the recruiter scope. JOB_POSTINGS_READ_ALL alone
            // (HIRING_MANAGER, APPROVER) reads only the public portal. The empty body stops a permitted write at 400 before any
            // lookup; the unknown id stops reads at 404 before the scope.
            endpoint("POST", "/api/v1/job-postings", anyOf(JOB_POSTINGS_WRITE_ALL, JOB_POSTINGS_WRITE_SCOPED), INVALID_BODY, 400),
            endpoint("GET", "/api/v1/job-postings/source-requisitions", anyOf(JOB_POSTINGS_WRITE_ALL, JOB_POSTINGS_WRITE_SCOPED), null, 200),
            endpoint("GET", "/api/v1/job-postings", anyOf(JOB_POSTINGS_WRITE_ALL, JOB_POSTINGS_WRITE_SCOPED), null, 200),
            endpoint("GET", "/api/v1/job-postings/{id}", anyOf(JOB_POSTINGS_WRITE_ALL, JOB_POSTINGS_WRITE_SCOPED), null, 404),
            endpoint("PUT", "/api/v1/job-postings/{id}", anyOf(JOB_POSTINGS_WRITE_ALL, JOB_POSTINGS_WRITE_SCOPED), INVALID_BODY, 400),
            // Task 308: sending for review is the authors' write; reviewing and publishing need JOB_POSTINGS_WRITE_ALL (ADMIN,
            // HR_MANAGER). The empty body stops every permitted call at 400 (expectedRevision, action). "/approvals" and
            // "/submit" fall under approvalHistoryHasNoEditOrDeleteRoute.
            endpoint("POST", "/api/v1/job-postings/{id}/submit", anyOf(JOB_POSTINGS_WRITE_ALL, JOB_POSTINGS_WRITE_SCOPED), INVALID_BODY, 400),
            endpoint("POST", "/api/v1/job-postings/{id}/approvals", permission(JOB_POSTINGS_WRITE_ALL), INVALID_BODY, 400),
            endpoint("POST", "/api/v1/job-postings/{id}/publish", permission(JOB_POSTINGS_WRITE_ALL), INVALID_BODY, 400),
            // Task 355 (story S4-08): taking a published posting down is the composers' write, like submit; the empty
            // body stops every permitted call at 400 (expectedRevision) before the reason, any lock or lookup.
            endpoint("POST", "/api/v1/job-postings/{id}/unpublish", anyOf(JOB_POSTINGS_WRITE_ALL, JOB_POSTINGS_WRITE_SCOPED), INVALID_BODY, 400),
            // Task 309: the preview is the authors' view of exactly what candidates will see; the portal is public and answers
            // 404 for anything not published (here the unknown id) and an empty first page to everyone.
            endpoint("GET", "/api/v1/job-postings/{id}/preview", anyOf(JOB_POSTINGS_WRITE_ALL, JOB_POSTINGS_WRITE_SCOPED), null, 404),
            endpoint("GET", "/api/v1/public/job-postings", PUBLIC, null, 200),
            endpoint("GET", "/api/v1/public/job-postings/{id}", PUBLIC, null, 404),
            // Anonymous application intake is declared publicly but remains disabled until quota/receipt
            // integrations are complete. Admission refuses before JSON/multipart parsing for every role.
            endpoint("POST", "/api/v1/public/job-postings/{id}/applications", PUBLIC, INVALID_BODY, 503),
            endpoint("GET", "/api/v1/public/application-sources", PUBLIC, null, 200),
            // Task 339: a candidate's lookup by tracking code and email; anonymous, a stale bearer is ignored, and the
            // empty body fails validation before the tracking quota counts anything.
            endpoint("POST", "/api/v1/public/applications/lookup", PUBLIC, INVALID_BODY, 400),
            // Task 345: a candidate's withdrawal, guarded and counted like the lookup; the empty body fails validation
            // (code, email and confirm missing) before the quota or any lock.
            endpoint("POST", "/api/v1/public/applications/withdraw", PUBLIC, INVALID_BODY, 400),
            // Task 315: the values of the portal's filter lists, public like the list; empty lists to everyone here.
            endpoint("GET", "/api/v1/public/job-postings/filters", PUBLIC, null, 200),
            // Task 310: the review and publication history is read like the posting; the unknown id stops it at 404.
            endpoint("GET", "/api/v1/job-postings/{id}/history", anyOf(JOB_POSTINGS_WRITE_ALL, JOB_POSTINGS_WRITE_SCOPED), null, 404),
            // Task 361: the unified candidate profile; both CANDIDATES read scopes pass (APPROVER is SCOPED from
            // V33_1), and the unknown id stops at 404 before any scope check.
            endpoint("GET", "/api/v1/candidates/{id}/unified-profile", anyOf(CANDIDATES_READ_ALL, CANDIDATES_READ_SCOPED), null, 404),
            endpoint("GET", "/api/v1/application-board", anyOf(CANDIDATES_READ_ALL, CANDIDATES_READ_SCOPED), null, 200),
            endpoint("GET", "/api/v1/application-board/positions", anyOf(CANDIDATES_READ_ALL, CANDIDATES_READ_SCOPED), null, 200),
            // Task 362: one application's CV, read like the profile; the unknown application stops at 404 before any
            // scope check.
            endpoint("GET", "/api/v1/applications/{id}/cv", anyOf(CANDIDATES_READ_ALL, CANDIDATES_READ_SCOPED), null, 404),
            endpoint("GET", "/api/v1/applications/{id}/timeline", anyOf(CANDIDATES_READ_ALL, CANDIDATES_READ_SCOPED), null, 404),
            // Task 380: a stage change is a CANDIDATES write (ALL or SCOPED); the empty body fails validation before
            // any lock or lookup. "/stage-changes" falls under stageHistoryHasNoEditOrDeleteRoute.
            endpoint("POST", "/api/v1/applications/{id}/stage-changes", anyOf(CANDIDATES_WRITE_ALL, CANDIDATES_WRITE_SCOPED), INVALID_BODY, 400),
            endpoint("GET", "/api/v1/applications/{id}/screening", anyOf(CANDIDATES_READ_ALL, CANDIDATES_READ_SCOPED), null, 404),
            endpoint("PUT", "/api/v1/applications/{id}/screening/tags", anyOf(CANDIDATES_WRITE_ALL, CANDIDATES_WRITE_SCOPED), INVALID_BODY, 400),
            endpoint("PUT", "/api/v1/applications/{id}/screening/rating", anyOf(CANDIDATES_WRITE_ALL, CANDIDATES_WRITE_SCOPED), INVALID_BODY, 400),
            endpoint("POST", "/api/v1/applications/{id}/screening/notes", anyOf(CANDIDATES_WRITE_ALL, CANDIDATES_WRITE_SCOPED), INVALID_BODY, 400),
            // Task 351: a staff referral, open to every internal role like the avatar write; multipart only, so the JSON
            // sample stops at 415 before readiness, the form or the attempt quota (like POST /company-profile/media).
            endpoint("POST", "/api/v1/referrals", permission(SELF_PROFILE_WRITE), INVALID_BODY, 415),
            // Task 352: the referrer's own list, read like a colleague's avatar; nobody here has referred anyone, so
            // every permitted caller gets an empty array.
            endpoint("GET", "/api/v1/referrals/my-referrals", permission(SELF_PROFILE_READ), null, 200));

    private static final List<String> STATE_TABLES = List.of("user_accounts", "user_roles", "departments",
            "auth_sessions", "account_activation_tokens", "password_reset_tokens", "role_permissions", "positions",
            "competency_frameworks", "competency_criteria", "interview_questions", "recruitment_requisitions", "recruitment_catalog_items",
            "company_profile", "company_profile_images", "user_avatars", "headcount_plans",
            "requisition_headcount_overrides", "requisition_recruiters",
            "requisition_recruiter_changes", "approval_flows", "approval_flow_versions", "approval_flow_steps",
            "requisition_workflow_events", "job_postings", "job_posting_authors", "job_posting_events");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping handlerMapping;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String deviceId = UUID.randomUUID().toString();
    private final Map<Identity, Actor> actors = new EnumMap<>(Identity.class);
    private List<Map<String, Object>> permissionSeed;
    private String fixturePasswordHash;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
    }

    @BeforeEach
    void createOneLoggedInAccountPerIdentity() throws Exception {
        permissionSeed = jdbc.queryForList("SELECT role_code, permission_code FROM role_permissions");
        clock.set(START);
        // Approval-flow versions and the approval history can never be deleted (triggers of V18), and the history
        // references both requisitions and flow versions, but TRUNCATE of all four together does not fire row triggers.
        // Task 308: the job posting history goes with them (it becomes append-only in V23).
        jdbc.execute("TRUNCATE job_posting_events, requisition_workflow_events, approval_flow_steps, approval_flow_versions, approval_flows");
        // Postings reference requisitions, accounts and catalog values (RESTRICT), so they go first, after their authors.
        jdbc.update("DELETE FROM job_posting_authors");
        jdbc.update("DELETE FROM job_postings");
        // Requisitions reference departments, positions and accounts (ON DELETE RESTRICT), so they go first, after the
        // rows that reference them.
        jdbc.update("DELETE FROM requisition_recruiter_changes");
        jdbc.update("DELETE FROM requisition_recruiters");
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET department_id = NULL, admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("UPDATE departments SET parent_id = NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM competency_frameworks");
        jdbc.update("DELETE FROM recruitment_catalog_items");
        jdbc.update("DELETE FROM company_profile");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        fixturePasswordHash = jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE email = ?",
                String.class, Identity.ADMIN.email());
        for (Identity identity : Identity.values()) {
            if (identity != Identity.ADMIN) {
                accounts.saveAndFlush(new Account(identity.email(), identity.name(), fixturePasswordHash,
                        identity.role == null ? Set.of() : Set.of(identity.role), START));
            }
            actors.put(identity, login(identity));
        }
    }

    @AfterEach
    void restorePermissionSeed() {
        if (permissionSeed == null) { return; }
        jdbc.update("DELETE FROM role_permissions");
        jdbc.batchUpdate("INSERT INTO role_permissions (role_code, permission_code) VALUES (?, ?)", permissionSeed.stream()
                .map(row -> new Object[] {row.get("role_code"), row.get("permission_code")}).toList());
    }

    @Test
    void approvalHistoryHasNoEditOrDeleteRoute() {
        // Task 268: the approval history is append-only, so no handler may offer to change or remove it. Task 290:
        // nor the status changes, which add to the same history.
        Set<String> editing = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            for (String pattern : info.getPatternValues()) {
                boolean approval = pattern.contains("/approvals") || pattern.contains("/approval-inbox")
                        || pattern.contains("/submit") || pattern.contains("/status-changes");
                info.getMethodsCondition().getMethods().stream()
                        .filter(method -> method == RequestMethod.PUT || method == RequestMethod.PATCH
                                || method == RequestMethod.DELETE)
                        .filter(method -> approval)
                        .forEach(method -> editing.add(method.name() + " " + pattern));
            }
        });
        assertThat(editing).as("approval routes that edit or delete").isEmpty();
    }

    @Test
    void jobPostingTrailHasNoEditOrDeleteRoute() {
        // Task 310: who published a posting and when, and its review history, are only ever added to. Task 355: taking
        // a posting down adds to the trail too ("/unpublish" does not contain "/publish").
        Set<String> editing = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            for (String pattern : info.getPatternValues()) {
                boolean trail = pattern.startsWith("/api/v1/job-postings/")
                        && (pattern.contains("/publish") || pattern.contains("/unpublish")
                                || pattern.contains("/history"));
                info.getMethodsCondition().getMethods().stream()
                        .filter(method -> method == RequestMethod.PUT || method == RequestMethod.PATCH
                                || method == RequestMethod.DELETE)
                        .filter(method -> trail)
                        .forEach(method -> editing.add(method.name() + " " + pattern));
            }
        });
        assertThat(editing).as("job posting trail routes that edit or delete").isEmpty();
    }

    @Test
    void stageHistoryHasNoEditOrDeleteRoute() {
        // Task 380: every stage change adds one row to the application's history (V34), which is never edited.
        Set<String> editing = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            for (String pattern : info.getPatternValues()) {
                boolean history = pattern.contains("/stage-changes") || pattern.contains("/stage-history");
                info.getMethodsCondition().getMethods().stream()
                        .filter(method -> method == RequestMethod.PUT || method == RequestMethod.PATCH
                                || method == RequestMethod.DELETE)
                        .filter(method -> history)
                        .forEach(method -> editing.add(method.name() + " " + pattern));
            }
        });
        assertThat(editing).as("stage history routes that edit or delete").isEmpty();
    }

    @Test
    void everyApplicationApiIsDeclaredInTheAuthorizationTable() {
        String applicationPackage = RecruitmentApplication.class.getPackageName();
        Set<String> mapped = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            String handlerPackage = handler.getBeanType().getPackageName();
            if (!handlerPackage.equals(applicationPackage) && !handlerPackage.startsWith(applicationPackage + ".")) {
                return;
            }
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (methods.isEmpty()) { mapped.add("ANY " + pattern); }
                methods.forEach(method -> mapped.add(method.name() + " " + pattern));
            }
        });
        Set<String> declared = ENDPOINTS.stream().map(Endpoint::key).collect(Collectors.toCollection(TreeSet::new));
        assertThat(declared).as("ENDPOINTS contains duplicate rows").hasSameSizeAs(ENDPOINTS);

        Set<String> undeclared = new TreeSet<>(mapped);
        undeclared.removeAll(declared);
        Set<String> stale = new TreeSet<>(declared);
        stale.removeAll(mapped);
        assertThat(undeclared).as("APIs without an authorization row; declare their rule in ENDPOINTS and SecurityConfiguration")
                .isEmpty();
        assertThat(stale).as("ENDPOINTS rows that match no API; update or remove them").isEmpty();
    }

    @TestFactory
    Stream<DynamicTest> everyIdentityIsAllowedOrForbiddenExactlyAsTheRoleSeedSpecifies() {
        return ENDPOINTS.stream().flatMap(endpoint -> Arrays.stream(Identity.values()).map(identity ->
                dynamicTest(identity + ": " + endpoint, () -> {
                    var response = call(endpoint, identity);
                    if (endpoint.rule().allows(identity.roles(), identity.expectedGrants())) {
                        assertAllowed(endpoint, response);
                    } else {
                        assertForbidden(endpoint.toString(), response);
                    }
                })));
    }

    @TestFactory
    Stream<DynamicTest> anonymousCallersAreRejectedFromProtectedApisButReachPublicOnes() {
        Stream<DynamicTest> anonymous = ENDPOINTS.stream().map(endpoint -> dynamicTest("anonymous: " + endpoint, () -> {
            var response = request(endpoint.method(), endpoint.samplePath(), endpoint.body(), null);
            if (endpoint.rule().isPublic()) {
                assertAllowed(endpoint, response);
            } else {
                assertUnauthorized(endpoint, response);
            }
        }));
        // These endpoints authenticate their body, so an expired or forged bearer must not block them.
        Stream<DynamicTest> staleBearer = ENDPOINTS.stream()
                .filter(endpoint -> endpoint.rule().isPublic() && endpoint.method().equals("POST"))
                .map(endpoint -> dynamicTest("stale bearer: " + endpoint, () ->
                        assertAllowed(endpoint, request(endpoint.method(), endpoint.samplePath(), endpoint.body(), "not-a-jwt"))));
        return Stream.concat(anonymous, staleBearer);
    }

    @TestFactory
    Stream<DynamicTest> serverAllowsACallOnlyWhenThePermissionListBehindTheMenuShowsIt() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (Identity identity : Identity.values()) {
            Actor actor = actors.get(identity);
            Set<String> menuPermissions = menuPermissions(actor);
            for (Endpoint endpoint : ENDPOINTS) {
                if (endpoint.rule().isPublic()) { continue; }
                boolean shown = endpoint.rule().allows(actor.roles(), menuPermissions);
                tests.add(dynamicTest(identity + (shown ? " sees " : " does not see ") + endpoint, () -> {
                    var response = call(endpoint, identity);
                    if (shown) {
                        assertAllowed(endpoint, response);
                    } else {
                        assertForbidden(endpoint.toString(), response);
                    }
                }));
            }
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> forbiddenCallsWithValidBodiesLeaveTheDatabaseUnchanged() throws Exception {
        UUID admin = actors.get(Identity.ADMIN).id();
        UUID hrManager = actors.get(Identity.HR_MANAGER).id();
        UUID recruiter = actors.get(Identity.RECRUITER).id();
        UUID hiringManager = actors.get(Identity.HIRING_MANAGER).id();
        UUID interviewer = actors.get(Identity.INTERVIEWER).id();
        UUID department = UUID.fromString(expect(request("POST", "/api/v1/departments", json.writeValueAsString(Map.of(
                "code", "TARGET", "name", "Target", "managerUserId", interviewer, "active", true)),
                actors.get(Identity.ADMIN).token()), 201).path("id").asText());
        UUID position = UUID.fromString(expect(request("POST", "/api/v1/positions", json.writeValueAsString(
                positionBody("TARGET", "Target")), actors.get(Identity.HR_MANAGER).token()), 201).path("id").asText());
        UUID framework = UUID.fromString(expect(request("POST", "/api/v1/competency-frameworks", json.writeValueAsString(
                competencyFrameworkBody("TARGET", "Target")), actors.get(Identity.HR_MANAGER).token()), 201).path("id").asText());
        Map<String, Object> requisitionBody = Map.of("positionId", position, "departmentId", department,
                "headcount", 1, "reason", "NEW_HEADCOUNT");
        UUID requisition = UUID.fromString(expect(request("POST", "/api/v1/requisitions",
                json.writeValueAsString(requisitionBody), actors.get(Identity.HR_MANAGER).token()), 201).path("id").asText());
        Map<String, Object> changedRequisition = Map.of("positionId", position, "departmentId", department,
                "headcount", 9, "reason", "REPLACEMENT");
        // Task 257: a complete draft of TARGET, ready to be submitted (TARGET's flow below makes HR its approver).
        Map<String, Object> readyBody = new java.util.HashMap<>(requisitionBody);
        readyBody.putAll(Map.of("proposedSalaryMin", 20_000_000L, "proposedSalaryMax", 22_000_000L,
                "neededBy", "2026-12-01", "jobDescription", "Mô tả công việc", "candidateRequirements", "Yêu cầu"));
        UUID ready = UUID.fromString(expect(request("POST", "/api/v1/requisitions", json.writeValueAsString(readyBody),
                actors.get(Identity.ADMIN).token()), 201).path("id").asText());
        // Task 197: nothing uses this department, so a permitted DELETE would remove it.
        // Tasks 254/255: TARGET has an approval flow, so a forbidden PUT that slipped through would add a version.
        UUID flow = UUID.fromString(expect(request("POST", "/api/v1/approval-flows", json.writeValueAsString(Map.of(
                "departmentId", department, "name", "Target",
                "steps", List.of(Map.of("position", 1, "approverRole", "HR_MANAGER")))),
                actors.get(Identity.HR_MANAGER).token()), 201).path("id").asText());
        // Task 262: a requisition of TARGET created and submitted by ADMIN; its only level is HR_MANAGER's, so a
        // forbidden decision that slipped through would add an event and change its status.
        UUID pending = UUID.fromString(expect(request("POST", "/api/v1/requisitions", json.writeValueAsString(readyBody),
                actors.get(Identity.ADMIN).token()), 201).path("id").asText());
        expect(request("POST", "/api/v1/requisitions/" + pending + "/submit", null, actors.get(Identity.ADMIN).token()), 200);
        // Task 303: approved requisitions of TARGET (ADMIN creates and submits, HR_MANAGER takes the only level). RECRUITER is
        // assigned to "requisition" only, so it may compose none of these postings.
        UUID approved = approvedRequisition(readyBody);
        UUID vacant = approvedRequisition(readyBody);      // no posting: a create that slipped through would add one
        UUID workLocation = catalogValue("WORK_LOCATION", "HN", "Hà Nội");
        UUID employmentType = catalogValue("EMPLOYMENT_TYPE", "FULL_TIME", "Toàn thời gian");
        UUID posting = UUID.fromString(expect(request("POST", "/api/v1/job-postings", json.writeValueAsString(Map.of(
                "requisitionId", approved, "workLocationId", workLocation, "employmentTypeId", employmentType,
                "applicationDeadline", "2026-11-30", "showSalary", false)), actors.get(Identity.HR_MANAGER).token()), 201)
                .path("id").asText());
        Map<String, Object> newPosting = Map.of("requisitionId", vacant, "showSalary", false);
        // Task 308: complete postings of TARGET in review. "waiting" was written and sent by HR_MANAGER, "reviewed" was
        // approved by ADMIN, and HR_MANAGER saved "authored" (an author without creating or sending it), so a forbidden
        // step that slipped through would change a posting and add an event.
        Map<String, Object> completePosting = Map.of("workLocationId", workLocation, "employmentTypeId", employmentType,
                "applicationDeadline", "2026-11-30", "showSalary", false);
        UUID waiting = posting(approvedRequisition(readyBody), completePosting, Identity.HR_MANAGER);
        submitPosting(waiting, 1, Identity.HR_MANAGER);
        UUID reviewed = posting(approvedRequisition(readyBody), completePosting, Identity.HR_MANAGER);
        submitPosting(reviewed, 1, Identity.HR_MANAGER);
        expect(request("POST", "/api/v1/job-postings/" + reviewed + "/approvals", json.writeValueAsString(Map.of(
                "action", "APPROVE", "expectedRevision", 2)), actors.get(Identity.ADMIN).token()), 200);
        UUID authored = posting(approvedRequisition(readyBody), completePosting, Identity.ADMIN);
        Map<String, Object> authoredContent = new java.util.HashMap<>(completePosting);
        authoredContent.putAll(Map.of("title", "Lập trình viên", "jobDescription", "Mô tả công việc",
                "candidateRequirements", "Yêu cầu", "expectedRevision", 1));
        expect(request("PUT", "/api/v1/job-postings/" + authored, json.writeValueAsString(authoredContent),
                actors.get(Identity.HR_MANAGER).token()), 200);
        submitPosting(authored, 2, Identity.ADMIN);
        // Task 355: a published posting of TARGET whose requisition RECRUITER is not assigned to (HR_MANAGER wrote,
        // sent and published it, ADMIN approved it: revision 4), so a take-down that slipped through would change it
        // and add an event.
        UUID live = posting(approvedRequisition(readyBody), completePosting, Identity.HR_MANAGER);
        submitPosting(live, 1, Identity.HR_MANAGER);
        expect(request("POST", "/api/v1/job-postings/" + live + "/approvals", json.writeValueAsString(Map.of(
                "action", "APPROVE", "expectedRevision", 2)), actors.get(Identity.ADMIN).token()), 200);
        expect(request("POST", "/api/v1/job-postings/" + live + "/publish", json.writeValueAsString(Map.of(
                "expectedRevision", 3)), actors.get(Identity.HR_MANAGER).token()), 200);
        Map<String, Object> takeDown = Map.of("expectedRevision", 4, "reason", "Gỡ thay");
        Map<String, Object> changedPosting = Map.of("title", "Bị đổi", "jobDescription", "Mô tả mới",
                "candidateRequirements", "Yêu cầu mới", "showSalary", true, "expectedRevision", 1);
        Map<String, Object> rejection = Map.of("action", "REJECT", "comment", "Không phù hợp", "expectedRevision", 1);
        UUID unused = UUID.fromString(expect(request("POST", "/api/v1/departments", json.writeValueAsString(Map.of(
                "code", "UNUSED", "name", "Unused", "managerUserId", interviewer, "active", true)),
                actors.get(Identity.ADMIN).token()), 201).path("id").asText());
        UUID catalogItem = UUID.fromString(expect(request("POST", CATALOG_ITEMS, json.writeValueAsString(
                catalogItemBody("TARGET", "Target")), actors.get(Identity.ADMIN).token()), 201).path("id").asText());
        UUID locked = accounts.saveAndFlush(new Account("locked@example.test", "Locked", fixturePasswordHash,
                Set.of(Role.RECRUITER), START)).getId();
        jdbc.update("UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                Timestamp.from(START), admin, locked);
        // Task 284: the requisition has a primary and a supporting recruiter, so a write that slipped through would
        // change requisition_recruiters.
        UUID backup = accounts.saveAndFlush(new Account("backup@example.test", "Backup", fixturePasswordHash,
                Set.of(Role.RECRUITER), START)).getId();
        expect(request("POST", "/api/v1/requisitions/" + requisition + "/assign", json.writeValueAsString(
                Map.of("recruiterId", recruiter)), actors.get(Identity.HR_MANAGER).token()), 200);
        UUID spare = accounts.saveAndFlush(new Account("spare@example.test", "Spare", fixturePasswordHash,
                Set.of(Role.RECRUITER), START)).getId();
        expect(request("POST", "/api/v1/requisitions/" + requisition + "/assign", json.writeValueAsString(
                Map.of("recruiterId", spare, "role", "SUPPORTING")), actors.get(Identity.HR_MANAGER).token()), 200);
        // NO_ROLE has an avatar, so a delete that slipped through would remove a user_avatars row.
        jdbc.update("""
                INSERT INTO user_avatars (user_id, content_type, image, thumbnail, size_bytes, updated_at)
                VALUES (?, 'image/png', ?, ?, 3, ?)""",
                actors.get(Identity.NO_ROLE).id(), new byte[] {1, 2, 3}, new byte[] {4}, Timestamp.from(START));

        List<Attack> attacks = List.of(
                new Attack(Identity.RECRUITER, "POST", "/api/v1/accounts",
                        Map.of("email", "intruder@example.test", "fullName", "Intruder", "roles", List.of("ADMIN"))),
                new Attack(Identity.INTERVIEWER, "POST", "/api/v1/departments",
                        Map.of("code", "SHADOW", "name", "Shadow", "managerUserId", interviewer, "active", true)),
                new Attack(Identity.HIRING_MANAGER, "PUT", "/api/v1/departments/" + department,
                        Map.of("code", "TARGET", "name", "Taken over", "managerUserId", hiringManager, "active", false)),
                new Attack(Identity.HR_MANAGER, "PUT", "/api/v1/accounts/" + hrManager + "/roles/ADMIN", null),
                new Attack(Identity.APPROVER, "DELETE", "/api/v1/accounts/" + admin + "/roles/ADMIN", null),
                new Attack(Identity.HR_MANAGER, "PUT", "/api/v1/accounts/" + hrManager + "/roles",
                        Map.of("roles", List.of("ADMIN", "HR_MANAGER"), "expectedRoles", List.of("HR_MANAGER"))),
                new Attack(Identity.HR_MANAGER, "PUT", "/api/v1/accounts/" + recruiter + "/lock", Map.of("reason", "Không được phép")),
                new Attack(Identity.HR_MANAGER, "DELETE", "/api/v1/accounts/" + locked + "/lock", null),
                // Feedback item 10: HR_MANAGER assigns recruiters (REQUISITIONS_WRITE_ALL) but may not hand over the
                // work of an account; one that slipped through would replace RECRUITER as primary of "requisition".
                new Attack(Identity.HR_MANAGER, "POST", "/api/v1/accounts/" + recruiter + "/handover",
                        Map.of("note", "Bàn giao thay", "requisitions",
                                List.of(Map.of("requisitionId", requisition, "toUserId", backup)))),
                new Attack(Identity.HR_MANAGER, "PUT", "/api/v1/accounts/" + recruiter,
                        Map.of("fullName", "Renamed by HR", "departmentId", department)),
                new Attack(Identity.NO_ROLE, "GET", "/api/v1/accounts", null),
                new Attack(Identity.INTERVIEWER, "GET", "/api/v1/accounts/" + admin, null),
                new Attack(Identity.NO_ROLE, "PUT", "/api/v1/profile", Map.of("fullName", "Renamed")),
                new Attack(Identity.NO_ROLE, "POST", "/api/v1/auth/change-password",
                        Map.of("currentPassword", PASSWORD, "newPassword", "ChangedPassword123")),
                new Attack(Identity.INTERVIEWER, "POST", "/api/v1/positions", positionBody("SHADOW", "Shadow")),
                new Attack(Identity.HIRING_MANAGER, "PUT", "/api/v1/positions/" + position,
                        positionBody("TARGET", "Taken over")),
                // ADMIN has ORGANIZATION_WRITE_ALL but not SALARY_RANGES_WRITE_ALL, and every position write sets salaries.
                new Attack(Identity.ADMIN, "POST", "/api/v1/positions", positionBody("SHADOW", "Shadow")),
                new Attack(Identity.ADMIN, "PUT", "/api/v1/positions/" + position, positionBody("TARGET", "Taken over")),
                new Attack(Identity.INTERVIEWER, "POST", "/api/v1/competency-frameworks",
                        competencyFrameworkBody("SHADOW", "Shadow")),
                new Attack(Identity.HIRING_MANAGER, "PUT", "/api/v1/competency-frameworks/" + framework,
                        competencyFrameworkBody("TARGET", "Taken over")),
                // INTERVIEWER holds no REQUISITIONS permission, even for the department it manages.
                new Attack(Identity.INTERVIEWER, "POST", "/api/v1/requisitions", requisitionBody),
                new Attack(Identity.INTERVIEWER, "PUT", "/api/v1/requisitions/" + requisition, changedRequisition),
                // HIRING_MANAGER passes the matcher (REQUISITIONS_WRITE_SCOPED) but does not manage TARGET,
                // so RequisitionService refuses after locking the row.
                new Attack(Identity.HIRING_MANAGER, "PUT", "/api/v1/requisitions/" + requisition, changedRequisition),
                // Task 249: the same SCOPED writers cannot create a requisition for a department they do not manage.
                new Attack(Identity.HIRING_MANAGER, "POST", "/api/v1/requisitions", requisitionBody),
                new Attack(Identity.RECRUITER, "POST", "/api/v1/requisitions", requisitionBody),
                new Attack(Identity.APPROVER, "POST", "/api/v1/requisitions", requisitionBody),
                // Task 197: ORGANIZATION_READ_ALL alone cannot delete a department, not even the one the caller manages.
                new Attack(Identity.INTERVIEWER, "DELETE", "/api/v1/departments/" + unused, null),
                new Attack(Identity.RECRUITER, "DELETE", "/api/v1/departments/" + unused, null),
                new Attack(Identity.RECRUITER, "POST", CATALOG_ITEMS, catalogItemBody("SHADOW", "Shadow")),
                new Attack(Identity.APPROVER, "PUT", CATALOG_ITEMS + "/" + catalogItem,
                        catalogItemBody("TARGET", "Taken over")),
                new Attack(Identity.HIRING_MANAGER, "DELETE", CATALOG_ITEMS + "/" + catalogItem, null),
                // JOB_POSTINGS_WRITE_SCOPED is not enough for the company-wide page.
                new Attack(Identity.RECRUITER, "PUT", "/api/v1/company-profile",
                        Map.of("companyName", "Shadow company", "introduction", "Taken over")),
                new Attack(Identity.NO_ROLE, "DELETE", "/api/v1/profile/avatar", null),
                new Attack(Identity.HIRING_MANAGER, "POST", "/api/v1/requisitions/" + requisition + "/assign",
                        Map.of("recruiterId", backup)),
                new Attack(Identity.RECRUITER, "POST", "/api/v1/requisitions/" + requisition + "/assign",
                        Map.of("recruiterId", backup, "role", "PRIMARY")),
                new Attack(Identity.INTERVIEWER, "POST", "/api/v1/requisitions/" + requisition + "/assign",
                        Map.of("recruiterId", backup)),
                new Attack(Identity.APPROVER, "POST", "/api/v1/requisitions/" + requisition + "/unassign",
                        Map.of("recruiterId", spare)),
                // Task 257: submitting is a write of the requisition's department; none of these may write TARGET.
                new Attack(Identity.INTERVIEWER, "POST", "/api/v1/requisitions/" + ready + "/submit", null),
                new Attack(Identity.HIRING_MANAGER, "POST", "/api/v1/requisitions/" + ready + "/submit", null),
                new Attack(Identity.RECRUITER, "POST", "/api/v1/requisitions/" + ready + "/submit", null),
                new Attack(Identity.APPROVER, "POST", "/api/v1/requisitions/" + ready + "/submit",
                        Map.of("comment", "Gửi thay")),
                // Task 262: only the people of the current level decide: not the creator and submitter (ADMIN), not
                // the department's other roles, not INTERVIEWER (no REQUISITIONS permission).
                new Attack(Identity.ADMIN, "POST", "/api/v1/requisitions/" + pending + "/approvals", rejection),
                new Attack(Identity.APPROVER, "POST", "/api/v1/requisitions/" + pending + "/approvals", rejection),
                new Attack(Identity.HIRING_MANAGER, "POST", "/api/v1/requisitions/" + pending + "/approvals", rejection),
                new Attack(Identity.RECRUITER, "POST", "/api/v1/requisitions/" + pending + "/approvals", rejection),
                new Attack(Identity.INTERVIEWER, "POST", "/api/v1/requisitions/" + pending + "/approvals", rejection),
                // Task 263: approving is no more open than rejecting.
                new Attack(Identity.APPROVER, "POST", "/api/v1/requisitions/" + pending + "/approvals",
                        Map.of("action", "APPROVE", "expectedRevision", 1)),
                // Task 264: asking for more information is a decision of the current level too.
                new Attack(Identity.HIRING_MANAGER, "POST", "/api/v1/requisitions/" + pending + "/approvals",
                        Map.of("action", "REQUEST_INFO", "comment", "Bổ sung", "expectedRevision", 1)),
                // Only ADMIN and HR_MANAGER configure approval flows, even with a REQUISITIONS write permission.
                new Attack(Identity.HIRING_MANAGER, "POST", "/api/v1/approval-flows", Map.of("departmentId", unused,
                        "name", "Shadow", "steps", List.of(Map.of("position", 1, "approverRole", "HIRING_MANAGER")))),
                new Attack(Identity.RECRUITER, "PUT", "/api/v1/approval-flows/" + flow, Map.of("departmentId", department,
                        "name", "Taken over", "expectedVersion", 1,
                        "steps", List.of(Map.of("position", 1, "approverRole", "RECRUITER")))),
                new Attack(Identity.NO_ROLE, "POST", "/api/v1/requisitions/" + requisition + "/unassign",
                        Map.of("recruiterId", spare)),
                // Task 303: only JOB_POSTINGS writers compose, and a recruiter only for requisitions assigned to it.
                new Attack(Identity.HIRING_MANAGER, "POST", "/api/v1/job-postings", newPosting),
                new Attack(Identity.APPROVER, "POST", "/api/v1/job-postings", newPosting),
                new Attack(Identity.NO_ROLE, "POST", "/api/v1/job-postings", newPosting),
                new Attack(Identity.RECRUITER, "POST", "/api/v1/job-postings", newPosting),
                new Attack(Identity.INTERVIEWER, "PUT", "/api/v1/job-postings/" + posting, changedPosting),
                new Attack(Identity.RECRUITER, "PUT", "/api/v1/job-postings/" + posting, changedPosting),
                // Task 308: only JOB_POSTINGS_WRITE_ALL reviews and publishes; HR never approves a posting it wrote, edited or last
                // submitted; a recruiter submits only postings of its requisitions.
                new Attack(Identity.RECRUITER, "POST", "/api/v1/job-postings/" + waiting + "/approvals", Map.of("action", "APPROVE", "expectedRevision", 2)),
                new Attack(Identity.HIRING_MANAGER, "POST", "/api/v1/job-postings/" + waiting + "/approvals", Map.of("action", "REQUEST_REVISION", "comment", "Sửa lại", "expectedRevision", 2)),
                new Attack(Identity.HR_MANAGER, "POST", "/api/v1/job-postings/" + waiting + "/approvals", Map.of("action", "APPROVE", "expectedRevision", 2)),
                new Attack(Identity.HR_MANAGER, "POST", "/api/v1/job-postings/" + authored + "/approvals", Map.of("action", "APPROVE", "expectedRevision", 3)),
                new Attack(Identity.APPROVER, "POST", "/api/v1/job-postings/" + reviewed + "/publish", Map.of("expectedRevision", 3)),
                new Attack(Identity.RECRUITER, "POST", "/api/v1/job-postings/" + reviewed + "/publish", Map.of("expectedRevision", 3)),
                new Attack(Identity.INTERVIEWER, "POST", "/api/v1/job-postings/" + reviewed + "/publish", Map.of("expectedRevision", 3)),
                // The "posting" fixture has every portal detail, so a submit that slipped through would change it.
                new Attack(Identity.RECRUITER, "POST", "/api/v1/job-postings/" + posting + "/submit", Map.of("expectedRevision", 1)),
                new Attack(Identity.HIRING_MANAGER, "POST", "/api/v1/job-postings/" + posting + "/submit", Map.of("expectedRevision", 1)),
                // Task 355: only JOB_POSTINGS writers take a posting down, and a recruiter only one of its
                // requisitions.
                new Attack(Identity.HIRING_MANAGER, "POST", "/api/v1/job-postings/" + live + "/unpublish", takeDown),
                new Attack(Identity.APPROVER, "POST", "/api/v1/job-postings/" + live + "/unpublish", takeDown),
                new Attack(Identity.INTERVIEWER, "POST", "/api/v1/job-postings/" + live + "/unpublish", takeDown),
                new Attack(Identity.NO_ROLE, "POST", "/api/v1/job-postings/" + live + "/unpublish", takeDown),
                new Attack(Identity.RECRUITER, "POST", "/api/v1/job-postings/" + live + "/unpublish", takeDown),
                // Task 290: only HR changes a requisition's status; one that slipped through would change its status,
                // add an event and (task 292) close its posting.
                new Attack(Identity.HIRING_MANAGER, "POST", "/api/v1/requisitions/" + approved + "/status-changes", Map.of("status", "PAUSED", "reason", "Tạm dừng thay", "expectedRevision", 2)),
                new Attack(Identity.RECRUITER, "POST", "/api/v1/requisitions/" + approved + "/status-changes", Map.of("status", "FILLED", "reason", "Đã tuyển thay", "expectedRevision", 2)),
                new Attack(Identity.APPROVER, "POST", "/api/v1/requisitions/" + approved + "/status-changes", Map.of("status", "CANCELLED", "reason", "Hủy thay", "expectedRevision", 2)),
                new Attack(Identity.INTERVIEWER, "POST", "/api/v1/requisitions/" + ready + "/status-changes", Map.of("status", "CANCELLED", "reason", "Hủy thay", "expectedRevision", 0)),
                new Attack(Identity.NO_ROLE, "POST", "/api/v1/requisitions/" + requisition + "/status-changes", Map.of("status", "CANCELLED", "reason", "Hủy thay", "expectedRevision", 0)),
                // Task 285: RECRUITER is the primary recruiter of "requisition"; being assigned opens it for reading
                // only. It is an incomplete DRAFT of TARGET: a widened submit would answer 400, a widened decision 409
                // and a widened read of the HR exceptions 200. PUT and copy pin the whole outcome only: their valid
                // bodies name TARGET, which RECRUITER does not manage, so RequisitionRecruiterAccessIntegrationTest
                // catches a widened check of the saved row or the source.
                new Attack(Identity.RECRUITER, "PUT", "/api/v1/requisitions/" + requisition, changedRequisition),
                new Attack(Identity.RECRUITER, "POST", "/api/v1/requisitions/" + requisition + "/copy", Map.of()),
                new Attack(Identity.RECRUITER, "POST", "/api/v1/requisitions/" + requisition + "/submit", null),
                new Attack(Identity.RECRUITER, "POST", "/api/v1/requisitions/" + requisition + "/approvals", rejection),
                new Attack(Identity.RECRUITER, "GET", "/api/v1/requisitions/" + requisition + "/headcount-overrides", null),
                // Task 291: only REQUISITIONS_WRITE_ALL reads the active pipeline; being assigned (task 285) does not open it.
                new Attack(Identity.HIRING_MANAGER, "GET", "/api/v1/requisitions/" + approved + "/active-pipeline", null),
                new Attack(Identity.RECRUITER, "GET", "/api/v1/requisitions/" + requisition + "/active-pipeline", null),
                new Attack(Identity.APPROVER, "GET", "/api/v1/requisitions/" + approved + "/active-pipeline", null),
                new Attack(Identity.INTERVIEWER, "GET", "/api/v1/requisitions/" + ready + "/active-pipeline", null),
                new Attack(Identity.NO_ROLE, "GET", "/api/v1/requisitions/" + requisition + "/active-pipeline", null));

        return attacks.stream().map(attack -> dynamicTest(attack.toString(), () -> {
            Map<String, List<Map<String, Object>>> before = snapshot();
            var response = request(attack.method(), attack.path(),
                    attack.body() == null ? null : json.writeValueAsString(attack.body()), actors.get(attack.attacker()).token());
            assertForbidden(attack.toString(), response);
            Map<String, List<Map<String, Object>>> after = snapshot();
            STATE_TABLES.forEach(table -> assertThat(after.get(table)).as(table).isEqualTo(before.get(table)));
        }));
    }

    @Test
    void userAdminWriteGrantedToHrManagerStillCannotReplaceTheAdminRole() throws Exception {
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', ?)", USER_ADMIN_WRITE_ALL);
        Actor hrManager = actors.get(Identity.HR_MANAGER);
        // A menu built from this list would now show account administration; the server must still refuse it.
        assertThat(menuPermissions(hrManager)).contains(USER_ADMIN_WRITE_ALL);

        List<Endpoint> adminOnly = ENDPOINTS.stream().filter(endpoint -> endpoint.rule().requiresAdminRole()).toList();
        assertThat(adminOnly).isNotEmpty();
        for (Endpoint endpoint : adminOnly) {
            assertForbidden(endpoint.toString(), call(endpoint, Identity.HR_MANAGER));
        }
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertForbidden("self-grant ADMIN", request("PUT", "/api/v1/accounts/" + hrManager.id() + "/roles/ADMIN", null, hrManager.token()));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void removedPermissionIsEnforcedOnTheNextRequestWithTheSameAccessToken() throws Exception {
        List<Endpoint> organizationReads = ENDPOINTS.stream()
                .filter(endpoint -> endpoint.rule().equals(permission(ORGANIZATION_READ_ALL))).toList();
        assertThat(organizationReads).isNotEmpty();
        for (Endpoint endpoint : organizationReads) {
            assertAllowed(endpoint, call(endpoint, Identity.RECRUITER));
        }

        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'RECRUITER' AND permission_code = ?", ORGANIZATION_READ_ALL);
        assertThat(menuPermissions(actors.get(Identity.RECRUITER))).doesNotContain(ORGANIZATION_READ_ALL);
        for (Endpoint endpoint : organizationReads) {
            assertForbidden(endpoint.toString(), call(endpoint, Identity.RECRUITER));
            assertAllowed(endpoint, call(endpoint, Identity.INTERVIEWER));
        }

        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('RECRUITER', ?)", ORGANIZATION_READ_ALL);
        for (Endpoint endpoint : organizationReads) {
            assertAllowed(endpoint, call(endpoint, Identity.RECRUITER));
        }
    }

    @Test
    void positionSalaryBandIsReturnedOnlyToIdentitiesGrantedSalaryRangeRead() throws Exception {
        UUID position = UUID.fromString(expect(request("POST", "/api/v1/positions", json.writeValueAsString(
                positionBody("BAND", "Band")), actors.get(Identity.HR_MANAGER).token()), 201).path("id").asText());
        for (Identity identity : Identity.values()) {
            String token = actors.get(identity).token();
            if (!identity.expectedGrants().contains(ORGANIZATION_READ_ALL)) {
                assertForbidden(identity + " GET /api/v1/positions/{id}", request("GET", "/api/v1/positions/" + position, null, token));
                continue;
            }
            // Field-level rule: the server drops the band, so a caller who skips the UI still cannot read it.
            boolean seesBand = identity.expectedGrants().contains(SALARY_RANGES_READ_ALL);
            JsonNode detail = expect(request("GET", "/api/v1/positions/" + position, null, token), 200);
            JsonNode item = expect(request("GET", "/api/v1/positions", null, token), 200).path("items").path(0);
            for (JsonNode view : List.of(detail, item)) {
                assertThat(view.path("code").asText()).as(identity.name()).isEqualTo("BAND");
                assertThat(view.has("salaryMin")).as(identity + " salaryMin").isEqualTo(seesBand);
                assertThat(view.has("salaryMax")).as(identity + " salaryMax").isEqualTo(seesBand);
            }
        }
    }

    @Test
    void routesWithoutADeclaredRuleAreDeniedEvenForTheAdministrator() throws Exception {
        String token = actors.get(Identity.ADMIN).token();
        for (String route : List.of("PATCH /api/v1/departments/" + UNKNOWN_ID, "DELETE /api/v1/accounts/" + UNKNOWN_ID,
                "DELETE /api/v1/positions/" + UNKNOWN_ID, "PATCH /api/v1/profile", "GET /api/v1/roles",
                "GET /api/v1/internal/accounts",
                // Task 268: a saved approval decision or comment is never edited or deleted.
                "PUT /api/v1/requisitions/" + UNKNOWN_ID + "/approvals",
                "PATCH /api/v1/requisitions/" + UNKNOWN_ID + "/approvals",
                "DELETE /api/v1/requisitions/" + UNKNOWN_ID + "/approvals",
                // Task 303: the frontend's draft path does not exist; postings are never deleted or patched.
                "POST /api/v1/job-postings/draft", "DELETE /api/v1/job-postings/" + UNKNOWN_ID,
                "PATCH /api/v1/job-postings/" + UNKNOWN_ID,
                // Task 308: a review step or a publication is never edited, deleted or patched.
                "PUT /api/v1/job-postings/" + UNKNOWN_ID + "/publish",
                "DELETE /api/v1/job-postings/" + UNKNOWN_ID + "/approvals",
                "PATCH /api/v1/job-postings/" + UNKNOWN_ID + "/submit",
                // Task 309: candidates only read the portal.
                "POST /api/v1/public/job-postings", "PUT /api/v1/public/job-postings/" + UNKNOWN_ID,
                // Task 315: nor change the values of its filter lists.
                "POST /api/v1/public/job-postings/filters",
                // Task 355: taking a posting down is never edited, undone or deleted.
                "PUT /api/v1/job-postings/" + UNKNOWN_ID + "/unpublish",
                "PATCH /api/v1/job-postings/" + UNKNOWN_ID + "/unpublish",
                "DELETE /api/v1/job-postings/" + UNKNOWN_ID + "/unpublish",
                // Task 310: the review and publication history is never edited or deleted.
                "DELETE /api/v1/job-postings/" + UNKNOWN_ID + "/history",
                "PUT /api/v1/job-postings/" + UNKNOWN_ID + "/history",
                // Task 290/291: the frontend's status and candidate-count routes do not exist
                // (POST /requisitions/{id}/status-changes and GET /requisitions/{id}/active-pipeline do).
                "PATCH /api/v1/requisitions/" + UNKNOWN_ID + "/status",
                "PATCH /api/v1/job-requisitions/" + UNKNOWN_ID + "/status",
                "GET /api/v1/job-requisitions/" + UNKNOWN_ID + "/active-candidates-count",
                // Task 292: a posting is closed only with its requisition; there is no route to close one by hand.
                "POST /api/v1/job-postings/" + UNKNOWN_ID + "/close",
                // Task 339: the frontend's current lookup puts the secret in the query; only the POST exists.
                "GET /api/v1/public/applications/lookup",
                // Task 345: the frontend's current withdrawal puts the code in the path; only the POST with the
                // credential in the body exists.
                "PATCH /api/v1/public/applications/" + UNKNOWN_ID + "/withdraw",
                // Task 361: only the unified profile exists; /candidates/{id} stays free for a later resource, and the
                // frontend's notes, tags and rating routes belong to S5-04.
                "GET /api/v1/candidates/" + UNKNOWN_ID, "POST /api/v1/candidates/" + UNKNOWN_ID + "/notes",
                "PUT /api/v1/candidates/" + UNKNOWN_ID + "/tags",
                "PATCH /api/v1/candidates/" + UNKNOWN_ID + "/rating",
                // Task 352: a referrer reads only their own list; there is no list of every referral.
                "GET /api/v1/referrals",
                // Task 380: a stage is changed only by POST .../stage-changes; a recorded change is never edited.
                "PATCH /api/v1/applications/" + UNKNOWN_ID + "/stage",
                "PUT /api/v1/applications/" + UNKNOWN_ID + "/stage",
                "PUT /api/v1/applications/" + UNKNOWN_ID + "/stage-changes",
                "DELETE /api/v1/applications/" + UNKNOWN_ID + "/stage-changes",
                // Task 356: only the daily task expires a posting; there is no route to expire one by hand.
                "POST /api/v1/job-postings/" + UNKNOWN_ID + "/expire",
                // Feedback item 8: the role set is only replaced, never wiped, patched or added to as a whole.
                "DELETE /api/v1/accounts/" + UNKNOWN_ID + "/roles",
                "PATCH /api/v1/accounts/" + UNKNOWN_ID + "/roles",
                "POST /api/v1/accounts/" + UNKNOWN_ID + "/roles")) {
            String[] parts = route.split(" ");
            assertForbidden(route, request(parts[0], parts[1], null, token));
        }
    }

    @Test
    void everyIdentityExpectsExactlyThePermissionsTheServerReportsForIt() throws Exception {
        for (Identity identity : Identity.values()) {
            assertThat(menuPermissions(actors.get(identity))).as(identity.name()).isEqualTo(identity.expectedGrants());
        }
    }

    @Test
    void ruleFactoriesCombinePermissionsAndTheAdminRoleAsDocumented() {
        Set<String> noRoles = Set.of();
        Set<String> readOnly = Set.of(ORGANIZATION_READ_ALL);
        Set<String> readWrite = Set.of(ORGANIZATION_READ_ALL, ORGANIZATION_WRITE_ALL);

        assertThat(PUBLIC.allows(noRoles, Set.of())).isTrue();
        assertThat(permission(ORGANIZATION_READ_ALL).allows(noRoles, readOnly)).isTrue();
        assertThat(permission(ORGANIZATION_WRITE_ALL).allows(Set.of("ADMIN"), readOnly)).isFalse();
        assertThat(allOf(ORGANIZATION_READ_ALL, ORGANIZATION_WRITE_ALL).allows(noRoles, readOnly)).isFalse();
        assertThat(allOf(ORGANIZATION_READ_ALL, ORGANIZATION_WRITE_ALL).allows(noRoles, readWrite)).isTrue();
        assertThat(anyOf(ORGANIZATION_WRITE_ALL, USER_ADMIN_READ_ALL).allows(noRoles, readOnly)).isFalse();
        assertThat(anyOf(ORGANIZATION_WRITE_ALL, ORGANIZATION_READ_ALL).allows(noRoles, readOnly)).isTrue();
        assertThat(adminWith(ORGANIZATION_READ_ALL).allows(Set.of("HR_MANAGER"), readOnly)).isFalse();
        assertThat(adminWith(ORGANIZATION_READ_ALL).allows(Set.of("ADMIN"), readOnly)).isTrue();
        assertThat(adminWith(ORGANIZATION_WRITE_ALL).allows(Set.of("ADMIN"), readOnly)).isFalse();
        assertThat(adminWithAll(ORGANIZATION_READ_ALL, ORGANIZATION_WRITE_ALL).allows(Set.of("ADMIN"), readWrite)).isTrue();
        assertThat(adminWithAll(ORGANIZATION_READ_ALL, ORGANIZATION_WRITE_ALL).allows(Set.of("ADMIN"), readOnly)).isFalse();
        assertThat(adminWithAll(ORGANIZATION_READ_ALL, ORGANIZATION_WRITE_ALL).allows(Set.of("HR_MANAGER"), readWrite)).isFalse();
        assertThat(List.of(PUBLIC, permission(ORGANIZATION_READ_ALL), allOf(ORGANIZATION_READ_ALL, ORGANIZATION_WRITE_ALL),
                anyOf(ORGANIZATION_READ_ALL, ORGANIZATION_WRITE_ALL), adminWith(USER_ADMIN_WRITE_ALL),
                adminWithAll(USER_ADMIN_WRITE_ALL, REQUISITIONS_READ_ALL)))
                .extracting(Rule::toString).containsExactly("PUBLIC", "ORGANIZATION_READ_ALL",
                        "ORGANIZATION_READ_ALL + ORGANIZATION_WRITE_ALL", "ORGANIZATION_READ_ALL | ORGANIZATION_WRITE_ALL",
                        "ROLE_ADMIN + USER_ADMIN_WRITE_ALL", "ROLE_ADMIN + USER_ADMIN_WRITE_ALL + REQUISITIONS_READ_ALL");
    }

    private static Map<String, Object> positionBody(String code, String name) {
        return Map.of("code", code, "name", name, "level", "Junior", "salaryMin", 15_000_000L,
                "salaryMax", 25_000_000L, "active", true);
    }

    private static Map<String, Object> competencyFrameworkBody(String code, String name) {
        return Map.of("code", code, "name", name, "criteria", List.of(Map.of("name", "Kỹ năng chuyên môn", "weight", 100)));
    }

    private static Map<String, Object> catalogItemBody(String code, String name) {
        return Map.of("code", code, "name", name, "active", true);
    }

    // Task 303: a requisition ADMIN creates and submits, approved by HR_MANAGER (the only level of TARGET's flow).
    private UUID approvedRequisition(Map<String, Object> body) throws Exception {
        String adminToken = actors.get(Identity.ADMIN).token();
        UUID id = UUID.fromString(expect(request("POST", "/api/v1/requisitions", json.writeValueAsString(body), adminToken), 201)
                .path("id").asText());
        expect(request("POST", "/api/v1/requisitions/" + id + "/submit", null, adminToken), 200);
        expect(request("POST", "/api/v1/requisitions/" + id + "/approvals", json.writeValueAsString(Map.of(
                "action", "APPROVE", "expectedRevision", 1)), actors.get(Identity.HR_MANAGER).token()), 200);
        return id;
    }

    // Task 308: a posting of the requisition with the given content (every portal detail), created by the identity.
    private UUID posting(UUID requisition, Map<String, Object> content, Identity author) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(content);
        body.put("requisitionId", requisition);
        return UUID.fromString(expect(request("POST", "/api/v1/job-postings", json.writeValueAsString(body),
                actors.get(author).token()), 201).path("id").asText());
    }

    // Task 308: the identity sends the posting for review.
    private void submitPosting(UUID posting, int expectedRevision, Identity submitter) throws Exception {
        expect(request("POST", "/api/v1/job-postings/" + posting + "/submit", json.writeValueAsString(Map.of(
                "expectedRevision", expectedRevision)), actors.get(submitter).token()), 200);
    }

    // Task 303: an active value of a shared catalog, created by ADMIN.
    private UUID catalogValue(String type, String code, String name) throws Exception {
        return UUID.fromString(expect(request("POST", "/api/v1/recruitment-catalogs/" + type + "/items",
                json.writeValueAsString(catalogItemBody(code, name)), actors.get(Identity.ADMIN).token()), 201)
                .path("id").asText());
    }

    private HttpResponse<String> call(Endpoint endpoint, Identity identity) throws Exception {
        String token = endpoint.endsSession() ? login(identity).token() : actors.get(identity).token();
        return request(endpoint.method(), endpoint.samplePath(), endpoint.body(), token);
    }

    private Actor login(Identity identity) throws Exception {
        JsonNode result = expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", identity.email(), "password", PASSWORD)), null), 200);
        Set<String> roles = new HashSet<>();
        result.path("user").path("roles").forEach(role -> roles.add(role.asText()));
        return new Actor(UUID.fromString(result.path("user").path("id").asText()),
                result.path("accessToken").asText(), Set.copyOf(roles));
    }

    private Set<String> menuPermissions(Actor actor) throws Exception {
        var response = request("GET", "/api/v1/auth/permissions", null, actor.token());
        if (response.statusCode() == 403) {
            // Without SELF_PROFILE_READ the account cannot even load its menu, so it shows nothing.
            assertForbidden("GET /api/v1/auth/permissions", response);
            return Set.of();
        }
        Set<String> permissions = new HashSet<>();
        expect(response, 200).path("permissions").forEach(permission -> permissions.add(permission.asText()));
        return permissions;
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> state = new LinkedHashMap<>();
        STATE_TABLES.forEach(table -> state.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1, 2")
                .stream().map(ApiAuthorizationMatrixIntegrationTest::comparable).toList()));
        return state;
    }

    // Arrays are equal only to themselves, so BYTEA columns such as user_avatars.image are compared as hex text.
    private static Map<String, Object> comparable(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>();
        row.forEach((column, value) -> copy.put(column, value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value));
        return copy;
    }

    private HttpResponse<String> request(String method, String path, String payload, String token) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path);
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        if (method.equals("POST") && path.equals("/api/v1/auth/forgot-password")) {
            // The browser id the frontend sends; a new browser each time, so the per-browser limit never applies.
            builder.header("X-Device-Id", UUID.randomUUID().toString());
        } else if (method.equals("POST") && path.equals("/api/v1/auth/login")) {
            builder.header("X-Device-Id", deviceId);
        }
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode expect(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        return json.readTree(response.body());
    }

    private void assertAllowed(Endpoint endpoint, HttpResponse<String> response) {
        assertThat(response.statusCode()).as("%s should pass authorization: %s", endpoint, response.body())
                .isNotIn(401, 403).isEqualTo(endpoint.allowedStatus());
    }

    private void assertForbidden(String call, HttpResponse<String> response) {
        assertThat(response.statusCode()).as("%s should be forbidden: %s", call, response.body()).isEqualTo(403);
        assertThat(json.readTree(response.body())).as("%s must return only the standard forbidden error", call)
                .isEqualTo(json.valueToTree(FORBIDDEN_ERROR));
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    private void assertUnauthorized(Endpoint endpoint, HttpResponse<String> response) {
        assertThat(response.statusCode()).as("%s should require login: %s", endpoint, response.body()).isEqualTo(401);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(response.headers().firstValue("WWW-Authenticate").orElseThrow()).startsWith("Bearer");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    private static Endpoint endpoint(String method, String template, Rule rule, String body, int allowedStatus) {
        return new Endpoint(method, template, rule, body, allowedStatus, false);
    }

    private static Rule permission(String code) { return new Rule(List.of(code), false, false); }

    private static Rule allOf(String... codes) { return new Rule(List.of(codes), false, false); }

    private static Rule anyOf(String... codes) { return new Rule(List.of(codes), true, false); }

    private static Rule adminWith(String code) { return new Rule(List.of(code), false, true); }

    private static Rule adminWithAll(String... codes) { return new Rule(List.of(codes), false, true); }

    // PUBLIC has no codes. Otherwise the caller needs every code (any one with anyOf), plus ROLE_ADMIN when required.
    private record Rule(List<String> permissions, boolean anyOf, boolean requiresAdminRole, boolean requiresRecruiterRole) {
        Rule(List<String> permissions, boolean anyOf, boolean requiresAdminRole) {
            this(permissions, anyOf, requiresAdminRole, false);
        }
        boolean isPublic() { return permissions.isEmpty(); }

        boolean allows(Set<String> roles, Set<String> granted) {
            if (isPublic()) { return true; }
            boolean permitted = anyOf ? permissions.stream().anyMatch(granted::contains) : granted.containsAll(permissions);
            return permitted && (!requiresAdminRole || roles.contains("ADMIN"))
                    && (!requiresRecruiterRole || roles.contains("RECRUITER"));
        }

        @Override
        public String toString() {
            if (isPublic()) { return "PUBLIC"; }
            return (requiresAdminRole ? "ROLE_ADMIN + " : "")
                    + (requiresRecruiterRole ? "ROLE_RECRUITER + " : "")
                    + String.join(anyOf ? " | " : " + ", permissions);
        }
    }

    private record Endpoint(String method, String template, Rule rule, String body, int allowedStatus, boolean endsSession) {
        String key() { return method + " " + template; }

        String samplePath() {
            return template.replace("{id}", UNKNOWN_ID.toString()).replace("{formId}", UNKNOWN_ID.toString()).replace("{role}", "RECRUITER")
                    .replace("{type}", CATALOG_TYPE);
        }

        @Override
        public String toString() { return key() + " [" + rule + "]"; }
    }

    private record Actor(UUID id, String token, Set<String> roles) { }

    private record Attack(Identity attacker, String method, String path, Map<String, Object> body) {
        @Override
        public String toString() { return attacker + " " + method + " " + path; }
    }

    // One account per internal role plus one without roles. Expected grants are written only once, in
    // RolePermissionSeedMigrationTest.EXPECTED_GRANTS, so a seed change updates this spec automatically.
    private enum Identity {
        ADMIN(Role.ADMIN),
        HR_MANAGER(Role.HR_MANAGER),
        RECRUITER(Role.RECRUITER),
        HIRING_MANAGER(Role.HIRING_MANAGER),
        INTERVIEWER(Role.INTERVIEWER),
        APPROVER(Role.APPROVER),
        NO_ROLE(null);

        private final Role role;

        Identity(Role role) {
            this.role = role;
        }

        Set<String> expectedGrants() {
            return role == null ? Set.of() : RolePermissionSeedMigrationTest.EXPECTED_GRANTS.get(role.name());
        }

        Set<String> roles() { return role == null ? Set.of() : Set.of(role.name()); }

        String email() {
            return this == ADMIN ? "admin@example.test" : name().toLowerCase(Locale.ROOT).replace('_', '-') + "@example.test";
        }
    }
}
