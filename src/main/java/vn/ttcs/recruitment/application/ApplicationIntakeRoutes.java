package vn.ttcs.recruitment.application;

import jakarta.servlet.http.HttpServletRequest;

public final class ApplicationIntakeRoutes {
    public static final String SUBMISSION = "/api/v1/public/job-postings/{id}/applications";
    public static final String SUBMISSION_MATCHER = "/api/v1/public/job-postings/*/applications";
    public static final String SOURCES = "/api/v1/public/application-sources";
    private static final String PREFIX = "/api/v1/public/job-postings/";
    private static final String SUFFIX = "/applications";

    private ApplicationIntakeRoutes() { }

    public static boolean isSubmission(HttpServletRequest request) {
        String path = request.getServletPath();
        if (!"POST".equals(request.getMethod()) || path.length() <= PREFIX.length() + SUFFIX.length()
                || !path.startsWith(PREFIX) || !path.endsWith(SUFFIX)) return false;
        String id = path.substring(PREFIX.length(), path.length() - SUFFIX.length());
        return !id.isEmpty() && id.indexOf('/') < 0;
    }

    static String postingId(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.substring(PREFIX.length(), path.length() - SUFFIX.length());
    }
}
