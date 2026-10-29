<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">知识库</h2>
        <p class="page-desc">上传文档 → 解析分块 → 向量索引，对话中自动检索引用</p>
      </div>
      <div class="toolbar">
        <el-button :icon="Refresh" :loading="loadingDocs" @click="loadDocuments()">刷新</el-button>
        <el-button v-perm="'rag:write'" type="primary" :icon="Plus" @click="openBaseDialog()">新建知识库</el-button>
      </div>
    </div>

    <div class="kb-layout">
      <div class="af-card kb-side">
        <div class="side-head">
          <h3>知识库列表</h3>
          <span class="muted">{{ bases.length }} 个</span>
        </div>
        <div v-loading="loadingBases" class="kb-list">
          <div
            v-for="base in bases"
            :key="base.id"
            class="kb-item"
            :class="{ active: base.id === selectedId }"
            @click="selectBase(base)"
          >
            <div class="kb-item-main">
              <div class="kb-name">{{ base.name }}</div>
              <div class="muted kb-meta">
                {{ base.documentCount ?? 0 }} 文档 · {{ formatNumber(base.chunkCount) }} 分块
              </div>
            </div>
            <el-dropdown v-perm="'rag:write'" trigger="click" @command="(command: string) => handleBaseCommand(command, base)">
              <el-icon class="kb-more" @click.stop><MoreFilled /></el-icon>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="edit">编辑</el-dropdown-item>
                  <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
          <el-empty v-if="!loadingBases && !bases.length" description="暂无知识库" :image-size="70" />
        </div>
      </div>

      <div class="af-card af-card--pad kb-main">
        <template v-if="selected">
          <div class="main-head">
            <div>
              <h3 class="main-title">{{ selected.name }}</h3>
              <p class="muted">{{ selected.description || '暂无描述' }}</p>
            </div>
            <div class="toolbar">
              <el-button :icon="Search" @click="searchVisible = true">检索测试</el-button>
              <el-upload
                v-perm="'rag:write'"
                :show-file-list="false"
                :http-request="doUpload"
                accept=".pdf,.docx,.md,.txt,.html,.csv"
              >
                <el-button v-perm="'rag:write'" type="primary" :icon="Upload" :loading="uploading">上传文档</el-button>
              </el-upload>
            </div>
          </div>

          <div class="muted hint">支持 PDF / Word / Markdown / TXT / HTML / CSV，单文件 ≤ 20MB；上传后异步解析，状态自动轮询。</div>

          <el-table v-loading="loadingDocs" :data="documents" size="small" empty-text="暂无文档，请上传">
            <el-table-column label="文件名" min-width="230">
              <template #default="{ row }">
                <el-icon class="file-icon"><Document /></el-icon>
                <span>{{ row.filename }}</span>
              </template>
            </el-table-column>
            <el-table-column label="大小" width="100">
              <template #default="{ row }">{{ formatFileSize(row.fileSize) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="150">
              <template #default="{ row }">
                <el-tag :type="docStatusType(row.status)" size="small" effect="light">{{ docStatusLabel(row.status) }}</el-tag>
                <span v-if="row.progress !== undefined && row.progress < 100 && isProcessing(row.status)" class="muted progress">
                  {{ row.progress }}%
                </span>
              </template>
            </el-table-column>
            <el-table-column label="分块" width="90">
              <template #default="{ row }">{{ row.chunkCount ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="上传时间" width="170">
              <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button v-perm="'rag:write'" link type="primary" @click="reindex(row)">重建索引</el-button>
                <el-tooltip v-if="row.status === 'FAILED'" :content="row.errorMessage || '解析失败'">
                  <el-button link type="warning">原因</el-button>
                </el-tooltip>
                <el-button v-perm="'rag:write'" link type="danger" @click="removeDocument(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="pager">
            <el-pagination
              v-model:current-page="docPage"
              v-model:page-size="docSize"
              :total="docTotal"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next"
              @current-change="loadDocuments()"
              @size-change="loadDocuments(1)"
            />
          </div>
        </template>
        <el-empty v-else description="请选择左侧知识库" />
      </div>
    </div>

    <!-- 新建 / 编辑知识库 -->
    <el-dialog v-model="baseDialogVisible" :title="editingBaseId ? '编辑知识库' : '新建知识库'" width="480px" destroy-on-close>
      <el-form ref="baseFormRef" :model="baseForm" :rules="baseRules" label-width="90px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="baseForm.name" maxlength="64" show-word-limit placeholder="如：产品手册" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="baseForm.description" type="textarea" :rows="3" maxlength="200" show-word-limit />
        </el-form-item>
        <el-form-item label="向量模型">
          <el-select v-model="baseForm.embeddingModelId" placeholder="使用默认模型" clearable style="width: 100%">
            <el-option v-for="model in models" :key="model.id" :label="model.name" :value="model.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="baseDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingBase" @click="submitBase">保存</el-button>
      </template>
    </el-dialog>

    <!-- 检索测试 -->
    <el-dialog v-model="searchVisible" title="检索测试" width="720px" destroy-on-close>
      <el-form label-width="72px">
        <el-form-item label="查询">
          <el-input v-model="searchForm.query" placeholder="输入自然语言问题" @keyup.enter="doSearch" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="TopK">
              <el-input-number v-model="searchForm.topK" :min="1" :max="20" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="阈值">
              <el-input-number v-model="searchForm.threshold" :min="0" :max="1" :step="0.05" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item>
          <el-button type="primary" :icon="Search" :loading="searching" @click="doSearch">检索</el-button>
        </el-form-item>
      </el-form>
      <div v-loading="searching" class="hits">
        <div v-for="(hit, index) in hits" :key="index" class="hit">
          <div class="hit-head">
            <el-tag size="small" type="info" effect="plain">#{{ index + 1 }}</el-tag>
            <span>{{ hit.filename || `文档 #${hit.documentId}` }}</span>
            <span class="muted">chunk {{ hit.chunkIndex }} · score {{ hit.score?.toFixed(4) }}</span>
          </div>
          <div class="hit-content">{{ hit.content }}</div>
        </div>
        <el-empty v-if="!searching && !hits.length" description="暂无检索结果" :image-size="60" />
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules, type UploadRequestOptions } from 'element-plus'
import { Document, MoreFilled, Plus, Refresh, Search, Upload } from '@element-plus/icons-vue'
import { knowledgeApi } from '@/api/knowledge'
import { modelsApi } from '@/api/models'
import type {
  DocumentStatus,
  KbDocument,
  KnowledgeBase,
  KnowledgeBaseRequest,
  ModelConfig,
  SearchHit,
} from '@/types'
import { formatDateTime, formatFileSize, formatNumber } from '@/utils/format'
import { isFallbackError } from '@/utils/request'
import {
  mockDocuments,
  mockKnowledgeBases,
  mockModels,
  mockSearchHits,
  withMock,
} from '@/utils/mock'

