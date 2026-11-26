<script setup>
// UC01 维护患者档案（前台）：查找、新建（疑似重复提示）、修改联系方式和已知过敏
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Plus, Search, Edit, Phone, Location, Message, User, Calendar, Printer } from '@element-plus/icons-vue'
import AllergyBox from '@/components/AllergyBox.vue'
import SettlementSlip from '@/components/SettlementSlip.vue'
import { appointmentApi, settlementApi } from '@/api/business.js'
import { APPOINTMENT_STATUS } from '@shared/labels.js'
import TableEmpty from '@/components/TableEmpty.vue'
import { patientApi, options } from '@/api/base.js'
import { useSubmit, handleChanged } from '@/composables/useSubmit.js'
import { useDirtyGuard } from '@/composables/useDirtyGuard.js'
import { age, dateTime, money, toApiDate } from '@shared/format.js'

// ---------- 查找 ----------
const keyword = ref('')
const searched = ref('') // 上一次实际查找的关键字
const loading = ref(false)
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 10

const loadError = ref('') // 查找失败的原因，用于和“没有找到”区分

async function search(toPage = 1) {
  loading.value = true
  loadError.value = ''
  page.value = toPage
  try {
    const result = await patientApi.search({ keyword: keyword.value.trim(), page: toPage - 1, size })
    rows.value = result.items
    total.value = result.total
    searched.value = keyword.value.trim()
  } catch (e) {
    rows.value = []
    total.value = 0
    loadError.value = e.message || '请稍后重试'
  } finally {
    loading.value = false
  }
}

const allergyOptions = ref([])
const route = useRoute()
const router = useRouter()
onMounted(async () => {
  search(1)
  if (route.query.action === 'create') {
    openCreate() // 从工作台“新建患者档案”进入
    router.replace({ query: {} })
  }
  try {
    allergyOptions.value = await options.allergies()
  } catch {
    /* 已统一提示 */
  }
})

// ---------- 详情 ----------
const detailOpen = ref(false)
const detail = ref(null)
const detailLoading = ref(false)

async function openDetail(row) {
  detailOpen.value = true
  detailLoading.value = true
  detailTab.value = 'profile'
  detail.value = null
  history.value = null
  try {
    detail.value = await patientApi.get(row.id)
    loadHistory(detail.value)
  } catch {
    detailOpen.value = false
  } finally {
    detailLoading.value = false
  }
}

// ---------- 就诊与费用记录：这位患者在所有诊所的预约和结算单 ----------
const detailTab = ref('profile')
const history = ref(null) // { appointments, settlements }
const historyLoading = ref(false)
const historyError = ref('')
async function loadHistory(p) {
  historyLoading.value = true
  historyError.value = ''
  try {
    const [appointments, settlements] = await Promise.all([
      appointmentApi.list({ patientId: p.id }),
      settlementApi.list({ from: '2000-01-01', to: toApiDate(), keyword: p.accountNo })
    ])
    if (detail.value?.id !== p.id) return
    history.value = {
      appointments: [...appointments].sort((a, b) => b.scheduledAt.localeCompare(a.scheduledAt)),
      // 关键字也会匹配姓名和单号，这里只保留账号完全相同的
      settlements: settlements.filter((x) => x.patient.accountNo === p.accountNo)
    }
  } catch (e) {
    historyError.value = e.message || '请稍后重试'
  } finally {
    historyLoading.value = false
  }
}
const historyStats = computed(() => {
  const h = history.value
  if (!h) return null
  const by = (st) => h.appointments.filter((a) => a.status === st).length
  return { total: h.appointments.length, arrived: by('ARRIVED'), cancelled: by('CANCELLED'), upcoming: by('VALID'), pending: by('PENDING_VERIFY'), paid: h.settlements.reduce((sum, x) => sum + Number(x.total), 0) }
})
const settlementOfVisit = (visitId) => history.value?.settlements.find((x) => x.visitId === visitId)

const slipOpen = ref(false)
const slip = ref(null)
const slipRef = ref()
function showSlip(st) {
  slip.value = st
  slipOpen.value = true
}

