package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 394 (story S5-05): saved filters belong strictly to the JWT subject. */
class CandidateSavedFilterIntegrationTest extends CandidateSearchTestSupport {
    static final String FILTERS = "/api/v1/candidate-search/saved-filters";

    private Map<String, Object> criteria() {
        Map<String, Object> criteria = new LinkedHashMap<>();
        criteria.put("requisitionId", requisition.toString());
        criteria.put("stages", List.of("NEW", "SCREENING"));
        criteria.put("sourceIds", List.of(linkedIn.toString()));
        criteria.put("tagMatch", "ALL");
        criteria.put("submittedFrom", "2026-10-01");
        criteria.put("minRating", 2);
        criteria.put("keyword", "binh");
        criteria.put("coverLetterKeyword", "java");
        return criteria;
    }

    private String body(String name, Object criteria, Long version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("criteria", criteria);
        if (version != null) body.put("expectedVersion", version);
        return json.writeValueAsString(body);
    }

    private JsonNode created(String token, String name) throws Exception {
        var response = call("POST", FILTERS, body(name, criteria(), null), token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
        return json.readTree(response.body());
    }

    @Test
    void ownerCreatesReadsUpdatesAndDeletesItsFilter() throws Exception {
        JsonNode filter = created(recruiterToken, "  Java tháng 10 ");
        UUID id = UUID.fromString(filter.path("id").asText());
        assertThat(filter.path("name").asText()).isEqualTo("Java tháng 10");
        assertThat(filter.path("version").asLong()).isZero();
        assertThat(filter.path("criteria").path("stages").toString()).isEqualTo("[\"NEW\",\"SCREENING\"]");
        assertThat(filter.path("criteria").path("submittedFrom").asText()).isEqualTo("2026-10-01");
        assertThat(filter.path("criteria").path("tagMatch").asText()).isEqualTo("ALL");
        assertThat(filter.path("criteria").path("coverLetterKeyword").asText()).isEqualTo("java");
        assertThat(filter.has("ownerId")).isFalse();
        assertThat(jdbc.queryForObject("SELECT owner_id FROM candidate_saved_filters WHERE id = ?", UUID.class, id))
                .isEqualTo(recruiter);

        assertThat(ok(call("GET", FILTERS, null, recruiterToken)).size()).isEqualTo(1);
        assertThat(ok(call("GET", FILTERS + "/" + id, null, recruiterToken)).path("name").asText()).isEqualTo("Java tháng 10");

        Map<String, Object> changed = criteria();
        changed.put("keyword", null);
        changed.put("maxRating", 5);
        JsonNode updated = ok(call("PUT", FILTERS + "/" + id, body("Java mới", changed, 0L), recruiterToken));
        assertThat(updated.path("version").asLong()).isEqualTo(1);
        assertThat(updated.path("criteria").path("keyword").isNull()).isTrue();
        assertThat(updated.path("criteria").path("maxRating").asInt()).isEqualTo(5);
        assertError(call("PUT", FILTERS + "/" + id, body("Java cũ", changed, 0L), recruiterToken), 409,
                "SAVED_FILTER_CHANGED");

        var deleted = call("DELETE", FILTERS + "/" + id, null, recruiterToken);
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertError(call("DELETE", FILTERS + "/" + id, null, recruiterToken), 404, "SAVED_FILTER_NOT_FOUND");
        assertThat(count("candidate_saved_filters")).isZero();
    }

    @Test
    void anotherAccountNeverReadsChangesOrDeletesAFilterEvenWithItsId() throws Exception {
        UUID id = UUID.fromString(created(recruiterToken, "Của recruiter").path("id").asText());
        for (String token : List.of(token(outsider), hrToken)) {
            assertError(call("GET", FILTERS + "/" + id, null, token), 404, "SAVED_FILTER_NOT_FOUND");
            assertError(call("PUT", FILTERS + "/" + id, body("Bị đổi", criteria(), 0L), token), 404,
                    "SAVED_FILTER_NOT_FOUND");
            assertError(call("DELETE", FILTERS + "/" + id, null, token), 404, "SAVED_FILTER_NOT_FOUND");
            assertThat(ok(call("GET", FILTERS, null, token)).size()).isZero();
        }
        assertError(call("GET", FILTERS + "/" + UUID.randomUUID(), null, recruiterToken), 404, "SAVED_FILTER_NOT_FOUND");
        // A client-sent owner is refused, never used.
        Map<String, Object> withOwner = new LinkedHashMap<>(Map.of("name", "Giả mạo", "criteria", criteria(),
                "ownerId", outsider.toString()));
        assertError(call("POST", FILTERS, json.writeValueAsString(withOwner), recruiterToken), 400, "INVALID_JSON");
        // The same name is free for another owner.
        created(token(outsider), "Của recruiter");
        JsonNode untouched = ok(call("GET", FILTERS + "/" + id, null, recruiterToken));
        assertThat(untouched.path("version").asLong()).isZero();
        assertThat(untouched.path("name").asText()).isEqualTo("Của recruiter");
        assertThat(count("candidate_saved_filters")).isEqualTo(2);
    }

    @Test
    void invalidRequestsAreRejectedWithoutChanges() throws Exception {
        created(recruiterToken, "Java");
        assertError(call("POST", FILTERS, body("JAVA", criteria(), null), recruiterToken), 409, "SAVED_FILTER_NAME_TAKEN");
        Map<String, Object> badCriteria = criteria();
        badCriteria.put("minRating", 7);
        var invalid = call("POST", FILTERS, body("Khác", badCriteria, null), recruiterToken);
        assertError(invalid, 400, "VALIDATION_ERROR");
        assertThat(json.readTree(invalid.body()).path("fieldErrors").has("criteria.minRating")).isTrue();
        Map<String, Object> unknownField = criteria();
        unknownField.put("ownerId", recruiter.toString());
        assertError(call("POST", FILTERS, body("Khác", unknownField, null), recruiterToken), 400, "INVALID_JSON");
        Map<String, Object> badStage = criteria();
        badStage.put("stages", List.of("ARCHIVED"));
        assertError(call("POST", FILTERS, body("Khác", badStage, null), recruiterToken), 400, "INVALID_JSON");
        for (String name : List.of("   ", "<b>Java</b>", "x".repeat(81))) {
            assertError(call("POST", FILTERS, body(name, criteria(), null), recruiterToken), 400, "VALIDATION_ERROR");
        }
        assertError(call("POST", FILTERS, body("Khác", null, null), recruiterToken), 400, "VALIDATION_ERROR");
        UUID id = jdbc.queryForObject("SELECT id FROM candidate_saved_filters", UUID.class);
        var missingVersion = call("PUT", FILTERS + "/" + id, body("Khác", criteria(), null), recruiterToken);
        assertError(missingVersion, 400, "VALIDATION_ERROR");
        assertThat(json.readTree(missingVersion.body()).path("fieldErrors").has("expectedVersion")).isTrue();
        assertThat(count("candidate_saved_filters")).isEqualTo(1);
    }

    @Test
    void repeatedListValuesAreKeptOnceAndInvisibleNameEdgesDoNotMakeANewName() throws Exception {
        Map<String, Object> repeated = criteria();
        repeated.put("sourceIds", java.util.Collections.nCopies(400, linkedIn.toString()));
        repeated.put("stages", java.util.Collections.nCopies(400, "NEW"));
        JsonNode filter = created(recruiterToken, "Lặp lại");
        assertThat(filter.path("criteria").path("sourceIds").size()).isEqualTo(1);
        var saved = call("POST", FILTERS, body("Nhiều giá trị lặp", repeated, null), recruiterToken);
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(201);
        JsonNode stored = json.readTree(saved.body()).path("criteria");
        assertThat(stored.path("sourceIds").toString()).isEqualTo("[\"" + linkedIn + "\"]");
        assertThat(stored.path("stages").toString()).isEqualTo("[\"NEW\"]");

        String nbsp = String.valueOf((char) 0x00A0);
        String zeroWidth = String.valueOf((char) 0x200B);
        created(recruiterToken, "Java");
        assertError(call("POST", FILTERS, body("JAVA" + nbsp + zeroWidth, criteria(), null), recruiterToken), 409,
                "SAVED_FILTER_NAME_TAKEN");
        JsonNode spaced = created(recruiterToken, nbsp + "Java" + nbsp + nbsp + "mới" + nbsp);
        assertThat(spaced.path("name").asText()).isEqualTo("Java mới");
        assertError(call("POST", FILTERS, body(nbsp + nbsp + zeroWidth, criteria(), null), recruiterToken), 400,
                "VALIDATION_ERROR");
        assertThat(count("candidate_saved_filters")).isEqualTo(4);
    }

    @Test
    void eachOwnerKeepsAtMostFiftyFilters() throws Exception {
        Timestamp at = Timestamp.from(ApplicationTestSupport.NOW);
        for (int index = 0; index < 50; index++) {
            jdbc.update("INSERT INTO candidate_saved_filters(id,owner_id,name,normalized_name,criteria,created_at,updated_at)"
                    + " VALUES (?,?,?,?,'{}'::jsonb,?,?)", UUID.randomUUID(), recruiter, "Bộ " + index, "bộ " + index, at, at);
        }
        assertError(call("POST", FILTERS, body("Thứ 51", criteria(), null), recruiterToken), 409, "SAVED_FILTER_LIMIT");
        created(token(outsider), "Thứ 51");
    }

    @Test
    void callersWithoutCandidateReadOrALiveSessionAreRefused() throws Exception {
        UUID noRole = applications.account("No role", "none@example.test");
        assertError(call("GET", FILTERS, null, token(noRole)), 403, "FORBIDDEN");
        assertError(call("POST", FILTERS, body("Java", criteria(), null), token(noRole)), 403, "FORBIDDEN");
        assertThat(call("GET", FILTERS, null, null).statusCode()).isEqualTo(401);
        jdbc.update("UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?", Timestamp.from(ApplicationTestSupport.NOW),
                recruiter);
        HttpResponse<String> revoked = call("POST", FILTERS, body("Java", criteria(), null), recruiterToken);
        assertThat(revoked.statusCode()).isEqualTo(401);
        assertThat(count("candidate_saved_filters")).isZero();
    }
}
