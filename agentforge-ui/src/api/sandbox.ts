/**
 * 代码沙箱（契约 2.5 增补）
 * POST /sandbox/execute 权限 datasource:query
 * GET  /sandbox/providers 权限 datasource:read
 */
import { http } from '@/utils/request'
import type { SandboxExecuteRequest, SandboxExecuteResult, SandboxProviderInfo } from '@/types'

export const sandboxApi = {
  execute(data: SandboxExecuteRequest) {
    return http.post<SandboxExecuteResult>('/sandbox/execute', data)
  },
  providers() {
    return http.get<SandboxProviderInfo[]>('/sandbox/providers')
  },
}

export default sandboxApi
