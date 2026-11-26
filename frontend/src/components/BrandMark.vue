<script setup>
// 系统标志：圆角方块里的一颗牙齿（布局、登录页、加载页共用）
defineProps({
  size: { type: Number, default: 36 },
  inverse: { type: Boolean, default: false } // 深色底上用白底青色牙齿
})
</script>

<template>
  <div class="brand-mark" :class="{ inverse }" :style="{ width: size + 'px', height: size + 'px', borderRadius: size * 0.26 + 'px' }" aria-hidden="true">
    <svg :width="size * 0.6" :height="size * 0.6" viewBox="0 0 24 24" fill="none">
      <path
        d="M7.2 3.2c-2.6 0-4.4 2.1-4.2 4.9.1 1.7.8 3 1.4 4.4.6 1.5.8 3.2 1.1 4.9.3 2 .8 3.4 1.9 3.4 1.3 0 1.6-1.7 2-3.6.3-1.4.8-2.6 2.6-2.6s2.3 1.2 2.6 2.6c.4 1.9.7 3.6 2 3.6 1.1 0 1.6-1.4 1.9-3.4.3-1.7.5-3.4 1.1-4.9.6-1.4 1.3-2.7 1.4-4.4.2-2.8-1.6-4.9-4.2-4.9-1.9 0-2.8 1-4.8 1s-2.9-1-4.8-1z"
        fill="currentColor"
      />
    </svg>
  </div>
</template>

<style scoped>
.brand-mark {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-color-primary);
  color: #fff;
}
.brand-mark.inverse {
  background: #fff;
  color: var(--el-color-primary);
}
</style>
