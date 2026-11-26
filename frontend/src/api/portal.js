// 患者端接口（官网、小程序共用），对应协作约定第 9.15—9.17 节
import http from './http.js'

/** 9.15 公开资料（免登录） */
export const publicApi = {
  clinics: () => http.get('/public/clinics', { silent: true }),
  dentists: () => http.get('/public/dentists', { silent: true }),
  treatmentItems: () => http.get('/public/treatment-items', { silent: true })
}

/** 9.16 患者注册与登录 */
export const patientAuthApi = {
  smsCode: (phone, purpose) => http.post('/patient-auth/sms-code', { phone, purpose }, { silent: true }),
  register: (form) => http.post('/patient-auth/register', form, { silent: true }),
  login: (phone, password) => http.post('/patient-auth/login', { phone, password }, { silent: true }),
  resetPassword: (form) => http.post('/patient-auth/reset-password', form, { silent: true }),
  me: () => http.get('/patient-auth/me', { silent: true }),
  logout: () => http.post('/patient-auth/logout', null, { silent: true })
}

/** 9.17 预约申请：患者本人 */
export const myRequestApi = {
  list: () => http.get('/patient/appointment-requests'),
  create: (body, requestId) => http.post('/patient/appointment-requests', body, { requestId, silent: true }),
  withdraw: (id, requestId) => http.post(`/patient/appointment-requests/${id}/withdraw`, {}, { requestId })
}

/** 9.17 预约申请：前台处理 */
export const requestApi = {
  /** { status, clinicId, from, to }；status 可传数组 */
  list: ({ status, ...rest } = {}) => {
    const params = { ...rest, status: Array.isArray(status) ? status.join(',') : status }
    return http.get('/appointment-requests', { params: Object.fromEntries(Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== '')) })
  },
  get: (id) => http.get(`/appointment-requests/${id}`),
  invalidate: (id, body, opts = {}) => http.post(`/appointment-requests/${id}/invalidate`, body, { requestId: opts.requestId, silent: true })
}
