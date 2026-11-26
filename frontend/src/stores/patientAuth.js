// 患者端登录状态（官网使用；和员工登录状态完全分开）
import { computed, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { getPatientToken, setPatientToken, setPatientUnauthorizedHandler } from '@/api/http.js'
import { patientAuthApi } from '@/api/portal.js'

const PATIENT_KEY = 'adc-patient'

function readPatient() {
  try {
    return JSON.parse(localStorage.getItem(PATIENT_KEY) || 'null')
  } catch {
    return null
  }
}

function writePatient(p) {
  try {
    if (p) localStorage.setItem(PATIENT_KEY, JSON.stringify(p))
    else localStorage.removeItem(PATIENT_KEY)
  } catch {
    /* 浏览器禁止存储时只在本次页面内有效 */
  }
}

const state = reactive({
  token: getPatientToken(),
  patient: readPatient(),
  verified: false,
  loginOpen: false, // 登录/注册弹窗是否打开（任何地方都可以要求登录）
  loginTab: 'login'
})

function apply(result) {
  setPatientToken(result.token)
  writePatient(result.patient)
  state.token = result.token
  state.patient = result.patient
  state.verified = true
}

function clear() {
  setPatientToken(null)
  writePatient(null)
  state.token = null
  state.patient = null
}

// 患者接口返回 401：令牌过期或在别处退出，清除登录状态并请患者重新登录
setPatientUnauthorizedHandler(() => {
  clear()
  ElMessage.warning('登录已过期，请重新登录')
  state.loginTab = 'login'
  state.loginOpen = true
})

export const patientAuth = {
  state,
  isLoggedIn: computed(() => Boolean(state.token && state.patient)),
  openLogin(tab = 'login') {
    state.loginTab = tab
    state.loginOpen = true
  },
  async login(phone, password) {
    apply(await patientAuthApi.login(phone, password))
  },
  async register(form) {
    apply(await patientAuthApi.register(form))
  },
  async logout() {
    try {
      await patientAuthApi.logout()
    } catch {
      /* 令牌已失效也照样清除 */
    }
    clear()
  },
  /** 打开页面时向后端核对一次令牌 */
  async verify() {
    if (!state.token || state.verified) return
    try {
      state.patient = await patientAuthApi.me()
      writePatient(state.patient)
      state.verified = true
    } catch (e) {
      if (e.status === 401) clear()
    }
  }
}
