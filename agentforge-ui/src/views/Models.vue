<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">模型配置</h2>
        <p class="page-desc">接入 DashScope / OpenAI 兼容模型 · 密钥 AES-GCM 加密存储，仅展示掩码</p>
      </div>
      <div class="toolbar">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button v-perm="'model:write'" type="primary" :icon="Plus" @click="openCreate">新增模型</el-button>
      </div>
    </div>

    <div class="af-card af-card--pad">
      <el-table v-loading="loading" :data="models" empty-text="暂无模型配置">
        <el-table-column label="名称" min-width="160">
          <template #default="{ row }">
            <div class="model-name">
              {{ row.name }}
              <el-tag v-if="row.defaultModel" type="success" size="small" effect="light">默认</el-tag>
            </div>
            <div class="muted">{{ row.modelName }}</div>
          </template>
        </el-table-column>
        <el-table-column label="提供商" width="170">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ providerLabel(row.provider) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Base URL" min-width="220">
          <template #default="{ row }"><span class="mono">{{ row.baseUrl || '（默认）' }}</span></template>
        </el-table-column>
        <el-table-column label="API Key" width="150">
          <template #default="{ row }"><span class="mono">{{ row.apiKeyMasked || '（未设置）' }}</span></template>
        </el-table-column>
        <el-table-column label="启用" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small" effect="light">
              {{ row.enabled ? '已启用' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt || row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button v-perm="'model:write'" link type="success" :loading="testingId === row.id" @click="test(row)">测试</el-button>
            <el-button v-perm="'model:write'" link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button v-perm="'model:write'" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p class="muted tip">
        提示：默认模型用于 Agent 未显式指定模型时的调用；密钥保存后不可回显，编辑时留空表示不修改。
      </p>
    </div>

    <el-dialog v-model="editorVisible" :title="editingId ? '编辑模型' : '新增模型'" width="560px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="64" show-word-limit placeholder="如：qwen-plus（业务）" />
        </el-form-item>
        <el-form-item label="提供商" prop="provider">
          <el-select v-model="form.provider" style="width: 100%">
            <el-option label="DashScope（阿里百炼）" value="DASHSCOPE" />
            <el-option label="OpenAI 兼容" value="OPENAI_COMPATIBLE" />
            <el-option label="Mock（本地演示）" value="MOCK" />
          </el-select>
        </el-form-item>
        <el-form-item label="模型名" prop="modelName">
          <el-input v-model="form.modelName" placeholder="如：qwen-plus / gpt-4o-mini" />
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input v-model="form.baseUrl" placeholder="留空使用官方默认地址" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password :placeholder="editingId ? '留空表示不修改' : 'sk-...'" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
        <el-form-item label="设为默认">
          <el-switch v-model="form.defaultModel" />
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
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { modelsApi } from '@/api/models'
import type { ModelConfig, ModelProvider, ModelRequest } from '@/types'
import { formatDateTime } from '@/utils/format'
import { isFallbackError } from '@/utils/request'
import { mockModels, withMock } from '@/utils/mock'

const loading = ref(false)
const saving = ref(false)
const testingId = ref<number | string | null>(null)
const models = ref<ModelConfig[]>([])

const editorVisible = ref(false)
const editingId = ref<number | string | null>(null)
const formRef = ref<FormInstance>()
const form = reactive<ModelRequest>({
  name: '',
  provider: 'DASHSCOPE',
  modelName: '',
  baseUrl: '',
  apiKey: '',
  enabled: true,
  defaultModel: false,
})
const rules: FormRules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  provider: [{ required: true, message: '请选择提供商', trigger: 'change' }],
  modelName: [{ required: true, message: '请输入模型名', trigger: 'blur' }],
}

function providerLabel(provider: ModelProvider | string): string {
  if (provider === 'DASHSCOPE') return 'DashScope'
  if (provider === 'OPENAI_COMPATIBLE') return 'OpenAI 兼容'
  if (provider === 'MOCK') return 'Mock'
  return String(provider)
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await withMock('模型列表', () => modelsApi.list(), () => mockModels())
    models.value = result.data
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('模型列表加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editingId.value = null
  form.name = ''
  form.provider = 'DASHSCOPE'
  form.modelName = ''
  form.baseUrl = ''
  form.apiKey = ''
  form.enabled = true
  form.defaultModel = false
  editorVisible.value = true
}

// el-table 作用域插槽的 row 类型为 DefaultRow，入口处放宽类型，函数内部按 ModelConfig 使用
function openEdit(row: any): void {
  editingId.value = row.id
  form.name = row.name
  form.provider = row.provider
  form.modelName = row.modelName
  form.baseUrl = row.baseUrl || ''
  form.apiKey = ''
  form.enabled = row.enabled
  form.defaultModel = row.defaultModel
  editorVisible.value = true
}

async function submit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const payload: ModelRequest = { ...form, apiKey: form.apiKey || undefined }
    if (editingId.value !== null) {
      await withMock(`编辑模型 #${editingId.value}`, () => modelsApi.update(editingId.value!, payload), () => ({
        id: editingId.value!,
        apiKeyMasked: 'sk-****demo',
        ...payload,
      }))
    } else {
      await withMock('新增模型', () => modelsApi.create(payload), () => ({
        id: Date.now(),
        apiKeyMasked: 'sk-****demo',
        ...payload,
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

async function test(row: any): Promise<void> {
  testingId.value = row.id
  try {
    const result = await withMock(
      `模型连通性 #${row.id}`,
      () => modelsApi.test(row.id),
      () => ({ success: true, latencyMs: 168, echo: 'pong', message: 'OK' }),
    )
    const value = result.data
    if (value.success) ElMessage.success(`连通成功，延迟 ${value.latencyMs}ms`)
    else ElMessage.warning(`连通失败：${value.message || '未知错误'}`)
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('测试失败')
  } finally {
    testingId.value = null
  }
}

async function remove(row: any): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除模型「${row.name}」？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await withMock(`删除模型 #${row.id}`, () => modelsApi.remove(row.id), () => undefined)
    ElMessage.success('已删除')
    await load()
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('删除失败')
  }
}

onMounted(load)
</script>

<style scoped>
.model-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: #0f172a;
}

.tip {
  font-size: 12px;
  margin: 12px 0 0;
}
</style>
