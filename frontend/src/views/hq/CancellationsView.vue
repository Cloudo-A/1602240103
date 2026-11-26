<script setup>
// UC11 查看取消就诊统计（总部）：按预约日期统计各诊所的预约数、患者取消数和取消率
// 取消率由后端计算；分母为 0 时显示“不适用”；待核实（过期未到诊）不算取消
import { computed, onMounted, ref, watch } from 'vue'
import { Refresh, InfoFilled, Download } from '@element-plus/icons-vue'
import { statsApi } from '@/api/stats.js'
import { exportExcel } from '@/utils/exportExcel.js'
import TableEmpty from '@/components/TableEmpty.vue'
import { options } from '@/api/base.js'
import PeriodPicker from '@/components/PeriodPicker.vue'
import { APPOINTMENT_STATUS } from '@shared/labels.js'
import { dateTime, rate } from '@shared/format.js'

const period = ref([])
const clinicIds = ref([])
const clinicOptions = ref([])
const loading = ref(false)
const result = ref(null)

let seq = 0
const loadError = ref('') // 查询失败时不显示成 0（报告 Q4）
async function load() {
  if (!period.value?.length) return
  const mine = ++seq
  loading.value = true
  loadError.value = ''
  try {
    const data = await statsApi.cancellations({ from: period.value[0], to: period.value[1], clinicIds: clinicIds.value })
    if (mine === seq) result.value = data
  } catch (e) {
    if (mine === seq) {
      result.value = null
      loadError.value = e.message || '请稍后重试'
    }
  } finally {
    if (mine === seq) loading.value = false
  }
}
watch([period, clinicIds], load)

const picker = ref()
onMounted(async () => {
  period.value = picker.value.presets.find((p) => p.key === 'lastQuarter').range()
  try {
    clinicOptions.value = await options.clinics(false)
  } catch {
    /* 已统一提示 */
  }
})

const totals = computed(() => {
  const rows = result.value?.rows || []
  return {
    total: rows.reduce((s, r) => s + r.total, 0),
    cancelled: rows.reduce((s, r) => s + r.cancelled, 0),
    pendingVerify: rows.reduce((s, r) => s + r.pendingVerify, 0)
  }
})
function exportResult() {
  const r = result.value
  exportExcel({
    filename: `取消就诊统计_${r.from}至${r.to}`,
    sheets: [{
      name: '取消统计',
      title: '患者取消就诊统计',
      notes: [`统计期间：${r.from} 至 ${r.to}${r.periodEnded ? '' : '（期间未结束，结果可能随后续记录变化）'}`, `统计时点：${dateTime(r.generatedAt)}`, '口径：按预约日期统计；取消率 = 患者取消数 ÷ 预约总数（含已取消）；待核实不计入取消'],
      columns: [
        { header: '诊所', key: 'clinicName', width: 16 },
        { header: '预约总数', key: 'total', type: 'int' },
        { header: '患者取消', key: 'cancelled', type: 'int' },
        { header: '待核实', key: 'pendingVerify', type: 'int' },
        { header: '取消率', key: 'rate', type: 'percent' }
      ],
      rows: r.rows,
      footer: [`合计：预约 ${totals.value.total} 条，患者取消 ${totals.value.cancelled} 条，待核实 ${totals.value.pendingVerify} 条`]
    }]
  })
}

const maxRate = computed(() => Math.max(0.0001, ...(result.value?.rows || []).map((r) => r.rate || 0)))

// ---------- 明细 ----------
const detailOpen = ref(false)
const detailRow = ref(null)
const details = ref([])
const detailLoading = ref(false)
const detailFilter = ref('')

async function openDetail(row) {
  detailRow.value = row
  detailFilter.value = ''
  details.value = []
  detailOpen.value = true
  detailLoading.value = true
  try {
    details.value = await statsApi.cancellationDetails({ clinicId: row.clinicId, from: result.value.from, to: result.value.to })
  } catch {
    /* 已统一提示 */
  } finally {
    detailLoading.value = false
  }
}
const shownDetails = computed(() => (detailFilter.value ? details.value.filter((a) => a.status === detailFilter.value) : details.value))
</script>

