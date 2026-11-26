<script setup>
// 前台工作台：今天的预约、到诊、待核实，待开结算单，常用操作
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, Promotion, User, Search, Refresh, Phone } from '@element-plus/icons-vue'
import { appointmentApi, visitApi, settlementApi } from '@/api/business.js'
import { requestApi } from '@/api/portal.js'
import { timeSlotText } from '@shared/labels.js'
import { useLoad } from '@/composables/useLoad.js'
import StatTile from './StatTile.vue'
import WorkCard from './WorkCard.vue'
import { toApiDate, timeOnly, dateTime, money } from '@shared/format.js'

const router = useRouter()
const today = toApiDate()
const daysAgo = (n) => {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return toApiDate(d)
}

const appts = useLoad(() => appointmentApi.list({ from: today, to: today }), [])
const pending = useLoad(() => visitApi.list({ from: daysAgo(90), to: today, status: 'CONFIRMED' }), [])
const issued = useLoad(() => settlementApi.list({ from: today, to: today }), [])
// 患者在官网、小程序登记的预约申请（先提交的排前面，先回电）
const requests = useLoad(() => requestApi.list({ status: 'PENDING' }).then((list) => list.reverse()), [])
function loadAll() {
  appts.load()
  pending.load()
  issued.load()
  requests.load()
}
onMounted(loadAll)

const count = (status) => appts.data.value.filter((a) => a.status === status).length
const upcoming = computed(() => appts.data.value.filter((a) => a.status === 'VALID').slice(0, 6))
const overdue = computed(() => appts.data.value.filter((a) => a.status === 'PENDING_VERIFY'))
const pendingTotal = computed(() => pending.data.value.reduce((s, v) => s + Number(v.total || 0), 0))
const issuedTotal = computed(() => issued.data.value.reduce((s, x) => s + Number(x.total || 0), 0))

const go = (path, query) => router.push({ path, query })
</script>

