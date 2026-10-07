package vn.ttcs.recruitment.interviewquestion;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Jira 221: read, create and edit one interview question. Jira 223: search and filter the question bank.
@RestController
@RequestMapping("/api/v1/interview-questions")
public class InterviewQuestionController {
    private final InterviewQuestionService service;

    public InterviewQuestionController(InterviewQuestionService service) {
        this.service = service;
    }

    // Every filter is optional. A UUID, difficulty, active or page value Spring cannot convert is answered by
    // ApiExceptionHandler with 400 VALIDATION_ERROR before the service runs.
    @GetMapping
    public ResponseEntity<InterviewQuestionPage> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) UUID positionId,
            @RequestParam(required = false) UUID criterionId,
            @RequestParam(required = false) InterviewQuestionDifficulty difficulty,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(jwt, q, positionId, criterionId, difficulty, active, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<InterviewQuestionView> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(jwt, id));
    }

    @PostMapping
    public ResponseEntity<InterviewQuestionView> create(@AuthenticationPrincipal Jwt jwt,
                                                        @Valid @RequestBody InterviewQuestionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.create(jwt, request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<InterviewQuestionView> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                        @Valid @RequestBody InterviewQuestionRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.update(jwt, id, request));
    }
}
