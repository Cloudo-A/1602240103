<script setup>
// 总部工作台：本月取消情况、上季度核算进度、资质待核实、基础资料概况
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Coin, DataAnalysis, Avatar, Refresh } from '@element-plus/icons-vue'
import { options } from '@/api/base.js'
import { statsApi, quarterApi } from '@/api/stats.js'
import { useLoad } from '@/composables/useLoad.js'
import StatTile from './StatTile.vue'
import WorkCard from './WorkCard.vue'
import { FUND_STATUS } from '@shared/labels.js'
import { toApiDate, dateTime, money, rate } from '@shared/format.js'

const router = useRouter()
const now = new Date()
const monthFrom = toApiDate(new Date(now.getFullYear(), now.getMonth(), 1))
const monthTo = toApiDate(new Date(now.getFullYear(), now.getMonth() + 1, 0))
const curQ = Math.floor(now.getMonth() / 3) + 1
const lastQ = curQ === 1 ? { year: now.getFullYear() - 1, quarter: 4 } : { year: now.getFullYear(), quarter: curQ - 1 }

const base = useLoad(async () => {
  const [clinics, dentists, items] = await Promise.all([options.clinics(false), options.dentists(false), options.treatmentItems(false)])
  return { clinics, dentists, items }
}, null)
const cancel = useLoad(() => statsApi.cancellations({ from: monthFrom, to: monthTo }), null)
const quals = useLoad(() => statsApi.demandQualification({ from: monthFrom, to: monthTo }), null)
const quarter = useLoad(() => quarterApi.prepare(lastQ.year, lastQ.quarter), null)
function loadAll() {
  base.load()
  cancel.load()
  quals.load()
  quarter.load()
}
onMounted(loadAll)

const activeCount = (list) => (list || []).filter((x) => x.active).length
const monthTotals = computed(() => {
  const rows = cancel.data.value?.rows || []
  return { total: rows.reduce((s, r) => s + r.total, 0), cancelled: rows.reduce((s, r) => s + r.cancelled, 0), pending: rows.reduce((s, r) => s + r.pendingVerify, 0) }
})
const maxRate = computed(() => Math.max(0.0001, ...(cancel.data.value?.rows || []).map((r) => r.rate || 0)))
const incomplete = computed(() => quals.data.value?.incomplete || [])
const q = computed(() => quarter.data.value)
const quarterTile = computed(() => {
  if (quarter.error.value || !q.value) return { value: '—', sub: '', tone: 'plain' }
  if (q.value.existing) return { value: '已确认', sub: `汇总周期 ${q.value.existing.collectionWorkdays} 个工作日`, tone: 'primary' }
  if (q.value.unsettledVisits.length) return { value: '待开单', sub: `${q.value.unsettledVisits.length} 次就诊未开结算单`, tone: 'danger' }
  return { value: '待核算', sub: '可以开始核算', tone: 'warning' }
})

const go = (path, query) => router.push({ path, query })
</script>

