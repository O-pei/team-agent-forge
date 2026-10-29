package com.agentforge.audit.config;

import com.agentforge.common.crypto.CryptoUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Audit 模块装配：主密钥初始化 + Token 用量异步落库线程池。
 */
@Configuration
public class AuditConfig {

    public AuditConfig(@Value("${agentforge.crypto.key:}") String cryptoKey) {
        CryptoUtils.configure(cryptoKey);
    }

    @Bean(name = "tokenUsageExecutor")
    public ThreadPoolTaskExecutor tokenUsageExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(2000);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("token-usage-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }
}
