<script setup>
// UC07 开具就诊结算单（前台）：先预览费用明细（不写库），确认后开具；也可按单号或患者查询并重新打印原单
// 预览返回 checkToken，开具时带回；预览之后诊疗记录有变化会返回 409 CHANGED（协作约定 9.9）
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search, Printer, Tickets, Refresh, Download } from '@element-plus/icons-vue'
import { visitApi, settlementApi } from '@/api/business.js'
import { exportExcel } from '@/utils/exportExcel.js'
import TableEmpty from '@/components/TableEmpty.vue'
import { useSubmit } from '@/composables/useSubmit.js'
import SettlementSlip from '@/components/SettlementSlip.vue'
import { toApiDate, dateTime, money, quantity } from '@shared/format.js'

const today = toApiDate()
const daysAgo = (n) => {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return toApiDate(d)
}
const tab = ref('pending')

// ---------- 待结算 ----------
const pendingRange = ref([daysAgo(90), today])
const pendingLoading = ref(false)
const pending = ref([])

// 条件连续变化时可能同时有几次查询，只采用最后一次的结果
let pendingSeq = 0
const pendingError = ref('')
async function loadPending() {
  const mine = ++pendingSeq
  pendingLoading.value = true
  pendingError.value = ''
  try {
    const [from, to] = pendingRange.value || [null, null]
    const result = await visitApi.list({ from, to, status: 'CONFIRMED' })
    if (mine === pendingSeq) pending.value = result
  } catch (e) {
    if (mine === pendingSeq) {
      pending.value = []
      pendingError.value = e.message || '请稍后重试'
    }
  } finally {
    if (mine === pendingSeq) pendingLoading.value = false
  }
}
watch(pendingRange, loadPending)
const pendingTotal = computed(() => pending.value.reduce((s, v) => s + Number(v.total || 0), 0))

// ---------- 预览与开具 ----------
const previewOpen = ref(false)
const previewVisit = ref(null)
const preview = ref(null)
const previewLoading = ref(false)
const previewNotice = ref('')
const issuer = useSubmit()

async function loadPreview() {
  previewLoading.value = true
  try {
    const result = await settlementApi.preview(previewVisit.value.id)
    if (result.existing) {
      // 已经开过：直接显示原单，不会重复开具
      previewOpen.value = false
      ElMessage.info(`这次就诊已开具结算单 ${result.settlement.settlementNo}，显示原单`)
      showSlip(result.settlement)
      loadPending()
      return
    }
    preview.value = result
  } catch (e) {
    previewOpen.value = false
    ElMessage.warning(e.message)
    loadPending()
  } finally {
    previewLoading.value = false
  }
}

function openPreview(v) {
  previewVisit.value = v
  preview.value = null
  previewNotice.value = ''
  issuer.reset()
  previewOpen.value = true
  loadPreview()
}

async function issue() {
  const result = await issuer.run((opts) => settlementApi.issue(previewVisit.value.id, preview.value.checkToken, opts), {
    handle: (e) => {
      if (e.code === 'CHANGED') {
        // 预览之后牙医改过诊疗记录：重新预览，请前台再核对一次
        previewNotice.value = e.message || '诊疗记录在预览后有变化，已显示最新费用明细，请重新核对'
        loadPreview()
        loadPending()
        return true
      }
      if (e.code === 'STATE_NOT_ALLOWED') {
        ElMessage.warning(e.message)
        previewOpen.value = false
        loadPending()
        return true
      }
      return false
    }
  })
  if (!result) return
  previewOpen.value = false
  ElMessage.success(`已开具结算单 ${result.settlementNo}`)
  pending.value = pending.value.filter((v) => v.id !== previewVisit.value.id)
  showSlip(result)
}

// ---------- 结算单查询 ----------
// 默认查最近 30 天：只查今天时刚打开常是空表，前台要找的多是最近开过的单
const issuedRange = ref([daysAgo(30), today])
const keyword = ref('')
const issuedLoading = ref(false)
const issued = ref([])

