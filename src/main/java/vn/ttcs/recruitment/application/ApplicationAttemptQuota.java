package vn.ttcs.recruitment.application;

import jakarta.servlet.http.HttpServletRequest;

// Integration point for the durable IP quota. Implementations must inspect headers/remote address only,
// commit each attempt independently, and throw ApiException on refusal. Never parse the body here.
public interface ApplicationAttemptQuota {
    void checkAndRecord(HttpServletRequest request);
}
