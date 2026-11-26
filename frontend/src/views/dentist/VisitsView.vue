<script setup>
// UC06 记录诊疗情况（牙医）：左边是自己接诊的就诊，右边逐项录入实际治疗、核实过敏、确认诊疗完成
// 计价只调用 price-lines（不写库），点“确认诊疗完成”时把全部治疗一次提交（协作约定 9.8）
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, ArrowRight, Plus, Delete, Document, CircleCheck, CircleClose } from '@element-plus/icons-vue'
import { visitApi } from '@/api/business.js'
import TableEmpty from '@/components/TableEmpty.vue'
import { options } from '@/api/base.js'
import { useSubmit, handleChanged } from '@/composables/useSubmit.js'
import { useDirtyGuard } from '@/composables/useDirtyGuard.js'
import AllergyBox from '@/components/AllergyBox.vue'
import MedicalRecord from '@/components/MedicalRecord.vue'
import { VISIT_STATUS } from '@shared/labels.js'
import { toApiDate, timeOnly, dateTime, money, quantity, scaleText } from '@shared/format.js'

const today = toApiDate()
const route = useRoute()
const router = useRouter()

// ---------- 选项 ----------
const allItems = ref([]) // 含停用的，用于显示已有治疗的单位和精度
const allergyOptions = ref([])
const activeItems = computed(() => allItems.value.filter((i) => i.active))
const itemOf = (id) => allItems.value.find((i) => i.id === id)

// ---------- 左侧：就诊列表 ----------
const day = ref(today)
const listLoading = ref(false)
const visits = ref([])
const selectedId = ref(null)

let listSeq = 0
const listError = ref('')
async function loadList({ keepSelection = false, prefer = null } = {}) {
  const mine = ++listSeq
  listLoading.value = true
  listError.value = ''
  try {
    const result = await visitApi.list({ from: day.value, to: day.value })
    if (mine !== listSeq) return
    visits.value = result
    if (!keepSelection || !visits.value.some((v) => v.id === selectedId.value)) {
      const first = visits.value.find((v) => v.id === prefer) || visits.value.find((v) => v.status === 'UNCONFIRMED') || visits.value[0]
      select(first ? first.id : null, { force: true })
    }
  } catch (e) {
    if (mine === listSeq) {
      visits.value = []
      listError.value = e.message || '请稍后重试'
    }
  } finally {
    if (mine === listSeq) listLoading.value = false
  }
}
watch(day, () => loadList())

onMounted(async () => {
  try {
    ;[allItems.value, allergyOptions.value] = await Promise.all([options.treatmentItems(false), options.allergies()])
  } catch {
    /* 已统一提示 */
  }
  // 从工作台“去记录”进入时，直接选中那位患者
  const prefer = route.query.visitId ? Number(route.query.visitId) : null
  loadList({ prefer })
  if (prefer) router.replace({ query: {} })
})

function shiftDay(n) {
  const d = new Date(`${day.value}T00:00:00`)
  d.setDate(d.getDate() + n)
  day.value = toApiDate(d)
}
const pendingCount = computed(() => visits.value.filter((v) => v.status === 'UNCONFIRMED').length)

// ---------- 右侧：就诊详情与诊疗记录 ----------
const visit = ref(null)
const detailLoading = ref(false)
const form = reactive({ problem: '', specialNeeds: '', relatedHistory: '', allergyIds: [], treatments: [] })
let snapshot = ''
// 切换菜单、刷新或关闭页面时，如有没确认的修改先提醒（切换患者另有提醒）
const guard = useDirtyGuard(() => (editable.value ? form : null), { routeLeave: true })
const editable = computed(() => visit.value && (visit.value.status === 'UNCONFIRMED' || visit.value.status === 'CONFIRMED'))
const dirty = computed(() => editable.value && JSON.stringify(form) !== snapshot)

function fillForm(v) {
  Object.assign(form, {
    problem: v.problem || '',
    specialNeeds: v.specialNeeds || '',
    relatedHistory: v.relatedHistory || '',
    allergyIds: v.patient.allergies.map((a) => a.id),
    treatments: v.treatments.map((t) => ({ itemId: t.itemId, quantity: Number(t.quantity) }))
  })
  snapshot = JSON.stringify(form)
  guard.mark()
  confirmer.reset()
  lines.value = v.treatments
  priceProblems.value = []
  newItem.itemId = null
  newItem.quantity = 1
}

