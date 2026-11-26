<script setup>
// 就诊结算单（可打印）：开具后显示，也用于查询时重新打印原单
import { ref } from 'vue'
import { dateTime, money, quantity } from '@shared/format.js'

defineProps({ settlement: { type: Object, required: true } })

const slipRef = ref()

// 打印样式写成独立的一段，放进临时 iframe 里打印，不受页面其他元素影响
const PRINT_CSS = `
  body { font-family: "PingFang SC", "Microsoft YaHei", sans-serif; color: #1f2d2b; margin: 24px; }
  .slip { max-width: 640px; margin: 0 auto; }
  .slip-title { text-align: center; font-size: 20px; font-weight: 600; letter-spacing: 2px; margin: 0 0 4px; }
  .slip-sub { text-align: center; font-size: 12px; color: #666; margin-bottom: 16px; }
  .slip-meta { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 24px; font-size: 13px; margin-bottom: 14px; }
  .slip-meta span { color: #666; margin-right: 6px; }
  table { width: 100%; border-collapse: collapse; font-size: 13px; }
  th, td { border-bottom: 1px solid #ddd; padding: 7px 4px; text-align: left; }
  th { font-weight: 600; background: #f3f7f6; }
  .num { text-align: right; white-space: nowrap; }
  .slip-total { display: flex; justify-content: flex-end; align-items: baseline; gap: 8px; margin-top: 12px; font-size: 14px; }
  .slip-total b { font-size: 20px; }
  .slip-foot { display: flex; justify-content: space-between; margin-top: 24px; font-size: 12px; color: #666; }
`

function print() {
  const frame = document.createElement('iframe')
  frame.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0'
  document.body.appendChild(frame)
  const doc = frame.contentDocument
  doc.open()
  doc.write(`<!doctype html><html><head><meta charset="utf-8"><title>就诊结算单</title><style>${PRINT_CSS}</style></head><body>${slipRef.value.outerHTML}</body></html>`)
  doc.close()
  frame.contentWindow.focus()
  frame.contentWindow.print()
  setTimeout(() => frame.remove(), 1000)
}

defineExpose({ print })
</script>

<template>
  <div ref="slipRef" class="slip">
    <div class="slip-title">ADC 牙科连锁诊所 · 就诊结算单</div>
    <div class="slip-sub">单号 {{ settlement.settlementNo }}</div>
    <div class="slip-meta">
      <div><span>患者</span>{{ settlement.patient.name }}（{{ settlement.patient.accountNo }}）</div>
      <div><span>就诊诊所</span>{{ settlement.clinic.name }}</div>
      <div><span>接诊牙医</span>{{ settlement.dentist.name }}</div>
      <div><span>开具时间</span>{{ dateTime(settlement.issuedAt) }}</div>
    </div>
    <table>
      <thead>
        <tr>
          <th>治疗项目</th>
          <th class="num">数量</th>
          <th class="num">单价（元）</th>
          <th class="num">小计（元）</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="l in settlement.lines" :key="l.itemId">
          <td>{{ l.itemName }}</td>
          <td class="num">{{ quantity(l.quantity) }} {{ l.unitName }}</td>
          <td class="num">{{ money(l.unitPrice) }}</td>
          <td class="num">{{ money(l.subtotal) }}</td>
        </tr>
      </tbody>
    </table>
    <div class="slip-total">合计 <b>{{ money(settlement.total) }}</b> 元</div>
    <div class="slip-foot">
      <span>开具人：{{ settlement.issuedBy }}</span>
      <span>单价为就诊当日适用的标准价格</span>
    </div>
  </div>
</template>

<style scoped>
.slip-title {
  text-align: center;
  font-size: 18px;
  font-weight: 600;
  letter-spacing: 1px;
}
.slip-sub {
  text-align: center;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin: 4px 0 16px;
}
.slip-meta {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px 24px;
  font-size: 13px;
  margin-bottom: 14px;
}
.slip-meta span {
  color: var(--el-text-color-secondary);
  margin-right: 6px;
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
th,
td {
  border-bottom: 1px solid var(--el-border-color-lighter);
  padding: 8px 4px;
  text-align: left;
}
th {
  font-weight: 600;
  background: #f3f7f6;
}
.num {
  text-align: right;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.slip-total {
  display: flex;
  justify-content: flex-end;
  align-items: baseline;
  gap: 6px;
  margin-top: 12px;
  font-size: 14px;
}
.slip-total b {
  font-size: 22px;
  color: var(--el-color-primary);
}
.slip-foot {
  display: flex;
  justify-content: space-between;
  margin-top: 20px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
