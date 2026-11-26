// 登录状态：令牌存在 localStorage，当前用户每次打开页面时向后端核对一次
import { reactive, computed } from 'vue'
import * as authApi from '@/api/auth.js'
import { getToken, setToken } from '@/api/http.js'

const USER_KEY = 'adc-user'

function readUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch {
    return null
  }
}

function writeUser(user) {
  try {
    if (user) localStorage.setItem(USER_KEY, JSON.stringify(user))
    else localStorage.removeItem(USER_KEY)
  } catch {
    /* 忽略 */
  }
}

const state = reactive({
  token: getToken(),
  user: readUser(),
  verified: false // 本次打开页面后是否已向后端核对过令牌
})

export const auth = {
  state,
  isLoggedIn: computed(() => Boolean(state.token && state.user)),
  role: computed(() => state.user?.role || null),

  async login(username, password) {
    const result = await authApi.login(username, password)
    setToken(result.token)
    writeUser(result.user)
    state.token = result.token
    state.user = result.user
    state.verified = true
    return result.user
  },

  /** 用已保存的令牌向后端核对当前用户；令牌失效则清除 */
  async verify() {
    if (!state.token) return false
    try {
      const user = await authApi.fetchMe()
      state.user = user
      writeUser(user)
      state.verified = true
      return true
    } catch (e) {
      if (e.status === 401) this.clear()
      else state.verified = false
      throw e
    }
  },

  async logout() {
    try {
      await authApi.logout()
    } catch {
      // 协作约定第 4 节：收到 401（令牌已作废）或网络失败，都照样清除本地令牌
    }
    this.clear()
  },

  clear() {
    setToken(null)
    writeUser(null)
    state.token = null
    state.user = null
    state.verified = false
  }
}
