<template>
  <div class="not-found">
    <el-result icon="info" title="页面不存在" sub-title="地址可能输错了，或该页面已经调整。">
      <template #extra>
        <el-button type="primary" @click="$router.replace('/')">回到首页</el-button>
      </template>
    </el-result>
  </div>
</template>

<style scoped>
.not-found {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--adc-page-bg);
}
</style>
