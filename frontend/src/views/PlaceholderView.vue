<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

// 尚未完成的页面：说明本页将做什么、在第几轮完成
const route = useRoute()
const page = computed(() => route.meta.page)
</script>

<template>
  <el-card shadow="never" class="placeholder">
    <el-empty :image-size="90" :description="`本页面将在第 ${page?.round} 轮完成`">
      <div class="plan">
        <div class="plan-title">{{ page?.title }}（{{ page?.uc }}）将提供：</div>
        <ul>
          <li v-for="item in page?.plan" :key="item">{{ item }}</li>
        </ul>
      </div>
    </el-empty>
  </el-card>
</template>

<style scoped>
.placeholder {
  max-width: 720px;
}
.plan {
  text-align: left;
  color: var(--el-text-color-regular);
  font-size: 14px;
}
.plan-title {
  font-weight: 600;
  margin-bottom: 6px;
}
.plan ul {
  margin: 0;
  padding-left: 20px;
  line-height: 1.9;
}
</style>
