<script setup>
// 我的预约（UC14 查看与撤回）：本人提交的预约申请及前台处理结果，与官网“我的预约”一致（协作约定第 9.17 节）
import { computed, ref, watch } from 'vue'
import { onShow, onPullDownRefresh } from '@dcloudio/uni-app'
import { REQUEST_STATUS, APPOINTMENT_STATUS, timeSlotText } from '@shared/labels.js'
import { dateTime } from '@shared/format.js'
import { newRequestId } from '@shared/requestId.js'
import { myRequestApi, patientAuthApi } from '../../api.js'
import { auth } from '../../utils/auth.js'

const list = ref([])
const loading = ref(false)
const loadError = ref('')
const filter = ref('ALL')

async function load() {
  if (!auth.state.token) {
    list.value = []
    uni.stopPullDownRefresh()
    return
  }
  loading.value = true
  loadError.value = ''
  try {
    list.value = await myRequestApi.list()
  } catch (e) {
    loadError.value = e.message
  } finally {
    loading.value = false
    uni.stopPullDownRefresh()
  }
}
onShow(load) // 每次切到本页都刷新，前台处理结果能及时看到
onPullDownRefresh(load)
watch(() => auth.state.token, (t) => !t && (list.value = []))

const FILTERS = [
  { value: 'ALL', label: '全部' },
  { value: 'PENDING', label: '待确认' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'CLOSED', label: '已结束' }
]
const counts = computed(() => {
  const c = { ALL: list.value.length, PENDING: 0, CONFIRMED: 0, CLOSED: 0 }
  list.value.forEach((r) => (r.status === 'PENDING' ? c.PENDING++ : r.status === 'CONFIRMED' ? c.CONFIRMED++ : c.CLOSED++))
  return c
})
const shown = computed(() =>
  list.value.filter((r) => filter.value === 'ALL' || (filter.value === 'CLOSED' ? ['INVALID', 'WITHDRAWN'].includes(r.status) : r.status === filter.value))
)

const CONFIRMED_HINT = {
  ARRIVED: '已为您安排预约，您已到诊',
  CANCELLED: '为您安排的预约已取消',
  PENDING_VERIFY: '预约时间已过，如未能到诊请致电门店'
}
const reqHint = (r) => (r.status === 'CONFIRMED' && r.appointment && CONFIRMED_HINT[r.appointment.status]) || REQUEST_STATUS[r.status].hint
const apptText = (a) => {
  if (a.status === 'CANCELLED') return '这条预约已取消，如需就诊请重新提交或致电门店'
  if (a.status === 'ARRIVED') return '您已到诊'
  if (a.status === 'PENDING_VERIFY') return '预约时间已过，如未能到诊请致电门店说明'
  return '请按时到诊，如需改期请致电门店'
}
const apptStatus = (a) => APPOINTMENT_STATUS[a.status] || { text: a.status, type: 'info' }
const call = (phone) => uni.makePhoneCall({ phoneNumber: String(phone).replace(/[^\d]/g, ''), fail: () => {} })

const withdrawing = ref(null)
async function withdraw(r) {
  const res = await new Promise((resolve) =>
    uni.showModal({
      title: '撤回预约申请',
      content: `撤回 ${r.clinic.name} ${r.preferredDate} ${timeSlotText(r.timeSlot)} 的申请？撤回后前台不会再联系您。`,
      confirmText: '撤回',
      cancelText: '再想想',
      confirmColor: '#D5553E',
      success: resolve
    })
  )
  if (!res.confirm) return
  withdrawing.value = r.id
  try {
    const updated = await myRequestApi.withdraw(r.id, newRequestId())
    list.value = list.value.map((x) => (x.id === r.id ? updated : x))
    uni.showToast({ title: '已撤回', icon: 'none' })
  } catch (e) {
    if (e.code === 'STATE_NOT_ALLOWED') load() // 前台刚处理过：刷新看最新结果
  } finally {
    withdrawing.value = null
  }
}

const goLogin = (tab = 'login') => uni.navigateTo({ url: '/pages/login/login?tab=' + tab + '&redirect=' + encodeURIComponent('/pages/mine/mine') })
const goBook = () => uni.switchTab({ url: '/pages/book/book' })
async function logout() {
  const r = await new Promise((resolve) => uni.showModal({ title: '退出登录', content: '确定退出登录吗？', success: resolve }))
  if (!r.confirm) return
  try {
    await patientAuthApi.logout()
  } catch {
    /* 令牌已失效也照样清除 */
  }
  auth.clear()
  uni.showToast({ title: '已退出登录', icon: 'none' })
}
</script>

