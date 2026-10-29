#!/usr/bin/env node
/**
 * AgentForge Token 额度端到端验收脚本（v0.2.0）。
 *
 * 用法：
 *   node scripts/quota-e2e.mjs
 *   node scripts/quota-e2e.mjs --base-url http://127.0.0.1:18080 --account admin@agentforge.dev --password 'Admin@123456'
 *
 * 前置：后端以 dev profile 启动（H2 + Mock 模型），Flyway V1~V7 已应用。
 * 说明：额度接口未就绪（404/503）时整体记为 SKIP 并退出 0；行为不符合契约记为 FAIL。
 * 退出码：0 = 全部 PASS/SKIP；1 = 存在 FAIL。
 */
import { setTimeout as sleep } from 'node:timers/promises';

const args = process.argv.slice(2);
function arg(name, fallback) {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
}
const BASE = arg('base-url', process.env.AF_QUOTA_BASE ?? 'http://127.0.0.1:18080');
const PREFIX = arg('api-prefix', '/api/v1');
const ACCOUNT = arg('account', 'admin@agentforge.dev');
const PASSWORD = arg('password', 'Admin@123456');
const CHAT_TIMEOUT = Number(arg('chat-timeout', 180000));
const USER_PASSWORD = 'Quota@123456';

const stats = { pass: 0, skip: 0, fail: 0 };
const failures = [];
const stamp = Date.now();
const stamp36 = stamp.toString(36).toUpperCase();
const created = { users: [], tiers: [], agents: [] };
let adminToken = null;
let userToken = null;

const color = (code, s) => (process.stdout.isTTY ? `\u001b[${code}m${s}\u001b[0m` : s);
const green = (s) => color(32, s);
const red = (s) => color(31, s);
const yellow = (s) => color(33, s);
const gray = (s) => color(90, s);

function step(name, status, detail = '') {
  const tag = status === 'PASS' ? green('PASS') : status === 'FAIL' ? red('FAIL') : yellow('SKIP');
  console.log(`[${tag}] ${name.padEnd(56)} ${detail}`);
  if (status === 'PASS') stats.pass++;
  else if (status === 'FAIL') {
    stats.fail++;
    failures.push(`${name} :: ${detail}`);
  } else stats.skip++;
}

/**
 * 发请求并返回原始结果，不因 4xx 抛异常，便于断言 429。
 * 返回 { status, code, data, text, parsed }。
 */
async function request(method, path, body, token = adminToken, timeoutMs = 30000) {
  const headers = { Accept: 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  const init = { method, headers, signal: AbortSignal.timeout(timeoutMs) };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json; charset=utf-8';
    init.body = JSON.stringify(body);
  }
  const res = await fetch(`${BASE}${PREFIX}${path}`, init);
  const text = await res.text();
  let parsed = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    parsed = null;
  }
  return {
    status: res.status,
    code: parsed?.code,
    data: parsed?.data,
    text,
    parsed,
  };
}

/** 发请求并要求成功（HTTP 200 且业务 code=0），返回 data。 */
async function api(method, path, body, token = adminToken, timeoutMs = 30000) {
  const r = await request(method, path, body, token, timeoutMs);
  if (r.status >= 400) {
    throw new Error(`HTTP ${r.status} code=${r.code ?? '-'} message=${r.parsed?.message ?? r.text.slice(0, 200)}`);
  }
  if (r.parsed && r.parsed.code !== undefined && r.parsed.code !== 0) {
    throw new Error(`code=${r.parsed.code} message=${r.parsed.message}`);
  }
  return r.data;
}

async function check(name, fn) {
  try {
    const out = await fn();
    if (out === 'SKIP') {
      step(name, 'SKIP', '接口未实现/模块未就绪');
      return null;
    }
    step(name, 'PASS');
    return out;
  } catch (e) {
    step(name, 'FAIL', String(e.message ?? e).split('\n')[0]);
    return null;
  }
}

