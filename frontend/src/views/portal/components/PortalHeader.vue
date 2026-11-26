<script setup>
// 官网顶栏 + 导航：首页内点导航平滑滚动；在其他页面点导航回到首页对应位置。右侧为患者登录入口或账号菜单
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import { Phone, Clock, Location, ArrowRight, ArrowDown, Menu, User, Tickets, SwitchButton } from '@element-plus/icons-vue'
import BrandMark from '@/components/BrandMark.vue'
import { patientAuth } from '@/stores/patientAuth.js'
import { SITE } from '../portalData.js'

defineProps({ clinicCount: { type: Number, default: 3 } })

const NAV = [
  { id: 'about', label: '关于我们' },
  { id: 'clinics', label: '门店' },
  { id: 'dentists', label: '医生团队' },
  { id: 'prices', label: '项目价格' },
  { id: 'process', label: '就诊流程' },
  { id: 'faq', label: '常见问题' }
]

const route = useRoute()
const router = useRouter()
const scrolled = ref(false)
const menuOpen = ref(false)
const active = ref('') // 当前所在板块，导航高亮
const progress = ref(0) // 阅读进度 0—1
function onScroll() {
  scrolled.value = window.scrollY > 12
  const max = document.documentElement.scrollHeight - window.innerHeight
  progress.value = max > 0 ? Math.min(1, window.scrollY / max) : 0
  if (route.name !== 'portal') return (active.value = '')
  let cur = ''
  for (const n of NAV) {
    const el = document.getElementById(n.id)
    if (el && el.getBoundingClientRect().top <= 140) cur = n.id
  }
  active.value = cur
}
onMounted(() => {
  window.addEventListener('scroll', onScroll, { passive: true })
  onScroll()
})
onBeforeUnmount(() => window.removeEventListener('scroll', onScroll))

function go(id) {
  menuOpen.value = false
  if (route.name === 'portal') document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  else router.push({ name: 'portal', hash: '#' + id })
}
function openLogin(tab) {
  menuOpen.value = false
  patientAuth.openLogin(tab)
}
function goMine() {
  menuOpen.value = false
  router.push({ name: 'my-requests' })
}
async function onCommand(cmd) {
  if (cmd === 'mine') goMine()
  if (cmd === 'logout') {
    try {
      await ElMessageBox.confirm('确定退出登录吗？', '退出登录', { confirmButtonText: '退出', cancelButtonText: '取消', type: 'warning' })
    } catch {
      return
    }
    await patientAuth.logout()
    ElMessage.success('已退出登录')
    if (route.name === 'my-requests') router.push({ name: 'portal' })
  }
}
</script>

<template>
  <div class="topbar">
    <div class="wrap topbar-inner">
      <span><el-icon><Phone /></el-icon> 预约热线 {{ SITE.hotline }}</span>
      <span class="hide-sm"><el-icon><Clock /></el-icon> {{ SITE.hours }}</span>
      <span class="hide-sm"><el-icon><Location /></el-icon> 广州 · 深圳 {{ clinicCount }} 家门店</span>
      <router-link to="/login" class="staff-link">员工登录 <el-icon><ArrowRight /></el-icon></router-link>
    </div>
  </div>

  <header class="nav" :class="{ scrolled }">
    <span class="read-progress" :style="{ transform: `scaleX(${progress})` }" aria-hidden="true"></span>
    <div class="wrap nav-inner">
      <router-link class="logo" :to="{ name: 'portal' }">
        <BrandMark :size="38" />
        <span class="logo-text">
          <b>{{ SITE.name }}</b>
          <small>ANDREW'S DENTAL CHAIN</small>
        </span>
      </router-link>
      <nav class="nav-links">
        <a v-for="n in NAV" :key="n.id" :href="'/#' + n.id" :class="{ active: active === n.id }" @click.prevent="go(n.id)">{{ n.label }}</a>
      </nav>

      <div class="account hide-md">
        <template v-if="patientAuth.isLoggedIn.value">
          <el-dropdown trigger="click" @command="onCommand">
            <button type="button" class="me">
              <span class="me-avatar">{{ patientAuth.state.patient.name.slice(0, 1) }}</span>
              <span class="me-name">{{ patientAuth.state.patient.name }}</span>
              <el-icon><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>{{ patientAuth.state.patient.phone }}</el-dropdown-item>
                <el-dropdown-item divided :icon="Tickets" command="mine">我的预约</el-dropdown-item>
                <el-dropdown-item :icon="SwitchButton" command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
        <template v-else>
          <button type="button" class="text-btn" @click="openLogin('login')"><el-icon><User /></el-icon>登录 / 注册</button>
        </template>
        <el-button type="primary" round @click="go('booking')">预约到诊</el-button>
      </div>
      <button class="burger" aria-label="打开菜单" @click="menuOpen = true"><el-icon :size="24"><Menu /></el-icon></button>
    </div>
  </header>

  <el-drawer v-model="menuOpen" direction="rtl" size="72%" :with-header="false" class="portal-drawer">
    <div class="drawer-menu">
      <div v-if="patientAuth.isLoggedIn.value" class="drawer-me">
        <span class="me-avatar">{{ patientAuth.state.patient.name.slice(0, 1) }}</span>
        <div><b>{{ patientAuth.state.patient.name }}</b><small>{{ patientAuth.state.patient.phone }}</small></div>
      </div>
      <a v-for="n in NAV" :key="n.id" @click="go(n.id)">{{ n.label }}</a>
      <a class="drawer-cta" @click="go('booking')">预约到诊</a>
      <template v-if="patientAuth.isLoggedIn.value">
        <a @click="goMine">我的预约</a>
        <a class="muted" @click="menuOpen = false; onCommand('logout')">退出登录</a>
      </template>
      <a v-else class="drawer-cta" @click="openLogin('login')">登录 / 注册</a>
      <router-link to="/login" class="muted">员工登录</router-link>
    </div>
  </el-drawer>
