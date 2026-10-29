package com.agentforge.security.mail;

/**
 * 邮件发送抽象：SMTP 已配置时走 JavaMail，否则 dev 环境走日志兜底（见 {@link RouterMailSender}）。
 */
public interface MailSender {

    void sendVerificationCode(String email, String scene, String code);
}