function finish() {
  console.log(`\n=== 结果：PASS=${stats.pass} SKIP=${stats.skip} FAIL=${stats.fail} ===\n`);
  if (stats.fail > 0) {
    console.log(red('失败明细：'));
    for (const f of failures) console.log(red(`  - ${f}`));
    process.exit(1);
  }
  process.exit(0);
}

async function cleanup() {
  for (const id of created.users) {
    await api('DELETE', `/quota/users/${id}/override`, undefined).catch(() => {});
    await api('DELETE', `/users/${id}`, undefined).catch(() => {});
  }
  for (const id of created.tiers) {
    await api('DELETE', `/quota/tiers/${id}`, undefined).catch(() => {});
  }
  for (const id of created.agents) {
    await api('DELETE', `/agents/${id}`, undefined).catch(() => {});
  }
}

/** 运行一次流式对话，返回 SSE 原文；HTTP 非 200 时返回响应体文本。 */
async function chat(token, agentId, message) {
  const res = await fetch(`${BASE}${PREFIX}/chat/stream`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      Accept: 'text/event-stream',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ agentId, message }),
    signal: AbortSignal.timeout(CHAT_TIMEOUT),
  });
  const text = await res.text();
  return { status: res.status, text };
}

console.log(`\n=== AgentForge Token 额度验收 @ ${BASE} ===\n`);

// ---------- 0. 存活与登录 ----------
const health = await check('GET /actuator/health', async () => {
  const res = await fetch(`${BASE}/actuator/health`, { signal: AbortSignal.timeout(10000) });
  if (res.status !== 200) throw new Error(`HTTP ${res.status}`);
  const body = await res.json().catch(() => ({}));
  if (body?.status && body.status !== 'UP') throw new Error(`status=${body.status}`);
  return body;
});
if (!health) finish();

const login = await check('POST /auth/login（管理员）', async () => {
  const data = await api('POST', '/auth/login', { account: ACCOUNT, password: PASSWORD }, null);
  if (!data?.accessToken) throw new Error('响应缺少 accessToken');
  adminToken = data.accessToken;
});
if (!login && !adminToken) finish();

// ---------- 1. 额度接口就绪探测 ----------
const meProbe = await request('GET', '/quota/me', undefined);
if (meProbe.status === 404 || meProbe.status === 503 || meProbe.code === 40400 || meProbe.code === 50300) {
  step('GET /quota/me（额度接口就绪）', 'SKIP', `HTTP ${meProbe.status} code=${meProbe.code ?? '-'}`);
  console.log(gray('        Token 额度接口尚未上线，跳过后续额度验收；上线后重跑本脚本。'));
  finish();
}

await check('GET /quota/me（管理员快照字段）', async () => {
  if (meProbe.status !== 200) throw new Error(`HTTP ${meProbe.status} code=${meProbe.code ?? '-'}`);
  const s = meProbe.data ?? {};
  const required = [
    'userId', 'tierCode', 'tierName', 'dailyLimit', 'dailyUsed', 'dailyRemaining',
    'totalLimit', 'totalUsed', 'totalRemaining', 'grantedTokens', 'unlimited', 'resetAt',
  ];
  const missing = required.filter((k) => !(k in s));
  if (missing.length > 0) throw new Error(`快照缺少字段: ${missing.join(',')}`);
  console.log(gray(`        tier=${s.tierCode} daily=${s.dailyUsed}/${s.dailyLimit} unlimited=${s.unlimited}`));
  return s;
});

const tiers = await check('GET /quota/tiers（内置套餐）', async () => {
  const list = await api('GET', '/quota/tiers');
  const arr = Array.isArray(list) ? list : [];
  if (arr.length === 0) throw new Error('套餐列表为空');
  const codes = arr.map((t) => t.code);
  if (!codes.includes('FREE')) throw new Error(`缺少内置套餐 FREE，实际: ${codes.join(',')}`);
  console.log(gray(`        tiers=${codes.join(',')}`));
  return arr;
});

