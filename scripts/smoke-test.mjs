#!/usr/bin/env node
/**
 * AgentForge v0.1.0 端到端冒烟测试（真实 HTTP，按 docs/04-API契约.md 校验）。
 *
 * 用法：
 *   node scripts/smoke-test.mjs
 *   node scripts/smoke-test.mjs --base-url http://127.0.0.1:18080 --account admin@agentforge.dev --password 'Admin@123456'
 *
 * 前置：后端以 dev profile 启动（H2 + Mock 模型），Flyway V1~V6 已应用。
 * 说明：只创建带时间戳前缀的临时数据；某模块未就绪记为 SKIP，行为不符合契约记为 FAIL。
 * 退出码：0 = 全部 PASS/SKIP；1 = 存在 FAIL。
 */
import { setTimeout as sleep } from 'node:timers/promises';

const args = process.argv.slice(2);
function arg(name, fallback) {
  const i = args.indexOf(`--${name}`);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
}
const BASE = arg('base-url', process.env.AF_SMOKE_BASE ?? 'http://127.0.0.1:18080');
const PREFIX = arg('api-prefix', '/api/v1');
const ACCOUNT = arg('account', 'admin@agentforge.dev');
const PASSWORD = arg('password', 'Admin@123456');

const stats = { pass: 0, skip: 0, fail: 0 };
const failures = [];
let token = null;
const stamp = Date.now();
const created = { agents: [], kbs: [], datasources: [] };

const color = (code, s) => (process.stdout.isTTY ? `\u001b[${code}m${s}\u001b[0m` : s);
const green = (s) => color(32, s);
const red = (s) => color(31, s);
const yellow = (s) => color(33, s);
const gray = (s) => color(90, s);

function step(name, status, detail = '') {
  const tag = status === 'PASS' ? green('PASS') : status === 'FAIL' ? red('FAIL') : yellow('SKIP');
  console.log(`[${tag}] ${name.padEnd(52)} ${detail}`);
  if (status === 'PASS') stats.pass++;
  else if (status === 'FAIL') {
    stats.fail++;
    failures.push(`${name} :: ${detail}`);
  } else stats.skip++;
}

async function api(method, path, body, extraHeaders = {}, timeoutMs = 30000) {
  const headers = { Accept: 'application/json', ...extraHeaders };
  if (token) headers.Authorization = `Bearer ${token}`;
  const init = { method, headers, signal: AbortSignal.timeout(timeoutMs) };
  if (body !== undefined) {
    if (body instanceof FormData) init.body = body;
    else {
      headers['Content-Type'] = 'application/json; charset=utf-8';
      init.body = JSON.stringify(body);
    }
  }
  const res = await fetch(`${BASE}${PREFIX}${path}`, init);
  const text = await res.text();
  let parsed = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    /* 非 JSON（例如 SSE/纯文本） */
  }
  if (res.status >= 400) {
    const code = parsed?.code ?? '-';
    const msg = parsed?.message ?? text.slice(0, 200);
    throw new Error(`HTTP ${res.status} code=${code} message=${msg}`);
  }
  if (parsed && parsed.code !== undefined && parsed.code !== 0) {
    throw new Error(`code=${parsed.code} message=${parsed.message}`);
  }
  return parsed ? parsed.data : text;
}

async function check(name, fn, { optional = false } = {}) {
  try {
    const out = await fn();
    if (out === 'SKIP') {
      step(name, 'SKIP', '接口未实现/模块未就绪');
      return null;
    }
    step(name, 'PASS');
    return out;
  } catch (e) {
    const detail = e.message.split('\n')[0];
    if (optional && /HTTP 404|HTTP 503|code=50300|code=40400|ECONNREFUSED/.test(detail)) {
      step(name, 'SKIP', detail);
    } else {
      step(name, 'FAIL', detail);
    }
    return null;
  }
}

async function cleanup() {
  // 仅删除本次冒烟创建的数据；失败静默（不影响结论）。
  for (const id of created.agents) await api('DELETE', `/agents/${id}`).catch(() => {});
  for (const id of created.datasources) await api('DELETE', `/datasources/${id}`).catch(() => {});
  for (const id of created.kbs) await api('DELETE', `/knowledge-bases/${id}`).catch(() => {});
}

console.log(`\n=== AgentForge smoke test @ ${BASE} ===\n`);

// ---------- 0. 存活 ----------
await check('GET /actuator/health', async () => {
  const res = await fetch(`${BASE}/actuator/health`, { signal: AbortSignal.timeout(10000) });
  if (res.status !== 200) throw new Error(`HTTP ${res.status}`);
});
await check('GET /v3/api-docs（OpenAPI）', async () => {
  const res = await fetch(`${BASE}/v3/api-docs`, { signal: AbortSignal.timeout(15000) });
  if (res.status !== 200) throw new Error(`HTTP ${res.status}`);
}, { optional: true });

