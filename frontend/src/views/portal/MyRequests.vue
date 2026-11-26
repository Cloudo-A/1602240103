<script setup>
// 官网“我的预约”：本人提交的预约申请及前台处理结果，处理前可以撤回（协作约定第 9.17 节）
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Calendar, Clock, Location, Phone, Refresh, Plus, Lock, User, Tickets, Warning } from '@element-plus/icons-vue'
import { patientAuth } from '@/stores/patientAuth.js'
import { myRequestApi } from '@/api/portal.js'
import { REQUEST_STATUS, APPOINTMENT_STATUS, timeSlotText } from '@shared/labels.js'
import { dateTime } from '@shared/format.js'
import { newRequestId } from '@shared/requestId.js'
import PortalHeader from './components/PortalHeader.vue'
import PortalFooter from './components/PortalFooter.vue'
import PatientLoginDialog from './components/PatientLoginDialog.vue'
import { usePortalData } from './usePortalData.js'
import './portal.css'

const data = usePortalData()
const list = ref([])
const loading = ref(false)
const loadError = ref('')
const filter = ref('ALL')

async function load() {
  if (!patientAuth.isLoggedIn.value) return
  loading.value = true
  loadError.value = ''
  try {
    list.value = await myRequestApi.list()
  } catch (e) {
    loadError.value = e.message
  } finally {
    loading.value = false
  }
}
onMounted(async () => {
  await patientAuth.verify()
  load()
})
// 在本页登录、退出后刷新
watch(() => patientAuth.state.token, (t) => (t ? load() : (list.value = [])))

const counts = computed(() => {
  const c = { ALL: list.value.length, PENDING: 0, CONFIRMED: 0, CLOSED: 0 }
  list.value.forEach((r) => (r.status === 'PENDING' ? c.PENDING++ : r.status === 'CONFIRMED' ? c.CONFIRMED++ : c.CLOSED++))
  return c
})
const shown = computed(() =>
  list.value.filter((r) => filter.value === 'ALL' || (filter.value === 'CLOSED' ? ['INVALID', 'WITHDRAWN'].includes(r.status) : r.status === filter.value))
)

const withdrawing = ref(null)
async function withdraw(r) {
  try {
    await ElMessageBox.confirm(`撤回 ${r.clinic.name} ${r.preferredDate} ${timeSlotText(r.timeSlot)} 的预约申请？撤回后前台不会再联系您。`, '撤回预约', {
      confirmButtonText: '撤回',
      cancelButtonText: '再想想',
      type: 'warning'
    })
  } catch {
    return
  }
  withdrawing.value = r.id
  try {
    const updated = await myRequestApi.withdraw(r.id, newRequestId())
    list.value = list.value.map((x) => (x.id === r.id ? updated : x))
    ElMessage.success('已撤回')
  } catch (e) {
    if (e.code === 'STATE_NOT_ALLOWED') load() // 前台刚处理过：刷新看最新结果
  } finally {
    withdrawing.value = null
  }
}

const apptStatus = (a) => APPOINTMENT_STATUS[a.status] || { text: a.status, type: 'info' }
// 已确认的申请：顶部提示跟随关联预约的当前状态（到诊、取消、过时未到诊后不再提示“请按时到诊”）
const CONFIRMED_HINT = {
  ARRIVED: '已为您安排预约，您已到诊',
  CANCELLED: '为您安排的预约已取消',
  PENDING_VERIFY: '预约时间已过，如未能到诊请致电门店'
}
const reqHint = (r) => (r.status === 'CONFIRMED' && r.appointment && CONFIRMED_HINT[r.appointment.status]) || REQUEST_STATUS[r.status].hint
const apptText = (a) => {
  if (a.status === 'CANCELLED') return '这条预约已取消，如需就诊请重新登记或致电门店'
  if (a.status === 'ARRIVED') return '您已到诊'
  if (a.status === 'PENDING_VERIFY') return '预约时间已过，如未能到诊请致电门店说明'
  return '请按时到诊，如需改期请致电门店'
}
</script>