<template>
  <div class="bench">
    <div class="tiles">
      <StatTile label="今日预约" :value="appts.error.value ? '—' : appts.data.value.length" unit="条" :sub="`已到诊 ${count('ARRIVED')} · 已取消 ${count('CANCELLED')}`" tone="primary" to="/front/appointments" />
      <StatTile label="等待到诊" :value="appts.error.value ? '—' : count('VALID')" unit="位" sub="今天还没到预约时间的患者" to="/front/appointments" />
      <StatTile label="待核实" :value="appts.error.value ? '—' : count('PENDING_VERIFY')" unit="条" sub="已过预约时间仍未到诊" :tone="count('PENDING_VERIFY') ? 'warning' : 'plain'" to="/front/appointments" />
      <StatTile label="待处理预约申请" :value="requests.error.value ? '—' : requests.data.value.length" unit="条" sub="官网、小程序登记，待回电确认" :tone="requests.data.value.length ? 'warning' : 'plain'" to="/front/requests" />
      <StatTile label="待开结算单" :value="pending.error.value ? '—' : pending.data.value.length" unit="次" :sub="`合计 ${money(pendingTotal)} 元`" :tone="pending.data.value.length ? 'warning' : 'plain'" to="/front/settlements" />
      <StatTile label="今日已开结算单" :value="issued.error.value ? '—' : issued.data.value.length" unit="张" :sub="`合计 ${money(issuedTotal)} 元`" :to="{ path: '/front/settlements', query: { tab: 'issued' } }" />
    </div>

    <div class="actions">
      <el-button type="primary" :icon="Plus" @click="go('/front/appointments', { action: 'create' })">新建预约</el-button>
      <el-button :icon="Promotion" @click="go('/front/appointments', { action: 'walkin' })">现场就诊</el-button>
      <el-button :icon="User" @click="go('/front/patients', { action: 'create' })">新建患者档案</el-button>
      <el-button :icon="Search" @click="go('/front/settlements', { tab: 'issued' })">查询结算单</el-button>
      <el-button :icon="Refresh" text @click="loadAll">刷新</el-button>
    </div>

    <div class="grid">
      <WorkCard v-if="requests.data.value.length" title="待处理的预约申请" to="/front/requests" class="span-2">
        <div class="hint">患者在官网或小程序登记的预约，请回电与患者确认牙医和时间后转为预约。</div>
        <ul class="list">
          <li v-for="r in requests.data.value.slice(0, 5)" :key="r.id">
            <span class="time wide">{{ r.preferredDate.slice(5) }}</span>
            <span class="main">
              <span><b>{{ r.contactName }}</b> <a class="muted" :href="'tel:' + r.contactPhone"><el-icon><Phone /></el-icon>{{ r.contactPhone }}</a></span>
              <span class="sub">{{ r.clinic.name }} · {{ timeSlotText(r.timeSlot) }}<template v-if="r.items.length"> · {{ r.items.map((i) => i.name).join('、') }}</template></span>
            </span>
            <el-button size="small" type="primary" plain @click="go('/front/appointments', { requestId: r.id })">转为预约</el-button>
          </li>
        </ul>
      </WorkCard>
      <WorkCard title="接下来的预约" to="/front/appointments" :loading="appts.loading.value" :error="appts.error.value" :empty="!upcoming.length" empty-text="今天没有等待到诊的预约" @retry="appts.load">
        <ul class="list">
          <li v-for="a in upcoming" :key="a.id">
            <span class="time">{{ timeOnly(a.scheduledAt) }}</span>
            <span class="main">
              <span><b>{{ a.patient.name }}</b> <span class="muted">{{ a.patient.accountNo }}</span></span>
              <span class="sub">{{ a.dentist.name }} · {{ a.clinic.name }} · {{ a.items.map((i) => i.name).join('、') }}</span>
            </span>
            <el-button size="small" type="primary" plain @click="go('/front/appointments', { arrive: a.id })">到诊</el-button>
          </li>
        </ul>
      </WorkCard>

      <WorkCard title="待开结算单" to="/front/settlements" :loading="pending.loading.value" :error="pending.error.value" :empty="!pending.data.value.length" empty-text="没有待开结算单的就诊" @retry="pending.load">
        <ul class="list">
          <li v-for="v in pending.data.value.slice(0, 6)" :key="v.id">
            <span class="time wide">{{ v.arrivedAt.slice(0, 10) === today ? timeOnly(v.arrivedAt) : v.arrivedAt.slice(5, 10) }}</span>
            <span class="main">
              <span><b>{{ v.patient.name }}</b> <span class="muted">{{ v.patient.accountNo }}</span></span>
              <span class="sub">{{ v.dentist.name }} · {{ v.clinic.name }} · <span class="money">{{ money(v.total) }}</span> 元</span>
            </span>
            <el-button size="small" type="primary" plain @click="go('/front/settlements', { visitId: v.id })">开单</el-button>
          </li>
        </ul>
      </WorkCard>

      <WorkCard v-if="overdue.length" title="已过预约时间仍未到诊" to="/front/appointments" class="span-2">
        <div class="hint">请联系患者核实：已经来了就登记到诊；患者表示不来了就按取消处理。未核实前不计入取消。</div>
        <ul class="list">
          <li v-for="a in overdue" :key="a.id">
            <span class="time">{{ timeOnly(a.scheduledAt) }}</span>
            <span class="main">
              <span><b>{{ a.patient.name }}</b> <span class="muted">{{ a.patient.accountNo }}</span></span>
              <span class="sub">{{ a.dentist.name }} · {{ a.clinic.name }}</span>
            </span>
            <el-button size="small" plain @click="go('/front/appointments', { arrive: a.id })">登记到诊</el-button>
          </li>
        </ul>
      </WorkCard>
    </div>
    <div v-if="issued.data.value.length" class="foot muted">今天最近一张结算单：{{ issued.data.value[0].settlementNo }}（{{ issued.data.value[0].patient.name }}，{{ dateTime(issued.data.value[0].issuedAt) }}）</div>
  </div>
</template>