// ---------- 1. 认证与 RBAC ----------
await check('POST /auth/login（默认管理员）', async () => {
  const data = await api('POST', '/auth/login', { account: ACCOUNT, password: PASSWORD });
  if (!data?.accessToken) throw new Error('响应缺少 accessToken');
  if (!data?.refreshToken) throw new Error('响应缺少 refreshToken');
  token = data.accessToken;
});

if (token) {
  await check('GET /auth/me（角色/权限/菜单）', async () => {
    const me = await api('GET', '/auth/me');
    const perms = me?.permissions ?? [];
    if (!Array.isArray(perms) || perms.length < 5) throw new Error(`permissions 数量异常: ${perms.length}`);
    if (!Array.isArray(me.roles) || me.roles.length === 0) throw new Error('缺少 roles');
    console.log(gray(`        roles=${me.roles.join(',')} perms=${perms.length}`));
  });

  await check('GET /users（分页）', async () => {
    const p = await api('GET', '/users?page=1&size=10');
    if (p?.total === undefined) throw new Error('响应缺少 total');
  });
  await check('GET /roles', () => api('GET', '/roles'));
  await check('GET /permissions', () => api('GET', '/permissions'));
  await check('GET /login-logs', () => api('GET', '/login-logs?page=1&size=5'));
  await check('POST /auth/email-code（注册场景）', async () => {
    await api('POST', '/auth/email-code', { email: `smoke-${stamp}@example.com`, scene: 'REGISTER' });
  });
  await check('POST /auth/sms-code（预留 → 50300）', async () => {
    try {
      await api('POST', '/auth/sms-code', { phone: '13800000000' });
    } catch (e) {
      if (/50300/.test(e.message)) return;
      throw e;
    }
    throw new Error('未按契约返回 50300');
  }, { optional: true });

  // ---------- 2. 模型 + Token 审计 ----------
  const models = await check('GET /models', () => api('GET', '/models'));
  const modelArr = Array.isArray(models) ? models : models ? [models] : [];
  if (modelArr.length > 0) {
    const first = modelArr[0];
    if (first?.provider) console.log(gray(`        default provider=${first.provider} model=${first.modelName}`));
    await check(`POST /models/${first.id}/test（连通性）`, () => api('POST', `/models/${first.id}/test`, undefined, {}, 60000));
  }
  await check('GET /audit/usage/summary', () => api('GET', '/audit/usage/summary'));
  await check('GET /audit/usage/trend?granularity=day', () => api('GET', '/audit/usage/trend?granularity=day'));
  await check('GET /audit/overview', () => api('GET', '/audit/overview'));

  // ---------- 3. 智能体 + SSE ----------
  const agent = await check('POST /agents（SINGLE）', () =>
    api('POST', '/agents', {
      name: `smoke-agent-${stamp}`,
      description: 'smoke test agent',
      type: 'SINGLE',
      systemPrompt: '你是冒烟测试助手，回答尽量简短。',
      temperature: 0.7,
      maxIterations: 5,
      enabled: true,
    }),
  );
  if (agent?.id) created.agents.push(agent.id);
  if (agent?.id) {
    await check('POST /chat/stream（SSE：delta + completed）', async () => {
      const res = await fetch(`${BASE}${PREFIX}/chat/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json; charset=utf-8',
          Accept: 'text/event-stream',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ agentId: agent.id, message: '你好，请用一句话介绍你自己。' }),
        signal: AbortSignal.timeout(150000),
      });
      if (res.status !== 200) throw new Error(`HTTP ${res.status}: ${(await res.text()).slice(0, 200)}`);
      const text = await res.text();
      if (!/event: ?message\.delta/.test(text)) throw new Error('未收到 message.delta 事件');
      if (!/event: ?run\.completed/.test(text)) throw new Error('未收到 run.completed 事件');
      if (/event: ?error/.test(text)) throw new Error(`SSE 返回 error 事件: ${text.slice(0, 300)}`);
      const runStarted = text.match(/event: ?run\.started\s*\ndata: (.*)/);
      if (runStarted) {
        try {
          const payload = JSON.parse(runStarted[1]);
          if (payload.runId) console.log(gray(`        runId=${payload.runId} model=${payload.model ?? '-'}`));
        } catch {
          /* ignore */
        }
      }
    });
    await check('POST /agents/{id}/run（同步运行）', () =>
      api('POST', `/agents/${agent.id}/run`, { message: '1+1 等于几？' }, {}, 90000), { optional: true });
  }
  await check('GET /tools', () => api('GET', '/tools'));

  // ---------- 4. RAG ----------
  const kb = await check('POST /knowledge-bases', () =>
    api('POST', '/knowledge-bases', { name: `smoke-kb-${stamp}`, description: 'smoke test kb' }),
  );
  if (kb?.id) {
    created.kbs.push(kb.id);
    const marker = `SMOKE_TOKEN_${stamp}`;
    const doc = await check('POST /knowledge-bases/{id}/documents（上传）', async () => {
      const form = new FormData();
      const content = `AgentForge 冒烟测试知识片段 ${stamp}。\n企业级多智能体框架支持 RAG 检索增强、Token 审计、RBAC 与安全沙箱。\n唯一校验串：${marker}\n`;
      form.append('file', new Blob([content], { type: 'text/plain' }), `smoke-${stamp}.txt`);
      return api('POST', `/knowledge-bases/${kb.id}/documents`, form, {}, 60000);
    });
    if (doc?.id) {
      await check('GET /documents/{id}（等待 READY）', async () => {
        for (let i = 0; i < 30; i++) {
          const d = await api('GET', `/documents/${doc.id}`);
          if (d?.status === 'READY') return;
          if (d?.status === 'FAILED') throw new Error(`解析失败: ${d.errorMessage ?? ''}`);
          await sleep(800);
        }
        throw new Error('等待解析超时（24s）');
      });
      await check('POST /knowledge-bases/{id}/search（命中校验）', async () => {
        const hits = await api('POST', `/knowledge-bases/${kb.id}/search`, { query: marker, topK: 3, threshold: 0.0 });
        const arr = Array.isArray(hits) ? hits : hits?.hits ?? [];
        if (arr.length === 0) throw new Error('检索结果为空');
      });
    }
  }

  // ---------- 5. 沙箱 ----------
  await check('POST /sandbox/execute（Java）', async () => {
    const r = await api(
      'POST',
      '/sandbox/execute',
      {
        language: 'java',
        code: 'public class Main { public static void main(String[] a) { System.out.println("SMOKE_SANDBOX_OK"); } }',
        timeoutSeconds: 60,
      },
      {},
      120000,
    );
    if (!String(r?.stdout ?? '').includes('SMOKE_SANDBOX_OK')) throw new Error(`stdout 未包含标记: ${r?.stdout}`);
  }, { optional: true });

  // ---------- 6. 数据源（只读 + 危险 SQL 拦截） ----------
  const ds = await check('POST /datasources（MySQL 只读）', () =>
    api('POST', '/datasources', {
      name: `smoke-ds-${stamp}`,
      type: 'MYSQL',
      jdbcUrl: arg('jdbc-url', 'jdbc:mysql://127.0.0.1:3306/agentforge_demo?useSSL=false&serverTimezone=Asia/Shanghai'),
      username: arg('db-user', 'root'),
      password: arg('db-password', 'root'),
      maxPoolSize: 3,
      readOnly: true,
    }),
  );
  await check('GET /sandbox/providers', () => api('GET', '/sandbox/providers'));
  if (ds?.id) {
    created.datasources.push(ds.id);
    await check('POST /datasources/{id}/test', () => api('POST', `/datasources/${ds.id}/test`, undefined, {}, 60000), { optional: true });
    await check('POST /datasources/{id}/query（SELECT 1）', async () => {
      const q = await api('POST', `/datasources/${ds.id}/query`, { sql: 'SELECT 1 AS one', maxRows: 10 });
      const rows = q?.rows ?? q?.data ?? [];
      if (rows.length < 1) throw new Error(`查询无结果: ${JSON.stringify(q).slice(0, 200)}`);
    }, { optional: true });
    await check('POST /datasources/{id}/query（DELETE 被拒绝）', async () => {
      try {
        await api('POST', `/datasources/${ds.id}/query`, { sql: 'DELETE FROM demo_user', maxRows: 10 });
      } catch {
        return;
      }
      throw new Error('危险 SQL 未被拒绝');
    }, { optional: true });
  }

  // ---------- 7. 记忆 ----------
  await check('GET /memory/facts', () => api('GET', '/memory/facts?page=1&size=10'), { optional: true });
  await check('GET /memory/sessions/{id}/messages', () => api('GET', `/memory/sessions/smoke-${stamp}/messages`), { optional: true });
}

await cleanup();

console.log(`\n=== 结果：PASS=${stats.pass} SKIP=${stats.skip} FAIL=${stats.fail} ===\n`);
if (stats.fail > 0) {
  console.log(red('失败明细：'));
  for (const f of failures) console.log(red(`  - ${f}`));
  process.exit(1);
}
process.exit(0);
