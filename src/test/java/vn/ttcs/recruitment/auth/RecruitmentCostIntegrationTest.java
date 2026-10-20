package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Task 573 (story S8-08): manually entered recruitment costs, validated, scoped and audited. */
class RecruitmentCostIntegrationTest extends ReportingTestSupport {
    static final String COSTS = "/api/v1/reports/costs";

    // Both fixture requisitions were finally approved; costs need that event, not only the status.
    @BeforeEach
    void approvedRequisitions() {
        workflowEvent(requisition, "APPROVED", "PENDING_APPROVAL", "APPROVED", NOW.minusSeconds(30 * 86_400L));
        workflowEvent(otherRequisition, "APPROVED", "PENDING_APPROVAL", "APPROVED", NOW.minusSeconds(30 * 86_400L));
    }

    Map<String, Object> body(UUID requisitionId, String type, Object amount, String date, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requisitionId", requisitionId);
        body.put("costType", type);
        body.put("amountVnd", amount);
        body.put("incurredOn", date);
        if (description != null) {
            body.put("description", description);
        }
        return body;
    }

    JsonNode created(Map<String, Object> body, String token) throws Exception {
        var response = call("POST", COSTS, json.writeValueAsString(body), token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
        return json.readTree(response.body());
    }

    @Test
    void hrRecordsACostAndEveryEntryKeepsWhoRecordedItAndWhen() throws Exception {
        JsonNode cost = created(body(requisition, "ADVERTISING", 5_000_000, "2026-10-10", "  Đăng tin TopCV  "), hrToken);
        assertThat(cost.path("requisitionId").asText()).isEqualTo(requisition.toString());
        assertThat(cost.path("costType").asText()).isEqualTo("ADVERTISING");
        assertThat(cost.path("amountVnd").asLong()).isEqualTo(5_000_000);
        assertThat(cost.path("incurredOn").asText()).isEqualTo("2026-10-10");
        assertThat(cost.path("description").asText()).isEqualTo("Đăng tin TopCV");
        assertThat(cost.path("recordedBy").asText()).isEqualTo(hr.toString());
        assertThat(cost.path("recordedByName").asText()).isEqualTo("HR Nội Bộ");
        assertThat(cost.path("recordedAt").asText()).isEqualTo("2026-10-15T03:00:00Z");
        assertThat(cost.path("voided").asBoolean()).isFalse();
        assertThat(cost.path("positionName").asText()).isEqualTo("Developer");
        JsonNode blank = created(body(requisition, "REFERRAL_BONUS", 2_000_000, "2026-10-15", "   "), hrToken);
        assertThat(blank.path("description").isNull()).isTrue();
        assertThat(count("recruitment_costs")).isEqualTo(2);

        // The database keeps every entry as it was entered.
        assertThatThrownBy(() -> jdbc.update("UPDATE recruitment_costs SET amount_vnd = 1 WHERE id = ?",
                UUID.fromString(cost.path("id").asText()))).hasMessageContaining("never changed");
    }

    @Test
    void invalidEntriesAreRefusedWithFieldErrors() throws Exception {
        var missing = call("POST", COSTS, "{}", hrToken);
        status(missing, 400, "VALIDATION_ERROR");
        assertThat(json.readTree(missing.body()).path("fieldErrors").propertyNames())
                .contains("requisitionId", "costType", "amountVnd", "incurredOn");
        status(call("POST", COSTS, json.writeValueAsString(body(requisition, "ADVERTISING", 0, "2026-10-10", null)), hrToken),
                400, "VALIDATION_ERROR");
        status(call("POST", COSTS, json.writeValueAsString(body(requisition, "ADVERTISING", 100_000_000_001L, "2026-10-10", null)),
                hrToken), 400, "VALIDATION_ERROR");
        status(call("POST", COSTS, json.writeValueAsString(body(requisition, "SALARY", 10, "2026-10-10", null)), hrToken),
                400, "VALIDATION_ERROR");
        status(call("POST", COSTS, json.writeValueAsString(body(requisition, "ADVERTISING", 10, "2026-10-16", null)), hrToken),
                400, "VALIDATION_ERROR");                                                  // tomorrow
        status(call("POST", COSTS, json.writeValueAsString(body(requisition, "ADVERTISING", 10, "1999-12-31", null)), hrToken),
                400, "VALIDATION_ERROR");
        status(call("POST", COSTS, json.writeValueAsString(body(requisition, "ADVERTISING", 10, "2026-10-10", "<b>tin</b>")),
                hrToken), 400, "VALIDATION_ERROR");
        status(call("POST", COSTS, json.writeValueAsString(body(requisition, "ADVERTISING", 10, "2026-10-10", "x".repeat(501))),
                hrToken), 400, "VALIDATION_ERROR");
        status(call("POST", COSTS, "{\"requisitionId\":\"" + requisition + "\",\"costType\":\"ADVERTISING\","
                + "\"amountVnd\":1.5,\"incurredOn\":\"2026-10-10\"}", hrToken), 400, "INVALID_JSON");
        status(call("POST", COSTS, "{\"requisitionId\":\"" + requisition + "\",\"costType\":\"ADVERTISING\","
                + "\"amountVnd\":\"1000\",\"incurredOn\":\"2026-10-10\"}", hrToken), 400, "INVALID_JSON");
        Map<String, Object> unknownField = body(requisition, "ADVERTISING", 10, "2026-10-10", null);
        unknownField.put("recordedBy", recruiter.toString());
        status(call("POST", COSTS, json.writeValueAsString(unknownField), hrToken), 400, "INVALID_JSON");
        status(call("POST", COSTS, json.writeValueAsString(body(UUID.randomUUID(), "ADVERTISING", 10, "2026-10-10", null)),
                hrToken), 404, "REQUISITION_NOT_FOUND");
        UUID draft = newRequisition(department, position, "DRAFT", null, 1);
        status(call("POST", COSTS, json.writeValueAsString(body(draft, "ADVERTISING", 10, "2026-10-10", null)), hrToken),
                409, "COST_REQUISITION_NOT_APPROVED");
        assertThat(count("recruitment_costs")).isZero();
    }

    @Test
    void costsNeedARequisitionThatWasFinallyApprovedWhateverItsStatusNow() throws Exception {
        UUID cancelledDraft = newRequisition(department, position, "CANCELLED", null, 1);
        workflowEvent(cancelledDraft, "CANCELLED", "DRAFT", "CANCELLED", NOW.minusSeconds(86_400));
        status(call("POST", COSTS, json.writeValueAsString(body(cancelledDraft, "ADVERTISING", 10, "2026-10-10", null)),
                hrToken), 409, "COST_REQUISITION_NOT_APPROVED");
        // Approved by a first level only: still PENDING_APPROVAL, no final approval.
        UUID halfway = newRequisition(department, position, "PENDING_APPROVAL", null, 1);
        workflowEvent(halfway, "APPROVED", "PENDING_APPROVAL", "PENDING_APPROVAL", NOW.minusSeconds(86_400));
        status(call("POST", COSTS, json.writeValueAsString(body(halfway, "ADVERTISING", 10, "2026-10-10", null)),
                hrToken), 409, "COST_REQUISITION_NOT_APPROVED");
        // An approved status set by hand, without the approval in the history, is not enough either.
        UUID unrecorded = newRequisition(department, position, "APPROVED", null, 1);
        status(call("POST", COSTS, json.writeValueAsString(body(unrecorded, "ADVERTISING", 10, "2026-10-10", null)),
                hrToken), 409, "COST_REQUISITION_NOT_APPROVED");
        assertThat(count("recruitment_costs")).isZero();
        // Cancelled after its approval: its costs were real.
        UUID cancelledLater = newRequisition(department, position, "CANCELLED", null, 1);
        workflowEvent(cancelledLater, "APPROVED", "PENDING_APPROVAL", "APPROVED", NOW.minusSeconds(3 * 86_400L));
        workflowEvent(cancelledLater, "CANCELLED", "APPROVED", "CANCELLED", NOW.minusSeconds(86_400));
        created(body(cancelledLater, "REFERRAL_BONUS", 1_000_000, "2026-10-10", null), hrToken);
        assertThat(count("recruitment_costs")).isEqualTo(1);
    }

    @Test
    void theLedgerRefusesDeleteAndKeepsItsRequisition() throws Exception {
        UUID id = UUID.fromString(created(body(requisition, "ADVERTISING", 10, "2026-10-10", null), hrToken)
                .path("id").asText());
        assertThatThrownBy(() -> jdbc.update("DELETE FROM recruitment_costs WHERE id = ?", id))
                .hasMessageContaining("append-only");
        assertThat(jdbc.queryForObject("""
                SELECT confdeltype FROM pg_constraint WHERE contype = 'f'
                  AND conrelid = 'recruitment_costs'::regclass AND confrelid = 'recruitment_requisitions'::regclass
                """, String.class)).isEqualTo("r");                                  // ON DELETE RESTRICT
        assertThat(count("recruitment_costs")).isEqualTo(1);
    }

    @Test
    void aWriteThatWaitsTooLongForItsLocksAnswers503CostBusy() throws Exception {
        String valid = json.writeValueAsString(body(requisition, "ADVERTISING", 10, "2026-10-10", null));
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var statement = holder.prepareStatement("SELECT id FROM recruitment_requisitions WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, requisition);
                statement.executeQuery();
            }
            // The write waits for the requisition row past its 2 s lock timeout.
            status(call("POST", COSTS, valid, hrToken), 503, "COST_BUSY");
            holder.rollback();
        }
        assertThat(count("recruitment_costs")).isZero();
        created(body(requisition, "ADVERTISING", 10, "2026-10-10", null), hrToken);
    }

