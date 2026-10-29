package com.agentforge.security.service;

import com.agentforge.common.api.PageResult;
import com.agentforge.security.dto.LoginLogVO;
import com.agentforge.security.entity.LoginLogEntity;
import com.agentforge.security.repository.LoginLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录日志查询（契约 §2.4 GET /api/v1/login-logs）。
 */
@Service
@RequiredArgsConstructor
public class LoginLogService {

    private final LoginLogRepository loginLogRepository;

    @Transactional(readOnly = true)
    public PageResult<LoginLogVO> page(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        Pageable pageable = PageRequest.of(safePage - 1, safeSize);
        Page<LoginLogEntity> result = loginLogRepository.findAllByOrderByCreatedAtDesc(pageable);
        return PageResult.of(result.getContent().stream().map(this::toVO).toList(),
                result.getTotalElements(), safePage, safeSize);
    }

    private LoginLogVO toVO(LoginLogEntity entity) {
        return new LoginLogVO(entity.getId(), entity.getUserId(), entity.getUsername(), entity.isSuccess(),
                entity.getReason(), entity.getIp(), entity.getUserAgent(), entity.getCreatedAt());
    }
}
