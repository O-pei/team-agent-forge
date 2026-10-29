package com.agentforge.security.mail;

import com.agentforge.security.config.AuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 邮件发送策略：mail-enabled 为 true 且存在 JavaMailSender 时走真实 SMTP，否则回退到日志输出。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouterMailSender implements MailSender {

    private final AuthProperties properties;
    private final ObjectProvider<JavaMailSender> javaMailSenderProvider;

    @Override
    public void sendVerificationCode(String email, String scene, String code) {
        if (properties.isMailEnabled()) {
            JavaMailSender sender = javaMailSenderProvider.getIfAvailable();
            if (sender == null) {
                log.warn("agentforge.auth.mail-enabled=true 但 spring.mail.* 未配置，降级为日志输出 scene={}", scene);
            } else {
                try {
                    SimpleMailMessage message = new SimpleMailMessage();
                    message.setTo(email);
                    message.setSubject("AgentForge 验证码");
                    message.setText("您的验证码是：" + code + "，5 分钟内有效。请勿泄露给他人。");
                    sender.send(message);
                    log.info("验证码邮件已发送 email={} scene={}", email, scene);
                    return;
                } catch (Exception ex) {
                    log.warn("验证码邮件发送失败，降级为日志输出 email={} scene={} error={}", email, scene, ex.getMessage());
                }
            }
        }
        // 开发环境兜底：日志打印验证码，便于本地联调；生产环境必须配置 SMTP。
        log.info("[DEV-MAIL] 邮箱验证码 email={} scene={} code={} (SMTP 未启用，仅开发环境可见)", email, scene, code);
    }
}
