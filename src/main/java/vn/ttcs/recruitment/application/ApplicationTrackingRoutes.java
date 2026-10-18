package vn.ttcs.recruitment.application;

import jakarta.servlet.http.HttpServletRequest;

// Task 339 (story S4-05): the public tracking API of a candidate without an account. Only the exact POST of these paths
// is public; SecurityConfiguration ignores a stale bearer on them and puts ApplicationTrackingRequestFilter in front.
// Task 345 (story S4-06) adds the withdrawal, with the same guard, gate and quota as the lookup.
public final class ApplicationTrackingRoutes {
    public static final String LOOKUP = "/api/v1/public/applications/lookup";
    public static final String WITHDRAW = "/api/v1/public/applications/withdraw";

    private ApplicationTrackingRoutes() { }

    public static boolean isPublicPost(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) return false;
        String path = request.getServletPath();
        return LOOKUP.equals(path) || WITHDRAW.equals(path);
    }
}
