<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">数据源</h2>
        <p class="page-desc">只读 SQL 查询 · 连接测试、表结构浏览、查询日志</p>
      </div>
      <div class="toolbar">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button v-perm="'datasource:write'" type="primary" :icon="Plus" @click="openCreate">新建数据源</el-button>
      </div>
    </div>

    <div class="af-card af-card--pad">
      <el-table v-loading="loading" :data="datasources" empty-text="暂无数据源">
        <el-table-column label="名称" min-width="150">
          <template #default="{ row }">
            <div class="ds-name">{{ row.name }}</div>
            <div class="muted">{{ row.username || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="120">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.type }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="JDBC URL" min-width="260">
          <template #default="{ row }">
            <span class="mono jdbc">{{ row.jdbcUrl }}</span>
          </template>
        </el-table-column>
        <el-table-column label="连接池" width="90">
          <template #default="{ row }">{{ row.maxPoolSize ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.status" :type="row.status === 'UP' ? 'success' : 'danger'" size="small" effect="light">
              {{ row.status === 'UP' ? '可用' : '不可用' }}
            </el-tag>
            <span v-else class="muted">未检测</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button link type="success" :loading="testingId === row.id" @click="test(row)">测试连接</el-button>
            <el-button link type="primary" @click="openSchema(row)">表结构</el-button>
            <el-button v-perm="'datasource:query'" link type="primary" @click="openQuery(row)">SQL 查询</el-button>
            <el-button v-perm="'datasource:write'" link @click="openEdit(row)">编辑</el-button>
            <el-button v-perm="'datasource:write'" link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新建 / 编辑 -->
    <el-dialog v-model="editorVisible" :title="editingId ? '编辑数据源' : '新建数据源'" width="560px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" maxlength="64" show-word-limit placeholder="如：业务库（只读）" />
        </el-form-item>
        <el-form-item label="类型" prop="type">
          <el-radio-group v-model="form.type">
            <el-radio-button value="MYSQL">MySQL</el-radio-button>
            <el-radio-button value="POSTGRESQL">PostgreSQL</el-radio-button>
            <el-radio-button value="H2">H2</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="JDBC URL" prop="jdbcUrl">
          <el-input v-model="form.jdbcUrl" placeholder="jdbc:mysql://127.0.0.1:3306/demo?useSSL=false" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="用户名" prop="username">
              <el-input v-model="form.username" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="密码">
              <el-input v-model="form.password" type="password" show-password placeholder="编辑时留空表示不修改" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="连接池大小">
          <el-input-number v-model="form.maxPoolSize" :min="1" :max="50" style="width: 160px" />
          <span class="muted" style="margin-left: 10px">仅允许只读查询</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 表结构 -->
    <el-dialog v-model="schemaVisible" :title="`表结构 · ${schemaTitle}`" width="760px" destroy-on-close>
      <div v-loading="schemaLoading" class="schema-body">
        <el-collapse v-if="tables.length">
          <el-collapse-item v-for="table in tables" :key="table.name" :name="table.name">
            <template #title>
              <span class="mono">{{ table.name }}</span>
              <span class="muted" style="margin-left: 8px">{{ table.comment || '' }}</span>
              <el-tag v-if="table.primaryKey" size="small" type="warning" effect="plain" style="margin-left: 8px">
                PK {{ table.primaryKey }}
              </el-tag>
            </template>
            <el-table :data="table.columns || []" size="small" border>
              <el-table-column prop="name" label="列名" width="180" />
              <el-table-column prop="type" label="类型" width="150" />
              <el-table-column label="主键" width="80">
                <template #default="{ row }">
                  <el-tag v-if="row.primaryKey" size="small" type="warning" effect="plain">PK</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="可空" width="80">
                <template #default="{ row }">{{ row.nullable === false ? '否' : '是' }}</template>
              </el-table-column>
              <el-table-column prop="comment" label="注释" />
            </el-table>
          </el-collapse-item>
        </el-collapse>
        <el-empty v-else-if="!schemaLoading" description="暂无表结构" :image-size="70" />
      </div>
    </el-dialog>

    <!-- SQL 查询 + 日志 -->
    <el-drawer v-model="queryVisible" :title="`SQL 查询 · ${queryTitle}`" size="72%" destroy-on-close>
      <el-tabs v-model="queryTab">
        <el-tab-pane label="查询" name="query">
          <el-input
            v-model="sql"
            type="textarea"
            :rows="7"
            class="code-area"
            placeholder="SELECT * FROM af_user LIMIT 20"
            spellcheck="false"
          />
          <div class="query-actions">
            <div class="toolbar">
              <span class="muted">最大返回行数</span>
              <el-input-number v-model="maxRows" :min="1" :max="1000" :step="10" size="small" />
              <el-tag size="small" type="info" effect="plain">只读校验 · 自动追加 LIMIT</el-tag>
            </div>
            <el-button type="primary" :icon="VideoPlay" :loading="querying" @click="runQuery">执行</el-button>
          </div>

          <el-alert
            v-if="queryResult && queryResult.truncated"
            type="warning"
            :closable="false"
            show-icon
            title="结果已截断（超过最大返回行数）"
            class="mt-12"
          />
          <div v-if="queryResult" class="result-meta muted mt-8">
            返回 {{ queryResult.rows.length }} 行 · 耗时 {{ queryResult.elapsedMs }}ms
          </div>
          <el-table
            v-if="queryResult"
            :data="resultRows"
            size="small"
            border
            class="mt-8"
            max-height="420"
            empty-text="无数据"
          >
            <el-table-column
              v-for="(column, index) in queryResult.columns"
              :key="column"
              :label="column"
              min-width="140"
            >
              <template #default="{ row }">
                <span class="mono">{{ formatCell(row[index]) }}</span>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="查询日志" name="logs">
          <el-table v-loading="logsLoading" :data="logs" size="small" empty-text="暂无查询日志" max-height="560">
            <el-table-column label="时间" width="170">
              <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="SQL" min-width="280">
              <template #default="{ row }"><span class="mono log-sql">{{ row.sql }}</span></template>
            </el-table-column>
            <el-table-column label="行数" width="80">
              <template #default="{ row }">{{ row.rowCount ?? '—' }}</template>
            </el-table-column>
            <el-table-column label="耗时" width="90">
              <template #default="{ row }">{{ formatDuration(row.elapsedMs) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.success === false ? 'danger' : 'success'" size="small" effect="light">
                  {{ row.success === false ? '失败' : '成功' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="{ row }">
                <el-button link type="primary" @click="reuseSql(row.sql)">复用</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Plus, Refresh, VideoPlay } from '@element-plus/icons-vue'
import { datasourcesApi } from '@/api/datasources'
import type {
  ConnectionTestResult,
  Datasource,
  DatasourceRequest,
  DatasourceType,
  SchemaTable,
  SqlQueryLog,
  SqlQueryResult,
} from '@/types'
import { formatDateTime, formatDuration } from '@/utils/format'
import { isFallbackError } from '@/utils/request'
import {
  mockDatasources,
  mockQueryLogs,
  mockQueryResult,
  mockSchema,
  withMock,
} from '@/utils/mock'

const loading = ref(false)
const saving = ref(false)
const datasources = ref<Datasource[]>([])
const testingId = ref<number | string | null>(null)

const editorVisible = ref(false)
const editingId = ref<number | string | null>(null)
const formRef = ref<FormInstance>()
const form = reactive<DatasourceRequest>({
  name: '',
  type: 'MYSQL',
  jdbcUrl: '',
  username: '',
  password: '',
  maxPoolSize: 5,
})
const rules: FormRules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择类型', trigger: 'change' }],
  jdbcUrl: [{ required: true, message: '请输入 JDBC URL', trigger: 'blur' }],
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
}

const schemaVisible = ref(false)
const schemaLoading = ref(false)
const schemaTitle = ref('')
const tables = ref<SchemaTable[]>([])
const schemaDatasourceId = ref<number | string | null>(null)

const queryVisible = ref(false)
const queryTab = ref('query')
const queryTitle = ref('')
const queryDatasourceId = ref<number | string | null>(null)
const sql = ref('SELECT * FROM af_user LIMIT 20')
const maxRows = ref(100)
const querying = ref(false)
const queryResult = ref<SqlQueryResult | null>(null)
const logsLoading = ref(false)
const logs = ref<SqlQueryLog[]>([])

const resultRows = computed(() => queryResult.value?.rows ?? [])

function formatCell(value: unknown): string {
  if (value === null || value === undefined) return 'NULL'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await withMock(
      '数据源列表',
      () => datasourcesApi.list({ page: 1, size: 100 }),
      () => {
        const list = mockDatasources()
        return { records: list, total: list.length, page: 1, size: 100 }
      },
    )
    datasources.value = result.data.records
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('数据源加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editingId.value = null
  form.name = ''
  form.type = 'MYSQL'
  form.jdbcUrl = ''
  form.username = ''
  form.password = ''
  form.maxPoolSize = 5
  editorVisible.value = true
}

// el-table 作用域插槽的 row 类型为 DefaultRow，入口处放宽类型，函数内部按 Datasource 使用
function openEdit(row: any): void {
  editingId.value = row.id
  form.name = row.name
  form.type = row.type as DatasourceType
  form.jdbcUrl = row.jdbcUrl
  form.username = row.username || ''
  form.password = ''
  form.maxPoolSize = row.maxPoolSize ?? 5
  editorVisible.value = true
}

async function submit(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (editingId.value !== null) {
      await withMock(`编辑数据源 #${editingId.value}`, () => datasourcesApi.update(editingId.value!, { ...form }), () => ({ id: editingId.value!, ...form }))
    } else {
      await withMock('新建数据源', () => datasourcesApi.create({ ...form }), () => ({ id: Date.now(), ...form }))
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
      `测试连接 #${row.id}`,
      () => datasourcesApi.test(row.id),
      () => ({ success: true, latencyMs: 42, message: 'PONG' }) as ConnectionTestResult,
    )
    const value = result.data
    if (value.success) ElMessage.success(`连接成功，延迟 ${value.latencyMs}ms${value.message ? `（${value.message}）` : ''}`)
    else ElMessage.error(`连接失败：${value.message || '未知错误'}`)
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('测试连接失败')
  } finally {
    testingId.value = null
  }
}

async function openSchema(row: any): Promise<void> {
  schemaDatasourceId.value = row.id
  schemaTitle.value = row.name
  schemaVisible.value = true
  schemaLoading.value = true
  tables.value = []
  try {
    const result = await withMock(`表结构 #${row.id}`, () => datasourcesApi.schema(row.id), () => mockSchema(row.id))
    tables.value = result.data
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('表结构加载失败')
  } finally {
    schemaLoading.value = false
  }
}

function openQuery(row: any): void {
  queryDatasourceId.value = row.id
  queryTitle.value = row.name
  queryVisible.value = true
  queryTab.value = 'query'
  queryResult.value = null
  void loadLogs()
}

async function loadLogs(): Promise<void> {
  if (!queryDatasourceId.value) return
  logsLoading.value = true
  try {
    const result = await withMock(
      `查询日志 #${queryDatasourceId.value}`,
      () => datasourcesApi.queryLogs(queryDatasourceId.value!, { page: 1, size: 50 }),
      () => {
        const list = mockQueryLogs()
        return { records: list, total: list.length, page: 1, size: 50 }
      },
    )
    logs.value = result.data.records
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('查询日志加载失败')
  } finally {
    logsLoading.value = false
  }
}

async function runQuery(): Promise<void> {
  if (!queryDatasourceId.value || !sql.value.trim()) {
    ElMessage.warning('请输入 SQL')
    return
  }
  querying.value = true
  try {
    const result = await withMock(
      'SQL 查询',
      () => datasourcesApi.query(queryDatasourceId.value!, { sql: sql.value, maxRows: maxRows.value }),
      () => mockQueryResult(),
    )
    queryResult.value = result.data
    ElMessage.success(`查询完成，${result.data.rows.length} 行 / ${result.data.elapsedMs}ms`)
    await loadLogs()
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('查询失败')
  } finally {
    querying.value = false
  }
}

function reuseSql(value: string): void {
  sql.value = value
  queryTab.value = 'query'
}

async function remove(row: any): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除数据源「${row.name}」？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await withMock(`删除数据源 #${row.id}`, () => datasourcesApi.remove(row.id), () => undefined)
    ElMessage.success('已删除')
    await load()
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('删除失败')
  }
}

onMounted(load)
</script>

<style scoped>
.ds-name {
  font-weight: 600;
  color: #0f172a;
}

.jdbc {
  font-size: 12px;
  color: #475569;
  word-break: break-all;
}

.query-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 10px 0;
}

.query-actions .toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
}

.result-meta {
  font-size: 12.5px;
}

.log-sql {
  font-size: 12px;
  color: #334155;
  word-break: break-all;
}

.schema-body {
  min-height: 120px;
}

.mt-8 {
  margin-top: 8px;
}

.mt-12 {
  margin-top: 12px;
}
</style>
