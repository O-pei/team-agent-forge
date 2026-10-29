package com.agentforge.security.service;

import com.agentforge.security.auth.AuthErrorCodes;
import com.agentforge.security.config.AuthProperties;
import com.agentforge.security.entity.RefreshTokenEntity;
import com.agentforge.security.repository.RefreshTokenRepository;
import com.agentforge.common.api.ErrorCode;
import com.agentforge.common.exception.BizException;
import com.agentforge.security.auth.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * refresh token 生命周期：签发（只存哈希）、消费轮换（一次性）、吊销（登出/改密）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final AuthProperties properties;

    /** 签发新的 refresh token，返回明文（仅此一次）。 */
    @Transactional
    public String issue(Long userId, String userAgent, String ip) {
        String rawToken = jwtService.generateRefreshToken();
        RefreshTokenEntity entity = new RefreshTokenEntity();
        entity.setUserId(userId);
        entity.setTokenHash(jwtService.hashRefreshToken(rawToken));
        entity.setExpiresAt(LocalDateTime.now().plus(properties.getRefreshTokenTtl()));
        entity.setRevoked(false);
        entity.setUserAgent(truncate(userAgent, 255));
        entity.setIp(truncate(ip, 64));
        refreshTokenRepository.save(entity);
        return rawToken;
    }

    /**
     * 消费（轮换）refresh token：校验存在、未吊销、未过期，然后立即吊销。
     *
     * @return 对应的用户 ID
     */
    @Transactional
    public Long consume(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BizException(AuthErrorCodes.REFRESH_TOKEN_INVALID, "refresh token 无效");
        }
        RefreshTokenEntity entity = refreshTokenRepository.findByTokenHash(jwtService.hashRefreshToken(rawToken))
                .orElseThrow(() -> new BizException(AuthErrorCodes.REFRESH_TOKEN_INVALID, "refresh token 无效或已吊销"));
        if (entity.isRevoked() || entity.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BizException(AuthErrorCodes.REFRESH_TOKEN_INVALID, "refresh token 无效或已吊销");
        }
        entity.setRevoked(true);
        refreshTokenRepository.save(entity);
        return entity.getUserId();
    }

    /** 登出时吊销单个 refresh token（不存在也视为成功，幂等）。 */
    @Transactional
    public void revoke(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        refreshTokenRepository.revokeByTokenHash(jwtService.hashRefreshToken(rawToken));
    }

    /** 吊销用户全部 refresh token（改密 / 强制下线）。 */
    @Transactional
    public int revokeAll(Long userId) {
        return refreshTokenRepository.revokeAllByUserId(userId);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 兜底错误码，避免直接依赖 common 的 401 语义。 */
    static BizException unauthorized(String message) {
        return new BizException(ErrorCode.UNAUTHORIZED, message);
    }
}
