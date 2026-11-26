// 登录相关接口（协作约定第 4 节）
import http from './http.js'

/** 登录：返回 { token, expiresAt, user }。错误由页面自己显示，所以 silent */
export function login(username, password) {
  return http.post('/auth/login', { username, password }, { silent: true })
}

/** 当前用户；令牌无效时后端返回 401 */
export function fetchMe() {
  return http.get('/auth/me', { silent: true })
}

/** 退出：后端立即作废令牌。200 和 401 都视为已退出 */
export function logout() {
  return http.post('/auth/logout', null, { silent: true })
}

/** 修改自己的密码（协作约定 9.13）；opts 由 useSubmit 传入请求标识 */
export function changePassword(oldPassword, newPassword, opts = {}) {
  return http.post('/auth/change-password', { oldPassword, newPassword }, { requestId: opts.requestId, silent: opts.silent ?? true })
}