// ---------- 2. 准备临时数据 ----------
const agent = await check('POST /agents（临时智能体）', async () => {
  const a = await api('POST', '/agents', {
    name: `quota-e2e-agent-${stamp}`,
    description: 'quota e2e agent',
    type: 'SINGLE',
    systemPrompt: '你是额度验收助手，只回答一句话。',
    temperature: 0.7,
    maxIterations: 1,
    enabled: true,
  });
  if (!a?.id) throw new Error('响应缺少 id');
  created.agents.push(a.id);
  return a;
});
if (!agent) finish();

const tier = await check('POST /quota/tiers（临时套餐）', async () => {
  const t = await api('POST', '/quota/tiers', {
    code: `QE2E${stamp36}`.slice(0, 32),
    name: '额度验收临时套餐',
    description: 'quota-e2e 临时数据，验收后删除',
    dailyTokenLimit: 100000,
    totalTokenLimit: 1000000,
    sortOrder: 999,
    enabled: true,
  });
  if (!t?.id) throw new Error('响应缺少 id');
  created.tiers.push(t.id);
  return t;
});
if (!tier) finish();

const username = `qe2e${stamp36}`.toLowerCase();
const email = `quota-e2e-${stamp}@example.com`;
const user = await check('POST /users（临时用户，默认 USER 角色）', async () => {
  const u = await api('POST', '/users', {
    username,
    email,
    password: USER_PASSWORD,
    nickname: '额度验收临时用户',
  });
  if (!u?.id) throw new Error('响应缺少 id');
  created.users.push(u.id);
  return u;
});
if (!user) finish();

await check('GET /quota/users?keyword（能搜到临时用户）', async () => {
  const p = await api('GET', `/quota/users?page=1&size=20&keyword=${encodeURIComponent(username)}`);
  const records = p?.records ?? [];
  const hit = records.find((r) => r.userId === user.id || r.id === user.id);
  if (!hit) throw new Error(`用户额度列表未找到 userId=${user.id}`);
  return hit;
});

const oneLimit = await check('PUT /quota/users/{id}（每日额度=1）', async () => {
  const s = await api('PUT', `/quota/users/${user.id}`, { tierId: tier.id, dailyLimit: 1, totalLimit: null });
  if (Number(s?.dailyLimit) !== 1) throw new Error(`dailyLimit 期望 1，实际 ${s?.dailyLimit}`);
  return s;
});
if (!oneLimit) finish();

// ---------- 3. 临时用户登录并触发超额 ----------
await check('POST /auth/login（临时用户）', async () => {
  const data = await api('POST', '/auth/login', { account: username, password: USER_PASSWORD }, null);
  if (!data?.accessToken) throw new Error('响应缺少 accessToken');
  userToken = data.accessToken;
  return data;
});
if (!userToken) finish();

await check('GET /quota/me（临时用户：dailyLimit=1）', async () => {
  const s = await api('GET', '/quota/me', undefined, userToken);
  if (Number(s?.dailyLimit) !== 1) throw new Error(`dailyLimit 期望 1，实际 ${s?.dailyLimit}`);
  console.log(gray(`        dailyRemaining=${s.dailyRemaining}`));
  return s;
});

await check('POST /chat/stream（第一次对话放行）', async () => {
  const r = await chat(userToken, agent.id, '你好，请用一句话介绍你自己。');
  if (r.status !== 200) throw new Error(`HTTP ${r.status}: ${r.text.slice(0, 200)}`);
  if (!/event: ?message\.delta/.test(r.text)) throw new Error('未收到 message.delta 事件');
  if (!/event: ?run\.completed/.test(r.text)) throw new Error('未收到 run.completed 事件');
  if (/event: ?error/.test(r.text)) throw new Error(`SSE 返回 error 事件: ${r.text.slice(0, 300)}`);
});

