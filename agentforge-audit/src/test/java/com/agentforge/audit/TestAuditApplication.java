package com.agentforge.audit;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * 测试用最小启动配置：audit 模块不在 app 内，{@code @DataJpaTest} 需要能找到一个
 * {@link SpringBootConfiguration} 来定位自动配置（仅测试 classpath 使用）。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class TestAuditApplication {
}