let issuedSeq = 0
const issuedError = ref('')
async function loadIssued() {
  const mine = ++issuedSeq
  issuedLoading.value = true
  issuedError.value = ''
  try {
    const [from, to] = issuedRange.value || [null, null]
    const result = await settlementApi.list({ from, to, keyword: keyword.value.trim() })
    if (mine === issuedSeq) issued.value = result
  } catch (e) {
    if (mine === issuedSeq) {
      issued.value = []
      issuedError.value = e.message || '请稍后重试'
    }
  } finally {
    if (mine === issuedSeq) issuedLoading.value = false
  }
}
watch(issuedRange, loadIssued)
watch(tab, (t) => (t === 'issued' ? loadIssued() : loadPending()))

function exportPending() {
  const [from, to] = pendingRange.value || []
  exportExcel({
    filename: `待开结算单_${from}至${to}`,
    sheets: [{
      name: '待开结算单',
      title: '已确认诊疗、待开结算单的就诊',
      notes: [`到诊日期：${from} 至 ${to}`],
      columns: [
        { header: '到诊时间', key: 'arrivedAt', width: 18, value: (v) => dateTime(v.arrivedAt) },
        { header: '患者账号', key: 'accountNo', width: 12, value: (v) => v.patient.accountNo },
        { header: '患者', key: 'patient', width: 10, value: (v) => v.patient.name },
        { header: '牙医', key: 'dentist', width: 10, value: (v) => v.dentist.name },
        { header: '诊所', key: 'clinic', width: 12, value: (v) => v.clinic.name },
        { header: '诊疗合计（元）', key: 'total', type: 'money', width: 14 }
      ],
      rows: pending.value,
      footer: [`共 ${pending.value.length} 次，合计 ${money(pendingTotal.value)} 元`]
    }]
  })
}

function exportIssued() {
  const [from, to] = issuedRange.value || []
  const k = keyword.value.trim()
  exportExcel({
    filename: `结算单_${from}至${to}`,
    sheets: [
      {
        name: '结算单',
        title: '就诊结算单列表',
        notes: [`开具日期：${from} 至 ${to}${k ? `　关键字：${k}` : ''}`],
        columns: [
          { header: '单号', key: 'settlementNo', width: 18 },
          { header: '开具时间', key: 'issuedAt', width: 18, value: (x) => dateTime(x.issuedAt) },
          { header: '患者账号', key: 'accountNo', width: 12, value: (x) => x.patient.accountNo },
          { header: '患者', key: 'patient', width: 10, value: (x) => x.patient.name },
          { header: '诊所', key: 'clinic', width: 12, value: (x) => x.clinic.name },
          { header: '牙医', key: 'dentist', width: 10, value: (x) => x.dentist.name },
          { header: '合计（元）', key: 'total', type: 'money' },
          { header: '开具人', key: 'issuedBy', width: 10 }
        ],
        rows: issued.value,
        footer: [`共 ${issued.value.length} 张，合计 ${money(issued.value.reduce((s, x) => s + Number(x.total), 0))} 元`]
      },
      {
        name: '结算明细',
        title: '结算单明细',
        columns: [
          { header: '单号', key: 'settlementNo', width: 18 },
          { header: '患者', key: 'patient', width: 10 },
          { header: '治疗项目', key: 'itemName', width: 16 },
          { header: '数量', key: 'quantity', type: 'quantity' },
          { header: '单位', key: 'unitName', width: 8 },
          { header: '单价（元）', key: 'unitPrice', type: 'money' },
          { header: '小计（元）', key: 'subtotal', type: 'money' }
        ],
        rows: issued.value.flatMap((x) => x.lines.map((l) => ({ ...l, settlementNo: x.settlementNo, patient: x.patient.name })))
      }
    ]
  })
}

