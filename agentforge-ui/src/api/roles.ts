/** 角色 / 权限（契约 2.1，权限 role:read / role:write） */
import { http } from '@/utils/request'
import type { PermissionNode, RoleInfo, RoleRequest } from '@/types'

export const rolesApi = {
  list() {
    return http.get<RoleInfo[]>('/roles')
  },
  create(data: RoleRequest) {
    return http.post<RoleInfo>('/roles', data)
  },
  /** 含 permissionIds */
  update(id: number | string, data: RoleRequest) {
    return http.put<RoleInfo>(`/roles/${id}`, data)
  },
  /** 内置角色不可删 */
  remove(id: number | string) {
    return http.delete<void>(`/roles/${id}`)
  },
  permissions() {
    return http.get<PermissionNode[]>('/permissions')
  },
}

export default rolesApi