/** 从档案直接为这位患者安排预约 */
function bookFor(pt) {
  router.push({ path: '/front/appointments', query: { action: 'create', patientId: pt.id } })
}

// ---------- 新建 / 修改 ----------
const dialogOpen = ref(false)
const editing = ref(null) // 正在修改的患者（完整档案）
const formRef = ref()
const form = reactive({ allergyIds: [] })
const guard = useDirtyGuard(() => form) // 关闭前检查有没有没保存的修改
watch(dialogOpen, (open) => !open && guard.clear())
const { saving, fieldErrors, run, reset } = useSubmit()

const phoneRule = { pattern: /^[0-9+\-\s()]{5,30}$/, message: '电话只能包含数字、空格和 + - ( )', trigger: 'blur' }
const rules = {
  name: [{ required: true, message: '请填写姓名', trigger: 'blur' }],
  birthDate: [{ required: true, message: '请选择出生日期', trigger: 'change' }],
  phone: [{ required: true, message: '请填写电话', trigger: 'blur' }, phoneRule],
  billingAddress: [{ required: true, message: '请填写账单地址', trigger: 'blur' }],
  email: [{ type: 'email', message: '电子邮箱格式不正确', trigger: 'blur' }],
  emergencyName: [{ required: true, message: '请填写紧急联系人姓名', trigger: 'blur' }],
  emergencyPhone: [{ required: true, message: '请填写紧急联系人电话', trigger: 'blur' }, phoneRule]
}

function fillForm(p) {
  Object.assign(form, {
    name: p?.name ?? '',
    birthDate: p?.birthDate ?? '',
    billingAddress: p?.billingAddress ?? '',
    phone: p?.phone ?? '',
    email: p?.email ?? '',
    emergencyName: p?.emergencyName ?? '',
    emergencyPhone: p?.emergencyPhone ?? '',
    allergyIds: (p?.allergies ?? []).map((a) => a.id),
    version: p?.version ?? null
  })
  guard.mark()
}

function openCreate() {
  editing.value = null
  fillForm(null)
  // 查找没有结果时，把输入的姓名或电话带进新建表单
  const k = searched.value
  if (k && total.value === 0) {
    if (/^[0-9+\-\s()]+$/.test(k)) form.phone = k
    else if (!/^P\d+$/i.test(k)) form.name = k
  }
  reset()
  dialogOpen.value = true
}

function openEdit(p) {
  editing.value = p
  fillForm(p)
  reset()
  dialogOpen.value = true
}

const disabledFuture = (d) => d.getTime() > Date.now()

function payload(confirmNotDuplicate = false) {
  const { version, ...fields } = form
  const body = { ...fields, email: fields.email?.trim() || null }
  return editing.value ? { ...body, version } : { ...body, confirmNotDuplicate }
}

// 疑似重复
const dupOpen = ref(false)
const candidates = ref([])

async function save(confirmNotDuplicate = false) {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  const result = await run(
    (opts) => (editing.value ? patientApi.update(editing.value.id, payload(), opts) : patientApi.create(payload(confirmNotDuplicate), opts)),
    {
      handle: (e) => {
        if (!editing.value && e.code === 'CONFLICT' && Array.isArray(e.current)) {
          candidates.value = e.current
          dupOpen.value = true
          return true
        }
        return handleChanged(e, (current) => {
          editing.value = current
          fillForm(current)
          if (detail.value?.id === current.id) detail.value = current
        })
      }
    }
  )
  if (!result) return
  dupOpen.value = false
  dialogOpen.value = false
  ElMessage.success(editing.value ? '患者档案已保存' : `已建立档案 ${result.accountNo}（${result.name}）`)
  detail.value = result
  detailOpen.value = true
  // 列表里显示这位患者
  if (!editing.value) keyword.value = result.accountNo
  search(editing.value ? page.value : 1)
}

function useCandidate(c) {
  dupOpen.value = false
  dialogOpen.value = false
  openDetail(c)
}

</script>

