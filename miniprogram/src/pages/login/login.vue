<script setup>
// 患者登录 / 注册 / 找回密码（协作约定第 9.16 节），规则与官网登录弹窗一致。课程演示：验证码统一为 123456
import { computed, reactive, ref } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import { patientAuthApi } from '../../api.js'
import { auth } from '../../utils/auth.js'

const TAB_PAGES = ['/pages/index/index', '/pages/book/book', '/pages/mine/mine']
let redirect = ''
const tab = ref('login')
onLoad((q) => {
  redirect = q.redirect ? decodeURIComponent(q.redirect) : ''
  if (q.tab) tab.value = q.tab
})

const PHONE_RE = /^1\d{10}$/
const form = reactive({ phone: '', password: '', code: '', name: '', confirm: '' })
const errors = reactive({})
const topError = ref('')
const loading = ref(false)
const showPwd = ref(false)

function switchTab(t) {
  tab.value = t
  topError.value = ''
  Object.keys(errors).forEach((k) => delete errors[k])
  form.password = ''
  form.confirm = ''
  form.code = ''
}

// ---------- 校验（与后端规则一致，后端仍会再校验） ----------
function validate() {
  Object.keys(errors).forEach((k) => delete errors[k])
  if (!PHONE_RE.test(form.phone)) errors.phone = '请输入 11 位手机号'
  if (tab.value !== 'login' && !form.code.trim()) errors.code = '请输入验证码'
  if (tab.value === 'register') {
    if (!form.name.trim()) errors.name = '请输入姓名'
    else if (form.name.trim().length > 20) errors.name = '姓名最多 20 个字'
  }
  if (!form.password) errors.password = '请输入密码'
  else if (tab.value !== 'login' && (form.password.length < 6 || form.password.length > 64)) errors.password = '密码为 6—64 个字符'
  if (tab.value !== 'login' && form.confirm !== form.password) errors.confirm = '两次输入的密码不一致'
  return Object.keys(errors).length === 0
}

// ---------- 验证码 ----------
const countdown = ref(0)
const codeHint = ref('')
let timer = 0
async function sendCode() {
  if (countdown.value > 0) return
  if (!PHONE_RE.test(form.phone)) {
    errors.phone = '请输入 11 位手机号'
    return
  }
  delete errors.phone
  topError.value = ''
  try {
    const r = await patientAuthApi.smsCode(form.phone, tab.value === 'register' ? 'REGISTER' : 'RESET_PASSWORD')
    codeHint.value = r.message || '课程演示：验证码统一为 123456'
    countdown.value = 60
    clearInterval(timer)
    timer = setInterval(() => {
      countdown.value -= 1
      if (countdown.value <= 0) clearInterval(timer)
    }, 1000)
  } catch (e) {
    showError(e)
  }
}
onUnload(() => clearInterval(timer))

function showError(e) {
  if (e.fieldErrors?.length) e.fieldErrors.forEach((f) => (errors[f.field === 'newPassword' ? 'password' : f.field] = f.message))
  else topError.value = e.message
}

function leave() {
  if (redirect && TAB_PAGES.includes(redirect)) return uni.switchTab({ url: redirect })
  if (getCurrentPages().length > 1) return uni.navigateBack()
  uni.switchTab({ url: '/pages/mine/mine' })
}

async function submit() {
  topError.value = ''
  if (loading.value || !validate()) return
  loading.value = true
  try {
    if (tab.value === 'login') {
      auth.save(await patientAuthApi.login(form.phone, form.password))
      uni.showToast({ title: `欢迎回来，${auth.state.patient.name}`, icon: 'none' })
      setTimeout(leave, 600)
    } else if (tab.value === 'register') {
      auth.save(await patientAuthApi.register({ phone: form.phone, code: form.code.trim(), name: form.name.trim(), password: form.password }))
      uni.showToast({ title: '注册成功，已为您登录', icon: 'none' })
      setTimeout(leave, 600)
    } else {
      await patientAuthApi.resetPassword({ phone: form.phone, code: form.code.trim(), newPassword: form.password })
      uni.showToast({ title: '密码已重置，请用新密码登录', icon: 'none' })
      switchTab('login')
    }
  } catch (e) {
    showError(e)
  } finally {
    loading.value = false
  }
}

const title = computed(() => ({ login: '欢迎回来', register: '注册新账号', reset: '找回密码' })[tab.value])
const sub = computed(() => (tab.value === 'reset' ? '用注册手机号验证后设置新密码' : '登录后可在线提交预约申请、查看前台确认结果'))
const btnText = computed(() => ({ login: '登录', register: '注册并登录', reset: '重置密码' })[tab.value])
</script>

