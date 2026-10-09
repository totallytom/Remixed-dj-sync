package net.remixed.djsync.auth;

import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Verifies Supabase Auth access tokens — the same token the app already holds.
 *
 * <p>Supabase projects sign tokens one of two ways: the legacy shared HS256 secret, or
 * asymmetric signing keys published at {@code /auth/v1/.well-known/jwks.json}. If a
 * secret is configured it's used; otherwise keys are fetched (and cached) from the JWKS.
 * Expiry is checked, and the audience must be {@code authenticated} (signed-in users,
 * not the anonymous key).
 */
public final class SupabaseTokenVerifier implements TokenVerifier {

    private final JwtDecoder decoder;

    public SupabaseTokenVerifier(String supabaseUrl, String jwtSecret) {
        NimbusJwtDecoder d;
        if (jwtSecret != null && !jwtSecret.isBlank()) {
            var key = new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            d = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        } else {
            String jwks = supabaseUrl.replaceAll("/+$", "") + "/auth/v1/.well-known/jwks.json";
            d = NimbusJwtDecoder.withJwkSetUri(jwks)
                    .jwsAlgorithms(a -> { a.add(SignatureAlgorithm.ES256); a.add(SignatureAlgorithm.RS256); })
                    .build();
        }
        d.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),     // exp / nbf with a small clock skew allowance
                new JwtClaimValidator<java.util.List<String>>("aud",
                        aud -> aud != null && aud.contains("authenticated"))));
        this.decoder = d;
    }

    @Override
    public String verify(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            String sub = jwt.getSubject();
            if (sub == null || sub.isBlank()) throw new InvalidTokenException("token has no subject", null);
            return sub;
        } catch (JwtException e) {
            throw new InvalidTokenException("invalid token: " + e.getMessage(), e);
        }
    }
}
