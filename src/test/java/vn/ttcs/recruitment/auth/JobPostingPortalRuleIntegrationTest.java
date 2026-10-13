package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.jobposting.JobPostingApplicationGate;
import vn.ttcs.recruitment.jobposting.JobPostingApplicationGate.OpenJobPosting;
import vn.ttcs.recruitment.jobposting.JobPostingStatus;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.EMPLOYMENT_TYPE;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.WORK_LOCATION;

// Task 320 (story S4-02): the public list (and its filter values) select open postings in SQL
// (JobPostingReadRepository.PUBLIC_WHERE), while the public detail decides in Java (JobPostingApplicability). This
// test checks that both give the answer written out below, independently of either, for every posting status, a
// deadline before, on and after today, and an approved or paused requisition: a posting is listed exactly when it is
// open; its detail opens exactly when it is open or expired, with applicationOpen true exactly when it is open; every
// other posting answers 404 with the same body as an id that does not exist.
// Task 321: the application gate (JobPostingApplicationGate, for the apply API of task 326) gives the same answers:
// it passes exactly the open postings, refuses the expired ones with 409 JOB_POSTING_EXPIRED and every other one with
// the 404 of an id that does not exist.
// The postings are written with SQL, each with its own requisition and department. The clock is 00:30 on 11 Oct 2026
// in Vietnam, when the date in UTC is still 10 Oct, so a date read in the wrong zone gives other answers.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JobPostingPortalRuleIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-10T17:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 11);
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

    private enum Expected { OPEN, EXPIRED, NOT_PUBLIC }

    private record Fixture(UUID id, UUID requisitionId, UUID department, JobPostingStatus status,
                           RequisitionStatus requisition, LocalDate deadline, Expected expected) {
        @Override
        public String toString() {
            return status + ", requisition " + requisition + ", deadline " + deadline;
        }
    }

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JobPostingApplicationGate gate;

    private JobPostingTestSupport postings;
    private ApprovalTestSupport api;
    private UUID adminId;
    private UUID position;
    private UUID workLocation;
    private UUID employmentType;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
    }

    @BeforeEach
    void resetFixture() throws Exception {
        clock.set(NOW);
        postings = new JobPostingTestSupport(jdbc, json, transactions,
                Integer.parseInt(environment.getRequiredProperty("local.server.port")));
        api = postings.api;
        postings.reset();
        bootstrap.run(new DefaultApplicationArguments());
        adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        position = api.position(NOW);
        workLocation = postings.catalogValue(WORK_LOCATION, "HN", "Hà Nội", true, NOW);
        employmentType = postings.catalogValue(EMPLOYMENT_TYPE, "FULL_TIME", "Toàn thời gian", true, NOW);
    }

    @Test
    void theListAndTheDetailFollowTheSameRule() throws Exception {
        List<Fixture> fixtures = new ArrayList<>();
        int index = 0;
        for (RequisitionStatus requisition : List.of(RequisitionStatus.APPROVED, RequisitionStatus.PAUSED)) {
            for (JobPostingStatus status : JobPostingStatus.values()) {
                for (LocalDate deadline : List.of(TODAY.minusDays(1), TODAY, TODAY.plusDays(1))) {
                    fixtures.add(fixture(index++, status, requisition, deadline));
                }
            }
        }
        assertThat(fixtures).hasSize(2 * JobPostingStatus.values().length * 3);
        // Each answer is reached: open on and before the deadline day, expired after it, hidden otherwise.
        assertThat(fixtures.stream().filter(fixture -> fixture.expected() == Expected.OPEN)).hasSize(2);
        assertThat(fixtures.stream().filter(fixture -> fixture.expected() == Expected.EXPIRED)).hasSize(1);

        // The list, its count and the filter values hold exactly the open postings.
        JsonNode list = ok(postings.publicList(0, 100));
        Set<UUID> listed = new LinkedHashSet<>();
        list.path("items").forEach(item -> listed.add(UUID.fromString(item.path("id").asText())));
        Set<UUID> open = new LinkedHashSet<>();
        Set<UUID> openDepartments = new LinkedHashSet<>();
        for (Fixture fixture : fixtures) {
            if (fixture.expected() == Expected.OPEN) {
                open.add(fixture.id());
                openDepartments.add(fixture.department());
            }
        }
        assertThat(listed).containsExactlyInAnyOrderElementsOf(open);
        assertThat(list.path("totalElements").asLong()).isEqualTo(open.size());
        Set<UUID> filterDepartments = new LinkedHashSet<>();
        ok(postings.publicFilters()).path("departments")
                .forEach(option -> filterDepartments.add(UUID.fromString(option.path("id").asText())));
        assertThat(filterDepartments).containsExactlyInAnyOrderElementsOf(openDepartments);

        // The detail: open or expired with the content, hidden exactly like an id that does not exist.
        var unknown = postings.publicDetail(UUID.randomUUID());
        postings.error(unknown, 404, "JOB_POSTING_NOT_FOUND");
        Map<Expected, Integer> answers = new LinkedHashMap<>();
        for (Fixture fixture : fixtures) {
            var response = postings.publicDetail(fixture.id());
            postings.noStore(response);
            answers.merge(fixture.expected(), 1, Integer::sum);
            if (fixture.expected() == Expected.NOT_PUBLIC) {
                assertThat(response.statusCode()).as(fixture.toString()).isEqualTo(404);
                assertThat(response.body()).as(fixture.toString()).isEqualTo(unknown.body());
                continue;
            }
            JsonNode detail = api.expect(response, 200);
            assertThat(detail.path("id").asText()).as(fixture.toString()).isEqualTo(fixture.id().toString());
            assertThat(detail.path("applicationDeadline").asText()).as(fixture.toString())
                    .isEqualTo(fixture.deadline().toString());
            assertThat(detail.path("applicationOpen").asBoolean()).as(fixture.toString())
                    .isEqualTo(fixture.expected() == Expected.OPEN);
            // 00:00 of the day after the deadline in Vietnam, 17:00 UTC on the deadline day itself.
            assertThat(detail.path("applicationClosesAt").asText()).as(fixture.toString())
                    .isEqualTo(fixture.deadline().plusDays(1).atStartOfDay(VIETNAM).toInstant().toString())
                    .isEqualTo(fixture.deadline() + "T17:00:00Z");
        }
        assertThat(answers).containsEntry(Expected.NOT_PUBLIC, fixtures.size() - 3);

        // Task 321: an application to each posting, checked the way the apply API will check it.
        var applications = new TransactionTemplate(transactions);
        for (Fixture fixture : fixtures) {
            Object outcome;
            try {
                outcome = applications.execute(transaction -> gate.requireOpen(fixture.id()));
            } catch (ApiException exception) {
                outcome = exception;
            }
            switch (fixture.expected()) {
                case OPEN -> assertThat(outcome).as(fixture.toString()).isEqualTo(new OpenJobPosting(fixture.id(),
                        fixture.requisitionId(), fixture.deadline()));
                case EXPIRED -> assertRefused(outcome, fixture, HttpStatus.CONFLICT, "JOB_POSTING_EXPIRED",
                        "Tin tuyển dụng đã hết hạn nộp hồ sơ.");
                case NOT_PUBLIC -> assertRefused(outcome, fixture, HttpStatus.NOT_FOUND, "JOB_POSTING_NOT_FOUND",
                        unknownMessage(unknown));
            }
        }
        assertThatThrownBy(() -> applications.execute(transaction -> gate.requireOpen(UUID.randomUUID())))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getMessage()).isEqualTo(unknownMessage(unknown));
                });
    }

    // Task 321: the gate's refusal, with the code and message of the HTTP answer the apply API will give.
    private static void assertRefused(Object outcome, Fixture fixture, HttpStatus status, String code,
                                      String message) {
        assertThat(outcome).as(fixture.toString()).isInstanceOfSatisfying(ApiException.class, exception -> {
            assertThat(exception.getStatus()).as(fixture.toString()).isEqualTo(status);
            assertThat(exception.getCode()).as(fixture.toString()).isEqualTo(code);
            assertThat(exception.getMessage()).as(fixture.toString()).isEqualTo(message);
            assertThat(exception.getFieldErrors()).as(fixture.toString()).isEmpty();
        });
    }

    // The message of the public detail's 404 for an unknown id, so the gate's 404 says the same.
    private String unknownMessage(HttpResponse<String> unknown) {
        JsonNode body = json.readTree(unknown.body());
        assertThat(body.path("code").asText()).isEqualTo("JOB_POSTING_NOT_FOUND");
        return body.path("message").asText();
    }

    // The answer candidates should get, written out without the code's sets. A posting is public only while its
    // requisition is approved; a published posting is open on and before its deadline day and expired after it; no
    // other status is ever public.
    private static Expected expected(JobPostingStatus status, RequisitionStatus requisition, LocalDate deadline) {
        boolean requisitionApproved = switch (requisition) {
            case APPROVED -> true;
            case DRAFT, PENDING_APPROVAL, RETURNED, REJECTED, PAUSED, FILLED, CANCELLED -> false;
        };
        if (!requisitionApproved) {
            return Expected.NOT_PUBLIC;
        }
        return switch (status) {
            case PUBLISHED -> TODAY.isAfter(deadline) ? Expected.EXPIRED : Expected.OPEN;
            // Task 355: a posting taken down is never public again.
            case DRAFT, PENDING_APPROVAL, REVISION_REQUESTED, APPROVED, CLOSED, UNPUBLISHED -> Expected.NOT_PUBLIC;
        };
    }

    // Whether a posting in this status went through the publication, so it names who published it and when (V23).
    // A posting may also be closed before its publication; here every closed one was published first. A posting taken
    // down (task 355) always was (V26).
    private static boolean published(JobPostingStatus status) {
        return switch (status) {
            case PUBLISHED, CLOSED, UNPUBLISHED -> true;
            case DRAFT, PENDING_APPROVAL, REVISION_REQUESTED, APPROVED -> false;
        };
    }

    // One posting with its own approved requisition (then paused with SQL, like task 309's tests) and its own
    // department, published one second after the previous one.
    private Fixture fixture(int index, JobPostingStatus status, RequisitionStatus requisitionStatus,
                            LocalDate deadline) {
        UUID department = api.department("R" + index, adminId, null, NOW);
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, NOW);
        if (requisitionStatus != RequisitionStatus.APPROVED) {
            jdbc.update("UPDATE recruitment_requisitions SET status = ? WHERE id = ?", requisitionStatus.name(),
                    requisition);
        }
        UUID id = UUID.randomUUID();
        Timestamp created = Timestamp.from(NOW.minusSeconds(3600));
        List<Object> values = new ArrayList<>(List.of(id, requisition, status.name(), "Tin " + index,
                "Mô tả công việc.", "Yêu cầu ứng viên.", workLocation, employmentType, deadline, adminId, created,
                created));
        String trailColumns = "";
        String trailValues = "";
        if (published(status)) {
            trailColumns = ", published_by, published_at";
            trailValues = ", ?, ?";
            values.add(adminId);
            values.add(Timestamp.from(NOW.minusSeconds(1000 - index)));
        }
        jdbc.update("INSERT INTO job_postings (id, requisition_id, status, revision, title, job_description, "
                + "candidate_requirements, work_location_id, employment_type_id, application_deadline, show_salary, "
                + "created_by, created_at, updated_at" + trailColumns + ") "
                + "VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, FALSE, ?, ?, ?" + trailValues + ")", values.toArray());
        assertThat(postings.row(id).get("status")).isEqualTo(status.name());
        return new Fixture(id, requisition, department, status, requisitionStatus, deadline,
                expected(status, requisitionStatus, deadline));
    }

    private JsonNode ok(HttpResponse<String> response) {
        JsonNode body = api.expect(response, 200);
        postings.noStore(response);
        return body;
    }
}
