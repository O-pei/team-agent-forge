/**
 * 流式对话 composable：解析 SSE 事件、维护消息与工具调用状态、支持取消与演示流。
 */
import { computed, ref } from 'vue'
import type { ChatRequest, UsageInfo } from '@/types'
import { streamSse, isAbortError, type SseEvent } from '@/utils/sse'
import { ApiError, getErrorMessage, isFallbackError } from '@/utils/request'
import { mockEnabled, mockState } from '@/utils/mock'
import { agentsApi } from '@/api/agents'

export type StreamStatus =
  | 'idle'
  | 'connecting'
  | 'streaming'
  | 'interrupted'
  | 'completed'
  | 'cancelled'
  | 'error'

export interface ToolCallItem {
  id: string
  toolName: string
  arguments?: unknown
  riskLevel?: string
  resultSummary?: string
  success?: boolean
  elapsedMs?: number
  running: boolean
}

export interface CitationItem {
  index: number
  documentId: number | string
  filename?: string
  chunkIndex?: number
  score?: number
}

export interface InterruptedInfo {
  approvalId: number | string
  toolName?: string
  arguments?: unknown
  reason?: string
}

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

export function useSse() {
  const status = ref<StreamStatus>('idle')
  const content = ref('')
  const toolCalls = ref<ToolCallItem[]>([])
  const citations = ref<CitationItem[]>([])
  const usage = ref<UsageInfo | null>(null)
  const runId = ref('')
  const sessionId = ref('')
  const interrupted = ref<InterruptedInfo | null>(null)
  const errorMessage = ref('')
  const errorCode = ref(0)
  const elapsedMs = ref(0)

  let controller: AbortController | null = null
  let startedAt = 0

  const isStreaming = computed(
    () => status.value === 'connecting' || status.value === 'streaming',
  )
  const isMockStream = computed(() => runId.value.startsWith('mock-'))

  function reset(): void {
    status.value = 'idle'
    content.value = ''
    toolCalls.value = []
    citations.value = []
    usage.value = null
    runId.value = ''
    sessionId.value = ''
    interrupted.value = null
    errorMessage.value = ''
    errorCode.value = 0
    elapsedMs.value = 0
    controller = null
  }

  function handleEvent(event: SseEvent): void {
    const data = (event.data ?? {}) as Record<string, unknown>
    switch (event.event) {
      case 'run.started': {
        runId.value = String(data.runId ?? '')
        if (data.sessionId) sessionId.value = String(data.sessionId)
        status.value = 'streaming'
        break
      }
      case 'message.delta': {
        content.value += String(data.content ?? '')
        status.value = 'streaming'
        break
      }
      case 'message.completed': {
        if (data.content) content.value = String(data.content)
        break
      }
      case 'tool.call': {
        toolCalls.value.push({
          id: `${String(data.toolName)}-${Date.now()}-${toolCalls.value.length}`,
          toolName: String(data.toolName ?? 'tool'),
          arguments: data.arguments,
          riskLevel: data.riskLevel ? String(data.riskLevel) : undefined,
          running: true,
        })
        break
      }
      case 'tool.result': {
        const target = [...toolCalls.value]
          .reverse()
          .find((item) => item.toolName === String(data.toolName) && item.running)
        if (target) {
          target.running = false
          target.resultSummary = data.resultSummary ? String(data.resultSummary) : ''
          target.success = data.success !== false
          target.elapsedMs = Number(data.elapsedMs ?? 0)
        } else {
          toolCalls.value.push({
            id: `${String(data.toolName)}-${Date.now()}`,
            toolName: String(data.toolName ?? 'tool'),
            resultSummary: data.resultSummary ? String(data.resultSummary) : '',
            success: data.success !== false,
            elapsedMs: Number(data.elapsedMs ?? 0),
            running: false,
          })
        }
        break
      }
      case 'citation': {
        const index = Number(data.index ?? citations.value.length + 1)
        if (!citations.value.some((item) => item.index === index)) {
          citations.value.push({
            index,
            documentId: data.documentId as number | string,
            filename: data.filename ? String(data.filename) : undefined,
            chunkIndex: data.chunkIndex === undefined ? undefined : Number(data.chunkIndex),
            score: data.score === undefined ? undefined : Number(data.score),
          })
        }
        break
      }
      case 'usage': {
        usage.value = {
          model: data.model ? String(data.model) : undefined,
          promptTokens: Number(data.promptTokens ?? 0),
          completionTokens: Number(data.completionTokens ?? 0),
          totalTokens: Number(data.totalTokens ?? 0),
          cost: Number(data.cost ?? 0),
        }
        break
      }
      case 'interrupted': {
        interrupted.value = {
          approvalId: data.approvalId as number | string,
          toolName: data.toolName ? String(data.toolName) : undefined,
          arguments: data.arguments,
          reason: data.reason ? String(data.reason) : undefined,
        }
        status.value = 'interrupted'
        break
      }
      case 'run.completed': {
        const resultStatus = String(data.status ?? 'SUCCEEDED')
        status.value =
          resultStatus === 'SUCCEEDED'
            ? 'completed'
            : resultStatus === 'CANCELLED'
              ? 'cancelled'
              : 'error'
        if (data.usage) usage.value = data.usage as UsageInfo
        elapsedMs.value = Number(data.elapsedMs ?? Date.now() - startedAt)
        break
      }
      case 'error': {
        errorMessage.value = String(data.message ?? '运行失败')
        errorCode.value = Number(data.code ?? 0)
        status.value = 'error'
        break
      }
      case 'ping':
      default:
        break
    }
  }

  async function start(payload: ChatRequest): Promise<void> {
    reset()
    startedAt = Date.now()
    status.value = 'connecting'
    controller = new AbortController()
    try {
      await streamSse({
        url: '/chat/stream',
        body: payload,
        signal: controller.signal,
        onEvent: handleEvent,
      })
      if (status.value === 'connecting' || status.value === 'streaming') {
        status.value = 'completed'
        elapsedMs.value = Date.now() - startedAt
      }
    } catch (error) {
      if (isAbortError(error)) {
        status.value = 'cancelled'
        elapsedMs.value = Date.now() - startedAt
        return
      }
      if (mockEnabled && isFallbackError(error)) {
        await runMockStream(payload)
        return
      }
      status.value = 'error'
      errorMessage.value = getErrorMessage(error)
      errorCode.value = error instanceof ApiError ? error.code : 0
    } finally {
      controller = null
    }
  }

  async function stop(): Promise<void> {
    controller?.abort()
    const currentRunId = runId.value
    if (currentRunId && !currentRunId.startsWith('mock-')) {
      try {
        await agentsApi.cancel(currentRunId)
      } catch {
        /* 服务端可能已结束 */
      }
    }
    status.value = 'cancelled'
  }

  /** 演示流：模拟 run.started → tool.call/result → delta → citation → usage → completed */
  async function runMockStream(payload: ChatRequest): Promise<void> {
    mockState.note('对话流式')
    startedAt = Date.now()
    const mockRunId = `mock-run-${Date.now()}`
    const mockSessionId = payload.sessionId || `mock-session-${Date.now()}`
    handleEvent({
      event: 'run.started',
      data: {
        runId: mockRunId,
        agentId: payload.agentId,
        sessionId: mockSessionId,
        model: 'mock-chat',
      },
    })

    const question = payload.message
    const wantsData = /sql|数据|查询|报表|分析/.test(question)
    const wantsKnowledge = /知识|文档|售后|产品|检索|引用/.test(question)

    if (wantsKnowledge) {
      await sleep(260)
      if (controller?.signal.aborted) return
      handleEvent({
        event: 'tool.call',
        data: { toolName: 'knowledge_search', arguments: { query: question, topK: 4 }, riskLevel: 'LOW' },
      })
      await sleep(620)
      if (controller?.signal.aborted) return
      handleEvent({
        event: 'tool.result',
        data: { toolName: 'knowledge_search', resultSummary: '命中 3 个分块（最高相似度 0.91）', success: true, elapsedMs: 618 },
      })
      handleEvent({
        event: 'citation',
        data: { index: 1, documentId: 101, filename: '产品功能总览.md', chunkIndex: 12, score: 0.9123 },
      })
      handleEvent({
        event: 'citation',
        data: { index: 2, documentId: 104, filename: '常见问题汇总.md', chunkIndex: 21, score: 0.8123 },
      })
    }

    if (wantsData) {
      await sleep(240)
      if (controller?.signal.aborted) return
      handleEvent({
        event: 'tool.call',
        data: { toolName: 'sql_query', arguments: { datasourceId: 1, sql: 'SELECT id, username FROM af_user LIMIT 20' }, riskLevel: 'LOW' },
      })
      await sleep(540)
      if (controller?.signal.aborted) return
      handleEvent({
        event: 'tool.result',
        data: { toolName: 'sql_query', resultSummary: '返回 8 行，耗时 37ms（已强制 LIMIT 20）', success: true, elapsedMs: 537 },
      })
    }

    const answer = wantsKnowledge
      ? `根据知识库检索结果，「${question}」的回答如下：\n\n1. **创建 Agent**：在「Agent 管理」页新建草稿，选择模型并绑定工具与知识库。\n2. **流式对话**：进入「对话」页选择该 Agent 即可实时查看 token 增量、工具调用与引用。\n3. **发布版本**：发布后版本不可变，可在历史版本间追溯。\n\n> 引用：产品功能总览.md · 常见问题汇总.md\n\n如需更细的配置说明，可以继续追问。`
      : wantsData
        ? `已通过只读 SQL 查询数据源（强制 LIMIT 20）：\n\n| id | username |\n| --- | --- |\n| 1 | admin |\n| 2 | alice |\n| 3 | bob |\n\n共返回 8 行，查询耗时 37ms，未发生截断。`
        : `收到：「${question}」\n\n这是**演示模式**下的流式回复（后端未就绪时会自动降级）。真实接入后，这里会逐 token 渲染模型输出，并在下方展示工具调用、知识库引用与 Token 用量。\n\n可以试试发送包含「产品」「售后」或「数据」「SQL」的问题，观察工具调用折叠面板与引用标签。`

    const chunks = answer.match(/[\s\S]{1,6}/g) || []
    for (const chunk of chunks) {
      if (controller?.signal.aborted) return
      handleEvent({ event: 'message.delta', data: { content: chunk } })
      await sleep(24)
    }
    handleEvent({ event: 'message.completed', data: { content: answer } })
    handleEvent({
      event: 'usage',
      data: {
        model: 'mock-chat',
        promptTokens: 842,
        completionTokens: Math.max(120, answer.length),
        totalTokens: 842 + Math.max(120, answer.length),
        cost: 0.000412,
      },
    })
    handleEvent({
      event: 'run.completed',
      data: { runId: mockRunId, status: 'SUCCEEDED', elapsedMs: Date.now() - startedAt },
    })
  }

  return {
    status,
    content,
    toolCalls,
    citations,
    usage,
    runId,
    sessionId,
    interrupted,
    errorMessage,
    errorCode,
    elapsedMs,
    isStreaming,
    isMockStream,
    start,
    stop,
    reset,
  }
}
