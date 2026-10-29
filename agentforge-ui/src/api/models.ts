/** 模型配置（契约 2.2，权限 model:read / model:write） */
import { http } from '@/utils/request'
import type { ModelConfig, ModelRequest, ModelTestResult } from '@/types'

export const modelsApi = {
  list() {
    return http.get<ModelConfig[]>('/models')
  },
  create(data: ModelRequest) {
    return http.post<ModelConfig>('/models', data)
  },
  /** apiKey 为空表示不变 */
  update(id: number | string, data: ModelRequest) {
    return http.put<ModelConfig>(`/models/${id}`, data)
  },
  remove(id: number | string) {
    return http.delete<void>(`/models/${id}`)
  },
  /** 发送 ping，返回延迟与回显 */
  test(id: number | string) {
    return http.post<ModelTestResult>(`/models/${id}/test`)
  },
}

export default modelsApi
