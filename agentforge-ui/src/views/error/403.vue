<template>
  <div class="error-page">
    <div class="error-card">
      <div class="error-code">403</div>
      <h2>没有访问权限</h2>
      <p class="muted">
        当前账号缺少访问该页面所需的权限，请联系管理员分配角色，或返回有权限的页面。
      </p>
      <div class="error-actions">
        <el-button type="primary" @click="router.replace('/dashboard')">返回工作台</el-button>
        <el-button @click="router.back()">返回上一页</el-button>
      </div>
      <p class="error-meta mono">
        权限码：{{ route.query.permission || '未提供' }}
      </p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'

const router = useRouter()
const route = useRoute()
</script>

<style scoped>
.error-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: calc(100vh - var(--af-header-height));
  padding: 40px 20px;
}

.error-card {
  max-width: 460px;
  padding: 40px 36px;
  text-align: center;
  background: #fff;
  border: 1px solid var(--af-border);
  border-radius: 14px;
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.06);
}

.error-code {
  font-size: 64px;
  font-weight: 700;
  line-height: 1;
  background: linear-gradient(135deg, #f59e0b, #ef4444);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  letter-spacing: 2px;
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

.error-meta {
  margin-top: 22px !important;
  color: var(--af-text-light);
}
</style>
