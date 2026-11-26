import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { auth } from '@/stores/auth.js'
import { pages, HOME_BY_ROLE } from '@/config/pages.js'
import MainLayout from '@/layouts/MainLayout.vue'

const routes = [
  // 官网首页（面向患者，免登录）；员工从右上角“员工登录”进入后台
  { path: '/', name: 'portal', component: () => import('@/views/portal/PortalHome.vue'), meta: { public: true, portal: true } },
  { path: '/my/appointments', name: 'my-requests', component: () => import('@/views/portal/MyRequests.vue'), meta: { public: true, portal: true, title: '我的预约' } },
  { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true, title: '登录' } },
  {
    path: '/',
    component: MainLayout,
    children: [
      ...pages.map((p) => ({ path: p.path, name: p.name, component: p.component, meta: { title: p.title, roles: p.roles, page: p } })),
      { path: 'forbidden', name: 'forbidden', component: () => import('@/views/ForbiddenView.vue'), meta: { title: '无权访问' } }
    ]
  },
  { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/NotFoundView.vue'), meta: { public: true, title: '页面不存在' } }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  // 官网页面之间跳转回到顶部；带 #板块 的由首页自己平滑滚动
  scrollBehavior(to, from, saved) {
    if (saved) return saved
    if (to.hash) return false
    return { top: 0 }
  }
})

router.beforeEach(async (to) => {
  if (to.meta.public) {
    // 已登录再打开登录页，直接回到自己的首页
    if (to.name === 'login' && auth.isLoggedIn.value) return HOME_BY_ROLE[auth.role.value]
    return true
  }

  if (!auth.isLoggedIn.value) {
    return { name: 'login', query: to.fullPath !== '/' ? { redirect: to.fullPath } : {} }
  }

  // 每次打开页面后第一次跳转时，向后端核对令牌是否仍有效
  if (!auth.state.verified) {
    try {
      await auth.verify()
    } catch (e) {
      if (e.status === 401) {
        ElMessage.warning('登录已过期，请重新登录')
        return { name: 'login', query: { redirect: to.fullPath } }
      }
      // 网络问题：先用本地保存的用户信息继续，下一次跳转再核对
    }
  }

  const roles = to.meta.roles
  if (roles && !roles.includes(auth.role.value)) return { name: 'forbidden' }
  return true
})

router.afterEach((to, from, failure) => {
  if (failure) return // 离开被取消（例如“还没保存”时选了继续编辑），标题保持原页面
  if (to.meta.portal) {
    document.title = to.meta.title ? `${to.meta.title} · ADC 安德鲁牙科` : 'ADC 安德鲁牙科 · 一份病历，连锁通用'
    return
  }
  document.title = to.meta.title ? `${to.meta.title} · ADC 牙科连锁诊所运营管理系统` : 'ADC 牙科连锁诊所运营管理系统'
})

export default router