<template>
  <div class="portal">
    <PortalHeader :clinic-count="data.clinics.length" />

    <section class="page-head">
      <div class="wrap">
        <span class="kicker">我的预约</span>
        <h1>预约记录与确认结果</h1>
        <p>前台确认后，这里会显示为您安排的牙医和具体时间。还没处理的预约可以撤回。</p>
      </div>
    </section>

    <main class="wrap main">
      <!-- 未登录 -->
      <div v-if="!patientAuth.isLoggedIn.value" class="empty-card">
        <el-icon class="empty-icon"><Lock /></el-icon>
        <h3>登录后查看您的预约</h3>
        <p>使用注册时的手机号登录，即可看到提交过的预约申请和前台的确认结果。</p>
        <div class="row">
          <el-button type="primary" round size="large" @click="patientAuth.openLogin('login')">登录</el-button>
          <el-button round size="large" @click="patientAuth.openLogin('register')">注册新账号</el-button>
        </div>
      </div>

      <template v-else>
        <div class="toolbar">
          <div class="me">
            <span class="avatar">{{ patientAuth.state.patient.name.slice(0, 1) }}</span>
            <div><b>{{ patientAuth.state.patient.name }}</b><small><el-icon><Phone /></el-icon>{{ patientAuth.state.patient.phone }}</small></div>
          </div>
          <el-radio-group v-model="filter" class="filter">
            <el-radio-button value="ALL">全部 {{ counts.ALL }}</el-radio-button>
            <el-radio-button value="PENDING">待确认 {{ counts.PENDING }}</el-radio-button>
            <el-radio-button value="CONFIRMED">已确认 {{ counts.CONFIRMED }}</el-radio-button>
            <el-radio-button value="CLOSED">已结束 {{ counts.CLOSED }}</el-radio-button>
          </el-radio-group>
          <div class="actions">
            <el-button :icon="Refresh" round :loading="loading" @click="load">刷新</el-button>
            <el-button type="primary" :icon="Plus" round @click="$router.push({ name: 'portal', hash: '#booking' })">登记新预约</el-button>
          </div>
        </div>

        <el-alert v-if="loadError" :title="`加载失败：${loadError}`" type="error" show-icon :closable="false" class="load-error">
          <el-button size="small" @click="load">重试</el-button>
        </el-alert>

        <div v-loading="loading && !list.length" class="cards">
          <div v-if="!loading && !loadError && !shown.length" class="empty-card small">
            <el-icon class="empty-icon"><Tickets /></el-icon>
            <h3>{{ list.length ? '这个分类下没有预约' : '还没有预约记录' }}</h3>
            <p v-if="!list.length">在首页选择门店、日期和时段，提交后前台会致电确认。</p>
            <el-button v-if="!list.length" type="primary" round @click="$router.push({ name: 'portal', hash: '#booking' })">去登记预约</el-button>
          </div>

          <article v-for="r in shown" :key="r.id" class="req" :class="'st-' + r.status.toLowerCase()">
            <div class="req-head">
              <el-tag :type="REQUEST_STATUS[r.status].type" effect="dark" round>{{ REQUEST_STATUS[r.status].text }}</el-tag>
              <span class="req-hint">{{ reqHint(r) }}</span>
              <span class="req-time">提交于 {{ dateTime(r.createdAt) }}</span>
            </div>

            <div class="req-body">
              <div class="req-main">
                <h3>{{ r.clinic.name }}</h3>
                <p><el-icon><Calendar /></el-icon>期望 {{ r.preferredDate }} · {{ timeSlotText(r.timeSlot) }}</p>
                <p><el-icon><Location /></el-icon><a :href="'tel:' + r.clinic.phone.replace(/[^\d]/g, '')">{{ r.clinic.phone }}</a></p>
                <div v-if="r.items.length" class="chips"><span v-for="it in r.items" :key="it.id">{{ it.name }}</span></div>
                <p v-if="r.note" class="note">“{{ r.note }}”</p>
              </div>

              <div v-if="r.status === 'CONFIRMED' && r.appointment" class="result ok">
                <div class="result-title">
                  <span>已为您安排</span>
                  <el-tag :type="apptStatus(r.appointment).type" size="small" round>{{ apptStatus(r.appointment).text }}</el-tag>
                </div>
                <div class="result-time"><el-icon><Clock /></el-icon>{{ dateTime(r.appointment.scheduledAt) }}</div>
                <div class="result-line"><el-icon><User /></el-icon>{{ r.appointment.dentist.name }} 医生 · {{ r.appointment.clinic.name }}</div>
                <p class="result-tip">{{ apptText(r.appointment) }}</p>
              </div>
              <div v-else-if="r.status === 'INVALID'" class="result bad">
                <div class="result-title"><el-icon><Warning /></el-icon><span>前台未能确认</span></div>
                <p class="result-tip">原因：{{ r.invalidReason }}</p>
                <p class="result-tip">如仍需就诊，可以重新登记或直接致电门店。</p>
              </div>
              <div v-else-if="r.status === 'WITHDRAWN'" class="result muted">
                <div class="result-title"><span>已撤回</span></div>
                <p class="result-tip">撤回于 {{ dateTime(r.withdrawnAt) }}</p>
              </div>
              <div v-else class="result wait">
                <div class="result-title"><span>等待前台致电</span></div>
                <p class="result-tip">前台会拨打 {{ r.contactPhone }} 与您确认牙医和具体时间，请留意来电。</p>
                <el-button size="small" round :loading="withdrawing === r.id" @click="withdraw(r)">撤回预约</el-button>
              </div>
            </div>
          </article>
        </div>
      </template>
    </main>

    <PortalFooter :clinics="data.clinics" />
    <PatientLoginDialog />
  </div>
