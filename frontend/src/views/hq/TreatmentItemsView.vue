<script setup>
// UC10 维护治疗项目与标准价格（总部）：项目资料、数量精度、调价（保留价格历史，不改不删）
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Search, Edit, PriceTag } from '@element-plus/icons-vue'
import { itemApi } from '@/api/base.js'
import TableEmpty from '@/components/TableEmpty.vue'
import { useSubmit, handleChanged } from '@/composables/useSubmit.js'
import { useDirtyGuard } from '@/composables/useDirtyGuard.js'
import { money, scaleText, toApiDate } from '@shared/format.js'

const loading = ref(false)
const items = ref([])
const keyword = ref('')
const statusFilter = ref('all')
const today = toApiDate()

const loadError = ref('') // 加载失败的原因，用于和“没有记录”区分

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    items.value = await itemApi.list()
  } catch (e) {
    loadError.value = e.message || '请稍后重试'
  } finally {
    loading.value = false
  }
}
onMounted(load)

const filtered = computed(() => {
  const k = keyword.value.trim()
  return items.value.filter((i) => {
    if (statusFilter.value === 'active' && !i.active) return false
    if (statusFilter.value === 'inactive' && i.active) return false
    return !k || i.name.includes(k)
  })
})
const futureCount = computed(() => items.value.filter((i) => i.prices.some((p) => p.effectiveFrom > today)).length)

/** 价格历史里每一条的状态：已过期 / 当前适用 / 未生效 */
function priceStatus(item, price) {
  if (price.effectiveFrom > today) return { text: '未生效', type: 'warning' }
  const applicable = item.prices.filter((p) => p.effectiveFrom <= today)
  return applicable[applicable.length - 1]?.id === price.id ? { text: '当前适用', type: 'success' } : { text: '已被替代', type: 'info' }
}
const nextPrice = (item) => item.prices.find((p) => p.effectiveFrom > today)

function replaceInList(item) {
  const i = items.value.findIndex((x) => x.id === item.id)
  if (i >= 0) items.value[i] = item
  else items.value.push(item)
}

// ---------- 新增 / 修改项目 ----------
const dialogOpen = ref(false)
const editing = ref(null)
const formRef = ref()
const form = reactive({})
const guard = useDirtyGuard(() => form) // 关闭前检查有没有没保存的修改
watch(dialogOpen, (open) => !open && guard.clear())
const { saving, fieldErrors, run, reset } = useSubmit()

const rules = {
  name: [{ required: true, message: '请填写项目名称', trigger: 'blur' }],
  standardUnit: [{ required: true, message: '请填写标准单位', trigger: 'blur' }],
  quantityScale: [{ required: true, message: '请选择数量精度', trigger: 'change' }],
  firstPriceFrom: [{ required: true, message: '请选择适用起始日期', trigger: 'change' }],
  firstPriceValue: [{ required: true, message: '请填写单位价格', trigger: 'blur' }]
}

function fillForm(item) {
  Object.assign(form, {
    name: item?.name ?? '',
    standardUnit: item?.standardUnit ?? '',
    quantityScale: item?.quantityScale ?? 0,
    active: item?.active ?? true,
    version: item?.version ?? null,
    firstPriceFrom: today,
    firstPriceValue: null
  })
  guard.mark()
}
function openCreate() {
  editing.value = null
  fillForm(null)
  reset()
  dialogOpen.value = true
}
function openEdit(item) {
  editing.value = item
  fillForm(item)
  reset()
  dialogOpen.value = true
}

