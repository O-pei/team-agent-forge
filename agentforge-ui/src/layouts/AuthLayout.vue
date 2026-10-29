<template>
  <div class="auth-page">
    <section class="auth-brand">
      <div class="brand-head">
        <span class="brand-mark">AF</span>
        <div>
          <h1>AgentForge</h1>
          <p>企业级多智能体协作平台</p>
        </div>
      </div>

      <ul class="brand-points">
        <li>
          <span class="point-index">01</span>
          <div>
            <strong>Agent Harness</strong>
            <p>工具调用 · HITL 人工审批 · 版本发布与回滚</p>
          </div>
        </li>
        <li>
          <span class="point-index">02</span>
          <div>
            <strong>RAG 知识库</strong>
            <p>文档解析 · 向量检索 · 回答引用溯源</p>
          </div>
        </li>
        <li>
          <span class="point-index">03</span>
          <div>
            <strong>可观测与审计</strong>
            <p>Token 用量 / 成本看板 · 全链路操作审计</p>
          </div>
        </li>
      </ul>

      <div class="brand-foot">
        Vue 3 · TypeScript · Element Plus · ECharts
      </div>
    </section>

    <section class="auth-form-side">
      <div class="auth-card">
        <slot />
      </div>
      <p class="auth-copyright">© {{ year }} AgentForge · 内部演示环境</p>
    </section>
  </div>
</template>

<script setup lang="ts">
const year = new Date().getFullYear()
</script>

<style scoped>
.auth-page {
  display: flex;
  min-height: 100vh;
  background: #f5f7fa;
}

.auth-brand {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  width: 46%;
  max-width: 620px;
  padding: 56px 60px;
  color: #e2e8f0;
  background:
    radial-gradient(circle at 85% 12%, rgba(124, 58, 237, 0.45), transparent 42%),
    radial-gradient(circle at 10% 90%, rgba(37, 99, 235, 0.5), transparent 45%),
    #0f172a;
  overflow: hidden;
}

.auth-brand::after {
  content: '';
  position: absolute;
  inset: 0;
  background-image: linear-gradient(rgba(148, 163, 184, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(148, 163, 184, 0.06) 1px, transparent 1px);
  background-size: 34px 34px;
  pointer-events: none;
}

.brand-head {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 14px;
}

.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 12px;
  background: linear-gradient(135deg, #2563eb, #7c3aed);
  font-weight: 700;
  letter-spacing: 0.5px;
  color: #fff;
  box-shadow: 0 10px 24px rgba(37, 99, 235, 0.45);
}

.brand-head h1 {
  margin: 0;
  font-size: 22px;
  letter-spacing: 0.5px;
  color: #f8fafc;
}

.brand-head p {
  margin: 4px 0 0;
  font-size: 13px;
  color: #94a3b8;
}

.brand-points {
  position: relative;
  z-index: 1;
  list-style: none;
  padding: 0;
  margin: 40px 0;
  display: flex;
  flex-direction: column;
  gap: 26px;
}

.brand-points li {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.point-index {
  font-family: Consolas, monospace;
  font-size: 12px;
  color: #60a5fa;
  border: 1px solid rgba(96, 165, 250, 0.4);
  border-radius: 6px;
  padding: 2px 6px;
  margin-top: 2px;
}

.brand-points strong {
  font-size: 14.5px;
  color: #f1f5f9;
}

.brand-points p {
  margin: 5px 0 0;
  font-size: 12.5px;
  color: #94a3b8;
  line-height: 1.6;
}

.brand-foot {
  position: relative;
  z-index: 1;
  font-size: 11.5px;
  letter-spacing: 1px;
  color: #64748b;
}

.auth-form-side {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 24px;
}

.auth-card {
  width: 100%;
  max-width: 400px;
  padding: 34px 34px 28px;
  background: #fff;
  border: 1px solid var(--af-border);
  border-radius: 14px;
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.08);
}

.auth-copyright {
  margin: 22px 0 0;
  font-size: 12px;
  color: var(--af-text-light);
}

:deep(.auth-title) {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 600;
  color: #0f172a;
}

:deep(.auth-subtitle) {
  margin: 0 0 22px;
  font-size: 13px;
  color: var(--af-text-secondary);
}

:deep(.auth-footer) {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 16px;
  font-size: 13px;
  color: var(--af-text-secondary);
}

@media (max-width: 900px) {
  .auth-brand {
    display: none;
  }
}
</style>
