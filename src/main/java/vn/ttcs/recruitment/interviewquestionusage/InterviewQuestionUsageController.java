package vn.ttcs.recruitment.interviewquestionusage;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

@RestController
public class InterviewQuestionUsageController {
    private final InterviewQuestionUsageService service;
    public InterviewQuestionUsageController(InterviewQuestionUsageService service) { this.service = service; }

    @GetMapping("/api/v1/interviews/{id}/questions")
    public ResponseEntity<InterviewQuestionUsageView.Page<InterviewQuestionUsageView>> questions(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id, @RequestParam(required = false) UUID criterionId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ok(service.questions(jwt, id, criterionId, page, size));
    }
    @GetMapping("/api/v1/interviews/{id}/question-history")
    public ResponseEntity<InterviewQuestionUsageView.Page<InterviewQuestionUsageView.History>> history(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id, @RequestParam(required = false) UUID criterionId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ok(service.history(jwt, id, criterionId, page, size));
    }
    @PostMapping("/api/v1/interviews/{id}/questions/{questionId}/asked")
    public ResponseEntity<InterviewQuestionUsageView> mark(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable UUID questionId, @RequestBody JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 1 || !body.has("expectedVersion")
                || !body.get("expectedVersion").isIntegralNumber() || !body.get("expectedVersion").canConvertToLong()
                || body.get("expectedVersion").longValue() < 0) {
            throw InterviewQuestionUsageService.invalid("expectedVersion", "Chỉ gửi expectedVersion là số nguyên không âm.");
        }
        return ok(service.mark(jwt, id, questionId, body.get("expectedVersion").longValue()));
    }
    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
