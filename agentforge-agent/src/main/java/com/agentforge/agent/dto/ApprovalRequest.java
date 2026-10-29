package com.agentforge.agent.dto;

/**
 * HITL 审批请求（契约 §2.6：POST /api/v1/runs/{runId}/approve）。
 */
public record ApprovalRequest(Boolean approved, String comment) {
}
