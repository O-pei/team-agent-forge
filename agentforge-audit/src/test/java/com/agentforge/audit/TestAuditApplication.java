package com.agentforge.audit;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * 测试用最小启动配置：audit 模块不在 app 内，@DataJpaTest 需要 SpringBootConfiguration 定位自动配置。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class TestAuditApplication {
}
