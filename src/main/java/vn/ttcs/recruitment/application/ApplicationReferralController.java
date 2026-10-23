package vn.ttcs.recruitment.application;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

// Task 351 (story S4-07): the staff referral. No consumes: the service answers a wrong Content-Type with the house's
// 415 body, and the Servlet multipart parser never sees this route (ApplicationMultipartConfiguration).
@RestController
public class ApplicationReferralController {
    private final ApplicationReferralService service;

    public ApplicationReferralController(ApplicationReferralService service) { this.service = service; }

    @PostMapping(ApplicationReferralRoutes.SUBMISSION)
    public ResponseEntity<ApplicationReferralView> submit(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.submit(jwt, request));
    }
}
