<script setup>
// UC13 核算季度经营业绩（总部）
// 收入由后端按已开结算单汇总；总部填写各牙医的医疗用品成本、运营费用和核对依据，
// 预览（不写库）得到净利润和激励基金，确认时带回 checkToken（协作约定 9.12）
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, EditPen, CircleCheck, WarningFilled, Download } from '@element-plus/icons-vue'
import { quarterApi } from '@/api/stats.js'
import { exportExcel } from '@/utils/exportExcel.js'
import TableEmpty from '@/components/TableEmpty.vue'
import { useSubmit } from '@/composables/useSubmit.js'
import { useDirtyGuard } from '@/composables/useDirtyGuard.js'
import { FUND_STATUS } from '@shared/labels.js'
import { dateTime, money } from '@shared/format.js'

// 默认打开最近一个已结束的季度
const now = new Date()
const currentQuarter = Math.floor(now.getMonth() / 3) + 1
const lastEnded = currentQuarter === 1 ? { year: now.getFullYear() - 1, quarter: 4 } : { year: now.getFullYear(), quarter: currentQuarter - 1 }
const year = ref(lastEnded.year)
const quarter = ref(lastEnded.quarter)
const years = [now.getFullYear(), now.getFullYear() - 1]
const isFuture = (q) => year.value > now.getFullYear() || (year.value === now.getFullYear() && q > currentQuarter)
const rangeText = computed(() => {
  const m = (quarter.value - 1) * 3 + 1
  return `${year.value} 年 ${m}—${m + 2} 月`
})

// ---------- 历史 ----------
const history = ref([])
async function loadHistory() {
  try {
    history.value = await quarterApi.list()
  } catch {
    /* 已统一提示 */
  }
}

// ---------- 准备数据 ----------
const prep = ref(null)
const loading = ref(false)
const editing = ref(false)
const form = reactive({ rows: [], fundNote: '' })
// 核算中填写的成本和依据，离开页面前提醒保存
const guard = useDirtyGuard(() => (editing.value ? form : null), { routeLeave: true })
watch(editing, (v) => !v && guard.clear())

let prepSeq = 0
const loadError = ref('')
async function loadPrep() {
  const mine = ++prepSeq
  loading.value = true
  loadError.value = ''
  preview.value = null
  try {
    const data = await quarterApi.prepare(year.value, quarter.value)
    if (mine !== prepSeq) return
    prep.value = data
    editing.value = !data.existing
    fillForm()
  } catch (e) {
    if (mine === prepSeq) {
      prep.value = null
      loadError.value = e.message || '请稍后重试'
    }
  } finally {
    if (mine === prepSeq) loading.value = false
  }
}
watch([year, quarter], async ([y, q], [oy, oq]) => {
  if (switching) return
  if (!(await guard.confirmLeave('本季度填写的内容还没有确认，切换季度后会丢失。确定切换吗？'))) {
    switching = true // 用户选择留下：把选择改回原季度
    year.value = oy
    quarter.value = oq
    await nextTick() // 等改回原季度触发的这次 watch 跑完
    switching = false
    return
  }
  loadPrep()
})
let switching = false
onMounted(() => {
  loadPrep()
  loadHistory()
})

function fillForm() {
  // 已有确认结果时，以确认结果里的成本、费用和依据为准
  const confirmed = prep.value.existing?.rows || []
  form.rows = prep.value.rows.map((r) => {
    const c = confirmed.find((x) => x.dentistId === r.dentistId)
    return { dentistId: r.dentistId, supplyCost: c ? c.supplyCost : r.supplyCost, operatingCost: c ? c.operatingCost : r.operatingCost, basis: (c ? c.basis : r.basis) || '' }
  })
  form.fundNote = prep.value.existing?.fundNote || ''
  confirmer.reset()
  guard.mark()
}

function startRecalc() {
  editing.value = true
  fillForm()
}
function cancelRecalc() {
  editing.value = false
  preview.value = null
}

const prepRow = (id) => prep.value.rows.find((r) => r.dentistId === id)

