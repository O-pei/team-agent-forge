package com.agentforge.security.sms;

/**
 * 短信发送抽象（v0.1.0 预留）：后续接入阿里云/腾讯云短信只需实现本接口。
 */
public interface SmsSender {

    void sendLoginCode(String phone);
}
