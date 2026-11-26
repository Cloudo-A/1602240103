import { createApp } from 'vue'
import ElementPlus, { ElMessage } from 'element-plus'
import 'element-plus/dist/index.css'
import './styles/main.css'

import App from './App.vue'
import router from './router/index.js'
import { auth } from './stores/auth.js'
import { setUnauthorizedHandler } from './api/http.js'

// 任何接口返回 401（令牌过期或已作废）：清除登录状态，回到登录页，登录后回到原页面
let redirecting = false
setUnauthorizedHandler(() => {
  if (redirecting) return
  redirecting = true
  auth.clear()
  ElMessage.warning('登录已过期，请重新登录')
  const current = router.currentRoute.value
  router.replace({ name: 'login', query: current.meta.public ? {} : { redirect: current.fullPath } }).finally(() => {
    redirecting = false
  })
})

createApp(App).use(ElementPlus).use(router).mount('#app')