const loadingBases = ref(false)
const loadingDocs = ref(false)
const uploading = ref(false)
const bases = ref<KnowledgeBase[]>([])
const selectedId = ref<number | string | null>(null)
const documents = ref<KbDocument[]>([])
const models = ref<ModelConfig[]>([])
const docPage = ref(1)
const docSize = ref(20)
const docTotal = ref(0)

const selected = computed(() => bases.value.find((base) => base.id === selectedId.value) ?? null)

const baseDialogVisible = ref(false)
const editingBaseId = ref<number | string | null>(null)
const savingBase = ref(false)
const baseFormRef = ref<FormInstance>()
const baseForm = reactive<KnowledgeBaseRequest & { embeddingModelId?: number | string | null }>({
  name: '',
  description: '',
  embeddingModelId: undefined,
})
const baseRules: FormRules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
}

const searchVisible = ref(false)
const searching = ref(false)
const hits = ref<SearchHit[]>([])
const searchForm = reactive({ query: '', topK: 5, threshold: 0.2 })

let pollTimer = 0

function mockBasesPage() {
  const list = mockKnowledgeBases()
  return { records: list, total: list.length, page: 1, size: 50 }
}

async function loadBases(): Promise<void> {
  loadingBases.value = true
  try {
    const result = await withMock('知识库列表', () => knowledgeApi.listBases({ page: 1, size: 50 }), () => mockBasesPage())
    bases.value = result.data.records
    if (!selectedId.value && bases.value.length) {
      selectBase(bases.value[0])
    }
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('知识库加载失败')
  } finally {
    loadingBases.value = false
  }
}

