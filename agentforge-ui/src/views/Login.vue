<template>
  <AuthLayout>
    <h2 class="auth-title">登录 AgentForge</h2>
    <p class="auth-subtitle">使用邮箱或用户名登录，体验多智能体协作</p>

    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
      <el-form-item label="账号" prop="account">
        <el-input
          v-model="form.account"
          placeholder="邮箱或用户名"
          size="large"
          clearable
          autocomplete="username"
        >
          <template #prefix><el-icon><User /></el-icon></template>
        </el-input>
      </el-form-item>

      <el-form-item label="密码" prop="password">
        <el-input
          v-model="form.password"
          type="password"
          placeholder="登录密码"
          size="large"
          show-password
          autocomplete="current-password"
          @keyup.enter="submit"
        >
          <template #prefix><el-icon><Lock /></el-icon></template>
        </el-input>
      </el-form-item>

      <div class="form-row">
        <router-link to="/forgot">忘记密码？</router-link>
      </div>

      <el-button type="primary" size="large" class="submit-btn" :loading="loading" @click="submit">
        登 录
      </el-button>
    </el-form>

    <el-alert
      v-if="showDemoHint"
      class="demo-hint"
      type="info"
      :closable="true"
      show-icon
      title="未连接后端？可体验演示模式"
    >
      <p class="demo-text">
        后端未就绪时，登录会自动进入 DEV 演示身份（SUPER_ADMIN，全部菜单可见）。
        也可以使用 <span class="mono">admin@agentforge.dev / Admin@123456</span> 登录。
      </p>
      <el-button link type="primary" size="small" @click="fillDemo">填充演示账号</el-button>
    </el-alert>

    <div class="auth-footer">
      <span>还没有账号？<router-link to="/register">立即注册</router-link></span>
      <router-link to="/forgot">找回密码</router-link>
    </div>
  </AuthLayout>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import AuthLayout from '@/layouts/AuthLayout.vue'
import { useAuthStore } from '@/stores/auth'
import { getErrorMessage } from '@/utils/request'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const showDemoHint = ref(import.meta.env.DEV)

const form = reactive({ account: '', password: '' })

const rules: FormRules<typeof form> = {
  account: [{ required: true, message: '请输入邮箱或用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' },
  ],
}

function fillDemo(): void {
  form.account = 'admin@agentforge.dev'
  form.password = 'Admin@123456'
}

async function submit(): Promise<void> {
  if (loading.value) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const result = await auth.login({ account: form.account.trim(), password: form.password })
    ElMessage.success(result.mock ? '后端未就绪，已进入 DEV 演示模式' : '登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : auth.homePath
    await router.replace(redirect)
  } catch (error) {
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.form-row {
  display: flex;
  justify-content: flex-end;
  margin: -4px 0 16px;
  font-size: 13px;
}

.submit-btn {
  width: 100%;
  letter-spacing: 4px;
}

.demo-hint {
  margin-top: 18px;
}

.demo-text {
  margin: 4px 0 6px;
  font-size: 12px;
  line-height: 1.7;
}
</style>
