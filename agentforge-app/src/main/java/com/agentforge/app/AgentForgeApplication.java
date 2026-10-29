package com.agentforge.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * AgentForge 应用入口。
 *
 * <p>模块划分：common / security / audit / memory / rag / datasource / agent / app。</p>
 *
 * <p>各业务模块的 Entity 与 Repository 分布在 {@code com.agentforge.<module>.entity|repository}，
 * 不在本启动类所在包下，因此必须显式声明扫描路径；模块内不要重复添加
 * <p>排除 {@link UserDetailsServiceAutoConfiguration}：本项目的认证完全基于自研 JWT 过滤器，
 * 不使用 Spring Security 的默认内存用户；保留自动配置会在启动日志里打印随机密码并注册
 * {@code inMemoryUserDetailsManager}，既无用又容易让人误判生产环境还存在默认凭据。</p>
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
