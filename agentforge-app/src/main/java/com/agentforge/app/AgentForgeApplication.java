package com.agentforge.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * AgentForge 应用入口。
 *
 * <p>模块划分：common / security / audit / memory / rag / datasource / agent / app。</p>
 */
@SpringBootApplication(scanBasePackages = "com.agentforge")
@ConfigurationPropertiesScan("com.agentforge")
@EnableAsync
public class AgentForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentForgeApplication.class, args);
    }
}
