<script setup>
// 表格空状态：区分“没有记录”和“加载失败”（报告 Q4：无记录与查询失败分别反馈，失败时可重试）
import { WarningFilled } from '@element-plus/icons-vue'

defineProps({
  /** 加载失败的原因；为空表示加载成功但没有记录 */
  error: { type: String, default: '' },
  /** 没有记录时的提示 */
  text: { type: String, default: '没有记录' }
})
defineEmits(['retry'])
</script>

<template>
  <div class="table-empty">
    <template v-if="error">
      <div class="err"><el-icon><WarningFilled /></el-icon> 加载失败：{{ error }}</div>
      <el-button size="small" type="primary" plain @click="$emit('retry')">重新加载</el-button>
    </template>
    <template v-else>
      <div class="muted">{{ text }}</div>
      <slot />
    </template>
  </div>
</template>

<style scoped>
.table-empty {
  padding: 24px 0;
  line-height: 1.8;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}
.err {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--el-color-danger);
}
</style>
