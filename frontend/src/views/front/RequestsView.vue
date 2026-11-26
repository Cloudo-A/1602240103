<script setup>
// 预约申请（前台）：患者在官网、小程序提交的预约申请。前台回电确认后转为正式预约，联系不上或不合适的判定为无效（协作约定第 9.17 节）
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Phone, Right, CircleClose, ChatLineRound } from '@element-plus/icons-vue'
import { requestApi } from '@/api/portal.js'
import { options } from '@/api/base.js'
import { useSubmit } from '@/composables/useSubmit.js'
import TableEmpty from '@/components/TableEmpty.vue'
import { REQUEST_STATUS, APPOINTMENT_STATUS, timeSlotText } from '@shared/labels.js'
import { toApiDate, dateTime } from '@shared/format.js'

const router = useRouter()
const today = toApiDate()
const daysAgo = (n) => {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return toApiDate(d)
}

const status = ref('PENDING')
const clinicId = ref(null)
const range = ref([daysAgo(30), today])
const rows = ref([])
const loading = ref(false)
const loadError = ref('')
const clinics = ref([])

let seq = 0
async function load() {
  const mine = ++seq
  loading.value = true
  loadError.value = ''
  try {
    const [from, to] = range.value || []
    const list = await requestApi.list({ status: status.value === 'ALL' ? null : status.value, clinicId: clinicId.value, from, to })
    if (mine === seq) rows.value = list
  } catch (e) {
    if (mine === seq) {
      rows.value = []
      loadError.value = e.message || '请稍后重试'
    }
  } finally {
    if (mine === seq) loading.value = false
  }
}
watch([status, clinicId, range], load)
onMounted(async () => {
  load()
  try {
    clinics.value = await options.clinics(false)
  } catch {
    /* 下拉加载失败不影响列表 */
  }
})

// 待处理按提交时间从早到晚：先提交的先回电
const shown = computed(() => (status.value === 'PENDING' ? [...rows.value].reverse() : rows.value))
const isPast = (r) => r.status === 'PENDING' && r.preferredDate < today
const isToday = (r) => r.status === 'PENDING' && r.preferredDate === today

function toAppointment(r) {
  router.push({ name: 'appointments', query: { requestId: r.id } })
}

// ---------- 判定无效 ----------
const QUICK_REASONS = ['多次致电无人接听', '患者表示暂不需要', '电话号码有误，无法联系', '患者已改为到店或电话预约']
const invalidOpen = ref(false)
const target = ref(null)
const reason = ref('')
const invalidator = useSubmit()
function openInvalid(r) {
  target.value = r
  reason.value = ''
  invalidator.reset()
  invalidOpen.value = true
}
async function saveInvalid() {
  if (!reason.value.trim()) {
    invalidator.fieldErrors.reason = '请填写无效原因'
    return
  }
  const result = await invalidator.run((opts) => requestApi.invalidate(target.value.id, { reason: reason.value.trim(), version: target.value.version }, opts), {
    handle: (e) => {
      if (e.code === 'CHANGED' || e.code === 'STATE_NOT_ALLOWED') {
        ElMessage.warning(e.code === 'CHANGED' ? '这条预约申请刚被修改过，已刷新，请重新核对' : '这条预约申请已被处理，已刷新列表')
        invalidOpen.value = false
        load()
        return true
      }
      return false
    }
  })
  if (!result) return
  invalidOpen.value = false
  ElMessage.success(`已将 ${result.contactName} 的预约申请判定为无效`)
  load()
}

const statusTabs = computed(() => [
  { value: 'PENDING', label: '待处理' },
  { value: 'CONFIRMED', label: '已转预约' },
  { value: 'INVALID', label: '无效' },
  { value: 'WITHDRAWN', label: '患者撤回' },
  { value: 'ALL', label: '全部' }
])
const pendingCount = computed(() => (status.value === 'PENDING' ? rows.value.length : null))
const tel = (p) => String(p || '').replace(/[^\d]/g, '')
</script>

