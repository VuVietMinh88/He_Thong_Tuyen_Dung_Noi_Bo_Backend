package vn.ttcs.recruitment.offerdelivery.http;

import jakarta.servlet.http.HttpServletRequest;

/** Only these exact POSTs authenticate an offer token supplied in their JSON body. */
public final class OfferRoutes {
    public static final String LOOKUP = "/api/v1/public/offers/lookup";
    public static final String RESPOND = "/api/v1/public/offers/respond";

    private OfferRoutes() { }

    public static boolean isPublicPost(HttpServletRequest request) {
        return "POST".equals(request.getMethod())
                && (LOOKUP.equals(request.getServletPath()) || RESPOND.equals(request.getServletPath()));
    }
}
