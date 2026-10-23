package vn.ttcs.recruitment.offerdelivery;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.common.ApiException;
import java.util.Set;
import java.util.UUID;

@RestController
public class OfferDeliveryController {
    private final OfferDeliveryService service;

    public OfferDeliveryController(OfferDeliveryService service) {
        this.service = service;
    }
    @PostMapping("/api/v1/offers/{id}/send")
    public ResponseEntity<OfferDeliveryService.SendResult> send(@AuthenticationPrincipal Jwt jwt,
        @PathVariable UUID id, @RequestBody JsonNode body) {
        fields(body, Set.of("expectedVersion"));
        JsonNode version = body.path("expectedVersion");
        if (!version.isIntegralNumber() || !version.canConvertToLong() || version.asLong() < 0) throw invalidJson();
        return ok(service.send(jwt, id, version.asLong()));
    }
    @PostMapping("/api/v1/public/offers/lookup")
    public ResponseEntity<OfferDeliveryService.Details> lookup(@RequestBody JsonNode body) {
        fields(body, Set.of("token"));
        return ok(service.lookup(text(body, "token")));
    }
    @PostMapping("/api/v1/public/offers/respond")
    public ResponseEntity<OfferDeliveryService.Response> respond(@RequestBody JsonNode body) {
        fields(body, Set.of("token", "action", "note"));
        return ok(service.respond(text(body, "token"), text(body, "action"), text(body, "note")));
    }
    @GetMapping(value = "/api/v1/public/offers/reply", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> page() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer")
        .header("Content-Security-Policy", "default-src 'none'; script-src 'self'; connect-src 'self'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
        .body(OfferReplyPage.html());
    }
    @GetMapping(value = "/api/v1/public/offers/reply.js", produces = "text/javascript;charset=UTF-8")
    public ResponseEntity<String> script() {
        return ok(OfferReplyPage.script());
    }

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
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Dữ liệu offer không đúng định dạng JSON.");
    }
}
