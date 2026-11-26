// 系统管理接口：账号管理（协作约定第 9.13 节）、操作记录（第 9.14 节），仅总部可用
import http from './http.js'

const write = (opts = {}) => ({ requestId: opts.requestId, silent: opts.silent ?? true })

export const userApi = {
  list: () => http.get('/users'),
  create: (data, opts) => http.post('/users', data, write(opts)),
  update: (id, data, opts) => http.put(`/users/${id}`, data, write(opts)),
  resetPassword: (id, newPassword, opts) => http.post(`/users/${id}/reset-password`, { newPassword }, write(opts))
}

export const logApi = {
  /** params：{ from, to, userId, action, targetType, keyword, page, size } → { items, total } */
  list: (params) => http.get('/operation-logs', { params })
}
