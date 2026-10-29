<template>
  <div class="app-layout">
    <aside class="app-sidebar" :class="{ 'is-collapsed': collapsed }">
      <div class="brand" @click="router.push('/dashboard')">
        <span class="brand-mark">AF</span>
        <span v-show="!collapsed" class="brand-text">
          AgentForge
          <small>多智能体平台</small>
        </span>
      </div>

      <el-scrollbar class="sidebar-scroll">
        <el-menu
          :default-active="activePath"
          :collapse="collapsed"
          :collapse-transition="false"
          router
          class="sidebar-menu"
          background-color="#0f172a"
          text-color="#94a3b8"
          active-text-color="#ffffff"
        >
          <template v-for="item in visibleMenu" :key="item.path || item.title">
            <el-sub-menu v-if="item.children && item.children.length" :index="item.title">
              <template #title>
                <el-icon><component :is="item.icon" /></el-icon>
                <span>{{ item.title }}</span>
              </template>
              <el-menu-item v-for="child in item.children" :key="child.path" :index="child.path!">
                <el-icon><component :is="child.icon" /></el-icon>
                <template #title>{{ child.title }}</template>
              </el-menu-item>
            </el-sub-menu>
            <el-menu-item v-else :index="item.path!">
              <el-icon><component :is="item.icon" /></el-icon>
              <template #title>{{ item.title }}</template>
            </el-menu-item>
          </template>
        </el-menu>
      </el-scrollbar>

      <div v-show="!collapsed" class="sidebar-footer">
        <span class="dot" /> 后端 {{ backendHint }}
      </div>
    </aside>

    <div class="app-main">
      <header class="app-header">
        <el-button text class="collapse-btn" @click="collapsed = !collapsed">
          <el-icon :size="18">
            <Expand v-if="collapsed" />
            <Fold v-else />
          </el-icon>
        </el-button>

        <el-breadcrumb separator="/">
          <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
          <el-breadcrumb-item v-if="route.meta.group">{{ route.meta.group }}</el-breadcrumb-item>
          <el-breadcrumb-item>{{ route.meta.title || '页面' }}</el-breadcrumb-item>
        </el-breadcrumb>

        <div class="header-right">
          <el-tag
            v-if="mockState.active"
            type="warning"
            effect="dark"
            size="small"
            class="mock-tag"
            @click="showMockSources"
          >
            演示数据 · {{ mockState.sources.length }}
          </el-tag>
          <el-tooltip content="后端未就绪时页面使用演示数据，真实请求恢复后自动消失" placement="bottom">
            <el-icon class="header-icon"><QuestionFilled /></el-icon>
          </el-tooltip>
          <el-divider direction="vertical" />
          <el-dropdown trigger="click" @command="onUserCommand">
            <span class="user-entry">
              <el-avatar :size="28" class="user-avatar">{{ avatarText }}</el-avatar>
              <span class="user-name">{{ auth.displayName }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>{{ auth.user?.email || '-' }}</el-dropdown-item>
                <el-dropdown-item disabled>
                  角色：{{ auth.roleLabels.length ? auth.roleLabels.join(' / ') : '-' }}
                </el-dropdown-item>
                <el-dropdown-item command="logout" divided>
                  <el-icon><SwitchButton /></el-icon> 退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="app-content">
        <router-view v-slot="{ Component }">
          <transition name="fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowDown,
  ChatDotRound,
  Coin,
  Collection,
  Cpu,
  DataAnalysis,
  Expand,
  Fold,
  Lock,
  Monitor,
  Odometer,
  QuestionFilled,
  Setting,
  SwitchButton,
  User,
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { mockState } from '@/utils/mock'

interface MenuEntry {
  path?: string
  title: string
  icon?: Component
  permission?: string
  children?: MenuEntry[]
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const collapsed = ref(false)

const menu: MenuEntry[] = [
  { path: '/dashboard', title: '工作台', icon: Odometer, permission: 'audit:read' },
  { path: '/chat', title: '对话', icon: ChatDotRound, permission: 'agent:run' },
  { path: '/agents', title: 'Agent 管理', icon: Cpu, permission: 'agent:read' },
  { path: '/knowledge', title: '知识库', icon: Collection, permission: 'rag:read' },
  { path: '/datasources', title: '数据源', icon: Coin, permission: 'datasource:read' },
  { path: '/sandbox', title: '代码沙箱', icon: Monitor, permission: 'datasource:read' },
  { path: '/audit', title: '审计看板', icon: DataAnalysis, permission: 'audit:read' },
  { path: '/models', title: '模型配置', icon: Setting, permission: 'model:read' },
  {
    title: '系统管理',
    icon: Lock,
    children: [
      { path: '/admin/users', title: '用户管理', icon: User, permission: 'user:read' },
      { path: '/admin/roles', title: '角色权限', icon: Lock, permission: 'role:read' },
    ],
  },
]

const visibleMenu = computed<MenuEntry[]>(() =>
  menu
    .map((entry) =>
      entry.children
        ? { ...entry, children: entry.children.filter((child) => auth.hasPermission(child.permission)) }
        : entry,
    )
    .filter((entry) =>
      entry.children ? entry.children.length > 0 : auth.hasPermission(entry.permission),
    ),
)

const activePath = computed(() => route.path)
const avatarText = computed(() => (auth.displayName || 'A').slice(0, 1).toUpperCase())
const backendHint = computed(() => (mockState.active ? '未连接（演示）' : '已连接'))

function showMockSources(): void {
  const sources = mockState.sources
  ElMessageBox.alert(
    sources.length
      ? `以下数据来源因后端接口未就绪或不可达，当前展示演示数据：\n\n${sources
          .map((item) => `· ${item}`)
          .join('<br/>')}`
      : '当前无演示数据',
    '演示数据说明',
    { dangerouslyUseHTMLString: true, confirmButtonText: '知道了' },
  )
}

async function onUserCommand(command: string): Promise<void> {
  if (command === 'logout') {
    try {
      await ElMessageBox.confirm('确认退出登录？', '提示', {
        type: 'warning',
        confirmButtonText: '退出',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
    await auth.logout()
    ElMessage.success('已退出登录')
    await router.replace('/login')
  }
}
</script>

<style scoped>
.app-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

/* 侧边栏 */
.app-sidebar {
  display: flex;
  flex-direction: column;
  width: var(--af-sidebar-width);
  flex: 0 0 var(--af-sidebar-width);
  background: var(--af-sidebar-bg);
  color: #e2e8f0;
  transition: width 0.2s ease, flex-basis 0.2s ease;
  overflow: hidden;
}

.app-sidebar.is-collapsed {
  width: 64px;
  flex-basis: 64px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: var(--af-header-height);
  padding: 0 16px;
  cursor: pointer;
  border-bottom: 1px solid rgba(148, 163, 184, 0.14);
  flex: 0 0 auto;
}

.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: linear-gradient(135deg, #2563eb, #7c3aed);
  color: #fff;
  font-weight: 700;
  font-size: 13px;
  letter-spacing: 0.5px;
  flex: 0 0 auto;
}

.brand-text {
  display: flex;
  flex-direction: column;
  font-size: 15px;
  font-weight: 600;
  color: #f8fafc;
  white-space: nowrap;
}

.brand-text small {
  font-size: 10px;
  font-weight: 400;
  color: #64748b;
  letter-spacing: 1px;
}

.sidebar-scroll {
  flex: 1;
  min-height: 0;
}

.sidebar-menu {
  border-right: none;
  padding: 8px 0;
}

.sidebar-menu :deep(.el-menu-item),
.sidebar-menu :deep(.el-sub-menu__title) {
  height: 44px;
  line-height: 44px;
  margin: 2px 8px;
  border-radius: 8px;
  font-size: 13.5px;
}

.sidebar-menu :deep(.el-menu-item:hover),
.sidebar-menu :deep(.el-sub-menu__title:hover) {
  background: rgba(148, 163, 184, 0.12) !important;
  color: #f8fafc !important;
}

.sidebar-menu :deep(.el-menu-item.is-active) {
  background: linear-gradient(90deg, #1d4ed8, #2563eb) !important;
  color: #fff !important;
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.35);
}

.sidebar-footer {
  flex: 0 0 auto;
  padding: 12px 18px;
  border-top: 1px solid rgba(148, 163, 184, 0.14);
  font-size: 11.5px;
  color: #64748b;
  display: flex;
  align-items: center;
  gap: 6px;
  white-space: nowrap;
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #22c55e;
  box-shadow: 0 0 6px #22c55e;
}

/* 主区域 */
.app-main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.app-header {
  display: flex;
  align-items: center;
  gap: 14px;
  height: var(--af-header-height);
  flex: 0 0 var(--af-header-height);
  padding: 0 18px;
  background: #fff;
  border-bottom: 1px solid var(--af-border);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
  z-index: 10;
}

.collapse-btn {
  color: #475569;
}

.header-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
}

.mock-tag {
  cursor: pointer;
}

.header-icon {
  color: var(--af-text-light);
  cursor: help;
}

.user-entry {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
}

.user-avatar {
  background: linear-gradient(135deg, #2563eb, #7c3aed);
  color: #fff;
  font-weight: 600;
}

.user-name {
  font-size: 13.5px;
  color: #334155;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.app-content {
  flex: 1;
  overflow-y: auto;
  background: var(--af-bg);
}

@media (max-width: 768px) {
  .app-sidebar {
    position: fixed;
    z-index: 30;
    height: 100%;
    box-shadow: 4px 0 18px rgba(15, 23, 42, 0.18);
  }
  .user-name {
    display: none;
  }
}
</style>
