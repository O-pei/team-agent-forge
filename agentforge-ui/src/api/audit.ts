/** Token 审计 / 用量看板（契约 2.2，权限 audit:read） */
import { http } from '@/utils/request'
import type {
  AuditOverview,
  AuditRangeQuery,
  PageResult,
  UsageBreakdownItem,
  UsageBreakdownQuery,
  UsageRecord,
  UsageRecordQuery,
  UsageSummary,
  UsageTrendResult,
} from '@/types'

export const auditApi = {
  overview() {
    return http.get<AuditOverview>('/audit/overview')
  },
  usageSummary(params: AuditRangeQuery) {
    return http.get<UsageSummary>('/audit/usage/summary', { params })
  },
  usageTrend(params: AuditRangeQuery & { granularity: 'day' | 'hour' }) {
    return http.get<UsageTrendResult>('/audit/usage/trend', { params })
  },
  usageBreakdown(params: UsageBreakdownQuery) {
    return http.get<UsageBreakdownItem[]>('/audit/usage/breakdown', { params })
  },
  usageRecords(params: UsageRecordQuery) {
    return http.get<PageResult<UsageRecord>>('/audit/usage/records', { params })
  },
  /** CSV 下载，直接返回 Blob */
  usageExport(params: AuditRangeQuery) {
    return http.get<Blob>('/audit/usage/export', { params, responseType: 'blob' })
  },
}

export default auditApi
