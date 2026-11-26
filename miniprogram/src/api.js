// 患者端接口（协作约定第 9.15—9.17 节），与官网 frontend/src/api/portal.js 对应
import { get, post } from './utils/request.js'

export const publicApi = {
  clinics: () => get('/public/clinics', null, { silent: true }),
  dentists: () => get('/public/dentists', null, { silent: true }),
  treatmentItems: () => get('/public/treatment-items', null, { silent: true })
}

export const patientAuthApi = {
  smsCode: (phone, purpose) => post('/patient-auth/sms-code', { phone, purpose }, { silent: true }),
  register: (form) => post('/patient-auth/register', form, { silent: true }),
  login: (phone, password) => post('/patient-auth/login', { phone, password }, { silent: true }),
  resetPassword: (form) => post('/patient-auth/reset-password', form, { silent: true }),
  me: () => get('/patient-auth/me', null, { silent: true }),
  logout: () => post('/patient-auth/logout', null, { silent: true })
}

export const myRequestApi = {
  list: () => get('/patient/appointment-requests'),
  create: (body, requestId) => post('/patient/appointment-requests', body, { requestId, silent: true }),
  withdraw: (id, requestId) => post(`/patient/appointment-requests/${id}/withdraw`, {}, { requestId })
}