async function save() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  const base = { name: form.name, standardUnit: form.standardUnit, quantityScale: form.quantityScale }
  const result = await run(
    (opts) =>
      editing.value
        ? itemApi.update(editing.value.id, { ...base, active: form.active, version: form.version }, opts)
        : itemApi.create({ ...base, firstPrice: { effectiveFrom: form.firstPriceFrom, unitPrice: form.firstPriceValue } }, opts),
    {
      handle: (e) => {
        if (e.code === 'CONFLICT') {
          fieldErrors.name = '已有同名治疗项目，请换一个名称'
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
  ElMessage.success(editing.value ? '项目资料已保存' : `已新增治疗项目“${result.name}”`)
}

// ---------- 调价 ----------
const priceOpen = ref(false)
const priceItem = ref(null)
const priceFormRef = ref()
const priceForm = reactive({ effectiveFrom: '', unitPrice: null })
const priceSubmit = useSubmit()
const priceGuard = useDirtyGuard(() => priceForm)
watch(priceOpen, (open) => !open && priceGuard.clear())
const priceRules = {
  effectiveFrom: [{ required: true, message: '请选择适用起始日期', trigger: 'change' }],
  unitPrice: [{ required: true, message: '请填写新的单位价格', trigger: 'blur' }]
}
const dateTaken = computed(() => priceItem.value?.prices.some((p) => p.effectiveFrom === priceForm.effectiveFrom))
const isBackdated = computed(() => priceForm.effectiveFrom && priceForm.effectiveFrom < today)

function tomorrow() {
  const d = new Date()
  d.setDate(d.getDate() + 1)
  return toApiDate(d)
}
function openPrice(item) {
  priceItem.value = item
  Object.assign(priceForm, { effectiveFrom: tomorrow(), unitPrice: item.currentPrice })
  priceSubmit.reset()
  priceGuard.mark()
  priceOpen.value = true
}
async function savePrice() {
  const valid = await priceFormRef.value.validate().catch(() => false)
  if (!valid) return
  if (isBackdated.value) {
    try {
      await ElMessageBox.confirm(
        `适用起始日期 ${priceForm.effectiveFrom} 早于今天。已经开具的结算单不受影响，但此后为这段日期内的就诊开单时会按新价格计算。确定继续吗？`,
        '起始日期早于今天',
        { type: 'warning', confirmButtonText: '继续保存', cancelButtonText: '返回修改' }
      )
    } catch {
      return
    }
  }
  const result = await priceSubmit.run((opts) => itemApi.addPrice(priceItem.value.id, { ...priceForm }, opts))
  if (!result) return
  replaceInList(result)
  priceOpen.value = false
  ElMessage.success(`已为“${result.name}”新增一条价格：${priceForm.effectiveFrom} 起 ${money(priceForm.unitPrice)} 元`)
}

function rowClass({ row }) {
  return row.active ? '' : 'row-inactive'
}
</script>

<template>
  <div class="page">
    <div class="page-toolbar">
      <div class="toolbar-left">
        <el-input v-model="keyword" :prefix-icon="Search" placeholder="按项目名称查找" clearable style="width: 220px" />
        <el-radio-group v-model="statusFilter">
          <el-radio-button value="all">全部</el-radio-button>
          <el-radio-button value="active">使用中</el-radio-button>
          <el-radio-button value="inactive">已停用</el-radio-button>
        </el-radio-group>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增治疗项目</el-button>
    </div>

    <div class="stat-chips">
      <span class="stat-chip">使用中 <b>{{ items.filter((i) => i.active).length }}</b> 项</span>
      <span class="stat-chip">有待生效调价 <b>{{ futureCount }}</b> 项</span>
      <span class="page-intro">点击行首的箭头查看价格历史；价格只能新增，不能修改或删除。</span>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table v-loading="loading" :data="filtered" :row-class-name="rowClass" row-key="id">
        <template #empty><TableEmpty :error="loadError" text="没有符合条件的治疗项目" @retry="load" /></template>
        <el-table-column type="expand" width="44">
          <template #default="{ row }">
            <div class="price-history">
              <div class="history-title">“{{ row.name }}”价格历史（按适用起始日期排列）</div>
              <el-timeline>
                <el-timeline-item
                  v-for="p in row.prices"
                  :key="p.id"
                  :timestamp="`${p.effectiveFrom} 起`"
                  placement="top"
                  :type="priceStatus(row, p).type"
                  :hollow="priceStatus(row, p).type !== 'success'"
                >
                  <span class="money history-price">{{ money(p.unitPrice) }} 元 / {{ row.standardUnit }}</span>
                  <el-tag :type="priceStatus(row, p).type" size="small" effect="plain">{{ priceStatus(row, p).text }}</el-tag>
                </el-timeline-item>
              </el-timeline>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="治疗项目" prop="name" min-width="150" />
        <el-table-column label="标准单位" prop="standardUnit" width="100" align="center" />
        <el-table-column label="数量精度" width="110" align="center">
          <template #default="{ row }">{{ scaleText(row.quantityScale) }}</template>
        </el-table-column>
        <el-table-column label="今天适用的单价" min-width="170" align="right">
          <template #default="{ row }">
            <div class="money">{{ row.currentPrice === null ? '尚未生效' : `${money(row.currentPrice)} 元` }}</div>
            <div v-if="nextPrice(row)" class="cell-sub">{{ nextPrice(row).effectiveFrom }} 起调为 {{ money(nextPrice(row).unitPrice) }} 元</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.active ? 'success' : 'info'" size="small">{{ row.active ? '使用中' : '已停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :icon="Edit" @click="openEdit(row)">修改</el-button>
            <el-button link type="primary" :icon="PriceTag" @click="openPrice(row)">调价</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新增 / 修改项目 -->
    <el-dialog v-model="dialogOpen" :before-close="guard.beforeClose" :title="editing ? `修改治疗项目 · ${editing.name}` : '新增治疗项目'" width="560px" :close-on-click-modal="false" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
        <div class="form-grid">
          <el-form-item label="项目名称" prop="name" class="span-2" :error="fieldErrors.name">
            <el-input v-model="form.name" maxlength="100" placeholder="例如：烤瓷冠" />
          </el-form-item>
          <el-form-item label="标准单位" prop="standardUnit" :error="fieldErrors.standardUnit">
            <el-input v-model="form.standardUnit" maxlength="30" placeholder="例如：次、颗、毫升" />
          </el-form-item>
          <el-form-item label="数量精度（小数位数）" prop="quantityScale" :error="fieldErrors.quantityScale">
            <el-input-number v-model="form.quantityScale" :min="0" :max="6" :step="1" step-strictly style="width: 100%" />
            <div class="field-hint">牙医录入数量时最多保留几位小数；0 表示只能填整数。</div>
          </el-form-item>
          <template v-if="!editing">
            <el-form-item label="首次价格起始日期" prop="firstPriceFrom" :error="fieldErrors['firstPrice.effectiveFrom'] || fieldErrors.firstPrice">
              <el-date-picker v-model="form.firstPriceFrom" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
            <el-form-item label="单位价格（元）" prop="firstPriceValue" :error="fieldErrors['firstPrice.unitPrice']">
              <el-input-number v-model="form.firstPriceValue" :min="0" :precision="2" :controls="false" align="left" style="width: 100%" placeholder="0.00" />
            </el-form-item>
          </template>
          <el-form-item v-else label="使用状态" class="span-2" :error="fieldErrors.active">
            <el-switch v-model="form.active" active-text="使用中" inactive-text="已停用" inline-prompt style="--el-switch-off-color: #a8abb2" />
            <div class="field-hint">停用后不能再被预约或录入，历史就诊和价格记录保留。修改价格请用“调价”。</div>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="guard.close(() => (dialogOpen = false))">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 调价 -->
    <el-dialog v-model="priceOpen" :before-close="priceGuard.beforeClose" :title="`调价 · ${priceItem?.name ?? ''}`" width="480px" :close-on-click-modal="false" destroy-on-close>
      <div v-if="priceItem" class="price-now">
        <span class="muted">今天适用</span>
        <b class="money">{{ priceItem.currentPrice === null ? '尚未生效' : `${money(priceItem.currentPrice)} 元 / ${priceItem.standardUnit}` }}</b>
      </div>
      <el-form ref="priceFormRef" :model="priceForm" :rules="priceRules" label-position="top" @submit.prevent>
        <el-form-item label="适用起始日期" prop="effectiveFrom" :error="priceSubmit.fieldErrors.effectiveFrom">
          <el-date-picker v-model="priceForm.effectiveFrom" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          <div v-if="dateTaken" class="field-hint warn">这一天已经有一条价格，同一项目同一天只能有一个价格。</div>
          <div v-else-if="isBackdated" class="field-hint warn">起始日期早于今天，会影响这段日期内尚未开单的就诊。</div>
        </el-form-item>
        <el-form-item label="新的单位价格（元）" prop="unitPrice" :error="priceSubmit.fieldErrors.unitPrice">
          <el-input-number v-model="priceForm.unitPrice" :min="0" :precision="2" :controls="false" align="left" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="priceGuard.close(() => (priceOpen = false))">取消</el-button>
        <el-button type="primary" :loading="priceSubmit.saving.value" :disabled="dateTaken" @click="savePrice">保存新价格</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.price-history {
  padding: 12px 24px 4px 60px;
}
.history-title {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 12px;
}
.history-price {
  font-weight: 600;
  margin-right: 10px;
}
.field-hint {
  font-size: 12px;
  line-height: 1.6;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
}
.field-hint.warn {
  color: var(--el-color-warning-dark-2);
}
.price-now {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 10px 14px;
  margin-bottom: 16px;
  border-radius: 8px;
  background: var(--el-color-primary-light-9);
}
.price-now b {
  font-size: 16px;
  color: var(--el-color-primary-dark-2);
}
</style>
