<script setup>
// 工作台卡片：标题 + 右上角“查看全部”链接；区分加载中、加载失败（可重试）、没有内容
import TableEmpty from '@/components/TableEmpty.vue'

defineProps({
  title: { type: String, required: true },
  /** 右上角链接的目标路由 */
  to: { type: [String, Object], default: null },
  linkText: { type: String, default: '查看全部' },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
  /** 为 true 时显示 emptyText */
  empty: { type: Boolean, default: false },
  emptyText: { type: String, default: '暂时没有' }
})
defineEmits(['retry'])
</script>

<template>
  <el-card shadow="never" class="work-card">
    <template #header>
      <div class="head">
        <span class="title">{{ title }}</span>
        <router-link v-if="to" :to="to" class="link">{{ linkText }} ›</router-link>
      </div>
    </template>
    <div v-loading="loading" class="body">
      <TableEmpty v-if="error || (empty && !loading)" :error="error" :text="emptyText" @retry="$emit('retry')" />
      <slot v-else />
    </div>
  </el-card>
</template>

<style scoped>
.work-card :deep(.el-card__header) {
  padding: 12px 16px;
}
.work-card :deep(.el-card__body) {
  padding: 8px 16px 12px;
}
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.title {
  font-weight: 600;
}
.link {
  font-size: 13px;
  color: var(--el-color-primary);
  text-decoration: none;
}
.link:hover {
  text-decoration: underline;
}
.body {
  min-height: 80px;
}
</style>