<template>
  <view class="page">
    <view class="me">
      <template v-if="auth.state.patient">
        <view class="me-avatar">{{ auth.state.patient.name.slice(0, 1) }}</view>
        <view class="me-info">
          <view class="me-name">{{ auth.state.patient.name }}</view>
          <view class="me-phone">{{ auth.state.patient.phone }}</view>
        </view>
        <view class="me-out" @click="logout">退出</view>
      </template>
      <template v-else>
        <view class="me-avatar empty">?</view>
        <view class="me-info">
          <view class="me-name">未登录</view>
          <view class="me-phone">登录后可查看预约申请和前台确认结果</view>
        </view>
      </template>
    </view>

    <!-- 未登录 -->
    <view v-if="!auth.state.patient" class="card empty-card">
      <view class="empty-icon">🔒</view>
      <view class="empty-t">登录后查看您的预约</view>
      <view class="empty-s">使用注册时的手机号登录，即可看到提交过的预约申请和前台的确认结果。</view>
      <view class="btn" @click="goLogin('login')">登录</view>
      <view class="btn ghost gap" @click="goLogin('register')">注册新账号</view>
    </view>

    <template v-else>
      <view class="filters">
        <view v-for="f in FILTERS" :key="f.value" class="filter" :class="{ on: filter === f.value }" @click="filter = f.value">
          {{ f.label }}<text class="cnt">{{ counts[f.value] }}</text>
        </view>
      </view>

      <view v-if="loadError" class="card fail" @click="load">加载失败：{{ loadError }}，点此重试</view>
      <view v-else-if="loading && !list.length" class="card muted">正在加载…</view>
      <view v-else-if="!shown.length" class="card empty-card">
        <view class="empty-icon">📅</view>
        <view class="empty-t">{{ list.length ? '这一类还没有记录' : '还没有提交过预约申请' }}</view>
        <view class="empty-s">在线提交后，前台会致电与您确认牙医和时间。</view>
        <view class="btn" @click="goBook">提交预约申请</view>
      </view>

      <view v-for="r in shown" :key="r.id" class="req" :class="'st-' + r.status.toLowerCase()">
        <view class="req-head">
          <text class="st" :class="'st-tag-' + r.status.toLowerCase()">{{ REQUEST_STATUS[r.status].text }}</text>
          <text class="req-hint">{{ reqHint(r) }}</text>
        </view>
        <view class="req-body">
          <view class="req-clinic">{{ r.clinic.name }}</view>
          <view class="req-line">期望 {{ r.preferredDate }} · {{ timeSlotText(r.timeSlot) }}</view>
          <view v-if="r.items.length" class="req-items">
            <text v-for="it in r.items" :key="it.id" class="tag">{{ it.name }}</text>
          </view>
          <view v-if="r.note" class="req-note">“{{ r.note }}”</view>

          <!-- 处理结果 -->
          <view v-if="r.status === 'PENDING'" class="result wait">
            <view class="r-t">等待前台致电</view>
            <view class="r-s">前台会拨打 {{ r.contactPhone }} 与您确认牙医和具体时间，请留意来电。</view>
          </view>
          <view v-else-if="r.status === 'CONFIRMED' && r.appointment" class="result ok">
            <view class="r-row">
              <text class="r-t">已为您安排</text>
              <text class="appt-st" :class="'appt-' + r.appointment.status.toLowerCase()">{{ apptStatus(r.appointment).text }}</text>
            </view>
            <view class="r-big">{{ dateTime(r.appointment.scheduledAt) }}</view>
            <view class="r-s">{{ r.appointment.dentist.name }} 医生 · {{ r.appointment.clinic.name }}</view>
            <view class="r-s">{{ apptText(r.appointment) }}</view>
          </view>
          <view v-else-if="r.status === 'INVALID'" class="result bad">
            <view class="r-t">前台未能确认</view>
            <view class="r-s">原因：{{ r.invalidReason }}</view>
            <view class="r-s">如仍需就诊，可以重新提交或直接致电门店。</view>
          </view>
          <view v-else-if="r.status === 'WITHDRAWN'" class="result off">
            <view class="r-t">已撤回</view>
            <view class="r-s">撤回于 {{ dateTime(r.withdrawnAt) }}</view>
          </view>
        </view>
        <view class="req-foot">
          <text class="muted">提交于 {{ dateTime(r.createdAt) }}</text>
          <view class="foot-actions">
            <text class="link" @click="call(r.clinic.phone)">致电门店</text>
            <text v-if="r.status === 'PENDING'" class="link danger" @click="withdrawing !== r.id && withdraw(r)">{{ withdrawing === r.id ? '撤回中…' : '撤回' }}</text>
          </view>
        </view>
      </view>
    </template>
  </view>