</template>

<style scoped>
.page-head {
  padding: 56px 0 40px;
  background:
    radial-gradient(800px 320px at 90% 0%, rgba(14, 124, 116, 0.12), transparent 60%),
    linear-gradient(180deg, #f6fbfa, #fff);
}
.page-head h1 {
  margin: 0 0 8px;
  font-size: 34px;
  font-weight: 800;
}
.page-head p {
  margin: 0;
  color: var(--ink-2);
}
.main {
  padding-bottom: 80px;
  min-height: 420px;
}
.toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 22px;
}
.me {
  display: flex;
  align-items: center;
  gap: 12px;
}
.me b {
  display: block;
  font-size: 16px;
}
.me small {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--ink-3);
}
.avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: var(--p);
  color: #fff;
  font-weight: 700;
  font-size: 18px;
}
.filter {
  margin-left: auto;
}
.actions {
  display: flex;
  gap: 8px;
}
.load-error {
  margin-bottom: 16px;
}
.cards {
  display: grid;
  gap: 18px;
  min-height: 120px;
}
.req {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: #fff;
  overflow: hidden;
  transition: box-shadow 0.25s;
}
.req:hover {
  box-shadow: 0 16px 36px rgba(16, 60, 56, 0.08);
}
.req-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding: 14px 22px;
  background: #fafcfb;
  border-bottom: 1px solid var(--line);
}
.req-hint {
  color: var(--ink-2);
  font-size: 14px;
}
.req-time {
  margin-left: auto;
  color: var(--ink-3);
  font-size: 13px;
}
.req-body {
  display: grid;
  grid-template-columns: 1fr 340px;
  gap: 24px;
  padding: 20px 22px 22px;
}
.req-main h3 {
  margin: 0 0 8px;
  font-size: 20px;
}
.req-main p {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 4px;
  color: var(--ink-2);
  font-size: 14.5px;
}
.req-main p .el-icon {
  color: var(--p);
}
.req-main a {
  color: inherit;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 10px 0 4px;
}
.chips span {
  font-size: 12.5px;
  padding: 2px 10px;
  border-radius: 999px;
  background: var(--p-soft);
  color: var(--p-deep);
}
.note {
  font-style: italic;
  color: var(--ink-3) !important;
}
.result {
  border-radius: 14px;
  padding: 14px 16px;
  font-size: 14px;
}
.result-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 700;
  margin-bottom: 6px;
}
.result-tip {
  margin: 4px 0 0;
  color: var(--ink-2);
  font-size: 13.5px;
}
.result .el-button {
  margin-top: 10px;
}
.result.ok {
  background: #eaf6f1;
}
.result.ok .result-title {
  color: #1f7a54;
}
.result-time {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 20px;
  font-weight: 800;
  color: var(--ink);
}
.result-line {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  color: var(--ink-2);
}
.result.bad {
  background: #fdf1ee;
}
.result.bad .result-title {
  color: #b5452a;
}
.result.wait {
  background: #fff6ea;
}
.result.wait .result-title {
  color: #9a6230;
}
.result.muted {
  background: #f4f6f6;
}
.result.muted .result-title {
  color: var(--ink-3);
}
.st-withdrawn,
.st-invalid {
  opacity: 0.85;
}
.empty-card {
  text-align: center;
  padding: 56px 24px;
  border: 1px dashed #cfdcd9;
  border-radius: var(--radius);
  background: #fbfdfc;
}
.empty-card.small {
  padding: 40px 24px;
}
.empty-icon {
  font-size: 44px;
  color: var(--p);
}
.empty-card h3 {
  margin: 12px 0 6px;
  font-size: 20px;
}
.empty-card p {
  margin: 0 0 18px;
  color: var(--ink-2);
}
.empty-card .row {
  display: flex;
  justify-content: center;
  gap: 10px;
}
@media (max-width: 900px) {
  .req-body {
    grid-template-columns: 1fr;
  }
  .filter {
    margin-left: 0;
    order: 3;
    width: 100%;
    overflow-x: auto;
  }
}
@media (max-width: 600px) {
  .page-head {
    padding: 36px 0 28px;
  }
  .page-head h1 {
    font-size: 26px;
  }
  .actions {
    width: 100%;
  }
  .actions .el-button {
    flex: 1;
  }
  .req-head,
  .req-body {
    padding-left: 16px;
    padding-right: 16px;
  }
  .req-time {
    margin-left: 0;
    width: 100%;
  }
}
</style>