<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-radio-group v-model="status">
          <el-radio-button v-for="t in statusTabs" :key="t.value" :value="t.value">
            {{ t.label }}<span v-if="t.value === 'PENDING' && pendingCount" class="tab-count">{{ pendingCount }}</span>
          </el-radio-button>
        </el-radio-group>
        <el-select v-model="clinicId" clearable placeholder="全部门店" style="width: 150px">
          <el-option v-for="c in clinics" :key="c.id" :value="c.id" :label="c.name" />
        </el-select>
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="提交日期" end-placeholder="提交日期" style="width: 260px" />
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
      <p class="tip">
        患者在官网或小程序登记的预约申请。请按联系电话回电，与患者确认牙医和具体时间后点“转为预约”；联系不上或患者不再需要时“判定无效”并写明原因。处理结果会显示在患者的“我的预约”里。
      </p>

      <el-table v-loading="loading" :data="shown" :row-class-name="({ row }) => (row.status === 'PENDING' ? '' : 'row-done')">
        <template #empty>
          <TableEmpty :error="loadError" :text="status === 'PENDING' ? '没有待处理的预约申请' : '没有符合条件的预约申请'" @retry="load" />
        </template>
        <el-table-column label="提交时间" width="150">
          <template #default="{ row }">{{ dateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="联系人" min-width="150">
          <template #default="{ row }">
            <div class="who">{{ row.contactName }}</div>
            <a class="phone" :href="'tel:' + tel(row.contactPhone)"><el-icon><Phone /></el-icon>{{ row.contactPhone }}</a>
          </template>
        </el-table-column>
        <el-table-column label="期望门店与时间" min-width="200">
          <template #default="{ row }">
            <div class="who">{{ row.clinic.name }}</div>
            <div class="sub">
              {{ row.preferredDate }} · {{ timeSlotText(row.timeSlot) }}
              <el-tag v-if="isPast(row)" type="danger" size="small" effect="plain">期望日期已过</el-tag>
              <el-tag v-else-if="isToday(row)" type="warning" size="small" effect="plain">就是今天</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="想做的项目与说明" min-width="200">
          <template #default="{ row }">
            <div v-if="row.items.length" class="items">{{ row.items.map((i) => i.name).join('、') }}</div>
            <div v-else class="sub">未选项目</div>
            <div v-if="row.note" class="note"><el-icon><ChatLineRound /></el-icon>{{ row.note }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="210">
          <template #default="{ row }">
            <el-tag :type="REQUEST_STATUS[row.status].type" size="small">{{ REQUEST_STATUS[row.status].text }}</el-tag>
            <div v-if="row.status === 'CONFIRMED' && row.appointment" class="sub">
              {{ dateTime(row.appointment.scheduledAt) }} · {{ row.appointment.dentist.name }}
              <el-tag v-if="row.appointment.status !== 'VALID'" size="small" :type="APPOINTMENT_STATUS[row.appointment.status]?.type" effect="plain">{{ APPOINTMENT_STATUS[row.appointment.status]?.text }}</el-tag>
            </div>
            <div v-else-if="row.status === 'INVALID'" class="sub">{{ row.invalidReason }}</div>
            <div v-else-if="row.status === 'WITHDRAWN'" class="sub">撤回于 {{ dateTime(row.withdrawnAt) }}</div>
            <div v-if="row.handledBy" class="sub muted">{{ row.handledBy }} · {{ dateTime(row.handledAt) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right" align="center">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button type="primary" size="small" :icon="Right" @click="toAppointment(row)">转为预约</el-button>
              <el-button size="small" link type="danger" :icon="CircleClose" @click="openInvalid(row)">判定无效</el-button>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 判定无效 -->
    <el-dialog v-model="invalidOpen" title="判定预约申请无效" width="480px" :close-on-click-modal="false">
      <template v-if="target">
        <div class="target">
          <b>{{ target.contactName }}</b> {{ target.contactPhone }}
          <div class="sub">{{ target.clinic.name }} · {{ target.preferredDate }} · {{ timeSlotText(target.timeSlot) }}</div>
        </div>
        <el-form label-position="top" @submit.prevent>
          <el-form-item label="无效原因（患者会在“我的预约”里看到）" :error="invalidator.fieldErrors.reason" required>
            <el-input v-model="reason" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="例如：多次致电无人接听" />
            <div class="quick">
              <el-tag v-for="q in QUICK_REASONS" :key="q" class="quick-tag" effect="plain" @click="reason = q">{{ q }}</el-tag>
            </div>
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="invalidOpen = false">取消</el-button>
        <el-button type="danger" :loading="invalidator.saving.value" @click="saveInvalid">判定无效</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.tab-count {
  display: inline-block;
  min-width: 18px;
  margin-left: 6px;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--el-color-danger);
  color: #fff;
  font-size: 12px;
  line-height: 18px;
}
.tip {
  margin: 12px 0 8px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.who {
  font-weight: 600;
}
.sub {
  color: var(--el-text-color-secondary);
  font-size: 12.5px;
  margin-top: 2px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
}
.phone {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--el-color-primary);
  text-decoration: none;
  font-variant-numeric: tabular-nums;
}
.items {
  font-size: 13px;
}
.note {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  margin-top: 4px;
  color: #8a5a2b;
  font-size: 12.5px;
}
.note .el-icon {
  margin-top: 3px;
}
.muted {
  color: var(--el-text-color-placeholder);
}
:deep(.row-done) {
  color: var(--el-text-color-secondary);
}
.target {
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  margin-bottom: 14px;
}
.quick {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.quick-tag {
  cursor: pointer;
}
</style>
