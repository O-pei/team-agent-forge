/**
 * SSE 解析（fetch + ReadableStream，EventSource 无法带 Authorization 头）
 * 契约第 3 节：event 为事件名，data 为单行 JSON；心跳 event: ping 每 15s。
 */
import { API_BASE, ApiError } from './request'
import { getAccessToken } from './token'

export interface SseEvent {
  event: string
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  data: any
}

export interface SseOptions {
  url: string
  body?: unknown
  method?: 'POST' | 'GET'
  headers?: Record<string, string>
  signal?: AbortSignal
  onEvent: (event: SseEvent) => void
}

function parseBlock(block: string): SseEvent | null {
  let event = 'message'
  const dataLines: string[] = []
  for (const rawLine of block.split('\n')) {
    const line = rawLine.trimEnd()
    if (!line || line.startsWith(':')) continue
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    const value = colon === -1 ? '' : line.slice(colon + 1).replace(/^ /, '')
    if (field === 'event') event = value
    else if (field === 'data') dataLines.push(value)
  }
  if (dataLines.length === 0) return null
  const raw = dataLines.join('\n')
  let data: unknown = raw
  try {
    data = JSON.parse(raw)
  } catch {
    data = raw
  }
  return { event, data }
}

export async function streamSse(options: SseOptions): Promise<void> {
  const token = getAccessToken()
  const response = await fetch(options.url.startsWith('http') ? options.url : `${API_BASE}${options.url}`, {
    method: options.method || 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    },
    body: options.method === 'GET' ? undefined : JSON.stringify(options.body ?? {}),
    signal: options.signal,
  })

  if (!response.ok) {
    let message = `流式接口连接失败（HTTP ${response.status}）`
    let code = response.status >= 500 ? 50000 : 40000
    try {
      const payload = (await response.json()) as { code?: number; message?: string }
      if (payload?.message) message = payload.message
      if (payload?.code) code = payload.code
    } catch {
      /* ignore non-json body */
    }
    throw new ApiError(code, message)
  }
  if (!response.body) throw new ApiError(50000, '当前浏览器不支持流式响应')

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  // eslint-disable-next-line no-constant-condition
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    buffer = buffer.replace(/\r\n/g, '\n')
    let index = buffer.indexOf('\n\n')
    while (index >= 0) {
      const block = buffer.slice(0, index)
      buffer = buffer.slice(index + 2)
      const event = parseBlock(block)
      if (event) options.onEvent(event)
      index = buffer.indexOf('\n\n')
    }
  }
  const tail = parseBlock(buffer)
  if (tail) options.onEvent(tail)
}

export function isAbortError(error: unknown): boolean {
  return (
    (error instanceof DOMException && error.name === 'AbortError') ||
    (error instanceof Error && /abort/i.test(error.name + error.message))
  )
}
