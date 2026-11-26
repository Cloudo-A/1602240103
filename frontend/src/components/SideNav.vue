<script setup>
// 侧栏菜单（品牌 + 分组菜单）：宽屏放在左侧栏，窄屏放进抽屉，两处共用
import BrandMark from '@/components/BrandMark.vue'

defineProps({
  groups: { type: Array, required: true },
  active: { type: String, required: true },
})
</script>

<template>
  <div class="side-nav">
    <div class="brand">
      <BrandMark :size="34" />
      <div class="brand-text">
        <div class="brand-name">连锁诊所管理</div>
        <div class="brand-sub">运营管理系统</div>
      </div>
    </div>
    <el-menu :default-active="active" router class="menu">
      <el-menu-item-group v-for="group in groups" :key="group.key">
        <template #title>{{ group.title }}</template>
        <el-menu-item v-for="page in group.pages" :key="page.path" :index="'/' + page.path">
          <el-icon><component :is="page.icon" /></el-icon>
          <span>{{ page.title }}</span>
        </el-menu-item>
      </el-menu-item-group>
    </el-menu>
  </div>
</template>

<style scoped>
.side-nav {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--adc-aside-bg);
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 64px;
  flex-shrink: 0;
  padding: 0 18px;
  border-bottom: 1px solid var(--el-border-color-light);
}
.brand-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.brand-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.menu {
  border-right: none;
  background: transparent;
  flex: 1;
  overflow-y: auto;
  padding: 6px 0;
}
</style>
