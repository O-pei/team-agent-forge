/**
 * 路由与守卫：权限校验、首屏拉取当前用户、按权限选择落地页。
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    /** 权限码，见契约附录 A */
    permission?: string
    /** 匿名可访问 */
    public?: boolean
    /** 侧边栏图标名 */
    icon?: string
    /** 侧边栏分组 */
    group?: string
    /** 不在侧边栏展示 */
    hidden?: boolean
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录', public: true, hidden: true },
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/Register.vue'),
    meta: { title: '注册', public: true, hidden: true },
  },
  {
    path: '/forgot',
    name: 'forgot',
    component: () => import('@/views/Forgot.vue'),
    meta: { title: '找回密码', public: true, hidden: true },
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    children: [
      {
        path: '',
        // 按权限选择落地页：有 audit:read 进工作台，否则进对话；权限未加载时由守卫二次校正
        redirect: () => useAuthStore().homePath,
      },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/Dashboard.vue'),
        meta: { title: '工作台', icon: 'Odometer', permission: 'audit:read' },
      },
      {
        path: 'chat',
        name: 'chat',
        component: () => import('@/views/Chat.vue'),
        meta: { title: '对话', icon: 'ChatDotRound', permission: 'agent:run' },
      },
      {
        path: 'agents',
        name: 'agents',
        component: () => import('@/views/Agents.vue'),
        meta: { title: 'Agent 管理', icon: 'Cpu', permission: 'agent:read' },
      },
      {
        path: 'knowledge',
        name: 'knowledge',
        component: () => import('@/views/Knowledge.vue'),
        meta: { title: '知识库', icon: 'Collection', permission: 'rag:read' },
      },
      {
        path: 'datasources',
        name: 'datasources',
        component: () => import('@/views/Datasources.vue'),
        meta: { title: '数据源', icon: 'Coin', permission: 'datasource:read' },
      },
      {
        path: 'sandbox',
        name: 'sandbox',
        component: () => import('@/views/Sandbox.vue'),
        meta: { title: '代码沙箱', icon: 'Monitor', permission: 'datasource:read' },
      },
      {
        path: 'audit',
        name: 'audit',
        component: () => import('@/views/Audit.vue'),
        meta: { title: '审计看板', icon: 'DataAnalysis', permission: 'audit:read' },
      },
      {
        path: 'models',
        name: 'models',
        component: () => import('@/views/Models.vue'),
        meta: { title: '模型配置', icon: 'Setting', permission: 'model:read' },
      },
      {
        path: 'admin/users',
        name: 'admin-users',
        component: () => import('@/views/admin/Users.vue'),
        meta: {
          title: '用户管理',
          icon: 'User',
          permission: 'user:read',
          group: '系统管理',
        },
      },
      {
        path: 'admin/roles',
        name: 'admin-roles',
        component: () => import('@/views/admin/Roles.vue'),
        meta: {
          title: '角色权限',
          icon: 'Lock',
          permission: 'role:read',
          group: '系统管理',
        },
      },
      {
        path: 'admin/quota',
        name: 'admin-quota',
        component: () => import('@/views/admin/Quota.vue'),
        meta: {
          title: '额度管理',
          icon: 'Wallet',
          permission: 'quota:read',
          group: '系统管理',
        },
      },
      {
        path: '403',
        name: 'forbidden',
        component: () => import('@/views/error/403.vue'),
        meta: { title: '无权限', hidden: true },
      },
      {
        path: '404',
        name: 'not-found',
        component: () => import('@/views/error/404.vue'),
        meta: { title: '页面不存在', public: true, hidden: true },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/404',
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  document.title = to.meta.title ? `${to.meta.title} · AgentForge` : 'AgentForge'

  if (to.meta.public) {
    if ((to.name === 'login' || to.name === 'register') && auth.isLoggedIn && auth.loaded) {
      return { path: auth.homePath }
    }
    return true
  }

  if (!auth.isLoggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  if (!auth.loaded) {
    try {
      await auth.fetchMe()
    } catch {
      auth.resetSession()
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  // 直接访问根路径时 redirect 先于 /auth/me 执行（此时权限为空会误判），加载完成后再按权限校正落地页
  if (to.redirectedFrom?.path === '/' && to.path !== auth.homePath) {
    return { path: auth.homePath }
  }

  if (to.meta.permission && !auth.hasPermission(to.meta.permission)) {
    return { path: '/403' }
  }
  return true
})

router.afterEach(() => {
  // 预留：埋点 / 进度条
})

export default router
