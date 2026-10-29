/** Markdown 渲染（对话内容），代码块高亮，禁用原始 HTML 防 XSS */
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'
import 'highlight.js/styles/github.css'

const ESCAPE_MAP: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
}

function escapeHtml(text: string): string {
  return text.replace(/[&<>"]/g, (char) => ESCAPE_MAP[char] ?? char)
}

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight(code: string, lang: string): string {
    if (lang && hljs.getLanguage(lang)) {
      try {
        const html = hljs.highlight(code, { language: lang, ignoreIllegals: true }).value
        return `<pre class="hljs"><code>${html}</code></pre>`
      } catch {
        /* 高亮失败时回退到纯文本 */
      }
    }
    return `<pre class="hljs"><code>${escapeHtml(code)}</code></pre>`
  },
})

// 外链新窗口打开
const defaultLinkRender = md.renderer.rules.link_open
md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  const targetIndex = tokens[idx].attrIndex('target')
  if (targetIndex < 0) tokens[idx].attrPush(['target', '_blank'])
  else if (tokens[idx].attrs) tokens[idx].attrs[targetIndex][1] = '_blank'
  const relIndex = tokens[idx].attrIndex('rel')
  if (relIndex < 0) tokens[idx].attrPush(['rel', 'noopener noreferrer'])
  if (defaultLinkRender) return defaultLinkRender(tokens, idx, options, env, self)
  return self.renderToken(tokens, idx, options)
}

export function renderMarkdown(text: string): string {
  return md.render(text || '')
}

export default renderMarkdown
