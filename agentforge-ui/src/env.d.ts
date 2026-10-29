/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 后端 API base，默认 /api/v1 */
  readonly VITE_API_BASE?: string
  /** Vite 代理目标，默认 http://127.0.0.1:8080 */
  readonly VITE_PROXY_TARGET?: string
  /** 后端未就绪时用演示数据兜底，仅 DEV 生效 */
  readonly VITE_ENABLE_MOCK?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
