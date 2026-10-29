package com.agentforge.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
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
 * {@code @EnableJpaRepositories} / {@code @EntityScan}，避免多份引导配置冲突。</p>
 */
@SpringBootApplication(scanBasePackages = "com.agentforge")
@ConfigurationPropertiesScan("com.agentforge")
@EntityScan("com.agentforge")
@EnableJpaRepositories("com.agentforge")
@EnableAsync
public class AgentForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentForgeApplication.class, args);
    }
}
