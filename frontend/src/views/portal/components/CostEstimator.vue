<script setup>
// 官网费用估算：按公示标准单价逐行计价，算法与系统开具结算单一致（每行小计四舍五入到分后再相加，报告 11.4）
import { computed, reactive, ref, watch, onBeforeUnmount } from 'vue'
import * as Icons from '@element-plus/icons-vue'

const props = defineProps({ items: { type: Array, default: () => [] } })
const emit = defineEmits(['book'])

const qty = reactive({}) // 项目编号 → 数量
const stepOf = (it) => (it.standardUnit === '毫升' ? 0.5 : 1)
const MAX = 20
const fmtQty = (n) => (Number.isInteger(n) ? String(n) : n.toFixed(1))
const money = (cents) => (cents / 100).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

function add(it) {
  qty[it.id] = Math.min(MAX, (qty[it.id] || 0) + stepOf(it))
}
function sub(it) {
  const n = (qty[it.id] || 0) - stepOf(it)
  if (n <= 0) delete qty[it.id]
  else qty[it.id] = n
}
function clear() {
  Object.keys(qty).forEach((k) => delete qty[k])
}

// 小计（分）：数量最多一位小数、单价两位小数，换成整数相乘后再四舍五入，避免浮点误差
const lineCents = (q, price) => Math.round((Math.round(q * 10) * Math.round(Number(price) * 100)) / 10)
const lines = computed(() =>
  props.items
    .filter((it) => qty[it.id])
    .map((it) => ({ ...it, q: qty[it.id], cents: lineCents(qty[it.id], it.unitPrice) }))
)
const totalCents = computed(() => lines.value.reduce((s, l) => s + l.cents, 0))

// 合计数字平滑滚动
const shown = ref(0)
let raf = 0
watch(totalCents, (to) => {
  cancelAnimationFrame(raf)
  const from = shown.value
  const t0 = performance.now()
  const step = (t) => {
    const k = Math.min(1, (t - t0) / 380)
    shown.value = Math.round(from + (to - from) * (1 - Math.pow(1 - k, 3)))
    if (k < 1) raf = requestAnimationFrame(step)
  }
  raf = requestAnimationFrame(step)
})
onBeforeUnmount(() => cancelAnimationFrame(raf))

// 常见组合（按项目名称匹配，公示里没有的项目自动跳过）
const PRESETS = [
  { label: '常规检查 + 洁牙', icon: 'Brush', set: { 口腔检查: 1, 洁牙: 1 } },
  { label: '补一颗蛀牙', icon: 'FirstAidKit', set: { 口腔检查: 1, 局部麻醉: 1.5, 树脂补牙: 1 } },
  { label: '根管治疗（单根）', icon: 'Aim', set: { 口腔检查: 1, 局部麻醉: 2, 根管治疗: 1 } },
  { label: '拔除智齿', icon: 'Scissor', set: { 口腔检查: 1, 局部麻醉: 2, 拔牙: 1 } }
]
const activePreset = ref('')
function applyPreset(p) {
  clear()
  for (const [name, n] of Object.entries(p.set)) {
    const it = props.items.find((i) => i.name === name)
    if (it) qty[it.id] = n
  }
  activePreset.value = p.label
}
watch(() => ({ ...qty }), () => {
  const p = PRESETS.find((x) => x.label === activePreset.value)
  if (!p) return
  const same = Object.keys(qty).length === Object.keys(p.set).filter((n) => props.items.some((i) => i.name === n)).length &&
    lines.value.every((l) => p.set[l.name] === l.q)
  if (!same) activePreset.value = ''
})

const today = new Date()
const todayText = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
const book = () => emit('book', lines.value.map((l) => l.id))
</script>

