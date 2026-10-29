package com.agentforge.security.auth;

import com.agentforge.security.config.AuthProperties;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtService 单测：签发/解析、过期、篡改、吊销、密钥长度校验。
 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789-0123456789";

    private AuthProperties properties;
    private TokenBlacklist tokenBlacklist;
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        properties = new AuthProperties();
        properties.setJwtSecret(SECRET);
        properties.setAccessTokenTtl(Duration.ofHours(2));
        properties.setRefreshTokenTtl(Duration.ofDays(7));
        tokenBlacklist = new TokenBlacklist();
        jwtService = new JwtService(properties, tokenBlacklist);
    }

    @Test
    void shouldCreateAndParseAccessToken() {
        String token = jwtService.createAccessToken(42L, "alice", 1L,
                Set.of("USER"), Set.of("agent:read", "rag:read"));

        JwtService.AccessClaims claims = jwtService.parseAccessToken(token);

        assertThat(claims.userId()).isEqualTo(42L);
        assertThat(claims.username()).isEqualTo("alice");
        assertThat(claims.tenantId()).isEqualTo(1L);
        assertThat(claims.roles()).containsExactly("USER");
        assertThat(claims.permissions()).containsExactlyInAnyOrder("agent:read", "rag:read");
        assertThat(claims.jti()).isNotBlank();
        assertThat(claims.expiresAt()).isAfter(java.time.Instant.now());
        assertThat(jwtService.accessTokenTtlSeconds()).isEqualTo(7200);
        assertThat(jwtService.refreshTokenTtlSeconds()).isEqualTo(604800);
    }

    @Test
    void shouldRejectExpiredToken() {
        properties.setAccessTokenTtl(Duration.ofSeconds(-10));
        JwtService shortLived = new JwtService(properties, new TokenBlacklist());
        String token = shortLived.createAccessToken(7L, "bob", 1L, Set.of("USER"), Set.of());

        assertThatThrownBy(() -> shortLived.parseAccessToken(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void shouldRejectTamperedToken() {
        String token = jwtService.createAccessToken(1L, "carol", 1L, Set.of("USER"), Set.of());

        assertThatThrownBy(() -> jwtService.parseAccessToken(token + "x"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void shouldRejectRevokedToken() {
        String token = jwtService.createAccessToken(9L, "dave", 1L, Set.of("USER"), Set.of());
        JwtService.AccessClaims claims = jwtService.parseAccessToken(token);
        tokenBlacklist.blacklist(claims.jti(), claims.expiresAt());

        assertThatThrownBy(() -> jwtService.parseAccessToken(token))
                .isInstanceOf(JwtException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    void shouldFailFastOnTooShortSecret() {
        AuthProperties weak = new AuthProperties();
        weak.setJwtSecret("too-short");

        assertThatThrownBy(() -> new JwtService(weak, new TokenBlacklist()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void refreshTokenShouldBeRandomAndHashStable() {
        String first = jwtService.generateRefreshToken();
        String second = jwtService.generateRefreshToken();

        assertThat(first).isNotEqualTo(second);
        assertThat(first).hasSizeGreaterThan(40);
        assertThat(jwtService.hashRefreshToken(first))
                .hasSize(64)
                .isEqualTo(jwtService.hashRefreshToken(first))
                .isNotEqualTo(jwtService.hashRefreshToken(second));
    }
}
