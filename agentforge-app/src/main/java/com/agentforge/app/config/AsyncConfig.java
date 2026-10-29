package com.agentforge.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 默认异步执行器。
 *
 * <p>各模块自带专用线程池（{@code tokenUsageExecutor}、{@code agentRunExecutor}），
 * 若容器里存在多个 {@code TaskExecutor} 而没有名为 {@code taskExecutor} 的 bean，
 * Spring 的 {@code @Async} 默认解析会打印
 * “More than one TaskExecutor bean found ... none is named 'taskExecutor'” 并退化为
 * 每次新建线程的 SimpleAsyncTaskExecutor。这里显式提供 {@code taskExecutor} 作为
 * 通用异步入口（文档解析、邮件发送等），避免隐式退化。</p>
 */
@Configuration
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    @Primary
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("af-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }
}
