<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { SwitchButton, ArrowDown, Lock, Expand } from '@element-plus/icons-vue'
import { auth } from '@/stores/auth.js'
import { MENU_GROUPS, pagesForRole } from '@/config/pages.js'
import { roleName } from '@shared/labels.js'
import { USE_MOCK } from '@/api/http.js'
import SideNav from '@/components/SideNav.vue'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'

const route = useRoute()
const router = useRouter()

const user = computed(() => auth.state.user)

/** 当前岗位能看到的菜单，按分组排列 */
const menuGroups = computed(() => {
  const groups = []
  for (const page of pagesForRole(auth.role.value)) {
    let group = groups.find((g) => g.key === page.group)
    if (!group) {
      group = { key: page.group, title: MENU_GROUPS[page.group], pages: [] }
      groups.push(group)
    }
    group.pages.push(page)
  }
  return groups
})

const activePath = computed(() => route.path)
const currentPage = computed(() => route.meta.page || null)
const groupTitle = computed(() => (currentPage.value ? MENU_GROUPS[currentPage.value.group] : ''))
const avatarText = computed(() => (user.value?.displayName || '?').slice(0, 1))

// 窄屏（手机、竖放的小平板）：侧栏收进抽屉，点左上角按钮打开，换页后自动收起
const narrowQuery = window.matchMedia('(max-width: 767px)')
const narrow = ref(narrowQuery.matches)
const drawerOpen = ref(false)
function onNarrowChange(e) {
  narrow.value = e.matches
  if (!e.matches) drawerOpen.value = false
}
narrowQuery.addEventListener('change', onNarrowChange)
onBeforeUnmount(() => narrowQuery.removeEventListener('change', onNarrowChange))
watch(() => route.fullPath, () => { drawerOpen.value = false })

const passwordOpen = ref(false)
function onCommand(command) {
  if (command === 'logout') onLogout()
  else if (command === 'password') passwordOpen.value = true
}

async function onLogout() {
  try {
    await ElMessageBox.confirm('确定退出登录吗？', '退出登录', { confirmButtonText: '退出', cancelButtonText: '取消', type: 'warning' })
  } catch {
    return
  }
  await auth.logout()
  router.replace({ name: 'login' })
}
</script>

<template>
  <el-container class="layout">
    <el-aside v-if="!narrow" width="216px" class="aside">
      <SideNav :groups="menuGroups" :active="activePath" />
    </el-aside>
    <el-drawer v-else v-model="drawerOpen" direction="ltr" size="232px" :with-header="false" class="nav-drawer">
      <SideNav :groups="menuGroups" :active="activePath" />
    </el-drawer>

    <el-container>
      <el-header class="header" :class="{ narrow }">
        <div class="header-left">
          <button v-if="narrow" type="button" class="menu-toggle" aria-label="打开菜单" @click="drawerOpen = true">
            <el-icon :size="20"><Expand /></el-icon>
          </button>
          <div class="title-block">
            <div v-if="groupTitle" class="crumb">{{ groupTitle }}</div>
            <div class="title-line">
              <span class="page-title">{{ route.meta.title }}</span>
              <el-tag v-if="currentPage?.uc" size="small" effect="plain" round class="uc-tag">{{ currentPage.uc }}</el-tag>
            </div>
          </div>
        </div>
        <div class="header-right">
          <el-tag v-if="USE_MOCK" type="warning" effect="light" size="small">模拟数据</el-tag>
          <el-dropdown trigger="click" @command="onCommand">
            <button type="button" class="user-entry" aria-label="用户菜单">
              <span class="avatar">{{ avatarText }}</span>
              <span class="user-text">
                <span class="user-name">{{ user?.displayName }}</span>
                <span class="user-role">{{ roleName(user?.role) }}</span>
              </span>
              <el-icon><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>登录名：{{ user?.username }}</el-dropdown-item>
                <el-dropdown-item divided :icon="Lock" command="password">修改密码</el-dropdown-item>
                <el-dropdown-item :icon="SwitchButton" command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="main" :class="{ narrow }">
        <router-view />
      </el-main>
    </el-container>
    <ChangePasswordDialog v-model="passwordOpen" />
  </el-container>
</template>

<style scoped>
.layout {
  height: 100vh;
}
.aside {
  border-right: 1px solid var(--el-border-color-light);
}
.menu-toggle {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  margin-left: -6px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--el-text-color-primary);
  cursor: pointer;
}
.menu-toggle:hover,
.menu-toggle:focus-visible {
  background: var(--el-fill-color-light);
  outline: none;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 64px;
  background: #fff;
  border-bottom: 1px solid var(--el-border-color-light);
  padding: 0 24px;
}
.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}
.title-block {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.crumb {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.title-line {
  display: flex;
  align-items: center;
  gap: 8px;
}
.uc-tag {
  font-family: Consolas, 'SFMono-Regular', monospace;
}
.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}
.user-text {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.3;
}
.page-title {
  font-size: 17px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}
.user-entry {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  border: none;
  background: transparent;
  font: inherit;
  padding: 6px 8px;
  border-radius: 6px;
}
.user-entry:hover,
.user-entry:focus-visible {
  background: var(--el-fill-color-light);
  outline: none;
}
.user-name {
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.user-name {
  font-size: 14px;
}
.user-role {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.main {
  background: var(--adc-page-bg);
  padding: 20px 24px;
}
.header.narrow {
  padding: 0 12px;
  height: 56px;
}
.header.narrow .crumb,
.header.narrow .uc-tag,
.header.narrow .user-text {
  display: none;
}
.header.narrow .header-right {
  gap: 8px;
}
.main.narrow {
  padding: 12px;
}
</style>

<style>
/* 抽屉挂在 body 下，带作用域的样式够不到，这里单独写 */
.nav-drawer .el-drawer__body {
  padding: 0;
}
</style>
