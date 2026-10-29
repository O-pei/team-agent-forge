import { http } from '@/utils/request'
import type {
  GrantRecord,
  PageResult,
  QuotaGrantRequest,
  QuotaStatus,
  QuotaTier,
  QuotaTierRequest,
  QuotaUserQuery,
  UserQuotaRow,
  UserQuotaUpdateRequest,
} from '@/types'

export const quotaApi = {
  getMyQuota() {
    return http.get<QuotaStatus>('/quota/me')
  },
  listTiers() {
    return http.get<QuotaTier[]>('/quota/tiers')
  },
  createTier(data: QuotaTierRequest) {
    return http.post<QuotaTier>('/quota/tiers', data)
  },
  updateTier(id: number | string, data: QuotaTierRequest) {
    return http.put<QuotaTier>(`/quota/tiers/${id}`, data)
  },
  deleteTier(id: number | string) {
    return http.delete<void>(`/quota/tiers/${id}`)
  },
  listUserQuotas(params: QuotaUserQuery) {
    return http.get<PageResult<UserQuotaRow>>('/quota/users', { params })
  },
  updateUserQuota(userId: number | string, data: UserQuotaUpdateRequest) {
    return http.put<UserQuotaRow>(`/quota/users/${userId}`, data)
  },
  resetUserQuota(userId: number | string) {
    return http.delete<void>(`/quota/users/${userId}/override`)
  },
  grantTokens(userId: number | string, data: QuotaGrantRequest) {
    return http.post<void>(`/quota/users/${userId}/grant`, data)
  },
  listGrants(userId: number | string, params: { page?: number; size?: number }) {
    return http.get<PageResult<GrantRecord>>(`/quota/users/${userId}/grants`, { params })
  },
}

export default quotaApi