<template>
  <div class="estimator">
    <div class="est-pick">
      <div class="est-title">
        <h3>费用估算</h3>
        <p>选择可能要做的项目和数量，按公示单价即时算出参考金额。</p>
      </div>
      <div class="presets">
        <button v-for="p in PRESETS" :key="p.label" type="button" class="preset" :class="{ on: activePreset === p.label }" @click="applyPreset(p)">
          <el-icon><component :is="Icons[p.icon]" /></el-icon>{{ p.label }}
        </button>
      </div>
      <div class="tiles">
        <div v-for="it in items" :key="it.id" class="tile" :class="{ on: qty[it.id] }" @click="!qty[it.id] && add(it)">
          <div class="tile-name">{{ it.name }}</div>
          <div class="tile-price">¥{{ money(Math.round(it.unitPrice * 100)) }}<small> / {{ it.standardUnit }}</small></div>
          <div v-if="qty[it.id]" class="stepper" @click.stop>
            <button type="button" aria-label="减少" @click="sub(it)"><el-icon><Icons.Minus /></el-icon></button>
            <span>{{ fmtQty(qty[it.id]) }}<small>{{ it.standardUnit }}</small></span>
            <button type="button" aria-label="增加" :disabled="qty[it.id] >= MAX" @click="add(it)"><el-icon><Icons.Plus /></el-icon></button>
          </div>
          <div v-else class="tile-add"><el-icon><Icons.Plus /></el-icon>加入</div>
        </div>
      </div>
    </div>

    <div class="slip">
      <div class="slip-top">
        <div>
          <div class="slip-brand">ADC 安德鲁牙科</div>
          <div class="slip-kind">费用估算单</div>
        </div>
        <div class="slip-meta">
          <span>估算日期 {{ todayText }}</span>
          <span>仅供参考 · 非收费凭据</span>
        </div>
      </div>
      <div class="slip-head"><span>项目</span><span>数量</span><span>单价</span><span>小计</span></div>
      <TransitionGroup name="row" tag="div" class="slip-rows">
        <div v-for="l in lines" :key="l.id" class="slip-row">
          <span class="n">{{ l.name }}</span>
          <span>{{ fmtQty(l.q) }} {{ l.standardUnit }}</span>
          <span>{{ money(Math.round(l.unitPrice * 100)) }}</span>
          <span class="s">{{ money(l.cents) }}</span>
        </div>
      </TransitionGroup>
      <div v-if="!lines.length" class="slip-empty">
        <el-icon><Icons.Tickets /></el-icon>
        <span>点左侧项目或常见组合，开始估算</span>
      </div>
      <div class="slip-total">
        <span>参考合计</span>
        <b>¥{{ money(shown) }}</b>
      </div>
      <p class="slip-rule">
        <el-icon><Icons.InfoFilled /></el-icon>
        与系统开具结算单的算法相同：每行小计 = 数量 × 单价，四舍五入到分后再相加。实际费用以牙医记录的治疗和就诊当日价格为准。
      </p>
      <div class="slip-actions">
        <el-button round :disabled="!lines.length" @click="clear(); activePreset = ''">清空</el-button>
        <el-button type="primary" round :disabled="!lines.length" @click="book">
          按这些项目提交预约申请<el-icon class="el-icon--right"><Icons.ArrowRight /></el-icon>
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.estimator {
  margin-top: 72px;
  display: grid;
  grid-template-columns: 1.15fr 1fr;
  gap: 40px;
  align-items: start;
}
.est-title h3 { margin: 0 0 6px; font-size: 26px; font-weight: 800; }
.est-title p { margin: 0 0 18px; color: var(--ink-2); }
.presets { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 20px; }
.preset {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 8px 16px; border-radius: 999px; cursor: pointer;
  border: 1px solid #cfe0dd; background: #fff; color: var(--ink-2); font-size: 14px;
  transition: all 0.2s;
}
.preset:hover { border-color: var(--p); color: var(--p); }
.preset.on { background: var(--p); border-color: var(--p); color: #fff; }
.tiles { display: grid; grid-template-columns: repeat(auto-fill, minmax(170px, 1fr)); gap: 14px; }
.tile {
  position: relative; background: #fff; border-radius: 16px; padding: 16px 16px 14px;
  border: 2px solid transparent; box-shadow: 0 2px 8px rgba(16, 60, 56, 0.05);
  cursor: pointer; transition: border-color 0.2s, box-shadow 0.2s, transform 0.2s;
}
.tile:hover { transform: translateY(-2px); box-shadow: 0 12px 24px rgba(16, 60, 56, 0.1); }
.tile.on { border-color: var(--p); cursor: default; }
.tile-name { font-weight: 700; font-size: 16px; }
.tile-price { color: var(--p-deep); font-weight: 700; font-variant-numeric: tabular-nums; margin: 2px 0 12px; }
.tile-price small { color: var(--ink-3); font-weight: 500; font-size: 12.5px; }
.tile-add { display: inline-flex; align-items: center; gap: 4px; font-size: 13px; color: var(--p); font-weight: 600; }
.stepper { display: flex; align-items: center; justify-content: space-between; background: var(--p-soft); border-radius: 999px; padding: 3px; }
.stepper button {
  width: 30px; height: 30px; border-radius: 50%; border: 0; cursor: pointer;
  background: #fff; color: var(--p-deep); display: grid; place-items: center;
}
.stepper button:disabled { opacity: 0.4; cursor: not-allowed; }
.stepper span { font-weight: 700; font-variant-numeric: tabular-nums; }
.stepper small { font-weight: 500; font-size: 12px; color: var(--ink-3); margin-left: 2px; }

/* 估算单：仿真实结算单的版式 */
.slip {
  position: sticky; top: 96px;
  background: #fff; border-radius: 6px; padding: 26px 28px 22px;
  box-shadow: 0 30px 60px rgba(16, 60, 56, 0.12);
  font-variant-numeric: tabular-nums;
}
.slip::before, .slip::after {
  content: ''; position: absolute; left: 0; right: 0; height: 10px;
  background: radial-gradient(circle at 6px 0, transparent 6px, #fff 6.5px) repeat-x;
  background-size: 12px 10px;
}
.slip::before { top: -10px; transform: rotate(180deg); }
.slip::after { bottom: -10px; }
.slip-top { display: flex; justify-content: space-between; align-items: flex-start; padding-bottom: 14px; border-bottom: 2px solid var(--ink); }
.slip-brand { font-size: 13px; letter-spacing: 2px; color: var(--ink-3); }
.slip-kind { font-size: 22px; font-weight: 800; }
.slip-meta { display: flex; flex-direction: column; align-items: flex-end; font-size: 12px; color: var(--ink-3); line-height: 1.6; }
.slip-head, .slip-row { display: grid; grid-template-columns: 1.4fr 0.8fr 0.8fr 0.9fr; gap: 8px; align-items: baseline; }
.slip-head { font-size: 12.5px; color: var(--ink-3); padding: 12px 0 8px; border-bottom: 1px dashed #cfdcd9; }
.slip-head span:nth-child(n + 2), .slip-row span:nth-child(n + 2) { text-align: right; }
.slip-rows { min-height: 8px; }
.slip-row { padding: 10px 0; border-bottom: 1px dashed #e3ebe9; font-size: 14.5px; }
.slip-row .n { font-weight: 600; }
.slip-row .s { font-weight: 700; }
.slip-empty { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 30px 0 22px; color: var(--ink-3); font-size: 14px; }
.slip-empty .el-icon { font-size: 30px; color: #c3d6d3; }
.slip-total { display: flex; justify-content: space-between; align-items: baseline; padding: 16px 0 6px; }
.slip-total span { font-weight: 600; }
.slip-total b { font-size: 34px; color: var(--p-deep); letter-spacing: 0.5px; }
.slip-rule { display: flex; gap: 6px; font-size: 12.5px; color: var(--ink-3); margin: 6px 0 16px; line-height: 1.6; }
.slip-rule .el-icon { margin-top: 3px; color: var(--p); flex-shrink: 0; }
.slip-actions { display: flex; justify-content: flex-end; gap: 10px; flex-wrap: wrap; }

.row-enter-active, .row-leave-active { transition: all 0.25s ease; }
.row-enter-from, .row-leave-to { opacity: 0; transform: translateX(12px); }

@media (max-width: 900px) {
  .estimator { grid-template-columns: 1fr; gap: 28px; margin-top: 48px; }
  .slip { position: static; }
}
@media (max-width: 600px) {
  .tiles { grid-template-columns: repeat(2, 1fr); gap: 10px; }
  .tile { padding: 12px; }
  .slip { padding: 22px 16px 18px; }
  .slip-head, .slip-row { grid-template-columns: 1.3fr 0.8fr 0.8fr 0.9fr; font-size: 13px; }
  .slip-actions .el-button { flex: 1; }
}
</style>
