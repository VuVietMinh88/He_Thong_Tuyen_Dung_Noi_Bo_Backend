package vn.ttcs.recruitment.invitation;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vn.ttcs.recruitment.invitation.http.InterviewInvitationRoutes;
import vn.ttcs.recruitment.common.ApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import java.util.Set;
import java.util.UUID;

@RestController
public class InterviewInvitationController {
    private final InterviewInvitationService service;
    public InterviewInvitationController(InterviewInvitationService service) { this.service = service; }
    @PostMapping(InterviewInvitationRoutes.LOOKUP)
    public ResponseEntity<InterviewInvitationService.Details> lookup(@RequestBody JsonNode body) {
        fields(body, Set.of("token"));
        return ok(service.lookup(text(body, "token")));
    }
    @GetMapping("/api/v1/interviews/{id}/invitation")
    public ResponseEntity<InterviewInvitationService.Status> status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ok(service.status(jwt, id));
    }
    @GetMapping(value="/api/v1/public/interview-invitations/reply", produces="text/html;charset=UTF-8")
    public ResponseEntity<String> page() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", "default-src 'none'; script-src 'self'; connect-src 'self'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
                .body(InterviewReplyPage.html());
    }
    @GetMapping(value="/api/v1/public/interview-invitations/reply.js", produces="text/javascript;charset=UTF-8")
    public ResponseEntity<String> script() { return ok(InterviewReplyPage.script()); }
    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").body(body);
    }
    private static void fields(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject() || !allowed.containsAll(body.propertyNames())) throw invalidJson();
    }
    private static String text(JsonNode body, String name) {
        JsonNode value = body.path(name);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual()) throw invalidJson();
        return value.asText();
    }
    private static ApiException invalidJson() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Dữ liệu lời mời không đúng định dạng JSON.");
    }
}
