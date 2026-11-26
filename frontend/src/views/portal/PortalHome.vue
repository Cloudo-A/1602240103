<script setup>
// 官网首页（面向患者）：品牌介绍、门店、医生团队、价格公示、就诊流程、预约申请（须登录，协作约定第 9.15—9.17 节）
import { computed, onBeforeUnmount, onMounted, reactive, ref, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as Icons from '@element-plus/icons-vue'
import { patientAuth } from '@/stores/patientAuth.js'
import { myRequestApi } from '@/api/portal.js'
import { TIME_SLOTS, timeSlotText } from '@shared/labels.js'
import { newRequestId } from '@shared/requestId.js'
import PortalHeader from './components/PortalHeader.vue'
import PortalFooter from './components/PortalFooter.vue'
import PatientLoginDialog from './components/PatientLoginDialog.vue'
import CostEstimator from './components/CostEstimator.vue'
import RecordJourney from './components/RecordJourney.vue'
import FaqTips from './components/FaqTips.vue'
import CountUp from './components/CountUp.vue'
import { SITE, FEATURES, STEPS, dentistIntro } from './portalData.js'
import { usePortalData } from './usePortalData.js'
import './portal.css'
// 照片来自 Unsplash（Unsplash License，可免费商用、无需署名），存放在 src/assets/portal
import heroImg from '@/assets/portal/hero.jpg'
import clinicImg1 from '@/assets/portal/clinic-1.jpg'
import clinicImg2 from '@/assets/portal/clinic-2.jpg'
import clinicImg3 from '@/assets/portal/clinic-3.jpg'
import consultImg from '@/assets/portal/consult.jpg'
const CLINIC_IMGS = [clinicImg1, clinicImg2, clinicImg3]

const route = useRoute()
const router = useRouter()
const data = usePortalData()

function go(id) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

// ---------- 进入视野时淡入；回到顶部按钮 ----------
const showTop = ref(false)
const onScroll = () => (showTop.value = window.scrollY > 800)
let observer
onMounted(async () => {
  window.addEventListener('scroll', onScroll, { passive: true })
  observer = new IntersectionObserver(
    (entries) => entries.forEach((e) => e.isIntersecting && (e.target.classList.add('shown'), observer.unobserve(e.target))),
    { threshold: 0.12 }
  )
  document.querySelectorAll('.portal .reveal').forEach((el) => observer.observe(el))
  patientAuth.verify()
  // 从其他页面点导航过来（/#clinics 之类）：滚到对应板块
  if (route.hash) {
    await nextTick()
    setTimeout(() => go(route.hash.slice(1)), 60)
  }
})
onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  observer?.disconnect()
})

const stats = computed(() => [
  { value: data.clinics.length, unit: '家', label: '连锁门店' },
  { value: data.dentists.length, unit: '位', label: '执业牙医' },
  { value: data.items.length, unit: '项', label: '标准化诊疗' },
  { value: '1', unit: '份', label: '全连锁共用病历' }
])
const money = (n) => Number(n).toFixed(2)
const initial = (name) => name.slice(0, 1)
const cityOf = (c) => (c.postalAddress || '').slice(0, 2)
const telOf = (phone) => String(phone || '').replace(/[^\d]/g, '')

// ---------- 预约申请（须登录） ----------
const slots = Object.entries(TIME_SLOTS).map(([value, s]) => ({ value, label: `${s.text} ${s.range}` }))
const formRef = ref()
const form = reactive({ clinicId: null, preferredDate: '', timeSlot: '', itemIds: [], note: '' })
const rules = {
  clinicId: [{ required: true, message: '请选择门店', trigger: 'change' }],
  preferredDate: [{ required: true, message: '请选择期望日期', trigger: 'change' }],
  timeSlot: [{ required: true, message: '请选择时段', trigger: 'change' }]
}
const fieldErrors = reactive({})
const disabledDate = (d) => {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const last = new Date(today)
  last.setDate(last.getDate() + 30)
  return d < today || d > last
}
const submitting = ref(false)
const submitError = ref('')
const submitted = ref(null)
let requestId = null // 同一次提交（含网络超时后重试）沿用同一个请求标识

function pickClinic(id) {
  form.clinicId = id
  go('booking')
}
// 小贴士里的“预约一次口腔检查”：预选口腔检查
function bookCheckup() {
  const it = data.items.find((i) => i.name === '口腔检查')
  bookItems(it ? [it.id] : [])
}
// 费用估算里选好的项目带进预约申请表
function bookItems(ids) {
  form.itemIds = ids
  go('booking')
}

