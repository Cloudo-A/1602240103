<script setup>
// 首页：品牌、门店、医生团队、价格公示（读公开接口，协作约定第 9.15 节）
import { ref, computed } from 'vue'
import { onLoad, onPullDownRefresh, onShareAppMessage } from '@dcloudio/uni-app'
import { publicApi } from '../../api.js'
import { auth } from '../../utils/auth.js'
import { draft } from '../../utils/draft.js'
import { SITE } from '../../config.js'

const clinics = ref([])
const dentists = ref([])
const items = ref([])
const loading = ref(true)
const failed = ref(false)

async function load() {
  failed.value = false
  try {
    const [c, d, i] = await Promise.all([publicApi.clinics(), publicApi.dentists(), publicApi.treatmentItems()])
    clinics.value = c
    dentists.value = d
    items.value = i
  } catch {
    failed.value = true
  } finally {
    loading.value = false
    uni.stopPullDownRefresh()
  }
}
onLoad(load)
onPullDownRefresh(load)
onShareAppMessage(() => ({ title: `${SITE.name} · ${SITE.slogan}`, path: '/pages/index/index' }))

const CLINIC_IMGS = ['/static/img/clinic-1.jpg', '/static/img/clinic-2.jpg', '/static/img/clinic-3.jpg']
const cityOf = (c) => (c.postalAddress || '').slice(0, 2)
const money = (n) => Number(n).toFixed(2)
const hello = computed(() => (auth.state.patient ? `您好，${auth.state.patient.name}` : '欢迎来到 ADC'))

function bookClinic(id) {
  draft.clinicId = id
  uni.switchTab({ url: '/pages/book/book' })
}
const goBook = () => uni.switchTab({ url: '/pages/book/book' })
const goMine = () => uni.switchTab({ url: '/pages/mine/mine' })
const call = (phone) => uni.makePhoneCall({ phoneNumber: String(phone).replace(/[^\d]/g, ''), fail: () => {} })

const FEATURES = [
  { t: '一份病历', s: '三家门店共用档案' },
  { t: '统一价格', s: '总部公示标准价' },
  { t: '逐项结算', s: '一诊一单可追溯' }
]
</script>

<template>
  <view class="page">
    <!-- 首屏 -->
    <view class="hero">
      <image class="hero-img" src="/static/img/hero.jpg" mode="aspectFill" />
      <view class="hero-mask" />
      <view class="hero-body">
        <view class="brand">
          <image class="logo" src="/static/img/logo.png" />
          <view>
            <view class="brand-name">{{ SITE.name }}</view>
            <view class="brand-sub">ANDREW'S DENTAL CHAIN</view>
          </view>
        </view>
        <view class="hello">{{ hello }}</view>
        <view class="slogan">{{ SITE.slogan }}，</view>
        <view class="slogan s2">安心看牙的每一步</view>
        <view class="hero-actions">
          <view class="btn hero-btn" @click="goBook">提交预约申请</view>
          <view class="btn ghost hero-btn2" @click="goMine">我的预约</view>
        </view>
      </view>
    </view>

    <view class="features">
      <view v-for="f in FEATURES" :key="f.t" class="feature">
        <view class="f-t">{{ f.t }}</view>
        <view class="f-s">{{ f.s }}</view>
      </view>
    </view>

    <view v-if="failed" class="card fail">
      <text>资料加载失败，请检查网络后下拉刷新</text>
    </view>

    <!-- 门店 -->
    <view class="sec-title">
      <text class="t">{{ clinics.length || 3 }} 家门店，就近就诊</text>
      <text class="s">{{ SITE.hours }}</text>
    </view>
    <view v-if="loading" class="card muted">正在加载…</view>
    <view v-for="(c, i) in clinics" :key="c.id" class="clinic">
      <image class="clinic-img" :src="CLINIC_IMGS[i % 3]" mode="aspectFill" />
      <view class="clinic-body">
        <view class="clinic-head">
          <text class="clinic-name">{{ c.name }}</text>
          <text class="city">{{ cityOf(c) }}</text>
        </view>
        <view class="clinic-line">{{ c.postalAddress }}</view>
        <view class="clinic-line">电话 {{ c.phone }}</view>
        <view class="clinic-actions">
          <view class="mini-btn ghost" @click="call(c.phone)">致电门店</view>
          <view class="mini-btn" @click="bookClinic(c.id)">预约此门店</view>
        </view>
      </view>
    </view>

    <!-- 医生 -->
    <view class="sec-title">
      <text class="t">医生团队</text>
      <text class="s">资质经总部核实</text>
    </view>
    <view class="card">
      <view v-for="d in dentists" :key="d.id" class="dentist">
        <view class="avatar">{{ d.name.slice(0, 1) }}</view>
        <view class="d-body">
          <view class="d-name">{{ d.name }} <text class="muted d-no">{{ d.employeeNo }}</text></view>
          <view>
            <text v-for="q in d.qualifications" :key="q" class="tag">{{ q }}</text>
          </view>
        </view>
      </view>
    </view>

    <!-- 价格 -->
    <view class="sec-title">
      <text class="t">价格公示</text>
      <text class="s">三家门店统一</text>
    </view>
    <view class="card">
      <view v-for="it in items" :key="it.id" class="price">
        <text class="p-name">{{ it.name }}</text>
        <text class="p-val">¥{{ money(it.unitPrice) }}<text class="p-unit"> / {{ it.standardUnit }}</text></text>
      </view>
      <view class="p-note">预约时选的项目不收费，按牙医实际记录的治疗和就诊当日价格计费，每次就诊一张结算单。</view>
    </view>

    <view class="footer">
      <view>{{ SITE.name }} · 预约热线 {{ SITE.hotline }}</view>
      <view>《系统分析与设计》课程项目演示 · 官网 {{ SITE.web }}</view>
    </view>
  </view>
