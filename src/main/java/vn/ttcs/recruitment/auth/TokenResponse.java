package vn.ttcs.recruitment.auth;

import java.time.Instant;

public record TokenResponse(String accessToken, String refreshToken, String tokenType,
                            long expiresIn, Instant refreshExpiresAt, CurrentUserResponse user) {

    @Override
    public String toString() {
        return "TokenResponse[tokens redacted]";
    }
}
