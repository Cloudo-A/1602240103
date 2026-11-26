<script setup>
// 账号管理（总部）：为前台、牙医、总部人员开设账号，停用账号，重置密码（协作约定 9.13）
// 登录名和岗位开设后不能修改；牙医账号必须对应一位牙医，一位牙医只能有一个账号
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search, Edit, Key, MagicStick, CopyDocument } from '@element-plus/icons-vue'
import { userApi } from '@/api/system.js'
import { options } from '@/api/base.js'
import { useSubmit, handleChanged } from '@/composables/useSubmit.js'
import { useDirtyGuard } from '@/composables/useDirtyGuard.js'
import { auth } from '@/stores/auth.js'
import TableEmpty from '@/components/TableEmpty.vue'
import { ROLES } from '@shared/labels.js'

const ROLE_TAG = { FRONT_DESK: 'primary', DENTIST: 'success', HQ: 'warning' }
const me = computed(() => auth.state.user)

// ---------- 列表 ----------
const loading = ref(false)
const loadError = ref('')
const users = ref([])
const dentistOptions = ref([])
const keyword = ref('')
const roleFilter = ref('')
const statusFilter = ref('all')

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    ;[users.value, dentistOptions.value] = await Promise.all([userApi.list(), options.dentists(false)])
  } catch (e) {
    loadError.value = e.message || '请稍后重试'
  } finally {
    loading.value = false
  }
}
onMounted(load)

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  return users.value.filter((u) => {
    if (roleFilter.value && u.role !== roleFilter.value) return false
    if (statusFilter.value === 'active' && !u.active) return false
    if (statusFilter.value === 'inactive' && u.active) return false
    return !k || u.username.toLowerCase().includes(k) || u.displayName.includes(k) || (u.dentist?.employeeNo || '').toLowerCase().includes(k)
  })
})
const countRole = (r) => users.value.filter((u) => u.role === r && u.active).length
const replaceRow = (u) => {
  const i = users.value.findIndex((x) => x.id === u.id)
  if (i >= 0) users.value[i] = u
  else users.value.push(u)
}
const rowClass = ({ row }) => (row.active ? '' : 'row-inactive')

// 牙医下拉：已绑定其他账号的牙医不能再选
const boundBy = (dentistId, selfId) => users.value.find((u) => u.dentist?.id === dentistId && u.id !== selfId)

function randomPassword() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789'
  const buf = new Uint32Array(10)
  crypto.getRandomValues(buf)
  return Array.from(buf, (n) => chars[n % chars.length]).join('')
}

// ---------- 新增 / 修改 ----------
const dialogOpen = ref(false)
const editing = ref(null)
const formRef = ref()
const form = reactive({})
const guard = useDirtyGuard(() => form)
watch(dialogOpen, (open) => !open && guard.clear())
const { saving, fieldErrors, run, reset } = useSubmit()
const showPassword = ref(false)

const samePassword = (rule, value, cb) => (value === form.password ? cb() : cb(new Error('两次输入的密码不一致')))
const rules = computed(() => ({
  username: [
    { required: true, message: '请填写登录名', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_]{3,40}$/, message: '登录名为 3—40 位字母、数字或下划线', trigger: 'blur' }
  ],
  displayName: [{ required: true, message: '请填写显示姓名', trigger: 'blur' }],
  role: [{ required: true, message: '请选择岗位', trigger: 'change' }],
  dentistId: form.role === 'DENTIST' ? [{ required: true, message: '请选择对应的牙医', trigger: 'change' }] : [],
  password: editing.value ? [] : [{ required: true, message: '请填写初始密码', trigger: 'blur' }, { min: 6, max: 64, message: '密码应为 6—64 位', trigger: 'blur' }],
  confirm: editing.value ? [] : [{ required: true, message: '请再输入一次密码', trigger: 'blur' }, { validator: samePassword, trigger: 'blur' }]
}))

function fillForm(u) {
  Object.assign(form, {
    username: u?.username ?? '',
    displayName: u?.displayName ?? '',
    role: u?.role ?? 'FRONT_DESK',
    dentistId: u?.dentist?.id ?? null,
    active: u?.active ?? true,
    password: '',
    confirm: '',
    version: u?.version ?? null
  })
  showPassword.value = false
  guard.mark()
}
function openCreate() {
  editing.value = null
  fillForm(null)
  reset()
  dialogOpen.value = true
  formRef.value?.clearValidate()
}
function openEdit(u) {
  editing.value = u
  fillForm(u)
  reset()
  dialogOpen.value = true
  formRef.value?.clearValidate()
}
function generate() {
  form.password = form.confirm = randomPassword()
  showPassword.value = true
}
// 选牙医时，显示姓名还空着就自动填上
watch(
  () => form.dentistId,
  (id) => {
    const d = dentistOptions.value.find((x) => x.id === id)
    if (d && !form.displayName) form.displayName = d.name
  }
)
watch(
  () => form.role,
  (r) => {
    if (r !== 'DENTIST') form.dentistId = null
  }
)

