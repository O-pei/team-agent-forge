<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">工作台</h2>
        <p class="page-desc">平台运行概览 · 近 14 天 Token 用量、成本与调用明细</p>
      </div>
      <div class="toolbar">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :icon="ChatDotRound" @click="router.push('/chat')">开始对话</el-button>
      </div>
    </div>

    <div class="stats-grid">
      <StatCard
        label="今日请求"
        :value="formatNumber(overview.todayRequests)"
        :hint="`累计 ${formatNumber(overview.totalRequests)} 次`"
        :icon="DataLine"
        color="#2563eb"
      />
      <StatCard
        label="今日 Token"
        :value="formatNumber(overview.todayTokens)"
        :hint="`累计 ${formatNumber(overview.totalTokens)}`"
        :icon="Histogram"
        color="#7c3aed"
      />
      <StatCard
        label="今日成本"
        :value="formatCost(overview.todayCost)"
        :hint="`累计 ${formatCost(overview.totalCost)}`"
        :icon="Coin"
        color="#f59e0b"
      />
      <StatCard
        label="成功率"
        :value="formatPercent(overview.successRate)"
        :hint="`Agent ${overview.totalAgents ?? '-'} · 用户 ${overview.totalUsers ?? '-'}`"
        :icon="CircleCheck"
        color="#10b981"
      />
    </div>

    <div class="meta-strip mt-16">
      <div><span class="muted">Agent</span><strong>{{ overview.totalAgents ?? 0 }}</strong></div>
      <div><span class="muted">用户</span><strong>{{ overview.totalUsers ?? 0 }}</strong></div>
      <div><span class="muted">知识库</span><strong>{{ overview.totalKnowledgeBases ?? 0 }}</strong></div>
      <div><span class="muted">文档</span><strong>{{ overview.totalDocuments ?? 0 }}</strong></div>
      <div class="strip-actions">
        <el-button link type="primary" @click="router.push('/agents')">Agent 管理</el-button>
        <el-button link type="primary" @click="router.push('/knowledge')">知识库</el-button>
        <el-button link type="primary" @click="router.push('/audit')">审计看板</el-button>
      </div>
    </div>

    <div class="charts-grid mt-16">
      <div class="af-card af-card--pad">
        <div class="card-head">
          <h3>Token 用量趋势</h3>
          <span class="muted">近 14 天</span>
        </div>
        <Chart :option="trendOption" height="300px" />
      </div>
      <div class="af-card af-card--pad">
        <div class="card-head">
          <h3>Agent 用量占比</h3>
          <span class="muted">按总 Token</span>
        </div>
        <Chart :option="pieOption" height="300px" />
      </div>
    </div>

    <div class="af-card af-card--pad mt-16">
      <div class="card-head">
        <h3>最近调用</h3>
        <router-link to="/audit">查看全部 →</router-link>
      </div>
      <el-table v-loading="loading" :data="records" size="small" empty-text="暂无调用记录">
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="Agent" min-width="130">
          <template #default="{ row }">{{ row.agentName || `#${row.agentId ?? '-'}` }}</template>
        </el-table-column>
        <el-table-column label="模型" min-width="130">
          <template #default="{ row }">{{ row.modelName || `#${row.modelId ?? '-'}` }}</template>
        </el-table-column>
        <el-table-column label="Token" width="150">
          <template #default="{ row }">
            <span class="mono">{{ formatNumber(row.totalTokens) }}</span>
            <span class="muted"> ({{ formatNumber(row.promptTokens) }}/{{ formatNumber(row.completionTokens) }})</span>
          </template>
        </el-table-column>
        <el-table-column label="成本" width="110">
          <template #default="{ row }">{{ formatCost(row.cost) }}</template>
        </el-table-column>
        <el-table-column label="延迟" width="100">
          <template #default="{ row }">{{ formatDuration(row.latencyMs) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.success === false ? 'danger' : 'success'" size="small" effect="light">
              {{ row.success === false ? '失败' : '成功' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ChatDotRound,
  CircleCheck,
  Coin,
  DataLine,
  Histogram,
  Refresh,
} from '@element-plus/icons-vue'
import type { EChartsCoreOption } from 'echarts/core'
import Chart from '@/components/Chart.vue'
import StatCard from '@/components/StatCard.vue'
import { auditApi } from '@/api/audit'
import type { AuditOverview, UsageBreakdownItem, UsageRecord, UsageTrendPoint } from '@/types'
import {
  formatCost,
  formatDateTime,
  formatDuration,
  formatNumber,
  formatPercent,
  recentRange,
} from '@/utils/format'
import {
  mockAuditOverview,
  mockUsageBreakdown,
  mockUsageRecords,
  mockUsageTrend,
  withMock,
} from '@/utils/mock'

const router = useRouter()
const loading = ref(false)
const overview = ref<AuditOverview>({})
const trend = ref<UsageTrendPoint[]>([])
const breakdown = ref<UsageBreakdownItem[]>([])
const records = ref<UsageRecord[]>([])

const [from, to] = recentRange(14)

const trendOption = computed<EChartsCoreOption>(() => ({
  color: ['#2563eb', '#a5b4fc', '#f59e0b'],
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
  legend: { data: ['输入 Token', '输出 Token', '请求数'], bottom: 0, itemWidth: 12, itemHeight: 8 },
  grid: { left: 10, right: 12, top: 24, bottom: 44, containLabel: true },
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
      name: '请求数',
      nameTextStyle: { color: '#94a3b8', fontSize: 11 },
      splitLine: { show: false },
      axisLabel: { color: '#94a3b8', fontSize: 11 },
    },
  ],
  series: [
    {
      name: '输入 Token',
      type: 'line',
      smooth: true,
      stack: 'token',
      areaStyle: { opacity: 0.18 },
      symbol: 'none',
      data: trend.value.map((point) => point.promptTokens ?? 0),
    },
    {
      name: '输出 Token',
      type: 'line',
      smooth: true,
      stack: 'token',
      areaStyle: { opacity: 0.18 },
      symbol: 'none',
      data: trend.value.map((point) => point.completionTokens ?? 0),
    },
    {
      name: '请求数',
      type: 'bar',
      yAxisIndex: 1,
      barWidth: 8,
      itemStyle: { borderRadius: [3, 3, 0, 0], opacity: 0.55 },
      data: trend.value.map((point) => point.requests ?? 0),
    },
  ],
}))

