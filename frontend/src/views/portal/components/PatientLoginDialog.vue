<script setup>
// 患者登录 / 注册 / 找回密码弹窗（协作约定第 9.16 节）。课程演示：验证码统一为 123456
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Iphone, Lock, User, Message } from '@element-plus/icons-vue'
import BrandMark from '@/components/BrandMark.vue'
import { patientAuth } from '@/stores/patientAuth.js'
import { patientAuthApi } from '@/api/portal.js'

const state = patientAuth.state
const visible = computed({ get: () => state.loginOpen, set: (v) => (state.loginOpen = v) })
const tab = computed({ get: () => state.loginTab, set: (v) => (state.loginTab = v) })

const PHONE_RE = /^1\d{10}$/
const phoneRule = [
  { required: true, message: '请输入手机号', trigger: 'blur' },
  { pattern: PHONE_RE, message: '请输入 11 位手机号', trigger: 'blur' }
]
const passwordRule = [
  { required: true, message: '请输入密码', trigger: 'blur' },
  { min: 6, max: 64, message: '密码为 6—64 个字符', trigger: 'blur' }
]
const codeRule = [{ required: true, message: '请输入验证码', trigger: 'blur' }]

const loginForm = reactive({ phone: '', password: '' })
const regForm = reactive({ phone: '', code: '', name: '', password: '', confirm: '' })
const resetForm = reactive({ phone: '', code: '', newPassword: '', confirm: '' })
const loginRef = ref()
const regRef = ref()
const resetRef = ref()
const errors = reactive({}) // 后端返回的字段错误，显示在对应输入框下

const regRules = {
  phone: phoneRule,
  code: codeRule,
  name: [{ required: true, message: '请输入姓名', trigger: 'blur' }, { max: 20, message: '最多 20 个字', trigger: 'blur' }],
  password: passwordRule,
  confirm: [{ validator: (_, v, cb) => (v === regForm.password ? cb() : cb(new Error('两次输入的密码不一致'))), trigger: 'blur' }]
}
const resetRules = {
  phone: phoneRule,
  code: codeRule,
  newPassword: passwordRule,
  confirm: [{ validator: (_, v, cb) => (v === resetForm.newPassword ? cb() : cb(new Error('两次输入的密码不一致'))), trigger: 'blur' }]
}
const loginRules = { phone: phoneRule, password: [{ required: true, message: '请输入密码', trigger: 'blur' }] }

const loading = ref(false)
const topError = ref('')
watch([visible, tab], () => {
  topError.value = ''
  Object.keys(errors).forEach((k) => delete errors[k])
})

// ---------- 验证码倒计时 ----------
const countdown = ref(0)
const codeHint = ref('')
let timer
async function sendCode(form, purpose) {
  if (!PHONE_RE.test(form.phone)) {
    errors.phone = '请输入 11 位手机号'
    return
  }
  delete errors.phone
  topError.value = ''
  try {
    const r = await patientAuthApi.smsCode(form.phone, purpose)
    codeHint.value = r.message || '课程演示：验证码统一为 123456'
    countdown.value = 60
    clearInterval(timer)
    timer = setInterval(() => {
      countdown.value -= 1
      if (countdown.value <= 0) clearInterval(timer)
    }, 1000)
  } catch (e) {
    topError.value = e.message
  }
}
onBeforeUnmount(() => clearInterval(timer))

function showError(e) {
  Object.keys(errors).forEach((k) => delete errors[k])
  if (e.fieldErrors?.length) e.fieldErrors.forEach((f) => (errors[f.field] = f.message))
  else topError.value = e.message
}

async function run(formRef, action, done) {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  loading.value = true
  topError.value = ''
  Object.keys(errors).forEach((k) => delete errors[k])
  try {
    await action()
    done?.()
  } catch (e) {
    showError(e)
  } finally {
    loading.value = false
  }
}

const doLogin = () =>
  run(loginRef, () => patientAuth.login(loginForm.phone, loginForm.password), () => {
    ElMessage.success(`欢迎回来，${state.patient.name}`)
    visible.value = false
  })
const doRegister = () =>
  run(regRef, () => patientAuth.register({ phone: regForm.phone, code: regForm.code, name: regForm.name.trim(), password: regForm.password }), () => {
    ElMessage.success('注册成功，已为您登录')
    visible.value = false
  })
const doReset = () =>
  run(resetRef, () => patientAuthApi.resetPassword({ phone: resetForm.phone, code: resetForm.code, newPassword: resetForm.newPassword }), () => {
    ElMessage.success('密码已重置，请用新密码登录')
    loginForm.phone = resetForm.phone
    loginForm.password = ''
    tab.value = 'login'
  })
</script>