<template>
  <div class="bench">
    <div class="tiles">
      <StatTile label="使用中的诊所" :value="base.data.value ? activeCount(base.data.value.clinics) : '—'" unit="家" :sub="base.data.value ? `共 ${base.data.value.clinics.length} 家` : ''" to="/hq/clinics" />
      <StatTile label="在职牙医" :value="base.data.value ? activeCount(base.data.value.dentists) : '—'" unit="位" :sub="quals.data.value ? `资质待核实 ${incomplete.length} 项` : ''" :tone="incomplete.length ? 'warning' : 'plain'" to="/hq/dentists" />
      <StatTile label="本月预约" :value="cancel.data.value ? monthTotals.total : '—'" unit="条" :sub="cancel.data.value ? `患者取消 ${monthTotals.cancelled} · 待核实 ${monthTotals.pending}` : ''" tone="primary" to="/hq/cancellations" />
      <StatTile :label="`${lastQ.year} 年第 ${lastQ.quarter} 季度核算`" :value="quarterTile.value" :sub="quarterTile.sub" :tone="quarterTile.tone" to="/hq/quarters" />
    </div>

    <div class="actions">
      <el-button type="primary" :icon="Coin" @click="go('/hq/quarters')">季度核算</el-button>
      <el-button :icon="DataAnalysis" @click="go('/hq/cancellations')">取消统计</el-button>
      <el-button :icon="Avatar" @click="go('/hq/dentists')">牙医资料</el-button>
      <el-button :icon="Refresh" text @click="loadAll">刷新</el-button>
    </div>

    <div class="grid">
      <WorkCard :title="`本月各诊所取消情况（${monthFrom.slice(5)} 至 ${monthTo.slice(5)}）`" to="/hq/cancellations" link-text="取消统计" :loading="cancel.loading.value" :error="cancel.error.value" :empty="!cancel.data.value?.rows.length" @retry="cancel.load">
        <div v-if="cancel.data.value && !cancel.data.value.periodEnded" class="hint">本月还没结束，数字会继续变化。</div>
        <ul class="list">
          <li v-for="r in cancel.data.value?.rows || []" :key="r.clinicId">
            <span class="main">
              <b>{{ r.clinicName }}</b>
              <span class="sub">预约 {{ r.total }} · 取消 {{ r.cancelled }} · 待核实 {{ r.pendingVerify }}</span>
            </span>
            <span class="rate-bar"><span :style="{ width: `${((r.rate || 0) / maxRate) * 100}%` }" /></span>
            <span class="rate-val" :class="{ na: r.rate === null }">{{ rate(r.rate) }}</span>
          </li>
        </ul>
      </WorkCard>

      <WorkCard :title="`${lastQ.year} 年第 ${lastQ.quarter} 季度核算`" to="/hq/quarters" link-text="去核算" :loading="quarter.loading.value" :error="quarter.error.value" @retry="quarter.load">
        <template v-if="q">
          <div v-if="q.existing" class="q-done">
            <div class="q-line">已于 {{ dateTime(q.existing.confirmedAt) }} 确认</div>
            <div class="q-figures">
              <div><span class="muted">公司净利润</span><b class="money">{{ money(q.existing.companyNetProfit) }}</b></div>
              <div><span class="muted">激励基金</span><b class="money">{{ q.existing.incentiveFund !== null ? money(q.existing.incentiveFund) : '—' }}</b></div>
              <div><span class="muted">汇总周期</span><b>{{ q.existing.collectionWorkdays }} 个工作日</b></div>
            </div>
            <el-tag :type="FUND_STATUS[q.existing.fundStatus].type" size="small" effect="light">{{ FUND_STATUS[q.existing.fundStatus].text }}</el-tag>
          </div>
          <template v-else>
            <div v-if="q.unsettledVisits.length" class="hint">还有 {{ q.unsettledVisits.length }} 次就诊已确认但未开结算单，需前台开单后才能确认核算：</div>
            <ul v-if="q.unsettledVisits.length" class="list">
              <li v-for="v in q.unsettledVisits" :key="v.id">
                <span class="main"><b>{{ v.patient.name }}</b><span class="sub">{{ v.arrivedAt.slice(0, 10) }} · {{ v.dentist.name }} · {{ v.clinic.name }} · {{ money(v.total) }} 元</span></span>
              </li>
            </ul>
            <div v-else class="q-line">还没有确认。本季度收入已经可以汇总，填写各牙医成本和费用后即可确认。</div>
            <div class="q-line muted">现在确认，汇总周期将是从季度结束次日到今天的工作日数。</div>
          </template>
        </template>
      </WorkCard>

      <WorkCard title="资质待核实" to="/hq/dentists" link-text="牙医资料" :loading="quals.loading.value" :error="quals.error.value" :empty="!incomplete.length" empty-text="所有在职牙医的资质都已核实" @retry="quals.load">
        <ul class="list">
          <li v-for="(i, idx) in incomplete" :key="idx">
            <span class="main"><span><b>{{ i.name }}</b> <span class="muted">{{ i.employeeNo }}</span></span></span>
            <el-tag type="warning" size="small" effect="light">{{ i.issue }}</el-tag>
          </li>
        </ul>
      </WorkCard>

      <WorkCard title="基础资料" :loading="base.loading.value" :error="base.error.value" @retry="base.load">
        <ul v-if="base.data.value" class="list">
          <li>
            <span class="main"><b>诊所</b><span class="sub">使用中 {{ activeCount(base.data.value.clinics) }} 家，已停用 {{ base.data.value.clinics.length - activeCount(base.data.value.clinics) }} 家</span></span>
            <el-button size="small" text type="primary" @click="go('/hq/clinics')">管理</el-button>
          </li>
          <li>
            <span class="main"><b>牙医</b><span class="sub">在职 {{ activeCount(base.data.value.dentists) }} 位，已停用 {{ base.data.value.dentists.length - activeCount(base.data.value.dentists) }} 位</span></span>
            <el-button size="small" text type="primary" @click="go('/hq/dentists')">管理</el-button>
          </li>
          <li>
            <span class="main"><b>治疗项目</b><span class="sub">使用中 {{ activeCount(base.data.value.items) }} 项，已停用 {{ base.data.value.items.length - activeCount(base.data.value.items) }} 项</span></span>
            <el-button size="small" text type="primary" @click="go('/hq/treatment-items')">管理</el-button>
          </li>
        </ul>
      </WorkCard>
    </div>
  </div>
</template>

<style scoped>
.rate-bar {
  width: 110px;
  height: 8px;
  border-radius: 4px;
  background: var(--el-fill-color);
  overflow: hidden;
  flex: none;
}
.rate-bar span {
  display: block;
  height: 100%;
  border-radius: 4px;
  background: var(--el-color-primary-light-3);
}
.rate-val {
  width: 60px;
  text-align: right;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.rate-val.na {
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
.q-line {
  font-size: 13px;
  line-height: 1.8;
  margin: 4px 0;
}
.q-figures {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin: 8px 0 10px;
}
.q-figures div {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
}
.q-figures b {
  font-size: 18px;
}
</style>
