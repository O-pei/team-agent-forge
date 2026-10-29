package com.agentforge.memory.config;

import com.agentforge.memory.fact.FactRepository;
import com.agentforge.memory.fact.InMemoryFactRepository;
import com.agentforge.memory.fact.RedisFactRepository;
import com.agentforge.memory.spi.MemoryStore;
import com.agentforge.memory.store.InMemoryMemoryStore;
import com.agentforge.memory.store.RedisMemoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 记忆引擎装配：启动时探测 Redis，可用则用 Redis 实现，否则自动降级 InMemory 并 WARN，启动不受影响。
 */
@Slf4j
@Configuration
public class MemoryConfig {

    @Bean
    public MemoryRedisSupport memoryRedisSupport(
            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
            @Value("${agentforge.memory.redis-enabled:false}") boolean redisEnabled) {
        return MemoryRedisSupport.probe(redisTemplateProvider.getIfAvailable(), redisEnabled);
    }

    @Bean
    @Primary
    public MemoryStore memoryStore(MemoryRedisSupport redisSupport, ObjectMapper objectMapper) {
        if (redisSupport.available()) {
            return new RedisMemoryStore(redisSupport.template(), objectMapper);
        }
        return new InMemoryMemoryStore();
    }

    @Bean
    public FactRepository factRepository(MemoryRedisSupport redisSupport, ObjectMapper objectMapper) {
        if (redisSupport.available()) {
            return new RedisFactRepository(redisSupport.template(), objectMapper);
        }
        return new InMemoryFactRepository();
    }
}
