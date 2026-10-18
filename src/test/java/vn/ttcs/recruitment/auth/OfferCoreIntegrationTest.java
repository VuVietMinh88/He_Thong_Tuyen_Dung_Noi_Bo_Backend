package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.common.BusinessCalendar;
import vn.ttcs.recruitment.mail.MailLayout;
import vn.ttcs.recruitment.offer.OfferMailContent;
import vn.ttcs.recruitment.offer.OfferMailPayload;
import vn.ttcs.recruitment.offerdelivery.OfferDeliverySettings;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Draft and approval behavior through real HTTP and disposable PostgreSQL only. */
@TestPropertySource(properties = {
        "app.offers.enabled=true",
        "app.offers.delivery-enabled=false",
        "app.offers.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.offers.time-zone=UTC",
        "app.offers.expiry-delay-ms=3600000",
        "app.offers.poll-delay-ms=3600000"
})
class OfferCoreIntegrationTest extends ScreeningTestSupport {
    @Autowired MailLayout mailLayout;
    @Autowired OfferDeliverySettings deliverySettings;
    @Autowired BusinessCalendar calendar;
    UUID position, higher, anotherHr;
    String higherToken, anotherHrToken;
    static final long INSIDE = 15_000_000;

    @BeforeEach
    void offerFixture() {
        jdbc.update("UPDATE job_applications SET stage='OFFER' WHERE id IN (?,?)", first.applicationId(), other.applicationId());
        position = jdbc.queryForObject("SELECT position_id FROM recruitment_requisitions WHERE id=?", UUID.class, requisition);
        jdbc.update("UPDATE positions SET salary_min=10000000,salary_max=20000000 WHERE id=?", position);
        higher = applications.account("Higher approver", "higher-offer@example.test", Role.APPROVER);
        anotherHr = applications.account("Other HR", "other-hr-offer@example.test", Role.HR_MANAGER);
        higherToken = token(higher);
        anotherHrToken = token(anotherHr);
    }

    @Test
    void draftStoresTermsAndMasksBandForRecruiterButHrCanReadIt() throws Exception {
        JsonNode created = create(INSIDE, null);
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");
        assertThat(created.path("salary").asLong()).isEqualTo(INSIDE);
        assertThat(created.path("allowance").asLong()).isEqualTo(1_000_000);
        assertThat(created.path("candidateId").asText()).isEqualTo(first.candidateId().toString());
        assertThat(created.has("bandMin")).isFalse();
        assertThat(created.path("deliveryStatus").asText()).isEqualTo("NOT_QUEUED");
        JsonNode read = ok(call("GET", path(created), null, hrToken));
        assertThat(read.path("bandMin").asLong()).isEqualTo(10_000_000);
        assertThat(read.path("bandMax").asLong()).isEqualTo(20_000_000);
        assertThat(count("offer_events")).isEqualTo(1);
    }

    @Test
    void outsideBandNeedsExplanationAtBothBoundaries() throws Exception {
        for (long salary : new long[]{9_999_999, 20_000_001}) {
            error(call("POST", createPath(first.applicationId()), json.writeValueAsString(draft(salary, null)), recruiterToken), 400, "VALIDATION_ERROR");
        }
        JsonNode low = create(10_000_000, null);
        assertThat(low.path("outsideBand").asBoolean()).isFalse();
        ok(cancel(low, 0, "Replace draft"));
        JsonNode high = create(20_000_000, null);
        assertThat(high.path("outsideBand").asBoolean()).isFalse();
    }

    @Test
    void draftValidationRejectsFractionalStringNegativeAndForgedFields() throws Exception {
        for (Object salary : List.of(1.5, "15000000", -1, 1_000_000_000_001L)) {
            var request = draft(INSIDE, null); request.put("salary", salary);
            assertThat(call("POST", createPath(first.applicationId()), json.writeValueAsString(request), recruiterToken).statusCode()).isEqualTo(400);
        }
        var forged = draft(INSIDE, null); forged.put("status", "APPROVED");
        assertThat(call("POST", createPath(first.applicationId()), json.writeValueAsString(forged), recruiterToken).statusCode()).isEqualTo(400);
        assertThat(count("offers")).isZero();
    }

