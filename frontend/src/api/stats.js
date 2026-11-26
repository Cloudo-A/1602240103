// 运营分析接口（协作约定第 9.10—9.12 节），仅总部可用
// preview 只算不写，不带请求标识（由 http.js 按路径自动判断）
import http from './http.js'

const ids = (list) => (Array.isArray(list) && list.length ? list.join(',') : undefined)

export const statsApi = {
  cancellations: ({ from, to, clinicIds }) => http.get('/stats/cancellations', { params: { from, to, clinicIds: ids(clinicIds) } }),
  cancellationDetails: ({ clinicId, from, to }) => http.get('/stats/cancellations/details', { params: { clinicId, from, to } }),
  demandQualification: ({ from, to, clinicIds }) => http.get('/stats/demand-qualification', { params: { from, to, clinicIds: ids(clinicIds) } })
}

export const quarterApi = {
  list: () => http.get('/quarters'),
  prepare: (year, quarter) => http.get(`/quarters/${year}/${quarter}`),
  preview: (year, quarter, rows) => http.post(`/quarters/${year}/${quarter}/preview`, { rows }, { silent: true }),
  confirm: (year, quarter, data, opts = {}) => http.post(`/quarters/${year}/${quarter}/confirm`, data, { requestId: opts.requestId, silent: opts.silent ?? true })
}
