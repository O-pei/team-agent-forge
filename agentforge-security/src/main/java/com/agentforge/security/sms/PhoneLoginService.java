package com.agentforge.security.sms;

import com.agentforge.common.api.ErrorCode;
import com.agentforge.common.exception.BizException;
import com.agentforge.security.dto.LoginResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 手机号登录骨架（v0.1.0 预留，不破坏契约）。
 *
 * <p>后续接入方式：</p>
 * <ol>
 *   <li>实现 {@link SmsSender}（如阿里云短信），按 {@code agentforge.auth.sms-enabled} 条件装配；</li>
 *   <li>在 V1 {@code af_user.phone} 基础上补 {@code phone_verified} 与 {@code af_sms_verification} 表（新迁移）；</li>
 *   <li>在此实现 {@code loginByPhone(phone, code)}，复用 AuthService 的 token 签发逻辑，渠道标记 {@link LoginChannel#PHONE}。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class PhoneLoginService {

    private final SmsSender smsSender;

    // TODO v0.2.0: 接入短信服务商后发送登录验证码
    public void sendLoginCode(String phone) {
        smsSender.sendLoginCode(phone);
    }

    // TODO v0.2.0: 校验短信验证码并签发双 token
    public LoginResponse loginByPhone(String phone, String code) {
        throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "sms-not-configured: 短信登录通道尚未配置");
    }
}
