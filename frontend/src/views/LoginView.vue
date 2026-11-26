<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { auth } from '@/stores/auth.js'
import { HOME_BY_ROLE } from '@/config/pages.js'
import { USE_MOCK } from '@/api/http.js'
import { roleName } from '@shared/labels.js'
import BrandMark from '@/components/BrandMark.vue'

const route = useRoute()
const router = useRouter()

const formRef = ref()
const form = reactive({ username: '', password: '' })
const rules = {
  username: [{ required: true, message: '请输入登录名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}
const loading = ref(false)
const errorText = ref('')

// 模拟模式下列出演示账号（密码见运行说明），方便检查不同岗位
// 左侧品牌区列出的三个岗位
const roleIntro = [
  { title: '前台', text: '患者档案、预约到诊、开具结算单' },
  { title: '牙医', text: '查阅跨诊所病历、记录本次诊疗' },
  { title: '总部', text: '基础资料、运营分析、季度核算' }
]

const demoAccounts = import.meta.env.DEV ? [
  { username: 'front1', role: 'FRONT_DESK', name: '王丽' },
  { username: 'dentist1', role: 'DENTIST', name: '林嘉文' },
  { username: 'dentist2', role: 'DENTIST', name: '周明远' },
  { username: 'hq1', role: 'HQ', name: '陈总部' }
] : []

// 开发时（npm run dev / dev:mock）显示演示账号，点击填入登录名；正式打包时不显示
const IS_DEV = import.meta.env.DEV

function fillUsername(username) {
  form.username = username
  errorText.value = ''
}

async function onSubmit() {
  // 回车由表单提交统一处理；登录进行中不重复提交
  if (loading.value) return
  errorText.value = ''
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    const user = await auth.login(form.username.trim(), form.password)
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/') ? route.query.redirect : null
    router.replace(redirect || HOME_BY_ROLE[user.role] || '/')
  } catch (e) {
    errorText.value = e.message || '登录失败，请稍后重试'
    form.password = ''
  } finally {
    loading.value = false
  }
}

async function onResetMock() {
  // 编译期判断：正式打包时整段被去掉，模拟数据不会进入打包文件
  if (import.meta.env.VITE_USE_MOCK !== 'true') return
  try {
    await ElMessageBox.confirm('模拟数据将恢复为初始示例数据，之前在模拟模式下做的修改都会丢失。确定重置吗？', '重置模拟数据', {
      confirmButtonText: '重置',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch {
    return
  }
  const { resetMockDb } = await import('@/mock/db.js')
  resetMockDb()
  auth.clear()
  ElMessage.success('模拟数据已重置')
}
</script>

<template>
  <div class="login-page">
    <div class="login-shell">
      <section class="brand-panel" aria-hidden="true">
        <div class="brand-top">
          <BrandMark :size="40" inverse />
          <span class="brand-title">ADC 连锁诊所</span>
        </div>
        <div class="brand-body">
          <h2>让每一次就诊<br />都有据可查</h2>
          <p>预约、诊疗、结算到季度核算，三家诊所共用一套记录。</p>
          <ul class="role-list">
            <li v-for="r in roleIntro" :key="r.title">
              <span class="role-dot" />
              <span class="role-name">{{ r.title }}</span>
              <span class="role-text">{{ r.text }}</span>
            </li>
          </ul>
        </div>
        <div class="brand-foot">系统分析与设计 · 课程项目</div>
        <svg class="brand-deco" viewBox="0 0 200 200" fill="none">
          <circle cx="160" cy="40" r="90" stroke="currentColor" stroke-width="1" />
          <circle cx="160" cy="40" r="60" stroke="currentColor" stroke-width="1" />
          <circle cx="160" cy="40" r="30" stroke="currentColor" stroke-width="1" />
        </svg>
      </section>

      <section class="form-panel">
        <div class="login-head">
          <BrandMark :size="44" class="head-mark" />
          <h1>牙科连锁诊所运营管理系统</h1>
          <p>请使用工作账号登录 · <router-link to="/" class="back-home">返回官网</router-link></p>
        </div>

        <el-alert v-if="errorText" :title="errorText" type="error" show-icon :closable="false" class="login-error" />

        <el-form ref="formRef" :model="form" :rules="rules" size="large" @submit.prevent="onSubmit">
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="登录名" :prefix-icon="User" autocomplete="username" clearable />
          </el-form-item>
          <el-form-item prop="password">
            <el-input v-model="form.password" type="password" placeholder="密码" :prefix-icon="Lock" autocomplete="current-password" show-password />
          </el-form-item>
          <el-button type="primary" native-type="submit" :loading="loading" class="login-button">登录</el-button>
        </el-form>

        <div v-if="IS_DEV" class="mock-panel">
          <div class="mock-title">
            <el-tag :type="USE_MOCK ? 'warning' : 'success'" size="small">{{ USE_MOCK ? '模拟数据模式' : '连接真实后端' }}</el-tag>
            <span>点击填入登录名，密码见运行说明</span>
          </div>
          <div class="mock-accounts">
            <button v-for="a in demoAccounts" :key="a.username" type="button" class="mock-account" @click="fillUsername(a.username)">
              <span class="mock-username">{{ a.username }}</span>
              <span class="mock-role">{{ roleName(a.role) }} · {{ a.name }}</span>
            </button>
          </div>
          <el-button v-if="USE_MOCK" link type="info" size="small" @click="onResetMock">重置模拟数据</el-button>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
  background: #eef4f3;
}
.login-shell {
  width: 100%;
  max-width: 920px;
  min-height: 560px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  background: #fff;
  border-radius: 16px;
  overflow: hidden;
  box-shadow: 0 20px 50px rgba(10, 70, 64, 0.1);
}

/* 左侧品牌区 */
.brand-panel {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  padding: 36px 40px;
  background: var(--el-color-primary);
  color: #fff;
}
.brand-top {
  display: flex;
  align-items: center;
  gap: 12px;
}
.brand-title {
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 1px;
}
.brand-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  position: relative;
  z-index: 1;
}
.brand-body h2 {
  margin: 0 0 14px;
  font-size: 30px;
  line-height: 1.35;
  font-weight: 600;
}
.brand-body p {
  margin: 0 0 30px;
  font-size: 14px;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.8);
}
.role-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.role-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.1);
  font-size: 13px;
}
.role-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #9fe3d9;
}
.role-name {
  font-weight: 600;
  min-width: 30px;
}
.role-text {
  color: rgba(255, 255, 255, 0.82);
}
.brand-foot {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.6);
  position: relative;
  z-index: 1;
}
.brand-deco {
  position: absolute;
  right: -60px;
  top: -40px;
  width: 320px;
  height: 320px;
  color: rgba(255, 255, 255, 0.12);
  pointer-events: none;
}

