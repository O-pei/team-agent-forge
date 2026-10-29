/**
 * 认证状态：token、用户与权限的持久化和权限判断，后端未就绪时可降级为演示身份。
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/auth'
import type { LoginRequest, LoginResult, MeResult, UserInfo } from '@/types'
import { isFallbackError } from '@/utils/request'
import { mockEnabled, mockLogin, mockMe, mockState } from '@/utils/mock'
import {
  clearTokens,
  getAccessToken,
  getPersistedUser,
  isMockSession,
  persistUser,
  setMockSession,
  setTokenPair,
} from '@/utils/token'

const ROLE_LABELS: Record<string, string> = {
  SUPER_ADMIN: '超级管理员',
  ADMIN: '管理员',
  USER: '普通用户',
}

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref(getAccessToken())
  const user = ref<UserInfo | null>(getPersistedUser<UserInfo>())
  const roles = ref<string[]>([])
  const permissions = ref<string[]>([])
  const loaded = ref(false)
  const mockMode = ref(isMockSession())

  const isLoggedIn = computed(() => !!accessToken.value)
  const isSuperAdmin = computed(() => roles.value.includes('SUPER_ADMIN'))
  const displayName = computed(() => user.value?.nickname || user.value?.username || '未登录')
  const roleLabels = computed(() => roles.value.map((role) => ROLE_LABELS[role] || role))
  const permCount = computed(() => permissions.value.length)

  /**
   * 登录后的默认落地页：有审计权限进工作台，否则进对话（USER 无 audit:read，默认 /chat）。
   * 供登录成功跳转、根路径重定向与路由守卫复用。
   */
  const homePath = computed(() => (hasPermission('audit:read') ? '/dashboard' : '/chat'))

  /** 权限判断：无参数=通过；数组=任一命中；SUPER_ADMIN/演示身份直接放行 */
  function hasPermission(code?: string | string[] | null): boolean {
    if (!code) return true
    const codes = Array.isArray(code) ? code : [code]
    if (codes.length === 0) return true
    if (mockMode.value || isSuperAdmin.value) return true
    return codes.some((item) => permissions.value.includes(item) || permissions.value.includes('*'))
  }

  function hasRole(role: string): boolean {
    return roles.value.includes(role)
  }

  function applySession(result: LoginResult): void {
    accessToken.value = result.accessToken
    setTokenPair(result.accessToken, result.refreshToken)
    if (result.user) {
      user.value = result.user
      persistUser(result.user)
    }
    if (result.roles?.length) roles.value = result.roles
    else if (result.user?.roles?.length) roles.value = result.user.roles
    if (result.permissions?.length) permissions.value = result.permissions
    loaded.value = true
  }

  async function fetchMe(): Promise<MeResult> {
    try {
      const me = await authApi.me()
      if (me.user) {
        user.value = me.user
        persistUser(me.user)
      }
      roles.value = me.roles || me.user?.roles || []
      permissions.value = me.permissions || []
      loaded.value = true
      return me
    } catch (error) {
      if (mockEnabled && (mockMode.value || isFallbackError(error))) {
        const me = mockMe()
        mockState.note('当前用户信息')
        setMockSession(true)
        mockMode.value = true
        user.value = me.user
        persistUser(me.user)
        roles.value = me.roles
        permissions.value = me.permissions
        loaded.value = true
        return me
      }
      throw error
    }
  }

  /** 登录：接口未就绪时降级为演示身份（仅 DEV） */
  async function login(payload: LoginRequest): Promise<{ mock: boolean }> {
    try {
      const result = await authApi.login(payload)
      mockMode.value = false
      setMockSession(false)
      applySession(result)
      if (!result.permissions?.length) {
        try {
          await fetchMe()
        } catch {
          /* 权限拉取失败不阻塞登录，路由守卫会再试 */
        }
      }
      return { mock: false }
    } catch (error) {
      if (!mockEnabled || !isFallbackError(error)) throw error
      const result = mockLogin(payload.account)
      mockState.note('登录')
      setMockSession(true)
      mockMode.value = true
      applySession(result)
      if (result.permissions) permissions.value = result.permissions
      loaded.value = true
      return { mock: true }
    }
  }

  async function logout(): Promise<void> {
    try {
      if (!mockMode.value) await authApi.logout()
    } catch {
      /* 登出失败也要清理本地状态 */
    }
    resetSession()
  }

  function resetSession(): void {
    accessToken.value = ''
    user.value = null
    roles.value = []
    permissions.value = []
    loaded.value = false
    mockMode.value = false
    clearTokens()
    setMockSession(false)
    mockState.reset()
  }

  return {
    accessToken,
    user,
    roles,
    permissions,
    loaded,
    mockMode,
    isLoggedIn,
    isSuperAdmin,
    displayName,
    roleLabels,
    permCount,
    homePath,
    hasPermission,
    hasRole,
    fetchMe,
    login,
    logout,
    resetSession,
  }
})
