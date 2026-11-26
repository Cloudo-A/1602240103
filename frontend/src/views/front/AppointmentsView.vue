<script setup>
// UC02 安排就诊预约、UC03 取消就诊预约、UC04 登记到诊（前台）
// 按日期看当天全部预约；也可以选定一位患者，查看他的全部预约（找要取消的那一条）
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, ArrowLeft, ArrowRight, CircleCheck, Warning, Clock, Promotion, Download } from '@element-plus/icons-vue'
import { appointmentApi } from '@/api/business.js'
import { exportExcel } from '@/utils/exportExcel.js'
import TableEmpty from '@/components/TableEmpty.vue'
import { options, patientApi } from '@/api/base.js'
import { useSubmit, handleChanged } from '@/composables/useSubmit.js'
import { useDirtyGuard } from '@/composables/useDirtyGuard.js'
import PatientPicker from '@/components/PatientPicker.vue'
import { APPOINTMENT_STATUS, timeSlotText } from '@shared/labels.js'
import { requestApi } from '@/api/portal.js'
import { toApiDate, toApiDateTime, timeOnly, dateTime, date } from '@shared/format.js'

const today = toApiDate()

// ---------- 下拉选项 ----------
const clinicOptions = ref([])
const dentistOptions = ref([])
const itemOptions = ref([])
const activeClinics = computed(() => clinicOptions.value.filter((c) => c.active))
const activeDentists = computed(() => dentistOptions.value.filter((d) => d.active))

async function loadOptions() {
  try {
    ;[clinicOptions.value, dentistOptions.value, itemOptions.value] = await Promise.all([options.clinics(false), options.dentists(false), options.treatmentItems(true)])
  } catch {
    /* 已统一提示 */
  }
}

// ---------- 列表 ----------
const day = ref(today)
const clinicId = ref(null)
const dentistId = ref(null)
const statusFilter = ref('')
const patientFilter = ref(null) // 选了患者时不限日期
const patientFilterInfo = ref(null)
const loading = ref(false)
const rows = ref([])

// 条件连续变化时可能同时有几次查询，只采用最后一次的结果
let loadSeq = 0
const loadError = ref('') // 加载失败的原因，用于和“没有预约”区分
async function load() {
  const mine = ++loadSeq
  loading.value = true
  loadError.value = ''
  try {
    const query = patientFilter.value ? { patientId: patientFilter.value } : { from: day.value, to: day.value }
    const result = await appointmentApi.list({ ...query, clinicId: clinicId.value, dentistId: dentistId.value })
    if (mine === loadSeq) rows.value = result
  } catch (e) {
    if (mine === loadSeq) {
      rows.value = []
      loadError.value = e.message || '请稍后重试'
    }
  } finally {
    if (mine === loadSeq) loading.value = false
  }
}
watch([day, clinicId, dentistId, patientFilter], load)
const route = useRoute()
const router = useRouter()
onMounted(async () => {
  await Promise.all([loadOptions(), load()])
  // 从工作台带来的快捷操作：打开对应弹窗后清掉地址里的参数
  const { action, arrive, patientId, requestId } = route.query
  if (patientId) {
    // 从患者档案“为他预约”进入：先按这位患者筛选，再打开新建预约
    try {
      const p = await patientApi.get(Number(patientId))
      patientFilterInfo.value = { id: p.id, accountNo: p.accountNo, name: p.name, birthDate: p.birthDate, phone: p.phone }
      patientFilter.value = p.id
    } catch {
      /* 已统一提示 */
    }
  }
  if (requestId) await openFromRequest(Number(requestId))
  else if (action === 'create') openCreate()
  else if (action === 'walkin') openWalkIn()
  else if (arrive) {
    const row = rows.value.find((r) => r.id === Number(arrive))
    if (row && canHandle(row)) openArrive(row)
  }
  if (action || arrive || patientId || requestId) router.replace({ query: {} })
})

function shiftDay(n) {
  const d = new Date(`${day.value}T00:00:00`)
  d.setDate(d.getDate() + n)
  day.value = toApiDate(d)
}

const counts = computed(() => {
  const c = { VALID: 0, PENDING_VERIFY: 0, ARRIVED: 0, CANCELLED: 0 }
  rows.value.forEach((r) => (c[r.status] += 1))
  return c
})
const shown = computed(() => (statusFilter.value ? rows.value.filter((r) => r.status === statusFilter.value) : rows.value))
const canHandle = (row) => row.status === 'VALID' || row.status === 'PENDING_VERIFY'

