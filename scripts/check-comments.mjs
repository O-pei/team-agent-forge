#!/usr/bin/env node
/**
 * 注释风格检查：禁止 Javadoc/块注释/行注释里出现 Markdown 语法与 HTML 排版标签。
 * 用法: node scripts/check-comments.mjs [目录或文件...]
 * 退出码: 0 = 无违规, 1 = 有违规。
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, extname, relative } from 'node:path';

const ROOT = process.cwd();
const DEFAULT_ROOTS = [
  'agentforge-common/src',
  'agentforge-security/src',
  'agentforge-audit/src',
  'agentforge-memory/src',
  'agentforge-rag/src',
  'agentforge-datasource/src',
  'agentforge-agent/src',
  'agentforge-app/src',
  'agentforge-ui/src',
  'scripts',
];
const TARGET_EXT = new Set(['.java', '.ts', '.js', '.mjs', '.vue']);
const SKIP_DIRS = new Set(['node_modules', 'target', '.git', 'dist', '.data', '.m2repo']);

function walk(entry, out) {
  const st = statSync(entry);
  if (st.isDirectory()) {
    for (const name of readdirSync(entry)) {
      if (SKIP_DIRS.has(name)) continue;
      walk(join(entry, name), out);
    }
  } else if (TARGET_EXT.has(extname(entry))) {
    out.push(entry);
  }
}

/** 字符级扫描，跳过字符串/模板字符串，只收集代码注释。 */
function commentsOf(file, src) {
  const isVue = extname(file) === '.vue';
  const out = [];
  let i = 0;
  let line = 1;
  let buf = null;
  let startLine = 0;
  const flush = () => {
    if (buf !== null) out.push({ line: startLine, text: buf.text });
    buf = null;
  };
  while (i < src.length) {
    const ch = src[i];
    if (buf !== null) {
      if (buf.kind === 'line') {
        if (ch === '\n') { flush(); } else { buf.text += ch; }
        i += 1; if (ch === '\n') line += 1;
        continue;
      }
      if (buf.kind === 'block') {
        if (ch === '*' && src[i + 1] === '/') { flush(); i += 2; continue; }
        buf.text += ch; if (ch === '\n') line += 1; i += 1; continue;
      }
      if (buf.kind === 'html') {
        if (ch === '-' && src.startsWith('-->', i)) { flush(); i += 3; continue; }
        buf.text += ch; if (ch === '\n') line += 1; i += 1; continue;
      }
    }
    // 普通代码区
    if (ch === '/' && src[i + 1] === '/') {
      buf = { kind: 'line', text: '' }; startLine = line; i += 2; continue;
    }
    if (ch === '/' && src[i + 1] === '*') {
      buf = { kind: 'block', text: '' }; startLine = line; i += 2; continue;
    }
    if (isVue && src.startsWith('<!--', i)) {
      buf = { kind: 'html', text: '' }; startLine = line; i += 4; continue;
    }
    if (ch === '"' || ch === "'" || ch === '`') {
      const quote = ch;
      i += 1;
      while (i < src.length) {
        const c = src[i];
        if (c === '\\') { i += 2; continue; }
        if (c === quote) { i += 1; break; }
        if (c === '\n') line += 1;
        i += 1;
      }
      continue;
    }
    if (ch === '\n') line += 1;
    i += 1;
  }
  flush();
  return out;
}

/** 归一化：去掉 Javadoc 行首的星号与空白 */
function normalize(text) {
  return text.replace(/^\s*\*+\s?/, '').replace(/\s+$/, '');
}

const RULES = [
  { name: '反引号/代码围栏', test: (t) => t.includes('`') },
  { name: 'Markdown 加粗', test: (t) => /\*\*/.test(t) },
  { name: 'Markdown 列表符号', test: (t) => /^([-*+])\s+/.test(t.trim()) },
  { name: 'Markdown 标题', test: (t) => /^#{1,6}\s/.test(t.trim()) },
  { name: 'Markdown 表格', test: (t) => t.trim().startsWith('|') },
  { name: 'Markdown 链接', test: (t) => /\[[^\]]+\]\([^)]+\)/.test(t) },
  { name: 'HTML 排版标签', test: (t) => /<(p|br|ul|ol|li|pre|code|b|strong|em|h[1-6])\b/i.test(t) },
];

const inputs = process.argv.slice(2);
const entries = inputs.length ? inputs : DEFAULT_ROOTS.filter((p) => {
  try { return statSync(join(ROOT, p)).isDirectory(); } catch { return false; }
});

const files = [];
for (const e of entries) {
  try {
    const p = join(ROOT, e);
    if (statSync(p).isDirectory()) walk(p, files); else files.push(p);
  } catch { /* 忽略不存在的路径 */ }
}

let totalViolations = 0;
let badFiles = 0;
for (const file of files) {
  let src;
  try { src = readFileSync(file, 'utf8'); } catch { continue; }
  const bad = [];
  for (const c of commentsOf(file, src)) {
    const lines = c.text.split('\n');
    lines.forEach((raw, idx) => {
      const text = normalize(raw);
      for (const rule of RULES) {
        if (rule.test(text)) {
          bad.push({ line: c.line + idx, rule: rule.name, text: text.slice(0, 90) });
        }
      }
    });
  }
  if (bad.length) {
    badFiles += 1;
    totalViolations += bad.length;
    console.log(`\n${relative(ROOT, file)}  (${bad.length})`);
    for (const b of bad.slice(0, 8)) console.log(`  L${b.line} [${b.rule}] ${b.text}`);
    if (bad.length > 8) console.log(`  ... 其余 ${bad.length - 8} 条略`);
  }
}

console.log(`\n扫描文件 ${files.length} 个，违规文件 ${badFiles} 个，违规行 ${totalViolations} 处。`);
process.exit(totalViolations === 0 ? 0 : 1);
