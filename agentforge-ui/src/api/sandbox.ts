/**
 * 代码沙箱：执行代码与查询可用运行时。
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
