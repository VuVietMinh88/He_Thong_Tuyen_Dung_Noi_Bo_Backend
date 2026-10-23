package vn.ttcs.recruitment.application;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Task 407 (story S5-08): the manual intake. No consumes: the service answers a wrong Content-Type with the house's
// 415 body, and the Servlet multipart parser never sees this route (ApplicationMultipartConfiguration).
@RestController
public class ManualApplicationController {
    private final ManualApplicationService service;

    public ManualApplicationController(ManualApplicationService service) {
        this.service = service;
    }

    @PostMapping(ManualApplicationRoutes.SUBMISSION)
    public ResponseEntity<ManualApplicationView> submit(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                                        HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.submit(jwt, id, request));
    }
}