// ---------- 预览（自动） ----------
const preview = ref(null)
const previewing = ref(false)
let previewTimer = null
let previewSeq = 0
watch(
  () => (editing.value ? JSON.stringify(form.rows) : null),
  (v) => {
    clearTimeout(previewTimer)
    if (!v || !prep.value) return
    previewTimer = setTimeout(async () => {
      const mine = ++previewSeq
      previewing.value = true
      try {
        const rows = form.rows.map((r) => ({ ...r, basis: r.basis.trim() || null }))
        const result = await quarterApi.preview(year.value, quarter.value, rows)
        if (mine === previewSeq) preview.value = result
      } catch (e) {
        if (mine === previewSeq) preview.value = { problems: [e.message], rows: [], checkToken: null }
      } finally {
        if (mine === previewSeq) previewing.value = false
      }
    }, 400)
  }
)
const previewRow = (id) => preview.value?.rows?.find((r) => r.dentistId === id)
// “还没填写”的问题合并成一句，其余问题逐条显示
const problemList = computed(() => {
  const all = preview.value?.problems || []
  // 模拟数据写“……还没填写”，真实后端写“请填写……”，两种说法都算未填写
  const isUnfilled = (p) => p.includes('还没填写') || p.startsWith('请填写')
  const unfilled = all.filter(isUnfilled)
  const others = all.filter((p) => !isUnfilled(p))
  return unfilled.length ? [`还有 ${unfilled.length} 处成本、费用或核对依据没填写`, ...others] : others
})
const canConfirm = computed(() => preview.value?.checkToken && !previewing.value && (!preview.value.fundNoteRequired || form.fundNote.trim()))

// ---------- 确认 ----------
const confirmer = useSubmit()

async function confirm() {
  const p = preview.value
  const again = Boolean(prep.value.existing)
  try {
    await ElMessageBox.confirm(
      `${again ? `将替换 ${year.value} 年第 ${quarter.value} 季度原有的核算结果。` : ''}公司净利润 ${money(p.companyNetProfit)} 元，${p.incentiveFund !== null ? `计提激励基金 ${money(p.incentiveFund)} 元` : '激励基金转总部处理'}。确认后各牙医业绩即为本季度正式结果。`,
      again ? '重新确认季度核算' : '确认季度核算',
      { type: 'info', confirmButtonText: '确认', cancelButtonText: '再看看' }
    )
  } catch {
    return
  }
  const body = { rows: form.rows.map((r) => ({ ...r, basis: r.basis.trim() || null })), fundNote: form.fundNote.trim() || null, checkToken: p.checkToken }
  const result = await confirmer.run((opts) => quarterApi.confirm(year.value, quarter.value, body, opts), {
    handle: (e) => {
      if (e.code === 'CHANGED') {
        ElMessage.warning(e.message || '预览之后收入有变化，已重新计算，请再核对一次')
        loadPrepKeepForm()
        return true
      }
      if (e.code === 'STATE_NOT_ALLOWED') {
        ElMessage.warning(e.message)
        loadPrepKeepForm()
        return true
      }
      return false
    }
  })
  if (!result) return
  ElMessage.success(`${year.value} 年第 ${quarter.value} 季度核算已确认`)
  prep.value = { ...prep.value, existing: result }
  editing.value = false
  preview.value = null
  loadHistory()
  loadPrep() // 重新取准备数据，保证“重新核算”时带出的是刚确认的数值
}

/** 收入或未开单就诊变化后重新取准备数据，保留已填写的成本和依据 */
async function loadPrepKeepForm() {
  const kept = JSON.parse(JSON.stringify(form))
  try {
    prep.value = await quarterApi.prepare(year.value, quarter.value)
    form.rows = prep.value.rows.map((r) => kept.rows.find((k) => k.dentistId === r.dentistId) || { dentistId: r.dentistId, supplyCost: null, operatingCost: null, basis: '' })
    form.fundNote = kept.fundNote
  } catch {
    /* 已统一提示 */
  }
}

function exportQuarter() {
  const e = prep.value.existing
  exportExcel({
    filename: `季度核算_${e.year}年第${e.quarter}季度`,
    sheets: [{
      name: '牙医业绩',
      title: `${e.year} 年第 ${e.quarter} 季度经营业绩核算`,
      notes: [
        `确认时间：${dateTime(e.confirmedAt)}　首次确认：${dateTime(e.firstConfirmedAt)}　汇总周期：${e.collectionWorkdays} 个工作日`,
        `公司净利润：${money(e.companyNetProfit)} 元　激励基金：${e.incentiveFund !== null ? `${money(e.incentiveFund)} 元（公司净利润 × 10%）` : '无（基金已转总部处理）'}`,
        ...(e.fundNote ? [`基金处理说明：${e.fundNote}`] : []),
        '收入按已开结算单、实际到诊日期和实际接诊牙医汇总；净利润 = 收入 − 医疗用品成本 − 运营费用'
      ],
      columns: [
        { header: '员工编号', key: 'employeeNo', width: 10 },
        { header: '牙医', key: 'name', width: 10 },
        { header: '收入', key: 'revenue', type: 'money' },
        { header: '医疗用品成本', key: 'supplyCost', type: 'money', width: 14 },
        { header: '运营费用', key: 'operatingCost', type: 'money' },
        { header: '净利润', key: 'netProfit', type: 'money' },
        { header: '核对依据', key: 'basis', width: 40 }
      ],
      rows: e.rows
    }]
  })
}

