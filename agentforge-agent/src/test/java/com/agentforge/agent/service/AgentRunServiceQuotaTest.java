package com.agentforge.agent.service;

import com.agentforge.agent.dto.RunRequest;
import com.agentforge.agent.entity.AgentRunEntity;
import com.agentforge.agent.event.AgentEvents;
import com.agentforge.agent.runtime.RunContext;
import com.agentforge.agent.runtime.RunRegistry;
import com.agentforge.common.exception.QuotaExceededException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 非流式运行：额度不足必须原样返回 42901，不能被包成 400/500。
 */
class AgentRunServiceQuotaTest {

    @Test
    void precheckQuotaFailureSurfaces42901() {
        AgentRunService service = new AgentRunService(new PrecheckStub(), new RunRegistry(), null);

        assertThatThrownBy(() -> service.run(1L, new RunRequest("你好", null)))
                .isInstanceOf(QuotaExceededException.class)
                .satisfies(error -> assertThat(((QuotaExceededException) error).getCode()).isEqualTo(42901));
    }

    @Test
    void midRunQuotaFailureKeeps42901AndMarksFailed() {
        ExecuteStub stub = new ExecuteStub();
        AgentRunService service = new AgentRunService(stub, new RunRegistry(), null);

        assertThatThrownBy(() -> service.run(1L, new RunRequest("你好", null)))
                .isInstanceOf(QuotaExceededException.class)
                .satisfies(error -> assertThat(((QuotaExceededException) error).getCode()).isEqualTo(42901));
        assertThat(stub.statuses).containsExactly("FAILED");
    }

    private abstract static class StubExecutionService extends AgentExecutionService {

        protected StubExecutionService() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        public void bindRunThread(Execution execution) {
            // 单测不依赖审计上下文
        }

        protected Execution execution() {
            RunContext context = new RunContext(1L, 1L, "session-1", 1L, 7L, false, null);
            AgentRunEntity run = new AgentRunEntity();
            run.setId(1L);
            run.setTenantId(1L);
            run.setAgentId(1L);
            run.setSessionId("session-1");
            return new Execution(null, null, context, null, run, "session-1", "你好", System.nanoTime());
        }
    }

    private static final class PrecheckStub extends StubExecutionService {

        @Override
        public Execution start(Long agentId, String message, String sessionId, boolean streaming,
                               com.agentforge.agent.runtime.AgentEventSink sink) {
            throw new QuotaExceededException("今日 Token 额度已用尽");
        }
    }

    private static final class ExecuteStub extends StubExecutionService {

        private final List<String> statuses = new ArrayList<>();

        @Override
        public Execution start(Long agentId, String message, String sessionId, boolean streaming,
                               com.agentforge.agent.runtime.AgentEventSink sink) {
            return execution();
        }

        @Override
        public String execute(Execution execution) {
            throw new QuotaExceededException("Token 额度已用尽");
        }

        @Override
        public AgentRunEntity complete(Execution execution, String answer, String status, String error) {
            statuses.add(status);
            execution.run().setStatus(status);
            return execution.run();
        }

        @Override
        public AgentEvents.RunCompleted completedEvent(Execution execution, AgentRunEntity run) {
            return new AgentEvents.RunCompleted(run.getId(), run.getStatus(), 1L, null);
        }
    }
}
