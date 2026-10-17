package vn.ttcs.recruitment.candidatemerge;

import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import static vn.ttcs.recruitment.candidatemerge.CandidateMergeApi.*;

@RestController
@RequestMapping("/api/v1/candidate-merges")
public class CandidateMergeController {
    private final CandidateMergeService service;
    public CandidateMergeController(CandidateMergeService service) { this.service=service; }
    @PostMapping("/preview")
    public ResponseEntity<Preview> preview(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody PreviewRequest request) {
        return response(service.preview(jwt,request));
    }
    @PostMapping
    public ResponseEntity<Result> merge(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody MergeRequest request) {
        return response(service.merge(jwt,request));
    }
    @GetMapping("/{id}")
    public ResponseEntity<Result> get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { return response(service.get(jwt,id)); }
    private static <T> ResponseEntity<T> response(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
