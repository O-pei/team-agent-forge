package com.agentforge.security.auth;

import com.agentforge.security.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * JWT 与 token 工具：
 * <ul>
 *   <li>access token：HS256 JWT，携带 userId/username/tenantId/roles/perms/jti，默认 2h；</li>
 *   <li>refresh token：SecureRandom 生成的不可预测随机串，DB 只保存 SHA-256 哈希（7d，可轮换/吊销）。</li>
 * </ul>
 * 契约：docs/04-API契约.md §1 认证。
 */
@Component
public class JwtService {

    private static final String CLAIM_USERNAME = "username";
    private static final String CLAIM_TENANT_ID = "tenantId";
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_PERMISSIONS = "perms";
    private static final String CLAIM_TYPE = "type";
    private static final String TYPE_ACCESS = "access";

    private final AuthProperties properties;
    private final TokenBlacklist tokenBlacklist;
    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public JwtService(AuthProperties properties, TokenBlacklist tokenBlacklist) {
        this.properties = properties;
        this.tokenBlacklist = tokenBlacklist;
        byte[] keyBytes = properties.getJwtSecret() == null
                ? new byte[0]
                : properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "agentforge.auth.jwt-secret 必须至少 32 字节（当前 " + keyBytes.length
                            + "），请用环境变量 AGENTFORGE_JWT_SECRET 覆盖");
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /** 签发 access token。 */
    public String createAccessToken(Long userId, String username, Long tenantId,
                                    Set<String> roles, Set<String> permissions) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_TENANT_ID, tenantId)
                .claim(CLAIM_ROLES, roles == null ? List.of() : List.copyOf(roles))
                .claim(CLAIM_PERMISSIONS, permissions == null ? List.of() : List.copyOf(permissions))
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.getAccessTokenTtl())))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析 access token。
     *
     * @throws io.jsonwebtoken.ExpiredJwtException token 过期（错误码 40101）
     * @throws io.jsonwebtoken.JwtException        token 非法或已被登出吊销
     */
    public AccessClaims parseAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
            throw new JwtException("not an access token");
        }
        String jti = claims.getId();
        if (jti == null || tokenBlacklist.isBlacklisted(jti)) {
            throw new JwtException("access token revoked");
        }
        Long userId = Long.valueOf(claims.getSubject());
        Number tenantClaim = claims.get(CLAIM_TENANT_ID, Number.class);
        Long tenantId = tenantClaim == null ? 1L : tenantClaim.longValue();
        return new AccessClaims(
                userId,
                claims.get(CLAIM_USERNAME, String.class),
                tenantId,
                toStringSet(claims.get(CLAIM_ROLES, List.class)),
                toStringSet(claims.get(CLAIM_PERMISSIONS, List.class)),
                jti,
                claims.getExpiration().toInstant());
    }

    /** 生成不可预测的 refresh token 明文（仅返回给客户端一次）。 */
    public String generateRefreshToken() {
        byte[] bytes = new byte[48];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** refresh token 入库哈希（含服务端 pepper，单独泄露哈希无法离线爆破原始 token）。 */
    public String hashRefreshToken(String rawToken) {
        return sha256Hex(properties.getJwtSecret() + "|refresh|" + rawToken);
    }

    public long accessTokenTtlSeconds() {
        return properties.getAccessTokenTtl().toSeconds();
    }

    public long refreshTokenTtlSeconds() {
        return properties.getRefreshTokenTtl().toSeconds();
    }

    private static Set<String> toStringSet(List<?> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (value != null) {
                result.add(String.valueOf(value));
            }
        }
        return result;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    /** access token 解析结果。 */
    public record AccessClaims(
            Long userId,
            String username,
            Long tenantId,
            Set<String> roles,
            Set<String> permissions,
            String jti,
            Instant expiresAt) {
    }
}
