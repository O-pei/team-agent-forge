package com.agentforge.agent.service;

import com.agentforge.agent.entity.AgentRunEntity;
import com.agentforge.agent.event.AgentEvent;
import com.agentforge.agent.event.AgentEvents;
import com.agentforge.agent.runtime.RunContext;
import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.QuotaExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运行中额度不足：SSE 必须先发 error(42901)，再发 run.completed(FAILED)，并保留已生成内容。
 */
class AgentChatServiceQuotaTest {

    private final List<AgentEvent> events = new ArrayList<>();
    private final StubExecutionService executionService = new StubExecutionService();
    private final AgentChatService service = new AgentChatService(executionService, null, null);

    @Test
    void quotaFailureEmitsErrorThenRunCompleted() {
        AgentExecutionService.Execution execution = execution();
        StringBuilder answer = new StringBuilder("已生成的部分内容");

        service.handleRunFailure(execution, answer, new QuotaExceededException("Token 额度已用尽"), new SseEmitter());

        assertThat(events).extracting(AgentEvent::event)
                .containsExactly(AgentEvent.ERROR, AgentEvent.RUN_COMPLETED);
        AgentEvents.ErrorData error = (AgentEvents.ErrorData) events.getFirst().data();
        assertThat(error.code()).isEqualTo(42901);
        assertThat(error.message()).isEqualTo("Token 额度已用尽");
        assertThat(executionService.completedStatuses).containsExactly("FAILED");
        assertThat(executionService.completedAnswers).containsExactly("已生成的部分内容");
    }

    @Test
    void wrappedQuotaFailureKeeps42901() {
        RuntimeException wrapped = new IllegalStateException("Agent 调用失败",
                new QuotaExceededException("每日额度已用尽"));

        service.handleRunFailure(execution(), new StringBuilder(), wrapped, new SseEmitter());

        AgentEvents.ErrorData error = (AgentEvents.ErrorData) events.getFirst().data();
        assertThat(error.code()).isEqualTo(42901);
        assertThat(error.message()).isEqualTo("每日额度已用尽");
    }

    @Test
    void genericFailureStays50000() {
        service.handleRunFailure(execution(), new StringBuilder(), new IllegalStateException("boom"),
                new SseEmitter());

        AgentEvents.ErrorData error = (AgentEvents.ErrorData) events.getFirst().data();
        assertThat(error.code()).isEqualTo(50000);
        assertThat(error.message()).isEqualTo("boom");
    }

    @Test
    void businessErrorKeepsItsCode() {
        assertThat(AgentChatService.errorCode(BizException.badRequest("参数错误"))).isEqualTo(40000);
        assertThat(AgentChatService.errorCode(new IllegalStateException(new QuotaExceededException("q"))))
                .isEqualTo(42901);
    }

    private AgentExecutionService.Execution execution() {
        RunContext context = new RunContext(1L, 2L, "session-1", 1L, 7L, true,
                (event, data) -> events.add(new AgentEvent(event, data)));
        AgentRunEntity run = new AgentRunEntity();
        run.setId(1L);
        return new AgentExecutionService.Execution(null, null, context, null, run, "session-1", "你好",
                System.nanoTime());
    }

    private static final class StubExecutionService extends AgentExecutionService {

        private final List<String> completedStatuses = new ArrayList<>();
        private final List<String> completedAnswers = new ArrayList<>();

        private StubExecutionService() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        public AgentRunEntity complete(Execution execution, String answer, String status, String error) {
            completedStatuses.add(status);
            completedAnswers.add(answer);
            execution.run().setStatus(status);
            return execution.run();
        }

        @Override
        public AgentEvents.RunCompleted completedEvent(Execution execution, AgentRunEntity run) {
            return new AgentEvents.RunCompleted(run.getId(), run.getStatus(), 5L,
                    execution.context().usageSnapshot());
        }
    }
}
