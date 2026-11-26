<script setup>
// 统计期间：快捷选择（本月、上月、本季度、上季度、今年）+ 自定义日期范围；v-model 为 [from, to]
import { computed } from 'vue'
import { toApiDate } from '@shared/format.js'

const model = defineModel({ type: Array, required: true })

function monthRange(offset) {
  const now = new Date()
  const start = new Date(now.getFullYear(), now.getMonth() + offset, 1)
  const end = new Date(now.getFullYear(), now.getMonth() + offset + 1, 0)
  return [toApiDate(start), toApiDate(end)]
}
function quarterRange(offset) {
  const now = new Date()
  const q = Math.floor(now.getMonth() / 3) + offset
  const start = new Date(now.getFullYear(), q * 3, 1)
  const end = new Date(now.getFullYear(), q * 3 + 3, 0)
  return [toApiDate(start), toApiDate(end)]
}
const presets = [
  { key: 'month', label: '本月', range: () => monthRange(0) },
  { key: 'lastMonth', label: '上月', range: () => monthRange(-1) },
  { key: 'quarter', label: '本季度', range: () => quarterRange(0) },
  { key: 'lastQuarter', label: '上季度', range: () => quarterRange(-1) },
  { key: 'year', label: '今年', range: () => [`${new Date().getFullYear()}-01-01`, `${new Date().getFullYear()}-12-31`] }
]
const active = computed(() => presets.find((p) => p.range().join() === (model.value || []).join())?.key || '')

defineExpose({ presets })
</script>

<template>
  <div class="period">
    <el-radio-group :model-value="active" @update:model-value="(k) => (model = presets.find((p) => p.key === k).range())">
      <el-radio-button v-for="p in presets" :key="p.key" :value="p.key">{{ p.label }}</el-radio-button>
    </el-radio-group>
    <el-date-picker v-model="model" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" :clearable="false" style="width: 250px" />
  </div>
</template>

<style scoped>
.period {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
</style>