async function save() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  const createdPassword = form.password
  const body = editing.value
    ? { displayName: form.displayName.trim(), dentistId: editing.value.role === 'DENTIST' ? form.dentistId : null, active: form.active, version: form.version }
    : { username: form.username.trim(), displayName: form.displayName.trim(), role: form.role, dentistId: form.role === 'DENTIST' ? form.dentistId : null, password: form.password }
  const result = await run((opts) => (editing.value ? userApi.update(editing.value.id, body, opts) : userApi.create(body, opts)), {
    handle: (e) => {
      if (e.code === 'CONFLICT') {
        if (e.message.includes('登录名')) fieldErrors.username = e.message
        else fieldErrors.dentistId = e.message
        return true
      }
      if (e.code === 'STATE_NOT_ALLOWED') {
        fieldErrors.active = e.message
        return true
      }
      return handleChanged(e, (current) => {
        replaceRow(current)
        editing.value = current
        fillForm(current)
      })
    }
  })
  if (!result) return
  replaceRow(result)
  dialogOpen.value = false
  if (editing.value) ElMessage.success('账号已保存')
  else showIssued(result, createdPassword, '账号已开设')
}

// ---------- 停用 / 启用 ----------
const toggler = useSubmit()
const togglingId = ref(null)
async function toggleActive(u) {
  const stopping = u.active
  try {
    await ElMessageBox.confirm(
      stopping ? `停用后，${u.displayName}（${u.username}）不能再登录，已经登录的设备会立即退出。历史操作记录会保留。确定停用吗？` : `启用后，${u.displayName}（${u.username}）可以重新用原密码登录。确定启用吗？`,
      stopping ? '停用账号' : '启用账号',
      { type: stopping ? 'warning' : 'info', confirmButtonText: stopping ? '停用' : '启用', cancelButtonText: '取消', confirmButtonClass: stopping ? 'el-button--danger' : '' }
    )
  } catch {
    return
  }
  togglingId.value = u.id
  toggler.reset()
  const result = await toggler.run((opts) => userApi.update(u.id, { displayName: u.displayName, dentistId: u.dentist?.id ?? null, active: !stopping, version: u.version }, opts), {
    handle: (e) => {
      if (e.code === 'STATE_NOT_ALLOWED') {
        ElMessage.warning(e.message)
        return true
      }
      return handleChanged(e, replaceRow)
    }
  })
  togglingId.value = null
  if (result) {
    replaceRow(result)
    ElMessage.success(stopping ? '账号已停用' : '账号已启用')
  }
}

// ---------- 重置密码 ----------
const resetOpen = ref(false)
const resetting = ref(null)
const resetFormRef = ref()
const resetForm = reactive({ password: '', confirm: '' })
const resetter = useSubmit()
const resetShow = ref(false)
const resetSame = (rule, value, cb) => (value === resetForm.password ? cb() : cb(new Error('两次输入的密码不一致')))
const resetRules = {
  password: [{ required: true, message: '请填写新密码', trigger: 'blur' }, { min: 6, max: 64, message: '密码应为 6—64 位', trigger: 'blur' }],
  confirm: [{ required: true, message: '请再输入一次新密码', trigger: 'blur' }, { validator: resetSame, trigger: 'blur' }]
}
function openReset(u) {
  resetting.value = u
  Object.assign(resetForm, { password: '', confirm: '' })
  resetShow.value = false
  resetter.reset()
  resetOpen.value = true
  resetFormRef.value?.clearValidate()
}
function generateReset() {
  resetForm.password = resetForm.confirm = randomPassword()
  resetShow.value = true
}
async function saveReset() {
  const valid = await resetFormRef.value.validate().catch(() => false)
  if (!valid) return
  const pwd = resetForm.password
  const result = await resetter.run((opts) => userApi.resetPassword(resetting.value.id, pwd, opts))
  if (!result) return
  replaceRow(result)
  resetOpen.value = false
  showIssued(result, pwd, '密码已重置')
}

