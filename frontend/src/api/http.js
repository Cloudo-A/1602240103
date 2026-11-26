// 统一的接口请求封装（对应协作约定第 4、5、6 节）
// - 自动带令牌 Authorization: Bearer <token>
// - 写操作自动带 X-Request-Id（登录、退出和“只算不写”的接口除外）
// - 把后端错误统一成 ApiError，默认弹出 message
// - 收到 401 时清除登录状态并回到登录页
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { newRequestId } from '@shared/requestId.js'

export const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

const TOKEN_KEY = 'adc-token'
const PATIENT_TOKEN_KEY = 'adc-patient-token' // 患者端（官网、小程序）的令牌，和员工令牌分开保存

/** 后端返回的错误，字段与协作约定第 5 节一致 */
export class ApiError extends Error {
  constructor({ status, code, message, fieldErrors = null, current = null, uncertain = false }) {
    super(message)
    this.name = 'ApiError'
    this.status = status // HTTP 状态码；网络错误为 0
    this.code = code // INVALID_INPUT / CHANGED / CONFLICT / ... / NETWORK
    this.fieldErrors = fieldErrors
    this.current = current
    this.uncertain = uncertain // 写操作因网络问题不知道是否成功（界面显示“结果待核实”）
  }
}

// 不带请求标识的写接口（协作约定第 6 节第 3 条）
const NO_REQUEST_ID = [/^\/auth\/login$/, /^\/auth\/logout$/, /^\/patient-auth\//, /\/check$/, /\/price-lines$/, /\/settlement-preview$/, /\/preview$/]

export function getToken() {
  try {
    return localStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

export function setToken(token) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token)
    else localStorage.removeItem(TOKEN_KEY)
  } catch {
    /* 浏览器禁止存储时只在本次页面内有效 */
  }
}

// 接口归属：患者端接口带患者令牌，公开接口不带令牌，其余带员工令牌（协作约定第 4 节）
const isPatientUrl = (url = '') => /^\/patient(-auth)?\//.test(url)
const isPublicUrl = (url = '') => /^\/public\//.test(url)

export function getPatientToken() {
  try {
    return localStorage.getItem(PATIENT_TOKEN_KEY)
  } catch {
    return null
  }
}

export function setPatientToken(token) {
  try {
    if (token) localStorage.setItem(PATIENT_TOKEN_KEY, token)
    else localStorage.removeItem(PATIENT_TOKEN_KEY)
  } catch {
    /* 同上 */
  }
}

let onPatientUnauthorized = () => {}
/** 由患者端登录状态注入：患者接口返回 401 时清除患者登录状态 */
export function setPatientUnauthorizedHandler(fn) {
  onPatientUnauthorized = fn
}

let onUnauthorized = () => {}
/** 由 main.js 注入：收到 401 时怎么处理（清登录状态、跳登录页） */
export function setUnauthorizedHandler(fn) {
  onUnauthorized = fn
}

const http = axios.create({ baseURL: '/api', timeout: 15000 })
// 模拟后端只在开发模式按需加载；正式打包时这段条件恒为假，模拟代码不会进入打包文件
if (import.meta.env.VITE_USE_MOCK === 'true') {
  http.defaults.adapter = async (config) => (await import('@/mock/index.js')).mockAdapter(config)
}

http.interceptors.request.use((config) => {
  const token = isPublicUrl(config.url) ? null : isPatientUrl(config.url) ? getPatientToken() : getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  const method = (config.method || 'get').toLowerCase()
  if ((method === 'post' || method === 'put') && !NO_REQUEST_ID.some((re) => re.test(config.url))) {
    // 调用方可传入 requestId，用于网络超时后用同一个标识重试
    config.headers['X-Request-Id'] = config.requestId || newRequestId()
  }
  return config
})

http.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const config = error.config || {}
    const method = (config.method || 'get').toLowerCase()
    let apiError

    const body = error.response?.data
    const isApiBody = body && typeof body === 'object' && typeof body.code === 'string'

    const isObjectBody = body && typeof body === 'object'

    if (error.response && !isObjectBody && error.response.status >= 500) {
      // 返回的根本不是 JSON：通常是后端没启动，开发服务器或 Nginx 代为返回 500/502/504。
      // （后端自己出错时返回的是 JSON，即使不带 code，也走下面的“系统错误”）
      apiError = new ApiError({
        status: error.response.status,
        code: 'BACKEND_UNAVAILABLE',
        message: '无法连接后端服务，请确认后端已经启动',
        uncertain: method !== 'get'
      })
    } else if (error.response) {
      apiError = new ApiError({
        status: error.response.status,
        code: isApiBody ? body.code : `HTTP_${error.response.status}`,
        message: (isApiBody && body.message) || defaultMessage(error.response.status),
        fieldErrors: (isApiBody && body.fieldErrors) || null,
        current: isApiBody ? body.current ?? null : null
      })
    } else {
      // 没收到响应：断网、超时或后端未启动
      const isWrite = method !== 'get'
      apiError = new ApiError({
        status: 0,
        code: 'NETWORK',
        message: isWrite
          ? '网络连接失败或超时，操作结果待核实，请刷新确认后再决定是否重试'
          : '网络连接失败或超时，请检查网络或后端是否已启动',
        uncertain: isWrite
      })
    }

    // 登录、退出、核对当前用户由调用方自己处理 401，不走统一跳转
    const isAuthCall = /^\/(patient-)?auth\/(login|logout|me)$/.test(config.url || '')
    if (apiError.status === 401 && !isAuthCall && isPatientUrl(config.url)) {
      onPatientUnauthorized(apiError)
    } else if (apiError.status === 401 && !isAuthCall) {
      onUnauthorized(apiError)
    } else if (!config.silent) {
      ElMessage({ type: 'error', message: apiError.message, grouping: true, duration: 4000 })
    }
    return Promise.reject(apiError)
  }
)

function defaultMessage(status) {
  if (status === 403) return '当前岗位无权办理此业务'
  if (status === 404) return '要查找的记录不存在'
  if (status >= 500) return '系统错误，请稍后重试'
  return '请求未成功，请稍后重试'
}

export default http