async function submit() {
  if (!patientAuth.isLoggedIn.value) {
    patientAuth.openLogin('login')
    return
  }
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  submitting.value = true
  submitError.value = ''
  Object.keys(fieldErrors).forEach((k) => delete fieldErrors[k])
  requestId = requestId || newRequestId()
  try {
    submitted.value = await myRequestApi.create({ ...form, note: form.note.trim() }, requestId)
    requestId = null
  } catch (e) {
    if (e.fieldErrors?.length) e.fieldErrors.forEach((f) => (fieldErrors[f.field] = f.message))
    else submitError.value = e.message
    if (!e.uncertain) requestId = null
  } finally {
    submitting.value = false
  }
}
function again() {
  submitted.value = null
  formRef.value?.resetFields()
}
const clinicName = (id) => data.clinics.find((c) => c.id === id)?.name || ''
</script>

<template>
  <div class="portal">
    <PortalHeader :clinic-count="data.clinics.length" />

    <!-- 首屏 -->
    <section id="top" class="hero">
      <div class="hero-bg" aria-hidden="true"></div>
      <div class="wrap hero-grid">
        <div class="hero-copy">
          <span class="eyebrow">{{ SITE.fullName }} · 广州 / 深圳</span>
          <h1>一份病历，{{ data.clinics.length }} 家门店<br /><em>安心看牙</em>的每一步</h1>
          <p class="lead">
            从预约、诊疗到结算，ADC 全连锁使用同一套系统：病历跨门店共用，价格统一公示，结算单逐项列明。让您每一次就诊都清楚、放心。
          </p>
          <div class="hero-actions">
            <el-button type="primary" size="large" round @click="go('booking')">
              <el-icon class="mr"><Icons.Calendar /></el-icon>提交预约申请
            </el-button>
            <el-button size="large" round class="ghost" @click="go('clinics')">
              <el-icon class="mr"><Icons.Location /></el-icon>查找门店
            </el-button>
          </div>
          <div class="stats">
            <div v-for="s in stats" :key="s.label" class="stat">
              <b><CountUp :value="s.value" /><small>{{ s.unit }}</small></b>
              <span>{{ s.label }}</span>
            </div>
          </div>
        </div>

        <div class="hero-visual">
          <div class="photo photo-hero has-img">
            <img :src="heroImg" alt="明亮整洁的诊室和牙科治疗椅" fetchpriority="high" />
          </div>
          <div class="float-card fc-record">
            <span class="fc-icon"><el-icon><Icons.Connection /></el-icon></span>
            <div><b>跨门店病历</b><small>越秀就诊 → 天河复诊，记录连续</small></div>
          </div>
          <div class="float-card fc-slip">
            <div class="slip-head"><b>就诊结算单</b><small>JS20261118-0001</small></div>
            <div class="slip-row"><span>洁牙 × 1 次</span><span>¥150.00</span></div>
            <div class="slip-row"><span>口腔检查 × 1 次</span><span>¥50.00</span></div>
            <div class="slip-total"><span>合计</span><b>¥200.00</b></div>
          </div>
          <div class="float-card fc-check">
            <el-icon class="ok"><Icons.CircleCheckFilled /></el-icon>
            <span>过敏信息已由牙医核实</span>
          </div>
        </div>
      </div>
    </section>

    <!-- 关于我们 / 优势 -->
    <section id="about" class="section">
      <div class="wrap">
        <div class="section-head reveal">
          <span class="kicker">为什么选择 ADC</span>
          <h2>连锁一体的牙科护理，<br class="show-sm" />每个环节都有据可查</h2>
          <p>安德鲁牙科连锁在广州、深圳设有门店，由总部统一管理诊疗项目、价格和牙医资质。患者在任何一家门店就诊，都能享受同样标准的服务。</p>
        </div>
        <div class="features">
          <div v-for="(f, i) in FEATURES" :key="f.title" class="feature reveal" :style="{ transitionDelay: i * 80 + 'ms' }">
            <span class="feature-icon"><el-icon :size="26"><component :is="Icons[f.icon]" /></el-icon></span>
            <h3>{{ f.title }}</h3>
            <p>{{ f.text }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- 门店 -->
    <section id="clinics" class="section tinted">
      <div class="wrap">
        <div class="section-head reveal">
          <span class="kicker">门店分布</span>
          <h2>{{ data.clinics.length }} 家门店，就近就诊</h2>
          <p>各门店共用同一份患者档案，可以在离您最近的门店预约，也可以换门店复诊。</p>
        </div>
        <div class="clinics">
          <article v-for="(c, i) in data.clinics" :key="c.id" class="clinic reveal" :style="{ transitionDelay: i * 90 + 'ms' }">
            <div class="photo photo-clinic has-img">
              <img :src="CLINIC_IMGS[i % 3]" :alt="c.name + '诊室环境'" loading="lazy" />
              <span class="clinic-no">0{{ i + 1 }}</span>
              <span class="city">{{ cityOf(c) }}</span>
            </div>
            <div class="clinic-body">
              <h3>{{ c.name }}</h3>
              <p><el-icon><Icons.Location /></el-icon>{{ c.postalAddress }}</p>
              <p><el-icon><Icons.Phone /></el-icon>{{ c.phone }}</p>
              <p><el-icon><Icons.Clock /></el-icon>{{ SITE.hours }}</p>
              <div class="clinic-actions">
                <a :href="'tel:' + telOf(c.phone)" class="link"><el-icon><Icons.Phone /></el-icon>致电门店</a>
                <a class="link strong" @click="pickClinic(c.id)">预约此门店<el-icon><Icons.ArrowRight /></el-icon></a>
              </div>
            </div>
          </article>
        </div>
      </div>
    </section>

    <!-- 一份病历：跨门店就诊演示 -->
    <RecordJourney />

    <!-- 医生团队 -->
    <section id="dentists" class="section">
      <div class="wrap">
        <div class="section-head reveal">
          <span class="kicker">医生团队</span>
          <h2>资质经总部核实的牙医团队</h2>
          <p>牙医不固定在某一家门店，会根据患者需要在各门店接诊。下方只展示已核实的专业资质。</p>
        </div>
        <div class="dentists">
          <article v-for="(d, i) in data.dentists" :key="d.id" class="dentist reveal" :style="{ transitionDelay: i * 80 + 'ms' }">
            <div class="avatar" :class="'tone' + (i % 4)">{{ initial(d.name) }}</div>
            <h3>{{ d.name }} <small>{{ d.employeeNo }}</small></h3>
            <div class="quals"><span v-for="q in d.qualifications" :key="q"><el-icon><Icons.Medal /></el-icon>{{ q }}</span></div>
            <p>{{ dentistIntro(d) }}</p>
          </article>
        </div>
      </div>
    </section>

    <!-- 项目价格 -->
    <section id="prices" class="section tinted">
      <div class="wrap prices-grid">
        <div class="section-head left reveal">
          <span class="kicker">价格公示</span>
          <h2>全连锁统一价格，<br />结算单逐项列明</h2>
          <p>标准价格由总部统一制定，三家门店一致。实际费用按牙医记录的治疗项目和数量计算，以就诊结算单为准；调价只影响调价后的就诊。</p>
          <ul class="promise">
            <li><el-icon><Icons.CircleCheck /></el-icon>预约时登记的意向不收费，按实际治疗计价</li>
            <li><el-icon><Icons.CircleCheck /></el-icon>每次就诊一张结算单，单号可追溯</li>
            <li><el-icon><Icons.CircleCheck /></el-icon>不使用后来调整的新价格重算已发生的治疗</li>
          </ul>
        </div>
        <div class="price-card reveal">
          <div class="price-head"><span>诊疗项目</span><span>标准单价</span></div>
          <div v-for="it in data.items" :key="it.id" class="price-row">
            <span class="item-name">{{ it.name }}</span>
            <span class="dots" aria-hidden="true"></span>
            <span class="item-price">¥{{ money(it.unitPrice) }}<small> / {{ it.standardUnit }}</small></span>
          </div>
          <p class="price-note">价格单位为人民币元，最终以结算单为准。</p>
        </div>
      </div>
      <div class="wrap reveal">
        <CostEstimator :items="data.items" @book="bookItems" />
      </div>
    </section>

    <!-- 就诊流程 -->
    <section id="process" class="section">
      <div class="wrap">
        <div class="section-head reveal">
          <span class="kicker">就诊流程</span>
          <h2>四步完成一次就诊</h2>
        </div>
        <ol class="steps">
          <li v-for="(s, i) in STEPS" :key="s.title" class="step reveal" :style="{ transitionDelay: i * 100 + 'ms' }">
            <span class="step-no">{{ i + 1 }}</span>
            <span class="step-icon"><el-icon :size="24"><component :is="Icons[s.icon]" /></el-icon></span>
            <h3>{{ s.title }}</h3>
            <p>{{ s.text }}</p>
          </li>
        </ol>
      </div>
    </section>

    <!-- 常见问题与口腔小贴士 -->
    <FaqTips @book="bookCheckup" />

    <!-- 预约申请 -->
    <section id="booking" class="booking">
      <div class="wrap booking-grid">
        <div class="booking-copy reveal">
          <span class="kicker light">预约到诊</span>
          <h2>留下联系方式，<br />前台会尽快致电确认</h2>
          <p>提交后，所选门店的前台会在营业时间内回电，与您确认牙医和具体时间。确认后才算预约成功；也可以直接致电门店预约。</p>
          <img class="consult-img" :src="consultImg" alt="牙医与患者面对面沟通治疗方案" loading="lazy" />
          <div class="contact-list">
            <a v-for="c in data.clinics" :key="c.id" :href="'tel:' + telOf(c.phone)"><b>{{ c.name }}</b><span>{{ c.phone }}</span></a>
          </div>
        </div>

        <div class="booking-card reveal">
          <template v-if="!submitted">
            <h3>预约申请</h3>
            <div v-if="patientAuth.isLoggedIn.value" class="account-card">
              <span class="acc-avatar">{{ initial(patientAuth.state.patient.name) }}</span>
              <div>
                <b>{{ patientAuth.state.patient.name }}</b>
                <small>{{ patientAuth.state.patient.phone }} · 前台将拨打此号码与您确认</small>
              </div>
              <router-link :to="{ name: 'my-requests' }" class="acc-link">我的预约</router-link>
            </div>
            <div v-else class="login-tip">
              <el-icon><Icons.Lock /></el-icon>
              <span>登记预约需要先登录，前台会按账号手机号回电确认。</span>
              <a @click="patientAuth.openLogin('login')">登录</a><i>/</i><a @click="patientAuth.openLogin('register')">注册</a>
            </div>
            <el-alert v-if="submitError" :title="submitError" type="error" show-icon :closable="false" class="submit-error" />
            <el-form ref="formRef" :model="form" :rules="rules" label-position="top" size="large" class="booking-form">
              <el-form-item label="门店" prop="clinicId" :error="fieldErrors.clinicId">
                <el-radio-group v-model="form.clinicId" class="clinic-pick">
                  <el-radio-button v-for="c in data.clinics" :key="c.id" :value="c.id">{{ c.name }}</el-radio-button>
                </el-radio-group>
              </el-form-item>
              <div class="form-2">
                <el-form-item label="期望日期" prop="preferredDate" :error="fieldErrors.preferredDate">
                  <el-date-picker v-model="form.preferredDate" type="date" value-format="YYYY-MM-DD" placeholder="今天起 30 天内" :disabled-date="disabledDate" style="width: 100%" />
                </el-form-item>
                <el-form-item label="时段" prop="timeSlot" :error="fieldErrors.timeSlot">
                  <el-select v-model="form.timeSlot" placeholder="请选择" style="width: 100%">
                    <el-option v-for="s in slots" :key="s.value" :label="s.label" :value="s.value" />
                  </el-select>
                </el-form-item>
              </div>
              <el-form-item label="想做的项目（可多选，可不选）" :error="fieldErrors.itemIds">
                <el-select v-model="form.itemIds" multiple collapse-tags collapse-tags-tooltip placeholder="不确定可以不选" style="width: 100%">
                  <el-option v-for="it in data.items" :key="it.id" :label="it.name" :value="it.id" />
                </el-select>
              </el-form-item>
              <el-form-item label="补充说明（选填）" :error="fieldErrors.note">
                <el-input v-model="form.note" type="textarea" :rows="2" maxlength="200" show-word-limit placeholder="例如：牙齿遇冷敏感、希望预约周末" />
              </el-form-item>
              <el-button type="primary" size="large" round class="submit" :loading="submitting" @click="submit">
                {{ patientAuth.isLoggedIn.value ? '提交预约申请' : '登录后提交' }}
              </el-button>
              <p class="privacy">提交后可在“我的预约”查看前台的确认结果，也可以在处理前撤回。</p>
            </el-form>
          </template>
          <div v-else class="done">
            <el-icon class="done-icon"><Icons.CircleCheckFilled /></el-icon>
            <h3>已收到您的预约申请</h3>
            <p>{{ submitted.clinic.name }} · {{ submitted.preferredDate }} · {{ timeSlotText(submitted.timeSlot) }}</p>
            <p class="muted">{{ submitted.clinic.name }}的前台会在营业时间内拨打 {{ submitted.contactPhone }} 与您确认牙医和具体时间。</p>
            <div class="done-actions">
              <el-button type="primary" round @click="router.push({ name: 'my-requests' })">查看我的预约</el-button>
              <el-button round @click="again">再登记一条</el-button>
            </div>
          </div>
        </div>
      </div>
    </section>

    <PortalFooter :clinics="data.clinics" />
    <PatientLoginDialog />

    <!-- 悬浮按钮 -->
    <div class="floats">
      <transition name="fade"><button v-if="showTop" class="float-btn top" aria-label="回到顶部" @click="go('top')"><el-icon><Icons.Top /></el-icon></button></transition>
      <button class="float-btn book" @click="go('booking')"><el-icon><Icons.Calendar /></el-icon><span>预约</span></button>
    </div>
  </div>
</template>

<style scoped>
.mr { margin-right: 6px; }
.show-sm { display: none; }

/* 首屏 */
.hero {
  position: relative;
  padding: 72px 0 96px;
  overflow: hidden;
}
.hero-bg {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(1000px 520px at 85% 10%, rgba(14, 124, 116, 0.12), transparent 60%),
    radial-gradient(700px 420px at 0% 100%, rgba(247, 222, 190, 0.45), transparent 60%),
    linear-gradient(180deg, #f6fbfa 0%, #fff 100%);
}
.hero-grid {
  position: relative;
  display: grid;
  grid-template-columns: 1.05fr 1fr;
  gap: 56px;
  align-items: center;
}
.eyebrow {
  display: inline-block;
  padding: 6px 14px;
  border-radius: 999px;
  background: var(--p-soft);
  color: var(--p);
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 1px;
}
.hero h1 {
  margin: 22px 0 18px;
  font-size: 48px;
  line-height: 1.25;
  letter-spacing: 1px;
  font-weight: 800;
}
.hero h1 em {
  font-style: normal;
  color: var(--p);
  background: linear-gradient(transparent 62%, rgba(14, 124, 116, 0.16) 62%);
}
.lead { color: var(--ink-2); font-size: 17px; max-width: 540px; }
.hero-actions { display: flex; gap: 14px; margin: 30px 0 40px; flex-wrap: wrap; }
.hero-actions .el-button { height: 52px; padding: 0 28px; font-size: 16px; }
.hero-actions .el-button + .el-button { margin-left: 0; }
.ghost { background: #fff; border-color: var(--line); }
.stats {
  display: grid;
  grid-template-columns: repeat(4, auto);
  justify-content: start;
  gap: 36px;
  padding-top: 26px;
  border-top: 1px solid var(--line);
}
.stat b { display: block; font-size: 30px; color: var(--ink); line-height: 1.1; }
.stat b small { font-size: 14px; margin-left: 2px; color: var(--ink-3); font-weight: 500; }
.stat span { font-size: 13px; color: var(--ink-3); }

.hero-visual { position: relative; min-height: 520px; }
.photo {
  position: relative;
  border-radius: var(--radius);
  overflow: hidden;
  background: linear-gradient(135deg, #12857c 0%, #0a5c56 55%, #0b4f4a 100%);
}
.photo::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image: radial-gradient(rgba(255, 255, 255, 0.12) 1px, transparent 1px);
  background-size: 18px 18px;
}
.photo.has-img::before { display: none; }
.photo > img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.photo-hero {
  position: absolute;
  inset: 0 0 40px 48px;
  border-radius: 28px;
  box-shadow: 0 30px 60px rgba(10, 92, 86, 0.25);
}
.float-card {
  position: absolute;
  z-index: 2;
  background: #fff;
  border-radius: 14px;
  box-shadow: 0 18px 40px rgba(16, 60, 56, 0.16);
  animation: floaty 6s ease-in-out infinite;
}
.fc-record {
  left: 0;
  top: 54px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 18px;
}
.fc-record b { display: block; font-size: 15px; }
.fc-record small { color: var(--ink-3); font-size: 12px; }
.fc-icon {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  background: var(--p-soft);
  color: var(--p);
  font-size: 20px;
}
.fc-slip {
  right: -6px;
  bottom: 0;
  width: 270px;
  padding: 16px 18px;
  animation-delay: -2s;
  font-size: 13px;
}
.slip-head { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 10px; padding-bottom: 8px; border-bottom: 1px dashed var(--line); }
.slip-head small { color: var(--ink-3); font-family: Consolas, monospace; }
.slip-row { display: flex; justify-content: space-between; color: var(--ink-2); line-height: 1.9; }
.slip-total { display: flex; justify-content: space-between; align-items: baseline; margin-top: 8px; padding-top: 8px; border-top: 1px solid var(--line); }
.slip-total b { font-size: 20px; color: var(--p); }
.fc-check {
  left: 18px;
  bottom: 96px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  font-size: 14px;
  animation-delay: -4s;
}
.fc-check .ok { color: #2e9d6b; font-size: 20px; }
@keyframes floaty {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-8px); }
}

/* 通用区块 */
.section { padding: 96px 0; }
.section.tinted { background: var(--sand); }
.section-head { text-align: center; max-width: 720px; margin: 0 auto 52px; }
.section-head.left { text-align: left; margin: 0; }
.kicker.light { color: #bfe7e2; }
.section h2 {
  font-size: 34px;
  line-height: 1.35;
  margin: 0 0 14px;
  font-weight: 800;
  letter-spacing: 0.5px;
}
.section-head p { color: var(--ink-2); margin: 0; }

.reveal {
  opacity: 0;
  transform: translateY(24px);
  transition: opacity 0.7s ease, transform 0.7s ease;
}
.reveal.shown { opacity: 1; transform: none; }

/* 优势 */
.features { display: grid; grid-template-columns: repeat(4, 1fr); gap: 22px; }
.feature {
  padding: 30px 26px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: #fff;
  transition: box-shadow 0.3s, transform 0.3s, border-color 0.3s, opacity 0.7s ease;
}
.feature:hover { transform: translateY(-6px); box-shadow: 0 22px 44px rgba(16, 60, 56, 0.1); border-color: transparent; }
.feature-icon {
  width: 56px;
  height: 56px;
  border-radius: 16px;
  display: grid;
  place-items: center;
  color: #fff;
  background: linear-gradient(135deg, #18a094, var(--p-deep));
  margin-bottom: 20px;
}
.feature h3 { font-size: 19px; margin: 0 0 10px; }
.feature p { color: var(--ink-2); font-size: 14.5px; margin: 0; }

/* 门店 */
.clinics { display: grid; grid-template-columns: repeat(3, 1fr); gap: 26px; }
.clinic {
  background: #fff;
  border-radius: var(--radius);
  overflow: hidden;
  box-shadow: 0 2px 6px rgba(16, 60, 56, 0.04);
  transition: box-shadow 0.3s, transform 0.3s, opacity 0.7s ease;
}
.clinic:hover { transform: translateY(-6px); box-shadow: 0 24px 48px rgba(16, 60, 56, 0.12); }
.photo-clinic { height: 190px; border-radius: 0; }
.photo-clinic > img { transition: transform 0.6s ease; }
.clinic:hover .photo-clinic > img { transform: scale(1.05); }
/* 底部压暗，保证门店序号在照片上看得清 */
.photo-clinic::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0) 45%, rgba(6, 40, 38, 0.55) 100%);
}
.clinic-no {
  position: absolute;
  right: 18px;
  bottom: 4px;
  z-index: 1;
  font-size: 84px;
  font-weight: 800;
  line-height: 1;
  color: rgba(255, 255, 255, 0.55);
}
.city {
  position: absolute;
  z-index: 1;
  left: 18px;
  top: 16px;
  padding: 4px 12px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.9);
  color: var(--ink);
  font-size: 13px;
  font-weight: 600;
}
.clinic-body { padding: 24px 26px 22px; }
.clinic-body h3 { font-size: 22px; margin: 0 0 12px; }
.clinic-body p {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 0 0 6px;
  color: var(--ink-2);
  font-size: 14.5px;
}
.clinic-body p .el-icon { margin-top: 5px; color: var(--p); flex-shrink: 0; }
.tags { display: flex; flex-wrap: wrap; gap: 8px; margin: 14px 0 18px; }
.tags span {
  font-size: 12.5px;
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--p-soft);
  color: var(--p-deep);
}
.clinic-actions { display: flex; justify-content: space-between; padding-top: 16px; border-top: 1px solid var(--line); }
.link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--ink-2);
  text-decoration: none;
  font-size: 14.5px;
  cursor: pointer;
}
.link.strong { color: var(--p); font-weight: 600; }
.link:hover { color: var(--p); }

