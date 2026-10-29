/**
 * v-perm 按钮级权限指令：无权限时隐藏元素，权限异步到达后可恢复显示。
 * 用法：v-perm="'user:write'" 或 v-perm="['rag:write','rag:read']"。
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