function selectBase(base: KnowledgeBase): void {
  selectedId.value = base.id
  void loadDocuments(1)
}

function mockDocsPage() {
  const list = mockDocuments(selectedId.value ?? 1)
  return { records: list, total: list.length, page: 1, size: 20 }
}

async function loadDocuments(target = docPage.value, silent = false): Promise<void> {
  if (!selectedId.value) return
  docPage.value = target
  if (!silent) loadingDocs.value = true
  try {
    const result = await withMock(
      '文档列表',
      () => knowledgeApi.listDocuments(selectedId.value!, { page: docPage.value, size: docSize.value }, silent),
      () => mockDocsPage(),
    )
    documents.value = result.data.records
    docTotal.value = result.data.total
  } catch (error) {
    if (!silent && !isFallbackError(error)) ElMessage.error('文档列表加载失败')
  } finally {
    loadingDocs.value = false
  }
}

function isProcessing(status: DocumentStatus | string): boolean {
  return status === 'PENDING' || status === 'PARSING' || status === 'INDEXING'
}

function docStatusLabel(status: DocumentStatus | string): string {
  switch (status) {
    case 'READY':
      return '已就绪'
    case 'PENDING':
      return '排队中'
    case 'PARSING':
      return '解析中'
    case 'INDEXING':
      return '索引中'
    case 'FAILED':
      return '失败'
    default:
      return String(status)
  }
}

function docStatusType(status: DocumentStatus | string): 'success' | 'info' | 'warning' | 'danger' {
  switch (status) {
    case 'READY':
      return 'success'
    case 'FAILED':
      return 'danger'
    default:
      return 'warning'
  }
}

/** 状态轮询：存在未完成文档时每 3s 静默刷新 */
function ensurePolling(): void {
  window.clearInterval(pollTimer)
  pollTimer = window.setInterval(() => {
    if (documents.value.some((doc) => isProcessing(doc.status))) {
      void loadDocuments(undefined, true)
    }
  }, 3000)
}

function openBaseDialog(base?: KnowledgeBase): void {
  editingBaseId.value = base?.id ?? null
  baseForm.name = base?.name ?? ''
  baseForm.description = base?.description ?? ''
  baseForm.embeddingModelId = base?.embeddingModelId
  baseDialogVisible.value = true
}

async function submitBase(): Promise<void> {
  if (!baseFormRef.value) return
  const valid = await baseFormRef.value.validate().catch(() => false)
  if (!valid) return
  savingBase.value = true
  try {
    const payload: KnowledgeBaseRequest = {
      name: baseForm.name,
      description: baseForm.description,
      embeddingModelId: baseForm.embeddingModelId,
    }
    if (editingBaseId.value !== null) {
      await withMock(`编辑知识库 #${editingBaseId.value}`, () => knowledgeApi.updateBase(editingBaseId.value!, payload), () => ({ id: editingBaseId.value!, name: payload.name }))
    } else {
      await withMock('新建知识库', () => knowledgeApi.createBase(payload), () => ({ id: Date.now(), name: payload.name }))
    }
    ElMessage.success('保存成功')
    baseDialogVisible.value = false
    await loadBases()
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('保存失败')
  } finally {
    savingBase.value = false
  }
}

function handleBaseCommand(command: string, base: KnowledgeBase): void {
  if (command === 'edit') {
    openBaseDialog(base)
  } else if (command === 'delete') {
    void removeBase(base)
  }
}

