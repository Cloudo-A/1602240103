<script setup>
// UC09 维护牙医资料（总部）：个人资料、多项专业资质及核实时间、停用/启用
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search, Edit, Delete, CircleCheck } from '@element-plus/icons-vue'
import { dentistApi, options } from '@/api/base.js'
import TableEmpty from '@/components/TableEmpty.vue'
import { useSubmit, handleChanged } from '@/composables/useSubmit.js'
import { useDirtyGuard } from '@/composables/useDirtyGuard.js'
import { dateTime, toApiDateTime, age } from '@shared/format.js'

const loading = ref(false)
const dentists = ref([])
const qualifications = ref([])
const keyword = ref('')
const qualificationFilter = ref(null)
const onlyPending = ref(false)

const loadError = ref('') // 加载失败的原因，用于和“没有记录”区分

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [list, quals] = await Promise.all([dentistApi.list(), options.qualifications()])
    dentists.value = list
    qualifications.value = quals
  } catch (e) {
    loadError.value = e.message || '请稍后重试'
  } finally {
    loading.value = false
  }
}
onMounted(load)

const hasPending = (d) => d.qualifications.some((q) => !q.verifiedAt)

const filtered = computed(() => {
  const k = keyword.value.trim()
  return dentists.value.filter((d) => {
    if (k && !d.name.includes(k) && !d.employeeNo.toLowerCase().includes(k.toLowerCase())) return false
    if (qualificationFilter.value && !d.qualifications.some((q) => q.qualificationId === qualificationFilter.value)) return false
    if (onlyPending.value && !hasPending(d)) return false
    return true
  })
})
const pendingCount = computed(() => dentists.value.filter(hasPending).length)
const activeCount = computed(() => dentists.value.filter((d) => d.active).length)

// ---------- 新增 / 修改 ----------
const dialogOpen = ref(false)
const editing = ref(null)
const formRef = ref()
const form = reactive({ holdings: [] })
const guard = useDirtyGuard(() => form) // 关闭前检查有没有没保存的修改
watch(dialogOpen, (open) => !open && guard.clear())
const { saving, fieldErrors, run, reset } = useSubmit()

const rules = {
  employeeNo: [{ required: true, message: '请填写员工编号', trigger: 'blur' }],
  name: [{ required: true, message: '请填写姓名', trigger: 'blur' }],
  birthDate: [{ required: true, message: '请选择出生日期', trigger: 'change' }],
  homeAddress: [{ required: true, message: '请填写家庭地址', trigger: 'blur' }],
  phone: [{ required: true, message: '请填写电话', trigger: 'blur' }],
  email: [
    { required: true, message: '请填写电子邮箱', trigger: 'blur' },
    { type: 'email', message: '电子邮箱格式不正确', trigger: 'blur' }
  ]
}

function fillForm(d) {
  Object.assign(form, {
    employeeNo: d?.employeeNo ?? '',
    name: d?.name ?? '',
    birthDate: d?.birthDate ?? '',
    homeAddress: d?.homeAddress ?? '',
    phone: d?.phone ?? '',
    email: d?.email ?? '',
    active: d?.active ?? true,
    version: d?.version ?? null,
    holdings: (d?.qualifications ?? []).map((q) => ({ qualificationId: q.qualificationId, verifiedAt: q.verifiedAt }))
  })
  guard.mark()
}

function openCreate() {
  editing.value = null
  fillForm(null)
  reset()
  dialogOpen.value = true
}
function openEdit(d) {
  editing.value = d
  fillForm(d)
  reset()
  dialogOpen.value = true
}

function addHolding() {
  form.holdings.push({ qualificationId: null, verifiedAt: null })
}
function removeHolding(i) {
  form.holdings.splice(i, 1)
}
function verifyNow(h) {
  h.verifiedAt = toApiDateTime(new Date())
}
const usedIds = computed(() => new Set(form.holdings.map((h) => h.qualificationId)))

function replaceInList(d) {
  const i = dentists.value.findIndex((x) => x.id === d.id)
  if (i >= 0) dentists.value[i] = d
  else dentists.value.push(d)
}