// 等待用量落库（审计写入可能有毫秒级延迟），再验证下一次预检。
await sleep(800);
await check('POST /chat/stream（第二次对话 → 429/42901）', async () => {
  const r = await chat(userToken, agent.id, '再回答一次：1+1 等于几？');
  if (r.status !== 429) throw new Error(`HTTP 期望 429，实际 ${r.status}: ${r.text.slice(0, 200)}`);
  let code = null;
  try {
    code = JSON.parse(r.text)?.code;
  } catch {
    code = null;
  }
  if (Number(code) !== 42901) throw new Error(`业务码期望 42901，实际 ${code}: ${r.text.slice(0, 200)}`);
});

// ---------- 4. 手动分配后仍受每日额度约束 ----------
await check('POST /quota/users/{id}/grant（+1000，管理员）', async () => {
  const s = await api('POST', `/quota/users/${user.id}/grant`, { amount: 1000, reason: 'quota-e2e 手动追加额度' });
  if (Number(s?.grantedTokens ?? 0) < 1000) throw new Error(`grantedTokens 期望 >=1000，实际 ${s?.grantedTokens}`);
  return s;
});

await check('POST /chat/stream（grant 后每日仍用尽 → 429/42901）', async () => {
  const r = await chat(userToken, agent.id, '额度追加后每日额度仍应拦截。');
  if (r.status !== 429) throw new Error(`HTTP 期望 429，实际 ${r.status}: ${r.text.slice(0, 200)}`);
  let code = null;
  try {
    code = JSON.parse(r.text)?.code;
  } catch {
    code = null;
  }
  if (Number(code) !== 42901) throw new Error(`业务码期望 42901，实际 ${code}`);
});

// ---------- 5. 每日额度改为不限后放行 ----------
await check('PUT /quota/users/{id}（dailyLimit=0 不限）', async () => {
  const s = await api('PUT', `/quota/users/${user.id}`, { tierId: tier.id, dailyLimit: 0, totalLimit: null });
  if (Number(s?.dailyLimit) !== 0) throw new Error(`dailyLimit 期望 0，实际 ${s?.dailyLimit}`);
  return s;
});

await check('POST /chat/stream（不限后放行）', async () => {
  const r = await chat(userToken, agent.id, '现在应该可以继续对话了，请回复一句话。');
  if (r.status !== 200) throw new Error(`HTTP ${r.status}: ${r.text.slice(0, 200)}`);
  if (!/event: ?run\.completed/.test(r.text)) throw new Error('未收到 run.completed 事件');
  if (/event: ?error/.test(r.text)) throw new Error(`SSE 返回 error 事件: ${r.text.slice(0, 300)}`);
});

await check('GET /quota/users/{id}/grants（流水包含 1000）', async () => {
  const p = await api('GET', `/quota/users/${user.id}/grants?page=1&size=10`);
  const records = p?.records ?? [];
  const hit = records.find((g) => Number(g.amount) === 1000);
  if (!hit) throw new Error(`流水未找到 amount=1000：${JSON.stringify(records).slice(0, 200)}`);
  console.log(gray(`        grants=${records.length} reason=${hit.reason ?? '-'}`));
  return hit;
});

await check('GET /quota/me（临时用户：每日不限）', async () => {
  const s = await api('GET', '/quota/me', undefined, userToken);
  if (Number(s?.dailyLimit) !== 0) throw new Error(`dailyLimit 期望 0，实际 ${s?.dailyLimit}`);
  if (Number(s?.dailyRemaining) !== -1) throw new Error(`dailyRemaining 期望 -1，实际 ${s?.dailyRemaining}`);
  return s;
});

// ---------- 6. 清理 ----------
await cleanup();
console.log(gray(`\n清理：用户 ${created.users.length} 个、套餐 ${created.tiers.length} 个、智能体 ${created.agents.length} 个（尽力而为）。`));

finish();
