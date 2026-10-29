<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">Agent 管理</h2>
        <p class="page-desc">创建、调试、发布 Agent · 版本不可变，发布后可追溯</p>
      </div>
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="搜索 Agent 名称" clearable style="width: 220px" :prefix-icon="Search" @keyup.enter="load(1)" @clear="load(1)" />
        <el-button :icon="Refresh" :loading="loading" @click="load()">刷新</el-button>
        <el-button v-perm="'agent:write'" type="primary" :icon="Plus" @click="openCreate">新建 Agent</el-button>
      </div>
    </div>

    <div class="af-card af-card--pad">
      <el-table v-loading="loading" :data="agents" empty-text="暂无 Agent">
        <el-table-column label="名称" min-width="200">
          <template #default="{ row }">
            <div class="agent-name">{{ row.name }}</div>
            <div class="muted agent-desc">{{ row.description || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="120">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.type || 'REACT' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="模型" width="150">
          <template #default="{ row }">{{ modelName(row.modelId) }}</template>
        </el-table-column>
        <el-table-column label="工具" width="110">
          <template #default="{ row }">{{ row.tools?.length ?? 0 }} 个</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small" effect="light">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="版本" width="110">
          <template #default="{ row }">
            <span class="mono">v{{ row.publishedVersion ?? row.version ?? '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt || row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goChat(row)">对话</el-button>
            <el-button v-perm="'agent:write'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-perm="'agent:write'" link type="success" :disabled="row.status === 'PUBLISHED'" @click="openPublish(row)">
              发布
            </el-button>
            <el-button link @click="openVersions(row)">版本</el-button>
            <el-button v-perm="'agent:write'" link type="danger" @click="remove(row)">删除</el-button>
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
    <el-dialog v-model="editorVisible" :title="editingId ? '编辑 Agent（生成新版本）' : '新建 Agent'" width="680px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="64" show-word-limit placeholder="如：客服助手" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" maxlength="200" show-word-limit placeholder="一句话说明用途" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="类型" prop="type">
              <el-select v-model="form.type" style="width: 100%">
                <el-option label="ReAct" value="REACT" />
                <el-option label="Plan & Execute" value="PLAN_EXECUTE" />
                <el-option label="对话" value="CHAT" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="模型">
              <el-select v-model="form.modelId" placeholder="选择模型" clearable style="width: 100%">
                <el-option v-for="model in models" :key="model.id" :label="`${model.name}（${model.modelName}）`" :value="model.id" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="系统提示">
          <el-input v-model="form.systemPrompt" type="textarea" :rows="4" maxlength="4000" show-word-limit placeholder="定义 Agent 的角色、边界与输出格式" />
        </el-form-item>
        <el-form-item label="工具">
          <el-select v-model="form.tools" multiple collapse-tags collapse-tags-tooltip style="width: 100%" placeholder="选择可调用的工具">
            <el-option v-for="tool in tools" :key="tool.name" :label="tool.name" :value="tool.name">
              <span>{{ tool.name }}</span>
              <span class="muted option-desc">{{ tool.description }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="Temperature">
              <el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="最大步数">
              <el-input-number v-model="form.maxSteps" :min="1" :max="20" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 发布 -->
    <el-dialog v-model="publishVisible" title="发布 Agent" width="480px" destroy-on-close>
      <p class="muted">发布后将生成不可变版本，对话默认使用已发布版本。</p>
      <el-form label-width="90px">
        <el-form-item label="版本号">
          <el-input-number v-model="publishForm.version" :min="1" :step="1" style="width: 200px" />
          <span class="muted" style="margin-left: 10px">当前 v{{ publishing?.version ?? '-' }}</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="publishVisible = false">取消</el-button>
        <el-button type="success" :loading="saving" @click="submitPublish">确认发布</el-button>
      </template>
    </el-dialog>

    <!-- 版本历史 -->
    <el-dialog v-model="versionsVisible" title="版本历史" width="560px" destroy-on-close>
      <el-table v-loading="versionsLoading" :data="versions" size="small" empty-text="暂无版本记录">
        <el-table-column label="版本" width="100">
          <template #default="{ row }">
            <span class="mono">v{{ row.version }}</span>
            <el-tag v-if="row.published" type="success" size="small" effect="light" style="margin-left: 6px">当前</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发布时间" min-width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="发布人" min-width="120">
          <template #default="{ row }">{{ row.createdBy || '—' }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { agentsApi } from '@/api/agents'
import { modelsApi } from '@/api/models'
import type { AgentInfo, AgentRequest, AgentTool, AgentVersionInfo, ModelConfig } from '@/types'
import { formatDateTime } from '@/utils/format'
import { isFallbackError } from '@/utils/request'
import {
  mockAgents,
  mockAgent,
  mockModels,
  mockTools,
  withMock,
} from '@/utils/mock'

const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const agents = ref<AgentInfo[]>([])
const models = ref<ModelConfig[]>([])
const tools = ref<AgentTool[]>([])
const keyword = ref('')
const page = ref(1)
const size = ref(10)
const total = ref(0)

const editorVisible = ref(false)
const editingId = ref<number | string | null>(null)
const formRef = ref<FormInstance>()
const form = reactive<AgentRequest>({
  name: '',
  description: '',
  type: 'REACT',
  modelId: undefined,
  systemPrompt: '',
  tools: [],
  temperature: 0.7,
  maxSteps: 6,
})
const rules: FormRules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择类型', trigger: 'change' }],
}

const publishVisible = ref(false)
const publishing = ref<AgentInfo | null>(null)
const publishForm = reactive({ version: 1 })

const versionsVisible = ref(false)
const versionsLoading = ref(false)
const versions = ref<AgentVersionInfo[]>([])

function mockPage(keywordValue: string) {
  const all = mockAgents()
  const filtered = keywordValue
    ? all.filter((agent) => agent.name.toLowerCase().includes(keywordValue.toLowerCase()))
    : all
  return { records: filtered, total: filtered.length, page: 1, size: 10 }
}

async function load(target = page.value): Promise<void> {
  page.value = target
  loading.value = true
  try {
    const result = await withMock(
      'Agent 列表',
      () => agentsApi.list({ page: page.value, size: size.value, keyword: keyword.value || undefined }),
      () => mockPage(keyword.value),
    )
    agents.value = result.data.records
    total.value = result.data.total
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('Agent 列表加载失败')
  } finally {
    loading.value = false
  }
}

function modelName(modelId?: number | string): string {
  if (modelId === undefined || modelId === null) return '—'
  return models.value.find((model) => model.id === modelId)?.name || `#${modelId}`
}

function statusLabel(status?: string): string {
  if (status === 'PUBLISHED') return '已发布'
  if (status === 'ARCHIVED') return '已归档'
  return '草稿'
}

function statusType(status?: string): 'success' | 'info' | 'warning' {
  if (status === 'PUBLISHED') return 'success'
  if (status === 'ARCHIVED') return 'info'
  return 'warning'
}

function resetForm(): void {
  editingId.value = null
  form.name = ''
  form.description = ''
  form.type = 'REACT'
  form.modelId = undefined
  form.systemPrompt = ''
  form.tools = []
  form.temperature = 0.7
  form.maxSteps = 6
}

function openCreate(): void {
  resetForm()
  editorVisible.value = true
}

// el-table 作用域插槽的 row 类型为 DefaultRow，入口处放宽类型，函数内部按 AgentInfo 使用
function openEdit(row: any): void {
  resetForm()
  editingId.value = row.id
  form.name = row.name
  form.description = row.description || ''
  form.type = row.type || 'REACT'
  form.modelId = row.modelId
  form.systemPrompt = row.systemPrompt || ''
  form.tools = [...(row.tools || [])]
  form.temperature = row.temperature ?? 0.7
  form.maxSteps = row.maxSteps ?? 6
  editorVisible.value = true
}

async function submit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (editingId.value !== null) {
      await withMock(`编辑 Agent #${editingId.value}`, () => agentsApi.update(editingId.value!, { ...form }), () => mockAgent(editingId.value!))
    } else {
      await withMock('新建 Agent', () => agentsApi.create({ ...form }), () => mockAgent(Date.now()))
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

function openPublish(row: any): void {
  publishing.value = row
  publishForm.version = (row.version ?? 0) + 1
  publishVisible.value = true
}

async function submitPublish(): Promise<void> {
  if (!publishing.value) return
  saving.value = true
  try {
    await withMock(
      `发布 Agent #${publishing.value.id}`,
      () => agentsApi.publish(publishing.value!.id, publishForm.version),
      () => ({ ...mockAgent(publishing.value!.id), status: 'PUBLISHED', publishedVersion: publishForm.version }),
    )
    ElMessage.success('发布成功')
    publishVisible.value = false
    await load()
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('发布失败')
  } finally {
    saving.value = false
  }
}

async function openVersions(row: any): Promise<void> {
  versionsVisible.value = true
  versions.value = []
  versionsLoading.value = true
  try {
    const result = await withMock(`Agent 版本 #${row.id}`, () => agentsApi.versions(row.id), () => [
      { version: row.version ?? 1, published: true, createdAt: row.updatedAt || row.createdAt, createdBy: 'admin' },
      { version: Math.max(1, (row.version ?? 1) - 1), published: false, createdAt: row.createdAt, createdBy: 'admin' },
    ])
    versions.value = result.data
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('版本加载失败')
  } finally {
    versionsLoading.value = false
  }
}

async function remove(row: any): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除 Agent「${row.name}」？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await withMock(`删除 Agent #${row.id}`, () => agentsApi.remove(row.id), () => undefined)
    ElMessage.success('已删除')
    await load()
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('删除失败')
  }
}

function goChat(row: any): void {
  router.push({ path: '/chat', query: { agentId: String(row.id) } })
}

onMounted(async () => {
  const [modelResult, toolResult] = await Promise.allSettled([
    withMock('模型列表', () => modelsApi.list(), () => mockModels()),
    withMock('工具列表', () => agentsApi.tools(), () => mockTools()),
  ])
  if (modelResult.status === 'fulfilled') models.value = modelResult.value.data
  if (toolResult.status === 'fulfilled') tools.value = toolResult.value.data
  await load(1)
})
</script>

<style scoped>
.agent-name {
  font-weight: 600;
  color: #0f172a;
}

.agent-desc {
  font-size: 12px;
  margin-top: 2px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

.option-desc {
  font-size: 12px;
  margin-left: 8px;
}
</style>
