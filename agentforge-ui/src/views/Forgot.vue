<template>
  <AuthLayout>
    <h2 class="auth-title">找回密码</h2>
    <p class="auth-subtitle">通过邮箱验证码重置登录密码</p>

    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
      <el-form-item label="邮箱" prop="email">
        <el-input
          v-model="form.email"
          placeholder="注册邮箱"
          size="large"
          clearable
          autocomplete="email"
        />
      </el-form-item>

      <el-form-item label="邮箱验证码" prop="code">
        <div class="code-row">
          <el-input v-model="form.code" placeholder="6 位数字验证码" size="large" maxlength="6" />
          <el-button size="large" :disabled="countdown > 0" :loading="codeLoading" @click="sendCode">
            {{ countdown > 0 ? `${countdown}s 后重发` : '获取验证码' }}
          </el-button>
        </div>
      </el-form-item>

      <el-form-item label="新密码" prop="newPassword">
        <el-input
          v-model="form.newPassword"
          type="password"
          placeholder="至少 8 位，需包含字母和数字"
          size="large"
          show-password
          autocomplete="new-password"
        />
      </el-form-item>

      <el-form-item label="确认新密码" prop="confirmPassword">
        <el-input
          v-model="form.confirmPassword"
          type="password"
          placeholder="再次输入新密码"
          size="large"
          show-password
          autocomplete="new-password"
        />
      </el-form-item>

      <el-button type="primary" size="large" class="submit-btn" :loading="loading" @click="submit">
        重置密码
      </el-button>
    </el-form>

    <div class="auth-footer">
      <span>想起密码了？<router-link to="/login">返回登录</router-link></span>
      <router-link to="/register">注册账号</router-link>
    </div>
  </AuthLayout>
</template>

<script setup lang="ts">
import { onBeforeUnmount, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormItemRule, type FormRules } from 'element-plus'
import AuthLayout from '@/layouts/AuthLayout.vue'
import { authApi } from '@/api/auth'
import { getErrorMessage, isFallbackError } from '@/utils/request'
import { mockEnabled } from '@/utils/mock'

const router = useRouter()
const formRef = ref<FormInstance>()
const loading = ref(false)
const codeLoading = ref(false)
const countdown = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

const form = reactive({
  email: '',
  code: '',
  newPassword: '',
  confirmPassword: '',
})

const validateConfirm: FormItemRule['validator'] = (_rule, value, callback) => {
  if (!value) callback(new Error('请再次输入新密码'))
  else if (value !== form.newPassword) callback(new Error('两次输入的密码不一致'))
  else callback()
}

const rules: FormRules<typeof form> = {
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
  code: [
    { required: true, message: '请输入验证码', trigger: 'blur' },
    { pattern: /^\d{6}$/, message: '验证码为 6 位数字', trigger: 'blur' },
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 8, max: 64, message: '密码长度 8-64 位', trigger: 'blur' },
    {
      pattern: /^(?=.*[A-Za-z])(?=.*\d).+$/,
      message: '密码需同时包含字母和数字',
      trigger: 'blur',
    },
  ],
  confirmPassword: [{ validator: validateConfirm, trigger: 'blur' }],
}

function startCountdown(): void {
  countdown.value = 60
  if (timer) clearInterval(timer)
  timer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0 && timer) {
      clearInterval(timer)
      timer = null
    }
  }, 1000)
}

async function sendCode(): Promise<void> {
  if (countdown.value > 0 || codeLoading.value) return
  const emailValid = await formRef.value?.validateField('email').catch(() => false)
  if (!emailValid) return
  codeLoading.value = true
  try {
    const result = await authApi.sendEmailCode({ email: form.email.trim(), scene: 'RESET_PASSWORD' })
    const devCode = result?.devCode || result?.code
    ElMessage.success(devCode ? `验证码已发送（DEV 回显：${devCode}）` : '验证码已发送，请查收邮箱')
    startCountdown()
  } catch (error) {
    if (mockEnabled && isFallbackError(error)) {
      ElMessage.warning('后端未就绪：演示模式下验证码可任意填写（如 123456）')
      startCountdown()
    } else {
      ElMessage.error(getErrorMessage(error))
    }
  } finally {
    codeLoading.value = false
  }
}

async function submit(): Promise<void> {
  if (loading.value) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await authApi.resetPassword({
      email: form.email.trim(),
      code: form.code.trim(),
      newPassword: form.newPassword,
    })
    ElMessage.success('密码已重置，请使用新密码登录')
    await router.replace('/login')
  } catch (error) {
    if (mockEnabled && isFallbackError(error)) {
      ElMessage.info('后端尚未就绪：演示模式无法重置密码，请直接使用演示账号登录')
      await router.replace('/login')
      return
    }
    ElMessage.error(getErrorMessage(error))
  } finally {
    loading.value = false
  }
}

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.code-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.code-row .el-input {
  flex: 1;
}

.submit-btn {
  width: 100%;
  letter-spacing: 1px;
}
</style>