<template>
  <div class="page">
    <div class="page-toolbar">
      <div class="toolbar-left">
        <PeriodPicker ref="picker" v-model="period" />
        <el-select v-model="clinicIds" multiple collapse-tags clearable placeholder="全部诊所" style="width: 200px">
          <el-option v-for="c in clinicOptions" :key="c.id" :value="c.id" :label="c.active ? c.name : `${c.name}（已停用）`" />
        </el-select>
      </div>
      <div>
        <el-button :icon="Download" :disabled="!result?.rows.length" @click="exportResult">导出 Excel</el-button>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="page-intro">按<b>预约日期</b>统计：取消率 = 患者取消数 ÷ 预约总数（含已取消）。过了预约时间仍未到诊的记为“待核实”，不算取消。</div>

    <el-alert v-if="result && !result.periodEnded" type="warning" show-icon :closable="false" class="block" title="统计期间还没有结束，数字会随新的预约和取消继续变化。" />

    <el-card v-loading="loading" shadow="never" class="table-card">
      <el-table :data="result?.rows || []" :show-summary="Boolean(result?.rows.length)" :summary-method="() => ['合计', totals.total, totals.cancelled, totals.pendingVerify, '', '']">
        <template #empty><TableEmpty :error="loadError" text="没有符合条件的诊所" @retry="load" /></template>
        <el-table-column label="诊所" prop="clinicName" min-width="140" />
        <el-table-column label="预约总数" prop="total" width="110" align="right" />
        <el-table-column label="患者取消" prop="cancelled" width="110" align="right" />
        <el-table-column label="待核实" width="110" align="right">
          <template #default="{ row }">
            <span :class="{ warn: row.pendingVerify }">{{ row.pendingVerify }}</span>
          </template>
        </el-table-column>
        <el-table-column label="取消率" min-width="220">
          <template #default="{ row }">
            <div v-if="row.rate !== null" class="rate">
              <div class="bar"><div class="fill" :style="{ width: `${(row.rate / maxRate) * 100}%` }" /></div>
              <span class="rate-text">{{ rate(row.rate) }}</span>
            </div>
            <el-tooltip v-else content="这段时间没有预约，取消率无法计算" placement="top">
              <span class="muted na">不适用 <el-icon><InfoFilled /></el-icon></span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="" width="100" align="center">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="!row.total" @click="openDetail(row)">查看明细</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    <div v-if="result" class="foot muted">统计期间 {{ result.from }} 至 {{ result.to }} · 生成于 {{ dateTime(result.generatedAt) }}</div>

    <el-drawer v-model="detailOpen" :title="detailRow ? `${detailRow.clinicName} · 预约明细` : '预约明细'" size="640px">
      <div class="drawer-filter">
        <el-radio-group v-model="detailFilter" size="small">
          <el-radio-button value="">全部 {{ details.length }}</el-radio-button>
          <el-radio-button v-for="(s, key) in APPOINTMENT_STATUS" :key="key" :value="key">{{ s.text }} {{ details.filter((a) => a.status === key).length }}</el-radio-button>
        </el-radio-group>
      </div>
      <el-table v-loading="detailLoading" :data="shownDetails" size="small" empty-text="没有预约">
        <el-table-column label="预约时间" width="140">
          <template #default="{ row }">{{ dateTime(row.scheduledAt) }}</template>
        </el-table-column>
        <el-table-column label="患者" min-width="110">
          <template #default="{ row }">{{ row.patient.name }}<div class="cell-sub">{{ row.patient.accountNo }}</div></template>
        </el-table-column>
        <el-table-column label="牙医" width="90">
          <template #default="{ row }">{{ row.dentist.name }}</template>
        </el-table-column>
        <el-table-column label="状态" min-width="150">
          <template #default="{ row }">
            <el-tag :type="APPOINTMENT_STATUS[row.status].type" size="small" effect="light">{{ APPOINTMENT_STATUS[row.status].text }}</el-tag>
            <div v-if="row.cancelledAt" class="cell-sub">{{ row.cancelledBy }} · {{ dateTime(row.cancelledAt) }}</div>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<style scoped>
.block {
  margin-bottom: 14px;
}
.warn {
  color: var(--el-color-warning-dark-2);
  font-weight: 600;
}
.rate {
  display: flex;
  align-items: center;
  gap: 10px;
}
.bar {
  flex: 1;
  height: 8px;
  border-radius: 4px;
  background: var(--el-fill-color);
  overflow: hidden;
}
.fill {
  height: 100%;
  border-radius: 4px;
  background: var(--el-color-primary-light-3);
}
.rate-text {
  width: 64px;
  text-align: right;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.na {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.foot {
  margin-top: 10px;
  font-size: 12px;
}
.drawer-filter {
  margin-bottom: 12px;
}
</style>
