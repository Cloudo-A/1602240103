// 预约、就诊、诊疗资料、结算单接口（协作约定第 9.6—9.9 节）
// 写接口都接收 opts：{ requestId, silent }，由 useSubmit 统一传入；
// check、price-lines、settlement-preview 只算不写，不带请求标识（由 http.js 自动判断）
import http from './http.js'

const write = (opts = {}) => ({ requestId: opts.requestId, silent: opts.silent ?? true })
const clean = (params) => Object.fromEntries(Object.entries(params).filter(([, v]) => v !== undefined && v !== null && v !== ''))

// ---------- 9.6 预约 ----------
export const appointmentApi = {
  /** { from, to, clinicId, dentistId, patientId, status }；status 可传数组 */
  list: ({ status, ...rest } = {}) => http.get('/appointments', { params: clean({ ...rest, status: Array.isArray(status) ? status.join(',') : status }) }),
  get: (id) => http.get(`/appointments/${id}`),
  check: (data) => http.post('/appointments/check', data, { silent: true }),
  create: (data, opts) => http.post('/appointments', data, write(opts)),
  cancel: (id, version, opts) => http.post(`/appointments/${id}/cancel`, { version }, write(opts)),
  arrive: (id, data, opts) => http.post(`/appointments/${id}/arrive`, data, write(opts))
}

// ---------- 9.7 诊疗资料 ----------
export const recordApi = {
  get: (patientId) => http.get(`/patients/${patientId}/medical-record`)
}

// ---------- 9.8 诊疗记录 ----------
export const visitApi = {
  /** { from, to, status, dentistId }；status 可传数组 */
  list: ({ status, ...rest } = {}) => http.get('/visits', { params: clean({ ...rest, status: Array.isArray(status) ? status.join(',') : status }) }),
  get: (id) => http.get(`/visits/${id}`),
  priceLines: (id, treatments) => http.post(`/visits/${id}/price-lines`, { treatments }, { silent: true }),
  confirm: (id, data, opts) => http.post(`/visits/${id}/confirm`, data, write(opts)),
  endWithoutTreatment: (id, data, opts) => http.post(`/visits/${id}/end-without-treatment`, data, write(opts))
}

// ---------- 9.9 结算单 ----------
export const settlementApi = {
  preview: (visitId) => http.post(`/visits/${visitId}/settlement-preview`, null, { silent: true }),
  issue: (visitId, checkToken, opts) => http.post(`/visits/${visitId}/settlement`, { checkToken }, write(opts)),
  list: ({ from, to, keyword } = {}) => http.get('/settlements', { params: clean({ from, to, keyword }) }),
  get: (settlementNo) => http.get(`/settlements/${encodeURIComponent(settlementNo)}`)
}
