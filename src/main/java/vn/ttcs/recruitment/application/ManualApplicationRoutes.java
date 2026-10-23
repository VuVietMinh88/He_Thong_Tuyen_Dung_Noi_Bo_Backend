package vn.ttcs.recruitment.application;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * Task 407 (story S5-08): the manual intake of internal staff. Only the exact POST of SUBMISSION, with a canonical
 * UUID, bypasses the Servlet multipart parser (ApplicationMultipartConfiguration): its 10 MiB CV is read by the
 * streaming decoder, like the public intake's and the referral's.
 */
public final class ManualApplicationRoutes {
    public static final String SUBMISSION = "/api/v1/requisitions/{id}/manual-applications";
    private static final Pattern SUBMISSION_PATH = Pattern.compile(
            "/api/v1/requisitions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                    + "/manual-applications");

    private ManualApplicationRoutes() { }

    public static boolean isSubmission(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && SUBMISSION_PATH.matcher(request.getServletPath()).matches();
    }
}
