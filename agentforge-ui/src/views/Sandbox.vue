<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2 class="page-title">代码沙箱</h2>
        <p class="page-desc">在隔离环境中执行代码片段 · 命令白名单、超时与输出截断保护</p>
      </div>
      <div class="toolbar">
        <el-tag v-if="providerLabel" size="small" effect="plain">{{ providerLabel }}</el-tag>
        <el-button :icon="Refresh" :loading="loadingProviders" @click="loadProviders">刷新运行时</el-button>
      </div>
    </div>

    <div class="sandbox-layout">
      <div class="af-card af-card--pad">
        <div class="panel-head">
          <h3>执行代码</h3>
          <span class="muted">超时默认 10s，最大 60s</span>
        </div>

        <div class="toolbar run-toolbar">
          <el-select v-model="language" style="width: 150px">
            <el-option v-for="item in languageOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <span class="muted">超时（秒）</span>
          <el-input-number v-model="timeoutSeconds" :min="1" :max="60" size="small" />
          <div class="spacer" />
          <el-button type="primary" :icon="VideoPlay" :loading="running" v-perm="'datasource:query'" @click="execute">
            运行
          </el-button>
        </div>

        <el-input
          v-model="code"
          type="textarea"
          :rows="16"
          class="code-area"
          spellcheck="false"
          :placeholder="placeholder"
          @keydown.ctrl.enter.prevent="execute"
        />
        <div class="muted hint">Ctrl + Enter 运行 · 环境变量（可选，KEY=VALUE 每行一个）</div>
        <el-input v-model="envText" type="textarea" :rows="3" placeholder="FOO=bar" spellcheck="false" />

        <el-alert
          v-if="providerNotice"
          class="mt-12"
          :type="providerNoticeType"
          :closable="false"
          show-icon
          :title="providerNotice"
        />
      </div>

      <div class="af-card af-card--pad">
        <div class="panel-head">
          <h3>运行结果</h3>
          <div class="toolbar" v-if="result">
            <el-tag :type="result.success ? 'success' : 'danger'" size="small" effect="light">
              {{ result.success ? '成功' : '失败' }}
            </el-tag>
            <el-tag size="small" effect="plain">exit {{ result.exitCode }}</el-tag>
            <span class="muted">{{ formatDuration(result.elapsedMs) }}</span>
          </div>
        </div>

        <div v-if="!result && !running" class="empty-result">
          <el-empty description="运行代码后在此查看 stdout / stderr" :image-size="80" />
        </div>
        <div v-else v-loading="running" class="result-body">
          <el-alert v-if="result?.truncated" type="warning" :closable="false" show-icon title="输出过长已截断" class="mb-8" />
          <div class="out-block">
            <div class="out-title">stdout</div>
            <pre class="out-pre">{{ result?.stdout || '（空）' }}</pre>
          </div>
          <div v-if="result?.stderr" class="out-block">
            <div class="out-title err">stderr</div>
            <pre class="out-pre err">{{ result.stderr }}</pre>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, VideoPlay } from '@element-plus/icons-vue'
import { sandboxApi } from '@/api/sandbox'
import type { SandboxExecuteResult, SandboxProviderInfo } from '@/types'
import { formatDuration } from '@/utils/format'
import { isFallbackError } from '@/utils/request'
import { mockSandboxProviders, mockSandboxResult, withMock } from '@/utils/mock'

type Language = 'java' | 'python' | 'node' | 'shell'

const languageOptions: Array<{ label: string; value: Language }> = [
  { label: 'Java', value: 'java' },
  { label: 'Python', value: 'python' },
  { label: 'Node.js', value: 'node' },
  { label: 'Shell', value: 'shell' },
]

const language = ref<Language>('python')
const code = ref('print("hello from AgentForge sandbox")\n')
const timeoutSeconds = ref(10)
const envText = ref('')
const running = ref(false)
const result = ref<SandboxExecuteResult | null>(null)
const providers = ref<SandboxProviderInfo[]>([])
const loadingProviders = ref(false)

const placeholder = computed(() => {
  switch (language.value) {
    case 'java':
      return 'public class Main { public static void main(String[] args) { System.out.println("hi"); } }'
    case 'node':
      return 'console.log("hello from node")'
    case 'shell':
      return 'echo hello'
    default:
      return 'print("hello")'
  }
})

const availableProvider = computed(() => providers.value.find((item) => item.available))
const providerLabel = computed(() => {
  if (!providers.value.length) return ''
  return availableProvider.value ? `运行时：${availableProvider.value.name}` : '运行时不可用'
})
const providerNotice = computed(() => {
  if (!providers.value.length) return ''
  if (availableProvider.value) return ''
  return '未检测到可用的沙箱运行时，后端未就绪时前端使用演示结果。'
})
const providerNoticeType = computed(() => (availableProvider.value ? 'success' : 'warning'))

function parseEnv(): Record<string, string> | undefined {
  const entries = envText.value
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const index = line.indexOf('=')
      if (index <= 0) return null
      return [line.slice(0, index).trim(), line.slice(index + 1)] as [string, string]
    })
    .filter((item): item is [string, string] => item !== null)
  return entries.length ? Object.fromEntries(entries) : undefined
}

async function loadProviders(): Promise<void> {
  loadingProviders.value = true
  try {
    const response = await withMock('沙箱运行时', () => sandboxApi.providers(), () => mockSandboxProviders())
    providers.value = response.data
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('运行时信息加载失败')
  } finally {
    loadingProviders.value = false
  }
}

async function execute(): Promise<void> {
  if (!code.value.trim()) {
    ElMessage.warning('请输入代码')
    return
  }
  running.value = true
  result.value = null
  try {
    const response = await withMock(
      '沙箱执行',
      () =>
        sandboxApi.execute({
          language: language.value,
          code: code.value,
          timeoutSeconds: timeoutSeconds.value,
          env: parseEnv(),
        }),
      () => mockSandboxResult(language.value, code.value),
    )
    result.value = response.data
    if (response.data.success) ElMessage.success(`执行完成，耗时 ${response.data.elapsedMs}ms`)
    else ElMessage.error(`执行失败（exit ${response.data.exitCode}）`)
  } catch (error) {
    if (!isFallbackError(error)) ElMessage.error('执行失败')
  } finally {
    running.value = false
  }
}

onMounted(loadProviders)
</script>

<style scoped>
.sandbox-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.panel-head h3 {
  margin: 0;
  font-size: 14.5px;
  font-weight: 600;
  color: #0f172a;
}

.run-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.spacer {
  flex: 1;
}

.hint {
  font-size: 12px;
  margin: 8px 0 6px;
}

.result-body {
  min-height: 200px;
}

.out-block {
  margin-top: 10px;
}

.out-title {
  font-size: 12.5px;
  font-weight: 600;
  color: #475569;
  margin-bottom: 4px;
}

.out-title.err {
  color: var(--el-color-danger);
}

.out-pre {
  margin: 0;
  padding: 10px 12px;
  background: #0f172a;
  color: #e2e8f0;
  border-radius: 8px;
  font-size: 12.5px;
  line-height: 1.6;
  max-height: 320px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.out-pre.err {
  background: #2a1215;
  color: #fecaca;
}

.mb-8 {
  margin-bottom: 8px;
}

.mt-12 {
  margin-top: 12px;
}

@media (max-width: 1100px) {
  .sandbox-layout {
    grid-template-columns: 1fr;
  }
}
</style>
