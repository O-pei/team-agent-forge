<template>
  <div class="stat-card">
    <div class="stat-icon" :style="{ background: `${color}1a`, color }">
      <el-icon :size="22"><component :is="icon" /></el-icon>
    </div>
    <div class="stat-body">
      <div class="stat-label">{{ label }}</div>
      <div class="stat-value">
        {{ value }}<span v-if="suffix" class="stat-suffix">{{ suffix }}</span>
      </div>
      <div v-if="hint" class="stat-hint">{{ hint }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { Component } from 'vue'

withDefaults(
  defineProps<{
    label: string
    value: string | number
    suffix?: string
    hint?: string
    icon?: Component
    color?: string
  }>(),
  {
    suffix: '',
    hint: '',
    color: '#2563eb',
    icon: undefined,
  },
)
</script>

<style scoped>
.stat-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px;
  background: #fff;
  border: 1px solid var(--af-border);
  border-radius: var(--af-radius);
  transition: transform 0.18s ease, box-shadow 0.18s ease;
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(15, 23, 42, 0.08);
}

.stat-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 12px;
  flex: 0 0 auto;
}

.stat-body {
  min-width: 0;
}

.stat-label {
  font-size: 12.5px;
  color: var(--af-text-secondary);
  white-space: nowrap;
}

.stat-value {
  margin-top: 4px;
  font-size: 23px;
  font-weight: 600;
  line-height: 1.2;
  color: #0f172a;
  white-space: nowrap;
}

.stat-suffix {
  margin-left: 4px;
  font-size: 12px;
  font-weight: 400;
  color: var(--af-text-light);
}

.stat-hint {
  margin-top: 4px;
  font-size: 11.5px;
  color: var(--af-text-light);
}
</style>
