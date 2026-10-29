/** 前端类型定义，与 docs/04-API契约.md 字段对应。 */

/** 通用响应包装，见契约第 1 节 */
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
  traceId?: string
  timestamp?: string
}

/** 分页返回，page 从 1 开始 */
export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
}

export interface PageQuery {
  page?: number
  size?: number
}

/* 认证与用户 */

export type EmailScene = 'REGISTER' | 'LOGIN' | 'RESET_PASSWORD'

export interface EmailCodeRequest {
  email: string
  scene: EmailScene
}

/** dev 环境后端可能把验证码回显在响应里（mail-enabled=false） */
export interface EmailCodeResult {
  expiresIn?: number
  devCode?: string
  code?: string
  message?: string
}

export interface RegisterRequest {
  email: string
  username: string
  password: string
  code: string
}

export interface LoginRequest {
  /** 邮箱或用户名 */
  account: string
  password: string
}

export interface UserInfo {
  id: number | string
  email: string
  username: string
  nickname?: string
  status?: string
  roles?: string[]
  lastLoginAt?: string
  createdAt?: string
}

export interface LoginResult {
  accessToken: string
  refreshToken: string
  expiresIn: number
  user: UserInfo
  roles?: string[]
  permissions?: string[]
}

export interface RefreshRequest {
  refreshToken: string
}

export interface RefreshResult {
  accessToken: string
  refreshToken: string
  expiresIn: number
}

export interface MenuItem {
  id: number | string
  name: string
  path: string
  icon?: string
  permission?: string
  children?: MenuItem[]
}

/** GET /auth/me */
export interface MeResult {
  user: UserInfo
  roles: string[]
  permissions: string[]
  menus?: MenuItem[]
}

export interface ForgotPasswordRequest {
  email: string
}

export interface ResetPasswordRequest {
  email: string
  code: string
  newPassword: string
}

export interface UserQuery extends PageQuery {
  keyword?: string
  status?: string
}

export interface UserCreateRequest {
  email: string
  username: string
  password: string
  nickname?: string
  roleIds?: Array<number | string>
}

export interface UserUpdateRequest {
  nickname?: string
  status?: string
  roleIds?: Array<number | string>
}

export interface ResetUserPasswordRequest {
  newPassword: string
}

/* 角色与权限 */

export interface RoleInfo {
  id: number | string
  code: string
  name: string
  description?: string
  builtIn?: boolean
  permissionIds?: Array<number | string>
  permissions?: string[]
}

export interface RoleRequest {
  code?: string
  name: string
  description?: string
  permissionIds: Array<number | string>
}

export interface PermissionNode {
  id: number | string
  code: string
  name: string
  type?: string
  parentId?: number | string | null
  children?: PermissionNode[]
}

/* 模型与 Token 审计 */

export type ModelProvider = 'DASHSCOPE' | 'OPENAI_COMPATIBLE' | 'MOCK'

export interface ModelConfig {
  id: number | string
  name: string
  provider: ModelProvider
  modelName: string
  baseUrl?: string
  /** 密钥掩码，服务端不回显明文 */
  apiKeyMasked?: string
  enabled: boolean
  defaultModel: boolean
  createdAt?: string
  updatedAt?: string
}

export interface ModelRequest {
  name: string
  provider: ModelProvider
  modelName: string
  baseUrl?: string
  /** 编辑时为空表示不变 */
  apiKey?: string
  enabled: boolean
  defaultModel: boolean
}

export interface ModelTestResult {
  success: boolean
  latencyMs: number
  echo?: string
  message?: string
}

export interface AuditRangeQuery {
  from?: string
  to?: string
}

export interface UsageSummary {
  totalPromptTokens: number
  totalCompletionTokens: number
  totalTokens: number
  totalCost: number
  requests: number
}

export interface UsageTrendPoint {
  time: string
  promptTokens?: number
  completionTokens?: number
  totalTokens: number
  cost?: number
  requests?: number
}

/** 契约只写“折线图数据”，此处兼容数组与 {points} 两种形态 */
export type UsageTrendResult = UsageTrendPoint[] | { points: UsageTrendPoint[]; granularity?: string }

