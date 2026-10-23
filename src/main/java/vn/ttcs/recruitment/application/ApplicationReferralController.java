package vn.ttcs.recruitment.application;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Task 351 (story S4-07): the staff referral. No consumes: the service answers a wrong Content-Type with the house's
// 415 body, and the Servlet multipart parser never sees this route (ApplicationMultipartConfiguration).
// Task 352: the referrer's own list, a bare array (what the frontend reads), never cached. No query parameter is read.
@RestController
public class ApplicationReferralController {
    private final ApplicationReferralService service;
    private final ApplicationReferralStatusService statuses;

    public ApplicationReferralController(ApplicationReferralService service, ApplicationReferralStatusService statuses) {
        this.service = service;
        this.statuses = statuses;
    }

    @PostMapping(ApplicationReferralRoutes.SUBMISSION)
    public ResponseEntity<ApplicationReferralView> submit(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.submit(jwt, request));
    }

    @GetMapping(ApplicationReferralRoutes.MY)
    public ResponseEntity<List<ApplicationReferralView>> myReferrals(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(statuses.myReferrals(jwt));
    }
}