async function save() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  if (form.holdings.some((h) => !h.qualificationId)) {
    ElMessage.warning('有一行资质还没有选择，请选择或删除该行')
    return
  }
  const { holdings, version, active, ...fields } = form
  const payload = { ...fields, qualifications: holdings.map((h) => ({ qualificationId: h.qualificationId, verifiedAt: h.verifiedAt || null })) }
  const result = await run(
    (opts) => (editing.value ? dentistApi.update(editing.value.id, { ...payload, active, version }, opts) : dentistApi.create(payload, opts)),
    {
      handle: (e) => {
        if (e.code === 'CONFLICT') {
          fieldErrors.employeeNo = '这个员工编号已被使用'
          return true
        }
        return handleChanged(e, (current) => {
          replaceInList(current)
          editing.value = current
          fillForm(current)
        })
      }
    }
  )
  if (!result) return
  replaceInList(result)
  dialogOpen.value = false
  ElMessage.success(editing.value ? '牙医资料已保存' : `已新增牙医 ${result.employeeNo} ${result.name}`)
}

// ---------- 新增资质种类 ----------
const qualSubmit = useSubmit()
async function createQualification() {
  let name
  try {
    ;({ value: name } = await ElMessageBox.prompt('请输入新的专业资质名称，例如“口腔修复”。', '新增资质种类', {
      confirmButtonText: '新增',
      cancelButtonText: '取消',
      inputPattern: /\S/,
      inputErrorMessage: '名称不能为空'
    }))
  } catch {
    return
  }
  qualSubmit.reset()
  const q = await qualSubmit.run((opts) => dentistApi.createQualification(name.trim(), opts))
  if (q) {
    qualifications.value.push(q)
    ElMessage.success(`已新增资质“${q.name}”`)
  }
}

// ---------- 停用 / 启用 ----------
const toggling = ref(null)
const toggler = useSubmit()
async function toggleActive(d) {
  const stopping = d.active
  try {
    await ElMessageBox.confirm(
      stopping ? `停用后，${d.name}（${d.employeeNo}）不能再被安排新的预约，历史就诊和业绩记录都会保留。确定停用吗？` : `确定重新启用 ${d.name}（${d.employeeNo}）吗？`,
      stopping ? '停用牙医' : '启用牙医',
      { type: stopping ? 'warning' : 'info', confirmButtonText: stopping ? '停用' : '启用', cancelButtonText: '取消', confirmButtonClass: stopping ? 'el-button--danger' : '' }
    )
  } catch {
    return
  }
  toggling.value = d.id
  toggler.reset()
  const { id, qualifications: quals, ...rest } = d
  const payload = { ...rest, active: !stopping, qualifications: quals.map((q) => ({ qualificationId: q.qualificationId, verifiedAt: q.verifiedAt })) }
  const result = await toggler.run((opts) => dentistApi.update(id, payload, opts), { handle: (e) => handleChanged(e, replaceInList) })
  toggling.value = null
  if (result) {
    replaceInList(result)
    ElMessage.success(stopping ? '已停用' : '已启用')
  }
}

function rowClass({ row }) {
  return row.active ? '' : 'row-inactive'
}
</script>

