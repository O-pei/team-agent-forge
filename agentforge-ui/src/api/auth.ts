/** 认证与用户（契约 2.1） */
import { http } from '@/utils/request'
import type {
  EmailCodeRequest,
  EmailCodeResult,
  ForgotPasswordRequest,
  LoginRequest,
  LoginResult,
  MeResult,
  RefreshRequest,
  RefreshResult,
  RegisterRequest,
  ResetPasswordRequest,
} from '@/types'

const silent = { silent: true }

export const authApi = {
  /** 发送邮箱验证码；60s 冷却，同邮箱 1h ≤ 10 次 */
  sendEmailCode(data: EmailCodeRequest) {
    return http.post<EmailCodeResult>('/auth/email-code', data, silent)
  },
  register(data: RegisterRequest) {
    return http.post<void>('/auth/register', data, silent)
  },
  /** 账号密码登录；错误由页面统一提示，避免重复 toast */
  login(data: LoginRequest) {
    return http.post<LoginResult>('/auth/login', data, silent)
  },
  refresh(data: RefreshRequest) {
    return http.post<RefreshResult>('/auth/refresh', data, silent)
  },
  logout() {
    return http.post<void>('/auth/logout', {}, silent)
  },
  /** 当前用户 + 角色 + 权限 + 菜单 */
  me() {
    return http.get<MeResult>('/auth/me')
  },
  forgotPassword(data: ForgotPasswordRequest) {
    return http.post<void>('/auth/password/forgot', data, silent)
  },
  resetPassword(data: ResetPasswordRequest) {
    return http.post<void>('/auth/password/reset', data, silent)
  },
}

export default authApi
