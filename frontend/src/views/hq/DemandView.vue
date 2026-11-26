<script setup>
// UC12 查看治疗需求与牙医资质对照（总部）
// 左边：各诊所各项目的预约意向次数（按预约日期）和实际治疗数量（按到诊日期），两者分开不相加
// 右边：每种资质有哪些已核实的牙医；下方列出资质待核实的牙医
import { computed, onMounted, ref, watch } from 'vue'
import { Refresh, WarningFilled, Download } from '@element-plus/icons-vue'
import { statsApi } from '@/api/stats.js'
import { exportExcel } from '@/utils/exportExcel.js'
import TableEmpty from '@/components/TableEmpty.vue'
import { options } from '@/api/base.js'
import PeriodPicker from '@/components/PeriodPicker.vue'
import { quantity } from '@shared/format.js'

const period = ref([])
const clinicIds = ref([])
const clinicOptions = ref([])
const loading = ref(false)
const result = ref(null)

let seq = 0
const loadError = ref('')
async function load() {
  if (!period.value?.length) return
  const mine = ++seq
  loading.value = true
  loadError.value = ''
  try {
    const data = await statsApi.demandQualification({ from: period.value[0], to: period.value[1], clinicIds: clinicIds.value })
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

// 同一诊所的行合并“诊所”单元格
const demand = computed(() => result.value?.demand || [])
function spanMethod({ row, rowIndex, columnIndex }) {
  if (columnIndex !== 0) return
  const prev = demand.value[rowIndex - 1]
  if (prev && prev.clinicId === row.clinicId) return { rowspan: 0, colspan: 0 }
  let n = 1
  while (demand.value[rowIndex + n]?.clinicId === row.clinicId) n += 1
  return { rowspan: n, colspan: 1 }
}
function exportResult() {
  const r = result.value
  const [from, to] = period.value
  const scope = clinicIds.value.length ? clinicOptions.value.filter((c) => clinicIds.value.includes(c.id)).map((c) => c.name).join('、') : '全部诊所'
  exportExcel({
    filename: `治疗需求与资质对照_${from}至${to}`,
    sheets: [
      {
        name: '治疗需求',
        title: '治疗需求（按诊所、治疗项目）',
        notes: [`期间：${from} 至 ${to}`, `诊所范围：${scope}`, '意向次数按预约日期统计未取消的预约；实际数量按到诊日期统计已确认的治疗；两者不相加'],
        columns: [
          { header: '诊所', key: 'clinicName', width: 14 },
          { header: '治疗项目', key: 'itemName', width: 16 },
          { header: '预约意向次数', key: 'intendedCount', type: 'int', width: 14 },
          { header: '实际治疗数量', key: 'actualQuantity', type: 'quantity', width: 14 },
          { header: '单位', key: 'unitName', width: 8 }
        ],
        rows: r.demand
      },
      {
        name: '已核实资质',
        title: '已核实的牙医资质（在职牙医）',
        columns: [
          { header: '资质', key: 'name', width: 16 },
          { header: '已核实人数', key: 'verifiedCount', type: 'int', width: 12 },
          { header: '牙医名单', key: 'dentists', width: 50, value: (q) => q.dentists.map((d) => `${d.name}（${d.employeeNo}）`).join('、') || '暂无' }
        ],
        rows: r.qualifications
      },
      {
        name: '资质信息不完整',
        title: '资质信息不完整的牙医',
        columns: [
          { header: '员工编号', key: 'employeeNo', width: 12 },
          { header: '姓名', key: 'name', width: 12 },
          { header: '问题', key: 'issue', width: 30 }
        ],
        rows: r.incomplete
      }
    ]
  })
}

const maxIntended = computed(() => Math.max(1, ...demand.value.map((d) => d.intendedCount)))
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
        <el-button :icon="Download" :disabled="!result" @click="exportResult">导出 Excel</el-button>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="page-intro">意向次数按<b>预约日期</b>统计未取消的预约，实际数量按<b>到诊日期</b>统计牙医已确认的治疗，两者口径不同，分开显示。资质只算已核实的。</div>

    <div v-loading="loading" class="grid">
      <el-card shadow="never" class="table-card">
        <template #header><span class="card-title">治疗需求</span></template>
        <el-table :data="demand" :span-method="spanMethod" border>
          <template #empty><TableEmpty :error="loadError" text="这段时间没有预约或治疗记录（不代表没有治疗需求）" @retry="load" /></template>
          <el-table-column label="诊所" prop="clinicName" width="110" />
          <el-table-column label="治疗项目" prop="itemName" min-width="120" />
          <el-table-column label="预约意向次数" min-width="170">
            <template #default="{ row }">
              <div class="bar-cell">
                <div class="bar"><div class="fill" :style="{ width: `${(row.intendedCount / maxIntended) * 100}%` }" /></div>
                <span class="num">{{ row.intendedCount }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="实际治疗数量" width="130" align="right">
            <template #default="{ row }">
              <span class="num">{{ quantity(row.actualQuantity) }}</span> <span class="muted">{{ row.unitName }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <div class="side">
        <el-card shadow="never">
          <template #header><span class="card-title">已核实的牙医资质</span></template>
          <div v-if="result" class="quals">
            <div v-for="q in result.qualifications" :key="q.qualificationId" class="qual" :class="{ empty: !q.verifiedCount }">
              <div class="qual-head">
                <span class="qual-name">{{ q.name }}</span>
                <span class="qual-count">{{ q.verifiedCount }} 位</span>
              </div>
              <div class="qual-people">
                <el-tag v-for="d in q.dentists" :key="d.employeeNo" size="small" effect="plain">{{ d.name }} {{ d.employeeNo }}</el-tag>
                <span v-if="!q.verifiedCount" class="warn-text">暂无已核实的牙医</span>
              </div>
            </div>
          </div>
        </el-card>

        <el-card v-if="result?.incomplete.length" shadow="never" class="incomplete">
          <template #header>
            <span class="card-title warn-title"><el-icon><WarningFilled /></el-icon> 资质信息不完整</span>
          </template>
          <ul class="issue-list">
            <li v-for="(i, idx) in result.incomplete" :key="idx">
              <span>{{ i.name }} <span class="muted">{{ i.employeeNo }}</span></span>
              <span class="warn-text">{{ i.issue }}</span>
            </li>
          </ul>
          <div class="muted hint">请到“牙医”页面核实后，才会计入上面的资质统计。</div>
        </el-card>
      </div>
    </div>
  </div>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(280px, 1fr);
  gap: 16px;
  align-items: start;
}
.side {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.card-title {
  font-weight: 600;
}
.bar-cell {
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
.num {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  min-width: 24px;
  text-align: right;
}
.quals {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.qual {
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
}
.qual.empty {
  background: var(--el-color-warning-light-9);
}
.qual-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 6px;
}
.qual-name {
  font-weight: 600;
}
.qual-count {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.qual-people {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.warn-text {
  color: var(--el-color-warning-dark-2);
  font-size: 13px;
}
.warn-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--el-color-warning-dark-2);
}
.issue-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.issue-list li {
  display: flex;
  justify-content: space-between;
  padding: 7px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
  font-size: 14px;
}
.hint {
  margin-top: 8px;
  font-size: 12px;
}
@media (max-width: 1100px) {
  .grid {
    grid-template-columns: 1fr;
  }
}
</style>
