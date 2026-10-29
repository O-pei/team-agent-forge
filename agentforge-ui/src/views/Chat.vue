<template>
  <div class="page chat-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">对话</h2>
        <p class="page-desc">与 Agent 流式对话 · SSE 增量输出、工具调用、知识库引用与 Token 用量</p>
      </div>
      <div class="toolbar">
        <el-tag v-if="quota" :type="quotaExhausted ? 'danger' : 'info'" effect="plain" class="quota-badge">
          <el-icon><Wallet /></el-icon>
          {{ quotaText }}
        </el-tag>
        <el-select v-model="agentId" placeholder="选择 Agent" style="width: 220px" :disabled="sse.isStreaming.value">
          <el-option v-for="agent in agents" :key="agent.id" :label="agent.name" :value="agent.id" />
        </el-select>
        <el-button :icon="Delete" :disabled="sse.isStreaming.value || !hasConversation" @click="clearConversation">
          清空
        </el-button>
      </div>
    </div>

    <div class="af-card chat-shell">
      <div ref="scrollRef" class="chat-scroll">
        <el-empty v-if="!hasConversation" description="选择 Agent，输入问题开始对话">
          <div class="quick-prompts">
            <el-button v-for="prompt in quickPrompts" :key="prompt" plain size="small" @click="usePrompt(prompt)">
              {{ prompt }}
            </el-button>
          </div>
          <p class="muted mt-12">试试含「产品」「售后」的问题观察知识库引用，或含「数据」「SQL」的问题观察 sql_query 工具调用。</p>
        </el-empty>

        <template v-else>
          <div v-for="(turn, index) in turns" :key="index" class="turn">
            <div class="msg msg-user">
              <div class="bubble">{{ turn.question }}</div>
            </div>
            <div class="msg msg-assistant">
              <div class="bubble assistant">
                <div v-if="turn.status === 'error'" class="sse-error">
                  <el-icon><WarningFilled /></el-icon>
                  <span>{{ turn.error || '运行失败，请稍后重试' }}</span>
                </div>

                <template v-else>
                  <el-collapse v-if="turn.toolCalls.length" class="tool-collapse">
                    <el-collapse-item :name="1">
                      <template #title>
                        <span class="tool-title">
                          <el-icon><Tools /></el-icon>
                          工具调用 {{ turn.toolCalls.length }} 次
                          <el-tag
                            v-for="item in turn.toolCalls.filter((tool) => tool.running).slice(0, 1)"
                            :key="item.id"
                            type="warning"
                            size="small"
                            effect="light"
                          >
                            {{ item.toolName }} 执行中
                          </el-tag>
                          <el-tag v-if="turn.mock" size="small" effect="plain">演示流</el-tag>
                        </span>
                      </template>
                      <div v-for="tool in turn.toolCalls" :key="tool.id" class="tool-item">
                        <div class="tool-head">
                          <span class="mono">{{ tool.toolName }}</span>
                          <el-tag v-if="tool.riskLevel" size="small" effect="plain" :type="riskTagType(tool.riskLevel)">
                            {{ tool.riskLevel }}
                          </el-tag>
                          <el-tag v-if="tool.running" type="warning" size="small" effect="light">运行中</el-tag>
                          <el-tag v-else :type="tool.success === false ? 'danger' : 'success'" size="small" effect="light">
                            {{ tool.success === false ? '失败' : '成功' }}
                          </el-tag>
                          <span class="muted">{{ formatDuration(tool.elapsedMs) }}</span>
                        </div>
                        <pre v-if="tool.arguments" class="tool-args">{{ prettyJson(tool.arguments) }}</pre>
                        <div v-if="tool.resultSummary" class="tool-result">{{ tool.resultSummary }}</div>
                      </div>
                    </el-collapse-item>
                  </el-collapse>

                  <div class="markdown-body" v-html="renderMarkdown(turn.answer || (turn.status === 'streaming' ? '…' : ''))" />
                  <div v-if="turn.status === 'streaming'" class="cursor">▍</div>

                  <div v-if="turn.citations.length" class="citations">
                    <div class="citations-title">引用来源</div>
                    <div v-for="cite in turn.citations" :key="cite.index" class="citation-item">
                      <el-tag size="small" effect="plain" type="info">[{{ cite.index }}]</el-tag>
                      <span>{{ cite.filename || `文档 #${cite.documentId}` }}</span>
                      <span class="muted">#chunk {{ cite.chunkIndex ?? '-' }} · 相似度 {{ cite.score ? cite.score.toFixed(4) : '-' }}</span>
                    </div>
                  </div>

                  <div v-if="turn.usage || turn.elapsedMs" class="usage-line">
                    <template v-if="turn.usage">
                      <span>模型 <b class="mono">{{ turn.usage.model || '-' }}</b></span>
                      <span>Token {{ formatNumber(turn.usage.promptTokens) }} / {{ formatNumber(turn.usage.completionTokens) }} / {{ formatNumber(turn.usage.totalTokens) }}</span>
                      <span>成本 {{ formatCost(turn.usage.cost) }}</span>
                    </template>
                    <span v-if="turn.elapsedMs">耗时 {{ formatDuration(turn.elapsedMs) }}</span>
                  </div>

                  <div v-if="turn.interrupted" class="approval-card">
                    <div class="approval-head">
                      <el-icon><Lock /></el-icon>
                      <b>高风险工具需要人工审批</b>
                    </div>
                    <div class="muted">
                      工具 <span class="mono">{{ turn.interrupted.toolName }}</span> · {{ turn.interrupted.reason || '按 HITL 策略暂停' }}
                    </div>
                    <pre v-if="turn.interrupted.arguments" class="tool-args">{{ prettyJson(turn.interrupted.arguments) }}</pre>
                    <div class="toolbar mt-8">
                      <el-button type="success" size="small" :loading="approving" @click="decide(turn.runId, true)">通过</el-button>
                      <el-button type="danger" size="small" plain :loading="approving" @click="decide(turn.runId, false)">拒绝</el-button>
                    </div>
                  </div>
                </template>
              </div>
            </div>
          </div>
        </template>
      </div>

      <div class="chat-input">
        <div v-if="quotaExhausted" class="quota-exhausted">
          <el-icon><WarningFilled /></el-icon>
          <span>
            额度已用尽，暂时无法继续对话。{{ quota?.resetAt ? `每日额度将于 ${formatDateTime(quota.resetAt)} 重置。` : '' }}
          </span>
          <el-button link type="primary" @click="loadQuota">刷新额度</el-button>
        </div>
        <el-input
          v-model="input"
          type="textarea"
          :rows="3"
          resize="none"
          maxlength="4000"
          show-word-limit
          :placeholder="quotaExhausted ? '额度已用尽，暂不可发送' : '输入问题，Enter 发送，Shift+Enter 换行'"
          :disabled="sse.isStreaming.value || !agentId || quotaExhausted"
          @keydown.enter.exact.prevent="send"
        />
        <div class="input-actions">
          <span class="muted">
            <template v-if="sse.sessionId.value">会话 {{ sse.sessionId.value }}</template>
            <template v-else>会话将在首次发送后创建</template>
          </span>
          <div class="toolbar">
            <el-button v-if="sse.isStreaming.value" type="danger" plain :icon="VideoPause" @click="stop">停止</el-button>
            <el-button type="primary" :icon="Promotion" :loading="sse.isStreaming.value" :disabled="!input.trim() || quotaExhausted" @click="send">
              发送
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Delete,
  Lock,
  Promotion,
  Tools,
  VideoPause,
  Wallet,
  WarningFilled,
} from '@element-plus/icons-vue'
import { useSse, type CitationItem, type InterruptedInfo, type StreamStatus, type ToolCallItem } from '@/composables/useSse'
import { agentsApi } from '@/api/agents'
import { quotaApi } from '@/api/quota'
import type { AgentInfo, QuotaStatus, UsageInfo } from '@/types'
import { formatCost, formatDateTime, formatDuration, formatNumber } from '@/utils/format'
import { isFallbackError } from '@/utils/request'
import { mockAgents, mockQuotaStatus, withMock } from '@/utils/mock'
import { renderMarkdown } from '@/utils/markdown'