<template>
  <el-dialog v-model="visible" width="440px" class="patient-login" align-center append-to-body :show-close="true" destroy-on-close>
    <div class="head">
      <BrandMark :size="44" />
      <h3>{{ tab === 'reset' ? '找回密码' : '欢迎来到 ADC 安德鲁牙科' }}</h3>
      <p>{{ tab === 'reset' ? '用注册手机号验证后设置新密码' : '登录后可在线登记预约、查看前台确认结果' }}</p>
    </div>

    <el-tabs v-if="tab !== 'reset'" v-model="tab" stretch class="tabs">
      <el-tab-pane label="登录" name="login" />
      <el-tab-pane label="注册" name="register" />
    </el-tabs>

    <el-alert v-if="topError" :title="topError" type="error" show-icon :closable="false" class="top-error" />
    <el-alert v-if="codeHint && tab !== 'login'" :title="codeHint" type="info" show-icon :closable="false" class="top-error" />

    <!-- 登录 -->
    <el-form v-if="tab === 'login'" ref="loginRef" :model="loginForm" :rules="loginRules" size="large" @submit.prevent="doLogin">
      <el-form-item prop="phone" :error="errors.phone">
        <el-input v-model="loginForm.phone" maxlength="11" placeholder="手机号" :prefix-icon="Iphone" autocomplete="username" />
      </el-form-item>
      <el-form-item prop="password" :error="errors.password">
        <el-input v-model="loginForm.password" type="password" show-password placeholder="密码" :prefix-icon="Lock" autocomplete="current-password" @keyup.enter="doLogin" />
      </el-form-item>
      <el-button type="primary" round class="full" :loading="loading" @click="doLogin">登 录</el-button>
      <div class="links">
        <a @click="tab = 'register'">没有账号？立即注册</a>
        <a @click="tab = 'reset'">忘记密码</a>
      </div>
    </el-form>

    <!-- 注册 -->
    <el-form v-else-if="tab === 'register'" ref="regRef" :model="regForm" :rules="regRules" size="large" @submit.prevent="doRegister">
      <el-form-item prop="phone" :error="errors.phone">
        <el-input v-model="regForm.phone" maxlength="11" placeholder="手机号（11 位）" :prefix-icon="Iphone" />
      </el-form-item>
      <el-form-item prop="code" :error="errors.code">
        <div class="code-row">
          <el-input v-model="regForm.code" maxlength="6" placeholder="验证码" :prefix-icon="Message" />
          <el-button :disabled="countdown > 0" @click="sendCode(regForm, 'REGISTER')">{{ countdown > 0 ? `${countdown} 秒后重发` : '获取验证码' }}</el-button>
        </div>
      </el-form-item>
      <el-form-item prop="name" :error="errors.name">
        <el-input v-model="regForm.name" maxlength="20" placeholder="姓名（前台回电时称呼您）" :prefix-icon="User" />
      </el-form-item>
      <el-form-item prop="password" :error="errors.password">
        <el-input v-model="regForm.password" type="password" show-password placeholder="设置密码（6—64 位）" :prefix-icon="Lock" autocomplete="new-password" />
      </el-form-item>
      <el-form-item prop="confirm">
        <el-input v-model="regForm.confirm" type="password" show-password placeholder="再次输入密码" :prefix-icon="Lock" autocomplete="new-password" @keyup.enter="doRegister" />
      </el-form-item>
      <el-button type="primary" round class="full" :loading="loading" @click="doRegister">注册并登录</el-button>
      <div class="links center"><a @click="tab = 'login'">已有账号？去登录</a></div>
    </el-form>

    <!-- 找回密码 -->
    <el-form v-else ref="resetRef" :model="resetForm" :rules="resetRules" size="large" @submit.prevent="doReset">
      <el-form-item prop="phone" :error="errors.phone">
        <el-input v-model="resetForm.phone" maxlength="11" placeholder="注册时的手机号" :prefix-icon="Iphone" />
      </el-form-item>
      <el-form-item prop="code" :error="errors.code">
        <div class="code-row">
          <el-input v-model="resetForm.code" maxlength="6" placeholder="验证码" :prefix-icon="Message" />
          <el-button :disabled="countdown > 0" @click="sendCode(resetForm, 'RESET_PASSWORD')">{{ countdown > 0 ? `${countdown} 秒后重发` : '获取验证码' }}</el-button>
        </div>
      </el-form-item>
      <el-form-item prop="newPassword" :error="errors.newPassword">
        <el-input v-model="resetForm.newPassword" type="password" show-password placeholder="新密码（6—64 位）" :prefix-icon="Lock" autocomplete="new-password" />
      </el-form-item>
      <el-form-item prop="confirm">
        <el-input v-model="resetForm.confirm" type="password" show-password placeholder="再次输入新密码" :prefix-icon="Lock" autocomplete="new-password" @keyup.enter="doReset" />
      </el-form-item>
      <el-button type="primary" round class="full" :loading="loading" @click="doReset">重置密码</el-button>
      <div class="links center"><a @click="tab = 'login'">想起来了，返回登录</a></div>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.head {
  text-align: center;
  margin-top: -8px;
}
.head :deep(.brand-mark) {
  margin: 0 auto;
}
.head h3 {
  margin: 14px 0 4px;
  font-size: 20px;
  color: #17302d;
}
.head p {
  margin: 0 0 8px;
  color: #7b8b88;
  font-size: 13.5px;
}
.tabs {
  margin-bottom: 6px;
}
.top-error {
  margin-bottom: 14px;
}
.code-row {
  display: flex;
  gap: 10px;
  width: 100%;
}
.code-row .el-button {
  width: 118px;
  flex-shrink: 0;
}
.full {
  width: 100%;
  height: 46px;
  font-size: 16px;
  margin-top: 4px;
}
.links {
  display: flex;
  justify-content: space-between;
  margin-top: 14px;
  font-size: 13.5px;
}
.links.center {
  justify-content: center;
}
.links a {
  color: #0e7c74;
  cursor: pointer;
}
.links a:hover {
  text-decoration: underline;
}
</style>

<style>
.patient-login.el-dialog {
  border-radius: 20px;
  padding: 28px 32px 26px;
  max-width: calc(100vw - 32px);
}
</style>
