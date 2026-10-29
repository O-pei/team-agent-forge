/** Agent Harness（契约 2.6，权限 agent:read / agent:write / agent:run） */
import { http } from '@/utils/request'
import type {
  AgentInfo,
  AgentRequest,
  AgentRunRequest,
  AgentRunResult,
  AgentTool,
  AgentVersionInfo,
  ApproveRequest,
  PageQuery,
  PageResult,
  RunStep,
  UsageInfo,
} from '@/types'

export const agentsApi = {
  list(params: PageQuery & { keyword?: string }) {
    return http.get<PageResult<AgentInfo>>('/agents', { params })
  },
  get(id: number | string) {
    return http.get<AgentInfo>(`/agents/${id}`)
  },
  create(data: AgentRequest) {
    return http.post<AgentInfo>('/agents', data)
  },
  /** 生成新版本（不可变历史） */
  update(id: number | string, data: AgentRequest) {
    return http.put<AgentInfo>(`/agents/${id}`, data)
  },
  remove(id: number | string) {
    return http.delete<void>(`/agents/${id}`)
  },
  publish(id: number | string, version: number) {
    return http.post<AgentInfo>(`/agents/${id}/publish`, { version })
  },
  versions(id: number | string) {
    return http.get<AgentVersionInfo[]>(`/agents/${id}/versions`)
  },
  /** 同步运行（非流式，调试用） */
  run(id: number | string, data: AgentRunRequest) {
    return http.post<AgentRunResult>(`/agents/${id}/run`, data)
  },
  tools() {
    return http.get<AgentTool[]>('/tools')
  },
  runDetail(runId: string) {
    return http.get<{
      runId: string
      status: string
      elapsedMs?: number
      usage?: UsageInfo
      steps?: RunStep[]
    }>(`/runs/${runId}`)
  },
  cancel(runId: string) {
    return http.post<void>(`/runs/${runId}/cancel`, {}, { silent: true })
  },
  /** HITL 中断恢复 */
  approve(runId: string, data: ApproveRequest) {
    return http.post<void>(`/runs/${runId}/approve`, data)
  },
}

export default agentsApi