interface Turn {
  question: string
  answer: string
  toolCalls: ToolCallItem[]
  citations: CitationItem[]
  usage: UsageInfo | null
  elapsedMs: number
  runId: string
  mock: boolean
  status: StreamStatus
  error: string
  errorCode?: number
  interrupted: InterruptedInfo | null
}

const sse = useSse()
const agents = ref<AgentInfo[]>([])
const agentId = ref<number | string | undefined>(undefined)
const turns = ref<Turn[]>([])
const input = ref('')
const scrollRef = ref<HTMLElement | null>(null)
const approving = ref(false)
const quota = ref<QuotaStatus | null>(null)
const quotaExhausted = ref(false)

const quotaText = computed(() => {
  const current = quota.value
  if (!current) return '额度加载中'
  const daily = current.dailyLimit
    ? `${formatNumber(current.dailyUsed)} / ${formatNumber(current.dailyLimit)}`
    : `${formatNumber(current.dailyUsed)}（不限）`
  const total = current.totalLimit
    ? `${Math.min(100, Math.round((current.totalUsed / current.totalLimit) * 100))}%`
    : '不限'
  return `今日 ${daily} · 总额度 ${total}`
})

async function loadQuota(): Promise<void> {
  try {
    const result = await withMock('我的额度', () => quotaApi.getMyQuota(), () => mockQuotaStatus())
    quota.value = result.data
    const current = result.data
    quotaExhausted.value =
      !current.unlimited &&
      ((current.dailyLimit > 0 && current.dailyRemaining <= 0) ||
        (current.totalLimit > 0 && current.totalRemaining <= 0))
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('额度信息加载失败')
  }
}

