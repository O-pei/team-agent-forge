<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">角色权限</h2>
        <p class="page-desc">基于 RBAC 的角色-权限绑定 · 权限码与后端接口一一对应，菜单与按钮按权限过滤</p>
      </div>
      <div class="toolbar">
        <el-button :icon="Refresh" :loading="loading" @click="loadAll">刷新</el-button>
        <el-button v-perm="'role:write'" type="primary" :icon="Plus" @click="openCreate">新建角色</el-button>
      </div>
    </div>

    <div class="af-card af-card--pad">
      <el-table v-loading="loading" :data="roles" empty-text="暂无角色">
        <el-table-column label="角色" min-width="200">
          <template #default="{ row }">
            <div class="role-cell">
              <div class="role-icon">
                <el-icon><Lock /></el-icon>
              </div>
              <div>
                <div class="role-name">
                  {{ row.name }}
                  <el-tag v-if="row.builtIn" size="small" effect="plain" type="info">内置</el-tag>
                </div>
                <div class="muted mono">{{ row.code }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="描述" min-width="200">
          <template #default="{ row }">{{ row.description || '—' }}</template>
        </el-table-column>
        <el-table-column label="权限数" width="100">
          <template #default="{ row }">
            <el-tag size="small" effect="light" :type="permCount(row) ? 'primary' : 'info'">{{ permCount(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="权限码" min-width="300">
          <template #default="{ row }">
            <template v-if="(row.permissions || []).length">
              <el-tag v-for="code in (row.permissions || []).slice(0, 6)" :key="code" size="small" effect="plain" class="perm-tag">
                {{ code }}
              </el-tag>
              <span v-if="(row.permissions || []).length > 6" class="muted">+{{ (row.permissions || []).length - 6 }}</span>
            </template>
            <span v-else class="muted">未展示（可在编辑中查看）</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button v-perm="'role:write'" link type="primary" @click="openEdit(row)">配置权限</el-button>
            <el-button v-perm="'role:write'" link type="danger" :disabled="row.builtIn" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p class="muted tip">内置角色（SUPER_ADMIN / ADMIN / USER）不可删除；SUPER_ADMIN 始终拥有全部权限。</p>
    </div>

    <el-dialog v-model="editorVisible" :title="editingId !== null ? '配置角色权限' : '新建角色'" width="720px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <div class="form-grid">
          <el-form-item label="角色名" prop="name">
            <el-input v-model="form.name" maxlength="32" placeholder="如：审计员" />
          </el-form-item>
          <el-form-item label="角色码" prop="code">
            <el-input v-model="form.code" :disabled="editingId !== null" placeholder="如：AUDITOR" />
          </el-form-item>
        </div>
        <el-form-item label="描述">
          <el-input v-model="form.description" maxlength="128" />
        </el-form-item>
        <el-form-item label="权限">
          <div v-loading="treeLoading" class="perm-panel">
            <div class="perm-actions">
              <el-button link type="primary" @click="checkAll(true)">全选</el-button>
              <el-button link @click="checkAll(false)">清空</el-button>
              <span class="muted">已选 {{ checkedIds.length }} 项</span>
            </div>
            <el-tree
              ref="treeRef"
              :data="permissionTree"
              node-key="id"
              show-checkbox
              default-expand-all
              :props="{ label: 'name', children: 'children' }"
              :empty-text="treeLoading ? '加载中…' : '暂无权限数据'"
              @check="onCheck"
            >
              <template #default="{ data }">
                <span class="tree-node">
                  <span>{{ data.name }}</span>
                  <span class="muted mono">{{ data.code }}</span>
                </span>
              </template>
            </el-tree>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { ElTree } from 'element-plus'
import { Lock, Plus, Refresh } from '@element-plus/icons-vue'
import { rolesApi } from '@/api/roles'
import type { PermissionNode, RoleInfo, RoleRequest } from '@/types'
import { isFallbackError } from '@/utils/request'
import { mockPermissionTree, mockRoles, withMock } from '@/utils/mock'

const loading = ref(false)
const saving = ref(false)
const roles = ref<RoleInfo[]>([])
const permissionTree = ref<PermissionNode[]>([])
const treeLoading = ref(false)
const treeRef = ref<InstanceType<typeof ElTree>>()

const editorVisible = ref(false)
const editingId = ref<number | string | null>(null)
const formRef = ref<FormInstance>()
const form = reactive({ name: '', code: '', description: '' })
const checkedIds = ref<Array<number | string>>([])
const rules: FormRules = {
  name: [{ required: true, message: '请输入角色名', trigger: 'blur' }],
  code: [
    { required: true, message: '请输入角色码', trigger: 'blur' },
    { pattern: /^[A-Z][A-Z0-9_]{1,31}$/, message: '大写字母开头，仅字母数字下划线', trigger: 'blur' },
  ],
}

// el-table 作用域插槽的 row 类型为 DefaultRow，入口处放宽类型，函数内部按 RoleInfo 使用
function permCount(row: any): number {
  return (row.permissionIds || row.permissions || []).length
}

async function loadRoles(): Promise<void> {
  loading.value = true
  try {
    const result = await withMock('角色列表', () => rolesApi.list(), () => mockRoles())
    roles.value = result.data
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('角色列表加载失败')
  } finally {
    loading.value = false
  }
}

async function loadPermissions(force = false): Promise<void> {
  if (!force && permissionTree.value.length) return
  treeLoading.value = true
  try {
    const result = await withMock('权限树', () => rolesApi.permissions(), () => mockPermissionTree())
    permissionTree.value = result.data
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('权限树加载失败')
  } finally {
    treeLoading.value = false
  }
}

async function loadAll(): Promise<void> {
  await Promise.all([loadRoles(), loadPermissions(true)])
}

function onCheck(): void {
  const tree = treeRef.value
  if (!tree) return
  checkedIds.value = [...tree.getCheckedKeys(), ...tree.getHalfCheckedKeys()] as Array<number | string>
}

function checkAll(checked: boolean): void {
  const tree = treeRef.value
  if (!tree) return
  tree.setCheckedKeys(checked ? collectLeafIds(permissionTree.value) : [])
  onCheck()
}

function collectLeafIds(nodes: PermissionNode[]): Array<number | string> {
  const ids: Array<number | string> = []
  const walk = (list: PermissionNode[]) => {
    list.forEach((node) => {
      if (node.children && node.children.length) walk(node.children)
      else ids.push(node.id)
    })
  }
  walk(nodes)
  return ids
}

function openCreate(): void {
  editingId.value = null
  form.name = ''
  form.code = ''
  form.description = ''
  checkedIds.value = []
  editorVisible.value = true
  void loadPermissions()
  nextTick(() => treeRef.value?.setCheckedKeys([]))
}

function openEdit(row: any): void {
  editingId.value = row.id
  form.name = row.name
  form.code = row.code
  form.description = row.description || ''
  checkedIds.value = [...(row.permissionIds || [])]
  editorVisible.value = true
  void loadPermissions().then(() => {
    nextTick(() => {
      treeRef.value?.setCheckedKeys(checkedIds.value)
      onCheck()
    })
  })
}

async function submit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  const tree = treeRef.value
  const permissionIds = tree
    ? ([...tree.getCheckedKeys(), ...tree.getHalfCheckedKeys()] as Array<number | string>)
    : checkedIds.value
  const payload: RoleRequest = {
    code: editingId.value === null ? form.code : undefined,
    name: form.name,
    description: form.description || undefined,
    permissionIds,
  }
  saving.value = true
  try {
    if (editingId.value !== null) {
      await withMock(`配置角色 #${editingId.value}`, () => rolesApi.update(editingId.value!, payload), () => ({
        id: editingId.value!,
        code: form.code,
        name: form.name,
        description: form.description,
        permissionIds,
      }))
    } else {
      await withMock('新建角色', () => rolesApi.create(payload), () => ({
        id: Date.now(),
        code: form.code,
        name: form.name,
        description: form.description,
        permissionIds,
      }))
    }
    ElMessage.success('保存成功')
    editorVisible.value = false
    await loadRoles()
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row: any): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除角色「${row.name}」？该角色下的用户将失去对应权限。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await withMock(`删除角色 #${row.id}`, () => rolesApi.remove(row.id), () => undefined)
    ElMessage.success('已删除')
    await loadRoles()
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('删除失败')
  }
}

onMounted(loadAll)
</script>

<style scoped>
.role-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.role-icon {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: #eff6ff;
  color: #2563eb;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.role-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: #0f172a;
}

.perm-tag {
  margin: 0 4px 4px 0;
}

.tip {
  font-size: 12px;
  margin: 12px 0 0;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 12px;
}

.perm-panel {
  width: 100%;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 8px 12px;
  max-height: 340px;
  overflow: auto;
}

.perm-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 6px;
  border-bottom: 1px dashed #e5e7eb;
  margin-bottom: 6px;
}

.tree-node {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}
</style>
