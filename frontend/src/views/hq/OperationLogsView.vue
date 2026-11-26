<script setup>
// 操作记录查询（总部，协作约定第 9.14 节、第 10 节；报告 Q3 可追溯性）
// 谁、什么时候、对哪条记录、做了什么；修改类记录展开后显示“修改前 / 修改后”对比
// 筛选、分页都由后端完成；页面只负责把 detail 翻译成中文（@shared/operationLog.js）
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Download, Search, Filter } from '@element-plus/icons-vue'
import { logApi, userApi } from '@/api/system.js'
import { options } from '@/api/base.js'
import { exportExcel } from '@/utils/exportExcel.js'
import TableEmpty from '@/components/TableEmpty.vue'
import { LOG_ACTIONS, LOG_TARGETS, roleName } from '@shared/labels.js'
import { summary, changes, fields, targetText, isChangeDetail, changeText } from '@shared/operationLog.js'
import { toApiDate, dateTime } from '@shared/format.js'

// ---------- 筛选条件 ----------
const daysAgo = (n) => {
  const d = new Date()
  d.setDate(d.getDate() - n)
  return toApiDate(d)
}
const RANGES = [
  { key: 'today', label: '今天', range: () => [daysAgo(0), daysAgo(0)] },
  { key: '7d', label: '最近 7 天', range: () => [daysAgo(6), daysAgo(0)] },
  { key: '30d', label: '最近 30 天', range: () => [daysAgo(29), daysAgo(0)] },
  { key: 'month', label: '本月', range: () => [`${daysAgo(0).slice(0, 8)}01`, daysAgo(0)] }
]
const filter = reactive({ period: RANGES[1].range(), userId: null, action: '', targetType: '', keyword: '' })
const activeRange = computed(() => RANGES.find((r) => r.range().join() === filter.period.join())?.key || '')
const actionGroups = computed(() => {
  const groups = {}
  Object.entries(LOG_ACTIONS).forEach(([key, a]) => (groups[a.group] ||= []).push({ key, text: a.text }))
  return Object.entries(groups).map(([label, items]) => ({ label, items }))
})
const hasFilter = computed(() => Boolean(filter.userId || filter.action || filter.targetType || filter.keyword.trim()))
function clearFilter() {
  Object.assign(filter, { userId: null, action: '', targetType: '', keyword: '' })
}

// ---------- 人员、牙医、诊所（用于筛选下拉和把编号翻译成名称） ----------
const users = ref([])
const refs = reactive({ dentists: new Map(), clinics: new Map() })
const userById = computed(() => new Map(users.value.map((u) => [u.id, u])))
async function loadRefs() {
  const [u, d, c] = await Promise.allSettled([userApi.list(), options.dentists(false), options.clinics(false)])
  if (u.status === 'fulfilled') users.value = u.value
  if (d.status === 'fulfilled') refs.dentists = new Map(d.value.map((x) => [x.id, x.name]))
  if (c.status === 'fulfilled') refs.clinics = new Map(c.value.map((x) => [x.id, x.name]))
}

// ---------- 列表 ----------
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const loadError = ref('')
const items = ref([])
const total = ref(0)
const queried = ref(null) // 本次结果对应的条件，用于导出说明

function params(p = page.value - 1, s = size.value) {
  return {
    from: filter.period[0],
    to: filter.period[1],
    userId: filter.userId || undefined,
    action: filter.action || undefined,
    targetType: filter.targetType || undefined,
    keyword: filter.keyword.trim() || undefined,
    page: p,
    size: s
  }
}

let seq = 0
async function load() {
  const mine = ++seq
  loading.value = true
  loadError.value = ''
  const q = params()
  try {
    const data = await logApi.list(q)
    if (mine !== seq) return
    items.value = data.items
    total.value = data.total
    queried.value = q
    // 删掉筛选后当前页超出范围（例如从第 5 页改成只看某人），回到最后一页
    if (!data.items.length && data.total && page.value > 1) page.value = Math.ceil(data.total / size.value)
  } catch (e) {
    if (mine !== seq) return
    items.value = []
    total.value = 0
    loadError.value = e.message || '请稍后重试'
  } finally {
    if (mine === seq) loading.value = false
  }
}

