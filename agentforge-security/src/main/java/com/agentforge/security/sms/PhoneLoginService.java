package com.agentforge.security.sms;

import com.agentforge.common.api.ErrorCode;
import com.agentforge.common.exception.BizException;
import com.agentforge.security.dto.LoginResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 手机号登录预留实现：短信通道未接入，发送验证码与手机号登录均返回 50300。
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
