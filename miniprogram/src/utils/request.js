// 请求封装（协作约定第 4—6 节）：患者接口带患者令牌；写操作带 X-Request-Id；错误统一成 { status, code, message, fieldErrors }
import { API_BASE } from '../config.js'
import { auth } from './auth.js'

const needsToken = (url) => /^\/patient(-auth)?\//.test(url)

export function request(method, url, data, { requestId, silent = false } = {}) {
  const header = { 'Content-Type': 'application/json' }
  if (needsToken(url) && auth.state.token) header.Authorization = 'Bearer ' + auth.state.token
  if (requestId) header['X-Request-Id'] = requestId
  return new Promise((resolve, reject) => {
    uni.request({
      url: API_BASE + url,
      method,
      data,
      header,
      timeout: 15000,
      success(res) {
        if (res.statusCode >= 200 && res.statusCode < 300) return resolve(res.data)
        const body = (res.data && typeof res.data === 'object') ? res.data : {}
        const err = {
          status: res.statusCode,
          code: body.code || 'HTTP_' + res.statusCode,
          message: body.message || '请求没有成功，请稍后重试',
          fieldErrors: body.fieldErrors || []
        }
        if (res.statusCode === 401 && needsToken(url) && !url.startsWith('/patient-auth/login')) {
          auth.clear()
          err.message = '登录已过期，请重新登录'
        }
        if (!silent) uni.showToast({ title: err.message, icon: 'none', duration: 2500 })
        reject(err)
      },
      fail() {
        const err = { status: 0, code: 'NETWORK', message: '网络连接失败，请检查网络后重试', fieldErrors: [] }
        if (!silent) uni.showToast({ title: err.message, icon: 'none' })
        reject(err)
      }
    })
  })
}

export const get = (url, data, opts) => request('GET', url, data, opts)
export const post = (url, data, opts) => request('POST', url, data, opts)

/** 把后端 fieldErrors 数组转成 { 字段: 提示 } */
export const fieldMap = (err) => Object.fromEntries((err?.fieldErrors || []).map((f) => [f.field, f.message]))
