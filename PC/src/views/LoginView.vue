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
    {
      // M-524：长度校验基于 trim 后的值，与请求负载 username.trim() 对齐，
      // 避免 "  ab  " 这类首尾带空格的输入通过客户端校验却被服务端拒绝
      validator: (_rule, value: string) => {
        const len = (value ?? '').trim().length
        return len >= 3 && len <= 32
      },
      message: '用户名长度为 3~32 个字符',
      trigger: 'blur',
    },
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
  if (submitting.value) return // H-161：先拦重复提交，避免校验期间连点叠加请求
  try {
    await loginFormRef.value?.validate()
  } catch {
    return // 字段校验未通过，错误提示由表单展示
  }
  if (submitting.value) return // 防重复提交（FR-AUTH-05，校验后再查一次双保险）
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
  if (submitting.value) return
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

// H-162：只允许站内相对路径，防开放重定向（//evil.com、/\evil.com、绝对 URL 一律回退首页）
function safeRedirect(target: string): string {
  return target.startsWith('/') && !target.startsWith('//') && !target.startsWith('/\\') ? target : '/'
}

function goAfterAuth(): void {
  const redirectQuery = typeof route.query.redirect === 'string' ? route.query.redirect : null
  const target = safeRedirect(redirectQuery ?? takeRedirectPath() ?? '/')
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
        <!-- M-523：submitting 期间禁用两个 tab，防止请求在途时切到另一张表单造成状态混乱 -->
        <n-tab-pane name="login" tab="登录" :disabled="submitting">
          <!-- M-525：Enter 统一走表单原生提交路径（表单内有 attr-type="submit" 按钮），
               @submit.prevent 阻止页面刷新；移除 form 上重复的 @keyup.enter，单次 Enter 只提交一次 -->
          <n-form ref="loginFormRef" :model="loginModel" :rules="rules" label-placement="top" @submit.prevent="doLogin">
            <n-form-item label="用户名" path="username">
              <n-input v-model:value="loginModel.username" placeholder="用户名" autofocus />
            </n-form-item>
            <n-form-item label="密码" path="password">
              <!-- M-525：去掉密码框重复的 @keyup.enter，Enter 由表单原生隐式提交统一处理 -->
              <n-input
                v-model:value="loginModel.password"
                type="password"
                show-password-on="click"
                placeholder="密码"
              />
            </n-form-item>
            <!-- M-525：保留 attr-type="submit" 走原生表单提交，移除重复的 @click -->
            <n-button type="primary" block :loading="submitting" :disabled="submitting" attr-type="submit">
              登录
            </n-button>
          </n-form>
        </n-tab-pane>

        <!-- M-523：同上，请求在途时禁用注册 tab -->
        <n-tab-pane name="register" tab="注册" :disabled="submitting">
          <!-- M-525：同登录表单，Enter 统一走 @submit.prevent 的原生提交路径 -->
          <n-form ref="registerFormRef" :model="registerModel" :rules="registerRules" label-placement="top" @submit.prevent="doRegister">
            <n-form-item label="用户名" path="username">
              <n-input v-model:value="registerModel.username" placeholder="3~32 个字符" autofocus />
            </n-form-item>
            <n-form-item label="密码" path="password">
              <n-input v-model:value="registerModel.password" type="password" show-password-on="click" placeholder="至少 8 位" />
            </n-form-item>
            <n-form-item label="确认密码" path="password2">
              <!-- M-525：去掉确认密码框重复的 @keyup.enter -->
              <n-input
                v-model:value="registerModel.password2"
                type="password"
                show-password-on="click"
                placeholder="再次输入密码"
              />
            </n-form-item>
            <!-- M-525：补 attr-type="submit" 使注册表单同样走原生隐式提交，移除重复的 @click -->
            <n-button type="primary" block :loading="submitting" :disabled="submitting" attr-type="submit">
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