    @Test
    void deadlineAndStartDateMustBeFutureAndInBusinessDateOrder() throws Exception {
        var deadlinePast = draft(INSIDE, null); deadlinePast.put("responseDeadline", ApplicationTestSupport.NOW.toString());
        var startPast = draft(INSIDE, null); startPast.put("startDate", "2026-10-14");
        var deadlineAfterStart = draft(INSIDE, null); deadlineAfterStart.put("startDate", "2026-10-16");
        for (var request : List.of(deadlinePast, startPast, deadlineAfterStart)) {
            error(call("POST", createPath(first.applicationId()), json.writeValueAsString(request), recruiterToken), 400, "VALIDATION_ERROR");
        }
        assertThat(count("offers")).isZero();
    }

    @Test
    void extremeInstantDeadlineIsValidationErrorRatherThanServerError() throws Exception {
        var request = draft(INSIDE, null);
        request.put("responseDeadline", "+1000000000-12-31T23:59:59Z");
        assertThat(call("POST", createPath(first.applicationId()), json.writeValueAsString(request), recruiterToken).statusCode()).isEqualTo(400);
        assertThat(count("offers")).isZero();
    }

    @Test
    void previewUsesCandidateTermsWithoutInternalExplanationOrApprovalData() throws Exception {
        JsonNode offer = create(25_000_000, "INTERNAL exceptional skill explanation");
        JsonNode preview = ok(call("GET", path(offer) + "/preview", null, recruiterToken));
        assertThat(preview.path("subject").asText()).isNotBlank();
        assertThat(preview.path("plainText").asText()).contains("25.000.000", "1.000.000");
        assertThat(preview.path("html").asText()).contains("25.000.000");
        assertThat(preview.toString()).doesNotContain("INTERNAL", "bandMin", "approvalRound", "token=");
        assertThat(count("offer_delivery_outbox")).isZero();
    }

