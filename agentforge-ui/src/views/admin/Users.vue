<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">用户管理</h2>
        <p class="page-desc">账号、角色分配与登录审计 · 软禁用用户不影响历史数据</p>
      </div>
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="搜索邮箱 / 用户名"
          clearable
          :prefix-icon="Search"
          style="width: 220px"
          @keyup.enter="load(1)"
          @clear="load(1)"
        />
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 130px" @change="load(1)">
          <el-option label="启用" value="ACTIVE" />
          <el-option label="禁用" value="DISABLED" />
        </el-select>
        <el-button :icon="Refresh" :loading="loading" @click="load()">刷新</el-button>
        <el-button v-perm="'user:write'" type="primary" :icon="Plus" @click="openCreate">新建用户</el-button>
      </div>
    </div>

    <div class="af-card af-card--pad">
      <el-table v-loading="loading" :data="users" empty-text="暂无用户">
        <el-table-column label="用户" min-width="220">
          <template #default="{ row }">
            <div class="user-cell">
              <div class="avatar">{{ (row.nickname || row.username || '?').slice(0, 1).toUpperCase() }}</div>
              <div>
                <div class="user-name">{{ row.nickname || row.username }}</div>
                <div class="muted">{{ row.email }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="用户名" width="140">
          <template #default="{ row }"><span class="mono">{{ row.username }}</span></template>
        </el-table-column>
        <el-table-column label="角色" min-width="200">
          <template #default="{ row }">
            <el-tag v-for="role in row.roles || []" :key="role" size="small" effect="light" class="role-tag">
              {{ roleLabel(role) }}
            </el-tag>
            <span v-if="!(row.roles || []).length" class="muted">未分配</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'DISABLED' ? 'info' : 'success'" size="small" effect="light">
              {{ row.status === 'DISABLED' ? '禁用' : '启用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最后登录" width="170">
          <template #default="{ row }">{{ row.lastLoginAt ? formatDateTime(row.lastLoginAt) : '从未登录' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button v-perm="'user:write'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-perm="'user:write'" link type="warning" @click="openReset(row)">重置密码</el-button>
            <el-button link type="info" @click="openLogs(row)">登录日志</el-button>
            <el-button v-perm="'user:write'" link type="danger" @click="toggleStatus(row)">
              {{ row.status === 'DISABLED' ? '启用' : '禁用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="load()"
          @size-change="load(1)"
        />
      </div>
    </div>

    <!-- 新建 / 编辑 -->
    <el-dialog v-model="editorVisible" :title="editingId !== null ? '编辑用户' : '新建用户'" width="520px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" :disabled="editingId !== null" placeholder="user@example.com" />
        </el-form-item>
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="editingId !== null" placeholder="字母、数字、下划线" />
        </el-form-item>
        <el-form-item v-if="editingId === null" label="初始密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="至少 8 位，包含字母与数字" />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" maxlength="32" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.roleIds" multiple placeholder="选择角色" style="width: 100%">
            <el-option v-for="role in roles" :key="role.id" :label="role.name" :value="role.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="editingId !== null" label="状态">
          <el-radio-group v-model="form.status">
            <el-radio value="ACTIVE">启用</el-radio>
            <el-radio value="DISABLED">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="resetVisible" title="重置密码" width="420px" destroy-on-close>
      <el-form ref="resetFormRef" :model="resetForm" :rules="resetRules" label-width="90px">
        <el-form-item label="用户">
          <span class="mono">{{ resetTarget?.email }}</span>
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="resetForm.newPassword" type="password" show-password />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitReset">确认重置</el-button>
      </template>
    </el-dialog>

    <!-- 登录日志 -->
    <el-drawer v-model="logsVisible" :title="`登录日志 · ${logsTarget?.email || ''}`" size="620px">
      <el-table v-loading="logsLoading" :data="loginLogs" size="small" empty-text="暂无登录记录">
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatDateTime(cell(row, 'createdAt') || cell(row, 'loginAt')) }}</template>
        </el-table-column>
        <el-table-column label="IP" width="140">
          <template #default="{ row }"><span class="mono">{{ cell(row, 'ip') || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="客户端" min-width="160">
          <template #default="{ row }">{{ cell(row, 'userAgent') || cell(row, 'device') || '—' }}</template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="cell(row, 'success') === 'false' ? 'danger' : 'success'" size="small" effect="light">
              {{ cell(row, 'success') === 'false' ? '失败' : '成功' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="140">
          <template #default="{ row }">{{ cell(row, 'message') || '—' }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { usersApi } from '@/api/users'
import { rolesApi } from '@/api/roles'
import type { RoleInfo, UserCreateRequest, UserInfo, UserUpdateRequest } from '@/types'
import { formatDateTime } from '@/utils/format'
import { isFallbackError } from '@/utils/request'
import { mockRoles, mockUsers, withMock } from '@/utils/mock'

const ROLE_LABELS: Record<string, string> = {
  SUPER_ADMIN: '超级管理员',
  ADMIN: '管理员',
  USER: '普通用户',
}

const loading = ref(false)
const saving = ref(false)
const keyword = ref('')
const statusFilter = ref<string | undefined>(undefined)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const users = ref<UserInfo[]>([])
const roles = ref<RoleInfo[]>([])

const editorVisible = ref(false)
const editingId = ref<number | string | null>(null)
const formRef = ref<FormInstance>()
const form = reactive({
  email: '',
  username: '',
  password: '',
  nickname: '',
  roleIds: [] as Array<number | string>,
  status: 'ACTIVE',
})
const rules: FormRules = {
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_]{3,32}$/, message: '3-32 位字母、数字或下划线', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入初始密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码长度 8-64 位', trigger: 'blur' },
  ],
}

const resetVisible = ref(false)
const resetTarget = ref<UserInfo | null>(null)
const resetFormRef = ref<FormInstance>()
const resetForm = reactive({ newPassword: '' })
const resetRules: FormRules = {
  newPassword: [{ required: true, min: 8, max: 64, message: '密码长度 8-64 位', trigger: 'blur' }],
}

const logsVisible = ref(false)
const logsLoading = ref(false)
const logsTarget = ref<UserInfo | null>(null)
const loginLogs = ref<Array<Record<string, unknown>>>([])

function roleLabel(code: string): string {
  return ROLE_LABELS[code] || code
}

function cell(row: Record<string, unknown>, key: string): string {
  const value = row[key]
  if (value === undefined || value === null || value === '') return ''
  return String(value)
}

function mockPage(source: UserInfo[], pageNo = 1, pageSize = 10) {
  const start = (pageNo - 1) * pageSize
  return { records: source.slice(start, start + pageSize), total: source.length, page: pageNo, size: pageSize }
}

async function load(target = page.value): Promise<void> {
  page.value = target
  loading.value = true
  try {
    const result = await withMock(
      '用户列表',
      () => usersApi.list({ page: page.value, size: size.value, keyword: keyword.value || undefined, status: statusFilter.value }),
      () => mockPage(mockUsers(), page.value, size.value),
    )
    users.value = result.data.records
    total.value = result.data.total
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('用户列表加载失败')
  } finally {
    loading.value = false
  }
}

async function loadRoles(): Promise<void> {
  try {
    const result = await withMock('角色列表', () => rolesApi.list(), () => mockRoles())
    roles.value = result.data
  } catch {
    /* 角色加载失败不阻塞用户管理 */
  }
}

function openCreate(): void {
  editingId.value = null
  form.email = ''
  form.username = ''
  form.password = ''
  form.nickname = ''
  form.roleIds = []
  form.status = 'ACTIVE'
  editorVisible.value = true
}

// el-table 作用域插槽的 row 类型为 DefaultRow，入口处放宽类型，函数内部按 UserInfo 使用
function openEdit(row: any): void {
  editingId.value = row.id
  form.email = row.email
  form.username = row.username
  form.password = ''
  form.nickname = row.nickname || ''
  form.roleIds = []
  form.status = row.status === 'DISABLED' ? 'DISABLED' : 'ACTIVE'
  // 列表返回角色码，编辑时按码匹配角色 id
  form.roleIds = roles.value.filter((role) => (row.roles || []).includes(role.code)).map((role) => role.id)
  editorVisible.value = true
}

async function submit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (editingId.value !== null) {
      const payload: UserUpdateRequest = {
        nickname: form.nickname || undefined,
        status: form.status,
        roleIds: form.roleIds,
      }
      await withMock(
        `编辑用户 #${editingId.value}`,
        () => usersApi.update(editingId.value!, payload),
        () => ({ id: editingId.value!, email: form.email, username: form.username, ...payload }),
      )
    } else {
      const payload: UserCreateRequest = {
        email: form.email,
        username: form.username,
        password: form.password,
        nickname: form.nickname || undefined,
        roleIds: form.roleIds,
      }
      await withMock('新建用户', () => usersApi.create(payload), () => ({
        id: Date.now(),
        email: payload.email,
        username: payload.username,
        nickname: payload.nickname,
        status: 'ACTIVE',
        roles: [],
      }))
    }
    ElMessage.success('保存成功')
    editorVisible.value = false
    await load()
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

function openReset(row: any): void {
  resetTarget.value = row
  resetForm.newPassword = ''
  resetVisible.value = true
}

async function submitReset(): Promise<void> {
  if (!resetFormRef.value || !resetTarget.value) return
  const valid = await resetFormRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    await withMock(
      `重置密码 #${resetTarget.value.id}`,
      () => usersApi.resetPassword(resetTarget.value!.id, { newPassword: resetForm.newPassword }),
      () => undefined,
    )
    ElMessage.success('密码已重置')
    resetVisible.value = false
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('重置失败')
  } finally {
    saving.value = false
  }
}

async function toggleStatus(row: any): Promise<void> {
  const disabled = row.status === 'DISABLED'
  try {
    await ElMessageBox.confirm(
      disabled ? `确认启用用户「${row.email}」？` : `确认禁用用户「${row.email}」？禁用后无法登录。`,
      disabled ? '启用确认' : '禁用确认',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await withMock(
      `切换用户状态 #${row.id}`,
      () => usersApi.update(row.id, { status: disabled ? 'ACTIVE' : 'DISABLED' }),
      () => ({ ...row, status: disabled ? 'ACTIVE' : 'DISABLED' }),
    )
    ElMessage.success(disabled ? '已启用' : '已禁用')
    await load()
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('操作失败')
  }
}

async function openLogs(row: any): Promise<void> {
  logsTarget.value = row
  logsVisible.value = true
  logsLoading.value = true
  try {
    const result = await withMock(
      `登录日志 #${row.id}`,
      () => usersApi.loginLogs({ page: 1, size: 20 }),
      () => {
        const list = mockUsers()
          .slice(0, 6)
          .map((user, index) => ({
            id: index + 1,
            username: user.username,
            createdAt: user.lastLoginAt || user.createdAt || '',
            ip: `10.0.${index}.${20 + index}`,
            userAgent: 'Chrome / Windows',
            success: String(index % 5 !== 4),
            message: index % 5 === 4 ? '密码错误' : '登录成功',
          }))
        return { records: list, total: list.length, page: 1, size: 20 }
      },
    )
    loginLogs.value = result.data.records
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('登录日志加载失败')
  } finally {
    logsLoading.value = false
  }
}

onMounted(async () => {
  await loadRoles()
  await load()
})
</script>

<style scoped>
.user-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: linear-gradient(135deg, #2563eb, #7c3aed);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 600;
  font-size: 14px;
  flex-shrink: 0;
}

.user-name {
  font-weight: 600;
  color: #0f172a;
  line-height: 1.4;
}

.role-tag {
  margin-right: 4px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
</style>