// 条件变化回到第 1 页；关键字输入停顿 300 毫秒再查
let timer = null
watch(() => [filter.period.join(), filter.userId, filter.action, filter.targetType], () => {
  if (page.value !== 1) page.value = 1
  else load()
})
watch(() => filter.keyword, () => {
  clearTimeout(timer)
  timer = setTimeout(() => {
    if (page.value !== 1) page.value = 1
    else load()
  }, 300)
})
watch([page, size], load)

const route = useRoute()
onMounted(() => {
  // 其他页面可以带条件跳过来：/hq/operation-logs?targetType=PATIENT&keyword=3
  const { targetType, keyword, action } = route.query
  if (targetType && LOG_TARGETS[targetType]) filter.targetType = targetType
  if (action && LOG_ACTIONS[action]) filter.action = action
  if (keyword) filter.keyword = String(keyword)
  loadRefs()
  load()
})

// 点表格里的人或对象，直接筛出相关记录
function onlyUser(row) {
  filter.userId = row.userId
}
function onlyTarget(row) {
  filter.targetType = row.targetType
  filter.keyword = row.targetId === null || row.targetId === undefined ? '' : String(row.targetId)
}

// ---------- 展开详情 ----------
const showAll = reactive({}) // 某条修改记录是否显示全部字段
const showRaw = reactive({})
const rawText = (row) => (typeof row.detail === 'string' ? row.detail : JSON.stringify(row.detail, null, 2))
const roleOf = (row) => roleName(userById.value.get(row.userId)?.role)