// ---------- 把密码告诉本人 ----------
const issuedOpen = ref(false)
const issued = reactive({ title: '', user: null, password: '' })
function showIssued(u, password, title) {
  Object.assign(issued, { title, user: u, password })
  issuedOpen.value = true
}
async function copyIssued() {
  const text = `ADC 系统账号\n登录名：${issued.user.username}\n密码：${issued.password}\n首次登录后请在右上角“修改密码”中改成自己的密码。`
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制，可以发给本人')
  } catch {
    ElMessage.warning('浏览器不允许复制，请手动记下密码')
  }
}
</script>

<template>
  <div class="page">
    <div class="page-toolbar">
      <div class="toolbar-left">
        <el-input v-model="keyword" :prefix-icon="Search" placeholder="按登录名、姓名或员工编号查找" clearable style="width: 240px" />
        <el-radio-group v-model="roleFilter">
          <el-radio-button value="">全部岗位</el-radio-button>
          <el-radio-button v-for="(name, key) in ROLES" :key="key" :value="key">{{ name.replace('工作人员', '').replace('管理人员', '') }}</el-radio-button>
        </el-radio-group>
        <el-select v-model="statusFilter" style="width: 110px">
          <el-option value="all" label="全部状态" />
          <el-option value="active" label="使用中" />
          <el-option value="inactive" label="已停用" />
        </el-select>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增账号</el-button>
    </div>

    <div class="stat-chips">
      <span class="stat-chip">使用中：前台 <b>{{ countRole('FRONT_DESK') }}</b></span>
      <span class="stat-chip">牙医 <b>{{ countRole('DENTIST') }}</b></span>
      <span class="stat-chip">总部 <b>{{ countRole('HQ') }}</b></span>
      <span class="stat-chip">已停用 <b>{{ users.filter((u) => !u.active).length }}</b></span>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table v-loading="loading" :data="filtered" :row-class-name="rowClass">
        <template #empty><TableEmpty :error="loadError" text="没有符合条件的账号" @retry="load" /></template>
        <el-table-column label="登录名" min-width="130">
          <template #default="{ row }">
            <span class="mono">{{ row.username }}</span>
            <el-tag v-if="row.id === me?.id" size="small" effect="plain" class="me-tag">我</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="显示姓名" prop="displayName" min-width="110" />
        <el-table-column label="岗位" width="130">
          <template #default="{ row }"><el-tag :type="ROLE_TAG[row.role]" effect="light" size="small">{{ ROLES[row.role] }}</el-tag></template>
        </el-table-column>
        <el-table-column label="对应牙医" min-width="130">
          <template #default="{ row }">
            <span v-if="row.dentist">{{ row.dentist.name }} <span class="cell-sub">{{ row.dentist.employeeNo }}</span></span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }"><el-tag :type="row.active ? 'success' : 'info'" effect="light" size="small">{{ row.active ? '使用中' : '已停用' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="230" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="Edit" @click="openEdit(row)">修改</el-button>
            <el-button link type="primary" :icon="Key" @click="openReset(row)">重置密码</el-button>
            <el-tooltip v-if="row.id === me?.id" content="不能停用自己正在使用的账号" placement="top">
              <span><el-button link disabled>停用</el-button></span>
            </el-tooltip>
            <el-button v-else link :type="row.active ? 'danger' : 'success'" :loading="togglingId === row.id" @click="toggleActive(row)">{{ row.active ? '停用' : '启用' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增 / 修改 -->
    <el-dialog v-model="dialogOpen" :before-close="guard.beforeClose" :title="editing ? `修改账号 · ${editing.username}` : '新增账号'" width="560px" :close-on-click-modal="false" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="登录名" prop="username" :error="fieldErrors.username">
            <el-input v-model="form.username" :disabled="Boolean(editing)" maxlength="40" placeholder="例如 front2" />
            <div v-if="!editing" class="field-hint">开设后不能修改</div>
          </el-form-item>
          <el-form-item label="岗位" prop="role" :error="fieldErrors.role">
            <el-radio-group v-model="form.role" :disabled="Boolean(editing)">
              <el-radio-button v-for="(name, key) in ROLES" :key="key" :value="key">{{ name.replace('工作人员', '').replace('管理人员', '') }}</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item v-if="form.role === 'DENTIST'" label="对应的牙医" prop="dentistId" class="span-2" :error="fieldErrors.dentistId">
            <el-select v-model="form.dentistId" filterable placeholder="选择牙医" style="width: 100%">
              <el-option v-for="d in dentistOptions" :key="d.id" :value="d.id" :label="`${d.name}（${d.employeeNo}）`" :disabled="(!d.active && d.id !== editing?.dentist?.id) || Boolean(boundBy(d.id, editing?.id))">
                <span>{{ d.name }} <span class="muted">{{ d.employeeNo }}</span></span>
                <span class="option-sub">{{ !d.active ? '已停用' : boundBy(d.id, editing?.id) ? `已绑定 ${boundBy(d.id, editing?.id).username}` : '' }}</span>
              </el-option>
            </el-select>
            <div class="field-hint">牙医登录后只能看到、记录自己接诊的就诊。</div>
          </el-form-item>
          <el-form-item label="显示姓名" prop="displayName" class="span-2" :error="fieldErrors.displayName">
            <el-input v-model="form.displayName" maxlength="60" placeholder="登录后右上角显示的名字" />
          </el-form-item>
          <template v-if="!editing">
            <el-form-item label="初始密码" prop="password" :error="fieldErrors.password">
              <el-input v-model="form.password" :type="showPassword ? 'text' : 'password'" maxlength="64" show-password autocomplete="new-password" />
            </el-form-item>
            <el-form-item label="再输入一次" prop="confirm">
              <el-input v-model="form.confirm" :type="showPassword ? 'text' : 'password'" maxlength="64" show-password autocomplete="new-password" />
            </el-form-item>
            <div class="span-2 gen-row">
              <el-button link type="primary" :icon="MagicStick" @click="generate">随机生成一个密码</el-button>
              <span class="muted">开设后会显示一次，方便告诉本人；本人登录后可自己修改。</span>
            </div>
          </template>
          <el-form-item v-else label="使用状态" class="span-2" :error="fieldErrors.active">
            <el-switch v-model="form.active" :disabled="editing.id === me?.id" active-text="使用中" inactive-text="已停用" inline-prompt style="--el-switch-off-color: #a8abb2" />
            <span v-if="editing.id === me?.id" class="muted switch-note">不能停用自己正在使用的账号</span>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="guard.close(() => (dialogOpen = false))">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ editing ? '保存' : '开设账号' }}</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码 -->
    <el-dialog v-model="resetOpen" :title="resetting ? `重置密码 · ${resetting.displayName}（${resetting.username}）` : '重置密码'" width="460px" :close-on-click-modal="false">
      <p class="tip">重置后，这个账号已经登录的设备会立即退出，需要用新密码重新登录。</p>
      <el-form ref="resetFormRef" :model="resetForm" :rules="resetRules" label-position="top" @submit.prevent>
        <el-form-item label="新密码" prop="password" :error="resetter.fieldErrors.newPassword">
          <el-input v-model="resetForm.password" :type="resetShow ? 'text' : 'password'" maxlength="64" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="再输入一次" prop="confirm">
          <el-input v-model="resetForm.confirm" :type="resetShow ? 'text' : 'password'" maxlength="64" show-password autocomplete="new-password" />
        </el-form-item>
        <el-button link type="primary" :icon="MagicStick" @click="generateReset">随机生成一个密码</el-button>
      </el-form>
      <template #footer>
        <el-button @click="resetOpen = false">取消</el-button>
        <el-button type="primary" :loading="resetter.saving.value" @click="saveReset">重置密码</el-button>
      </template>
    </el-dialog>

    <!-- 显示一次密码 -->
    <el-dialog v-model="issuedOpen" :title="issued.title" width="420px">
      <template v-if="issued.user">
        <p class="tip">请把登录名和密码告诉 {{ issued.user.displayName }}。关闭后不会再显示这个密码。</p>
        <div class="issued">
          <div><span class="muted">登录名</span><b class="mono">{{ issued.user.username }}</b></div>
          <div><span class="muted">密码</span><b class="mono">{{ issued.password }}</b></div>
        </div>
      </template>
      <template #footer>
        <el-button :icon="CopyDocument" @click="copyIssued">复制</el-button>
        <el-button type="primary" @click="issuedOpen = false">我已记下</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.mono {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
}
.me-tag {
  margin-left: 6px;
}
.field-hint {
  width: 100%;
  font-size: 12px;
  line-height: 1.6;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
}
.option-sub {
  float: right;
  margin-left: 16px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.gen-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  margin: -6px 0 6px;
}
.switch-note {
  margin-left: 10px;
  font-size: 12px;
}
.tip {
  margin: 0 0 14px;
  line-height: 1.7;
  color: var(--el-text-color-regular);
}
.issued {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px;
  border-radius: 10px;
  background: var(--el-color-primary-light-9);
}
.issued div {
  display: flex;
  gap: 16px;
  align-items: baseline;
}
.issued span {
  width: 48px;
}
.issued b {
  font-size: 18px;
  letter-spacing: 1px;
}
</style>