const quickPrompts = [
  '产品支持哪些知识库能力？',
  '帮我查询最近的用户数据',
  '如何发布一个 Agent 版本？',
]

const hasConversation = computed(() => turns.value.length > 0)
let syncTimer = 0

function riskTagType(riskLevel?: string): 'info' | 'warning' | 'danger' {
  if (riskLevel === 'HIGH') return 'danger'
  if (riskLevel === 'MEDIUM') return 'warning'
  return 'info'
}

function prettyJson(value: unknown): string {
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

async function loadAgents(): Promise<void> {
  try {
    const result = await withMock('Agent 列表', () => agentsApi.list({ page: 1, size: 100 }), () => ({
      records: mockAgents(),
      total: mockAgents().length,
      page: 1,
      size: 100,
    }))
    agents.value = result.data.records
    const first = agents.value.find((agent) => agent.status === 'PUBLISHED') ?? agents.value[0]
    if (first) agentId.value = first.id
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('Agent 列表加载失败')
  }
}

/** 把当前 SSE 状态写回某一轮对话 */
function applySse(target: Turn): void {
  target.answer = sse.content.value
  target.toolCalls = [...sse.toolCalls.value]
  target.citations = [...sse.citations.value]
  target.usage = sse.usage.value
  target.elapsedMs = sse.elapsedMs.value
  target.runId = sse.runId.value
  target.mock = sse.isMockStream.value
  target.status = sse.status.value
  target.error = sse.errorMessage.value
  target.errorCode = sse.errorCode.value
  if (target.errorCode === 42901) {
    target.error = '额度已用尽，已停止后续模型调用。请联系管理员分配 Token 或等待每日额度重置。'
    quotaExhausted.value = true
  }
  target.interrupted = sse.interrupted.value
}

async function send(): Promise<void> {
  const question = input.value.trim()
  if (!question || sse.isStreaming.value || !agentId.value) return
  if (quotaExhausted.value) {
    ElMessage.warning('额度已用尽，暂时无法继续对话')
    return
  }

  // 上一次已完成/中断的对话快照入列
  const previous = turns.value[turns.value.length - 1]
  if (previous && previous.status !== 'connecting') applySse(previous)

  const turn: Turn = {
    question,
    answer: '',
    toolCalls: [],
    citations: [],
    usage: null,
    elapsedMs: 0,
    runId: '',
    mock: false,
    status: 'connecting',
    error: '',
    interrupted: null,
  }
  turns.value.push(turn)
  input.value = ''
  await scrollToBottom()

  void sse.start({ agentId: agentId.value, sessionId: sse.sessionId.value || undefined, message: question })

  // 同步流式状态到当前轮次（SSE 为独立 ref，轮询比 watch 更简单可靠）
  const index = turns.value.length - 1
  window.clearInterval(syncTimer)
  syncTimer = window.setInterval(() => {
    const current = turns.value[index]
    if (!current) {
      window.clearInterval(syncTimer)
      return
    }
    applySse(current)
    if (['completed', 'cancelled', 'error'].includes(sse.status.value)) {
      window.clearInterval(syncTimer)
      void loadQuota()
    }
    void scrollToBottom()
  }, 60)
}

async function stop(): Promise<void> {
  await sse.stop()
  ElMessage.info('已停止生成')
}

function clearConversation(): void {
  sse.reset()
  turns.value = []
}

function usePrompt(prompt: string): void {
  input.value = prompt
}

async function decide(runId: string, approved: boolean): Promise<void> {
  if (!runId) {
    ElMessage.warning('缺少 runId，无法提交审批')
    return
  }
  approving.value = true
  try {
    await agentsApi.approve(runId, { approved, comment: approved ? '前端审批通过' : '前端审批拒绝' })
    ElMessage.success(approved ? '已通过审批' : '已拒绝执行')
    const turn = turns.value[turns.value.length - 1]
    if (turn) turn.interrupted = null
  } catch (error) {
    if (isFallbackError(error)) ElMessage.warning('后端未就绪：演示模式下审批不落库')
    else ElMessage.error('审批提交失败')
  } finally {
    approving.value = false
  }
}

async function scrollToBottom(): Promise<void> {
  await nextTick()
  const element = scrollRef.value
  if (element) element.scrollTop = element.scrollHeight
}

onMounted(() => {
  void loadAgents()
  void loadQuota()
})

onBeforeUnmount(() => {
  window.clearInterval(syncTimer)
  void sse.stop()
})
</script>

<style scoped>
.chat-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - var(--af-header-height) - 48px);
}