<template>
  <view class="page">
    <view class="head">
      <image class="logo" src="/static/img/logo.png" />
      <view class="h-title">{{ title }}</view>
      <view class="h-sub">{{ sub }}</view>
    </view>

    <view class="panel">
      <view v-if="tab !== 'reset'" class="tabs">
        <view class="tab" :class="{ on: tab === 'login' }" @click="switchTab('login')">登录</view>
        <view class="tab" :class="{ on: tab === 'register' }" @click="switchTab('register')">注册</view>
      </view>

      <view class="form">
        <view v-if="topError" class="top-error">{{ topError }}</view>

        <view class="field" :class="{ bad: errors.phone }">
          <text class="label">手机号</text>
          <input v-model="form.phone" class="input" type="number" maxlength="11" placeholder="11 位手机号" />
        </view>
        <view v-if="errors.phone" class="err">{{ errors.phone }}</view>

        <template v-if="tab !== 'login'">
          <view class="field" :class="{ bad: errors.code }">
            <text class="label">验证码</text>
            <input v-model="form.code" class="input" type="number" maxlength="6" placeholder="6 位验证码" />
            <view class="code-btn" :class="{ off: countdown > 0 }" @click="sendCode">{{ countdown > 0 ? countdown + ' 秒后重发' : '获取验证码' }}</view>
          </view>
          <view v-if="errors.code" class="err">{{ errors.code }}</view>
          <view v-else-if="codeHint" class="hint">{{ codeHint }}</view>
        </template>

        <template v-if="tab === 'register'">
          <view class="field" :class="{ bad: errors.name }">
            <text class="label">姓名</text>
            <input v-model="form.name" class="input" maxlength="20" placeholder="就诊人姓名" />
          </view>
          <view v-if="errors.name" class="err">{{ errors.name }}</view>
        </template>

        <view class="field" :class="{ bad: errors.password }">
          <text class="label">{{ tab === 'reset' ? '新密码' : '密码' }}</text>
          <input v-model="form.password" class="input" :password="!showPwd" maxlength="64" :placeholder="tab === 'login' ? '登录密码' : '6—64 个字符'" />
          <text class="eye" @click="showPwd = !showPwd">{{ showPwd ? '隐藏' : '显示' }}</text>
        </view>
        <view v-if="errors.password" class="err">{{ errors.password }}</view>

        <template v-if="tab !== 'login'">
          <view class="field" :class="{ bad: errors.confirm }">
            <text class="label">确认密码</text>
            <input v-model="form.confirm" class="input" :password="!showPwd" maxlength="64" placeholder="再输入一次" />
          </view>
          <view v-if="errors.confirm" class="err">{{ errors.confirm }}</view>
        </template>

        <view class="btn submit" :class="{ disabled: loading }" @click="submit">{{ loading ? '请稍候…' : btnText }}</view>

        <view class="links">
          <text v-if="tab === 'login'" class="link" @click="switchTab('reset')">忘记密码？</text>
          <text v-if="tab === 'reset'" class="link" @click="switchTab('login')">返回登录</text>
        </view>
      </view>
    </view>

    <view class="demo">
      <view class="demo-t">课程演示说明</view>
      <view>本小程序为《系统分析与设计》课程项目演示，未接入短信服务，注册和找回密码的验证码统一为 123456。请不要填写真实的个人信息。</view>
    </view>
  </view>
</template>

<style>
.page { padding-bottom: 60rpx; }
.head { background: linear-gradient(160deg, #0e7c74, #0a5c56); color: #fff; padding: 48rpx 40rpx 120rpx; }
.logo { width: 96rpx; height: 96rpx; border-radius: 24rpx; }
.h-title { font-size: 44rpx; font-weight: 800; margin-top: 24rpx; }
.h-sub { font-size: 26rpx; opacity: 0.85; margin-top: 6rpx; }
.panel { margin: -72rpx 24rpx 0; position: relative; background: #fff; border-radius: 24rpx; box-shadow: 0 12rpx 32rpx rgba(16, 60, 56, 0.08); overflow: hidden; }
.tabs { display: flex; border-bottom: 2rpx solid var(--line); }
.tab { flex: 1; text-align: center; padding: 26rpx 0; font-size: 30rpx; color: var(--ink-3); position: relative; }
.tab.on { color: var(--p-deep); font-weight: 700; }
.tab.on::after { content: ''; position: absolute; left: 50%; bottom: 0; width: 64rpx; height: 6rpx; margin-left: -32rpx; border-radius: 3rpx; background: var(--p); }
.form { padding: 16rpx 32rpx 36rpx; }
.top-error { background: #fdf0ee; color: #b5432f; border-radius: 12rpx; padding: 16rpx 20rpx; font-size: 26rpx; margin-top: 20rpx; }
.field { display: flex; align-items: center; border-bottom: 2rpx solid var(--line); padding: 24rpx 0; }
.field.bad { border-bottom-color: #d5553e; }
.label { width: 140rpx; font-size: 28rpx; color: var(--ink-2); flex-shrink: 0; }
.input { flex: 1; font-size: 30rpx; height: 48rpx; }
.code-btn { font-size: 26rpx; color: var(--p); border: 2rpx solid var(--p); border-radius: 999rpx; padding: 6rpx 22rpx; flex-shrink: 0; }
.code-btn.off { color: var(--ink-3); border-color: var(--line); }
.eye { font-size: 24rpx; color: var(--p); padding-left: 16rpx; }
.err { font-size: 24rpx; color: #d5553e; margin-top: 8rpx; }
.hint { font-size: 24rpx; color: var(--p); margin-top: 8rpx; }
.submit { margin-top: 48rpx; }
.links { text-align: center; margin-top: 28rpx; }
.link { font-size: 26rpx; color: var(--p); }
.demo { margin: 32rpx 40rpx 0; font-size: 24rpx; color: var(--ink-3); line-height: 1.7; }
.demo-t { font-weight: 700; color: var(--ink-2); margin-bottom: 6rpx; }
</style>