function exportHistory() {
  exportExcel({
    filename: '已确认季度核算汇总',
    sheets: [{
      name: '已确认季度',
      title: '已确认的季度核算',
      columns: [
        { header: '季度', key: 'q', width: 16, value: (q) => `${q.year} 年第 ${q.quarter} 季度` },
        { header: '公司净利润', key: 'companyNetProfit', type: 'money', width: 14 },
        { header: '激励基金', key: 'incentiveFund', type: 'money' },
        { header: '基金状态', key: 'fundStatus', width: 18, value: (q) => FUND_STATUS[q.fundStatus].text },
        { header: '汇总周期（工作日）', key: 'collectionWorkdays', type: 'int', width: 18 },
        { header: '首次确认', key: 'firstConfirmedAt', width: 18, value: (q) => dateTime(q.firstConfirmedAt) },
        { header: '最近确认', key: 'confirmedAt', width: 18, value: (q) => dateTime(q.confirmedAt) },
        { header: '基金处理说明', key: 'fundNote', width: 40 }
      ],
      rows: history.value
    }]
  })
}

function openQuarter(q) {
  year.value = q.year
  quarter.value = q.quarter
}

const profitClass = (n) => (n === null || n === undefined ? '' : n < 0 ? 'neg' : 'pos')
const rowError = (i, field) => confirmer.fieldErrors[`rows[${i}].${field}`]
</script>

