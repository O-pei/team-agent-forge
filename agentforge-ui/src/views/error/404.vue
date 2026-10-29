<template>
  <div class="error-page">
    <div class="error-card">
      <div class="error-code">404</div>
      <h2>页面不存在</h2>
      <p class="muted">地址可能输入有误，或该页面已被移除。</p>
      <div class="error-actions">
        <el-button type="primary" @click="router.replace('/dashboard')">返回工作台</el-button>
        <el-button @click="router.back()">返回上一页</el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<style scoped>
.error-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 40px 20px;
  background: var(--af-bg);
}

.error-card {
  max-width: 460px;
  padding: 44px 36px;
  text-align: center;
  background: #fff;
  border: 1px solid var(--af-border);
  border-radius: 14px;
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.06);
}

.error-code {
  font-size: 68px;
  font-weight: 700;
  line-height: 1;
  background: linear-gradient(135deg, #2563eb, #7c3aed);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  letter-spacing: 3px;
}

.error-card h2 {
  margin: 18px 0 8px;
  font-size: 20px;
  color: #0f172a;
}

.error-card p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
}

.error-actions {
  display: flex;
  justify-content: center;
  gap: 12px;
  margin-top: 26px;
}
</style>
