<script setup>
// UC08 维护诊所资料（总部）：查询、新增、修改、停用/启用。不提供删除，停用后历史记录保留。
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search, Edit } from '@element-plus/icons-vue'
import { clinicApi } from '@/api/base.js'
import TableEmpty from '@/components/TableEmpty.vue'
import { useSubmit, handleChanged } from '@/composables/useSubmit.js'
import { useDirtyGuard } from '@/composables/useDirtyGuard.js'

const loading = ref(false)
const clinics = ref([])
const keyword = ref('')
const statusFilter = ref('all')

const loadError = ref('') // 加载失败的原因，用于和“没有记录”区分

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    clinics.value = await clinicApi.list()
  } catch (e) {
    loadError.value = e.message || '请稍后重试'
  } finally {
    loading.value = false
  }
}
onMounted(load)

const filtered = computed(() => {
  const k = keyword.value.trim()
  return clinics.value.filter((c) => {
    if (statusFilter.value === 'active' && !c.active) return false
    if (statusFilter.value === 'inactive' && c.active) return false
    return !k || c.name.includes(k) || c.managerName.includes(k) || c.postalAddress.includes(k)
  })
})
const activeCount = computed(() => clinics.value.filter((c) => c.active).length)
const totalArea = computed(() => clinics.value.filter((c) => c.active).reduce((s, c) => s + Number(c.officeArea), 0))

// ---------- 新增 / 修改 ----------
const dialogOpen = ref(false)
const editing = ref(null) // 正在修改的诊所；null 表示新增
const formRef = ref()
const form = reactive({})
const guard = useDirtyGuard(() => form) // 关闭前检查有没有没保存的修改
watch(dialogOpen, (open) => !open && guard.clear())
const { saving, fieldErrors, run, reset } = useSubmit()

const rules = {
  name: [{ required: true, message: '请填写诊所名称', trigger: 'blur' }],
  postalAddress: [{ required: true, message: '请填写邮政地址', trigger: 'blur' }],
  managerName: [{ required: true, message: '请填写主管姓名', trigger: 'blur' }],
  phone: [{ required: true, message: '请填写电话', trigger: 'blur' }],
  email: [
    { required: true, message: '请填写电子邮箱', trigger: 'blur' },
    { type: 'email', message: '电子邮箱格式不正确', trigger: 'blur' }
  ],
  officeArea: [{ required: true, message: '请填写办公面积', trigger: 'blur' }]
}

function fillForm(c) {
  Object.assign(form, {
    name: c?.name ?? '',
    postalAddress: c?.postalAddress ?? '',
    managerName: c?.managerName ?? '',
    phone: c?.phone ?? '',
    email: c?.email ?? '',
    officeArea: c?.officeArea ?? null,
    active: c?.active ?? true,
    version: c?.version ?? null
  })
  guard.mark()
}

function openCreate() {
  editing.value = null
  fillForm(null)
  reset()
  dialogOpen.value = true
  formRef.value?.clearValidate()
}

function openEdit(c) {
  editing.value = c
  fillForm(c)
  reset()
  dialogOpen.value = true
  formRef.value?.clearValidate()
}

function replaceInList(c) {
  const i = clinics.value.findIndex((x) => x.id === c.id)
  if (i >= 0) clinics.value[i] = c
  else clinics.value.push(c)
}