async function removeBase(base: KnowledgeBase): Promise<void> {
  try {
    await ElMessageBox.confirm(`删除知识库「${base.name}」将级联删除其文档与向量，确认继续？`, '危险操作', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await withMock(`删除知识库 #${base.id}`, () => knowledgeApi.removeBase(base.id), () => undefined)
    ElMessage.success('已删除')
    if (selectedId.value === base.id) selectedId.value = null
    await loadBases()
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('删除失败')
  }
}

async function doUpload(options: UploadRequestOptions): Promise<void> {
  if (!selectedId.value) return
  uploading.value = true
  try {
    const file = options.file as File
    if (file.size > 20 * 1024 * 1024) {
      ElMessage.warning('单文件不能超过 20MB')
      return
    }
    const result = await withMock(
      '上传文档',
      () => knowledgeApi.uploadDocument(selectedId.value!, file),
      () => ({ documentId: Date.now(), status: 'PENDING' as DocumentStatus }),
    )
    ElMessage.success(`上传成功（${result.data.status}），解析完成后自动刷新`)
    await loadDocuments(1)
    ensurePolling()
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('上传失败')
  } finally {
    uploading.value = false
  }
}

// el-table 作用域插槽的 row 类型为 DefaultRow，入口处放宽类型，函数内部按 KbDocument 使用
async function reindex(doc: any): Promise<void> {
  try {
    await withMock(`重建索引 #${doc.id}`, () => knowledgeApi.reindex(doc.id), () => ({ documentId: doc.id, status: 'PENDING' as DocumentStatus }))
    ElMessage.success('已提交重建索引')
    await loadDocuments(undefined, true)
    ensurePolling()
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('重建失败')
  }
}

async function removeDocument(doc: any): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除文档「${doc.filename}」？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await withMock(`删除文档 #${doc.id}`, () => knowledgeApi.removeDocument(doc.id), () => undefined)
    ElMessage.success('已删除')
    await loadDocuments()
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('删除失败')
  }
}

async function doSearch(): Promise<void> {
  if (!selectedId.value || !searchForm.query.trim()) {
    ElMessage.warning('请输入查询内容')
    return
  }
  searching.value = true
  try {
    const result = await withMock(
      '检索测试',
      () => knowledgeApi.search(selectedId.value!, { ...searchForm }),
      () => mockSearchHits(searchForm.query),
    )
    hits.value = result.data
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('检索失败')
    hits.value = []
  } finally {
    searching.value = false
  }
}

onMounted(async () => {
  const modelResult = await withMock('模型列表', () => modelsApi.list(), () => mockModels()).catch(() => null)
  if (modelResult) models.value = modelResult.data
  await loadBases()
  ensurePolling()
})

onBeforeUnmount(() => window.clearInterval(pollTimer))
</script>

<style scoped>
.kb-layout {
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.kb-side {
  padding: 14px;
}

.side-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.side-head h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}

.kb-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 620px;
  overflow-y: auto;
}

.kb-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid transparent;
  cursor: pointer;
  transition: background 0.15s ease;
}

.kb-item:hover {
  background: #f8fafc;
}

.kb-item.active {
  background: #eff6ff;
  border-color: #bfdbfe;
}

.kb-name {
  font-weight: 600;
  font-size: 13.5px;
  color: #0f172a;
}

.kb-meta {
  font-size: 12px;
  margin-top: 2px;
}

.kb-more {
  color: #94a3b8;
  cursor: pointer;
}

.main-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.main-title {
  margin: 0 0 2px;
  font-size: 16px;
}

.main-head p {
  margin: 0;
  font-size: 12.5px;
}

.hint {
  font-size: 12px;
  margin: 8px 0 12px;
}

.file-icon {
  color: #64748b;
  margin-right: 6px;
  vertical-align: -2px;
}

.progress {
  font-size: 12px;
  margin-left: 4px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

.hits {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 420px;
  overflow-y: auto;
}

.hit {
  border: 1px solid var(--af-border);
  border-radius: 8px;
  padding: 10px 12px;
  background: #fafbfc;
}

.hit-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: #334155;
  margin-bottom: 6px;
}

.hit-content {
  font-size: 13px;
  color: #475569;
  white-space: pre-wrap;
  line-height: 1.6;
}

@media (max-width: 1000px) {
  .kb-layout {
    grid-template-columns: 1fr;
  }
}
</style>