function showVisit(v) {
  visit.value = v
  fillForm(v)
  const i = visits.value.findIndex((x) => x.id === v.id)
  if (i >= 0) visits.value[i] = { ...visits.value[i], status: v.status, total: v.total }
}

async function select(id, { force = false } = {}) {
  if (id === selectedId.value && !force) return
  if (!force && dirty.value) {
    try {
      await ElMessageBox.confirm('当前就诊还有没确认的修改，切换后这些修改会丢失。确定切换吗？', '还没保存', { type: 'warning', confirmButtonText: '切换', cancelButtonText: '留在这里' })
    } catch {
      return
    }
  }
  selectedId.value = id
  visit.value = null
  if (!id) return
  detailLoading.value = true
  try {
    const v = await visitApi.get(id)
    if (selectedId.value === id) showVisit(v)
  } catch {
    /* 已统一提示 */
  } finally {
    detailLoading.value = false
  }
}

// ---------- 实际治疗：逐项添加，后端计价 ----------
const newItem = reactive({ itemId: null, quantity: 1 })
const newItemInfo = computed(() => itemOf(newItem.itemId))
const usedItemIds = computed(() => new Set(form.treatments.map((t) => t.itemId)))
const lines = ref([]) // 后端返回的计价行
const priceProblems = ref([])
const pricing = ref(false)

function addTreatment() {
  if (!newItem.itemId || !(newItem.quantity > 0)) return
  form.treatments.push({ itemId: newItem.itemId, quantity: newItem.quantity })
  newItem.itemId = null
  newItem.quantity = 1
}
function removeTreatment(i) {
  form.treatments.splice(i, 1)
}

let priceTimer = null
let priceSeq = 0
watch(
  () => JSON.stringify(form.treatments),
  () => {
    if (!editable.value) return
    clearTimeout(priceTimer)
    if (!form.treatments.length) {
      lines.value = []
      priceProblems.value = []
      return
    }
    priceTimer = setTimeout(async () => {
      const mine = ++priceSeq
      pricing.value = true
      try {
        const result = await visitApi.priceLines(visit.value.id, form.treatments)
        if (mine === priceSeq) {
          lines.value = result.lines
          priceProblems.value = result.problems
        }
      } catch (e) {
        if (mine === priceSeq) priceProblems.value = [e.message]
      } finally {
        if (mine === priceSeq) pricing.value = false
      }
    }, 250)
  }
)

const lineOf = (itemId) => lines.value.find((l) => l.itemId === itemId)
const total = computed(() => form.treatments.reduce((s, t) => s + Number(lineOf(t.itemId)?.subtotal || 0), 0))
// 已确认的就诊只有改动过才需要“更新诊疗记录”
const canConfirm = computed(
  () => form.treatments.length > 0 && !priceProblems.value.length && !pricing.value && form.treatments.every((t) => lineOf(t.itemId)) && (visit.value?.status !== 'CONFIRMED' || dirty.value)
)

// ---------- 确认诊疗完成 ----------
const confirmer = useSubmit()

function applyChanged(current) {
  showVisit(current)
}

async function confirmVisit() {
  if (!form.problem.trim()) {
    confirmer.fieldErrors.problem = '请填写患者问题描述'
    return
  }
  const again = visit.value.status === 'CONFIRMED'
  try {
    await ElMessageBox.confirm(
      `${again ? '将用这次填写的内容替换原有诊疗记录。' : ''}本次实际治疗 ${form.treatments.length} 项，合计 ${money(total.value)} 元；患者过敏情况将记为已由您核实。确认后前台即可开具结算单。`,
      again ? '更新诊疗记录' : '确认诊疗完成',
      { type: 'info', confirmButtonText: '确认', cancelButtonText: '再看看' }
    )
  } catch {
    return
  }
  const body = {
    problem: form.problem.trim(),
    specialNeeds: form.specialNeeds.trim() || null,
    relatedHistory: form.relatedHistory.trim() || null,
    allergyIds: form.allergyIds,
    treatments: form.treatments,
    version: visit.value.version
  }
  const result = await confirmer.run((opts) => visitApi.confirm(visit.value.id, body, opts), {
    handle: (e) => {
      if (e.code === 'STATE_NOT_ALLOWED') {
        ElMessage.warning(e.message)
        select(visit.value.id, { force: true })
        return true
      }
      return handleChanged(e, applyChanged)
    }
  })
  if (!result) return
  showVisit(result)
  ElMessage.success(again ? '诊疗记录已更新' : `${result.patient.name}的诊疗已确认，合计 ${money(result.total)} 元`)
}

