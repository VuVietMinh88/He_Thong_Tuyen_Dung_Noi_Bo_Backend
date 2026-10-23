package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.CvValidator;
import vn.ttcs.recruitment.candidate.ApplicationAccess;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateAccessKind;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.department.DepartmentRepository;
import vn.ttcs.recruitment.requisition.RecruiterAssignmentAccess;
import vn.ttcs.recruitment.security.AccessScope;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 363 (story S4-09, "Chỉ recruiter phụ trách và hiring manager của vị trí đó mở được; mọi truy cập được ghi
 * nhật ký"): CandidateAccess decides who reads which application and logs every read. Real PostgreSQL, no HTTP (the
 * first candidate endpoint is task 361); the readers come from real sessions through requireReader, so the seeded
 * permission codes (with V33_1 for APPROVER) decide the scope.
 * <p>
 * Fixture: TOP (managed by topHead) above PARENT (parentHead) above CHILD (childHead) and SIBLING (siblingHead); a
 * third posting in an unrelated department. One person ("a@example.test") applied to CHILD and SIBLING, another
 * ("b@example.test") to the unrelated posting. CHILD's requisition has a PRIMARY and a SUPPORTING recruiter; its
 * creator holds HIRING_MANAGER but has no other relation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.page-url=https://portal.example.test/tra-cuu",
        "app.application-confirmation.poll-delay-ms=3600000", "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-tracking.cleanup-delay-ms=3600000",
        "spring.mail.host=127.0.0.1", "spring.mail.port=1"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CandidateAccessIntegrationTest {
    private static final Instant NOW = ApplicationTestSupport.NOW;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private NamedParameterJdbcTemplate named;
    @Autowired private ObjectMapper json;
    @Autowired private Environment environment;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationStorageService storage;
    @Autowired private CvValidator validator;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private CandidateAccess access;
    @Autowired private RecruiterAssignmentAccess assignments;
    @Autowired private DepartmentRepository departments;

    private ApplicationTestSupport applications;
    private UUID admin, hr, primary, supporting, otherRecruiter, topHead, parentHead, childHead, siblingHead;
    private UUID approver, interviewer, noRole, creator;
    private UUID top, parent, child, sibling, unrelated;
    private UUID childRequisition, siblingRequisition, unrelatedRequisition;
    private ApplicationTestSupport.Stored inChild, inSibling, inUnrelated;

    @BeforeEach
    void fixture() {
        clock.set(NOW);
        int port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, port).reset();

        UUID childPosting = applications.publishedPosting("Lập trình viên Java");
        UUID siblingPosting = applications.publishedPosting("Kiểm thử phần mềm");
        UUID unrelatedPosting = applications.publishedPosting("Kế toán tổng hợp");
        childRequisition = applications.requisitionOf(childPosting);
        siblingRequisition = applications.requisitionOf(siblingPosting);
        unrelatedRequisition = applications.requisitionOf(unrelatedPosting);
        child = departmentOf(childRequisition);
        sibling = departmentOf(siblingRequisition);
        unrelated = departmentOf(unrelatedRequisition);
        // publishedPosting makes one account the creator of the requisition and the manager of its department.
        creator = jdbc.queryForObject("SELECT created_by FROM recruitment_requisitions WHERE id = ?", UUID.class,
                childRequisition);

        admin = account("Quản trị", "admin@example.test", Role.ADMIN);
        hr = account("Nhân sự", "hr@example.test", Role.HR_MANAGER);
        primary = account("Recruiter chính", "primary@example.test", Role.RECRUITER);
        supporting = account("Recruiter hỗ trợ", "supporting@example.test", Role.RECRUITER);
        otherRecruiter = account("Recruiter khác", "other-recruiter@example.test", Role.RECRUITER);
        topHead = account("Giám đốc khối", "top-head@example.test", Role.HIRING_MANAGER);
        parentHead = account("Trưởng phòng", "parent-head@example.test", Role.HIRING_MANAGER);
        childHead = account("Trưởng nhóm", "child-head@example.test", Role.HIRING_MANAGER);
        siblingHead = account("Trưởng nhóm khác", "sibling-head@example.test", Role.HIRING_MANAGER);
        approver = account("Người duyệt", "approver@example.test", Role.APPROVER);
        interviewer = account("Người phỏng vấn", "interviewer@example.test", Role.INTERVIEWER);
        noRole = account("Không vai trò", "no-role@example.test");
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'HIRING_MANAGER')", creator);

        top = department("TOP", topHead, null);
        parent = department("PARENT", parentHead, top);
        jdbc.update("UPDATE departments SET parent_id = ?, manager_user_id = ? WHERE id = ?", parent, childHead, child);
        jdbc.update("UPDATE departments SET parent_id = ?, manager_user_id = ? WHERE id = ?", parent, siblingHead,
                sibling);
        applications.assign(childRequisition, primary, "PRIMARY", hr);
        applications.assign(childRequisition, supporting, "SUPPORTING", hr);

        inChild = applications.stored(childPosting, "a@example.test");
        inSibling = applications.stored(siblingPosting, "a@example.test");
        inUnrelated = applications.stored(unrelatedPosting, "b@example.test");
        assertThat(inSibling.candidateId()).isEqualTo(inChild.candidateId());
    }

    // CANDIDATES_READ_ALL reads every application whatever the relation. Pinned on purpose: the task's criterion is
    // NOT met for HR_MANAGER and ADMIN until BA/PO answer questions 6 and 25(4) of role-permission-matrix.md.
    @Test
    void theReadAllHoldersHrManagerAndAdminReadEveryApplication() {
        for (UUID holder : List.of(admin, hr)) {
            CandidateReader reader = reader(holder);
            assertThat(reader.scope()).isEqualTo(AccessScope.ALL);
            assertThat(readable(reader)).containsExactlyInAnyOrder(inChild.applicationId(), inSibling.applicationId(),
                    inUnrelated.applicationId());
        }
    }

    // The primary and the supporting recruiter both read their requisition's application, and nothing else; a
    // recruiter without an assignment reads nothing although it holds the same permission.
    @Test
    void thePrimaryAndTheSupportingRecruiterReadTheirRequisitionOnly() {
        for (UUID recruiter : List.of(primary, supporting)) {
            CandidateReader reader = reader(recruiter);
            assertThat(reader.scope()).isEqualTo(AccessScope.SCOPED);
            assertThat(readable(reader)).containsExactly(inChild.applicationId());
        }
        assertThat(reader(otherRecruiter).scope()).isEqualTo(AccessScope.SCOPED);
        assertThat(readable(reader(otherRecruiter))).isEmpty();
    }

    // The team as it is now decides: after a handover the old primary reads nothing and the new one reads the
    // application; a supporting recruiter who is removed loses it too.
    @Test
    void aHandoverMovesTheReadToTheNewRecruiter() {
        jdbc.update("UPDATE requisition_recruiters SET recruiter_id = ? WHERE requisition_id = ? AND recruiter_id = ?",
                otherRecruiter, childRequisition, primary);
        jdbc.update("DELETE FROM requisition_recruiters WHERE requisition_id = ? AND recruiter_id = ?",
                childRequisition, supporting);

        assertThat(readable(reader(primary))).isEmpty();
        assertThat(readable(reader(supporting))).isEmpty();
        assertThat(readable(reader(otherRecruiter))).containsExactly(inChild.applicationId());
    }

    // V33_1: the workbook's R (CANDIDATES_READ_ALL) became CANDIDATES_READ_SCOPED. Without a relation the approver
    // reads nothing; as a department manager or an assigned recruiter it reads like everybody scoped.
    @Test
    void theApproverReadsOnlyAsADepartmentManagerOrAnAssignedRecruiter() {
        assertThat(reader(approver).scope()).isEqualTo(AccessScope.SCOPED);
        assertThat(readable(reader(approver))).isEmpty();

        jdbc.update("UPDATE departments SET manager_user_id = ? WHERE id = ?", approver, sibling);
        assertThat(readable(reader(approver))).containsExactly(inSibling.applicationId());

        applications.assign(childRequisition, approver, "SUPPORTING", hr);
        assertThat(readable(reader(approver))).containsExactlyInAnyOrder(inChild.applicationId(),
                inSibling.applicationId());
    }

    // The manager of the requisition's department reads it, and so does every manager above it (recursively); the
    // sibling department's manager does not. The requisition's creator never counts.
    @Test
    void departmentManagersReadTheirDepartmentAndEveryDepartmentBelowIt() {
        assertThat(readable(reader(childHead))).containsExactly(inChild.applicationId());
        assertThat(readable(reader(siblingHead))).containsExactly(inSibling.applicationId());
        assertThat(readable(reader(parentHead))).containsExactlyInAnyOrder(inChild.applicationId(),
                inSibling.applicationId());
        assertThat(readable(reader(topHead))).containsExactlyInAnyOrder(inChild.applicationId(),
                inSibling.applicationId());

        CandidateReader creatorReader = reader(creator);
        assertThat(creatorReader.scope()).isEqualTo(AccessScope.SCOPED);
        assertThat(readable(creatorReader)).isEmpty();
    }

    // The rule follows permission codes, never role names: an INTERVIEWER (no interview rounds before S6) reads
    // nothing, but one who manages a department reads that department's applications.
    @Test
    void anInterviewerReadsOnlyThroughARelation() {
        assertThat(reader(interviewer).scope()).isEqualTo(AccessScope.SCOPED);
        assertThat(readable(reader(interviewer))).isEmpty();

        jdbc.update("UPDATE departments SET manager_user_id = ? WHERE id = ?", interviewer, unrelated);
        assertThat(readable(reader(interviewer))).containsExactly(inUnrelated.applicationId());
    }

    // 401 for the token, session or account; 403 (AccessDeniedException) without a CANDIDATES read permission. Both
    // come before any lookup.
    @Test
    void requireReaderRefusesBeforeAnyLookup() {
        assertThatThrownBy(() -> access.requireReader(jwt(noRole))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> access.requireReader(null)).isInstanceOf(AuthenticationFailureException.class);
        assertThatThrownBy(() -> access.requireReader(jwt(primary, NOW)))
                .isInstanceOf(AuthenticationFailureException.class);

        Jwt revoked = jwt(primary);
        jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE id = ?::uuid", Timestamp.from(NOW), revoked.getId());
        assertThatThrownBy(() -> access.requireReader(revoked)).isInstanceOf(BadCredentialsException.class);

        Jwt locked = jwt(primary);
        jdbc.update("UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Kiểm thử', admin_locked_by = ?"
                + " WHERE id = ?", Timestamp.from(NOW), admin, primary);
        assertThatThrownBy(() -> access.requireReader(locked)).isInstanceOf(BadCredentialsException.class);

        Jwt disabled = jwt(supporting);
        jdbc.update("UPDATE user_accounts SET enabled = FALSE WHERE id = ?", supporting);
        assertThatThrownBy(() -> access.requireReader(disabled)).isInstanceOf(BadCredentialsException.class);
    }

    // Permissions are read again on every call: a grant removed from role_permissions refuses the next call, and a
    // wider grant widens the next one.
    @Test
    void aChangedGrantTakesEffectOnTheNextCall() {
        Jwt token = jwt(otherRecruiter);
        assertThat(access.requireReader(token).scope()).isEqualTo(AccessScope.SCOPED);
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'RECRUITER' AND permission_code = 'CANDIDATES_READ_SCOPED'");
        try {
            assertThatThrownBy(() -> access.requireReader(token)).isInstanceOf(AccessDeniedException.class);
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('RECRUITER', 'CANDIDATES_READ_ALL')");
            assertThat(access.requireReader(token).scope()).isEqualTo(AccessScope.ALL);
            assertThat(readable(access.requireReader(token))).hasSize(3);
        } finally {
            jdbc.update("DELETE FROM role_permissions WHERE role_code = 'RECRUITER' AND permission_code = 'CANDIDATES_READ_ALL'");
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('RECRUITER', 'CANDIDATES_READ_SCOPED')");
        }
        assertThat(access.requireReader(token).scope()).isEqualTo(AccessScope.SCOPED);
    }

    // The oracle: for every reader and application of the fixture (with a few more relations), the SQL fragment equals
    // the Java rule built from the house's own checks: ALL, or RecruiterAssignmentAccess.isAssigned, or the
    // department in DepartmentRepository.findManagedDepartmentIds.
    @Test
    void theSqlRuleEqualsTheJavaRuleForEveryReaderAndApplication() {
        applications.assign(unrelatedRequisition, interviewer, "PRIMARY", hr);
        jdbc.update("UPDATE departments SET manager_user_id = ? WHERE id = ?", approver, sibling);
        Map<UUID, UUID> requisitions = Map.of(inChild.applicationId(), childRequisition,
                inSibling.applicationId(), siblingRequisition, inUnrelated.applicationId(), unrelatedRequisition);
        Map<UUID, UUID> departmentOf = Map.of(inChild.applicationId(), child, inSibling.applicationId(), sibling,
                inUnrelated.applicationId(), unrelated);
        int checked = 0;
        for (UUID account : List.of(admin, hr, primary, supporting, otherRecruiter, topHead, parentHead, childHead,
                siblingHead, approver, interviewer, creator)) {
            CandidateReader reader = reader(account);
            Set<UUID> sql = readable(reader);
            Set<UUID> managed = departments.findManagedDepartmentIds(account);
            for (UUID application : requisitions.keySet()) {
                boolean assigned = Boolean.TRUE.equals(inTransaction(() ->
                        assignments.isAssigned(requisitions.get(application), account)));
                boolean expected = reader.scope() == AccessScope.ALL || assigned
                        || managed.contains(departmentOf.get(application));
                assertThat(sql.contains(application)).as("%s reads %s", account, application).isEqualTo(expected);
                checked++;
            }
        }
        assertThat(checked).isEqualTo(36);
    }

    // findApplication names the profile and is empty for an unknown application; like every read it needs the
    // caller's transaction.
    @Test
    void findApplicationNamesTheProfileAndNeedsTheCallersTransaction() {
        CandidateReader reader = reader(primary);
        assertThat(inTransaction(() -> access.findApplication(reader, inSibling.applicationId())))
                .contains(new ApplicationAccess(inSibling.applicationId(), inChild.candidateId(), false));
        assertThat(inTransaction(() -> access.findApplication(reader, UUID.randomUUID()))).isEmpty();
        assertThatThrownBy(() -> access.findApplication(reader, inChild.applicationId()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> new CandidateReader(primary, AccessScope.NONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // A granted read writes one GRANTED row per disclosed application with the reader's scope, at the clock's time
    // truncated to microseconds, in the read's transaction: a rolled-back read leaves no row.
    @Test
    void recordGrantedWritesOneRowPerDisclosedApplicationInTheReadsTransaction() {
        Instant at = NOW.plusNanos(123_456_789);
        clock.set(at);
        CandidateReader scoped = reader(primary);
        CandidateReader all = reader(hr);
        inTransaction(() -> {
            access.recordGranted(scoped, CandidateAccessKind.PROFILE, inChild.candidateId(),
                    List.of(inChild.applicationId()));
            access.recordGranted(all, CandidateAccessKind.CV, inChild.candidateId(), List.of(inSibling.applicationId()));
            access.recordGranted(all, CandidateAccessKind.PROFILE, inChild.candidateId(),
                    List.of(inChild.applicationId(), inSibling.applicationId()));
            return null;
        });

        Timestamp stored = Timestamp.from(NOW.plusNanos(123_456_000));
        assertThat(events()).containsExactly(
                event(primary, inChild.candidateId(), inChild.applicationId(), "PROFILE", "SCOPED", "GRANTED", stored),
                event(hr, inChild.candidateId(), inSibling.applicationId(), "CV", "ALL", "GRANTED", stored),
                event(hr, inChild.candidateId(), inChild.applicationId(), "PROFILE", "ALL", "GRANTED", stored),
                event(hr, inChild.candidateId(), inSibling.applicationId(), "PROFILE", "ALL", "GRANTED", stored));

        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            access.recordGranted(scoped, CandidateAccessKind.CV, inChild.candidateId(), List.of(inChild.applicationId()));
            status.setRollbackOnly();
        });
        assertThat(events()).hasSize(4);

        assertThatThrownBy(() -> access.recordGranted(scoped, CandidateAccessKind.PROFILE, inChild.candidateId(),
                List.of(inChild.applicationId()))).isInstanceOf(IllegalTransactionStateException.class);
        for (List<UUID> disclosed : List.of(List.<UUID>of(), List.of(inChild.applicationId(), inChild.applicationId()))) {
            assertThatThrownBy(() -> inTransaction(() -> {
                access.recordGranted(scoped, CandidateAccessKind.PROFILE, inChild.candidateId(), disclosed);
                return null;
            })).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(events()).hasSize(4);
    }

    // A refused read of an existing record writes one committed DENIED row, the application for a CV and none for a
    // profile, but only once the read transaction has ended: inside one it throws and writes nothing.
    @Test
    void recordDeniedWritesOneCommittedRowAfterTheReadTransaction() {
        CandidateReader scoped = reader(otherRecruiter);
        access.recordDenied(scoped, CandidateAccessKind.PROFILE, inChild.candidateId(), null);
        access.recordDenied(scoped, CandidateAccessKind.CV, inChild.candidateId(), inSibling.applicationId());
        Timestamp at = Timestamp.from(NOW);
        assertThat(events()).containsExactly(
                event(otherRecruiter, inChild.candidateId(), null, "PROFILE", "SCOPED", "DENIED", at),
                event(otherRecruiter, inChild.candidateId(), inSibling.applicationId(), "CV", "SCOPED", "DENIED", at));

        assertThatThrownBy(() -> inTransaction(() -> {
            access.recordDenied(scoped, CandidateAccessKind.PROFILE, inChild.candidateId(), null);
            return null;
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> access.recordDenied(reader(hr), CandidateAccessKind.PROFILE, inChild.candidateId(),
                null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> access.recordDenied(scoped, CandidateAccessKind.CV, inChild.candidateId(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(events()).hasSize(2);
    }

    // Append-only like V20/V23: UPDATE and DELETE are refused with SQLSTATE 23514; TRUNCATE fires no row trigger.
    @Test
    void theLogIsAppendOnly() {
        access.recordDenied(reader(otherRecruiter), CandidateAccessKind.PROFILE, inChild.candidateId(), null);
        assertThat(sqlState(() -> jdbc.update("UPDATE candidate_access_events SET outcome = 'GRANTED'")))
                .isEqualTo("23514");
        assertThat(sqlState(() -> jdbc.update("DELETE FROM candidate_access_events"))).isEqualTo("23514");
        assertThat(events()).hasSize(1);
        jdbc.execute("TRUNCATE candidate_access_events");
        assertThat(events()).isEmpty();
    }

    // The CHECKs refuse what no read produces: a GRANTED row or a CV row without an application, a DENIED row with the
    // scope ALL, unknown kinds, scopes and outcomes. A DENIED profile row without an application is the one allowed.
    @Test
    void theLogConstraintsRefuseWhatNoReadProduces() {
        UUID application = inChild.applicationId();
        assertThat(sqlState(() -> insertEvent(null, "PROFILE", "SCOPED", "GRANTED"))).isEqualTo("23514");
        assertThat(sqlState(() -> insertEvent(null, "CV", "SCOPED", "DENIED"))).isEqualTo("23514");
        assertThat(sqlState(() -> insertEvent(application, "PROFILE", "ALL", "DENIED"))).isEqualTo("23514");
        assertThat(sqlState(() -> insertEvent(application, "LIST", "SCOPED", "GRANTED"))).isEqualTo("23514");
        assertThat(sqlState(() -> insertEvent(application, "CV", "NONE", "GRANTED"))).isEqualTo("23514");
        assertThat(sqlState(() -> insertEvent(application, "CV", "SCOPED", "VIEWED"))).isEqualTo("23514");
        assertThat(events()).isEmpty();

        insertEvent(null, "PROFILE", "SCOPED", "DENIED");
        insertEvent(application, "CV", "ALL", "GRANTED");
        assertThat(events()).hasSize(2);
        // Every kind the code logs is a value the table accepts.
        for (CandidateAccessKind kind : CandidateAccessKind.values()) {
            insertEvent(application, kind.name(), "SCOPED", "GRANTED");
        }
        assertThat(events()).hasSize(2 + CandidateAccessKind.values().length);
    }

    // The applications this reader may read, through the list form of READABLE; findApplication must answer the same
    // for every application, so a list and a single record can never disagree.
    private Set<UUID> readable(CandidateReader reader) {
        return inTransaction(() -> {
            Set<UUID> listed = new HashSet<>(named.queryForList("SELECT a.id FROM job_applications a"
                    + " JOIN recruitment_requisitions r ON r.id = a.requisition_id WHERE " + CandidateAccess.READABLE,
                    access.parameters(reader), UUID.class));
            for (UUID application : jdbc.queryForList("SELECT id FROM job_applications", UUID.class)) {
                assertThat(access.findApplication(reader, application).orElseThrow().readable())
                        .as("findApplication(%s)", application).isEqualTo(listed.contains(application));
            }
            return listed;
        });
    }

    private <T> T inTransaction(Supplier<T> work) {
        var template = new TransactionTemplate(transactions);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return template.execute(status -> work.get());
    }

    private CandidateReader reader(UUID account) {
        return access.requireReader(jwt(account));
    }

    private Jwt jwt(UUID account) {
        return jwt(account, NOW.plus(TokenService.ACCESS_TOKEN_TTL));
    }

    // A session row of this account (SQL) and an access token for it that expires at expiresAt.
    private Jwt jwt(UUID account, Instant expiresAt) {
        UUID session = UUID.randomUUID();
        String refreshHash = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
        jdbc.update("INSERT INTO auth_sessions (id, user_id, refresh_token_hash, created_at, expires_at) VALUES (?,?,?,?,?)",
                session, account, refreshHash, Timestamp.from(NOW.minusSeconds(60)),
                Timestamp.from(NOW.plus(Duration.ofDays(7))));
        return Jwt.withTokenValue("candidate-access-test").header("alg", "HS256").subject(account.toString())
                .claim("jti", session.toString()).issuedAt(NOW.minusSeconds(60)).expiresAt(expiresAt).build();
    }

    private UUID account(String fullName, String email, Role... roles) {
        UUID id = applications.staff(fullName, email);
        for (Role role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role.name());
        }
        return id;
    }

    private UUID department(String code, UUID manager, UUID parentDepartment) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, code, name, manager_user_id, parent_id, active, created_at)"
                + " VALUES (?, ?, ?, ?, ?, TRUE, ?)", id, code, "Phòng " + code, manager, parentDepartment,
                Timestamp.from(NOW));
        return id;
    }

    private UUID departmentOf(UUID requisition) {
        return jdbc.queryForObject("SELECT department_id FROM recruitment_requisitions WHERE id = ?", UUID.class,
                requisition);
    }

    private void insertEvent(UUID application, String kind, String scope, String outcome) {
        jdbc.update("INSERT INTO candidate_access_events (actor_id, candidate_id, application_id, access_kind,"
                + " access_scope, outcome, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?)", primary, inChild.candidateId(),
                application, kind, scope, outcome, Timestamp.from(NOW));
    }

    private List<Map<String, Object>> events() {
        return jdbc.queryForList("SELECT actor_id, candidate_id, application_id, access_kind, access_scope, outcome,"
                + " occurred_at FROM candidate_access_events ORDER BY id");
    }

    private static Map<String, Object> event(UUID actor, UUID candidate, UUID application, String kind, String scope,
                                             String outcome, Timestamp at) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("actor_id", actor);
        row.put("candidate_id", candidate);
        row.put("application_id", application);
        row.put("access_kind", kind);
        row.put("access_scope", scope);
        row.put("outcome", outcome);
        row.put("occurred_at", at);
        return row;
    }

    // The SQLSTATE of the statement's failure (the first SQLException in the cause chain).
    private static String sqlState(Runnable statement) {
        try {
            statement.run();
        } catch (RuntimeException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql) return sql.getSQLState();
            }
            throw exception;
        }
        throw new AssertionError("The statement succeeded");
    }
}