function rowClass({ row }) {
  return row.status === 'CANCELLED' ? 'row-inactive' : ''
}

function replaceRow(appt) {
  const i = rows.value.findIndex((r) => r.id === appt.id)
  if (i >= 0) rows.value[i] = appt
}

// ---------- 新建预约（UC02） ----------
const createOpen = ref(false)
const formRef = ref()
const form = reactive({})
const creator = useSubmit()
const guard = useDirtyGuard(() => form) // 关闭前检查有没有没保存的内容
watch(createOpen, (open) => !open && guard.clear())
const conflictText = ref('')
const rules = {
  patientId: [{ required: true, message: '请选择患者', trigger: 'change' }],
  clinicId: [{ required: true, message: '请选择诊所', trigger: 'change' }],
  dentistId: [{ required: true, message: '请选择牙医', trigger: 'change' }],
  date: [{ required: true, message: '请选择日期', trigger: 'change' }],
  time: [{ required: true, message: '请选择时间', trigger: 'change' }],
  itemIds: [{ type: 'array', required: true, min: 1, message: '请至少选择一项意向治疗', trigger: 'change' }]
}

function openCreate() {
  fromRequest.value = null
  requestPatient.value = null
  requestHint.value = ''
  Object.assign(form, {
    patientId: patientFilter.value,
    clinicId: clinicId.value && activeClinics.value.some((c) => c.id === clinicId.value) ? clinicId.value : null,
    dentistId: dentistId.value && activeDentists.value.some((d) => d.id === dentistId.value) ? dentistId.value : null,
    date: day.value >= today ? day.value : today,
    time: '',
    itemIds: []
  })
  creator.reset()
  guard.mark()
  conflictText.value = ''
  check.value = null
  createOpen.value = true
  formRef.value?.clearValidate()
}

const scheduledAt = computed(() => (form.date && form.time ? `${form.date}T${form.time}:00` : null))
const payload = () => ({
  patientId: form.patientId,
  dentistId: form.dentistId,
  clinicId: form.clinicId,
  scheduledAt: scheduledAt.value,
  itemIds: form.itemIds,
  ...(fromRequest.value ? { requestId: fromRequest.value.id } : {})
})

// ---------- 由预约申请转为预约（患者在官网、小程序登记，前台回电确认后办理） ----------
const fromRequest = ref(null)
const requestPatient = ref(null) // 按联系电话找到的患者档案，用于患者选择框回显
const requestHint = ref('')
const SLOT_START = { MORNING: '09:00', AFTERNOON: '14:00', EVENING: '17:00' }
const pickerInitial = computed(() => requestPatient.value || (patientFilter.value ? patientFilterInfo.value : null))
watch(createOpen, (open) => !open && (fromRequest.value = null))

async function openFromRequest(id) {
  let r
  try {
    r = await requestApi.get(id)
  } catch {
    return
  }
  if (r.status !== 'PENDING') {
    ElMessage.warning('这条预约申请已被处理，不能再转为预约')
    return
  }
  openCreate()
  fromRequest.value = r
  const activeItemIds = new Set(itemOptions.value.map((i) => i.id))
  Object.assign(form, {
    clinicId: activeClinics.value.some((c) => c.id === r.clinic.id) ? r.clinic.id : null,
    date: r.preferredDate >= today ? r.preferredDate : today,
    time: SLOT_START[r.timeSlot] || '',
    itemIds: r.items.map((i) => i.id).filter((i) => activeItemIds.has(i))
  })
  // 按联系电话找患者档案：电话完全一致且只有一位时直接选上
  try {
    const found = await patientApi.search({ keyword: r.contactPhone, page: 0, size: 20 })
    const same = found.items.filter((p) => p.phone === r.contactPhone)
    if (same.length === 1) {
      requestPatient.value = same[0]
      form.patientId = same[0].id
      requestHint.value = `已按电话找到患者档案 ${same[0].accountNo} ${same[0].name}，请核对是否本人。`
    } else if (same.length > 1) {
      requestHint.value = `电话 ${r.contactPhone} 对应 ${same.length} 份患者档案，请在下方选择。`
    } else {
      requestHint.value = `没有找到电话为 ${r.contactPhone} 的患者档案。首次就诊请先到“患者档案”新建档案，再回来办理。`
    }
  } catch {
    requestHint.value = ''
  }
  guard.mark()
}
const disabledPast = (d) => toApiDate(d) < today
const selectedDentist = computed(() => dentistOptions.value.find((d) => d.id === form.dentistId))