/* 医生 */
.dentists { display: grid; grid-template-columns: repeat(4, 1fr); gap: 22px; }
.dentist {
  text-align: center;
  padding: 34px 22px 28px;
  border-radius: var(--radius);
  background: #fff;
  border: 1px solid var(--line);
  transition: box-shadow 0.3s, transform 0.3s, opacity 0.7s ease;
}
.dentist:hover { transform: translateY(-6px); box-shadow: 0 22px 44px rgba(16, 60, 56, 0.1); }
.avatar {
  width: 104px;
  height: 104px;
  margin: 0 auto 18px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 40px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, #1aa79a, #0a5c56);
  box-shadow: 0 0 0 6px #fff, 0 0 0 7px var(--line);
}
.avatar.tone1 { background: linear-gradient(135deg, #3a8fb7, #1d5a7a); }
.avatar.tone2 { background: linear-gradient(135deg, #d29a63, #9a6034); }
.avatar.tone3 { background: linear-gradient(135deg, #6aa86b, #33703f); }
.dentist h3 { margin: 0 0 10px; font-size: 20px; }
.dentist h3 small { font-size: 13px; color: var(--ink-3); font-weight: 500; margin-left: 4px; }
.quals { display: flex; flex-wrap: wrap; justify-content: center; gap: 6px; margin-bottom: 14px; }
.quals span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12.5px;
  padding: 3px 10px;
  border-radius: 999px;
  background: #fff6ea;
  color: #8a5a2b;
}
.dentist p { margin: 0; color: var(--ink-2); font-size: 14px; }

/* 价格 */
.prices-grid { display: grid; grid-template-columns: 1fr 1.1fr; gap: 64px; align-items: center; }
.promise { list-style: none; padding: 0; margin: 26px 0 0; }
.promise li { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; color: var(--ink-2); }
.promise .el-icon { color: var(--p); font-size: 18px; }
.price-card {
  background: #fff;
  border-radius: 22px;
  padding: 30px 34px;
  box-shadow: 0 30px 60px rgba(16, 60, 56, 0.1);
}
.price-head {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: var(--ink-3);
  letter-spacing: 2px;
  padding-bottom: 12px;
  border-bottom: 2px solid var(--ink);
}
.price-row { display: flex; align-items: baseline; gap: 10px; padding: 14px 0; border-bottom: 1px solid var(--line); }
.item-name { font-size: 16.5px; font-weight: 600; }
.dots { flex: 1; border-bottom: 2px dotted #cfdcd9; transform: translateY(-4px); }
.item-price { font-size: 18px; font-weight: 700; color: var(--p-deep); font-variant-numeric: tabular-nums; }
.item-price small { font-size: 13px; color: var(--ink-3); font-weight: 500; }
.price-note { font-size: 12.5px; color: var(--ink-3); margin: 14px 0 0; }

/* 流程 */
.steps {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 22px;
  position: relative;
}
.steps::before {
  content: '';
  position: absolute;
  top: 36px;
  left: 12%;
  right: 12%;
  border-top: 2px dashed #bcd8d4;
}
.step { position: relative; text-align: center; padding: 0 10px; }
.step-icon { transition: transform 0.3s, box-shadow 0.3s; }
.step:hover .step-icon { transform: translateY(-4px) scale(1.06); box-shadow: 0 14px 28px rgba(14, 124, 116, 0.25); }
.step-icon {
  position: relative;
  width: 72px;
  height: 72px;
  margin: 0 auto 20px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: #fff;
  color: var(--p);
  border: 2px solid var(--p);
  box-shadow: 0 0 0 8px #fff;
}
.step-no {
  position: absolute;
  top: -6px;
  left: calc(50% + 20px);
  z-index: 1;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--p);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  display: grid;
  place-items: center;
}
.step h3 { margin: 0 0 8px; font-size: 20px; }
.step p { margin: 0; color: var(--ink-2); font-size: 14.5px; }

/* 预约 */
.booking {
  padding: 100px 0;
  background:
    radial-gradient(800px 400px at 10% 0%, rgba(255, 255, 255, 0.08), transparent 60%),
    linear-gradient(135deg, #0e7c74 0%, #0a5c56 60%, #084a45 100%);
  color: #fff;
}
.booking-grid { display: grid; grid-template-columns: 1fr 1.05fr; gap: 64px; align-items: center; }
.booking h2 { font-size: 34px; line-height: 1.35; margin: 0 0 16px; font-weight: 800; }
.booking-copy p { color: rgba(255, 255, 255, 0.82); }
.consult-img {
  display: block;
  width: 100%;
  height: 220px;
  object-fit: cover;
  margin-top: 26px;
  border-radius: 18px;
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.22);
}
.contact-list { margin-top: 22px; display: grid; gap: 12px; }
.contact-list a {
  display: flex;
  justify-content: space-between;
  padding: 14px 18px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.14);
}
.contact-list span { font-variant-numeric: tabular-nums; opacity: 0.9; }
.booking-card {
  background: #fff;
  color: var(--ink);
  border-radius: 24px;
  padding: 34px 36px 26px;
  box-shadow: 0 40px 80px rgba(0, 0, 0, 0.25);
}
.booking-card h3 { margin: 0 0 18px; font-size: 22px; }
.form-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.booking-form :deep(.el-form-item) { margin-bottom: 16px; }
.booking-form :deep(.el-form-item__label) { font-weight: 600; padding-bottom: 4px; }
.clinic-pick { display: flex; width: 100%; }
.clinic-pick :deep(.el-radio-button) { flex: 1; }
.clinic-pick :deep(.el-radio-button__inner) { width: 100%; }
.submit { width: 100%; height: 50px; font-size: 16px; margin-top: 4px; }
.privacy { text-align: center; color: var(--ink-3); font-size: 12.5px; margin: 10px 0 0; }
.done { text-align: center; padding: 30px 0 16px; }
.done-icon { font-size: 64px; color: #2e9d6b; }
.done h3 { margin: 12px 0 6px; }
.done p { margin: 0 0 14px; color: var(--ink-2); }
.done .muted { font-size: 13px; color: var(--ink-3); }
.code {
  display: inline-block;
  margin: 6px 0 14px;
  padding: 10px 22px;
  border-radius: 12px;
  background: var(--p-soft);
  color: var(--ink-2);
}
.code b { font-size: 24px; letter-spacing: 4px; color: var(--p-deep); font-family: Consolas, monospace; margin-left: 8px; }

/* 预约卡片：账号信息与登录提示 */
.account-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border-radius: 14px;
  background: var(--p-soft);
  margin-bottom: 18px;
}
.account-card b { display: block; font-size: 15px; }
.account-card small { color: var(--ink-2); font-size: 12.5px; }
.acc-avatar {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: var(--p);
  color: #fff;
  font-weight: 700;
  flex-shrink: 0;
}
.acc-link { margin-left: auto; color: var(--p); font-size: 14px; text-decoration: none; white-space: nowrap; }
.acc-link:hover { text-decoration: underline; }
.login-tip {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  padding: 12px 14px;
  border-radius: 14px;
  background: #fff6ea;
  color: #8a5a2b;
  font-size: 14px;
  margin-bottom: 18px;
}
.login-tip a { color: var(--p); font-weight: 600; cursor: pointer; }
.login-tip i { font-style: normal; color: #c9a37a; }
.submit-error { margin-bottom: 14px; }
.done-actions { display: flex; justify-content: center; gap: 10px; flex-wrap: wrap; }
.contact-list a { color: inherit; text-decoration: none; transition: background 0.2s; }
.contact-list a:hover { background: rgba(255, 255, 255, 0.16); }

/* 悬浮按钮 */
.floats { position: fixed; right: 22px; bottom: 26px; z-index: 60; display: flex; flex-direction: column; gap: 12px; align-items: flex-end; }
.float-btn {
  border: none;
  cursor: pointer;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  box-shadow: 0 12px 28px rgba(10, 92, 86, 0.3);
  font-size: 15px;
}
.float-btn.book { height: 52px; padding: 0 22px; background: var(--p); color: #fff; font-weight: 600; }
.float-btn.book .el-icon { font-size: 19px; }
.float-btn.top { width: 44px; height: 44px; background: #fff; color: var(--p); }
.fade-enter-active, .fade-leave-active { transition: opacity 0.25s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

/* 平板 */
@media (max-width: 1080px) {
  .features, .dentists { grid-template-columns: repeat(2, 1fr); }
  .hero h1 { font-size: 40px; }
}
@media (max-width: 900px) {
  .hero-grid, .prices-grid, .booking-grid { grid-template-columns: 1fr; gap: 40px; }
  .hero-visual { min-height: 440px; }
  .clinics { grid-template-columns: 1fr; max-width: 560px; margin: 0 auto; }
  .steps { grid-template-columns: repeat(2, 1fr); row-gap: 40px; }
  .steps::before { display: none; }
}
/* 手机 */
@media (max-width: 600px) {
  .show-sm { display: inline; }
  .hero { padding: 40px 0 64px; }
  .hero h1 { font-size: 31px; }
  .lead { font-size: 15.5px; }
  .hero-actions .el-button { flex: 1; height: 48px; padding: 0 12px; }
  .stats { grid-template-columns: repeat(2, 1fr); gap: 18px; }
  .hero-visual { min-height: 380px; }
  .photo-hero { inset: 0 0 30px 0; }
  .fc-record { top: 18px; left: 12px; }
  .fc-slip { width: 230px; right: 0; }
  .fc-check { display: none; }
  .consult-img { height: 180px; }
  .section { padding: 64px 0; }
  .section h2, .booking h2 { font-size: 26px; }
  .features, .dentists { grid-template-columns: 1fr; }
  .steps { grid-template-columns: 1fr; }
  .price-card { padding: 22px 20px; }
  .booking { padding: 64px 0; }
  .booking-card { padding: 26px 20px 20px; }
  .form-2 { grid-template-columns: 1fr; }
  .float-btn.book span { display: none; }
  .float-btn.book { width: 52px; padding: 0; }
}
</style>
