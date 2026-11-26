<script setup>
// 工作台顶部的数字卡片：点击跳到对应页面
defineProps({
  label: { type: String, required: true },
  value: { type: [String, Number], default: '—' },
  unit: { type: String, default: '' },
  sub: { type: String, default: '' },
  /** primary / warning / danger / plain */
  tone: { type: String, default: 'plain' },
  to: { type: [String, Object], default: null }
})
</script>

<template>
  <component :is="to ? 'router-link' : 'div'" :to="to" class="tile" :class="[tone, { clickable: to }]">
    <div class="label">{{ label }}</div>
    <div class="value">
      {{ value }}<small v-if="unit">{{ unit }}</small>
    </div>
    <div v-if="sub" class="sub">{{ sub }}</div>
  </component>
</template>

<style scoped>
.tile {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 14px 16px;
  border-radius: var(--adc-card-radius, 10px);
  background: #fff;
  box-shadow: var(--adc-card-shadow);
  border-left: 3px solid transparent;
  text-decoration: none;
  color: inherit;
  transition: transform 0.15s, box-shadow 0.15s;
}
.tile.clickable:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 14px rgba(16, 60, 56, 0.1);
}
.tile.primary {
  border-left-color: var(--el-color-primary);
}
.tile.warning {
  border-left-color: var(--el-color-warning);
}
.tile.danger {
  border-left-color: var(--el-color-danger);
}
.label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.value {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}
.tile.warning .value {
  color: var(--el-color-warning-dark-2);
}
.tile.danger .value {
  color: var(--el-color-danger);
}
.value small {
  font-size: 13px;
  font-weight: 400;
  margin-left: 4px;
  color: var(--el-text-color-secondary);
}
.sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
