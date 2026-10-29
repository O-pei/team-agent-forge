/**
 * 演示数据兜底（后端未就绪时保证 UI 可完整走查）
 *
 * 设计：页面用 withMock(source, () => api.xxx(), () => mockXxx()) 包裹真实请求；
 * 仅当「网络不可达 / 404 / 5xx / 50300」且 VITE_ENABLE_MOCK !== 'false' 时切换演示数据，
 * 并在顶栏显示「演示数据」标记，避免把业务错误误当后端未就绪。
 */
import { reactive } from 'vue'
import { isFallbackError } from '@/utils/request'
import type {
  AgentInfo,
  AgentTool,
  AuditOverview,
  Datasource,
  KnowledgeBase,
  KbDocument,
  LoginResult,
  MeResult,
  ModelConfig,
  PermissionNode,
  RoleInfo,
  SandboxExecuteResult,
  SandboxProviderInfo,
  SchemaTable,
  SearchHit,
  SqlQueryLog,
  SqlQueryResult,
  UsageBreakdownItem,
  UsageRecord,
  UsageSummary,
  UsageTrendPoint,
  UserInfo,
} from '@/types'

export const mockEnabled = import.meta.env.DEV && import.meta.env.VITE_ENABLE_MOCK !== 'false'

const mockSources = reactive<string[]>([])

export const mockState = {
  get active(): boolean {
    return mockSources.length > 0
  },
  get sources(): string[] {
    return [...mockSources]
  },
  note(source: string): void {
    if (!mockSources.includes(source)) mockSources.push(source)
  },
  reset(): void {
    mockSources.splice(0, mockSources.length)
  },
}

/** 真实请求失败且属于「后端未就绪」时用演示数据兜底 */
export async function withMock<T>(
  source: string,
  loader: () => Promise<T>,
  factory: () => T | Promise<T>,
): Promise<{ data: T; mock: boolean }> {
  try {
    return { data: await loader(), mock: false }
  } catch (error) {
    if (!mockEnabled || !isFallbackError(error)) throw error
    mockState.note(source)
    return { data: await factory(), mock: true }
  }
}

/* ------------------------------ 基础造数 ------------------------------ */

const NOW = Date.now()

