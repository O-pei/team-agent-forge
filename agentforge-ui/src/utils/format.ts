/** 通用格式化 / 下载工具 */
import dayjs from 'dayjs'

export function formatDateTime(value?: string | number | Date | null): string {
  if (!value) return '-'
  const d = dayjs(value)
  return d.isValid() ? d.format('YYYY-MM-DD HH:mm:ss') : String(value)
}

export function formatDate(value?: string | number | Date | null): string {
  if (!value) return '-'
  const d = dayjs(value)
  return d.isValid() ? d.format('YYYY-MM-DD') : String(value)
}

export function formatNumber(value?: number | null): string {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return '0'
  return Number(value).toLocaleString('zh-CN')
}

export function formatCost(value?: number | null): string {
  const n = Number(value ?? 0)
  if (!Number.isFinite(n)) return '$0.0000'
  if (n > 0 && n < 0.0001) return `$${n.toExponential(2)}`
  return `$${n.toFixed(4)}`
}

export function formatDuration(ms?: number | null): string {
  if (!ms && ms !== 0) return '-'
  if (ms < 1000) return `${Math.round(ms)} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(2)} s`
  return `${(ms / 60_000).toFixed(1)} min`
}

export function formatFileSize(bytes?: number | null): string {
  if (!bytes) return '-'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let size = bytes
  let i = 0
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i += 1
  }
  return `${size.toFixed(i === 0 ? 0 : 1)} ${units[i]}`
}

export function formatPercent(value?: number | null, digits = 1): string {
  if (value === null || value === undefined || Number.isNaN(Number(value))) return '-'
  return `${(Number(value) * 100).toFixed(digits)}%`
}

/** 生成带 BOM 的 CSV，避免 Excel 中文乱码 */
export function toCsv(headers: string[], rows: Array<Array<string | number | null | undefined>>): string {
  const escape = (cell: string | number | null | undefined): string => {
    const text = cell === null || cell === undefined ? '' : String(cell)
    return /[",\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
  }
  const lines = [headers.map(escape).join(',')]
  for (const row of rows) lines.push(row.map(escape).join(','))
  return `\uFEFF${lines.join('\r\n')}`
}

export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
}

export function downloadText(filename: string, content: string, mime = 'text/csv;charset=utf-8'): void {
  downloadBlob(new Blob([content], { type: mime }), filename)
}

/** 日期范围默认值：最近 N 天（ISO-8601 秒级，契约要求 yyyy-MM-ddTHH:mm:ss） */
export function recentRange(days = 7): [string, string] {
  const to = dayjs()
  const from = to.subtract(days - 1, 'day').startOf('day')
  return [from.format('YYYY-MM-DDTHH:mm:ss'), to.format('YYYY-MM-DDTHH:mm:ss')]
}
