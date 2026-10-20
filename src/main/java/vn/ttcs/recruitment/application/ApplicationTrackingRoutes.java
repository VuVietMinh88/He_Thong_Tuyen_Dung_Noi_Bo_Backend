package vn.ttcs.recruitment.application;

import jakarta.servlet.http.HttpServletRequest;

// Task 339 (story S4-05): the public tracking API of a candidate without an account. Only the exact POST of these paths
// is public; SecurityConfiguration ignores a stale bearer on them and puts ApplicationTrackingRequestFilter in front.
public final class ApplicationTrackingRoutes {
    public static final String LOOKUP = "/api/v1/public/applications/lookup";

    private ApplicationTrackingRoutes() { }

    public static boolean isPublicPost(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && LOOKUP.equals(request.getServletPath());
    }
}
