/** 数据源与 SQL 查询（契约 2.5，权限 datasource:read / datasource:write / datasource:query） */
import { http } from '@/utils/request'
import type {
  ConnectionTestResult,
  Datasource,
  DatasourceRequest,
  PageQuery,
  PageResult,
  SchemaTable,
  SqlQueryLog,
  SqlQueryRequest,
  SqlQueryResult,
} from '@/types'

export const datasourcesApi = {
  list(params: PageQuery) {
    return http.get<PageResult<Datasource>>('/datasources', { params })
  },
  create(data: DatasourceRequest) {
    return http.post<Datasource>('/datasources', data)
  },
  /** password 空表示不变 */
  update(id: number | string, data: DatasourceRequest) {
    return http.put<Datasource>(`/datasources/${id}`, data)
  },
  remove(id: number | string) {
    return http.delete<void>(`/datasources/${id}`)
  },
  test(id: number | string) {
    return http.post<ConnectionTestResult>(`/datasources/${id}/test`)
  },
  schema(id: number | string) {
    return http.get<SchemaTable[]>(`/datasources/${id}/schema`)
  },
  /** 只读 + 强制 LIMIT */
  query(id: number | string, data: SqlQueryRequest) {
    return http.post<SqlQueryResult>(`/datasources/${id}/query`, data)
  },
  queryLogs(id: number | string, params: PageQuery) {
    return http.get<PageResult<SqlQueryLog>>(`/datasources/${id}/query-logs`, { params })
  },
}

export default datasourcesApi
