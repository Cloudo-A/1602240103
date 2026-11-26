<script setup>
// 牙医工作台：今天自己接诊的患者（待记录的排在前面）、今天还没到的预约
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { FirstAidKit, Notebook, Refresh } from '@element-plus/icons-vue'
import { appointmentApi, visitApi } from '@/api/business.js'
import { useLoad } from '@/composables/useLoad.js'
import StatTile from './StatTile.vue'
import WorkCard from './WorkCard.vue'
import { APPOINTMENT_STATUS, VISIT_STATUS } from '@shared/labels.js'
import { toApiDate, timeOnly, money } from '@shared/format.js'

const router = useRouter()
const today = toApiDate()

// 牙医调用时，后端只返回自己的预约和自己接诊的就诊
const visits = useLoad(() => visitApi.list({ from: today, to: today }), [])
const appts = useLoad(() => appointmentApi.list({ from: today, to: today }), [])
function loadAll() {
  visits.load()
  appts.load()
}
onMounted(loadAll)

const vcount = (s) => visits.data.value.filter((v) => v.status === s).length
const toRecord = computed(() => visits.data.value.filter((v) => v.status === 'UNCONFIRMED'))
const notArrived = computed(() => appts.data.value.filter((a) => a.status === 'VALID' || a.status === 'PENDING_VERIFY'))
const doneTotal = computed(() => visits.data.value.reduce((s, v) => s + Number(v.total || 0), 0))

const go = (path, query) => router.push({ path, query })
</script>

<template>
  <div class="bench">
    <div class="tiles">
      <StatTile label="今日接诊" :value="visits.error.value ? '—' : visits.data.value.length" unit="位" tone="primary" to="/dentist/visits" />
      <StatTile label="待记录诊疗" :value="visits.error.value ? '—' : toRecord.length" unit="位" sub="已到诊、还没确认诊疗" :tone="toRecord.length ? 'warning' : 'plain'" to="/dentist/visits" />
      <StatTile label="已确认诊疗" :value="visits.error.value ? '—' : vcount('CONFIRMED') + vcount('SETTLED')" unit="位" :sub="`治疗合计 ${money(doneTotal)} 元`" to="/dentist/visits" />
      <StatTile label="还没到的预约" :value="appts.error.value ? '—' : notArrived.length" unit="位" sub="今天的预约中尚未到诊" to="/dentist/visits" />
    </div>

    <div class="actions">
      <el-button type="primary" :icon="FirstAidKit" @click="go('/dentist/visits')">记录诊疗</el-button>
      <el-button :icon="Notebook" @click="go('/dentist/records')">查阅诊疗资料</el-button>
      <el-button :icon="Refresh" text @click="loadAll">刷新</el-button>
    </div>

    <div class="grid">
      <WorkCard title="待记录的患者" to="/dentist/visits" :loading="visits.loading.value" :error="visits.error.value" :empty="!toRecord.length" empty-text="今天没有待记录的患者" @retry="visits.load">
        <ul class="list">
          <li v-for="v in toRecord" :key="v.id">
            <span class="time">{{ timeOnly(v.arrivedAt) }}</span>
            <span class="main">
              <span><b>{{ v.patient.name }}</b> <span class="muted">{{ v.patient.accountNo }}</span></span>
              <span class="sub">{{ v.clinic.name }} · 已到诊</span>
            </span>
            <el-button size="small" plain @click="go('/dentist/records', { patientId: v.patient.id })">病历</el-button>
            <el-button size="small" type="primary" plain @click="go('/dentist/visits', { visitId: v.id })">去记录</el-button>
          </li>
        </ul>
      </WorkCard>

      <WorkCard title="今天的预约" :loading="appts.loading.value" :error="appts.error.value" :empty="!appts.data.value.length" empty-text="今天没有预约" @retry="appts.load">
        <ul class="list">
          <li v-for="a in appts.data.value" :key="a.id">
            <span class="time">{{ timeOnly(a.scheduledAt) }}</span>
            <span class="main">
              <span><b>{{ a.patient.name }}</b> <span class="muted">{{ a.patient.accountNo }}</span></span>
              <span class="sub">{{ a.clinic.name }} · {{ a.items.map((i) => i.name).join('、') }}</span>
            </span>
            <el-tag :type="APPOINTMENT_STATUS[a.status].type" size="small" effect="light">{{ APPOINTMENT_STATUS[a.status].text }}</el-tag>
          </li>
        </ul>
      </WorkCard>

      <WorkCard v-if="visits.data.value.length > toRecord.length" title="今天已处理" to="/dentist/visits" class="span-2">
        <ul class="list">
          <li v-for="v in visits.data.value.filter((x) => x.status !== 'UNCONFIRMED')" :key="v.id">
            <span class="time">{{ timeOnly(v.arrivedAt) }}</span>
            <span class="main">
              <b>{{ v.patient.name }}</b>
              <span class="sub">{{ v.clinic.name }}<template v-if="v.total !== null"> · {{ money(v.total) }} 元</template></span>
            </span>
            <el-tag :type="VISIT_STATUS[v.status].type" size="small" effect="light">{{ VISIT_STATUS[v.status].text }}</el-tag>
          </li>
        </ul>
      </WorkCard>
    </div>
  </div>
</template>
