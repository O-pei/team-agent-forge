<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">审计看板</h2>
        <p class="page-desc">Token 用量、成本与调用明细 · 可按时间、模型、Agent 聚合分析</p>
      </div>
      <div class="toolbar">
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          :clearable="false"
          style="width: 260px"
        />
        <el-radio-group v-model="granularity" size="small">
          <el-radio-button value="day">按天</el-radio-button>
          <el-radio-button value="hour">按小时</el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" :loading="loading" @click="loadAll">刷新</el-button>
        <el-button :icon="Download" :loading="exporting" @click="exportCsv">导出 CSV</el-button>
      </div>
    </div>

    <div class="stats-grid">
      <StatCard label="请求数" :value="formatNumber(summary.requests)" :icon="DataLine" color="#2563eb" />
      <StatCard label="总 Token" :value="formatNumber(summary.totalTokens)" :hint="`输入 ${formatNumber(summary.totalPromptTokens)} · 输出 ${formatNumber(summary.totalCompletionTokens)}`" :icon="Histogram" color="#7c3aed" />
      <StatCard label="总成本" :value="formatCost(summary.totalCost)" :icon="Coin" color="#f59e0b" />
      <StatCard label="单次平均 Token" :value="formatNumber(avgTokens)" :hint="`成功率 ${formatPercent(overview.successRate)}`" :icon="CircleCheck" color="#10b981" />
    </div>

    <div class="charts-grid mt-16">
      <div class="af-card af-card--pad">
        <div class="panel-head">
          <h3>Token 趋势</h3>
          <span class="muted">{{ granularity === 'day' ? '按天聚合' : '按小时聚合' }}</span>
        </div>
        <Chart :option="trendOption" height="300px" />
      </div>
      <div class="af-card af-card--pad">
        <div class="panel-head">
          <h3>用量分布</h3>
          <el-radio-group v-model="groupBy" size="small">
            <el-radio-button value="model">模型</el-radio-button>
            <el-radio-button value="agent">Agent</el-radio-button>
            <el-radio-button value="user">用户</el-radio-button>
          </el-radio-group>
        </div>
        <Chart :option="breakdownOption" height="300px" />
      </div>
    </div>

    <div class="af-card af-card--pad mt-16">
      <div class="panel-head">
        <h3>调用明细</h3>
        <div class="toolbar">
          <el-select v-model="modelFilter" placeholder="全部模型" clearable style="width: 170px" @change="loadRecords(1)">
            <el-option v-for="model in models" :key="model.id" :label="model.name" :value="model.id" />
          </el-select>
          <el-select v-model="agentFilter" placeholder="全部 Agent" clearable style="width: 170px" @change="loadRecords(1)">
            <el-option v-for="agent in agents" :key="agent.id" :label="agent.name" :value="agent.id" />
          </el-select>
        </div>
      </div>

      <el-table v-loading="loadingRecords" :data="records" size="small" empty-text="暂无调用记录">
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="模型" width="150">
          <template #default="{ row }">{{ row.modelName || modelName(row.modelId) }}</template>
        </el-table-column>
        <el-table-column label="Agent" width="140">
          <template #default="{ row }">{{ row.agentName || agentName(row.agentId) }}</template>
        </el-table-column>
        <el-table-column label="用户" width="120">
          <template #default="{ row }">{{ row.username || `#${row.userId ?? '-'}` }}</template>
        </el-table-column>
        <el-table-column label="输入" width="100">
          <template #default="{ row }">{{ formatNumber(row.promptTokens) }}</template>
        </el-table-column>
        <el-table-column label="输出" width="100">
          <template #default="{ row }">{{ formatNumber(row.completionTokens) }}</template>
        </el-table-column>
        <el-table-column label="总 Token" width="110">
          <template #default="{ row }"><span class="mono">{{ formatNumber(row.totalTokens) }}</span></template>
        </el-table-column>
        <el-table-column label="成本" width="100">
          <template #default="{ row }">{{ formatCost(row.cost) }}</template>
        </el-table-column>
        <el-table-column label="延迟" width="90">
          <template #default="{ row }">{{ formatDuration(row.latencyMs) }}</template>
        </el-table-column>
        <el-table-column label="流式" width="70">
          <template #default="{ row }">{{ row.stream ? '是' : '否' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80" fixed="right">
          <template #default="{ row }">
            <el-tag :type="row.success === false ? 'danger' : 'success'" size="small" effect="light">
              {{ row.success === false ? '失败' : '成功' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="loadRecords()"
          @size-change="loadRecords(1)"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { CircleCheck, Coin, DataLine, Download, Histogram, Refresh } from '@element-plus/icons-vue'
import type { EChartsCoreOption } from 'echarts/core'
import Chart from '@/components/Chart.vue'
import StatCard from '@/components/StatCard.vue'
import { auditApi } from '@/api/audit'
import { modelsApi } from '@/api/models'
import { agentsApi } from '@/api/agents'
import type {
  AgentInfo,
  AuditOverview,
  ModelConfig,
  UsageBreakdownItem,
  UsageRecord,
  UsageSummary,
  UsageTrendPoint,
} from '@/types'
import {
  downloadText,
  formatCost,
  formatDateTime,
  formatDuration,
  formatNumber,
  formatPercent,
  toCsv,
} from '@/utils/format'
import { isFallbackError } from '@/utils/request'
import {
  mockAgents,
  mockAuditOverview,
  mockModels,
  mockUsageBreakdown,
  mockUsageRecords,
  mockUsageSummary,
  mockUsageTrend,
  withMock,
} from '@/utils/mock'

function defaultRange(): [string, string] {
  const end = new Date()
  const start = new Date()
  start.setDate(start.getDate() - 6)
  const format = (value: Date) => {
    const year = value.getFullYear()
    const month = String(value.getMonth() + 1).padStart(2, '0')
    const day = String(value.getDate()).padStart(2, '0')
    return `${year}-${month}-${day}`
  }
  return [format(start), format(end)]
}

const dateRange = ref<[string, string]>(defaultRange())
const granularity = ref<'day' | 'hour'>('day')
const groupBy = ref<'model' | 'agent' | 'user'>('model')

const loading = ref(false)
const loadingRecords = ref(false)
const exporting = ref(false)
const summary = ref<UsageSummary>({
  totalPromptTokens: 0,
  totalCompletionTokens: 0,
  totalTokens: 0,
  totalCost: 0,
  requests: 0,
})
const overview = ref<AuditOverview>({})
const trend = ref<UsageTrendPoint[]>([])
const breakdown = ref<UsageBreakdownItem[]>([])
const records = ref<UsageRecord[]>([])
const models = ref<ModelConfig[]>([])
const agents = ref<AgentInfo[]>([])
const page = ref(1)
const size = ref(20)
const total = ref(0)
const modelFilter = ref<number | string | undefined>(undefined)
const agentFilter = ref<number | string | undefined>(undefined)

const from = computed(() => `${dateRange.value[0]}T00:00:00`)
const to = computed(() => `${dateRange.value[1]}T23:59:59`)
const avgTokens = computed(() =>
  summary.value.requests ? Math.round(summary.value.totalTokens / summary.value.requests) : 0,
)

const trendOption = computed<EChartsCoreOption>(() => ({
  color: ['#2563eb', '#a5b4fc', '#f59e0b'],
  tooltip: { trigger: 'axis' },
  legend: { data: ['输入 Token', '输出 Token', '成本'], bottom: 0, itemWidth: 12, itemHeight: 8 },
  grid: { left: 10, right: 14, top: 24, bottom: 44, containLabel: true },
  xAxis: {
    type: 'category',
    data: trend.value.map((point) => point.time),
    axisLine: { lineStyle: { color: '#e5e7eb' } },
    axisLabel: { color: '#94a3b8', fontSize: 11 },
  },
  yAxis: [
    {
      type: 'value',
      name: 'Token',
      nameTextStyle: { color: '#94a3b8', fontSize: 11 },
      splitLine: { lineStyle: { color: '#f1f5f9' } },
      axisLabel: { color: '#94a3b8', fontSize: 11 },
    },
    {
      type: 'value',
      name: '成本($)',
      nameTextStyle: { color: '#94a3b8', fontSize: 11 },
      splitLine: { show: false },
      axisLabel: { color: '#94a3b8', fontSize: 11 },
    },
  ],
  series: [
    {
      name: '输入 Token',
      type: 'bar',
      stack: 'token',
      barWidth: 14,
      itemStyle: { borderRadius: [3, 3, 0, 0] },
      data: trend.value.map((point) => point.promptTokens ?? 0),
    },
    {
      name: '输出 Token',
      type: 'bar',
      stack: 'token',
      barWidth: 14,
      itemStyle: { borderRadius: [3, 3, 0, 0] },
      data: trend.value.map((point) => point.completionTokens ?? 0),
    },
    {
      name: '成本',
      type: 'line',
      yAxisIndex: 1,
      smooth: true,
      symbol: 'circle',
      symbolSize: 5,
      data: trend.value.map((point) => Number((point.cost ?? 0).toFixed(4))),
    },
  ],
}))

const breakdownOption = computed<EChartsCoreOption>(() => ({
  color: ['#2563eb'],
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, formatter: '{b}: {c} tokens' },
  grid: { left: 10, right: 24, top: 16, bottom: 12, containLabel: true },
  xAxis: {
    type: 'value',
    splitLine: { lineStyle: { color: '#f1f5f9' } },
    axisLabel: { color: '#94a3b8', fontSize: 11 },
  },
  yAxis: {
    type: 'category',
    inverse: true,
    data: breakdown.value.map((item) => item.label || String(item.key)),
    axisLine: { lineStyle: { color: '#e5e7eb' } },
    axisLabel: { color: '#475569', fontSize: 12 },
  },
  series: [
    {
      type: 'bar',
      barWidth: 16,
      itemStyle: { borderRadius: [0, 4, 4, 0] },
      data: breakdown.value.map((item) => item.totalTokens),
    },
  ],
}))

function modelName(id?: number | string): string {
  if (id === undefined || id === null) return '—'
  return models.value.find((model) => model.id === id)?.name || `#${id}`
}

function agentName(id?: number | string): string {
  if (id === undefined || id === null) return '—'
  return agents.value.find((agent) => agent.id === id)?.name || `#${id}`
}

async function loadSummary(): Promise<void> {
  try {
    const result = await withMock(
      '用量汇总',
      () => auditApi.usageSummary({ from: from.value, to: to.value }),
      () => mockUsageSummary(),
    )
    summary.value = result.data
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('用量汇总加载失败')
  }
}

async function loadOverview(): Promise<void> {
  try {
    const result = await withMock('审计概览', () => auditApi.overview(), () => mockAuditOverview())
    overview.value = result.data
  } catch {
    /* 概览失败不阻塞看板 */
  }
}

async function loadTrend(): Promise<void> {
  try {
    const result = await withMock(
      `Token 趋势（${granularity.value}）`,
      () => auditApi.usageTrend({ from: from.value, to: to.value, granularity: granularity.value }),
      () => mockUsageTrend(granularity.value),
    )
    const value = result.data
    trend.value = Array.isArray(value) ? value : value.points ?? []
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('趋势数据加载失败')
  }
}

async function loadBreakdown(): Promise<void> {
  try {
    const result = await withMock(
      `用量分布（${groupBy.value}）`,
      () => auditApi.usageBreakdown({ from: from.value, to: to.value, groupBy: groupBy.value }),
      () => mockUsageBreakdown(groupBy.value),
    )
    breakdown.value = result.data
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('分布数据加载失败')
  }
}

async function loadRecords(target = page.value): Promise<void> {
  page.value = target
  loadingRecords.value = true
  try {
    const result = await withMock(
      '调用明细',
      () =>
        auditApi.usageRecords({
          page: page.value,
          size: size.value,
          from: from.value,
          to: to.value,
          modelId: modelFilter.value,
          agentId: agentFilter.value,
        }),
      () => mockUsageRecords(page.value, size.value),
    )
    records.value = result.data.records
    total.value = result.data.total
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('调用明细加载失败')
  } finally {
    loadingRecords.value = false
  }
}

async function loadAll(): Promise<void> {
  loading.value = true
  try {
    await Promise.all([loadOverview(), loadSummary(), loadTrend(), loadBreakdown(), loadRecords(1)])
  } finally {
    loading.value = false
  }
}

async function exportCsv(): Promise<void> {
  exporting.value = true
  try {
    const blob = await auditApi.usageExport({ from: from.value, to: to.value })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `agentforge-usage-${dateRange.value[0]}_${dateRange.value[1]}.csv`
    link.click()
    URL.revokeObjectURL(url)
    ElMessage.success('导出成功')
  } catch (error) {
    if (!isFallbackError(error)) {
      ElMessage.error('导出失败')
      return
    }
    // 后端未就绪：用当前明细在浏览器端生成同结构 CSV
    const headers = ['时间', '模型', 'Agent', '用户', '输入Token', '输出Token', '总Token', '成本', '延迟ms', '成功']
    const rows = records.value.map((record) => [
      formatDateTime(record.createdAt),
      record.modelName || modelName(record.modelId),
      record.agentName || agentName(record.agentId),
      record.username || '',
      record.promptTokens,
      record.completionTokens,
      record.totalTokens,
      record.cost,
      record.latencyMs ?? '',
      record.success === false ? '失败' : '成功',
    ])
    downloadText(`agentforge-usage-${dateRange.value[0]}_${dateRange.value[1]}.csv`, toCsv(headers, rows))
    ElMessage.success('后端未就绪：已导出浏览器端 CSV（演示数据）')
  } finally {
    exporting.value = false
  }
}

async function loadMeta(): Promise<void> {
  const [modelResult, agentResult] = await Promise.allSettled([
    withMock('模型列表', () => modelsApi.list(), () => mockModels()),
    withMock('Agent 列表', () => agentsApi.list({ page: 1, size: 100 }), () => ({
      records: mockAgents(),
      total: mockAgents().length,
      page: 1,
      size: 100,
    })),
  ])
  if (modelResult.status === 'fulfilled') models.value = modelResult.value.data
  if (agentResult.status === 'fulfilled') agents.value = agentResult.value.data.records
}

onMounted(async () => {
  await loadMeta()
  await loadAll()
})
</script>

<style scoped>
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 16px;
}

.charts-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr);
  gap: 16px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.panel-head h3 {
  margin: 0;
  font-size: 14.5px;
  font-weight: 600;
  color: #0f172a;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}

@media (max-width: 1100px) {
  .charts-grid {
    grid-template-columns: 1fr;
  }
}
</style>