async function save() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  const { version, active, ...fields } = form
  const result = await run(
    (opts) => (editing.value ? clinicApi.update(editing.value.id, { ...fields, active, version }, opts) : clinicApi.create(fields, opts)),
    {
      handle: (e) => {
        if (e.code === 'CONFLICT') {
          fieldErrors.name = '已有同名诊所，请换一个名称'
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
  ElMessage.success(editing.value ? '诊所资料已保存' : `已新增诊所“${result.name}”`)
}

// ---------- 停用 / 启用 ----------
const toggling = ref(null)
const toggler = useSubmit()

async function toggleActive(c) {
  const stopping = c.active
  try {
    await ElMessageBox.confirm(
      stopping ? `停用后，“${c.name}”不能再安排新的预约，已有的预约、就诊和结算记录都会保留。确定停用吗？` : `启用后，“${c.name}”可以重新安排预约。确定启用吗？`,
      stopping ? '停用诊所' : '启用诊所',
      { type: stopping ? 'warning' : 'info', confirmButtonText: stopping ? '停用' : '启用', cancelButtonText: '取消', confirmButtonClass: stopping ? 'el-button--danger' : '' }
    )
  } catch {
    return
  }
  toggling.value = c.id
  toggler.reset()
  const { id, ...rest } = c
  const result = await toggler.run((opts) => clinicApi.update(id, { ...rest, active: !stopping }, opts), {
    handle: (e) => handleChanged(e, replaceInList)
  })
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
        <el-input v-model="keyword" :prefix-icon="Search" placeholder="按名称、主管或地址查找" clearable style="width: 240px" />
        <el-radio-group v-model="statusFilter">
          <el-radio-button value="all">全部</el-radio-button>
          <el-radio-button value="active">使用中</el-radio-button>
          <el-radio-button value="inactive">已停用</el-radio-button>
        </el-radio-group>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增诊所</el-button>
    </div>

    <div class="stat-chips">
      <span class="stat-chip">使用中 <b>{{ activeCount }}</b> 家</span>
      <span class="stat-chip">已停用 <b>{{ clinics.length - activeCount }}</b> 家</span>
      <span class="stat-chip">使用中的办公面积合计 <b class="money">{{ totalArea.toFixed(2) }}</b> ㎡</span>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table v-loading="loading" :data="filtered" :row-class-name="rowClass">
        <template #empty><TableEmpty :error="loadError" text="没有符合条件的诊所" @retry="load" /></template>
        <el-table-column label="诊所" min-width="200">
          <template #default="{ row }">
            <div>{{ row.name }}</div>
            <div class="cell-sub">{{ row.postalAddress }}</div>
          </template>
        </el-table-column>
        <el-table-column label="主管" prop="managerName" width="110" />
        <el-table-column label="联系方式" min-width="190">
          <template #default="{ row }">
            <div>{{ row.phone }}</div>
            <div class="cell-sub">{{ row.email }}</div>
          </template>
        </el-table-column>
        <el-table-column label="办公面积" width="120" align="right">
          <template #default="{ row }"><span class="money">{{ Number(row.officeArea).toFixed(2) }}</span> ㎡</template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.active ? 'success' : 'info'" effect="light" size="small">{{ row.active ? '使用中' : '已停用' }}</el-tag>
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

    <el-dialog v-model="dialogOpen" :before-close="guard.beforeClose" :title="editing ? `修改诊所 · ${editing.name}` : '新增诊所'" width="620px" :close-on-click-modal="false" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="诊所名称" prop="name" :error="fieldErrors.name">
            <el-input v-model="form.name" maxlength="60" placeholder="例如：天河诊所" />
          </el-form-item>
          <el-form-item label="办公面积（平方米）" prop="officeArea" :error="fieldErrors.officeArea">
            <el-input-number v-model="form.officeArea" :min="0.01" :precision="2" :step="10" :controls="false" align="left" style="width: 100%" placeholder="大于 0，最多两位小数" />
          </el-form-item>
          <el-form-item label="邮政地址" prop="postalAddress" class="span-2" :error="fieldErrors.postalAddress">
            <el-input v-model="form.postalAddress" maxlength="255" />
          </el-form-item>
          <el-form-item label="主管姓名" prop="managerName" :error="fieldErrors.managerName">
            <el-input v-model="form.managerName" maxlength="60" />
          </el-form-item>
          <el-form-item label="电话" prop="phone" :error="fieldErrors.phone">
            <el-input v-model="form.phone" maxlength="30" />
          </el-form-item>
          <el-form-item label="电子邮箱" prop="email" class="span-2" :error="fieldErrors.email">
            <el-input v-model="form.email" maxlength="254" />
          </el-form-item>
          <el-form-item v-if="editing" label="使用状态" class="span-2" :error="fieldErrors.active">
            <el-switch v-model="form.active" active-text="使用中" inactive-text="已停用" inline-prompt style="--el-switch-off-color: #a8abb2" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="guard.close(() => (dialogOpen = false))">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
