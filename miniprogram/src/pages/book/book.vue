<script setup>
// 提交预约申请（UC14，协作约定第 9.17 节）：规则与官网预约申请表一致，申请经前台回电确认后才形成预约
import { computed, reactive, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { TIME_SLOTS } from '@shared/labels.js'
import { newRequestId } from '@shared/requestId.js'
import { publicApi, myRequestApi } from '../../api.js'
import { auth } from '../../utils/auth.js'
import { draft } from '../../utils/draft.js'
import { SITE } from '../../config.js'

const clinics = ref([])
const items = ref([])
const loadFailed = ref(false)
async function load() {
  loadFailed.value = false
  try {
    const [c, i] = await Promise.all([publicApi.clinics(), publicApi.treatmentItems()])
    clinics.value = c
    items.value = i
  } catch {
    loadFailed.value = true
  }
}
onLoad(load)

// 从首页“预约此门店”带过来的门店
onShow(() => {
  if (draft.clinicId) {
    form.clinicId = draft.clinicId
    draft.clinicId = null
    submitted.value = null
  }
  if (draft.itemIds.length) {
    form.itemIds = [...draft.itemIds]
    draft.itemIds = []
  }
})

const pad = (n) => String(n).padStart(2, '0')
const ymd = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
const today = () => ymd(new Date())
const lastDay = () => {
  const d = new Date()
  d.setDate(d.getDate() + 30)
  return ymd(d)
}
const WEEK = ['日', '一', '二', '三', '四', '五', '六']
const weekOf = (s) => '周' + WEEK[new Date(s.replace(/-/g, '/')).getDay()]

const SLOTS = Object.entries(TIME_SLOTS).map(([value, s]) => ({ value, text: s.text, range: s.range }))
const form = reactive({ clinicId: null, preferredDate: '', timeSlot: '', itemIds: [], note: '' })
const errors = reactive({})
const topError = ref('')
const submitting = ref(false)
const submitted = ref(null)
let requestId = null // 网络超时重试时沿用同一个请求标识，避免重复提交

const toggleItem = (id) => {
  const i = form.itemIds.indexOf(id)
  if (i >= 0) form.itemIds.splice(i, 1)
  else form.itemIds.push(id)
}
const pick = (key, value) => {
  form[key] = value
  delete errors[key]
}
const onDate = (e) => {
  form.preferredDate = e.detail.value
  delete errors.preferredDate
}

function validate() {
  Object.keys(errors).forEach((k) => delete errors[k])
  if (!form.clinicId) errors.clinicId = '请选择门店'
  if (!form.preferredDate) errors.preferredDate = '请选择期望日期'
  else if (form.preferredDate < today() || form.preferredDate > lastDay()) errors.preferredDate = '请选择今天起 30 天内的日期'
  if (!form.timeSlot) errors.timeSlot = '请选择时段'
  if (form.note.length > 200) errors.note = '说明最多 200 字'
  return Object.keys(errors).length === 0
}

async function submit() {
  if (!auth.state.token) return goLogin()
  topError.value = ''
  if (submitting.value || !validate()) return
  submitting.value = true
  requestId = requestId || newRequestId()
  try {
    submitted.value = await myRequestApi.create({ ...form, itemIds: [...form.itemIds], note: form.note.trim() }, requestId)
    requestId = null
  } catch (e) {
    if (e.fieldErrors?.length) e.fieldErrors.forEach((f) => (errors[f.field] = f.message))
    else topError.value = e.message
    if (e.status !== 0) requestId = null
    if (e.status === 401) goLogin()
  } finally {
    submitting.value = false
  }
}
function again() {
  submitted.value = null
  Object.assign(form, { preferredDate: '', timeSlot: '', itemIds: [], note: '' })
}

const goLogin = () => uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/book/book') })
const goMine = () => uni.switchTab({ url: '/pages/mine/mine' })
const clinicName = (id) => clinics.value.find((c) => c.id === id)?.name || ''
const slotText = (v) => SLOTS.find((s) => s.value === v)
const itemNames = computed(() => (submitted.value?.items || []).map((i) => i.name).join('、'))
</script>

<template>
  <view class="page">
    <!-- 提交成功 -->
    <view v-if="submitted" class="card done">
      <view class="done-icon">✓</view>
      <view class="done-t">已收到您的预约申请</view>
      <view class="done-s">{{ submitted.clinic.name }} · {{ submitted.preferredDate }} {{ weekOf(submitted.preferredDate) }} · {{ slotText(submitted.timeSlot)?.text }}</view>
      <view v-if="itemNames" class="done-s">想做的项目：{{ itemNames }}</view>
      <view class="done-tip">{{ submitted.clinic.name }}的前台会在营业时间内拨打 {{ submitted.contactPhone }} 与您确认牙医和具体时间。确认后才算预约成功，处理前可以在“我的预约”里撤回。</view>
      <view class="btn" @click="goMine">查看我的预约</view>
      <view class="btn ghost again" @click="again">再提交一条</view>
    </view>

    <template v-else>
      <view class="intro">
        <view class="intro-t">留下期望时间，前台会致电确认</view>
        <view class="intro-s">申请不等于预约成功，也不会占用牙医时间。也可以直接致电门店预约。</view>
      </view>

      <!-- 账号 -->
      <view v-if="auth.state.patient" class="card who">
        <view class="who-avatar">{{ auth.state.patient.name.slice(0, 1) }}</view>
        <view class="who-info">
          <view class="who-name">{{ auth.state.patient.name }}</view>
          <view class="muted who-phone">{{ auth.state.patient.phone }} · 前台将拨打此号码</view>
        </view>
      </view>
      <view v-else class="card need-login">
        <view>登录后才能提交预约申请，处理结果会显示在“我的预约”里。</view>
        <view class="btn small" @click="goLogin">登录 / 注册</view>
      </view>

      <view v-if="loadFailed" class="card fail" @click="load">门店和项目加载失败，点此重试</view>

      <view class="card">
        <view v-if="topError" class="top-error">{{ topError }}</view>

        <view class="label">门店 <text class="req">*</text></view>
        <view class="options">
          <view v-for="c in clinics" :key="c.id" class="opt clinic-opt" :class="{ on: form.clinicId === c.id }" @click="pick('clinicId', c.id)">
            <view class="opt-t">{{ c.name }}</view>
            <view class="opt-s">{{ c.postalAddress }}</view>
          </view>
        </view>
        <view v-if="errors.clinicId" class="err">{{ errors.clinicId }}</view>

        <view class="label">期望日期 <text class="req">*</text></view>
        <picker mode="date" :value="form.preferredDate || today()" :start="today()" :end="lastDay()" @change="onDate">
          <view class="picker" :class="{ empty: !form.preferredDate }">
            {{ form.preferredDate ? form.preferredDate + ' ' + weekOf(form.preferredDate) : '选择今天起 30 天内的日期' }}
            <text class="arrow">›</text>
          </view>
        </picker>
        <view v-if="errors.preferredDate" class="err">{{ errors.preferredDate }}</view>

        <view class="label">时段 <text class="req">*</text></view>
        <view class="slots">
          <view v-for="s in SLOTS" :key="s.value" class="opt slot" :class="{ on: form.timeSlot === s.value }" @click="pick('timeSlot', s.value)">
            <view class="opt-t">{{ s.text }}</view>
            <view class="opt-s">{{ s.range }}</view>
          </view>
        </view>
        <view v-if="errors.timeSlot" class="err">{{ errors.timeSlot }}</view>

        <view class="label">想做的项目 <text class="muted opt-hint">可多选，可不选</text></view>
        <view class="chips">
          <view v-for="it in items" :key="it.id" class="chip" :class="{ on: form.itemIds.includes(it.id) }" @click="toggleItem(it.id)">{{ it.name }}</view>
        </view>
        <view v-if="errors.itemIds" class="err">{{ errors.itemIds }}</view>

        <view class="label">补充说明 <text class="muted opt-hint">选填</text></view>
        <textarea v-model="form.note" class="note" maxlength="200" placeholder="例如：牙齿遇冷敏感、希望预约周末" />
        <view class="count">{{ form.note.length }} / 200</view>
        <view v-if="errors.note" class="err">{{ errors.note }}</view>

        <view class="btn submit" :class="{ disabled: submitting }" @click="submit">
          {{ submitting ? '提交中…' : auth.state.token ? '提交预约申请' : '登录后提交' }}
        </view>
        <view class="foot muted">预约热线 {{ SITE.hotline }} · {{ SITE.hours }}</view>
      </view>
    </template>
  </view>
</template>

<style>
.page { padding: 24rpx 0 40rpx; }
.intro { margin: 0 32rpx 24rpx; }
.intro-t { font-size: 36rpx; font-weight: 800; }
.intro-s { font-size: 24rpx; color: var(--ink-3); margin-top: 6rpx; }
.who { display: flex; align-items: center; }
.who-avatar { width: 80rpx; height: 80rpx; border-radius: 50%; background: var(--p-soft); color: var(--p-deep); font-weight: 800; font-size: 34rpx; display: flex; align-items: center; justify-content: center; margin-right: 20rpx; }
.who-name { font-size: 30rpx; font-weight: 700; }
.who-phone { font-size: 24rpx; }
.need-login { display: flex; align-items: center; font-size: 26rpx; color: var(--ink-2); }
.need-login > view:first-child { flex: 1; margin-right: 20rpx; }
.btn.small { height: 64rpx; padding: 0 28rpx; font-size: 26rpx; flex-shrink: 0; }
.fail { color: #b5432f; }
.top-error { background: #fdf0ee; color: #b5432f; border-radius: 12rpx; padding: 16rpx 20rpx; font-size: 26rpx; margin-bottom: 12rpx; }
.label { font-size: 28rpx; font-weight: 700; margin: 28rpx 0 14rpx; }
.label:first-child, .top-error + .label { margin-top: 0; }
.req { color: #d5553e; }
.opt-hint { font-size: 22rpx; font-weight: 400; margin-left: 8rpx; }
.opt { border: 2rpx solid var(--line); border-radius: 16rpx; padding: 16rpx 20rpx; background: #fafcfc; }
.opt.on { border-color: var(--p); background: var(--p-soft); }
.opt-t { font-size: 28rpx; font-weight: 700; }
.opt.on .opt-t { color: var(--p-deep); }
.opt-s { font-size: 22rpx; color: var(--ink-3); margin-top: 2rpx; }
.clinic-opt { margin-bottom: 14rpx; }
.slots { display: flex; }
.slot { flex: 1; text-align: center; margin-right: 14rpx; padding: 16rpx 8rpx; }
.slot:last-child { margin-right: 0; }
.picker { display: flex; justify-content: space-between; align-items: center; border: 2rpx solid var(--line); border-radius: 16rpx; padding: 22rpx 20rpx; font-size: 28rpx; background: #fafcfc; }
.picker.empty { color: var(--ink-3); }
.arrow { font-size: 36rpx; color: var(--ink-3); line-height: 1; }
.chips { display: flex; flex-wrap: wrap; }
.chip { font-size: 26rpx; padding: 10rpx 26rpx; border-radius: 999rpx; border: 2rpx solid var(--line); margin: 0 14rpx 14rpx 0; color: var(--ink-2); background: #fafcfc; }
.chip.on { border-color: var(--p); background: var(--p); color: #fff; }
.note { width: 100%; box-sizing: border-box; height: 160rpx; border: 2rpx solid var(--line); border-radius: 16rpx; padding: 18rpx 20rpx; font-size: 28rpx; background: #fafcfc; }
.count { text-align: right; font-size: 22rpx; color: var(--ink-3); margin-top: 6rpx; }
.err { font-size: 24rpx; color: #d5553e; margin-top: 8rpx; }
.submit { margin-top: 36rpx; }
.foot { text-align: center; font-size: 22rpx; margin-top: 20rpx; }
.done { margin-top: 24rpx; text-align: center; padding: 56rpx 36rpx 40rpx; }
.done-icon { width: 120rpx; height: 120rpx; border-radius: 50%; background: var(--p); color: #fff; font-size: 64rpx; line-height: 120rpx; margin: 0 auto 24rpx; }
.done-t { font-size: 38rpx; font-weight: 800; }
.done-s { font-size: 28rpx; color: var(--ink-2); margin-top: 10rpx; }
.done-tip { font-size: 24rpx; color: var(--ink-3); background: #f6f9f8; border-radius: 16rpx; padding: 20rpx; margin: 28rpx 0 36rpx; text-align: left; line-height: 1.8; }
.again { margin-top: 20rpx; }
</style>
