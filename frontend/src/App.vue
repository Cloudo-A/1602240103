<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import BrandMark from '@/components/BrandMark.vue'

// 打开页面时要先向后端核对登录状态，期间显示加载提示，避免白屏
const router = useRouter()
const ready = ref(false)
router.isReady().finally(() => {
  ready.value = true
})
</script>

<template>
  <el-config-provider :locale="zhCn">
    <div v-if="!ready" class="app-loading">
      <BrandMark :size="44" class="app-loading-mark" />
      <div class="app-loading-text">正在加载…</div>
    </div>
    <router-view v-else />
  </el-config-provider>
</template>

<style scoped>
.app-loading {
  height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--el-text-color-secondary);
  font-size: 14px;
}

/* 标志轻微呼吸 */
:deep(.app-loading-mark) {
  animation: pulse 1.2s ease-in-out infinite;
}
@keyframes pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.55;
  }
}
</style>
