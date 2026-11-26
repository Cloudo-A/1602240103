// 患者登录状态（协作约定第 9.16 节）：令牌保存在本地存储，7 天有效；与网页的员工令牌无关
import { reactive, computed } from 'vue'

const TOKEN_KEY = 'adc-patient-token'
const PATIENT_KEY = 'adc-patient'

const read = (k) => {
  try { return uni.getStorageSync(k) || null } catch { return null }
}

const state = reactive({ token: read(TOKEN_KEY), patient: read(PATIENT_KEY) })

export const auth = {
  state,
  isLoggedIn: computed(() => Boolean(state.token)),
  save({ token, patient }) {
    state.token = token
    state.patient = patient
    uni.setStorageSync(TOKEN_KEY, token)
    uni.setStorageSync(PATIENT_KEY, patient)
  },
  clear() {
    state.token = null
    state.patient = null
    uni.removeStorageSync(TOKEN_KEY)
    uni.removeStorageSync(PATIENT_KEY)
  }
}

/** 需要登录的操作：未登录时跳到登录页，返回 false */
export function requireLogin(redirect = '') {
  if (state.token) return true
  uni.navigateTo({ url: '/pages/login/login' + (redirect ? '?redirect=' + encodeURIComponent(redirect) : '') })
  return false
}