// ---------- 未实施治疗结束 ----------
const endOpen = ref(false)
const endForm = reactive({ reason: '', problem: '' })
const ender = useSubmit()

function openEnd() {
  Object.assign(endForm, { reason: '', problem: form.problem })
  ender.reset()
  endOpen.value = true
}

async function saveEnd() {
  if (!endForm.reason.trim()) {
    ender.fieldErrors.reason = '请填写未实施治疗的原因'
    return
  }
  const body = { reason: endForm.reason.trim(), problem: endForm.problem.trim() || null, version: visit.value.version }
  const result = await ender.run((opts) => visitApi.endWithoutTreatment(visit.value.id, body, opts), {
    handle: (e) => {
      if (e.code === 'STATE_NOT_ALLOWED') {
        ElMessage.warning(e.message)
        endOpen.value = false
        select(visit.value.id, { force: true })
        return true
      }
      return handleChanged(e, (current) => {
        endOpen.value = false
        applyChanged(current)
      })
    }
  })
  if (!result) return
  endOpen.value = false
  showVisit(result)
  ElMessage.success('已按“未实施治疗”结束本次就诊，不会产生结算单')
}

// ---------- 既往诊疗资料 ----------
const recordOpen = ref(false)

const rowError = (i, field) => confirmer.fieldErrors[`treatments[${i}].${field}`]
</script>