    @Test
    void previewUsesConfiguredDeliveryZoneWithoutChangingBusinessDates() throws Exception {
        JsonNode offer = create(INSIDE, null);
        JsonNode preview = ok(call("GET", path(offer) + "/preview", null, recruiterToken));
        var payload = new OfferMailPayload(
                offer.path("email").asText(), offer.path("candidateName").asText(),
                offer.path("positionTitle").asText(), offer.path("salary").asLong(),
                offer.path("allowance").asLong(), LocalDate.parse(offer.path("startDate").asText()),
                Instant.parse(offer.path("responseDeadline").asText()), null);
        var expected = mailLayout.render(OfferMailContent.body(payload, deliverySettings.zone()));

        assertThat(deliverySettings.zone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(calendar.zone()).isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
        assertThat(preview.path("plainText").asText()).isEqualTo(expected.plainText()).contains("UTC").doesNotContain("Giờ Việt Nam");
        assertThat(preview.path("html").asText()).isEqualTo(expected.html());
    }

    @Test
    void uniqueCandidateSlotCoversOtherApplicationsAndCancellationReleasesIt() throws Exception {
        JsonNode offer = create(INSIDE, null);
        var otherTerms = draft(INSIDE, "Other position explanation");
        error(call("POST", createPath(other.applicationId()), json.writeValueAsString(otherTerms), hrToken), 409, "OFFER_ALREADY_ACTIVE");
        ok(cancel(offer, 0, "Candidate chose another opening"));
        assertThat(call("POST", createPath(other.applicationId()), json.writeValueAsString(otherTerms), hrToken).statusCode()).isEqualTo(201);
    }

    @Test
    void simultaneousOffersForSameCandidateCannotBothCommit() throws Exception {
        var barrier = new CyclicBarrier(2);
        String body = json.writeValueAsString(draft(INSIDE, "Valid for both positions"));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return call("POST", createPath(first.applicationId()), body, hrToken); });
            var b = executor.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return call("POST", createPath(other.applicationId()), body, anotherHrToken); });
            assertThat(List.of(a.get(20, TimeUnit.SECONDS).statusCode(), b.get(20, TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(201, 409);
        }
        assertThat(count("offers")).isEqualTo(1);
        assertThat(count("offer_events")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"token", "deadline"})
    void candidateUniqueIndexWaitRechecksTokenAndDeadlineBeforeCommit(String expiry) throws Exception {
        JsonNode existing = create(INSIDE, null);
        var request = draft(INSIDE, "Valid exception for the second position");
        if (expiry.equals("deadline")) request.put("responseDeadline", ApplicationTestSupport.NOW.plusSeconds(1).toString());
        String body = json.writeValueAsString(request);
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var update = blocker.prepareStatement("UPDATE offers SET status='CANCELLED' WHERE id=?")) {
                update.setObject(1, UUID.fromString(existing.path("id").asText())); update.executeUpdate();
            }
            var pending = executor.submit(() -> call("POST", createPath(other.applicationId()), body, hrToken));
            try {
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                clock.set(ApplicationTestSupport.NOW.plusSeconds(expiry.equals("token") ? 3600 : 2));
                blocker.commit();
            } finally { blocker.rollback(); }
            assertThat(pending.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(expiry.equals("token") ? 401 : 400);
        } finally { clock.set(ApplicationTestSupport.NOW); }
        assertThat(count("offers")).isEqualTo(1);
        assertThat(count("offer_events")).isEqualTo(1);
    }

    @Test
    void updateRequiresCurrentVersionAndCannotAlterSubmittedTerms() throws Exception {
        JsonNode offer = create(INSIDE, null);
        var changed = draft(18_000_000, null); changed.put("expectedVersion", 0);
        JsonNode updated = ok(call("PUT", path(offer), json.writeValueAsString(changed), recruiterToken));
        assertThat(updated.path("version").asLong()).isEqualTo(1);
        error(call("PUT", path(offer), json.writeValueAsString(changed), recruiterToken), 409, "OFFER_VERSION_CHANGED");
        ok(submit(offer, 1, hr, null));
        changed.put("expectedVersion", 2);
        error(call("PUT", path(offer), json.writeValueAsString(changed), recruiterToken), 409, "OFFER_NOT_EDITABLE");
        UUID id = UUID.fromString(offer.path("id").asText());
        assertThatThrownBy(() -> jdbc.update("UPDATE offers SET salary=19000000 WHERE id=?", id))
                .rootCause().hasMessageContaining("submitted offer terms are frozen");
    }

    @Test
    void withinBandUsesOneAssignedHrApprovalAndStoresHistory() throws Exception {
        JsonNode offer = create(INSIDE, null);
        assertThat(ok(submit(offer, 0, hr, null)).path("status").asText()).isEqualTo("PENDING_HR");
        JsonNode approved = ok(decide(offer, 1, "APPROVE", null, hrToken));
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("approvedAt").asText()).isEqualTo(ApplicationTestSupport.NOW.toString());
        JsonNode events = ok(call("GET", path(offer) + "/history", null, recruiterToken));
        assertThat(events.path("totalElements").asLong()).isEqualTo(3);
        assertThat(events.toString()).contains("HR_APPROVED", hr.toString()).doesNotContain("terms_snapshot", "bandMin");
    }

    @Test
    void outsideBandNeedsHrThenDistinctNamedHigherApproverWithNarrowAccess() throws Exception {
        JsonNode offer = create(25_000_000, "Scarce expertise");
        error(submit(offer, 0, hr, null), 400, "VALIDATION_ERROR");
        ok(submit(offer, 0, hr, higher));
        error(decide(offer, 1, "APPROVE", null, higherToken), 403, "FORBIDDEN");
        JsonNode hrApproved = ok(decide(offer, 1, "APPROVE", null, hrToken));
        assertThat(hrApproved.path("status").asText()).isEqualTo("PENDING_HIGHER");
        JsonNode narrow = ok(call("GET", path(offer), null, higherToken));
        assertThat(narrow.has("bandMin")).isFalse();
        ok(call("GET", path(offer) + "/preview", null, higherToken));
        ok(call("GET", path(offer) + "/history", null, higherToken));
        JsonNode approved = ok(decide(offer, 2, "APPROVE", null, higherToken));
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        error(decide(offer, 3, "APPROVE", null, higherToken), 409, "OFFER_NOT_PENDING");
    }

    @Test
    void unrelatedApproverCannotReadPreviewHistoryOrApprove() throws Exception {
        JsonNode offer = create(25_000_000, "Scarce expertise");
        UUID unassigned = applications.account("Unassigned", "unassigned-offer@example.test", Role.APPROVER);
        String unassignedToken = token(unassigned);
        ok(submit(offer, 0, hr, higher));
        for (String suffix : List.of("", "/preview", "/history")) error(call("GET", path(offer) + suffix, null, unassignedToken), 403, "FORBIDDEN");
        error(decide(offer, 1, "APPROVE", null, anotherHrToken), 403, "FORBIDDEN");
        assertThat(count("offer_events")).isEqualTo(2);
    }

    @Test
    void creatorAndSubmitterCannotBeAssignedAndLevelsMustUseDifferentPeople() throws Exception {
        JsonNode offer = created(call("POST", createPath(first.applicationId()), json.writeValueAsString(draft(INSIDE, null)), hrToken));
        error(call("POST", path(offer) + "/submit", json.writeValueAsString(Map.of("expectedVersion", 0, "hrApproverId", hr)), hrToken), 400, "VALIDATION_ERROR");
        assertThat(ok(call("GET", path(offer), null, hrToken)).path("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void rejectsAndChangeRequestsNeedMeaningfulComment() throws Exception {
        JsonNode offer = create(INSIDE, null); ok(submit(offer, 0, hr, null));
        for (String action : List.of("REJECT", "REQUEST_CHANGES")) {
            error(decide(offer, 1, action, null, hrToken), 400, "VALIDATION_ERROR");
            error(decide(offer, 1, action, "   ", hrToken), 400, "VALIDATION_ERROR");
        }
        JsonNode rejected = ok(decide(offer, 1, "REJECT", "Budget not approved", hrToken));
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        assertThat(count("offer_events")).isEqualTo(3);
        assertThat(create(INSIDE, null).path("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void requestedChangesRequireFreshRoundAndRepeatHrApproval() throws Exception {
        JsonNode offer = create(25_000_000, "Scarce expertise"); ok(submit(offer, 0, hr, higher));
        ok(decide(offer, 1, "APPROVE", null, hrToken));
        JsonNode changes = ok(decide(offer, 2, "REQUEST_CHANGES", "Reduce salary", higherToken));
        assertThat(changes.path("status").asText()).isEqualTo("CHANGES_REQUESTED");
        var corrected = draft(22_000_000, "Negotiated exception"); corrected.put("expectedVersion", 3);
        ok(call("PUT", path(offer), json.writeValueAsString(corrected), recruiterToken));
        JsonNode secondRound = ok(submit(offer, 4, hr, higher));
        assertThat(secondRound.path("approvalRound").asInt()).isEqualTo(2);
        assertThat(secondRound.path("status").asText()).isEqualTo("PENDING_HR");
        error(decide(offer, 5, "APPROVE", null, higherToken), 403, "FORBIDDEN");
        ok(decide(offer, 5, "APPROVE", null, hrToken));
        ok(decide(offer, 6, "APPROVE", null, higherToken));
    }

    @Test
    void approvalHistoryCannotBeUpdatedOrDeleted() throws Exception {
        JsonNode offer = create(INSIDE, null); ok(submit(offer, 0, hr, null));
        UUID id = UUID.fromString(offer.path("id").asText());
        assertThatThrownBy(() -> jdbc.update("UPDATE offer_events SET comment='rewrite' WHERE offer_id=?", id))
                .rootCause().hasMessageContaining("offer_events rows are never changed or deleted");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM offer_events WHERE offer_id=?", id))
                .rootCause().hasMessageContaining("offer_events rows are never changed or deleted");
    }

    @Test
    void salaryBandIsRefreshedAtSubmitButFrozenAfterSubmission() throws Exception {
        JsonNode offer = create(INSIDE, null);
        jdbc.update("UPDATE positions SET salary_max=12000000 WHERE id=?", position);
        error(submit(offer, 0, hr, null), 400, "VALIDATION_ERROR");
        var revised = draft(INSIDE, "Band changed after drafting"); revised.put("expectedVersion", 0);
        ok(call("PUT", path(offer), json.writeValueAsString(revised), recruiterToken));
        ok(submit(offer, 1, hr, higher));
        jdbc.update("UPDATE positions SET salary_max=30000000 WHERE id=?", position);
        JsonNode hrApproved = ok(decide(offer, 2, "APPROVE", null, hrToken));
        assertThat(hrApproved.path("status").asText()).isEqualTo("PENDING_HIGHER");
        assertThat(hrApproved.path("bandMax").asLong()).isEqualTo(12_000_000);
    }

    @Test
    void appStageRequisitionAndPositionMustRemainActiveForApproval() throws Exception {
        JsonNode offer = create(INSIDE, null); ok(submit(offer, 0, hr, null));
        jdbc.update("UPDATE positions SET active=false WHERE id=?", position);
        error(decide(offer, 1, "APPROVE", null, hrToken), 409, "POSITION_INACTIVE");
        jdbc.update("UPDATE positions SET active=true WHERE id=?", position);
        jdbc.update("UPDATE recruitment_requisitions SET status='PAUSED' WHERE id=?", requisition);
        error(decide(offer, 1, "APPROVE", null, hrToken), 409, "OFFER_APPLICATION_INACTIVE");
        jdbc.update("UPDATE recruitment_requisitions SET status='APPROVED' WHERE id=?", requisition);
        jdbc.update("UPDATE job_applications SET stage='WITHDRAWN' WHERE id=?", first.applicationId());
        error(decide(offer, 1, "APPROVE", null, hrToken), 409, "OFFER_APPLICATION_INACTIVE");
        assertThat(count("offer_events")).isEqualTo(2);
    }

    @Test
    void assignmentAndLivePermissionsAreRequiredForApproval() throws Exception {
        JsonNode offer = create(INSIDE, null); ok(submit(offer, 0, hr, null));
        jdbc.update("DELETE FROM role_permissions WHERE role_code='HR_MANAGER' AND permission_code='OFFERS_WRITE_ALL'");
        try { error(decide(offer, 1, "APPROVE", null, hrToken), 403, "FORBIDDEN"); }
        finally { jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('HR_MANAGER','OFFERS_WRITE_ALL')"); }
        jdbc.update("UPDATE auth_sessions SET revoked_at=? WHERE user_id=?", Timestamp.from(ApplicationTestSupport.NOW), hr);
        assertThat(decide(offer, 1, "APPROVE", null, hrToken).statusCode()).isEqualTo(401);
    }

    @Test
    void anotherRecruiterCannotCreateReadOrMutateOutsideScope() throws Exception {
        String denied = token(outsider);
        error(call("POST", createPath(first.applicationId()), json.writeValueAsString(draft(INSIDE, null)), denied), 403, "FORBIDDEN");
        JsonNode offer = create(INSIDE, null);
        error(call("GET", path(offer), null, denied), 403, "FORBIDDEN");
        error(call("GET", createPath(first.applicationId()), null, denied), 403, "FORBIDDEN");
        error(call("POST", path(offer) + "/cancel", "{\"expectedVersion\":0,\"reason\":\"No scope\"}", denied), 403, "FORBIDDEN");
    }

    @Test
    void departmentManagerReadScopeDoesNotGrantOfferWritesAfterRecruiterHandover() throws Exception {
        JsonNode offer = create(INSIDE, null);
        ok(submit(offer, 0, hr, null));
        ok(decide(offer, 1, "APPROVE", null, hrToken));
        jdbc.update("UPDATE departments SET manager_user_id=? WHERE id=(SELECT department_id FROM recruitment_requisitions WHERE id=?)",
                recruiter, requisition);
        jdbc.update("DELETE FROM requisition_recruiters WHERE requisition_id=? AND recruiter_id=?", requisition, recruiter);

        ok(call("GET", path(offer), null, recruiterToken));
        ok(call("GET", createPath(first.applicationId()), null, recruiterToken));
        error(call("POST", createPath(first.applicationId()), json.writeValueAsString(draft(INSIDE, null)), recruiterToken), 403, "FORBIDDEN");
        var edit = draft(INSIDE, null);
        edit.put("expectedVersion", 2);
        error(call("PUT", path(offer), json.writeValueAsString(edit), recruiterToken), 403, "FORBIDDEN");
        error(call("POST", path(offer) + "/send", "{\"expectedVersion\":2}", recruiterToken), 403, "FORBIDDEN");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events WHERE actor_id=? AND application_id=? AND outcome='DENIED'",
                Long.class, recruiter, first.applicationId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM offers WHERE id=?", String.class,
                UUID.fromString(offer.path("id").asText()))).isEqualTo("APPROVED");
        assertThat(count("offers")).isEqualTo(1);
        assertThat(count("offer_events")).isEqualTo(3);
        assertThat(count("offer_delivery_outbox")).isZero();
    }

    @Test
    void draftPermissionIsRecheckedAfterPositionLockWait() throws Exception {
        String body = json.writeValueAsString(draft(INSIDE, null));
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var hold = blocker.prepareStatement("SELECT id FROM positions WHERE id=? FOR UPDATE")) { hold.setObject(1, position); hold.executeQuery().close(); }
            var pending = executor.submit(() -> call("POST", createPath(first.applicationId()), body, recruiterToken));
            try {
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                jdbc.update("DELETE FROM role_permissions WHERE role_code='RECRUITER' AND permission_code='OFFERS_WRITE_SCOPED'");
            } finally { blocker.rollback(); }
            error(pending.get(10, TimeUnit.SECONDS), 403, "FORBIDDEN");
        } finally { jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('RECRUITER','OFFERS_WRITE_SCOPED') ON CONFLICT DO NOTHING"); }
        assertThat(count("offers")).isZero();
    }

    @Test
    void approvalIsOptimisticAndDuplicateDecisionDoesNotAddHistory() throws Exception {
        JsonNode offer = create(INSIDE, null); ok(submit(offer, 0, hr, null));
        error(decide(offer, 0, "APPROVE", null, hrToken), 409, "OFFER_VERSION_CHANGED");
        ok(decide(offer, 1, "APPROVE", null, hrToken));
        error(decide(offer, 1, "APPROVE", null, hrToken), 409, "OFFER_NOT_PENDING");
        assertThat(count("offer_events")).isEqualTo(3);
    }

    @Test
    void readListAndApproverDirectoryAreBoundedAndContainNoCredentials() throws Exception {
        JsonNode offer = create(INSIDE, null);
        JsonNode listed = ok(call("GET", createPath(first.applicationId()) + "?size=1", null, recruiterToken));
        assertThat(listed.path("totalElements").asLong()).isEqualTo(1);
        assertThat(listed.path("items").get(0).path("id").asText()).isEqualTo(offer.path("id").asText());
        JsonNode approvers = ok(call("GET", "/api/v1/offers/approvers", null, recruiterToken));
        assertThat(approvers.toString()).contains(hr.toString()).doesNotContain("email", "password", "roles");
        error(call("GET", createPath(first.applicationId()) + "?size=101", null, recruiterToken), 400, "VALIDATION_ERROR");
        error(call("GET", path(offer) + "/history?page=-1", null, recruiterToken), 400, "VALIDATION_ERROR");
    }

    private Map<String, Object> draft(long salary, String explanation) {
        var result = new LinkedHashMap<String, Object>();
        result.put("salary", salary); result.put("allowance", 1_000_000);
        result.put("startDate", LocalDate.ofInstant(ApplicationTestSupport.NOW, ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(15).toString());
        result.put("responseDeadline", ApplicationTestSupport.NOW.plusSeconds(7 * 86400).toString());
        if (explanation != null) result.put("explanation", explanation);
        return result;
    }
    private JsonNode create(long salary, String explanation) throws Exception {
        return created(call("POST", createPath(first.applicationId()), json.writeValueAsString(draft(salary, explanation)), recruiterToken));
    }
    private JsonNode created(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
        return json.readTree(response.body());
    }
    private HttpResponse<String> submit(JsonNode offer, long version, UUID hrApprover, UUID higherApprover) throws Exception {
        var body = new LinkedHashMap<String, Object>(); body.put("expectedVersion", version); body.put("hrApproverId", hrApprover);
        if (higherApprover != null) body.put("higherApproverId", higherApprover);
        return call("POST", path(offer) + "/submit", json.writeValueAsString(body), recruiterToken);
    }
    private HttpResponse<String> decide(JsonNode offer, long version, String action, String comment, String token) throws Exception {
        var body = new LinkedHashMap<String, Object>(); body.put("expectedVersion", version); body.put("action", action);
        if (comment != null) body.put("comment", comment);
        return call("POST", path(offer) + "/approval", json.writeValueAsString(body), token);
    }
    private HttpResponse<String> cancel(JsonNode offer, long version, String reason) throws Exception {
        return call("POST", path(offer) + "/cancel", json.writeValueAsString(Map.of("expectedVersion", version, "reason", reason)), recruiterToken);
    }
    private static String createPath(UUID application) { return "/api/v1/applications/" + application + "/offers"; }
    private static String path(JsonNode offer) { return "/api/v1/offers/" + offer.path("id").asText(); }
    private void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }
}