// ---------- 结算单 ----------
const slipOpen = ref(false)
const slip = ref(null)
const slipRef = ref()
function showSlip(s) {
  slip.value = s
  slipOpen.value = true
}

const route = useRoute()
const router = useRouter()
onMounted(async () => {
  // 从工作台带来的参数：tab=issued 打开结算单查询；visitId 直接预览这次就诊的费用
  if (route.query.tab === 'issued') tab.value = 'issued'
  await loadPending()
  if (route.query.visitId) {
    const v = pending.value.find((x) => x.id === Number(route.query.visitId))
    if (v) openPreview(v)
  }
  if (route.query.tab || route.query.visitId) router.replace({ query: {} })
})
</script>

<template>
  <div class="page">
    <el-tabs v-model="tab" class="tabs">
      <el-tab-pane name="pending">
        <template #label>待开结算单 <el-badge v-if="pending.length" :value="pending.length" type="warning" class="tab-badge" /></template>
        <div class="page-toolbar">
          <div class="toolbar-left">
            <span class="muted">到诊日期</span>
            <el-date-picker v-model="pendingRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width: 260px" />
            <el-button :icon="Refresh" @click="loadPending">刷新</el-button>
            <el-button :icon="Download" :disabled="!pending.length" @click="exportPending">导出 Excel</el-button>
          </div>
        </div>
        <div class="stat-chips">
          <span class="stat-chip">待开单就诊 <b>{{ pending.length }}</b> 次</span>
          <span class="stat-chip">合计 <b class="money">{{ money(pendingTotal) }}</b> 元</span>
        </div>
        <el-card shadow="never" class="table-card">
          <el-table v-loading="pendingLoading" :data="pending">
            <template #empty><TableEmpty :error="pendingError" text="这段时间没有待开结算单的就诊" @retry="loadPending" /></template>
            <el-table-column label="到诊时间" width="160">
              <template #default="{ row }">{{ dateTime(row.arrivedAt) }}</template>
            </el-table-column>
            <el-table-column label="患者" min-width="140">
              <template #default="{ row }">
                <div>{{ row.patient.name }}</div>
                <div class="cell-sub">{{ row.patient.accountNo }}</div>
              </template>
            </el-table-column>
            <el-table-column label="牙医 · 诊所" min-width="150">
              <template #default="{ row }">
                <div>{{ row.dentist.name }}</div>
                <div class="cell-sub">{{ row.clinic.name }}</div>
              </template>
            </el-table-column>
            <el-table-column label="诊疗合计" width="130" align="right">
              <template #default="{ row }"><span class="money">{{ money(row.total) }}</span> 元</template>
            </el-table-column>
            <el-table-column label="操作" width="140" align="center" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" size="small" :icon="Tickets" @click="openPreview(row)">开具结算单</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="结算单查询" name="issued">
        <div class="page-toolbar">
          <div class="toolbar-left">
            <span class="muted">开具日期</span>
            <el-date-picker v-model="issuedRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始" end-placeholder="结束" style="width: 260px" />
            <el-input v-model="keyword" :prefix-icon="Search" placeholder="单号、患者姓名或账号" clearable style="width: 220px" @keyup.enter="loadIssued" @clear="loadIssued" />
            <el-button type="primary" plain @click="loadIssued">查找</el-button>
            <el-button :icon="Download" :disabled="!issued.length" @click="exportIssued">导出 Excel</el-button>
          </div>
        </div>
        <el-card shadow="never" class="table-card">
          <el-table v-loading="issuedLoading" :data="issued" @row-click="showSlip">
            <template #empty><TableEmpty :error="issuedError" text="没有符合条件的结算单" @retry="loadIssued" /></template>
            <el-table-column label="单号" prop="settlementNo" width="170" />
            <el-table-column label="开具时间" width="160">
              <template #default="{ row }">{{ dateTime(row.issuedAt) }}</template>
            </el-table-column>
            <el-table-column label="患者" min-width="130">
              <template #default="{ row }">
                <div>{{ row.patient.name }}</div>
                <div class="cell-sub">{{ row.patient.accountNo }}</div>
              </template>
            </el-table-column>
            <el-table-column label="牙医 · 诊所" min-width="140">
              <template #default="{ row }">
                <div>{{ row.dentist.name }}</div>
                <div class="cell-sub">{{ row.clinic.name }}</div>
              </template>
            </el-table-column>
            <el-table-column label="合计" width="120" align="right">
              <template #default="{ row }"><span class="money">{{ money(row.total) }}</span> 元</template>
            </el-table-column>
            <el-table-column label="开具人" prop="issuedBy" width="90" />
            <el-table-column label="" width="90" align="center" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click.stop="showSlip(row)">查看</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 预览 -->
    <el-dialog v-model="previewOpen" title="核对费用明细" width="640px" :close-on-click-modal="false">
      <div v-loading="previewLoading" class="preview">
        <template v-if="previewVisit">
          <div class="preview-head">
            <div class="ph-name">{{ previewVisit.patient.name }} <span class="muted">{{ previewVisit.patient.accountNo }}</span></div>
            <div class="muted">到诊 {{ dateTime(previewVisit.arrivedAt) }} · {{ previewVisit.clinic.name }} · {{ previewVisit.dentist.name }}</div>
          </div>
          <el-alert v-if="previewNotice" type="warning" :title="previewNotice" show-icon :closable="false" class="notice" />
          <template v-if="preview">
            <el-table :data="preview.lines" size="small" class="lines">
              <el-table-column label="治疗项目" prop="itemName" min-width="140" />
              <el-table-column label="数量" width="110">
                <template #default="{ row }">{{ quantity(row.quantity) }} {{ row.unitName }}</template>
              </el-table-column>
              <el-table-column label="单价（元）" width="110" align="right">
                <template #default="{ row }"><span class="money">{{ money(row.unitPrice) }}</span></template>
              </el-table-column>
              <el-table-column label="小计（元）" width="110" align="right">
                <template #default="{ row }"><span class="money">{{ money(row.subtotal) }}</span></template>
              </el-table-column>
            </el-table>
            <div class="preview-total">合计 <b class="money">{{ money(preview.total) }}</b> 元</div>
            <div class="hint">金额由系统按牙医确认的治疗和就诊当日价格计算。开具后不能修改；同一次就诊只能开一张结算单。</div>
          </template>
        </template>
      </div>
      <template #footer>
        <el-button @click="previewOpen = false">取消</el-button>
        <el-button type="primary" :loading="issuer.saving.value" :disabled="!preview" @click="issue">确认开具</el-button>
      </template>
    </el-dialog>

    <!-- 结算单 -->
    <el-dialog v-model="slipOpen" title="就诊结算单" width="680px">
      <SettlementSlip v-if="slip" ref="slipRef" :settlement="slip" />
      <template #footer>
        <el-button @click="slipOpen = false">关闭</el-button>
        <el-button type="primary" :icon="Printer" @click="slipRef?.print()">打印</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}
.tab-badge {
  margin-left: 4px;
  vertical-align: middle;
}
.tab-badge :deep(.el-badge__content) {
  position: static;
  transform: none;
}
.preview {
  min-height: 120px;
}
.preview-head {
  padding: 12px 14px;
  border-radius: 10px;
  background: var(--el-color-primary-light-9);
  margin-bottom: 14px;
  line-height: 1.7;
}
.ph-name {
  font-size: 16px;
  font-weight: 600;
}
.notice {
  margin-bottom: 12px;
}
.lines {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}
.preview-total {
  text-align: right;
  margin-top: 12px;
  font-size: 14px;
}
.preview-total b {
  font-size: 22px;
  color: var(--el-color-primary);
  margin: 0 2px;
}
.hint {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