</template>

<style>
.page { padding-bottom: 40rpx; }
.me { display: flex; align-items: center; background: linear-gradient(160deg, #0e7c74, #0a5c56); color: #fff; padding: 40rpx 36rpx 72rpx; }
.me-avatar { width: 104rpx; height: 104rpx; border-radius: 50%; background: #fff; color: var(--p-deep); font-size: 44rpx; font-weight: 800; display: flex; align-items: center; justify-content: center; margin-right: 24rpx; }
.me-avatar.empty { background: rgba(255, 255, 255, 0.2); color: #fff; }
.me-info { flex: 1; }
.me-name { font-size: 36rpx; font-weight: 700; }
.me-phone { font-size: 24rpx; opacity: 0.85; margin-top: 4rpx; }
.me-out { font-size: 26rpx; border: 2rpx solid rgba(255, 255, 255, 0.6); border-radius: 999rpx; padding: 8rpx 26rpx; }

.filters { display: flex; margin: -40rpx 24rpx 24rpx; position: relative; background: #fff; border-radius: 20rpx; padding: 10rpx; box-shadow: 0 8rpx 24rpx rgba(16, 60, 56, 0.08); }
.filter { flex: 1; text-align: center; padding: 14rpx 0; font-size: 26rpx; color: var(--ink-2); border-radius: 14rpx; }
.filter.on { background: var(--p); color: #fff; font-weight: 700; }
.cnt { font-size: 22rpx; margin-left: 6rpx; opacity: 0.8; }

.empty-card { text-align: center; padding: 48rpx 36rpx; margin-top: 24rpx; }
.page .me + .empty-card { margin-top: -40rpx; position: relative; }
.empty-icon { font-size: 64rpx; }
.empty-t { font-size: 32rpx; font-weight: 700; margin-top: 12rpx; }
.empty-s { font-size: 24rpx; color: var(--ink-3); margin: 10rpx 0 32rpx; line-height: 1.7; }
.gap { margin-top: 20rpx; }
.fail { color: #b5432f; }

.req { margin: 0 24rpx 24rpx; background: #fff; border-radius: 24rpx; overflow: hidden; box-shadow: 0 4rpx 16rpx rgba(16, 60, 56, 0.05); }
.req-head { display: flex; align-items: center; padding: 20rpx 28rpx; background: #f6f9f8; }
.st { font-size: 22rpx; font-weight: 700; color: #fff; padding: 4rpx 16rpx; border-radius: 999rpx; margin-right: 14rpx; flex-shrink: 0; }
.st-tag-pending { background: #e6a23c; }
.st-tag-confirmed { background: #2e9d6b; }
.st-tag-invalid, .st-tag-withdrawn { background: #a3b1ae; }
.req-hint { font-size: 24rpx; color: var(--ink-2); }
.req-body { padding: 24rpx 28rpx 8rpx; }
.st-invalid .req-body, .st-withdrawn .req-body { opacity: 0.85; }
.req-clinic { font-size: 34rpx; font-weight: 700; }
.req-line { font-size: 26rpx; color: var(--ink-2); margin: 4rpx 0 12rpx; }
.req-note { font-size: 24rpx; color: #8a5a2b; margin-bottom: 12rpx; }
.result { border-radius: 16rpx; padding: 20rpx 22rpx; margin: 8rpx 0 16rpx; }
.result.wait { background: #fdf6e9; }
.result.ok { background: #eaf6f0; }
.result.bad { background: #fdf0ee; }
.result.off { background: #f3f5f5; }
.r-row { display: flex; justify-content: space-between; align-items: center; }
.r-t { font-size: 28rpx; font-weight: 700; }
.result.ok .r-t { color: #2e7d57; }
.result.bad .r-t { color: #b5432f; }
.r-big { font-size: 40rpx; font-weight: 800; color: var(--p-deep); margin: 6rpx 0; }
.r-s { font-size: 24rpx; color: var(--ink-2); margin-top: 4rpx; line-height: 1.6; }
.appt-st { font-size: 22rpx; padding: 2rpx 14rpx; border-radius: 999rpx; background: #fff; color: var(--p-deep); }
.appt-cancelled { color: #8a9a98; }
.appt-pending_verify { color: #b7791f; }
.req-foot { display: flex; justify-content: space-between; align-items: center; padding: 16rpx 28rpx 22rpx; border-top: 2rpx solid var(--line); font-size: 24rpx; }
.foot-actions { display: flex; }
.link { color: var(--p); margin-left: 32rpx; font-size: 26rpx; }
.link.danger { color: #d5553e; }
</style>
