package vn.ttcs.recruitment.invitation.http;

import jakarta.servlet.http.HttpServletRequest;

/** Only these exact POSTs authenticate an invitation token supplied in their JSON body. */
public final class InterviewInvitationRoutes {
    public static final String LOOKUP = "/api/v1/public/interview-invitations/lookup";
    public static final String RESPOND = "/api/v1/public/interview-invitations/respond";

    private InterviewInvitationRoutes() { }

    public static boolean isPublicPost(HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                && (LOOKUP.equals(request.getServletPath()) || RESPOND.equals(request.getServletPath()));
    }
}