<template>
  <div class="page">
    <div class="page-toolbar">
      <div class="toolbar-left">
        <el-select v-model="year" style="width: 110px">
          <el-option v-for="y in years" :key="y" :value="y" :label="`${y} 年`" />
        </el-select>
        <el-radio-group v-model="quarter">
          <el-radio-button v-for="q in 4" :key="q" :value="q" :disabled="isFuture(q)">第 {{ q }} 季度</el-radio-button>
        </el-radio-group>
        <span class="muted">{{ rangeText }}</span>
      </div>
      <el-button :icon="Refresh" @click="loadPrep">刷新</el-button>
    </div>

    <div v-loading="loading" class="main">
      <template v-if="prep">
        <!-- 已确认的结果 -->
        <el-card v-if="prep.existing && !editing" shadow="never" class="result-card">
          <div class="card-head">
            <div>
              <div class="card-title">{{ prep.year }} 年第 {{ prep.quarter }} 季度 · 核算结果</div>
              <div class="muted">最近确认 {{ dateTime(prep.existing.confirmedAt) }}<template v-if="prep.existing.firstConfirmedAt !== prep.existing.confirmedAt"> · 首次确认 {{ dateTime(prep.existing.firstConfirmedAt) }}</template></div>
            </div>
            <div>
              <el-button :icon="Download" @click="exportQuarter">导出 Excel</el-button>
              <el-button :icon="EditPen" @click="startRecalc">重新核算</el-button>
            </div>
          </div>
          <div class="kpis">
            <div class="kpi">
              <div class="kpi-label">公司净利润</div>
              <div class="kpi-value money" :class="profitClass(prep.existing.companyNetProfit)">{{ money(prep.existing.companyNetProfit) }}</div>
            </div>
            <div class="kpi">
              <div class="kpi-label">激励基金</div>
              <div class="kpi-value money">{{ prep.existing.incentiveFund !== null ? money(prep.existing.incentiveFund) : '—' }}</div>
              <el-tag :type="FUND_STATUS[prep.existing.fundStatus].type" size="small" effect="light">{{ FUND_STATUS[prep.existing.fundStatus].text }}</el-tag>
            </div>
            <div class="kpi">
              <div class="kpi-label">汇总周期</div>
              <div class="kpi-value">{{ prep.existing.collectionWorkdays }} <small>个工作日</small></div>
              <div class="kpi-sub">季度结束次日到首次确认</div>
            </div>
          </div>
          <el-alert v-if="prep.existing.fundNote" type="info" :closable="false" class="block" :title="`基金处理说明：${prep.existing.fundNote}`" />
          <el-table :data="prep.existing.rows" size="small" class="rows-table">
            <el-table-column label="牙医" min-width="120">
              <template #default="{ row }">{{ row.name }} <span class="muted">{{ row.employeeNo }}</span></template>
            </el-table-column>
            <el-table-column label="收入" width="120" align="right"><template #default="{ row }"><span class="money">{{ money(row.revenue) }}</span></template></el-table-column>
            <el-table-column label="医疗用品成本" width="120" align="right"><template #default="{ row }"><span class="money">{{ money(row.supplyCost) }}</span></template></el-table-column>
            <el-table-column label="运营费用" width="120" align="right"><template #default="{ row }"><span class="money">{{ money(row.operatingCost) }}</span></template></el-table-column>
            <el-table-column label="净利润" width="120" align="right">
              <template #default="{ row }"><span class="money" :class="profitClass(row.netProfit)">{{ money(row.netProfit) }}</span></template>
            </el-table-column>
            <el-table-column label="核对依据" prop="basis" min-width="200" show-overflow-tooltip />
          </el-table>
        </el-card>

        <!-- 核算 / 重新核算 -->
        <el-card v-else shadow="never" class="result-card">
          <div class="card-head">
            <div>
              <div class="card-title">{{ prep.year }} 年第 {{ prep.quarter }} 季度 · {{ prep.existing ? '重新核算' : '核算' }}</div>
              <div class="muted">收入按已开结算单、实际到诊日期和实际接诊牙医自动汇总；请填写成本、费用和核对依据，填 0 也可以。</div>
            </div>
            <el-button v-if="prep.existing" @click="cancelRecalc">放弃重新核算</el-button>
          </div>

          <el-alert v-if="!prep.quarterEnded" type="warning" :closable="false" show-icon class="block" title="这个季度还没有结束：可以先填写和预览，季度结束后才能确认。" />
          <el-alert v-if="prep.unsettledVisits.length" type="error" :closable="false" show-icon class="block" :title="`还有 ${prep.unsettledVisits.length} 次就诊已确认但未开结算单，开单前不能确认核算（这些就诊的收入尚未计入）`">
            <ul class="unsettled">
              <li v-for="v in prep.unsettledVisits" :key="v.id">{{ dateTime(v.arrivedAt) }} · {{ v.patient.name }}（{{ v.patient.accountNo }}）· {{ v.dentist.name }} · {{ v.clinic.name }} · {{ money(v.total) }} 元</li>
            </ul>
            <div>请通知前台在“就诊结算”中开具结算单，然后点右上角“刷新”。</div>
          </el-alert>

          <el-table :data="form.rows" size="small" class="rows-table edit-table">
            <el-table-column label="牙医" min-width="120">
              <template #default="{ row }">{{ prepRow(row.dentistId)?.name }} <span class="muted">{{ prepRow(row.dentistId)?.employeeNo }}</span></template>
            </el-table-column>
            <el-table-column label="收入" width="110" align="right">
              <template #default="{ row }"><span class="money">{{ money(prepRow(row.dentistId)?.revenue) }}</span></template>
            </el-table-column>
            <el-table-column label="医疗用品成本" width="140">
              <template #default="{ row, $index }">
                <el-input-number v-model="row.supplyCost" :min="0" :precision="2" :controls="false" placeholder="0.00" size="small" style="width: 100%" />
                <div v-if="rowError($index, 'supplyCost')" class="row-error">{{ rowError($index, 'supplyCost') }}</div>
              </template>
            </el-table-column>
            <el-table-column label="运营费用" width="140">
              <template #default="{ row, $index }">
                <el-input-number v-model="row.operatingCost" :min="0" :precision="2" :controls="false" placeholder="0.00" size="small" style="width: 100%" />
                <div v-if="rowError($index, 'operatingCost')" class="row-error">{{ rowError($index, 'operatingCost') }}</div>
              </template>
            </el-table-column>
            <el-table-column label="核对依据" min-width="200">
              <template #default="{ row, $index }">
                <el-input v-model="row.basis" size="small" maxlength="500" placeholder="例如：财务台账 2026-Q3 第 12 页" />
                <div v-if="rowError($index, 'basis')" class="row-error">{{ rowError($index, 'basis') }}</div>
              </template>
            </el-table-column>
            <el-table-column label="净利润" width="120" align="right">
              <template #default="{ row }">
                <span v-if="previewRow(row.dentistId)?.netProfit !== null && previewRow(row.dentistId)?.netProfit !== undefined" class="money" :class="profitClass(previewRow(row.dentistId).netProfit)">{{ money(previewRow(row.dentistId).netProfit) }}</span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
          </el-table>

          <div class="summary">
            <div class="summary-left">
              <span v-if="previewing" class="muted">正在计算…</span>
              <el-alert v-if="problemList.length" type="warning" :closable="false" show-icon title="确认前还需处理">
                <ul class="problems">
                  <li v-for="p in problemList" :key="p">{{ p }}</li>
                </ul>
              </el-alert>
            </div>
            <div class="summary-right">
              <div class="sum-line">公司净利润 <b class="money" :class="profitClass(preview?.companyNetProfit)">{{ preview?.companyNetProfit !== null && preview?.companyNetProfit !== undefined ? money(preview.companyNetProfit) : '—' }}</b></div>
              <div v-if="preview?.incentiveFund !== null && preview?.incentiveFund !== undefined" class="sum-line">激励基金（10%）<b class="money">{{ money(preview.incentiveFund) }}</b></div>
              <div v-else-if="preview?.fundNoteRequired" class="sum-line warn">公司净利润不为正，激励基金转总部处理</div>
            </div>
          </div>

          <el-form v-if="preview?.fundNoteRequired" label-position="top" class="fund-note" @submit.prevent>
            <el-form-item label="基金处理说明" required :error="confirmer.fieldErrors.fundNote">
              <el-input v-model="form.fundNote" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="说明本季度为什么没有可分配利润，以及基金如何处理" />
            </el-form-item>
          </el-form>

          <div class="actions">
            <el-button type="primary" :icon="CircleCheck" :loading="confirmer.saving.value" :disabled="!canConfirm" @click="confirm">{{ prep.existing ? '重新确认核算' : '确认核算' }}</el-button>
          </div>
        </el-card>
      </template>

      <el-card v-if="!prep && loadError" shadow="never">
        <TableEmpty :error="loadError" @retry="loadPrep" />
      </el-card>

      <!-- 已确认的季度 -->
      <el-card shadow="never" class="history">
        <template #header>
          <div class="history-head">
            <span class="card-title">已确认的季度</span>
            <el-button link type="primary" :icon="Download" :disabled="!history.length" @click="exportHistory">导出 Excel</el-button>
          </div>
        </template>
        <el-table :data="history" size="small" empty-text="还没有确认过的季度" highlight-current-row @row-click="openQuarter">
          <el-table-column label="季度" width="130">
            <template #default="{ row }">{{ row.year }} 年第 {{ row.quarter }} 季度</template>
          </el-table-column>
          <el-table-column label="公司净利润" width="130" align="right">
            <template #default="{ row }"><span class="money" :class="profitClass(row.companyNetProfit)">{{ money(row.companyNetProfit) }}</span></template>
          </el-table-column>
          <el-table-column label="激励基金" min-width="170">
            <template #default="{ row }">
              <el-tag :type="FUND_STATUS[row.fundStatus].type" size="small" effect="light">{{ FUND_STATUS[row.fundStatus].text }}</el-tag>
              <span v-if="row.incentiveFund !== null" class="money fund"> {{ money(row.incentiveFund) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="汇总周期" width="110" align="right">
            <template #default="{ row }">{{ row.collectionWorkdays }} 个工作日</template>
          </el-table-column>
          <el-table-column label="最近确认" width="150">
            <template #default="{ row }">{{ dateTime(row.confirmedAt) }}</template>
          </el-table-column>
        </el-table>
        <div class="muted hint"><el-icon><WarningFilled /></el-icon> 汇总周期是愿景指标：从季度结束次日到首次确认核算，周一至周五的天数。</div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.main {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-height: 200px;
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 14px;
}
.card-title {
  font-size: 16px;
  font-weight: 600;
  margin-bottom: 2px;
}
.kpis {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 14px;
}
.kpi {
  padding: 12px 14px;
  border-radius: 10px;
  background: var(--el-fill-color-lighter);
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
}
.kpi-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.kpi-value {
  font-size: 22px;
  font-weight: 600;
}
.kpi-value small {
  font-size: 13px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
.kpi-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.pos {
  color: var(--el-color-primary);
}
.neg {
  color: var(--el-color-danger);
}
.block {
  margin-bottom: 14px;
}
.rows-table {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}
.row-error {
  font-size: 12px;
  color: var(--el-color-danger);
  line-height: 1.5;
}
.unsettled {
  margin: 4px 0 6px;
  padding-left: 18px;
}
.summary {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-top: 14px;
}
.summary-left {
  flex: 1 1 260px;
}
.problems {
  margin: 4px 0 0;
  padding-left: 18px;
}
.summary-right {
  margin-left: auto;
  text-align: right;
  white-space: nowrap;
}
.sum-line {
  font-size: 14px;
  line-height: 2;
}
.sum-line b {
  font-size: 20px;
  margin-left: 6px;
}
.sum-line.warn {
  color: var(--el-color-warning-dark-2);
}
.fund-note {
  margin-top: 10px;
}
.actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
  margin-top: 10px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.history-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.fund {
  margin-left: 6px;
}
.hint {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 10px;
  font-size: 12px;
}
</style>