// 选好日期后，列出所选牙医和患者当天已有的预约，方便前台协调时段（报告 UC02 第 2 步）
const dayAppointments = ref([])
const dayLoading = ref(false)
let daySeq = 0
watch(
  () => (createOpen.value ? form.date : null),
  async (d) => {
    dayAppointments.value = []
    if (!d) return
    const mine = ++daySeq
    dayLoading.value = true
    try {
      const list = await appointmentApi.list({ from: d, to: d })
      if (mine === daySeq) dayAppointments.value = list.filter((a) => a.status !== 'CANCELLED')
    } catch {
      /* 参考信息，加载失败不影响新建 */
    } finally {
      if (mine === daySeq) dayLoading.value = false
    }
  }
)
const dentistDay = computed(() => dayAppointments.value.filter((a) => a.dentist.id === form.dentistId))
const patientDay = computed(() => dayAppointments.value.filter((a) => a.patient.id === form.patientId))
const isPicked = (a) => form.time && timeOnly(a.scheduledAt) === form.time

// 内容填齐后自动“只检查不保存”，提前告诉前台有没有冲突
const check = ref(null) // { ok, problems }
const checking = ref(false)
let checkTimer = null
let checkSeq = 0
watch(
  () => [form.patientId, form.dentistId, form.clinicId, scheduledAt.value, (form.itemIds || []).join(',')],
  () => {
    conflictText.value = ''
    clearTimeout(checkTimer)
    const p = payload()
    if (!createOpen.value || !p.patientId || !p.dentistId || !p.clinicId || !p.scheduledAt || !p.itemIds?.length) {
      check.value = null
      return
    }
    checkTimer = setTimeout(async () => {
      const mine = ++checkSeq
      checking.value = true
      try {
        const result = await appointmentApi.check(p)
        if (mine === checkSeq) check.value = result
      } catch {
        if (mine === checkSeq) check.value = null
      } finally {
        if (mine === checkSeq) checking.value = false
      }
    }, 350)
  }
)

async function saveCreate() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  const result = await creator.run((opts) => appointmentApi.create(payload(), opts), {
    handle: (e) => {
      if (e.code === 'CONFLICT') {
        conflictText.value = e.message
        return true
      }
      if (e.code === 'STATE_NOT_ALLOWED' && fromRequest.value) {
        ElMessage.warning(`${e.message}，预约没有保存`)
        guard.clear()
        createOpen.value = false
        return true
      }
      return false
    }
  })
  if (!result) return
  const converted = Boolean(fromRequest.value)
  createOpen.value = false
  ElMessage.success(`已为${result.patient.name}预约 ${date(result.scheduledAt)} ${timeOnly(result.scheduledAt)}，${result.dentist.name}医生${converted ? '；预约申请已确认，患者可在“我的预约”中看到' : ''}`)
  if (!patientFilter.value) day.value = date(result.scheduledAt)
  if (day.value === date(result.scheduledAt) || patientFilter.value) load()
}

// ---------- 取消预约（UC03） ----------
const busyId = ref(null)
const canceller = useSubmit()

async function cancel(row) {
  try {
    await ElMessageBox.confirm(
      `取消${row.patient.name}在 ${date(row.scheduledAt)} ${timeOnly(row.scheduledAt)} 的预约（${row.dentist.name}医生，${row.clinic.name}）？取消后预约记录会保留，并计入取消统计。`,
      '取消预约',
      { type: 'warning', confirmButtonText: '确认取消', cancelButtonText: '返回', confirmButtonClass: 'el-button--danger' }
    )
  } catch {
    return
  }
  busyId.value = row.id
  canceller.reset()
  const result = await canceller.run((opts) => appointmentApi.cancel(row.id, row.version, opts), {
    handle: (e) => {
      if (e.code === 'STATE_NOT_ALLOWED') {
        ElMessage.warning(e.message)
        load()
        return true
      }
      return handleChanged(e, replaceRow)
    }
  })
  busyId.value = null
  if (result) {
    replaceRow(result)
    ElMessage.success('预约已取消')
  }
}

// ---------- 登记到诊（UC04） ----------
const arriveOpen = ref(false)
const arriving = ref(null)
const arriveFormRef = ref()
const arriveForm = reactive({})
const arriver = useSubmit()
const arriveRules = {
  arrivedAt: [{ required: true, message: '请填写到诊时间', trigger: 'change' }],
  dentistId: [{ required: true, message: '请选择接诊牙医', trigger: 'change' }],
  clinicId: [{ required: true, message: '请选择就诊诊所', trigger: 'change' }]
}