</template>

<style>
.page { padding-bottom: 40rpx; }
.hero { position: relative; height: 560rpx; overflow: hidden; }
.hero-img { position: absolute; left: 0; top: 0; width: 100%; height: 100%; }
.hero-mask { position: absolute; left: 0; top: 0; right: 0; bottom: 0; background: linear-gradient(180deg, rgba(10, 92, 86, 0.55) 0%, rgba(8, 57, 58, 0.85) 100%); }
.hero-body { position: relative; padding: 40rpx 36rpx; color: #fff; }
.brand { display: flex; align-items: center; }
.logo { width: 80rpx; height: 80rpx; border-radius: 20rpx; margin-right: 18rpx; }
.brand-name { font-size: 32rpx; font-weight: 700; }
.brand-sub { font-size: 18rpx; letter-spacing: 4rpx; opacity: 0.75; }
.hello { margin-top: 48rpx; font-size: 26rpx; opacity: 0.85; }
.slogan { margin-top: 8rpx; font-size: 48rpx; font-weight: 800; line-height: 1.35; }
.slogan.s2 { margin-top: 0; }
.hero-actions { display: flex; margin-top: 40rpx; }
.hero-btn { flex: 1.3; background: #fff; color: var(--p-deep); margin-right: 20rpx; }
.btn.ghost.hero-btn2 { flex: 1; background: transparent; color: #fff; border-color: rgba(255, 255, 255, 0.7); }

.features { display: flex; margin: -40rpx 24rpx 0; position: relative; background: #fff; border-radius: 24rpx; padding: 24rpx 8rpx; box-shadow: 0 12rpx 32rpx rgba(16, 60, 56, 0.1); }
.feature { flex: 1; text-align: center; border-right: 2rpx solid var(--line); }
.feature:last-child { border-right: 0; }
.f-t { font-size: 30rpx; font-weight: 700; color: var(--p-deep); }
.f-s { font-size: 22rpx; color: var(--ink-3); margin-top: 4rpx; }
.fail { margin-top: 24rpx; color: #b5432f; }

.clinic { margin: 0 24rpx 24rpx; background: #fff; border-radius: 24rpx; overflow: hidden; box-shadow: 0 4rpx 16rpx rgba(16, 60, 56, 0.05); }
.clinic-img { width: 100%; height: 240rpx; display: block; }
.clinic-body { padding: 24rpx 28rpx 28rpx; }
.clinic-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8rpx; }
.clinic-name { font-size: 34rpx; font-weight: 700; }
.city { font-size: 22rpx; color: var(--p-deep); background: var(--p-soft); padding: 2rpx 16rpx; border-radius: 999rpx; }
.clinic-line { font-size: 26rpx; color: var(--ink-2); }
.clinic-actions { display: flex; justify-content: flex-end; margin-top: 20rpx; }
.mini-btn { padding: 12rpx 30rpx; border-radius: 999rpx; font-size: 26rpx; background: var(--p); color: #fff; margin-left: 16rpx; }
.mini-btn.ghost { background: #fff; color: var(--p); border: 2rpx solid var(--p); }

.dentist { display: flex; padding: 18rpx 0; border-bottom: 2rpx solid var(--line); }
.dentist:last-child { border-bottom: 0; }
.avatar { width: 84rpx; height: 84rpx; border-radius: 50%; background: linear-gradient(135deg, #12857c, #0a5c56); color: #fff; font-size: 34rpx; font-weight: 700; display: flex; align-items: center; justify-content: center; margin-right: 22rpx; flex-shrink: 0; }
.d-body { flex: 1; }
.d-name { font-size: 30rpx; font-weight: 700; margin-bottom: 6rpx; }
.d-no { font-size: 22rpx; font-weight: 400; }

.price { display: flex; justify-content: space-between; align-items: baseline; padding: 20rpx 0; border-bottom: 2rpx dashed var(--line); }
.p-name { font-size: 30rpx; font-weight: 600; }
.p-val { font-size: 32rpx; font-weight: 700; color: var(--p-deep); }
.p-unit { font-size: 22rpx; color: var(--ink-3); font-weight: 400; }
.p-note { font-size: 22rpx; color: var(--ink-3); margin-top: 16rpx; line-height: 1.7; }

.footer { text-align: center; font-size: 22rpx; color: var(--ink-3); margin-top: 40rpx; line-height: 1.9; }
</style>