// ---------- 导出：按当前条件取全部页 ----------
const EXPORT_LIMIT = 2000
const exporting = ref(false)
async function exportAll() {
  exporting.value = true
  try {
    const all = []
    let p = 0
    let count = Infinity
    while (all.length < Math.min(count, EXPORT_LIMIT)) {
      const data = await logApi.list(params(p, 100))
      count = data.total
      all.push(...data.items)
      if (!data.items.length) break
      p += 1
    }
    const q = queried.value || params()
    const notes = [`操作日期：${q.from} 至 ${q.to}`]
    const cond = []
    if (q.userId) cond.push(`人员：${userById.value.get(q.userId)?.displayName || q.userId}`)
    if (q.action) cond.push(`操作：${LOG_ACTIONS[q.action]?.text || q.action}`)
    if (q.targetType) cond.push(`对象：${LOG_TARGETS[q.targetType] || q.targetType}`)
    if (q.keyword) cond.push(`关键字：${q.keyword}`)
    if (cond.length) notes.push(`筛选条件：${cond.join('；')}`)
    if (count > EXPORT_LIMIT) notes.push(`共 ${count} 条，只导出最近的 ${EXPORT_LIMIT} 条，请缩小日期范围后分批导出`)
    await exportExcel({
      filename: `操作记录_${q.from}至${q.to}`,
      sheets: [{
        name: '操作记录',
        title: '操作记录',
        notes,
        columns: [
          { header: '操作时间', key: 'time', width: 18, value: (r) => r.occurredAt.replace('T', ' ') },
          { header: '操作人', key: 'displayName', width: 10 },
          { header: '登录名', key: 'username', width: 12 },
          { header: '操作', key: 'action', width: 14, value: (r) => LOG_ACTIONS[r.action]?.text || r.action },
          { header: '对象', key: 'target', width: 14, value: (r) => targetText(r) },
          { header: '摘要', key: 'summary', width: 46, value: (r) => summary(r, refs) },
          { header: '修改内容', key: 'changes', width: 60, value: (r) => changeText(r, refs) }
        ],
        rows: all.slice(0, EXPORT_LIMIT)
      }]
    })
  } catch (e) {
    ElMessage.error(`导出失败：${e.message || '请稍后重试'}`)
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="page-toolbar">
      <div class="toolbar-left">
        <el-radio-group :model-value="activeRange" @update:model-value="(k) => (filter.period = RANGES.find((r) => r.key === k).range())">
          <el-radio-button v-for="r in RANGES" :key="r.key" :value="r.key">{{ r.label }}</el-radio-button>
        </el-radio-group>
        <el-date-picker v-model="filter.period" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" :clearable="false" :disabled-date="(d) => d > new Date()" style="width: 250px" />
      </div>
      <div>
        <el-button :icon="Download" :loading="exporting" :disabled="!total" @click="exportAll">导出 Excel</el-button>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="toolbar-left">
      <el-select v-model="filter.userId" clearable filterable placeholder="全部人员" style="width: 170px">
        <el-option v-for="u in users" :key="u.id" :value="u.id" :label="`${u.displayName}（${u.username}）`">
          <span>{{ u.displayName }}</span>
          <span class="opt-sub">{{ u.username }}{{ u.active ? '' : ' · 已停用' }}</span>
        </el-option>
      </el-select>
      <el-select v-model="filter.action" clearable placeholder="全部操作" style="width: 160px">
        <el-option-group v-for="g in actionGroups" :key="g.label" :label="g.label">
          <el-option v-for="a in g.items" :key="a.key" :value="a.key" :label="a.text" />
        </el-option-group>
      </el-select>
      <el-select v-model="filter.targetType" clearable placeholder="全部对象" style="width: 140px">
        <el-option v-for="(text, key) in LOG_TARGETS" :key="key" :value="key" :label="text" />
      </el-select>
      <el-input v-model="filter.keyword" clearable :prefix-icon="Search" placeholder="登录名、姓名或对象编号" style="width: 220px" />
      <el-button v-if="hasFilter" link type="primary" @click="clearFilter">清除筛选</el-button>
    </div>

    <div class="page-intro">每次新建、修改、取消、到诊、诊疗确认、开单、季度确认和账号变动都会留下一条记录，记录只能查看，不能修改或删除。点“操作人”或“对象”可以只看相关记录。</div>

    <el-card v-loading="loading" shadow="never" class="table-card">
      <el-table :data="items" row-key="id">
        <template #empty><TableEmpty :error="loadError" :text="hasFilter ? '没有符合条件的操作记录' : '这段时间没有操作记录'" @retry="load" /></template>
        <el-table-column type="expand" width="40">
          <template #default="{ row }">
            <div class="detail">
              <template v-if="isChangeDetail(row.detail)">
                <div class="detail-head">
                  <span>修改前后对比</span>
                  <el-switch v-model="showAll[row.id]" size="small" inline-prompt active-text="全部字段" inactive-text="只看改动" />
                </div>
                <table class="diff">
                  <thead><tr><th>字段</th><th>修改前</th><th>修改后</th></tr></thead>
                  <tbody>
                    <tr v-for="c in changes(row, { all: showAll[row.id], refs })" :key="c.key" :class="{ changed: c.changed }">
                      <td class="f">{{ c.label }}</td>
                      <td class="old"><div v-for="(l, i) in c.before" :key="i">{{ l }}</div></td>
                      <td class="new"><div v-for="(l, i) in c.after" :key="i">{{ l }}</div></td>
                    </tr>
                    <tr v-if="!changes(row, { all: showAll[row.id], refs }).length"><td colspan="3" class="muted">提交的内容与原来相同，没有字段发生变化</td></tr>
                  </tbody>
                </table>
              </template>
              <template v-else-if="fields(row, { refs }).length">
                <div class="detail-head"><span>记录内容</span></div>
                <dl class="kv">
                  <template v-for="f in fields(row, { refs })" :key="f.key">
                    <dt>{{ f.label }}</dt>
                    <dd><div v-for="(l, i) in f.lines" :key="i">{{ l }}</div></dd>
                  </template>
                </dl>
              </template>
              <div v-else class="muted">这条记录没有保存详细内容</div>
              <div class="raw">
                <el-button link size="small" @click="showRaw[row.id] = !showRaw[row.id]">{{ showRaw[row.id] ? '收起原始数据' : '查看原始数据' }}</el-button>
                <pre v-if="showRaw[row.id]">{{ rawText(row) }}</pre>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作时间" width="110">
          <template #default="{ row }">{{ row.occurredAt.slice(0, 10) }}<div class="cell-sub">{{ row.occurredAt.slice(11, 19) }}</div></template>
        </el-table-column>
        <el-table-column label="操作人" width="140">
          <template #default="{ row }">
            <el-button link type="primary" class="who" @click="onlyUser(row)">{{ row.displayName || row.username }}</el-button>
            <div class="cell-sub">{{ row.username }}{{ roleOf(row) ? ` · ${roleOf(row)}` : '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="130">
          <template #default="{ row }">
            <el-tag :type="LOG_ACTIONS[row.action]?.type || 'info'" size="small" effect="light">{{ LOG_ACTIONS[row.action]?.text || row.action }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="对象" width="130">
          <template #default="{ row }">
            <el-tooltip content="只看这条记录的操作" placement="top" :show-after="400">
              <el-button link class="who" @click="onlyTarget(row)"><el-icon class="f-icon"><Filter /></el-icon>{{ targetText(row) }}</el-button>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="摘要" min-width="260">
          <template #default="{ row }"><span class="summary">{{ summary(row, refs) || '—' }}</span></template>
        </el-table-column>
      </el-table>
    </el-card>

    <div class="pager">
      <span class="muted">共 {{ total }} 条 · {{ filter.period[0] }} 至 {{ filter.period[1] }}</span>
      <el-pagination v-model:current-page="page" v-model:page-size="size" :total="total" :page-sizes="[20, 50, 100]" layout="sizes, prev, pager, next" background hide-on-single-page />
    </div>
  </div>
</template>

<style scoped>
.opt-sub {
  float: right;
  margin-left: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.who {
  padding: 0;
  height: auto;
  font-weight: 500;
}
.f-icon {
  margin-right: 4px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.summary {
  line-height: 1.6;
}
.pager {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  font-size: 13px;
}
.detail {
  padding: 4px 24px 12px 56px;
}
.detail-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
  font-weight: 600;
  font-size: 13px;
}
.diff {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.diff th,
.diff td {
  padding: 6px 10px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  text-align: left;
  vertical-align: top;
}
.diff th {
  background: var(--el-fill-color-light);
  font-weight: 500;
  color: var(--el-text-color-secondary);
}
.diff .f {
  width: 150px;
  color: var(--el-text-color-secondary);
}
.diff tr:not(.changed) td {
  color: var(--el-text-color-secondary);
}
.diff tr.changed .old {
  background: #fdf1f0;
  color: #a8433a;
  text-decoration: line-through;
  text-decoration-color: rgba(168, 67, 58, 0.4);
}
.diff tr.changed .new {
  background: #edf7f5;
  color: #0b6560;
  font-weight: 500;
}
.kv {
  display: grid;
  grid-template-columns: 150px 1fr;
  gap: 6px 12px;
  margin: 0;
  font-size: 13px;
}
.kv dt {
  color: var(--el-text-color-secondary);
}
.kv dd {
  margin: 0;
}
.raw {
  margin-top: 8px;
}
.raw pre {
  max-height: 280px;
  overflow: auto;
  margin: 6px 0 0;
  padding: 10px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  font-size: 12px;
  line-height: 1.5;
}
@media (max-width: 720px) {
  .detail {
    padding: 4px 8px 12px;
  }
  .kv {
    grid-template-columns: 100px 1fr;
  }
}
</style>