function openArrive(row) {
  arriving.value = row
  Object.assign(arriveForm, { arrivedAt: toApiDateTime(new Date()), dentistId: row.dentist.id, clinicId: row.clinic.id })
  arriver.reset()
  arriveOpen.value = true
  arriveFormRef.value?.clearValidate()
}

const dentistChanged = computed(() => arriving.value && arriveForm.dentistId !== arriving.value.dentist.id)
const clinicChanged = computed(() => arriving.value && arriveForm.clinicId !== arriving.value.clinic.id)
const disabledFutureDay = (d) => toApiDate(d) > today

async function saveArrive() {
  const valid = await arriveFormRef.value.validate().catch(() => false)
  if (!valid) return
  const row = arriving.value
  const body = {
    arrivedAt: arriveForm.arrivedAt,
    // 与预约相同时传 null，表示沿用预约上的牙医、诊所
    dentistId: dentistChanged.value ? arriveForm.dentistId : null,
    clinicId: clinicChanged.value ? arriveForm.clinicId : null,
    version: row.version
  }
  const result = await arriver.run((opts) => appointmentApi.arrive(row.id, body, opts), {
    handle: (e) => {
      if (e.code === 'STATE_NOT_ALLOWED') {
        ElMessage.warning(e.message)
        arriveOpen.value = false
        load()
        return true
      }
      return handleChanged(e, (current) => {
        replaceRow(current)
        arriving.value = current
      })
    }
  })
  if (!result) return
  replaceRow(result.appointment)
  arriveOpen.value = false
  const dentistName = dentistOptions.value.find((d) => d.id === arriveForm.dentistId)?.name || row.dentist.name
  ElMessage.success(`${row.patient.name}已登记到诊，请${dentistName}医生接诊`)
}

// ---------- 现场就诊（无预约直接来诊：以当前时间建立预约后立即登记到诊） ----------
const walkOpen = ref(false)
const walkFormRef = ref()
const walkForm = reactive({})
const walkCreator = useSubmit()
const walkArriver = useSubmit()
const walkConflict = ref('')
const walkGuard = useDirtyGuard(() => walkForm)
watch(walkOpen, (open) => !open && walkGuard.clear())
const walkRules = {
  patientId: [{ required: true, message: '请选择患者', trigger: 'change' }],
  clinicId: [{ required: true, message: '请选择诊所', trigger: 'change' }],
  dentistId: [{ required: true, message: '请选择接诊牙医', trigger: 'change' }],
  itemIds: [{ type: 'array', required: true, min: 1, message: '请至少选择一项治疗', trigger: 'change' }]
}
const walking = computed(() => walkCreator.saving.value || walkArriver.saving.value)

function openWalkIn() {
  Object.assign(walkForm, {
    patientId: patientFilter.value,
    clinicId: clinicId.value && activeClinics.value.some((c) => c.id === clinicId.value) ? clinicId.value : null,
    dentistId: dentistId.value && activeDentists.value.some((d) => d.id === dentistId.value) ? dentistId.value : null,
    itemIds: []
  })
  walkCreator.reset()
  walkArriver.reset()
  walkConflict.value = ''
  walkGuard.mark()
  walkOpen.value = true
  walkFormRef.value?.clearValidate()
}

async function saveWalkIn() {
  const valid = await walkFormRef.value.validate().catch(() => false)
  if (!valid) return
  walkConflict.value = ''
  const nowTime = new Date()
  const appt = await walkCreator.run(
    (opts) =>
      appointmentApi.create(
        { patientId: walkForm.patientId, dentistId: walkForm.dentistId, clinicId: walkForm.clinicId, scheduledAt: `${toApiDateTime(nowTime).slice(0, 16)}:00`, itemIds: walkForm.itemIds },
        opts
      ),
    {
      handle: (e) => {
        if (e.code === 'CONFLICT') {
          // 后端会写明是患者还是哪位牙医冲突（协作约定 9.6）
          walkConflict.value = `${e.message}。如是牙医此刻已有安排，可换一位牙医，或稍后再登记。`
          return true
        }
        return false
      }
    }
  )
  if (!appt) return
  const arrived = await walkArriver.run((opts) => appointmentApi.arrive(appt.id, { arrivedAt: toApiDateTime(new Date()), dentistId: null, clinicId: null, version: appt.version }, opts))
  walkOpen.value = false
  if (arrived) {
    ElMessage.success(`${appt.patient.name}已登记现场就诊，请${appt.dentist.name}医生接诊`)
  } else {
    ElMessage.warning(`已为${appt.patient.name}建立 ${timeOnly(appt.scheduledAt)} 的预约，但登记到诊没有成功，请在列表中点“到诊”再试一次`)
  }
  if (!patientFilter.value) day.value = toApiDate()
  load()
}

