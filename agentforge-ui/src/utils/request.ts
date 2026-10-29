/**
 * Axios 统一封装：ApiResponse 解包、Bearer 注入、401 刷新重试与全局错误提示。
 */
import axios, {
  type AxiosError,
  type AxiosRequestConfig,
  type InternalAxiosRequestConfig,
} from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResponse } from '@/types'
import {
  clearTokens,
  getAccessToken,
  getRefreshToken,
  setTokenPair,
} from './token'

export const API_BASE = import.meta.env.VITE_API_BASE || '/api/v1'

/** 网络层错误（后端未启动/跨域/超时）统一用该码，供 mock 兜底判断 */
export const NETWORK_ERROR_CODE = -1

export class ApiError extends Error {
  code: number
  traceId?: string

  constructor(code: number, message: string, traceId?: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
  }
}

declare module 'axios' {
  export interface AxiosRequestConfig {
    /** 静默：不弹全局错误提示（轮询/页面自行兜底时用） */
    silent?: boolean
    /** 内部标记：401 是否已重试过 */
    _retried?: boolean
  }
}

type RetriableConfig = InternalAxiosRequestConfig & { silent?: boolean; _retried?: boolean }

const service = axios.create({
  baseURL: API_BASE,
  timeout: 25_000,
  headers: { 'Content-Type': 'application/json' },
})

function isApiResponse(value: unknown): value is ApiResponse<unknown> {
  return (
    !!value &&
    typeof value === 'object' &&
    typeof (value as ApiResponse<unknown>).code === 'number' &&
    'data' in (value as Record<string, unknown>)
  )
}

function notify(err: ApiError, config?: RetriableConfig): void {
  if (config?.silent) return
  // 未认证类错误由跳转处理，避免重复弹窗
  if (err.code === 40100 || err.code === 40101 || err.code === 40102) return
  ElMessage.error({ message: err.message || '请求失败', grouping: true, duration: 3500 })
}

/* 401 刷新 */

let refreshPromise: Promise<string | null> | null = null
let redirecting = false

async function refreshAccessToken(): Promise<string | null> {
  const refreshToken = getRefreshToken()
  if (!refreshToken) return null
  try {
    const response = await axios.post<ApiResponse<{ accessToken: string; refreshToken: string }>>(
      `${API_BASE}/auth/refresh`,
      { refreshToken },
      { timeout: 15_000, headers: { 'Content-Type': 'application/json' } },
    )
    const payload = response.data
    if (payload?.code === 0 && payload.data?.accessToken) {
      setTokenPair(payload.data.accessToken, payload.data.refreshToken || refreshToken)
      return payload.data.accessToken
    }
    return null
  } catch {
    return null
  }
}

async function forceLogin(): Promise<void> {
  if (redirecting) return
  redirecting = true
  try {
    const { default: router } = await import('@/router')
    const current = router.currentRoute.value
    if (current.path !== '/login' && current.path !== '/register') {
      await router.replace({ path: '/login', query: { redirect: current.fullPath } })
    }
  } catch {
    if (!window.location.pathname.endsWith('/login')) window.location.href = '/login'
  } finally {
    redirecting = false
  }
}

async function handleUnauthorized(config: RetriableConfig | undefined): Promise<unknown> {
  if (config && !config._retried && getRefreshToken()) {
    config._retried = true
    if (!refreshPromise) {
      refreshPromise = refreshAccessToken().finally(() => {
        refreshPromise = null
      })
    }
    const token = await refreshPromise
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
      return service.request(config)
    }
  }
  clearTokens()
  await forceLogin()
  throw new ApiError(40100, '登录状态已失效，请重新登录')
}

/* 拦截器 */

service.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

service.interceptors.response.use(
  (response) => {
    // 文件下载：直接返回 Blob
    if (response.config.responseType === 'blob') return response.data

    const payload: unknown = response.data
    if (isApiResponse(payload)) {
      if (payload.code === 0) return payload.data
      if (payload.code === 40100 || payload.code === 40101) {
        return handleUnauthorized(response.config as RetriableConfig)
      }
      const err = new ApiError(payload.code, payload.message || '请求失败', payload.traceId)
      notify(err, response.config as RetriableConfig)
      return Promise.reject(err)
    }
    // 非标准响应（如网关返回原始 JSON/文本）原样返回
    return payload
  },
  async (error: AxiosError) => {
    const config = (error.config || {}) as RetriableConfig
    const status = error.response?.status
    if (status === 401) return handleUnauthorized(config)

    const payload = error.response?.data as Partial<ApiResponse<unknown>> | undefined
    let code = NETWORK_ERROR_CODE
    if (payload && typeof payload.code === 'number') code = payload.code
    else if (status === 404) code = 40400
    else if (status === 403) code = 40300
    else if (status === 429) code = 42900
    else if (status && status >= 500) code = 50000

    const message =
      payload?.message ||
      (status
        ? `请求失败（HTTP ${status}）`
        : '无法连接后端服务，请确认后端已启动或检查 VITE_PROXY_TARGET')

    const err = new ApiError(code, message, payload?.traceId)
    notify(err, config)
    return Promise.reject(err)
  },
)

/* 对外客户端 */

export interface HttpClient {
  request<T = unknown>(config: AxiosRequestConfig): Promise<T>
  get<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T>
  post<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  put<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  patch<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  delete<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T>
}

export const http = service as unknown as HttpClient

/** 后端未就绪（网络错误 / 接口不存在 / 5xx / 服务不可用）时可走演示数据 */
export function isFallbackError(error: unknown): boolean {
  if (error instanceof ApiError) {
    return (
      error.code === NETWORK_ERROR_CODE ||
      error.code === 40400 ||
      error.code === 50300 ||
      error.code >= 50000
    )
  }
  return false
}

export function getErrorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof Error) return error.message
  return String(error ?? '未知错误')
}

export default http
