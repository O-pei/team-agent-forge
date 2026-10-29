/**
 * v-perm 按钮级权限指令
 * 用法：<el-button v-perm="'user:write'">新增</el-button> / v-perm="['rag:write','rag:read']"
 * 无权限时隐藏（保留 DOM 占位，权限异步到达后可恢复）
 */
import type { Directive, DirectiveBinding } from 'vue'
import { useAuthStore } from '@/stores/auth'

type PermValue = string | string[] | undefined

function apply(el: HTMLElement, binding: DirectiveBinding<PermValue>): void {
  const auth = useAuthStore()
  el.style.display = auth.hasPermission(binding.value) ? '' : 'none'
}

export const permissionDirective: Directive<HTMLElement, PermValue> = {
  mounted(el, binding) {
    apply(el, binding)
  },
  updated(el, binding) {
    apply(el, binding)
  },
}

export default permissionDirective