function iso(minutesAgo = 0): string {
  const d = new Date(NOW - minutesAgo * 60_000)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(
    d.getMinutes(),
  )}:${p(d.getSeconds())}`
}

function pseudo(seed: number): number {
  const x = Math.sin(seed * 9301 + 49297) * 233280
  return x - Math.floor(x)
}

function pick<T>(list: T[], seed: number): T {
  return list[Math.floor(pseudo(seed) * list.length) % list.length]
}

/* ------------------------------ 权限 ------------------------------ */

/** 契约附录 A 全量权限码 */
export const ALL_PERMISSIONS = [
  'user:read',
  'user:write',
  'role:read',
  'role:write',
  'audit:read',
  'model:read',
  'model:write',
  'agent:read',
  'agent:write',
  'agent:run',
  'rag:read',
  'rag:write',
  'datasource:read',
  'datasource:write',
  'datasource:query',
  'memory:read',
  'memory:write',
]

const USER_INFO: UserInfo = {
  id: 1,
  email: 'admin@agentforge.dev',
  username: 'admin',
  nickname: '超级管理员',
  status: 'ACTIVE',
  roles: ['SUPER_ADMIN'],
  createdAt: iso(60 * 24 * 30),
  lastLoginAt: iso(3),
}

export function mockLogin(account: string): LoginResult {
  return {
    accessToken: `mock-access-token.${Date.now()}`,
    refreshToken: 'mock-refresh-token',
    expiresIn: 7200,
    user: { ...USER_INFO, username: account || USER_INFO.username },
    roles: ['SUPER_ADMIN'],
    permissions: ALL_PERMISSIONS,
  }
}

export function mockMe(): MeResult {
  return {
    user: { ...USER_INFO },
    roles: ['SUPER_ADMIN'],
    permissions: ALL_PERMISSIONS,
    menus: [],
  }
}

/* ------------------------------ Agent / 工具 ------------------------------ */

const AGENTS: AgentInfo[] = [
  {
    id: 1,
    name: '客服助手',
    description: '基于知识库回答售前售后问题，必要时转人工',
    type: 'REACT',
    modelId: 1,
    systemPrompt: '你是 AgentForge 的客服助手，回答必须基于检索到的知识库内容，并给出引用。',
    status: 'PUBLISHED',
    tools: ['knowledge_search', 'send_email'],
    version: 3,
    publishedVersion: 3,
    temperature: 0.3,
    maxSteps: 8,
    createdAt: iso(60 * 24 * 12),
    updatedAt: iso(60 * 5),
  },
  {
    id: 2,
    name: '数据分析师',
    description: '把自然语言转成 SQL，查询数据源并输出分析结论',
    type: 'REACT',
    modelId: 1,
    systemPrompt: '你是数据分析师。生成 SQL 前先查看 schema，只允许 SELECT，输出表格与分析结论。',
    status: 'PUBLISHED',
    tools: ['sql_query', 'python_sandbox'],
    version: 2,
    publishedVersion: 2,
    temperature: 0.2,
    maxSteps: 10,
    createdAt: iso(60 * 24 * 8),
    updatedAt: iso(60 * 20),
  },
  {
    id: 3,
    name: '内容运营助手',
    description: '营销文案生成与多平台改写（草稿）',
    type: 'SEQUENTIAL',
    modelId: 2,
    systemPrompt: '你是内容运营，输出简洁有感染力的中文文案。',
    status: 'DRAFT',
    tools: ['web_search'],
    version: 1,
    temperature: 0.7,
    maxSteps: 5,
    createdAt: iso(60 * 26),
    updatedAt: iso(60 * 26),
  },
  {
    id: 4,
    name: '运维巡检助手',
    description: '执行只读命令、汇总主机状态',
    type: 'REACT',
    modelId: 1,
    systemPrompt: '你是运维助手，命令必须只读，高风险操作需人工审批。',
    status: 'PUBLISHED',
    tools: ['shell_sandbox', 'http_request'],
    version: 4,
    publishedVersion: 4,
    temperature: 0.1,
    maxSteps: 6,
    createdAt: iso(60 * 24 * 20),
    updatedAt: iso(60 * 2),
  },
]

const TOOLS: AgentTool[] = [
  { name: 'knowledge_search', description: '向量检索知识库', riskLevel: 'LOW', source: 'RAG' },
  { name: 'sql_query', description: '对数据源执行只读 SQL', riskLevel: 'LOW', source: 'Datasource' },
  { name: 'python_sandbox', description: '在沙箱执行 Python 代码', riskLevel: 'MEDIUM', source: 'Sandbox' },
  { name: 'shell_sandbox', description: '在沙箱执行 Shell 命令', riskLevel: 'HIGH', source: 'Sandbox' },
  { name: 'http_request', description: '发起外部 HTTP 请求', riskLevel: 'HIGH', source: 'Builtin' },
  { name: 'send_email', description: '发送邮件通知', riskLevel: 'MEDIUM', source: 'Builtin' },
  { name: 'web_search', description: '联网搜索', riskLevel: 'LOW', source: 'Builtin' },
  { name: 'calculator', description: '数学计算', riskLevel: 'LOW', source: 'Builtin' },
]

export function mockAgents(): AgentInfo[] {
  return AGENTS.map((item) => ({ ...item }))
}

export function mockAgent(id: number | string): AgentInfo {
  return { ...(AGENTS.find((a) => String(a.id) === String(id)) || AGENTS[0]) }
}

export function mockTools(): AgentTool[] {
  return TOOLS.map((item) => ({ ...item }))
}

/* ------------------------------ 知识库 ------------------------------ */

const KBS: KnowledgeBase[] = [
  {
    id: 1,
    name: '产品手册',
    description: 'AgentForge 产品功能与使用说明',
    embeddingModelId: 3,
    embeddingModel: 'text-embedding-v3',
    documentCount: 12,
    chunkCount: 486,
    createdAt: iso(60 * 24 * 18),
    updatedAt: iso(60 * 4),
  },
  {
    id: 2,
    name: '售后 FAQ',
    description: '常见售后问题与标准答复',
    embeddingModelId: 3,
    embeddingModel: 'text-embedding-v3',
    documentCount: 8,
    chunkCount: 214,
    createdAt: iso(60 * 24 * 10),
    updatedAt: iso(60 * 30),
  },
  {
    id: 3,
    name: '内部制度',
    description: '报销、考勤、假期制度（ACL 受限）',
    embeddingModelId: 3,
    embeddingModel: 'text-embedding-v3',
    documentCount: 6,
    chunkCount: 132,
    createdAt: iso(60 * 24 * 5),
    updatedAt: iso(60 * 48),
  },
]

export function mockKnowledgeBases(): KnowledgeBase[] {
  return KBS.map((item) => ({ ...item }))
}

export function mockDocuments(kbId: number | string): KbDocument[] {
  const base = KBS.find((k) => String(k.id) === String(kbId)) || KBS[0]
  const names = [
    '产品功能总览.md',
    '快速上手.pdf',
    'API 使用说明.docx',
    '常见问题汇总.md',
    '部署运维手册.pdf',
    '版本更新日志.md',
  ]
  const statuses = ['READY', 'READY', 'PARSING', 'READY', 'FAILED', 'READY']
  return names.map((filename, index) => ({
    id: Number(kbId) * 100 + index + 1,
    knowledgeBaseId: base.id,
    filename,
    fileSize: 12_000 + index * 48_500,
    status: statuses[index],
    chunkCount: statuses[index] === 'READY' ? 30 + index * 7 : 0,
    progress: statuses[index] === 'READY' ? 100 : statuses[index] === 'PARSING' ? 46 : 0,
    errorMessage: statuses[index] === 'FAILED' ? '文件解析失败：PDF 加密或格式不支持' : undefined,
    createdAt: iso(60 * (index + 2)),
    updatedAt: iso(60 * index),
  }))
}

export function mockSearchHits(query: string): SearchHit[] {
  return [
    {
      content: `关于「${query}」：AgentForge 支持在创建 Agent 时绑定多个知识库，检索结果会自动注入到上下文并附带引用信息。`,
      score: 0.9123,
      documentId: 101,
      chunkIndex: 12,
      filename: '产品功能总览.md',
    },
    {
      content: `「${query}」相关配置位于「模型配置」页，provider 支持 DASHSCOPE / OPENAI_COMPATIBLE / MOCK，无密钥时会自动降级为 Mock 模型。`,
      score: 0.8742,
      documentId: 103,
      chunkIndex: 4,
      filename: 'API 使用说明.docx',
    },
    {
      content: `若检索无结果，请检查文档状态是否为 READY，并确认 embedding 模型已正确配置；分块与向量可通过「重建索引」刷新。`,
      score: 0.8123,
      documentId: 104,
      chunkIndex: 21,
      filename: '常见问题汇总.md',
    },
    {
      content: `部署环境需设置 JWT 密钥、AES master key 等环境变量；缺失时服务会以 dev 默认值启动并打印告警。`,
      score: 0.7655,
      documentId: 105,
      chunkIndex: 7,
      filename: '部署运维手册.pdf',
    },
  ]
}

/* ------------------------------ 数据源 / SQL ------------------------------ */

const DATASOURCES: Datasource[] = [
  {
    id: 1,
    name: '本地演示库',
    type: 'H2',
    jdbcUrl: 'jdbc:h2:file:./.data/agentforge;MODE=MySQL',
    username: 'sa',
    maxPoolSize: 5,
    status: 'UP',
    createdAt: iso(60 * 24 * 22),
  },
  {
    id: 2,
    name: '订单库（MySQL）',
    type: 'MYSQL',
    jdbcUrl: 'jdbc:mysql://127.0.0.1:3306/order_db?useSSL=false',
    username: 'readonly',
    maxPoolSize: 10,
    status: 'UP',
    createdAt: iso(60 * 24 * 9),
  },
  {
    id: 3,
    name: '分析库（PostgreSQL）',
    type: 'POSTGRESQL',
    jdbcUrl: 'jdbc:postgresql://127.0.0.1:5432/analytics',
    username: 'analyst',
    maxPoolSize: 10,
    status: 'DOWN',
    createdAt: iso(60 * 24 * 3),
  },
]

export function mockDatasources(): Datasource[] {
  return DATASOURCES.map((item) => ({ ...item }))
}

export function mockSchema(datasourceId: number | string): SchemaTable[] {
  return [
    {
      name: 'af_user',
      comment: '用户表',
      primaryKey: 'id',
      columns: [
        { name: 'id', type: 'BIGINT', comment: '主键', primaryKey: true },
        { name: 'username', type: 'VARCHAR(64)', comment: '用户名' },
        { name: 'email', type: 'VARCHAR(128)', comment: '邮箱' },
        { name: 'status', type: 'VARCHAR(16)', comment: '状态' },
        { name: 'created_at', type: 'DATETIME', comment: '创建时间' },
      ],
    },
    {
      name: 'ai_token_usage',
      comment: 'Token 用量明细',
      primaryKey: 'id',
      columns: [
        { name: 'id', type: 'BIGINT', comment: '主键', primaryKey: true },
        { name: 'model_id', type: 'BIGINT', comment: '模型 ID' },
        { name: 'agent_id', type: 'BIGINT', comment: 'Agent ID' },
        { name: 'prompt_tokens', type: 'BIGINT', comment: '输入 Token' },
        { name: 'completion_tokens', type: 'BIGINT', comment: '输出 Token' },
        { name: 'total_tokens', type: 'BIGINT', comment: '总 Token' },
        { name: 'cost', type: 'DECIMAL(12,6)', comment: '成本（USD）' },
        { name: 'created_at', type: 'DATETIME', comment: '创建时间' },
      ],
    },
    {
      name: 'kb_document',
      comment: '知识库文档',
      primaryKey: 'id',
      columns: [
        { name: 'id', type: 'BIGINT', comment: '主键', primaryKey: true },
        { name: 'knowledge_base_id', type: 'BIGINT', comment: '知识库 ID' },
        { name: 'filename', type: 'VARCHAR(255)', comment: '文件名' },
        { name: 'status', type: 'VARCHAR(16)', comment: '解析状态' },
        { name: 'chunk_count', type: 'INT', comment: '分块数' },
      ],
    },
  ]
}

export function mockQueryResult(): SqlQueryResult {
  const usernames = ['admin', 'alice', 'bob', 'carol', 'david', 'erin', 'frank', 'grace']
  const rows = usernames.map((name, index) => [
    index + 1,
    name,
    `${name}@agentforge.dev`,
    index % 5 === 0 ? 'DISABLED' : 'ACTIVE',
    iso(60 * (index + 1)).replace('T', ' '),
  ])
  return {
    columns: ['id', 'username', 'email', 'status', 'created_at'],
    rows,
    elapsedMs: 37,
    truncated: false,
  }
}

export function mockQueryLogs(): SqlQueryLog[] {
  const sqls = [
    'SELECT id, username, email, status FROM af_user LIMIT 20',
    'SELECT model_id, SUM(total_tokens) AS tokens FROM ai_token_usage GROUP BY model_id LIMIT 100',
    'SELECT COUNT(*) AS total FROM kb_document WHERE status = \'READY\' LIMIT 1',
    'SELECT id, filename, chunk_count FROM kb_document ORDER BY created_at DESC LIMIT 20',
    'SELECT * FROM agent_run WHERE status = \'FAILED\' LIMIT 50',
  ]
  return Array.from({ length: 8 }).map((_, index) => ({
    id: 100 + index,
    datasourceId: (index % 3) + 1,
    sql: sqls[index % sqls.length],
    success: index !== 5,
    elapsedMs: 12 + index * 9,
    rowCount: index === 5 ? 0 : 3 + index * 4,
    username: index % 2 === 0 ? 'admin' : 'alice',
    createdAt: iso(index * 47),
  }))
}

/* ------------------------------ 模型 ------------------------------ */

const MODELS: ModelConfig[] = [
  {
    id: 1,
    name: '通义千问 Plus',
    provider: 'DASHSCOPE',
    modelName: 'qwen-plus',
    apiKeyMasked: 'sk-****9f2c',
    enabled: true,
    defaultModel: true,
    createdAt: iso(60 * 24 * 24),
    updatedAt: iso(60 * 6),
  },
  {
    id: 2,
    name: 'GPT-4o mini（兼容）',
    provider: 'OPENAI_COMPATIBLE',
    modelName: 'gpt-4o-mini',
    baseUrl: 'https://api.openai.com/v1',
    apiKeyMasked: 'sk-****1a7d',
    enabled: true,
    defaultModel: false,
    createdAt: iso(60 * 24 * 10),
    updatedAt: iso(60 * 30),
  },
  {
    id: 3,
    name: 'Mock 模型（零配置）',
    provider: 'MOCK',
    modelName: 'mock-chat',
    enabled: true,
    defaultModel: false,
    createdAt: iso(60 * 24 * 30),
    updatedAt: iso(60 * 24 * 2),
  },
]

export function mockModels(): ModelConfig[] {
  return MODELS.map((item) => ({ ...item }))
}

/* ------------------------------ 审计 ------------------------------ */

export function mockAuditOverview(): AuditOverview {
  return {
    totalRequests: 12846,
    totalTokens: 8_732_450,
    totalCost: 12.4831,
    successRate: 0.9872,
    totalAgents: 4,
    totalUsers: 8,
    totalKnowledgeBases: 3,
    totalDocuments: 26,
    todayRequests: 1263,
    todayTokens: 812_340,
    todayCost: 1.2345,
  }
}

export function mockUsageSummary(): UsageSummary {
  return {
    totalPromptTokens: 5_867_230,
    totalCompletionTokens: 2_865_220,
    totalTokens: 8_732_450,
    totalCost: 12.4831,
    requests: 12846,
  }
}

export function mockUsageTrend(granularity: 'day' | 'hour'): UsageTrendPoint[] {
  if (granularity === 'hour') {
    return Array.from({ length: 24 }).map((_, index) => {
      const requests = Math.round(30 + pseudo(index + 3) * 90)
      const totalTokens = Math.round(requests * (380 + pseudo(index + 11) * 260))
      return {
        time: `${String(index).padStart(2, '0')}:00`,
        promptTokens: Math.round(totalTokens * 0.67),
        completionTokens: Math.round(totalTokens * 0.33),
        totalTokens,
        cost: Number((totalTokens * 0.00000149).toFixed(5)),
        requests,
      }
    })
  }
  return Array.from({ length: 14 }).map((_, index) => {
    const day = new Date(NOW - (13 - index) * 86_400_000)
    const label = `${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`
    const requests = Math.round(420 + pseudo(index + 5) * 680)
    const totalTokens = Math.round(requests * (430 + pseudo(index + 17) * 220))
    return {
      time: label,
      promptTokens: Math.round(totalTokens * 0.66),
      completionTokens: Math.round(totalTokens * 0.34),
      totalTokens,
      cost: Number((totalTokens * 0.00000142).toFixed(5)),
      requests,
    }
  })
}

export function mockUsageBreakdown(groupBy: 'model' | 'agent' | 'user'): UsageBreakdownItem[] {
  const names =
    groupBy === 'model'
      ? ['qwen-plus', 'gpt-4o-mini', 'mock-chat']
      : groupBy === 'agent'
        ? ['客服助手', '数据分析师', '运维巡检助手', '内容运营助手']
        : ['admin', 'alice', 'bob', 'carol', 'david']
  return names.map((label, index) => ({
    key: index + 1,
    label,
    totalTokens: Math.round(2_800_000 / (index + 1.4) + pseudo(index + 30) * 60_000),
    cost: Number((1.8 / (index + 1.2)).toFixed(4)),
    requests: Math.round(4200 / (index + 1.3)),
  }))
}

export function mockUsageRecords(page = 1, size = 20): { records: UsageRecord[]; total: number } {
  const total = 137
  const records: UsageRecord[] = Array.from({ length: Math.min(size, total - (page - 1) * size) }).map(
    (_, index) => {
      const seq = (page - 1) * size + index
      const promptTokens = 320 + Math.round(pseudo(seq + 1) * 1600)
      const completionTokens = 150 + Math.round(pseudo(seq + 2) * 900)
      const totalTokens = promptTokens + completionTokens
      return {
        id: 10_000 + seq,
        modelId: (seq % 3) + 1,
        modelName: pick(['qwen-plus', 'gpt-4o-mini', 'mock-chat'], seq),
        agentId: (seq % 4) + 1,
        agentName: pick(['客服助手', '数据分析师', '内容运营助手', '运维巡检助手'], seq + 1),
        userId: (seq % 5) + 1,
        username: pick(['admin', 'alice', 'bob', 'carol', 'david'], seq + 2),
        promptTokens,
        completionTokens,
        totalTokens,
        cost: Number((totalTokens * 0.00000145).toFixed(6)),
        stream: seq % 2 === 0,
        success: seq !== 7,
        latencyMs: 480 + Math.round(pseudo(seq + 3) * 2400),
        createdAt: iso(seq * 7),
      }
    },
  )
  return { records, total }
}

/* ------------------------------ RBAC ------------------------------ */

const USERS: UserInfo[] = [
  { id: 1, email: 'admin@agentforge.dev', username: 'admin', nickname: '超级管理员', status: 'ACTIVE', roles: ['SUPER_ADMIN'], createdAt: iso(60 * 24 * 30) },
  { id: 2, email: 'alice@agentforge.dev', username: 'alice', nickname: 'Alice', status: 'ACTIVE', roles: ['ADMIN'], createdAt: iso(60 * 24 * 18) },
  { id: 3, email: 'bob@agentforge.dev', username: 'bob', nickname: 'Bob', status: 'ACTIVE', roles: ['USER'], createdAt: iso(60 * 24 * 12) },
  { id: 4, email: 'carol@agentforge.dev', username: 'carol', nickname: 'Carol', status: 'DISABLED', roles: ['USER'], createdAt: iso(60 * 24 * 9) },
  { id: 5, email: 'david@agentforge.dev', username: 'david', nickname: 'David', status: 'ACTIVE', roles: ['USER'], createdAt: iso(60 * 24 * 6) },
  { id: 6, email: 'erin@agentforge.dev', username: 'erin', nickname: 'Erin', status: 'ACTIVE', roles: ['USER'], createdAt: iso(60 * 24 * 4) },
  { id: 7, email: 'frank@agentforge.dev', username: 'frank', nickname: 'Frank', status: 'ACTIVE', roles: ['USER'], createdAt: iso(60 * 24 * 2) },
  { id: 8, email: 'grace@agentforge.dev', username: 'grace', nickname: 'Grace', status: 'DISABLED', roles: ['USER'], createdAt: iso(60) },
]

export function mockUsers(): UserInfo[] {
  return USERS.map((item) => ({ ...item }))
}

const ROLES: RoleInfo[] = [
  {
    id: 1,
    code: 'SUPER_ADMIN',
    name: '超级管理员',
    description: '拥有全部权限',
    builtIn: true,
    permissions: [...ALL_PERMISSIONS],
  },
  {
    id: 2,
    code: 'ADMIN',
    name: '管理员',
    description: '除用户/角色/模型写入外的管理权限',
    builtIn: true,
    permissions: ALL_PERMISSIONS.filter(
      (code) => !['user:write', 'role:write', 'model:write'].includes(code),
    ),
  },
  {
    id: 3,
    code: 'USER',
    name: '普通用户',
    description: '业务功能只读/运行权限',
    builtIn: true,
    permissions: ['agent:read', 'agent:run', 'rag:read', 'datasource:read', 'datasource:query', 'memory:read'],
  },
]

export function mockRoles(): RoleInfo[] {
  return ROLES.map((item) => ({ ...item, permissions: [...(item.permissions || [])] }))
}

/** 权限树：父节点为模块分组（id 100+），子节点为真实权限码 */
export function mockPermissionTree(): PermissionNode[] {
  const groups: Array<{ name: string; prefix: string }> = [
    { name: '用户管理', prefix: 'user' },
    { name: '角色权限', prefix: 'role' },
    { name: '审计看板', prefix: 'audit' },
    { name: '模型配置', prefix: 'model' },
    { name: 'Agent 管理', prefix: 'agent' },
    { name: '知识库', prefix: 'rag' },
    { name: '数据源', prefix: 'datasource' },
    { name: '记忆', prefix: 'memory' },
  ]
  let leafId = 1
  return groups.map((group, groupIndex) => {
    const leaves = ALL_PERMISSIONS.filter((code) => code.startsWith(`${group.prefix}:`)).map(
      (code) => ({
        id: leafId++,
        code,
        name: permissionLabel(code),
        type: 'BUTTON',
        parentId: 100 + groupIndex,
      }),
    )
    return {
      id: 100 + groupIndex,
      code: `${group.prefix}:group`,
      name: group.name,
      type: 'MENU',
      parentId: null,
      children: leaves,
    }
  })
}

function permissionLabel(code: string): string {
  const labels: Record<string, string> = {
    'user:read': '查看用户',
    'user:write': '新增/编辑/禁用用户',
    'role:read': '查看角色与权限',
    'role:write': '维护角色与权限',
    'audit:read': '查看审计与用量',
    'model:read': '查看模型配置',
    'model:write': '维护模型配置/连通性测试',
    'agent:read': '查看 Agent',
    'agent:write': '维护 Agent',
    'agent:run': '运行/对话',
    'rag:read': '查看知识库与检索',
    'rag:write': '维护知识库与文档',
    'datasource:read': '查看数据源/Schema',
    'datasource:write': '维护数据源',
    'datasource:query': '执行只读 SQL/沙箱',
    'memory:read': '查看记忆',
    'memory:write': '清理记忆',
  }
  return labels[code] || code
}

/* ------------------------------ 沙箱 ------------------------------ */

export function mockSandboxProviders(): SandboxProviderInfo[] {
  return [
    {
      name: 'process',
      type: 'PROCESS',
      available: true,
      description: '本地进程沙箱：临时目录 + 超时 kill + 输出截断',
      languages: ['java', 'python', 'node', 'shell'],
      capabilities: ['timeout', 'output-limit', 'temp-dir'],
    },
    {
      name: 'docker',
      type: 'DOCKER',
      available: false,
      description: 'Docker 隔离沙箱（未检测到 Docker，自动降级为 process）',
      languages: ['java', 'python', 'node', 'shell'],
      capabilities: ['network-isolation', 'readonly-fs'],
    },
  ]
}

export function mockSandboxResult(language: string, code: string): SandboxExecuteResult {
  const stdoutByLanguage: Record<string, string> = {
    java: 'Hello from AgentForge Java sandbox!\nsum(1..10) = 55\n',
    python: 'Hello from AgentForge Python sandbox!\nsum(1..10) = 55\n',
    node: 'Hello from AgentForge Node sandbox!\nsum(1..10) = 55\n',
    shell: 'agentforge sandbox ready\nuname: Linux\n',
  }
  return {
    success: true,
    stdout: stdoutByLanguage[language] || `已执行 ${language} 代码（${code.length} 字符）\n`,
    stderr: '',
    exitCode: 0,
    elapsedMs: 168,
    truncated: false,
  }
}