export interface UsageBreakdownItem {
  key: string | number
  label?: string
  totalTokens: number
  cost?: number
  requests?: number
}

export interface UsageBreakdownQuery extends AuditRangeQuery {
  groupBy: 'model' | 'agent' | 'user'
}

export interface UsageRecordQuery extends PageQuery, AuditRangeQuery {
  modelId?: number | string
  agentId?: number | string
  userId?: number | string
}

export interface UsageRecord {
  id: number | string
  modelId?: number | string
  modelName?: string
  agentId?: number | string
  agentName?: string
  userId?: number | string
  username?: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  cost: number
  stream?: boolean
  success?: boolean
  latencyMs?: number
  createdAt: string
}

export interface AuditOverview {
  totalRequests?: number
  totalTokens?: number
  totalCost?: number
  successRate?: number
  totalAgents?: number
  totalUsers?: number
  totalKnowledgeBases?: number
  totalDocuments?: number
  todayRequests?: number
  todayTokens?: number
  todayCost?: number
}

/* Agent Harness */

export type AgentStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'

export interface AgentTool {
  name: string
  description?: string
  riskLevel?: 'LOW' | 'MEDIUM' | 'HIGH'
  source?: string
  enabled?: boolean
}

export interface AgentInfo {
  id: number | string
  name: string
  description?: string
  type?: string
  modelId?: number | string | null
  systemPrompt?: string
  status?: AgentStatus | string
  tools?: string[]
  version?: number
  publishedVersion?: number
  temperature?: number
  maxSteps?: number
  createdAt?: string
  updatedAt?: string
}

export interface AgentRequest {
  name: string
  description?: string
  type: string
  modelId?: number | string | null
  systemPrompt: string
  tools: string[]
  temperature?: number
  maxSteps?: number
}

export interface AgentVersionInfo {
  version: number
  published?: boolean
  createdAt?: string
  createdBy?: string
}

export interface UsageInfo {
  model?: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  cost: number
}

export interface RunStep {
  index?: number
  type?: string
  name?: string
  content?: string
  toolName?: string
  success?: boolean
  elapsedMs?: number
}

export interface AgentRunRequest {
  input?: string
  message?: string
  sessionId?: string
}

export interface AgentRunResult {
  runId: string
  status: string
  output?: string
  usage?: UsageInfo
  elapsedMs?: number
  steps?: RunStep[]
}

/** POST /chat/stream */
export interface ChatRequest {
  agentId: number | string
  sessionId?: string
  message: string
  attachments?: string[]
}

/** POST /runs/{runId}/approve */
export interface ApproveRequest {
  approved: boolean
  comment?: string
}

/* RAG */

export interface KnowledgeBase {
  id: number | string
  name: string
  description?: string
  embeddingModelId?: number | string | null
  embeddingModel?: string
  documentCount?: number
  chunkCount?: number
  createdAt?: string
  updatedAt?: string
}

export interface KnowledgeBaseRequest {
  name: string
  description?: string
  embeddingModelId?: number | string | null
}

export type DocumentStatus = 'PENDING' | 'PARSING' | 'INDEXING' | 'READY' | 'FAILED'

export interface KbDocument {
  id: number | string
  knowledgeBaseId?: number | string
  filename: string
  fileSize?: number
  status: DocumentStatus | string
  chunkCount?: number
  progress?: number
  errorMessage?: string
  createdAt?: string
  updatedAt?: string
}

export interface DocumentQuery extends PageQuery {
  status?: string
}

export interface DocumentUploadResult {
  documentId: number | string
  status: string
}

export interface KnowledgeSearchRequest {
  query: string
  topK: number
  threshold: number
}

export interface SearchHit {
  content: string
  score: number
  documentId: number | string
  chunkIndex: number
  filename?: string
}

/* 数据源 */

export type DatasourceType = 'MYSQL' | 'POSTGRESQL' | 'H2'

export interface Datasource {
  id: number | string
  name: string
  type: DatasourceType
  jdbcUrl: string
  username?: string
  maxPoolSize?: number
  status?: string
  createdAt?: string
}

