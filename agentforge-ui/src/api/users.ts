/** 用户管理（契约 2.1，权限 user:read / user:write） */
import { http } from '@/utils/request'
import type {
  PageResult,
  ResetUserPasswordRequest,
  UserCreateRequest,
  UserInfo,
  UserQuery,
  UserUpdateRequest,
} from '@/types'

export const usersApi = {
  list(params: UserQuery) {
    return http.get<PageResult<UserInfo>>('/users', { params })
  },
  create(data: UserCreateRequest) {
    return http.post<UserInfo>('/users', data)
  },
  update(id: number | string, data: UserUpdateRequest) {
    return http.put<UserInfo>(`/users/${id}`, data)
  },
  /** 软禁用 */
  remove(id: number | string) {
    return http.delete<void>(`/users/${id}`)
  },
  resetPassword(id: number | string, data: ResetUserPasswordRequest) {
    return http.put<void>(`/users/${id}/password`, data)
  },
  loginLogs(params: { page?: number; size?: number }) {
    return http.get<PageResult<Record<string, unknown>>>('/login-logs', { params })
  },
}

export default usersApi
