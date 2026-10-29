/**
 * Token 与用户信息本地持久化，独立成模块避免循环依赖。
 */

const ACCESS_TOKEN_KEY = 'agentforge.accessToken'
const REFRESH_TOKEN_KEY = 'agentforge.refreshToken'
const USER_KEY = 'agentforge.user'

export function getAccessToken(): string {
  return localStorage.getItem(ACCESS_TOKEN_KEY) || ''
}

export function getRefreshToken(): string {
  return localStorage.getItem(REFRESH_TOKEN_KEY) || ''
}

export function setTokens(accessToken: string, refreshToken?: string): void {
  localStorage.setItem(ACCESS_TOKEN_KEY, accessToken)
  if (refreshToken) localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken)
}

export function setTokenPair(accessToken: string, refreshToken: string): void {
  setTokens(accessToken, refreshToken)
}

export function clearTokens(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
}

export function persistUser<T>(user: T | null): void {
  if (user) localStorage.setItem(USER_KEY, JSON.stringify(user))
  else localStorage.removeItem(USER_KEY)
}

export function getPersistedUser<T>(): T | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as T
  } catch {
    return null
  }
}

/** 演示模式标记（后端未就绪时用 mock 身份登录，刷新后仍保持） */
const MOCK_SESSION_KEY = 'agentforge.mockSession'

export function isMockSession(): boolean {
  return localStorage.getItem(MOCK_SESSION_KEY) === '1'
}

export function setMockSession(value: boolean): void {
  if (value) localStorage.setItem(MOCK_SESSION_KEY, '1')
  else localStorage.removeItem(MOCK_SESSION_KEY)
}