function exportList() {
  const scope = patientFilter.value ? `患者：${patientFilterInfo.value?.name || ''}（${patientFilterInfo.value?.accountNo || ''}）的全部预约` : `日期：${day.value}`
  const filters = [clinicId.value && `诊所：${clinicOptions.value.find((c) => c.id === clinicId.value)?.name}`, dentistId.value && `牙医：${dentistOptions.value.find((d) => d.id === dentistId.value)?.name}`, statusFilter.value && `状态：${APPOINTMENT_STATUS[statusFilter.value].text}`].filter(Boolean)
  exportExcel({
    filename: patientFilter.value ? `预约记录_${patientFilterInfo.value?.accountNo || ''}` : `预约列表_${day.value}`,
    sheets: [{
      name: '预约',
      title: '预约列表',
      notes: [[scope, ...filters].join('　')],
      columns: [
        { header: '预约时间', key: 'scheduledAt', width: 18, value: (a) => dateTime(a.scheduledAt) },
        { header: '患者账号', key: 'accountNo', width: 12, value: (a) => a.patient.accountNo },
        { header: '患者', key: 'patient', width: 10, value: (a) => a.patient.name },
        { header: '牙医', key: 'dentist', width: 10, value: (a) => a.dentist.name },
        { header: '诊所', key: 'clinic', width: 12, value: (a) => a.clinic.name },
        { header: '意向治疗', key: 'items', width: 24, value: (a) => a.items.map((i) => i.name).join('、') },
        { header: '状态', key: 'status', width: 10, value: (a) => APPOINTMENT_STATUS[a.status].text },
        { header: '取消经办', key: 'cancel', width: 26, value: (a) => (a.cancelledAt ? `${a.cancelledBy} ${dateTime(a.cancelledAt)}` : '') }
      ],
      rows: shown.value
    }]
  })
}

function onPatientFilter(p) {
  patientFilterInfo.value = p
}
</script>

