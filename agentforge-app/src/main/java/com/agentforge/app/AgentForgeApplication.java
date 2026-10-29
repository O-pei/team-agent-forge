package com.agentforge.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * AgentForge 应用入口，统一扫描各模块的组件、配置属性、Entity 与 Repository。
 * 认证完全基于自研 JWT 过滤器，故排除 Spring Security 默认内存用户自动配置，
 * 避免启动日志打印随机密码并注册无用的 inMemoryUserDetailsManager。
 */
@SpringBootApplication(
        scanBasePackages = "com.agentforge",
        exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan("com.agentforge")
@EntityScan("com.agentforge")
@EnableJpaRepositories("com.agentforge")
@EnableAsync
public class AgentForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentForgeApplication.class, args);
    }
}
