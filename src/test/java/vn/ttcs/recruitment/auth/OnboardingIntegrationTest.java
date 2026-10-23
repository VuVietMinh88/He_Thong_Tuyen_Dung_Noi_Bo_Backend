package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.account.Role;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP and temporary PostgreSQL: templates are snapshots, completion is atomic and HR-authorized. */
class OnboardingIntegrationTest extends ScreeningTestSupport {
    private static final String TEMPLATES = "/api/v1/onboarding/templates";
    private static final LocalDate START = LocalDate.of(2026, 10, 20);
    private UUID department;

    @Test
    void configuredFrontendCanPatchAnItemThroughCorsButOtherOriginsCannot() throws Exception {
        String endpoint = "http://127.0.0.1:" + port + onboardingPath(first.applicationId()) + "/items/" + UUID.randomUUID();
        try (var client = HttpClient.newHttpClient()) {
            for (String origin : List.of("http://localhost:5173", "https://untrusted.example.test")) {
                var request = HttpRequest.newBuilder(URI.create(endpoint)).header("Origin", origin)
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "Content-Type,Authorization")
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(origin.startsWith("http://localhost") ? 200 : 403);
                if (response.statusCode() == 200) {
                    assertThat(response.headers().firstValue("Access-Control-Allow-Methods")).hasValueSatisfying(value -> assertThat(value).contains("PATCH"));
                    assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains(origin);
                } else assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
            }
        }
    }

    @BeforeEach
    void hired() {
        department = jdbc.queryForObject("SELECT department_id FROM recruitment_requisitions WHERE id=?", UUID.class, requisition);
        jdbc.update("UPDATE job_applications SET stage='HIRED' WHERE id=?", first.applicationId());
    }

    @Test
    void snapshotsDepartmentTemplateAndCompletesOnlyOnLastItem() throws Exception {
        JsonNode template = created(createTemplate(3));
        JsonNode checklist = created(createChecklist(template, first.applicationId(), hrToken));
        assertThat(checklist.path("templateVersion").asLong()).isZero();
        assertThat(checklist.path("totalItems").asInt()).isEqualTo(3);
        assertThat(checklist.path("progressPercent").asInt()).isZero();
        assertThat(checklist.path("items").get(0).path("dueDate").asText()).isEqualTo(START.minusDays(1).toString());
        assertThat(checklist.path("items").get(0).path("assigneeId").asText()).isEqualTo(recruiter.toString());

        checklist = ok(patch(checklist, 0, "DONE", hrToken));
        assertThat(checklist.path("progressPercent").asInt()).isEqualTo(33);
        assertThat(stage()).isEqualTo("HIRED");
        checklist = ok(patch(checklist, 1, "DONE", hrToken));
        assertThat(checklist.path("progressPercent").asInt()).isEqualTo(66);
        assertThat(stage()).isEqualTo("HIRED");
        checklist = ok(patch(checklist, 2, "DONE", hrToken));
        assertThat(checklist.path("progressPercent").asInt()).isEqualTo(100);
        assertThat(checklist.path("completedItems").asInt()).isEqualTo(3);
        assertThat(checklist.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(checklist.path("completedBy").asText()).isEqualTo(hr.toString());
        assertThat(checklist.path("completedAt").asText()).isNotBlank();
        assertThat(stage()).isEqualTo("ONBOARDED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM application_stage_events WHERE application_id=?
                AND stage_before='HIRED' AND stage_after='ONBOARDED' AND actor_type='STAFF' AND actor_id=?
                """, Long.class, first.applicationId(), hr)).isEqualTo(1);
        assertThat(ok(call("GET", onboardingPath(first.applicationId()), null, hrToken)).path("status").asText()).isEqualTo("COMPLETED");
        error(patch(checklist, 0, "PENDING", hrToken), 409, "ONBOARDING_ALREADY_COMPLETED");
        assertThat(stage()).isEqualTo("ONBOARDED");
    }

    @Test
    void templateEditsNeverRewriteAnExistingChecklist() throws Exception {
        JsonNode template = created(createTemplate(2));
        JsonNode checklist = created(createChecklist(template, first.applicationId(), hrToken));
        Map<String, Object> update = templateUpdate(template, 1);
        update.put("name", "New template");
        update.put("active", false);
        JsonNode revised = ok(call("PUT", TEMPLATES + "/" + template.path("id").asText(), body(update), hrToken));
        assertThat(revised.path("version").asLong()).isEqualTo(1);
        JsonNode saved = ok(call("GET", onboardingPath(first.applicationId()), null, hrToken));
        assertThat(saved.path("items")).isEqualTo(checklist.path("items"));
        assertThat(saved.path("templateName").asText()).isEqualTo("IT onboarding");
        assertThat(saved.path("templateVersion").asLong()).isZero();
        ok(patch(saved, 0, "DONE", hrToken));
    }

    @Test
    void canReopenAnItemBeforeAggregateCompletionAndChangeItsResponsibility() throws Exception {
        JsonNode checklist = checklist(2);
        checklist = ok(patch(checklist, 0, "DONE", hrToken));
        var update = itemUpdate(checklist, 0, "IN_PROGRESS");
        update.put("title", "Prepare laptop");
        update.put("assigneeId", manager);
        update.put("dueDate", START.plusDays(2).toString());
        checklist = ok(call("PATCH", itemPath(checklist, 0), body(update), hrToken));
        assertThat(checklist.path("progressPercent").asInt()).isZero();
        JsonNode item = checklist.path("items").get(0);
        assertThat(item.path("title").asText()).isEqualTo("Prepare laptop");
        assertThat(item.path("assigneeId").asText()).isEqualTo(manager.toString());
        assertThat(item.path("completedAt").isMissingNode() || item.path("completedAt").isNull()).isTrue();
        assertThat(item.path("completedBy").isMissingNode() || item.path("completedBy").isNull()).isTrue();
        assertThat(stage()).isEqualTo("HIRED");
    }

    @Test
    void closedRequisitionDoesNotPreventPreparingAnAlreadyHiredEmployee() throws Exception {
        JsonNode template = created(createTemplate(1));
        jdbc.update("UPDATE recruitment_requisitions SET status='FILLED' WHERE id=?", requisition);
        JsonNode checklist = created(createChecklist(template, first.applicationId(), hrToken));
        ok(patch(checklist, 0, "DONE", hrToken));
        assertThat(stage()).isEqualTo("ONBOARDED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NEW", "SCREENING", "INTERVIEW", "OFFER", "REJECTED", "WITHDRAWN"})
    void cannotCreateForApplicationThatHasNotBeenHired(String stage) throws Exception {
        JsonNode template = created(createTemplate(1));
        jdbc.update("UPDATE job_applications SET stage=? WHERE id=?", stage, first.applicationId());
        error(createChecklist(template, first.applicationId(), hrToken), 409, "ONBOARDING_APPLICATION_INELIGIBLE");
        assertThat(count("onboarding_checklists")).isZero();
    }

    @Test
    void rejectsWrongDepartmentAndDuplicateWithoutReplacingWork() throws Exception {
        JsonNode template = created(createTemplate(2));
        jdbc.update("UPDATE job_applications SET stage='HIRED' WHERE id=?", other.applicationId());
        error(createChecklist(template, other.applicationId(), hrToken), 409, "ONBOARDING_DEPARTMENT_MISMATCH");
        JsonNode existing = created(createChecklist(template, first.applicationId(), hrToken));
        error(createChecklist(template, first.applicationId(), hrToken), 409, "ONBOARDING_ALREADY_EXISTS");
        assertThat(ok(call("GET", onboardingPath(first.applicationId()), null, hrToken)).path("id")).isEqualTo(existing.path("id"));
        assertThat(count("onboarding_checklist_items")).isEqualTo(2);
    }

    @Test
    void inactiveTemplateAndStaleTemplateVersionCannotBeCopied() throws Exception {
        JsonNode template = created(createTemplate(1));
        var update = templateUpdate(template, 1);
        JsonNode revised = ok(call("PUT", TEMPLATES + "/" + template.path("id").asText(), body(update), hrToken));
        error(createChecklist(template, first.applicationId(), hrToken), 409, "ONBOARDING_VERSION_CHANGED");
        update = templateUpdate(revised, 1);
        update.put("active", false);
        revised = ok(call("PUT", TEMPLATES + "/" + template.path("id").asText(), body(update), hrToken));
        error(createChecklist(revised, first.applicationId(), hrToken), 409, "ONBOARDING_TEMPLATE_INACTIVE");
        assertThat(count("onboarding_checklists")).isZero();
    }

    @Test
    void inactiveOwnerOrDepartmentCannotStartANewChecklist() throws Exception {
        JsonNode template = created(createTemplate(1));
        jdbc.update("UPDATE user_accounts SET enabled=false WHERE id=?", recruiter);
        error(createChecklist(template, first.applicationId(), hrToken), 409, "ONBOARDING_ASSIGNEE_INACTIVE");
        jdbc.update("UPDATE user_accounts SET enabled=true WHERE id=?", recruiter);
        jdbc.update("UPDATE departments SET active=false WHERE id=?", department);
        error(createChecklist(template, first.applicationId(), hrToken), 409, "ONBOARDING_DEPARTMENT_INACTIVE");
        assertThat(count("onboarding_checklists")).isZero();
    }

    @Test
    void staleAndCrossChecklistItemUpdatesCannotChangeAnything() throws Exception {
        JsonNode original = checklist(2);
        JsonNode changed = ok(patch(original, 0, "IN_PROGRESS", hrToken));
        error(patch(original, 1, "DONE", hrToken), 409, "ONBOARDING_VERSION_CHANGED");
        error(call("PATCH", onboardingPath(first.applicationId()) + "/items/" + UUID.randomUUID(),
                body(itemUpdate(changed, 0, "DONE")), hrToken), 404, "ONBOARDING_ITEM_NOT_FOUND");
        assertThat(ok(call("GET", onboardingPath(first.applicationId()), null, hrToken)).path("version").asLong()).isEqualTo(1);
        assertThat(stage()).isEqualTo("HIRED");
    }

    @Test
    void unauthorizedAssigneeCannotReadOrWriteEvenWhenAssignedTheWork() throws Exception {
        JsonNode checklist = checklist(1);
        for (String token : List.of(recruiterToken, token(manager), token(outsider))) {
            assertThat(call("GET", TEMPLATES, null, token).statusCode()).isEqualTo(403);
            assertThat(call("GET", onboardingPath(first.applicationId()), null, token).statusCode()).isEqualTo(403);
            assertThat(patch(checklist, 0, "DONE", token).statusCode()).isEqualTo(403);
        }
        assertThat(call("GET", onboardingPath(first.applicationId()), null, null).statusCode()).isEqualTo(401);
        assertThat(stage()).isEqualTo("HIRED");
        assertThat(jdbc.queryForObject("SELECT status FROM onboarding_checklist_items LIMIT 1", String.class)).isEqualTo("PENDING");
    }

    @Test
    void administratorCanManageWithoutSalaryPermission() throws Exception {
        UUID admin = applications.account("Admin", "admin-onboarding@example.test", Role.ADMIN);
        String adminToken = token(admin);
        JsonNode template = created(call("POST", TEMPLATES, body(templateBody(1)), adminToken));
        JsonNode checklist = created(createChecklist(template, first.applicationId(), adminToken));
        ok(patch(checklist, 0, "DONE", adminToken));
        assertThat(stage()).isEqualTo("ONBOARDED");
    }

    @Test
    void listSupportsPagingAndDepartmentFilterWithoutExposingOtherRows() throws Exception {
        created(createTemplate(1));
        created(createTemplate(2));
        JsonNode page = ok(call("GET", TEMPLATES + "?departmentId=" + department + "&size=1", null, hrToken));
        assertThat(page.path("items").size()).isEqualTo(1);
        assertThat(page.path("totalElements").asLong()).isEqualTo(2);
        assertThat(page.path("totalPages").asLong()).isEqualTo(2);
        assertThat(ok(call("GET", TEMPLATES + "?departmentId=" + UUID.randomUUID(), null, hrToken)).path("items").size()).isZero();
        assertThat(call("GET", TEMPLATES + "?size=101", null, hrToken).statusCode()).isEqualTo(400);
        assertThat(call("GET", TEMPLATES + "?page=-1", null, hrToken).statusCode()).isEqualTo(400);
        error(call("GET", TEMPLATES + "/" + UUID.randomUUID(), null, hrToken), 404, "ONBOARDING_TEMPLATE_NOT_FOUND");
        error(call("GET", onboardingPath(first.applicationId()), null, hrToken), 404, "ONBOARDING_CHECKLIST_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty", "tooMany", "blank", "invisible", "unknown", "decimalOffset", "stringOffset", "invalidBoolean", "nullItem"})
    void invalidTemplatesNeverCreatePartialRows(String invalid) throws Exception {
        var request = templateBody(1);
        var item = new LinkedHashMap<String, Object>(items(1).get(0));
        switch (invalid) {
            case "empty" -> request.put("items", List.of());
            case "tooMany" -> request.put("items", items(101));
            case "blank" -> request.put("name", "  ");
            case "invisible" -> request.put("name", "\u200B");
            case "unknown" -> request.put("createdBy", hr);
            case "decimalOffset" -> { item.put("dueOffsetDays", 1.5); request.put("items", List.of(item)); }
            case "stringOffset" -> { item.put("dueOffsetDays", "1"); request.put("items", List.of(item)); }
            case "invalidBoolean" -> request.put("active", "true");
            case "nullItem" -> request.put("items", java.util.Arrays.asList((Object) null));
            default -> throw new IllegalArgumentException();
        }
        assertThat(call("POST", TEMPLATES, body(request), hrToken).statusCode()).isEqualTo(400);
        assertThat(count("onboarding_templates")).isZero();
        assertThat(count("onboarding_template_items")).isZero();
    }

    @Test
    void invalidDatesStatusAndServerOwnedFieldsNeverPartiallyUpdate() throws Exception {
        JsonNode checklist = checklist(2);
        for (Map<String, Object> invalid : List.of(
                Map.<String, Object>of("dueDate", START.plusDays(366).toString()),
                Map.<String, Object>of("dueDate", START.minusDays(31).toString()),
                Map.<String, Object>of("status", "COMPLETED"),
                Map.<String, Object>of("completedBy", hr),
                Map.<String, Object>of("title", "x".repeat(201)))) {
            var request = itemUpdate(checklist, 0, "DONE");
            request.putAll(invalid);
            assertThat(call("PATCH", itemPath(checklist, 0), body(request), hrToken).statusCode()).isEqualTo(400);
        }
        assertThat(ok(call("GET", onboardingPath(first.applicationId()), null, hrToken)).path("version").asLong()).isZero();
        assertThat(stage()).isEqualTo("HIRED");
    }

    @Test
    void concurrentChecklistCreationHasOneWinnerAndNoDuplicateItems() throws Exception {
        JsonNode template = created(createTemplate(2));
        UUID secondHr = applications.account("HR Two", "hr-two@example.test", Role.HR_MANAGER);
        String secondToken = token(secondHr);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return createChecklist(template, first.applicationId(), hrToken); });
            var two = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return createChecklist(template, first.applicationId(), secondToken); });
            assertThat(List.of(one.get(15, TimeUnit.SECONDS).statusCode(), two.get(15, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(201, 409);
        }
        assertThat(count("onboarding_checklists")).isEqualTo(1);
        assertThat(count("onboarding_checklist_items")).isEqualTo(2);
    }

    @Test
    void concurrentLastItemCompletionHasOneWinnerAndOneStageEvent() throws Exception {
        JsonNode checklist = checklist(1);
        UUID secondHr = applications.account("HR Two", "hr-two@example.test", Role.HR_MANAGER);
        String secondToken = token(secondHr);
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return patch(checklist, 0, "DONE", hrToken); });
            var two = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return patch(checklist, 0, "DONE", secondToken); });
            assertThat(List.of(one.get(15, TimeUnit.SECONDS).statusCode(), two.get(15, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(stage()).isEqualTo("ONBOARDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events WHERE application_id=? AND stage_after='ONBOARDED'",
                Long.class, first.applicationId())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"session", "permission"})
    void rechecksLiveAuthorityAfterWaitingForTemplate(String revoke) throws Exception {
        JsonNode template = created(createTemplate(1));
        try (Connection blocker = dataSource.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var hold = blocker.prepareStatement("SELECT id FROM onboarding_templates WHERE id=? FOR UPDATE")) {
                hold.setObject(1, UUID.fromString(template.path("id").asText()));
                hold.executeQuery().close();
            }
            var pending = pool.submit(() -> createChecklist(template, first.applicationId(), hrToken));
            try {
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                if ("session".equals(revoke)) clock.set(ApplicationTestSupport.NOW.plusSeconds(3600));
                else jdbc.update("DELETE FROM role_permissions WHERE role_code='HR_MANAGER' AND permission_code='OFFERS_WRITE_ALL'");
            } finally {
                blocker.rollback();
            }
            assertThat(pending.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo("session".equals(revoke) ? 401 : 403);
        } finally {
            jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES('HR_MANAGER','OFFERS_WRITE_ALL') ON CONFLICT DO NOTHING");
        }
        assertThat(count("onboarding_checklists")).isZero();
        assertThat(count("onboarding_checklist_items")).isZero();
        assertThat(stage()).isEqualTo("HIRED");
    }

    private JsonNode checklist(int count) throws Exception {
        return created(createChecklist(created(createTemplate(count)), first.applicationId(), hrToken));
    }
    private HttpResponse<String> createTemplate(int count) throws Exception {
        return call("POST", TEMPLATES, body(templateBody(count)), hrToken);
    }
    private Map<String, Object> templateBody(int count) {
        var request = new LinkedHashMap<String, Object>();
        request.put("departmentId", department); request.put("name", "IT onboarding");
        request.put("active", true); request.put("items", items(count)); return request;
    }
    private Map<String, Object> templateUpdate(JsonNode template, int count) {
        var request = templateBody(count);
        request.remove("departmentId"); request.put("expectedVersion", template.path("version").asLong()); return request;
    }
    private List<Map<String, Object>> items(int count) {
        var items = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < count; i++) items.add(Map.of("title", "Task " + (i + 1), "assigneeId", recruiter, "dueOffsetDays", i - 1));
        return items;
    }
    private HttpResponse<String> createChecklist(JsonNode template, UUID application, String token) throws Exception {
        return call("POST", onboardingPath(application), body(Map.of("templateId", template.path("id").asText(),
                "expectedTemplateVersion", template.path("version").asLong(), "startDate", START.toString())), token);
    }
    private Map<String, Object> itemUpdate(JsonNode checklist, int item, String status) {
        JsonNode previous = checklist.path("items").get(item);
        var request = new LinkedHashMap<String, Object>();
        request.put("expectedVersion", checklist.path("version").asLong()); request.put("title", previous.path("title").asText());
        request.put("assigneeId", previous.path("assigneeId").asText()); request.put("dueDate", previous.path("dueDate").asText());
        request.put("status", status); return request;
    }
    private HttpResponse<String> patch(JsonNode checklist, int item, String status, String token) throws Exception {
        return call("PATCH", itemPath(checklist, item), body(itemUpdate(checklist, item, status)), token);
    }
    private String itemPath(JsonNode checklist, int item) {
        return onboardingPath(first.applicationId()) + "/items/" + checklist.path("items").get(item).path("id").asText();
    }
    private String onboardingPath(UUID application) { return "/api/v1/applications/" + application + "/onboarding"; }
    private String body(Object value) { return json.writeValueAsString(value); }
    private JsonNode created(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
        return json.readTree(response.body());
    }
    private void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }
    private String stage() { return jdbc.queryForObject("SELECT stage FROM job_applications WHERE id=?", String.class, first.applicationId()); }
}
