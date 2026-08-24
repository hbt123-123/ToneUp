<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NForm, NFormItem, NInput, NTabPane, NTabs } from 'naive-ui'
import type { FormInst, FormRules } from 'naive-ui'
import { useAuthStore } from '@/stores/auth'
import { humanizeError } from '@/api/http'
import { takeRedirectPath } from '@/api/token'
import { appMessage } from '@/utils/feedback'

/**
 * 登录/注册页（FR-AUTH-01~05）：
 * - 双 Tab 切换；字段校验先行（密码 ≥8 位与后端一致）；
 * - 回车提交、提交中防重复；
 * - 成功后跳转来源页或首页。
 */
const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const activeTab = ref<'login' | 'register'>('login')
const submitting = ref(false)

interface FormModel {
  username: string
  password: string
  password2?: string
}

const loginFormRef = ref<FormInst | null>(null)
const registerFormRef = ref<FormInst | null>(null)
const loginModel = reactive<FormModel>({ username: '', password: '' })
const registerModel = reactive<FormModel>({ username: '', password: '', password2: '' })

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名长度为 3~32 个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 8, message: '密码至少 8 位', trigger: 'blur' },
  ],
}

const registerRules: FormRules = {
  ...rules,
  password2: [
    {
      required: true,
      message: '请再次输入密码',
      trigger: ['blur', 'password-input'],
    },
    {
      validator: (_rule, value: string) => value === registerModel.password,
      message: '两次输入的密码不一致',
      trigger: ['blur', 'password-input'],
    },
  ],
}

async function doLogin(): Promise<void> {
  try {
    await loginFormRef.value?.validate()
  } catch {
    return // 字段校验未通过，错误提示由表单展示
  }
  if (submitting.value) return // 防重复提交（FR-AUTH-05）
  submitting.value = true
  try {
    await auth.login(loginModel.username.trim(), loginModel.password)
    goAfterAuth()
  } catch (err) {
    appMessage.error(humanizeError(err))
  } finally {
    submitting.value = false
  }
}

async function doRegister(): Promise<void> {
  try {
    await registerFormRef.value?.validate()
  } catch {
    return
  }
  if (submitting.value) return
  submitting.value = true
  try {
    await auth.register(registerModel.username.trim(), registerModel.password)
    goAfterAuth()
  } catch (err) {
    appMessage.error(humanizeError(err))
  } finally {
    submitting.value = false
  }
}

function goAfterAuth(): void {
  const redirectQuery = typeof route.query.redirect === 'string' ? route.query.redirect : null
  const target = redirectQuery ?? takeRedirectPath() ?? '/'
  void router.replace(target)
}
</script>

<template>
  <div class="login-page">
    <div class="login-card tu-card">
      <div class="brand-block">
        <h1 class="title">ToneUp</h1>
        <p class="subtitle">一潼上岸 · 考研刷题</p>
      </div>

      <n-tabs v-model:value="activeTab" type="segment" animated>
        <n-tab-pane name="login" tab="登录">
          <n-form ref="loginFormRef" :model="loginModel" :rules="rules" label-placement="top" @keyup.enter="doLogin">
            <n-form-item label="用户名" path="username">
              <n-input v-model:value="loginModel.username" placeholder="用户名" autofocus />
            </n-form-item>
            <n-form-item label="密码" path="password">
              <n-input
                v-model:value="loginModel.password"
                type="password"
                show-password-on="click"
                placeholder="密码"
                @keyup.enter="doLogin"
              />
            </n-form-item>
            <n-button type="primary" block :loading="submitting" :disabled="submitting" attr-type="submit" @click="doLogin">
              登录
            </n-button>
          </n-form>
        </n-tab-pane>

        <n-tab-pane name="register" tab="注册">
          <n-form ref="registerFormRef" :model="registerModel" :rules="registerRules" label-placement="top" @keyup.enter="doRegister">
            <n-form-item label="用户名" path="username">
              <n-input v-model:value="registerModel.username" placeholder="3~32 个字符" autofocus />
            </n-form-item>
            <n-form-item label="密码" path="password">
              <n-input v-model:value="registerModel.password" type="password" show-password-on="click" placeholder="至少 8 位" />
            </n-form-item>
            <n-form-item label="确认密码" path="password2">
              <n-input
                v-model:value="registerModel.password2"
                type="password"
                show-password-on="click"
                placeholder="再次输入密码"
                @keyup.enter="doRegister"
              />
            </n-form-item>
            <n-button type="primary" block :loading="submitting" :disabled="submitting" @click="doRegister">
              注册并登录
            </n-button>
          </n-form>
        </n-tab-pane>
      </n-tabs>

      <p class="foot-hint text-secondary">正确性、解析与复习安排均以服务端数据为准</p>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  background:
    radial-gradient(1200px 600px at 20% -10%, rgba(124, 58, 237, 0.12), transparent),
    radial-gradient(1000px 500px at 110% 110%, rgba(43, 58, 103, 0.18), transparent),
    var(--tu-bg);
  padding: 24px;
}

.login-card {
  width: min(420px, 92vw);
  padding: 32px 28px;
}

.brand-block {
  text-align: center;
  margin-bottom: 20px;
}

.title {
  font-size: 34px;
  font-weight: 800;
  color: var(--tu-primary);
  margin: 0;
  letter-spacing: 1px;
}

.subtitle {
  color: var(--tu-text-secondary);
  margin: 4px 0 0;
  font-size: 14px;
}

.foot-hint {
  margin-top: 16px;
  font-size: 12px;
  text-align: center;
}
</style>