.chat-shell {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 20px 22px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.quick-prompts {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  margin-top: 10px;
}

.turn {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.msg {
  display: flex;
}

.msg-user {
  justify-content: flex-end;
}

.msg-user .bubble {
  max-width: 76%;
  background: var(--af-primary);
  color: #fff;
  border-radius: 12px 12px 2px 12px;
  padding: 10px 14px;
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
}

.msg-assistant {
  justify-content: flex-start;
}

.bubble.assistant {
  max-width: 88%;
  width: 100%;
  background: #f8fafc;
  border: 1px solid var(--af-border);
  border-radius: 12px 12px 12px 2px;
  padding: 12px 16px;
}

.markdown-body {
  font-size: 14px;
}

.cursor {
  color: var(--af-primary);
  animation: blink 1s steps(2, start) infinite;
}

@keyframes blink {
  to {
    visibility: hidden;
  }
}

.sse-error {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-color-danger);
  font-size: 13px;
}

.tool-collapse {
  margin-bottom: 10px;
  border-top: none;
  border-bottom: none;
}

.tool-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #475569;
}

.tool-item {
  padding: 8px 0;
  border-bottom: 1px dashed var(--af-border);
}

.tool-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.tool-args {
  margin: 6px 0 0;
  padding: 8px 10px;
  background: #0f172a;
  color: #e2e8f0;
  border-radius: 6px;
  font-size: 12px;
  max-height: 180px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.tool-result {
  margin-top: 6px;
  font-size: 12.5px;
  color: #475569;
}

.citations {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--af-border);
}

.citations-title {
  font-size: 12.5px;
  font-weight: 600;
  color: #475569;
  margin-bottom: 6px;
}

.citation-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: #334155;
  padding: 2px 0;
}

.usage-line {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--af-border);
  color: #64748b;
  font-size: 12px;
}

.approval-card {
  margin-top: 12px;
  padding: 12px;
  border: 1px solid var(--el-color-warning-light-5);
  background: var(--el-color-warning-light-9);
  border-radius: 8px;
}

.approval-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
  color: #b45309;
  font-size: 13px;
}

.chat-input {
  border-top: 1px solid var(--af-border);
  padding: 12px 16px 10px;
  background: #fff;
}

.quota-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 320px;
}

.quota-exhausted {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  padding: 8px 12px;
  border: 1px solid #fecaca;
  border-radius: var(--af-radius);
  background: #fef2f2;
  color: #b91c1c;
  font-size: 13px;
}

.input-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}

.mt-8 {
  margin-top: 8px;
}

.mt-12 {
  margin-top: 12px;
}
</style>
