<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">额度管理</h2>
        <p class="page-desc">
          套餐定义每日与总额度；用户可继承套餐或单独覆盖额度，管理员手动分配会累加到总额度并记入流水。
        </p>
      </div>
      <div class="toolbar">
        <el-button :icon="Refresh" @click="refreshAll">刷新</el-button>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="af-card af-card--pad quota-tabs">
      <el-tab-pane label="套餐管理" name="tiers">
        <div class="toolbar mb-12">
          <el-button v-perm="'quota:write'" type="primary" :icon="Plus" @click="openTierDialog()">
            新建套餐
          </el-button>
          <span class="muted">额度为 0 表示不限；内置套餐不可删除，仍被用户引用时删除会返回 409</span>
        </div>

        <el-table v-loading="tierLoading" :data="tiers" border stripe>
          <el-table-column prop="code" label="编码" width="140">
            <template #default="{ row }">
              <span class="mono">{{ row.code }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="名称" width="140" />
          <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip />
          <el-table-column label="每日额度" width="140">
            <template #default="{ row }">{{ limitText(row.dailyTokenLimit) }}</template>
          </el-table-column>
          <el-table-column label="总额度" width="150">
            <template #default="{ row }">{{ limitText(row.totalTokenLimit) }}</template>
          </el-table-column>
          <el-table-column prop="sortOrder" label="排序" width="80" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                {{ row.enabled ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="来源" width="100">
            <template #default="{ row }">
              <el-tag :type="row.builtin ? 'warning' : 'info'" size="small" effect="plain">
                {{ row.builtin ? '内置' : '自定义' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="使用人数" width="100">
            <template #default="{ row }">{{ formatNumber(row.userCount ?? 0) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button v-perm="'quota:write'" link type="primary" @click="openTierDialog(row)">
                编辑
              </el-button>
              <el-button
                v-perm="'quota:write'"
                link
                type="danger"
                :disabled="row.builtin"
                @click="removeTier(row)"
              >
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="用户额度" name="users">
        <div class="toolbar mb-12">
          <el-input
            v-model="keyword"
            placeholder="搜索用户名 / 邮箱 / 昵称"
            clearable
            style="width: 240px"
            @keyup.enter="loadUsers(1)"
          />
          <el-button type="primary" :icon="Search" @click="loadUsers(1)">搜索</el-button>
          <el-button :icon="Refresh" @click="loadUsers(page)">刷新</el-button>
          <span class="muted">覆盖额度留空表示继承套餐，0 表示不限</span>
        </div>

        <el-table v-loading="userLoading" :data="userQuotas" border stripe>
          <el-table-column label="用户" min-width="200">
            <template #default="{ row }">
              <div class="user-cell">
                <span class="avatar">{{ (row.nickname || row.username || '?').slice(0, 1).toUpperCase() }}</span>
                <div>
                  <div>{{ row.nickname || row.username }}</div>
                  <div class="muted mono">{{ row.email }}</div>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="套餐" width="150">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ row.tierName || '-' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="每日用量" width="160">
            <template #default="{ row }">
              {{ limitText(row.dailyLimit) }} / {{ formatNumber(row.dailyUsed) }}
            </template>
          </el-table-column>
          <el-table-column label="总额度用量" width="180">
            <template #default="{ row }">
              {{ limitText(row.totalLimit) }} / {{ formatNumber(row.totalUsed) }}
            </template>
          </el-table-column>
          <el-table-column label="剩余" width="120">
            <template #default="{ row }">
              {{ row.unlimited ? '不限' : formatNumber(row.totalRemaining) }}
            </template>
          </el-table-column>
          <el-table-column label="手动分配" width="120">
            <template #default="{ row }">
              {{ row.grantedTokens ? formatNumber(row.grantedTokens) : '-' }}
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
                {{ row.status === 'ACTIVE' ? '正常' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="重置时间" width="170">
            <template #default="{ row }">
              {{ row.resetAt ? formatDateTime(row.resetAt) : '-' }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="300" fixed="right">
            <template #default="{ row }">
              <el-button v-perm="'quota:write'" link type="primary" @click="openUserDialog(row)">
                覆盖额度
              </el-button>
              <el-button v-perm="'quota:write'" link type="success" @click="openGrantDialog(row)">
                分配 Token
              </el-button>
              <el-button link type="info" @click="openGrants(row)">流水</el-button>
              <el-button
                v-perm="'quota:write'"
                link
                type="warning"
                :disabled="!hasOverride(row)"
                @click="resetUserQuota(row)"
              >
                清除覆盖
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pager mt-12">
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="size"
            :total="total"
            :page-sizes="[10, 20, 50]"
            layout="total, sizes, prev, pager, next"
            @current-change="(value: number) => loadUsers(value)"
            @size-change="loadUsers(1)"
          />
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="tierDialogVisible"
      :title="editingTierId ? '编辑套餐' : '新建套餐'"
      width="560px"
      destroy-on-close
    >
      <el-form ref="tierFormRef" :model="tierForm" :rules="tierRules" label-width="110px">
        <el-form-item label="套餐编码" prop="code">
          <el-input
            v-model="tierForm.code"
            :disabled="!!editingTierId"
            placeholder="大写字母与下划线，如 PRO"
            maxlength="32"
          />
        </el-form-item>
        <el-form-item label="套餐名称" prop="name">
          <el-input v-model="tierForm.name" placeholder="如 专业版" maxlength="64" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="tierForm.description" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item label="每日额度">
          <el-input-number v-model="tierForm.dailyTokenLimit" :min="0" :step="10000" style="width: 200px" />
          <span class="muted ml-8">0 表示不限</span>
        </el-form-item>
        <el-form-item label="总额度">
          <el-input-number v-model="tierForm.totalTokenLimit" :min="0" :step="100000" style="width: 200px" />
          <span class="muted ml-8">0 表示不限</span>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="tierForm.sortOrder" :min="0" :max="999" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="tierForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tierDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitTier">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="userDialogVisible" title="覆盖用户额度" width="520px" destroy-on-close>
      <div class="dialog-user">
        <span class="avatar">{{ (userTarget?.nickname || userTarget?.username || '?').slice(0, 1).toUpperCase() }}</span>
        <div>
          <div>{{ userTarget?.nickname || userTarget?.username }}</div>
          <div class="muted mono">{{ userTarget?.email }}</div>
        </div>
      </div>
      <el-form label-width="110px" class="mt-12">
        <el-form-item label="套餐">
          <el-select
            v-model="userForm.tierId"
            clearable
            placeholder="继承套餐"
            style="width: 220px"
            @clear="userForm.tierId = null"
          >
            <el-option v-for="tier in tiers" :key="tier.id" :label="tier.name" :value="tier.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="每日额度">
          <el-input-number
            :model-value="userForm.dailyLimit ?? undefined"
            :min="0"
            :step="10000"
            style="width: 200px"
            @update:model-value="(value: number | undefined) => (userForm.dailyLimit = value ?? null)"
          />
          <span class="muted ml-8">留空继承套餐，0 表示不限</span>
        </el-form-item>
        <el-form-item label="总额度">
          <el-input-number
            :model-value="userForm.totalLimit ?? undefined"
            :min="0"
            :step="100000"
            style="width: 200px"
            @update:model-value="(value: number | undefined) => (userForm.totalLimit = value ?? null)"
          />
          <span class="muted ml-8">留空继承套餐，0 表示不限</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="userDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitUserQuota">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="grantDialogVisible" title="手动分配 Token" width="480px" destroy-on-close>
      <div class="dialog-user">
        <span class="avatar">{{ (grantTarget?.nickname || grantTarget?.username || '?').slice(0, 1).toUpperCase() }}</span>
        <div>
          <div>{{ grantTarget?.nickname || grantTarget?.username }}</div>
          <div class="muted">当前总额度 {{ limitText(grantTarget?.totalLimit ?? 0) }}</div>
        </div>
      </div>
      <el-form label-width="90px" class="mt-12">
        <el-form-item label="分配数量" required>
          <el-input-number v-model="grantForm.amount" :min="1" :step="10000" style="width: 200px" />
        </el-form-item>
        <el-form-item label="分配原因">
          <el-input v-model="grantForm.reason" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <p class="muted">分配数量会累加到该用户总额度，并记入分配流水；不会影响每日额度。</p>
      <template #footer>
        <el-button @click="grantDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitGrant">确认分配</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="grantsVisible" title="额度分配流水" size="620px">
      <el-table v-loading="grantsLoading" :data="grants" border stripe>
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="数量" width="120">
          <template #default="{ row }">+{{ formatNumber(row.amount) }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="160" show-overflow-tooltip />
        <el-table-column prop="operatorName" label="操作人" width="110" />
      </el-table>
      <div class="pager mt-12">
        <el-pagination
          v-model:current-page="grantPage"
          :page-size="10"
          :total="grantTotal"
          layout="total, prev, pager, next"
          @current-change="(value: number) => loadGrants(value)"
        />
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { quotaApi } from '@/api/quota'
import type { GrantRecord, QuotaTier, QuotaTierRequest, UserQuotaRow } from '@/types'
import { formatDateTime, formatNumber } from '@/utils/format'
import { isFallbackError } from '@/utils/request'
import { mockGrants, mockQuotaTiers, mockUserQuotas, withMock } from '@/utils/mock'

const activeTab = ref<'tiers' | 'users'>('tiers')

const tiers = ref<QuotaTier[]>([])
const tierLoading = ref(false)
const tierDialogVisible = ref(false)
const editingTierId = ref<number | string | null>(null)
const tierFormRef = ref<FormInstance>()
const saving = ref(false)

const tierForm = reactive<QuotaTierRequest>({
  code: '',
  name: '',
  description: '',
  dailyTokenLimit: 100000,
  totalTokenLimit: 1000000,
  sortOrder: 1,
  enabled: true,
})

const tierRules: FormRules = {
  code: [
    { required: true, message: '请输入套餐编码', trigger: 'blur' },
    { pattern: /^[A-Z][A-Z0-9_]{1,31}$/, message: '大写字母开头，可含数字与下划线', trigger: 'blur' },
  ],
  name: [{ required: true, message: '请输入套餐名称', trigger: 'blur' }],
}

const keyword = ref('')
const userQuotas = ref<UserQuotaRow[]>([])
const userLoading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const userDialogVisible = ref(false)
const userTarget = ref<UserQuotaRow | null>(null)
const userForm = reactive<{ tierId: number | string | null; dailyLimit: number | null; totalLimit: number | null }>({
  tierId: null,
  dailyLimit: null,
  totalLimit: null,
})

const grantDialogVisible = ref(false)
const grantTarget = ref<UserQuotaRow | null>(null)
const grantForm = reactive<{ amount: number; reason: string }>({ amount: 100000, reason: '' })

const grantsVisible = ref(false)
const grantsLoading = ref(false)
const grants = ref<GrantRecord[]>([])
const grantPage = ref(1)
const grantTotal = ref(0)
const grantUserId = ref<number | string>('')

function limitText(limit: number | null | undefined): string {
  return !limit ? '不限' : formatNumber(limit)
}

function hasOverride(row: any): boolean {
  return row.tierId != null || row.dailyLimit != null || row.totalLimit != null
}

function mockTiers(): Promise<QuotaTier[]> {
  return Promise.resolve(mockQuotaTiers())
}

async function loadTiers(): Promise<void> {
  tierLoading.value = true
  try {
    const result = await withMock('额度套餐', () => quotaApi.listTiers(), mockTiers)
    tiers.value = result.data
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('套餐列表加载失败')
  } finally {
    tierLoading.value = false
  }
}

function openTierDialog(row?: any): void {
  editingTierId.value = row ? row.id : null
  tierForm.code = row ? row.code : ''
  tierForm.name = row ? row.name : ''
  tierForm.description = row?.description || ''
  tierForm.dailyTokenLimit = row ? row.dailyTokenLimit : 100000
  tierForm.totalTokenLimit = row ? row.totalTokenLimit : 1000000
  tierForm.sortOrder = row?.sortOrder ?? tiers.value.length + 1
  tierForm.enabled = row ? row.enabled !== false : true
  tierDialogVisible.value = true
}

async function submitTier(): Promise<void> {
  if (!tierFormRef.value) return
  const valid = await tierFormRef.value.validate().catch(() => false)
  if (!valid) return
  const payload: QuotaTierRequest = {
    code: tierForm.code,
    name: tierForm.name,
    description: tierForm.description,
    dailyTokenLimit: tierForm.dailyTokenLimit,
    totalTokenLimit: tierForm.totalTokenLimit,
    sortOrder: tierForm.sortOrder,
    enabled: tierForm.enabled,
  }
  saving.value = true
  try {
    const editing = editingTierId.value
    if (editing) {
      await withMock(`保存套餐 #${editing}`, () => quotaApi.updateTier(editing, payload), () => ({ ...payload, id: editing } as QuotaTier))
      ElMessage.success('套餐已更新')
    } else {
      await withMock('新建套餐', () => quotaApi.createTier(payload), () => ({ ...payload, id: Date.now(), builtin: false, code: payload.code ?? '' } as QuotaTier))
      ElMessage.success('套餐已创建')
    }
    tierDialogVisible.value = false
    await loadTiers()
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('套餐保存失败')
  } finally {
    saving.value = false
  }
}

async function removeTier(row: any): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除套餐 ${row.name}？删除后使用该套餐的用户需重新指定。`, '删除套餐', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await withMock(`删除套餐 #${row.id}`, () => quotaApi.deleteTier(row.id), () => undefined)
    ElMessage.success('套餐已删除')
    await loadTiers()
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('删除失败：内置套餐或仍被用户引用时不可删除')
  }
}

async function loadUsers(target = page.value): Promise<void> {
  page.value = target
  userLoading.value = true
  try {
    const params = { page: target, size: size.value, keyword: keyword.value.trim() }
    const result = await withMock('用户额度', () => quotaApi.listUsers(params), () => mockUserQuotas(target, size.value, keyword.value))
    userQuotas.value = result.data.records
    total.value = result.data.total
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('用户额度加载失败')
  } finally {
    userLoading.value = false
  }
}

function openUserDialog(row: any): void {
  userTarget.value = row
  userForm.tierId = row.tierId ?? null
  userForm.dailyLimit = row.dailyLimit ?? null
  userForm.totalLimit = row.totalLimit ?? null
  userDialogVisible.value = true
}

async function submitUserQuota(): Promise<void> {
  const target = userTarget.value
  if (!target) return
  saving.value = true
  try {
    await withMock(
      `覆盖额度 #${target.userId}`,
      () => quotaApi.updateUser(target.userId, { ...userForm }),
      () => ({
        ...target,
        tierId: userForm.tierId,
        dailyLimit: userForm.dailyLimit ?? target.dailyLimit,
        totalLimit: userForm.totalLimit ?? target.totalLimit,
      }),
    )
    ElMessage.success('额度已覆盖')
    userDialogVisible.value = false
    await loadUsers(page.value)
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('额度覆盖失败')
  } finally {
    saving.value = false
  }
}

async function resetUserQuota(row: any): Promise<void> {
  try {
    await ElMessageBox.confirm('清除覆盖后该用户将恢复继承套餐额度，确认继续？', '清除覆盖', { type: 'warning' })
  } catch {
    return
  }
  try {
    await withMock(`清除覆盖 #${row.userId}`, () => quotaApi.resetUser(row.userId), () => undefined)
    ElMessage.success('已恢复继承套餐')
    await loadUsers(page.value)
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('清除覆盖失败')
  }
}

function openGrantDialog(row: any): void {
  grantTarget.value = row
  grantForm.amount = 100000
  grantForm.reason = ''
  grantDialogVisible.value = true
}

async function submitGrant(): Promise<void> {
  const target = grantTarget.value
  if (!target) return
  if (!grantForm.amount || grantForm.amount <= 0) {
    ElMessage.warning('分配数量需大于 0')
    return
  }
  saving.value = true
  try {
    await withMock(
      `分配 Token #${target.userId}`,
      () => quotaApi.grant(target.userId, { amount: grantForm.amount, reason: grantForm.reason }),
      () => undefined,
    )
    ElMessage.success(`已分配 ${formatNumber(grantForm.amount)} Token`)
    grantDialogVisible.value = false
    await loadUsers(page.value)
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('分配失败')
  } finally {
    saving.value = false
  }
}

function openGrants(row: any): void {
  grantUserId.value = row.userId
  grants.value = []
  grantPage.value = 1
  grantTotal.value = 0
  grantsVisible.value = true
  void loadGrants(1)
}

async function loadGrants(target: number): Promise<void> {
  grantPage.value = target
  grantsLoading.value = true
  try {
    const result = await withMock(
      `分配流水 #${grantUserId.value}`,
      () => quotaApi.grants(grantUserId.value, { page: target, size: 10 }),
      () => mockGrants(grantUserId.value, target, 10),
    )
    grants.value = result.data.records
    grantTotal.value = result.data.total
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('分配流水加载失败')
  } finally {
    grantsLoading.value = false
  }
}

async function refreshAll(): Promise<void> {
  await Promise.all([loadTiers(), loadUsers(page.value)])
}

onMounted(() => {
  void loadTiers()
  void loadUsers(1)
})
</script>

<style scoped>
.quota-tabs {
  padding-top: 8px;
}

.user-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--af-primary-light);
  color: var(--af-primary);
  font-weight: 600;
  flex: none;
}

.dialog-user {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--af-border);
  border-radius: var(--af-radius);
}

.mb-12 {
  margin-bottom: 12px;
}

.ml-8 {
  margin-left: 8px;
}

.pager {
  display: flex;
  justify-content: flex-end;
}

.mt-12 {
  margin-top: 12px;
}
</style>
