package com.agentforge.agent.service;

import com.agentforge.agent.domain.RunStatus;
import com.agentforge.agent.dto.ApprovalRequest;
import com.agentforge.agent.dto.RunRequest;
import com.agentforge.agent.dto.RunResponse;
import com.agentforge.agent.dto.RunStepResponse;
import com.agentforge.agent.entity.AgentRunEntity;
import com.agentforge.agent.entity.AgentRunStepEntity;
import com.agentforge.agent.repository.AgentRunStepRepository;
import com.agentforge.agent.runtime.QuotaErrors;
import com.agentforge.agent.runtime.RunRegistry;
import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.QuotaExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * 非流式运行：POST /api/v1/agents/{id}/run，以及运行记录查询/取消。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunService {

    private final AgentExecutionService executionService;
    private final RunRegistry runRegistry;
    private final AgentRunStepRepository stepRepository;

    public RunResponse run(Long agentId, RunRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw BizException.badRequest("message 不能为空");
        }
        AgentExecutionService.Execution execution = executionService.start(
                agentId, request.message(), request.sessionId(), false, null);
        try {
            executionService.bindRunThread(execution);
            String answer = executionService.execute(execution);
            AgentRunEntity run = executionService.complete(execution, answer, RunStatus.SUCCEEDED, null);
            return toResponse(run);
        } catch (RuntimeException e) {
            log.warn("运行失败 runId={}: {}", execution.context().getRunId(), e.getMessage());
            AgentRunEntity run = executionService.complete(execution, null, RunStatus.FAILED, e.getMessage());
            QuotaExceededException quota = QuotaErrors.find(e);
            if (quota != null) {
                throw quota;
            }
            throw BizException.badRequest("Agent 运行失败: " + run.getErrorMessage());
        }
    }

    @Transactional(readOnly = true)
    public RunResponse get(Long runId) {
        return toResponse(executionService.loadRun(runId));
    }

    public RunResponse cancel(Long runId) {
        boolean cancelled = runRegistry.cancel(runId);
        AgentRunEntity run = executionService.loadRun(runId);
        if (!cancelled && RunStatus.RUNNING.equals(run.getStatus())) {
            log.info("运行不在本机执行，取消信号未送达 runId={}", runId);
        }
        return toResponse(run);
    }

    /**
     * HITL 审批：v0.1 只记录审批步骤与结果，SAA interrupt/resume 恢复执行放在 v0.2
     * （前端收到 interrupted 事件后调用本接口，服务端用 checkpoint 恢复）。
     */
    @Transactional
    public RunResponse approve(Long runId, ApprovalRequest request) {
        if (request == null || request.approved() == null) {
            throw BizException.badRequest("approved 不能为空");
        }
        AgentRunEntity run = executionService.loadRun(runId);
        if (!RunStatus.RUNNING.equals(run.getStatus())) {
            throw BizException.badRequest("运行不在等待审批状态: " + run.getStatus());
        }
        AgentRunStepEntity step = new AgentRunStepEntity();
        step.setRunId(runId);
        step.setSeqNo((int) executionService.stepsOf(runId).size() + 1);
        step.setStepType("APPROVAL");
        step.setInput(request.comment());
        step.setOutput(Boolean.TRUE.equals(request.approved()) ? "APPROVED" : "REJECTED");
        step.setStatus("SUCCEEDED");
        step.setElapsedMs(0L);
        stepRepository.save(step);
        log.info("记录 HITL 审批 runId={} approved={}", runId, request.approved());
        return toResponse(run);
    }

    public RunResponse toResponse(AgentRunEntity run) {
        return toResponse(run, executionService.stepsOf(run.getId()));
    }

    public RunResponse toResponse(AgentRunEntity run, List<AgentRunStepEntity> steps) {
        List<RunStepResponse> stepResponses = steps.stream()
                .map(step -> new RunStepResponse(step.getSeqNo(), step.getAgentName(), step.getNodeName(),
                        step.getStepType(), step.getToolName(), step.getInput(), step.getOutput(),
                        step.getStatus(), step.getElapsedMs()))
                .toList();
        long elapsedMs = run.getStartedAt() == null || run.getFinishedAt() == null ? 0L
                : Math.max(0L, Duration.between(run.getStartedAt(), run.getFinishedAt()).toMillis());
        return new RunResponse(run.getId(), run.getAgentId(), run.getAgentVersion(), run.getSessionId(),
                run.getStatus(), run.getInput(), run.getOutput(), run.getPromptTokens(),
                run.getCompletionTokens(), run.getTotalTokens(), run.getModelName(), run.getErrorMessage(),
                run.getTraceId(), run.getStartedAt(), run.getFinishedAt(), elapsedMs, stepResponses);
    }
}
