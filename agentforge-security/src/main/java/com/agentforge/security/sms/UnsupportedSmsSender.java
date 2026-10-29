package com.agentforge.security.sms;

import com.agentforge.common.api.ErrorCode;
import com.agentforge.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 未配置短信通道时的占位实现：按契约返回业务码 50300（message 含 {@code sms-not-configured}）。
 */
@Slf4j
@Component
public class UnsupportedSmsSender implements SmsSender {

    @Override
    public void sendLoginCode(String phone) {
        log.info("收到短信登录请求，但短信通道未配置 phone={}", maskPhone(phone));
        throw new BizException(ErrorCode.SERVICE_UNAVAILABLE, "sms-not-configured: 短信登录通道尚未配置");
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