    @Test
    void aScopedReportWriterRecordsAndVoidsOnlyInsideItsScope() throws Exception {
        String foreign = created(body(otherRequisition, "ADVERTISING", 7, "2026-10-10", null), hrToken).path("id").asText();
        grant("RECRUITER", "REPORTS_WRITE_SCOPED");
        JsonNode own = created(body(requisition, "ADVERTISING", 5, "2026-10-10", null), recruiterToken);
        assertThat(own.path("recordedBy").asText()).isEqualTo(recruiter.toString());
        status(call("POST", COSTS, json.writeValueAsString(body(otherRequisition, "ADVERTISING", 5, "2026-10-10", null)),
                recruiterToken), 403, "FORBIDDEN");
        status(call("POST", COSTS + "/" + foreign + "/void", json.writeValueAsString(Map.of("reason", "Sai")),
                recruiterToken), 403, "FORBIDDEN");
        ok(call("POST", COSTS + "/" + own.path("id").asText() + "/void", json.writeValueAsString(Map.of("reason", "Nhập thử")),
                recruiterToken));
        assertThat(jdbc.queryForObject("SELECT voided_at IS NULL FROM recruitment_costs WHERE id = ?", Boolean.class,
                UUID.fromString(foreign))).isTrue();
        assertThat(count("recruitment_costs")).isEqualTo(2);
    }