<template>
  <div class="page">
    <div class="page-toolbar">
      <div class="toolbar-left">
        <div v-if="!patientFilter" class="day-nav">
          <el-button :icon="ArrowLeft" @click="shiftDay(-1)" />
          <el-date-picker v-model="day" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width: 150px" />
          <el-button :icon="ArrowRight" @click="shiftDay(1)" />
          <el-button v-if="day !== today" link type="primary" @click="day = today">回到今天</el-button>
        </div>
        <el-select v-model="clinicId" clearable placeholder="全部诊所" style="width: 130px">
          <el-option v-for="c in clinicOptions" :key="c.id" :value="c.id" :label="c.name" />
        </el-select>
        <el-select v-model="dentistId" clearable filterable placeholder="全部牙医" style="width: 130px">
          <el-option v-for="d in dentistOptions" :key="d.id" :value="d.id" :label="d.name" />
        </el-select>
        <PatientPicker v-model="patientFilter" placeholder="按患者查找全部预约" style="width: 230px" @select="onPatientFilter" />
      </div>
      <div>
        <el-button :icon="Download" :disabled="!shown.length" @click="exportList">导出 Excel</el-button>
        <el-button :icon="Promotion" @click="openWalkIn">现场就诊</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建预约</el-button>
      </div>
    </div>

    <div class="stat-chips">
      <span class="stat-chip">{{ patientFilter ? `${patientFilterInfo?.name || '该患者'}的全部预约` : day === today ? '今天' : day }} 共 <b>{{ rows.length }}</b> 条</span>
      <el-radio-group v-model="statusFilter" size="small">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button v-for="(s, key) in APPOINTMENT_STATUS" :key="key" :value="key">{{ s.text }} {{ counts[key] }}</el-radio-button>
      </el-radio-group>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table v-loading="loading" :data="shown" :row-class-name="rowClass">
        <template #empty><TableEmpty :error="loadError" :text="patientFilter ? '这位患者还没有预约' : statusFilter ? '没有这个状态的预约' : '这一天没有预约'" @retry="load" /></template>
        <el-table-column label="预约时间" :width="patientFilter ? 160 : 100">
          <template #default="{ row }">
            <span class="time">{{ patientFilter ? dateTime(row.scheduledAt) : timeOnly(row.scheduledAt) }}</span>
          </template>
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
        <el-table-column label="意向治疗" min-width="170">
          <template #default="{ row }">
            <el-tag v-for="i in row.items" :key="i.id" size="small" effect="plain" class="item-tag">{{ i.name }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-tag :type="APPOINTMENT_STATUS[row.status].type" effect="light" size="small">{{ APPOINTMENT_STATUS[row.status].text }}</el-tag>
            <div v-if="row.status === 'CANCELLED'" class="cell-sub">{{ row.cancelledBy }} · {{ dateTime(row.cancelledAt) }}</div>
            <div v-else-if="row.status === 'PENDING_VERIFY'" class="cell-sub">已过预约时间未到诊</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <template v-if="canHandle(row)">
              <el-button link type="primary" :icon="CircleCheck" @click="openArrive(row)">到诊</el-button>
              <el-button link type="danger" :loading="busyId === row.id" @click="cancel(row)">取消</el-button>
            </template>
            <span v-else-if="row.status === 'ARRIVED'" class="muted">已转牙医接诊</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建预约 -->
    <el-dialog v-model="createOpen" :before-close="guard.beforeClose" :title="fromRequest ? '由预约申请转为预约' : '新建预约'" width="640px" :close-on-click-modal="false">
      <div v-if="fromRequest" class="from-request">
        <div class="fr-head">
          <b>{{ fromRequest.contactName }}</b>
          <span>{{ fromRequest.contactPhone }}</span>
          <span class="muted">提交于 {{ dateTime(fromRequest.createdAt) }}</span>
        </div>
        <div>期望：{{ fromRequest.clinic.name }} · {{ fromRequest.preferredDate }} · {{ timeSlotText(fromRequest.timeSlot) }}<template v-if="fromRequest.items.length"> · {{ fromRequest.items.map((i) => i.name).join('、') }}</template></div>
        <div v-if="fromRequest.note" class="fr-note">患者说明：{{ fromRequest.note }}</div>
        <div v-if="requestHint" class="fr-hint">{{ requestHint }}</div>
        <div class="fr-hint muted">已按患者的期望填好门店、日期和项目，时间先放在所选时段的开始，请按与患者电话确认的结果修改。</div>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
        <el-form-item label="患者" prop="patientId" :error="creator.fieldErrors.patientId">
          <PatientPicker v-model="form.patientId" :initial="pickerInitial" style="width: 100%" />
          <div class="field-hint">找不到患者时，请先到“患者档案”新建档案。</div>
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="诊所" prop="clinicId" :error="creator.fieldErrors.clinicId">
            <el-select v-model="form.clinicId" placeholder="选择诊所" style="width: 100%">
              <el-option v-for="c in activeClinics" :key="c.id" :value="c.id" :label="c.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="牙医" prop="dentistId" :error="creator.fieldErrors.dentistId">
            <el-select v-model="form.dentistId" filterable placeholder="选择牙医" style="width: 100%">
              <el-option v-for="d in activeDentists" :key="d.id" :value="d.id" :label="d.name">
                <span>{{ d.name }}</span>
                <span class="option-sub">{{ d.verifiedQualifications.join('、') || '暂无已核实资质' }}</span>
              </el-option>
            </el-select>
          </el-form-item>
          <el-form-item label="日期" prop="date" :error="creator.fieldErrors.scheduledAt">
            <el-date-picker v-model="form.date" type="date" value-format="YYYY-MM-DD" :disabled-date="disabledPast" placeholder="选择日期" style="width: 100%" />
          </el-form-item>
          <el-form-item label="时间" prop="time">
            <el-time-select v-model="form.time" start="08:00" end="20:00" step="00:15" placeholder="选择时间" style="width: 100%" />
          </el-form-item>
          <el-form-item label="意向治疗" prop="itemIds" class="span-2" :error="creator.fieldErrors.itemIds">
            <el-select v-model="form.itemIds" multiple filterable placeholder="可多选" style="width: 100%">
              <el-option v-for="i in itemOptions" :key="i.id" :value="i.id" :label="i.name" />
            </el-select>
          </el-form-item>
        </div>
        <div v-if="selectedDentist" class="field-hint dentist-hint">{{ selectedDentist.name }}医生已核实的资质：{{ selectedDentist.verifiedQualifications.join('、') || '暂无' }}</div>

        <div v-if="form.date && (form.dentistId || form.patientId)" v-loading="dayLoading" class="day-ref">
          <div class="day-ref-title">{{ form.date }} 已有的预约（供协调时段参考）</div>
          <div v-if="form.dentistId" class="day-ref-row">
            <span class="day-ref-label">{{ selectedDentist?.name }}医生</span>
            <span v-if="!dentistDay.length" class="muted">当天没有其他预约</span>
            <el-tag v-for="a in dentistDay" :key="a.id" size="small" :type="isPicked(a) ? 'danger' : 'info'" effect="plain" class="day-chip">{{ timeOnly(a.scheduledAt) }} {{ a.patient.name }} · {{ a.clinic.name }}</el-tag>
          </div>
          <div v-if="form.patientId" class="day-ref-row">
            <span class="day-ref-label">这位患者</span>
            <span v-if="!patientDay.length" class="muted">当天没有其他预约</span>
            <el-tag v-for="a in patientDay" :key="a.id" size="small" :type="isPicked(a) ? 'danger' : 'info'" effect="plain" class="day-chip">{{ timeOnly(a.scheduledAt) }} {{ a.dentist.name }} · {{ a.clinic.name }}</el-tag>
          </div>
        </div>

        <div class="check-area">
          <el-alert v-if="conflictText" type="error" :title="conflictText" description="请换一个时间或牙医后再保存。" show-icon :closable="false" />
          <div v-else-if="checking" class="checking"><el-icon class="is-loading"><Clock /></el-icon> 正在检查时间冲突…</div>
          <el-alert v-else-if="check && check.ok" type="success" title="检查通过：患者和牙医在这个时间都没有其他预约" show-icon :closable="false" />
          <el-alert v-else-if="check && !check.ok" type="warning" title="暂时不能保存" show-icon :closable="false">
            <ul class="problem-list">
              <li v-for="p in check.problems" :key="p">{{ p }}</li>
            </ul>
          </el-alert>
          <div v-else class="checking muted">填好以上内容后会自动检查时间冲突。</div>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="guard.close(() => (createOpen = false))">取消</el-button>
        <el-button type="primary" :loading="creator.saving.value" :disabled="check && !check.ok" @click="saveCreate">保存预约</el-button>
      </template>
    </el-dialog>

    <!-- 现场就诊 -->
    <el-dialog v-model="walkOpen" :before-close="walkGuard.beforeClose" title="现场就诊" width="560px" :close-on-click-modal="false">
      <p class="walk-tip">患者没有预约、直接来诊时使用：系统以当前时间（{{ timeOnly(toApiDateTime(new Date())) }}）建立预约，并立即登记到诊。</p>
      <el-form ref="walkFormRef" :model="walkForm" :rules="walkRules" label-position="top" @submit.prevent>
        <el-form-item label="患者" prop="patientId" :error="walkCreator.fieldErrors.patientId">
          <PatientPicker v-model="walkForm.patientId" :initial="patientFilter ? patientFilterInfo : null" style="width: 100%" />
          <div class="field-hint">第一次来的患者，请先到“患者档案”新建档案。</div>
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="就诊诊所" prop="clinicId" :error="walkCreator.fieldErrors.clinicId">
            <el-select v-model="walkForm.clinicId" placeholder="选择诊所" style="width: 100%">
              <el-option v-for="c in activeClinics" :key="c.id" :value="c.id" :label="c.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="接诊牙医" prop="dentistId" :error="walkCreator.fieldErrors.dentistId">
            <el-select v-model="walkForm.dentistId" filterable placeholder="选择牙医" style="width: 100%">
              <el-option v-for="d in activeDentists" :key="d.id" :value="d.id" :label="d.name">
                <span>{{ d.name }}</span>
                <span class="option-sub">{{ d.verifiedQualifications.join('、') || '暂无已核实资质' }}</span>
              </el-option>
            </el-select>
          </el-form-item>
          <el-form-item label="治疗需求" prop="itemIds" class="span-2" :error="walkCreator.fieldErrors.itemIds">
            <el-select v-model="walkForm.itemIds" multiple filterable placeholder="可多选" style="width: 100%">
              <el-option v-for="i in itemOptions" :key="i.id" :value="i.id" :label="i.name" />
            </el-select>
          </el-form-item>
        </div>
        <el-alert v-if="walkConflict" type="error" :title="walkConflict" show-icon :closable="false" />
      </el-form>
      <template #footer>
        <el-button @click="walkGuard.close(() => (walkOpen = false))">取消</el-button>
        <el-button type="primary" :loading="walking" @click="saveWalkIn">登记现场就诊</el-button>
      </template>
    </el-dialog>

    <!-- 登记到诊 -->
    <el-dialog v-model="arriveOpen" title="登记到诊" width="520px" :close-on-click-modal="false">
      <template v-if="arriving">
        <div class="arrive-head">
          <div class="arrive-name">{{ arriving.patient.name }} <span class="muted">{{ arriving.patient.accountNo }}</span></div>
          <div class="muted">预约 {{ dateTime(arriving.scheduledAt) }} · {{ arriving.dentist.name }} · {{ arriving.clinic.name }}</div>
          <div class="arrive-items">
            <el-tag v-for="i in arriving.items" :key="i.id" size="small" effect="plain" class="item-tag">{{ i.name }}</el-tag>
          </div>
        </div>
        <el-alert v-if="arriving.status === 'PENDING_VERIFY'" type="warning" :closable="false" show-icon title="已过预约时间：请确认患者确实已到诊再登记" class="arrive-alert" />
        <el-form ref="arriveFormRef" :model="arriveForm" :rules="arriveRules" label-position="top" @submit.prevent>
          <el-form-item label="到诊时间" prop="arrivedAt" :error="arriver.fieldErrors.arrivedAt">
            <el-date-picker v-model="arriveForm.arrivedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" format="YYYY-MM-DD HH:mm" :disabled-date="disabledFutureDay" style="width: 100%" />
          </el-form-item>
          <div class="form-grid">
            <el-form-item label="接诊牙医" prop="dentistId" :error="arriver.fieldErrors.dentistId">
              <el-select v-model="arriveForm.dentistId" filterable style="width: 100%">
                <el-option v-for="d in activeDentists" :key="d.id" :value="d.id" :label="d.name" />
              </el-select>
            </el-form-item>
            <el-form-item label="就诊诊所" prop="clinicId" :error="arriver.fieldErrors.clinicId">
              <el-select v-model="arriveForm.clinicId" style="width: 100%">
                <el-option v-for="c in activeClinics" :key="c.id" :value="c.id" :label="c.name" />
              </el-select>
            </el-form-item>
          </div>
          <div class="field-hint">
            <el-icon><Warning /></el-icon>
            {{ dentistChanged || clinicChanged ? '已改为与预约不同的牙医或诊所：就诊和业绩都按这里填写的记录。' : '默认按预约的牙医和诊所；临时调整时在这里修改。' }}
          </div>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="arriveOpen = false">取消</el-button>
        <el-button type="primary" :loading="arriver.saving.value" @click="saveArrive">确认到诊</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.day-nav {
  display: flex;
  align-items: center;
  gap: 6px;
}
.day-nav .el-button + .el-button {
  margin-left: 0;
}
.time {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.item-tag {
  margin: 2px 4px 2px 0;
}
.option-sub {
  float: right;
  margin-left: 16px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.field-hint {
  width: 100%;
  font-size: 12px;
  line-height: 1.6;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  display: flex;
  align-items: center;
  gap: 4px;
}
.walk-tip {
  margin: 0 0 14px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
}
.day-ref {
  margin: 0 0 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
  font-size: 13px;
}
.day-ref-title {
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.day-ref-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  line-height: 1.9;
}
.day-ref-label {
  width: 72px;
  flex: none;
  font-weight: 600;
}
.day-chip {
  margin: 0;
}
.dentist-hint {
  margin: -6px 0 12px;
}
.check-area {
  min-height: 40px;
}
.checking {
  font-size: 13px;
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--el-text-color-secondary);
  padding: 8px 0;
}
.problem-list {
  margin: 4px 0 0;
  padding-left: 18px;
}
.arrive-head {
  padding: 12px 14px;
  border-radius: 10px;
  background: var(--el-color-primary-light-9);
  margin-bottom: 16px;
  line-height: 1.7;
}
.arrive-name {
  font-size: 16px;
  font-weight: 600;
}
.arrive-items {
  margin-top: 4px;
}
.arrive-alert {
  margin-bottom: 14px;
}
.from-request {
  margin: -6px 0 16px;
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-7);
  font-size: 13px;
  line-height: 1.8;
}
.fr-head {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: baseline;
}
.fr-head b {
  font-size: 15px;
}
.fr-note {
  color: #8a5a2b;
}
.fr-hint {
  font-size: 12.5px;
}
</style>