<template>
  <div class="page visits-page">
    <div class="layout">
      <!-- 左侧列表 -->
      <el-card shadow="never" class="list-card">
        <div class="day-nav">
          <el-button :icon="ArrowLeft" size="small" @click="shiftDay(-1)" />
          <el-date-picker v-model="day" type="date" value-format="YYYY-MM-DD" :clearable="false" size="small" style="width: 130px" />
          <el-button :icon="ArrowRight" size="small" @click="shiftDay(1)" />
          <el-button v-if="day !== today" link type="primary" size="small" @click="day = today">今天</el-button>
        </div>
        <div class="list-sum">
          {{ day === today ? '今天' : day }}接诊 <b>{{ visits.length }}</b> 位
          <span v-if="pendingCount" class="pending">· {{ pendingCount }} 位待记录</span>
        </div>
        <div v-loading="listLoading" class="visit-list">
          <button v-for="v in visits" :key="v.id" type="button" class="visit-item" :class="{ active: v.id === selectedId }" @click="select(v.id)">
            <span class="vi-time">{{ timeOnly(v.arrivedAt) }}</span>
            <span class="vi-main">
              <span class="vi-name">{{ v.patient.name }}</span>
              <span class="vi-sub">{{ v.patient.accountNo }} · {{ v.clinic.name }}</span>
            </span>
            <el-tag :type="VISIT_STATUS[v.status].type" size="small" effect="light">{{ VISIT_STATUS[v.status].text }}</el-tag>
          </button>
          <TableEmpty v-if="!listLoading && !visits.length" :error="listError" text="这一天没有接诊记录" @retry="loadList()" />
        </div>
      </el-card>

      <!-- 右侧工作区 -->
      <el-card v-loading="detailLoading" shadow="never" class="work-card">
        <el-empty v-if="!visit && !detailLoading" description="在左侧选择一位患者" :image-size="80" />
        <template v-if="visit">
          <div class="work-head">
            <div>
              <div class="wh-name">
                {{ visit.patient.name }}
                <span class="muted">{{ visit.patient.accountNo }}</span>
                <el-tag :type="VISIT_STATUS[visit.status].type" effect="light" size="small">{{ VISIT_STATUS[visit.status].text }}</el-tag>
              </div>
              <div class="muted">到诊 {{ dateTime(visit.arrivedAt) }} · {{ visit.clinic.name }} · {{ visit.dentist.name }}</div>
            </div>
            <el-button :icon="Document" @click="recordOpen = true">既往诊疗资料</el-button>
          </div>

          <div class="intended">
            <span class="muted">预约时的意向治疗：</span>
            <el-tag v-for="i in visit.intendedItems" :key="i.id" size="small" effect="plain">{{ i.name }}</el-tag>
            <span v-if="!visit.intendedItems.length" class="muted">无</span>
          </div>

          <!-- 可编辑：未确认或已确认未结算 -->
          <template v-if="editable">
            <el-alert v-if="visit.status === 'CONFIRMED'" type="info" :closable="false" show-icon class="block" :title="`已于 ${dateTime(visit.confirmedAt)} 确认。前台开具结算单之前，您仍可更正或追加治疗后重新确认。`" />

            <el-form label-position="top" class="block" @submit.prevent>
              <div class="form-section-title">本次诊疗</div>
              <el-form-item label="患者问题描述" required :error="confirmer.fieldErrors.problem">
                <el-input v-model="form.problem" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="患者主诉和检查发现" />
              </el-form-item>
              <div class="form-grid">
                <el-form-item label="特殊需求（选填）" :error="confirmer.fieldErrors.specialNeeds">
                  <el-input v-model="form.specialNeeds" type="textarea" :rows="2" maxlength="500" />
                </el-form-item>
                <el-form-item label="相关病史（选填）" :error="confirmer.fieldErrors.relatedHistory">
                  <el-input v-model="form.relatedHistory" type="textarea" :rows="2" maxlength="500" />
                </el-form-item>
              </div>

              <div class="form-section-title">核实过敏</div>
              <AllergyBox :allergies="visit.patient.allergies" :verified-at="visit.patient.allergyVerifiedAt" class="block-sm" />
              <el-form-item :error="confirmer.fieldErrors.allergyIds">
                <el-checkbox-group v-model="form.allergyIds">
                  <el-checkbox v-for="a in allergyOptions" :key="a.id" :value="a.id" border>{{ a.name }}</el-checkbox>
                </el-checkbox-group>
                <div class="field-hint">请与患者核对后勾选。确认诊疗时会一并记为“已由牙医核实”；都不勾选表示核实后无已知过敏。</div>
              </el-form-item>

              <div class="form-section-title">实际治疗</div>
              <div class="add-row">
                <el-select v-model="newItem.itemId" filterable placeholder="选择治疗项目" style="width: 220px">
                  <el-option v-for="i in activeItems" :key="i.id" :value="i.id" :label="i.name" :disabled="usedItemIds.has(i.id)">
                    <span>{{ i.name }}</span>
                    <span class="option-sub">{{ i.standardUnit }} · {{ scaleText(i.quantityScale) }}</span>
                  </el-option>
                </el-select>
                <el-input-number v-model="newItem.quantity" :min="newItemInfo ? 10 ** -newItemInfo.quantityScale : 1" :precision="newItemInfo?.quantityScale ?? 0" :step="1" controls-position="right" style="width: 130px" />
                <span class="muted unit">{{ newItemInfo?.standardUnit || '' }}</span>
                <el-button type="primary" plain :icon="Plus" :disabled="!newItem.itemId" @click="addTreatment">添加</el-button>
              </div>

              <el-table :data="form.treatments" class="treat-table" empty-text="还没有添加治疗。从上面选择项目和数量后点“添加”。" size="small">
                <el-table-column label="治疗项目" min-width="140">
                  <template #default="{ row, $index }">
                    {{ itemOf(row.itemId)?.name || lineOf(row.itemId)?.itemName }}
                    <div v-if="rowError($index, 'itemId')" class="row-error">{{ rowError($index, 'itemId') }}</div>
                  </template>
                </el-table-column>
                <el-table-column label="数量" width="170">
                  <template #default="{ row, $index }">
                    <el-input-number v-model="row.quantity" size="small" :min="10 ** -(itemOf(row.itemId)?.quantityScale ?? 0)" :precision="itemOf(row.itemId)?.quantityScale ?? 0" controls-position="right" style="width: 110px" />
                    <span class="muted unit">{{ itemOf(row.itemId)?.standardUnit }}</span>
                    <div v-if="rowError($index, 'quantity')" class="row-error">{{ rowError($index, 'quantity') }}</div>
                  </template>
                </el-table-column>
                <el-table-column label="单价" width="150" align="right">
                  <template #default="{ row }">
                    <template v-if="lineOf(row.itemId)">
                      <span class="money">{{ money(lineOf(row.itemId).unitPrice) }}</span>
                      <div v-if="lineOf(row.itemId).priceEffectiveFrom" class="cell-sub">{{ lineOf(row.itemId).priceEffectiveFrom }} 起的价格</div>
                    </template>
                    <span v-else class="muted">—</span>
                  </template>
                </el-table-column>
                <el-table-column label="小计" width="110" align="right">
                  <template #default="{ row }">
                    <span class="money">{{ lineOf(row.itemId) ? money(lineOf(row.itemId).subtotal) : '—' }}</span>
                  </template>
                </el-table-column>
                <el-table-column width="56" align="center">
                  <template #default="{ $index }">
                    <el-button link type="danger" :icon="Delete" @click="removeTreatment($index)" />
                  </template>
                </el-table-column>
              </el-table>

              <div class="treat-foot">
                <div class="treat-msgs">
                  <span v-if="pricing" class="muted">正在按到诊日期计价…</span>
                  <el-alert v-for="p in priceProblems" :key="p" :title="p" type="warning" :closable="false" show-icon class="price-problem" />
                  <div v-if="confirmer.fieldErrors.treatments" class="row-error">{{ confirmer.fieldErrors.treatments }}</div>
                </div>
                <div class="treat-total">合计 <b class="money">{{ money(total) }}</b> 元</div>
              </div>
              <div class="field-hint">单价按患者到诊日期适用的标准价格自动带出；确认时后端会重新计价并保存当时的单价。</div>
            </el-form>

            <div class="work-actions">
              <el-button v-if="visit.status === 'UNCONFIRMED'" :icon="CircleClose" @click="openEnd">未实施治疗，结束就诊</el-button>
              <el-button type="primary" :icon="CircleCheck" :loading="confirmer.saving.value" :disabled="!canConfirm" @click="confirmVisit">
                {{ visit.status === 'CONFIRMED' ? '更新诊疗记录' : '确认诊疗完成' }}
              </el-button>
            </div>
          </template>

          <!-- 只读：已结算或未实施治疗 -->
          <template v-else>
            <el-alert v-if="visit.status === 'SETTLED'" type="success" :closable="false" show-icon class="block" :title="`已开具结算单 ${visit.settlementNo}，诊疗记录不能再修改。`" />
            <el-alert v-else type="info" :closable="false" show-icon class="block" title="本次就诊已按“未实施治疗”结束，不产生结算单。" />
            <dl class="facts">
              <dt>确认时间</dt><dd>{{ dateTime(visit.confirmedAt) }}</dd>
              <dt>问题描述</dt><dd>{{ visit.problem || '—' }}</dd>
              <template v-if="visit.specialNeeds"><dt>特殊需求</dt><dd>{{ visit.specialNeeds }}</dd></template>
              <template v-if="visit.relatedHistory"><dt>相关病史</dt><dd>{{ visit.relatedHistory }}</dd></template>
              <template v-if="visit.noTreatmentReason"><dt>未治疗原因</dt><dd>{{ visit.noTreatmentReason }}</dd></template>
            </dl>
            <AllergyBox :allergies="visit.patient.allergies" :verified-at="visit.patient.allergyVerifiedAt" class="block" />
            <el-table v-if="visit.treatments.length" :data="visit.treatments" size="small" class="treat-table">
              <el-table-column label="治疗项目" prop="itemName" min-width="140" />
              <el-table-column label="数量" width="120">
                <template #default="{ row }">{{ quantity(row.quantity) }} {{ row.unitName }}</template>
              </el-table-column>
              <el-table-column label="单价" width="110" align="right">
                <template #default="{ row }"><span class="money">{{ money(row.unitPrice) }}</span></template>
              </el-table-column>
              <el-table-column label="小计" width="110" align="right">
                <template #default="{ row }"><span class="money">{{ money(row.subtotal) }}</span></template>
              </el-table-column>
            </el-table>
            <div v-if="visit.total !== null" class="treat-total readonly-total">合计 <b class="money">{{ money(visit.total) }}</b> 元</div>
          </template>
        </template>
      </el-card>
    </div>

    <!-- 既往诊疗资料 -->
    <el-drawer v-model="recordOpen" :title="visit ? `${visit.patient.name}的诊疗资料` : '诊疗资料'" size="520px">
      <MedicalRecord v-if="recordOpen && visit" :patient-id="visit.patient.id" :current-visit-id="visit.id" />
    </el-drawer>

    <!-- 未实施治疗结束 -->
    <el-dialog v-model="endOpen" title="未实施治疗，结束就诊" width="520px" :close-on-click-modal="false">
      <p class="end-tip">患者已到诊，但本次没有实施任何治疗（例如身体状况不适合治疗）。结束后不产生治疗记录和结算单，也不算作取消预约。</p>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="未实施治疗的原因" required :error="ender.fieldErrors.reason">
          <el-input v-model="endForm.reason" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="例如：患者血压偏高，建议控制后再来" />
        </el-form-item>
        <el-form-item label="患者问题描述（选填）" :error="ender.fieldErrors.problem">
          <el-input v-model="endForm.problem" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="endOpen = false">取消</el-button>
        <el-button type="warning" :loading="ender.saving.value" @click="saveEnd">确认结束</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: 300px 1fr;
  gap: 16px;
  align-items: start;
}
.list-card :deep(.el-card__body) {
  padding: 14px;
}
.day-nav {
  display: flex;
  align-items: center;
  gap: 4px;
}
.day-nav .el-button + .el-button {
  margin-left: 0;
}
.list-sum {
  margin: 12px 2px 10px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.list-sum b {
  color: var(--el-text-color-primary);
}
.pending {
  color: var(--el-color-warning-dark-2);
}
.visit-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-height: 80px;
}
.visit-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: #fff;
  font: inherit;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.visit-item:hover {
  border-color: var(--el-color-primary-light-7);
}
.visit-item.active {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
}
.vi-time {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  width: 40px;
}
.vi-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.vi-name {
  font-weight: 600;
}
.vi-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.work-card {
  min-height: 420px;
}
.work-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 12px;
}
.wh-name {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  white-space: nowrap;
  gap: 8px;
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 4px;
}
.wh-name .muted {
  font-size: 13px;
  font-weight: 400;
}
.intended {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 13px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  margin-bottom: 14px;
}
.block {
  margin-bottom: 14px;
}
.block-sm {
  margin-bottom: 10px;
}
.field-hint {
  width: 100%;
  font-size: 12px;
  line-height: 1.6;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
}
.add-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}
.unit {
  margin-left: 4px;
  font-size: 13px;
}
.option-sub {
  float: right;
  margin-left: 16px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.treat-table {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}
.row-error {
  font-size: 12px;
  color: var(--el-color-danger);
  line-height: 1.5;
}
.treat-foot {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-top: 10px;
}
.treat-msgs {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
}
.treat-total {
  font-size: 14px;
  white-space: nowrap;
}
.treat-total b {
  font-size: 20px;
  color: var(--el-color-primary);
  margin: 0 2px;
}
.readonly-total {
  text-align: right;
  margin-top: 10px;
}
.work-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 14px;
  margin-top: 6px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.facts {
  display: grid;
  grid-template-columns: 80px 1fr;
  gap: 6px 10px;
  margin: 0 0 14px;
  font-size: 14px;
  line-height: 1.6;
}
.facts dt {
  color: var(--el-text-color-secondary);
}
.facts dd {
  margin: 0;
}
.end-tip {
  margin: 0 0 14px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
}
@media (max-width: 960px) {
  .layout {
    grid-template-columns: 1fr;
  }
}
</style>