    @Test
    void onlyReportWritersRecordAndARevokedGrantStopsTheNextWrite() throws Exception {
        String valid = json.writeValueAsString(body(requisition, "ADVERTISING", 10, "2026-10-10", null));
        status(call("POST", COSTS, valid, recruiterToken), 403, "FORBIDDEN");
        status(call("POST", COSTS, valid, managerToken), 403, "FORBIDDEN");
        status(call("POST", COSTS, valid, approverToken), 403, "FORBIDDEN");
        assertThat(call("POST", COSTS, valid, null).statusCode()).isEqualTo(401);
        assertThat(count("recruitment_costs")).isZero();
        created(body(requisition, "ADVERTISING", 10, "2026-10-10", null), hrToken);
        revoke("HR_MANAGER", "REPORTS_WRITE_ALL");
        status(call("POST", COSTS, valid, hrToken), 403, "FORBIDDEN");
        assertThat(count("recruitment_costs")).isEqualTo(1);
    }

    @Test
    void theListIsScopedFilteredAndTotalsOnlyEntriesThatAreNotVoided() throws Exception {
        created(body(requisition, "ADVERTISING", 3_000_000, "2026-09-01", null), hrToken);
        JsonNode referral = created(body(requisition, "REFERRAL_BONUS", 2_000_000, "2026-10-01", null), hrToken);
        created(body(otherRequisition, "ADVERTISING", 7_000_000, "2026-10-05", null), hrToken);

        JsonNode all = okJson(COSTS, hrToken);
        assertThat(all.path("totalElements").asLong()).isEqualTo(3);
        assertThat(all.path("activeEntries").asLong()).isEqualTo(3);
        assertThat(all.path("activeTotalVnd").asLong()).isEqualTo(12_000_000);
        assertThat(all.path("items").path(0).path("incurredOn").asText()).isEqualTo("2026-10-05");   // newest first
        assertThat(okJson(COSTS + "?requisitionId=" + requisition, hrToken).path("activeTotalVnd").asLong())
                .isEqualTo(5_000_000);
        assertThat(okJson(COSTS + "?costType=ADVERTISING", hrToken).path("activeTotalVnd").asLong()).isEqualTo(10_000_000);
        assertThat(okJson(COSTS + "?from=2026-10-01&to=2026-10-31", hrToken).path("activeTotalVnd").asLong())
                .isEqualTo(9_000_000);
        status(get(COSTS + "?costType=SALARY", hrToken), 400, "VALIDATION_ERROR");

        JsonNode recruiterView = okJson(COSTS, recruiterToken);
        assertThat(recruiterView.path("scope").asText()).isEqualTo("SCOPED");
        assertThat(recruiterView.path("activeTotalVnd").asLong()).isEqualTo(5_000_000);
        assertThat(okJson(COSTS, outsiderToken).path("activeTotalVnd").asLong()).isEqualTo(7_000_000);
        status(get(COSTS + "?requisitionId=" + otherRequisition, recruiterToken), 403, "FORBIDDEN");
        status(get(COSTS, interviewerToken), 403, "FORBIDDEN");

        var voided = call("POST", COSTS + "/" + referral.path("id").asText() + "/void",
                json.writeValueAsString(Map.of("reason", "Nhập trùng")), hrToken);
        JsonNode voidedCost = ok(voided);
        assertThat(voidedCost.path("voided").asBoolean()).isTrue();
        assertThat(voidedCost.path("voidedBy").asText()).isEqualTo(hr.toString());
        assertThat(voidedCost.path("voidReason").asText()).isEqualTo("Nhập trùng");
        JsonNode afterVoid = okJson(COSTS, hrToken);
        assertThat(afterVoid.path("totalElements").asLong()).isEqualTo(2);
        assertThat(afterVoid.path("activeTotalVnd").asLong()).isEqualTo(10_000_000);
        JsonNode withVoided = okJson(COSTS + "?includeVoided=true", hrToken);
        assertThat(withVoided.path("totalElements").asLong()).isEqualTo(3);
        assertThat(withVoided.path("activeTotalVnd").asLong()).isEqualTo(10_000_000);
    }

