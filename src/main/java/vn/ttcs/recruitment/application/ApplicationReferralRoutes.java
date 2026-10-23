package vn.ttcs.recruitment.application;

import jakarta.servlet.http.HttpServletRequest;

// Task 351 (story S4-07): the referral API of logged-in staff. Only the exact POST of SUBMISSION bypasses the Servlet
// multipart parser (ApplicationMultipartConfiguration): its 10 MiB CV is read by the streaming decoder, like the public
// intake's. MY is the referrer's own list (task 352).
public final class ApplicationReferralRoutes {
    public static final String SUBMISSION = "/api/v1/referrals";
    public static final String MY = "/api/v1/referrals/my-referrals";

    private ApplicationReferralRoutes() { }

    public static boolean isSubmission(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && SUBMISSION.equals(request.getServletPath());
    }
}