<template>
  <div class="page">
    <div class="page-toolbar">
      <div class="toolbar-left">
        <el-input v-model="keyword" :prefix-icon="Search" placeholder="输入姓名、电话或账号" clearable style="width: 280px" @keyup.enter="search(1)" @clear="search(1)" />
        <el-button type="primary" plain @click="search(1)">查找</el-button>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建档案</el-button>
    </div>

    <div class="page-intro">先查找再新建：同一位患者在所有诊所共用一份档案。新建时如果发现姓名相同、出生日期或电话也相同的患者，系统会提示核对。</div>

    <el-card shadow="never" class="table-card">
      <el-table v-loading="loading" :data="rows" highlight-current-row @row-click="openDetail">
        <el-table-column label="账号" prop="accountNo" width="120" />
        <el-table-column label="姓名" prop="name" min-width="120" />
        <el-table-column label="出生日期" min-width="150">
          <template #default="{ row }">{{ row.birthDate }}<span class="cell-sub">（{{ age(row.birthDate) }} 岁）</span></template>
        </el-table-column>
        <el-table-column label="电话" prop="phone" min-width="140" />
        <el-table-column label="" width="90" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">查看</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <TableEmpty :error="loadError" :text="searched ? `没有找到与“${searched}”相符的患者` : '还没有患者档案'" @retry="search(page)">
            <el-button type="primary" link :icon="Plus" @click="openCreate">新建档案</el-button>
          </TableEmpty>
        </template>
      </el-table>
      <div v-if="total > size" class="pager">
        <el-pagination layout="total, prev, pager, next" :total="total" :page-size="size" :current-page="page" @current-change="search" />
      </div>
    </el-card>

    <!-- 档案详情 -->
    <el-drawer v-model="detailOpen" :title="detail ? `${detail.name} · ${detail.accountNo}` : '患者档案'" size="560px">
      <div v-loading="detailLoading" class="detail">
        <template v-if="detail">
          <div class="detail-head">
            <div class="avatar-lg">{{ detail.name.slice(0, 1) }}</div>
            <div>
              <div class="detail-name">{{ detail.name }}</div>
              <div class="muted">{{ detail.birthDate }}（{{ age(detail.birthDate) }} 岁）· 账号 {{ detail.accountNo }}</div>
            </div>
          </div>

          <el-tabs v-model="detailTab" class="detail-tabs">
            <el-tab-pane label="档案" name="profile">
              <AllergyBox :allergies="detail.allergies" :verified-at="detail.allergyVerifiedAt" class="detail-allergy" />
              <div class="form-section-title">联系信息</div>
              <ul class="info-list">
                <li><el-icon><Phone /></el-icon><span class="label">电话</span>{{ detail.phone }}</li>
                <li><el-icon><Message /></el-icon><span class="label">邮箱</span>{{ detail.email || '—' }}</li>
                <li><el-icon><Location /></el-icon><span class="label">账单地址</span>{{ detail.billingAddress }}</li>
                <li><el-icon><User /></el-icon><span class="label">紧急联系人</span>{{ detail.emergencyName }}（{{ detail.emergencyPhone }}）</li>
              </ul>
            </el-tab-pane>

            <el-tab-pane name="visits">
              <template #label>预约与就诊<span v-if="history" class="tab-count">{{ history.appointments.length }}</span></template>
              <div v-loading="historyLoading" class="history">
                <TableEmpty v-if="historyError" :error="historyError" @retry="loadHistory(detail)" />
                <template v-else-if="history">
                  <div v-if="historyStats" class="history-stats">
                    <span>共 <b>{{ historyStats.total }}</b> 次预约</span>
                    <span>已到诊 <b>{{ historyStats.arrived }}</b></span>
                    <span>已取消 <b>{{ historyStats.cancelled }}</b></span>
                    <span v-if="historyStats.upcoming">将来 <b>{{ historyStats.upcoming }}</b></span>
                    <span v-if="historyStats.pending" class="warn">待核实 <b>{{ historyStats.pending }}</b></span>
                  </div>
                  <TableEmpty v-if="!history.appointments.length" text="这位患者还没有预约" />
                  <ul class="hist-list">
                    <li v-for="a in history.appointments" :key="a.id" :class="{ cancelled: a.status === 'CANCELLED' }">
                      <div class="hist-top">
                        <span class="hist-time">{{ dateTime(a.scheduledAt) }}</span>
                        <el-tag :type="APPOINTMENT_STATUS[a.status].type" size="small" effect="light">{{ APPOINTMENT_STATUS[a.status].text }}</el-tag>
                      </div>
                      <div class="hist-sub">{{ a.clinic.name }} · {{ a.dentist.name }} · {{ a.items.map((i) => i.name).join('、') }}</div>
                      <div v-if="a.status === 'CANCELLED'" class="hist-sub">{{ a.cancelledBy }}于 {{ dateTime(a.cancelledAt) }} 取消</div>
                      <div v-if="settlementOfVisit(a.visitId)" class="hist-sub">
                        已开结算单
                        <el-button link type="primary" size="small" @click="showSlip(settlementOfVisit(a.visitId))">{{ settlementOfVisit(a.visitId).settlementNo }}</el-button>
                        · {{ money(settlementOfVisit(a.visitId).total) }} 元
                      </div>
                    </li>
                  </ul>
                </template>
              </div>
            </el-tab-pane>

            <el-tab-pane name="settlements">
              <template #label>结算单<span v-if="history" class="tab-count">{{ history.settlements.length }}</span></template>
              <div v-loading="historyLoading" class="history">
                <TableEmpty v-if="historyError" :error="historyError" @retry="loadHistory(detail)" />
                <template v-else-if="history">
                  <div class="history-stats"><span>共 <b>{{ history.settlements.length }}</b> 张，合计 <b class="money">{{ money(historyStats.paid) }}</b> 元</span></div>
                  <el-table :data="history.settlements" size="small" @row-click="showSlip">
                    <template #empty><TableEmpty text="还没有结算单" /></template>
                    <el-table-column label="单号" prop="settlementNo" min-width="150" />
                    <el-table-column label="开具时间" width="140">
                      <template #default="{ row }">{{ dateTime(row.issuedAt) }}</template>
                    </el-table-column>
                    <el-table-column label="诊所" width="90">
                      <template #default="{ row }">{{ row.clinic.name }}</template>
                    </el-table-column>
                    <el-table-column label="合计" width="100" align="right">
                      <template #default="{ row }"><span class="money">{{ money(row.total) }}</span></template>
                    </el-table-column>
                  </el-table>
                </template>
              </div>
            </el-tab-pane>
          </el-tabs>
        </template>
      </div>
      <template #footer>
        <template v-if="detail">
          <el-button :icon="Calendar" @click="bookFor(detail)">为他预约</el-button>
          <el-button type="primary" :icon="Edit" @click="openEdit(detail)">修改档案</el-button>
        </template>
      </template>
    </el-drawer>

    <!-- 结算单（重新打印原单） -->
    <el-dialog v-model="slipOpen" title="就诊结算单" width="680px" append-to-body>
      <SettlementSlip v-if="slip" ref="slipRef" :settlement="slip" />
      <template #footer>
        <el-button @click="slipOpen = false">关闭</el-button>
        <el-button type="primary" :icon="Printer" @click="slipRef?.print()">打印</el-button>
      </template>
    </el-dialog>

    <!-- 新建 / 修改 -->
    <el-dialog v-model="dialogOpen" :before-close="guard.beforeClose" :title="editing ? `修改档案 · ${editing.name}（${editing.accountNo}）` : '新建患者档案'" width="680px" :close-on-click-modal="false" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
        <div class="form-section-title">基本信息</div>
        <div class="form-grid">
          <el-form-item label="姓名" prop="name" :error="fieldErrors.name">
            <el-input v-model="form.name" maxlength="60" />
          </el-form-item>
          <el-form-item label="出生日期" prop="birthDate" :error="fieldErrors.birthDate">
            <el-date-picker v-model="form.birthDate" type="date" value-format="YYYY-MM-DD" :disabled-date="disabledFuture" placeholder="选择日期" style="width: 100%" />
          </el-form-item>
          <el-form-item label="电话" prop="phone" :error="fieldErrors.phone">
            <el-input v-model="form.phone" maxlength="30" />
          </el-form-item>
          <el-form-item label="电子邮箱（选填）" prop="email" :error="fieldErrors.email">
            <el-input v-model="form.email" maxlength="254" />
          </el-form-item>
          <el-form-item label="账单地址" prop="billingAddress" class="span-2" :error="fieldErrors.billingAddress">
            <el-input v-model="form.billingAddress" maxlength="255" />
          </el-form-item>
        </div>
        <div class="form-section-title">紧急联系人</div>
        <div class="form-grid">
          <el-form-item label="姓名" prop="emergencyName" :error="fieldErrors.emergencyName">
            <el-input v-model="form.emergencyName" maxlength="60" />
          </el-form-item>
          <el-form-item label="电话" prop="emergencyPhone" :error="fieldErrors.emergencyPhone">
            <el-input v-model="form.emergencyPhone" maxlength="30" />
          </el-form-item>
        </div>
        <div class="form-section-title">已知过敏</div>
        <el-form-item :error="fieldErrors.allergyIds">
          <el-checkbox-group v-model="form.allergyIds">
            <el-checkbox v-for="a in allergyOptions" :key="a.id" :value="a.id" border>{{ a.name }}</el-checkbox>
          </el-checkbox-group>
          <div class="field-hint">登记患者自述的过敏。前台登记的内容会显示为“尚未核实”，由牙医在诊疗时核实。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="guard.close(() => (dialogOpen = false))">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save(false)">保存</el-button>
      </template>
    </el-dialog>

    <!-- 疑似重复 -->
    <el-dialog v-model="dupOpen" title="可能已有这位患者的档案" width="560px" append-to-body>
      <p class="dup-tip">以下患者与“{{ form.name }}”姓名相同，出生日期或电话也相同。请向患者核对，避免同一个人建两份档案。</p>
      <el-table :data="candidates" size="small" border>
        <el-table-column label="账号" prop="accountNo" width="100" />
        <el-table-column label="姓名" prop="name" width="80" />
        <el-table-column label="出生日期" min-width="100">
          <template #default="{ row }"><span :class="{ same: row.birthDate === form.birthDate }">{{ row.birthDate }}</span></template>
        </el-table-column>
        <el-table-column label="电话" min-width="110">
          <template #default="{ row }"><span :class="{ same: row.phone === form.phone }">{{ row.phone }}</span></template>
        </el-table-column>
        <el-table-column width="96" align="center">
          <template #default="{ row }"><el-button link type="primary" @click="useCandidate(row)">是这一位</el-button></template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="dupOpen = false">返回修改</el-button>
        <el-button type="warning" :loading="saving" @click="save(true)">不是同一人，继续新建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.empty {
  padding: 24px 0;
  line-height: 2;
  color: var(--el-text-color-secondary);
}
.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 16px;
  border-top: 1px solid var(--el-border-color-lighter);
}
:deep(.el-table__row) {
  cursor: pointer;
}
.detail {
  min-height: 200px;
}
.detail-head {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 18px;
}
.avatar-lg {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 20px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}
.detail-name {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 2px;
}
.detail-tabs :deep(.el-tabs__header) {
  margin-bottom: 14px;
}
.tab-count {
  margin-left: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.history {
  min-height: 120px;
}
.history-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 10px;
}
.history-stats b {
  color: var(--el-text-color-primary);
}
.history-stats .warn,
.history-stats .warn b {
  color: var(--el-color-warning-dark-2);
}
.hist-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.hist-list li {
  padding: 10px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.hist-list li.cancelled .hist-time {
  color: var(--el-text-color-secondary);
  text-decoration: line-through;
}
.hist-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.hist-time {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.hist-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.8;
}
.detail-allergy {
  margin-bottom: 20px;
}
.info-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.info-list li {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 9px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
  font-size: 14px;
  line-height: 1.5;
}
.info-list .el-icon {
  margin-top: 3px;
  color: var(--el-text-color-secondary);
}
.info-list .label {
  width: 72px;
  flex: none;
  color: var(--el-text-color-secondary);
}
.field-hint {
  width: 100%;
  font-size: 12px;
  line-height: 1.6;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
}
.dup-tip {
  margin: 0 0 12px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
}
.same {
  color: var(--el-color-danger);
  font-weight: 600;
}
</style>