    @Test
    void anEntryIsVoidedOnceWithAReasonByAReportWriter() throws Exception {
        String id = created(body(requisition, "ADVERTISING", 10, "2026-10-10", null), hrToken).path("id").asText();
        String path = COSTS + "/" + id + "/void";
        status(call("POST", path, "{}", hrToken), 400, "VALIDATION_ERROR");
        status(call("POST", path, json.writeValueAsString(Map.of("reason", "  ")), hrToken), 400, "VALIDATION_ERROR");
        status(call("POST", path, json.writeValueAsString(Map.of("reason", "Sai")), recruiterToken), 403, "FORBIDDEN");
        status(call("POST", COSTS + "/" + UUID.randomUUID() + "/void", json.writeValueAsString(Map.of("reason", "Sai")),
                hrToken), 404, "COST_NOT_FOUND");
        ok(call("POST", path, json.writeValueAsString(Map.of("reason", "Sai số tiền")), token(applications.account(
                "Admin", "admin2@example.test", vn.ttcs.recruitment.account.Role.ADMIN))));
        status(call("POST", path, json.writeValueAsString(Map.of("reason", "Lần nữa")), hrToken), 409, "COST_ALREADY_VOIDED");
        assertThat(jdbc.queryForObject("SELECT void_reason FROM recruitment_costs WHERE id = ?", String.class,
                UUID.fromString(id))).isEqualTo("Sai số tiền");
        assertThatThrownBy(() -> jdbc.update("UPDATE recruitment_costs SET void_reason = 'Khác' WHERE id = ?",
                UUID.fromString(id))).hasMessageContaining("final");
        assertThat(List.of(okJson(COSTS + "?includeVoided=true", hrToken).path("items").path(0).path("voidedByName").asText()))
                .containsExactly("Admin");
    }
}