<template>
  <div class="page">
    <div class="page-toolbar">
      <div class="toolbar-left">
        <el-input v-model="keyword" :prefix-icon="Search" placeholder="按姓名或员工编号查找" clearable style="width: 220px" />
        <el-select v-model="qualificationFilter" placeholder="按资质筛选" clearable style="width: 160px">
          <el-option v-for="q in qualifications" :key="q.id" :label="q.name" :value="q.id" />
        </el-select>
        <el-checkbox v-model="onlyPending">只看有待核实资质的</el-checkbox>
      </div>
      <div class="toolbar-left">
        <el-button @click="createQualification">新增资质种类</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新增牙医</el-button>
      </div>
    </div>

    <div class="stat-chips">
      <span class="stat-chip">在职 <b>{{ activeCount }}</b> 位</span>
      <span class="stat-chip">资质待核实 <b :style="{ color: pendingCount ? 'var(--el-color-warning)' : '' }">{{ pendingCount }}</b> 位</span>
      <span class="stat-chip">资质种类 <b>{{ qualifications.length }}</b> 种</span>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table v-loading="loading" :data="filtered" :row-class-name="rowClass">
        <template #empty><TableEmpty :error="loadError" text="没有符合条件的牙医" @retry="load" /></template>
        <el-table-column label="牙医" min-width="150">
          <template #default="{ row }">
            <div>{{ row.name }} <span class="cell-sub">{{ row.employeeNo }}</span></div>
            <div class="cell-sub">{{ row.birthDate }}（{{ age(row.birthDate) }} 岁）</div>
          </template>
        </el-table-column>
        <el-table-column label="专业资质" min-width="260">
          <template #default="{ row }">
            <div class="qual-tags">
              <el-tooltip v-for="q in row.qualifications" :key="q.qualificationId" :content="q.verifiedAt ? `核实时间：${dateTime(q.verifiedAt)}` : '尚未核实，不计入已核实资质'" placement="top">
                <el-tag :type="q.verifiedAt ? 'primary' : 'warning'" effect="light" size="small">
                  {{ q.name }}<span v-if="!q.verifiedAt" class="pending-mark">待核实</span>
                </el-tag>
              </el-tooltip>
              <span v-if="!row.qualifications.length" class="muted">未登记资质</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="联系方式" min-width="180">
          <template #default="{ row }">
            <div>{{ row.phone }}</div>
            <div class="cell-sub">{{ row.email }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.active ? 'success' : 'info'" size="small">{{ row.active ? '在职' : '已停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="Edit" @click="openEdit(row)">修改</el-button>
            <el-button link :type="row.active ? 'danger' : 'success'" :loading="toggling === row.id" @click="toggleActive(row)">{{ row.active ? '停用' : '启用' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogOpen" :before-close="guard.beforeClose" :title="editing ? `修改牙医 · ${editing.name}` : '新增牙医'" width="720px" :close-on-click-modal="false" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
        <div class="form-section-title">个人资料</div>
        <div class="form-grid">
          <el-form-item label="员工编号" prop="employeeNo" :error="fieldErrors.employeeNo">
            <el-input v-model="form.employeeNo" maxlength="30" placeholder="例如：D005" />
          </el-form-item>
          <el-form-item label="姓名" prop="name" :error="fieldErrors.name">
            <el-input v-model="form.name" maxlength="60" />
          </el-form-item>
          <el-form-item label="出生日期" prop="birthDate" :error="fieldErrors.birthDate">
            <el-date-picker v-model="form.birthDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width: 100%" />
          </el-form-item>
          <el-form-item label="电话" prop="phone" :error="fieldErrors.phone">
            <el-input v-model="form.phone" maxlength="30" />
          </el-form-item>
          <el-form-item label="家庭地址" prop="homeAddress" class="span-2" :error="fieldErrors.homeAddress">
            <el-input v-model="form.homeAddress" maxlength="255" />
          </el-form-item>
          <el-form-item label="电子邮箱" prop="email" :error="fieldErrors.email">
            <el-input v-model="form.email" maxlength="254" />
          </el-form-item>
          <el-form-item v-if="editing" label="使用状态" :error="fieldErrors.active">
            <el-switch v-model="form.active" active-text="在职" inactive-text="已停用" inline-prompt style="--el-switch-off-color: #a8abb2" />
          </el-form-item>
        </div>

        <div class="form-section-title">专业资质</div>
        <p class="hint">核实时间留空表示“待核实”，待核实的资质不会计入“需求与资质对照”的已核实资质。</p>
        <el-form-item :error="fieldErrors.qualifications">
          <div class="holdings">
            <div v-for="(h, i) in form.holdings" :key="i" class="holding-row">
              <el-select v-model="h.qualificationId" placeholder="选择资质" style="width: 180px">
                <el-option v-for="q in qualifications" :key="q.id" :label="q.name" :value="q.id" :disabled="usedIds.has(q.id) && h.qualificationId !== q.id" />
              </el-select>
              <el-date-picker v-model="h.verifiedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" format="YYYY-MM-DD HH:mm" placeholder="待核实" style="width: 200px" />
              <el-button v-if="!h.verifiedAt" link type="primary" :icon="CircleCheck" @click="verifyNow(h)">现在核实</el-button>
              <el-tag v-else type="primary" size="small" effect="plain">已核实</el-tag>
              <el-button link type="danger" :icon="Delete" aria-label="删除这项资质" class="holding-del" @click="removeHolding(i)" />
            </div>
            <el-button :icon="Plus" plain size="small" @click="addHolding">添加资质</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="guard.close(() => (dialogOpen = false))">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.qual-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.pending-mark {
  margin-left: 4px;
  padding-left: 4px;
  border-left: 1px solid currentColor;
}
.hint {
  margin: -4px 0 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.holdings {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
}
.holding-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--el-fill-color-lighter);
  width: 100%;
}
.holding-del {
  margin-left: auto;
}
</style>