const pieOption = computed<EChartsCoreOption>(() => ({
  color: ['#2563eb', '#7c3aed', '#f59e0b', '#10b981', '#ef4444'],
  tooltip: { trigger: 'item', formatter: '{b}: {c} tokens ({d}%)' },
  legend: { orient: 'vertical', right: 4, top: 'middle', itemWidth: 10, itemHeight: 10 },
  series: [
    {
      name: 'Agent 用量',
      type: 'pie',
      radius: ['45%', '68%'],
      center: ['38%', '52%'],
      avoidLabelOverlap: true,
      itemStyle: { borderColor: '#fff', borderWidth: 2, borderRadius: 4 },
      label: { show: false },
      data: breakdown.value.map((item) => ({
        name: item.label || String(item.key),
        value: item.totalTokens,
      })),
    },
  ],
}))

async function load(): Promise<void> {
  loading.value = true
  try {
    const [overviewResult, trendResult, breakdownResult, recordsResult] = await Promise.allSettled([
      withMock('工作台概览', () => auditApi.overview(), () => mockAuditOverview()),
      withMock('Token 趋势', () => auditApi.usageTrend({ from, to, granularity: 'day' }), () =>
        mockUsageTrend('day'),
      ),
      withMock('Agent 用量占比', () => auditApi.usageBreakdown({ from, to, groupBy: 'agent' }), () =>
        mockUsageBreakdown('agent'),
      ),
      withMock('最近调用记录', () => auditApi.usageRecords({ page: 1, size: 8, from, to }), () => {
        const page = mockUsageRecords(1, 8)
        return { records: page.records, total: page.total, page: 1, size: 8 }
      }),
    ])

    if (overviewResult.status === 'fulfilled') overview.value = overviewResult.value.data
    if (trendResult.status === 'fulfilled') {
      const value = trendResult.value.data
      trend.value = Array.isArray(value) ? value : value.points ?? []
    }
    if (breakdownResult.status === 'fulfilled') breakdown.value = breakdownResult.value.data
    if (recordsResult.status === 'fulfilled') records.value = recordsResult.value.data.records

    const rejected = [overviewResult, trendResult, breakdownResult, recordsResult].filter(
      (item) => item.status === 'rejected',
    )
    if (rejected.length === 4) ElMessage.error('工作台数据加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 16px;
}

.meta-strip {
  display: flex;
  align-items: center;
  gap: 34px;
  padding: 12px 18px;
  background: #fff;
  border: 1px solid var(--af-border);
  border-radius: var(--af-radius);
  flex-wrap: wrap;
}

.meta-strip > div {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 13px;
}

.meta-strip strong {
  font-size: 16px;
  color: #0f172a;
}

.strip-actions {
  margin-left: auto;
  gap: 4px;
}

.charts-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr);
  gap: 16px;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.card-head h3 {
  margin: 0;
  font-size: 14.5px;
  font-weight: 600;
  color: #0f172a;
}

@media (max-width: 1100px) {
  .charts-grid {
    grid-template-columns: 1fr;
  }
  .strip-actions {
    margin-left: 0;
  }
}
</style>