/* 右侧登录表单 */
.form-panel {
  padding: 48px 48px 28px;
  display: flex;
  flex-direction: column;
  justify-content: center;
}
.login-head {
  margin-bottom: 26px;
}
.head-mark {
  display: none;
  margin-bottom: 14px;
}
.login-head h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.login-head p {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.login-error {
  margin-bottom: 16px;
}
.login-button {
  width: 100%;
  margin-top: 6px;
  height: 44px;
  font-size: 15px;
  letter-spacing: 4px;
}
.mock-panel {
  margin-top: 26px;
  padding-top: 16px;
  border-top: 1px dashed var(--el-border-color);
}
.mock-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.mock-accounts {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-bottom: 6px;
}
.mock-account {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
  cursor: pointer;
  font: inherit;
  text-align: left;
  transition: border-color 0.15s, background 0.15s;
}
.mock-account:hover,
.mock-account:focus-visible {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
  outline: none;
}
.mock-username {
  font-weight: 600;
  font-size: 13px;
  color: var(--el-text-color-primary);
}
.mock-role {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

/* 窄屏：只保留登录表单 */
@media (max-width: 820px) {
  .login-shell {
    grid-template-columns: 1fr;
    max-width: 420px;
    min-height: 0;
  }
  .brand-panel {
    display: none;
  }
  .form-panel {
    padding: 32px 28px 24px;
  }
  .login-head {
    text-align: center;
  }
  .head-mark {
    display: flex;
    margin: 0 auto 14px;
  }
}
.back-home {
  color: var(--el-color-primary);
  text-decoration: none;
}
.back-home:hover {
  text-decoration: underline;
}
</style>