</template>

<style scoped>
.topbar {
  background: var(--p-deep);
  color: rgba(255, 255, 255, 0.86);
  font-size: 13px;
}
.topbar-inner {
  display: flex;
  align-items: center;
  gap: 28px;
  height: 38px;
}
.topbar-inner span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.staff-link {
  margin-left: auto;
  color: #fff;
  text-decoration: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  opacity: 0.9;
}
.staff-link:hover {
  opacity: 1;
  text-decoration: underline;
}
.nav {
  position: sticky;
  top: 0;
  z-index: 50;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(10px);
  transition: box-shadow 0.25s;
}
.nav.scrolled {
  box-shadow: 0 6px 24px rgba(16, 60, 56, 0.08);
}
.nav-inner {
  display: flex;
  align-items: center;
  height: 76px;
  gap: 28px;
}
.logo {
  display: flex;
  align-items: center;
  gap: 12px;
  text-decoration: none;
  color: var(--ink);
}
.logo-text {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}
.logo-text b {
  font-size: 19px;
  letter-spacing: 1px;
}
.logo-text small {
  font-size: 10px;
  letter-spacing: 2px;
  color: var(--ink-3);
}
.nav-links {
  display: flex;
  gap: 26px;
  margin-left: auto;
}
.nav-links a {
  color: var(--ink-2);
  text-decoration: none;
  font-size: 15px;
  position: relative;
  padding: 6px 0;
}
.nav-links a::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 2px;
  background: var(--p);
  transform: scaleX(0);
  transition: transform 0.25s;
}
.nav-links a:hover,
.nav-links a.active {
  color: var(--p);
}
.nav-links a:hover::after,
.nav-links a.active::after {
  transform: scaleX(1);
}
.read-progress {
  position: absolute;
  left: 0;
  bottom: -1px;
  height: 2px;
  width: 100%;
  background: linear-gradient(90deg, var(--p), #3bb3a6);
  transform-origin: left;
  pointer-events: none;
}
.account {
  display: flex;
  align-items: center;
  gap: 14px;
}
.text-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: transparent;
  color: var(--ink-2);
  font: inherit;
  font-size: 15px;
  cursor: pointer;
  padding: 6px 4px;
}
.text-btn:hover {
  color: var(--p);
}
.me {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--line);
  background: #fff;
  border-radius: 999px;
  padding: 4px 12px 4px 4px;
  cursor: pointer;
  font: inherit;
  color: var(--ink);
}
.me-avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: var(--p);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  flex-shrink: 0;
}
.me-name {
  font-size: 14px;
  font-weight: 600;
}
.burger {
  display: none;
  margin-left: auto;
  border: none;
  background: transparent;
  color: var(--ink);
  cursor: pointer;
}
.drawer-menu {
  display: flex;
  flex-direction: column;
  padding: 24px 8px;
  gap: 4px;
}
.drawer-menu a {
  padding: 14px 12px;
  font-size: 17px;
  color: var(--ink, #17302d);
  border-bottom: 1px solid #e3ebe9;
  cursor: pointer;
  text-decoration: none;
}
.drawer-menu .drawer-cta {
  color: #0e7c74;
  font-weight: 600;
}
.drawer-menu .muted {
  color: #7b8b88;
  font-size: 15px;
}
.drawer-me {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px 18px;
  border-bottom: 1px solid #e3ebe9;
}
.drawer-me b {
  display: block;
}
.drawer-me small {
  color: #7b8b88;
}
@media (max-width: 1080px) {
  .nav-links {
    gap: 18px;
  }
}
@media (max-width: 960px) {
  .nav-links,
  .hide-md {
    display: none !important;
  }
  .burger {
    display: block;
  }
}
@media (max-width: 600px) {
  .topbar-inner {
    gap: 12px;
  }
  .nav-inner {
    height: 64px;
  }
}
</style>
