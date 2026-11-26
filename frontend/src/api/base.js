// 下拉选项和基础资料接口（协作约定第 9.1—9.5 节）
// 写接口都接收 opts：{ requestId, silent }，由 useSubmit 统一传入
import http from './http.js'

const write = (opts = {}) => ({ requestId: opts.requestId, silent: opts.silent ?? true })

// ---------- 9.1 下拉选项 ----------
export const options = {
  clinics: (activeOnly = true) => http.get('/options/clinics', { params: { activeOnly } }),
  dentists: (activeOnly = true) => http.get('/options/dentists', { params: { activeOnly } }),
  treatmentItems: (activeOnly = true) => http.get('/options/treatment-items', { params: { activeOnly } }),
  allergies: () => http.get('/options/allergies'),
  qualifications: () => http.get('/options/qualifications')
}

// ---------- 9.2 诊所 ----------
export const clinicApi = {
  list: () => http.get('/clinics'),
  get: (id) => http.get(`/clinics/${id}`),
  create: (data, opts) => http.post('/clinics', data, write(opts)),
  update: (id, data, opts) => http.put(`/clinics/${id}`, data, write(opts))
}

// ---------- 9.3 牙医与资质 ----------
export const dentistApi = {
  list: () => http.get('/dentists'),
  get: (id) => http.get(`/dentists/${id}`),
  create: (data, opts) => http.post('/dentists', data, write(opts)),
  update: (id, data, opts) => http.put(`/dentists/${id}`, data, write(opts)),
  createQualification: (name, opts) => http.post('/qualifications', { name }, write(opts))
}

// ---------- 9.4 治疗项目与价格 ----------
export const itemApi = {
  list: () => http.get('/treatment-items'),
  get: (id) => http.get(`/treatment-items/${id}`),
  create: (data, opts) => http.post('/treatment-items', data, write(opts)),
  update: (id, data, opts) => http.put(`/treatment-items/${id}`, data, write(opts)),
  addPrice: (id, data, opts) => http.post(`/treatment-items/${id}/prices`, data, write(opts))
}

// ---------- 9.5 患者档案 ----------
export const patientApi = {
  search: ({ keyword = '', page = 0, size = 20 } = {}) => http.get('/patients', { params: { keyword: keyword || undefined, page, size } }),
  get: (id) => http.get(`/patients/${id}`),
  create: (data, opts) => http.post('/patients', data, write(opts)),
  update: (id, data, opts) => http.put(`/patients/${id}`, data, write(opts))
}