export interface DatasourceRequest {
  name: string
  type: DatasourceType
  jdbcUrl: string
  username: string
  /** 编辑时为空表示不变 */
  password?: string
  maxPoolSize: number
}

export interface ConnectionTestResult {
  success: boolean
  latencyMs: number
  message?: string
}

export interface SchemaColumn {
  name: string
  type?: string
  comment?: string
  primaryKey?: boolean
  nullable?: boolean
}

export interface SchemaTable {
  name: string
  comment?: string
  columns?: SchemaColumn[]
  primaryKey?: string
}

export interface SqlQueryRequest {
  sql: string
  maxRows: number
}

export interface SqlQueryResult {
  columns: string[]
  rows: unknown[][]
  elapsedMs: number
  truncated: boolean
}

export interface SqlQueryLog {
  id: number | string
  datasourceId?: number | string
  sql: string
  success?: boolean
  elapsedMs?: number
  rowCount?: number
  username?: string
  createdAt: string
}

/* 沙箱 */

export interface SandboxExecuteRequest {
  /** java | python | node | shell */
  language: string
  code: string
  timeoutSeconds?: number
  env?: Record<string, string>
}

export interface SandboxProviderInfo {
  name: string
  type?: string
  available?: boolean
  description?: string
  capabilities?: string[]
  languages?: string[]
}

export interface SandboxExecuteResult {
  success: boolean
  stdout: string
  stderr: string
  exitCode: number
  elapsedMs: number
  truncated?: boolean
}

/* SSE 事件 */

export type SseEventName =
  | 'run.started'
  | 'message.delta'
  | 'message.completed'
  | 'tool.call'
  | 'tool.result'
  | 'citation'
  | 'usage'
  | 'interrupted'
  | 'run.completed'
  | 'error'
  | 'ping'

export interface RunStartedEvent {
  runId: string
  agentId: number | string
  sessionId?: string
  model?: string
}

export interface MessageDeltaEvent {
  content: string
}

export interface MessageCompletedEvent {
  content: string
}

export interface ToolCallEvent {
  toolName: string
  arguments?: unknown
  riskLevel?: string
}

export interface ToolResultEvent {
  toolName: string
  resultSummary?: string
  success?: boolean
  elapsedMs?: number
}

export interface CitationEvent {
  index: number
  documentId: number | string
  filename?: string
  chunkIndex?: number
  score?: number
}

export interface InterruptedEvent {
  approvalId: number | string
  toolName?: string
  arguments?: unknown
  reason?: string
}

export interface RunCompletedEvent {
  runId: string
  status: 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | string
  elapsedMs?: number
  usage?: UsageInfo
}

export interface SseErrorEvent {
  code: number
  message: string
}

/* Token 额度 */

export interface QuotaStatus {
  userId: number | string
  tierCode?: string
  tierName?: string
  dailyLimit: number
  dailyUsed: number
  dailyRemaining: number
  totalLimit: number
  totalUsed: number
  totalRemaining: number
  grantedTokens?: number
  unlimited?: boolean
  resetAt?: string
}

export interface QuotaTier {
  id: number | string
  code: string
  name: string
  description?: string
  dailyTokenLimit: number
  totalTokenLimit: number
  sortOrder?: number
  enabled?: boolean
  builtin?: boolean
  userCount?: number
  createdAt?: string
  updatedAt?: string
}

export interface QuotaTierRequest {
  code?: string
  name: string
  description?: string
  dailyTokenLimit: number
  totalTokenLimit: number
  sortOrder?: number
  enabled?: boolean
}

export interface UserQuotaRow extends QuotaStatus {
  username?: string
  email?: string
  nickname?: string
  status?: string
  tierId?: number | string | null
}

export interface UserQuotaUpdateRequest {
  tierId?: number | string | null
  dailyLimit?: number | null
  totalLimit?: number | null
}

export interface QuotaGrantRequest {
  amount: number
  reason?: string
}

export interface GrantRecord {
  id: number | string
  userId?: number | string
  username?: string
  amount: number
  reason?: string
  operatorName?: string
  createdAt?: string
}

export interface QuotaUserQuery extends PageQuery {
  keyword?: string
}
