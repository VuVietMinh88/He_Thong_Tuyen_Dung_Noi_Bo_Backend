package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import vn.ttcs.recruitment.security.AuthConfiguration;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    private final AuthConfiguration configuration = new AuthConfiguration();
    private SecretKey signingKey;
    private JwtEncoder encoder;
    private JwtDecoder decoder;
    private TokenService tokens;

    @BeforeEach
    void configureRealJwtSigningAndValidation() {
        byte[] secretBytes = new byte[32];
        new SecureRandom().nextBytes(secretBytes);
        signingKey = configuration.jwtSecretKey(Base64.getEncoder().encodeToString(secretBytes));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        encoder = configuration.jwtEncoder(signingKey);
        decoder = configuration.jwtDecoder(signingKey, clock);
        tokens = new TokenService(encoder, clock);
    }

    @Test
    void issuedAccessTokenHasValidSignatureAndOnlyRequiredClaims() {
        String accessToken = tokens.createAccessToken(USER_ID, SESSION_ID);

        Jwt verified = decoder.decode(accessToken);

        assertThat(verified.getHeaders().get("alg")).isEqualTo("HS256");
        assertThat(verified.getClaimAsString("iss")).isEqualTo("ttcs-backend");
        assertThat(verified.getAudience()).containsExactly("ttcs-api");
        assertThat(verified.getSubject()).isEqualTo(USER_ID.toString());
        assertThat(verified.getId()).isEqualTo(SESSION_ID.toString());
        assertThat(verified.getIssuedAt()).isEqualTo(NOW);
        assertThat(verified.getExpiresAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(verified.getClaims()).containsOnlyKeys("iss", "aud", "sub", "jti", "iat", "exp");
    }

    @Test
    void accessTokenIsAcceptedImmediatelyBeforeExpiration() {
        JwtDecoder beforeExpiry = configuration.jwtDecoder(signingKey,
                Clock.fixed(NOW.plusSeconds(899), ZoneOffset.UTC));

        assertThat(beforeExpiry.decode(tokens.createAccessToken(USER_ID, SESSION_ID)).getSubject())
                .isEqualTo(USER_ID.toString());
    }

    @ParameterizedTest
    @ValueSource(longs = {900, 901})
    void accessTokenIsRejectedAtOrAfterExpiration(long elapsedSeconds) {
        String accessToken = tokens.createAccessToken(USER_ID, SESSION_ID);
        JwtDecoder atExpiry = configuration.jwtDecoder(signingKey,
                Clock.fixed(NOW.plusSeconds(elapsedSeconds), ZoneOffset.UTC));

        assertThatThrownBy(() -> atExpiry.decode(accessToken)).isInstanceOf(JwtException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"exp", "iss", "aud"})
    void signedAccessTokenMustIncludeExpirationIssuerAndAudience(String claimName) {
        String accessToken = signClaims(claims -> claims.remove(claimName));

        assertThatThrownBy(() -> decoder.decode(accessToken)).isInstanceOf(JwtException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"iss", "aud"})
    void signedAccessTokenFromAnotherIssuerOrForAnotherAudienceIsRejected(String claimName) {
        String accessToken = signClaims(claims -> claims.put(claimName,
                claimName.equals("aud") ? List.of("another-api") : "another-issuer"));

        assertThatThrownBy(() -> decoder.decode(accessToken)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenThatIsNotYetValidIsRejected() {
        String accessToken = signClaims(claims -> claims.put("nbf", NOW.plusSeconds(1)));

        assertThatThrownBy(() -> decoder.decode(accessToken)).isInstanceOf(JwtException.class);
    }

    private String signClaims(Consumer<Map<String, Object>> customize) {
        // Use the real signing key so each case exercises claim validation, not signature rejection.
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("ttcs-backend")
                .audience(List.of("ttcs-api"))
                .subject(USER_ID.toString())
                .id(SESSION_ID.toString())
                .issuedAt(NOW)
                .expiresAt(NOW.plusSeconds(900))
                .claims(customize)
                .build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
