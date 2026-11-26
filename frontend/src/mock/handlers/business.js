// 模拟：预约与到诊、诊疗资料、诊疗记录、结算单（协作约定第 9.6—9.9 节）
import { db, nextId } from '../db.js'
import { MockError, notFound, conflict, stateNotAllowed, changed, invalid } from '../errors.js'
import { createChecker, checkVersion } from '../validate.js'
import { toApiDate, toApiDateTime } from '@shared/format.js'
import { priceOn } from './base.js'
import { roundMoney } from '../seedBusiness.js'
import { assertRequestPending, confirmRequest } from './portal.js'

const ALL = ['FRONT_DESK', 'DENTIST', 'HQ']
const FRONT = ['FRONT_DESK']
const DENTIST = ['DENTIST']

const now = () => toApiDateTime(new Date())
const byId = (list, id) => list.find((x) => x.id === Number(id))
const forbidden = (message) => new MockError(403, 'FORBIDDEN', message)

function statusList(query) {
  return query.status ? String(query.status).split(',').map((s) => s.trim()).filter(Boolean) : null
}

/** from/to 都不传时为今天；返回 [from, to]（含两端的日期） */
function dateRange(query, { unlimited = false } = {}) {
  if (!query.from && !query.to) return unlimited ? [null, null] : [toApiDate(), toApiDate()]
  return [query.from || null, query.to || null]
}

function inRange(dateTime, [from, to]) {
  const day = dateTime.slice(0, 10)
  return (!from || day >= from) && (!to || day <= to)
}

// ---------- 视图 ----------

export function appointmentStatus(a) {
  if (a.cancelledAt) return 'CANCELLED'
  if (a.arrived) return 'ARRIVED'
  return a.scheduledAt < now() ? 'PENDING_VERIFY' : 'VALID'
}

export function appointmentView(a) {
  const patient = byId(db.patients, a.patientId)
  const dentist = byId(db.dentists, a.dentistId)
  const clinic = byId(db.clinics, a.clinicId)
  return {
    id: a.id,
    status: appointmentStatus(a),
    scheduledAt: a.scheduledAt,
    patient: { id: patient.id, accountNo: patient.accountNo, name: patient.name },
    dentist: { id: dentist.id, employeeNo: dentist.employeeNo, name: dentist.name },
    clinic: { id: clinic.id, name: clinic.name },
    items: a.itemIds.map((id) => byId(db.items, id)).filter(Boolean).map((i) => ({ id: i.id, name: i.name })),
    arrived: a.arrived,
    cancelledAt: a.cancelledAt,
    cancelledBy: a.cancelledBy,
    visitId: a.visitId,
    version: a.version
  }
}

export const settlementOf = (visitId) => db.settlements.find((s) => s.visitId === visitId) || null

export function visitStatus(v) {
  if (settlementOf(v.id)) return 'SETTLED'
  if (!v.confirmedAt) return 'UNCONFIRMED'
  return v.noTreatmentReason ? 'NO_TREATMENT' : 'CONFIRMED'
}

/** 已保存的治疗 → 计价行（单价是保存时的快照，所以没有 priceEffectiveFrom） */
export function savedLines(v) {
  return v.treatments.map((t) => {
    const item = byId(db.items, t.itemId)
    return { itemId: t.itemId, itemName: item.name, unitName: item.standardUnit, quantity: t.quantity, unitPrice: t.unitPrice, subtotal: roundMoney(t.quantity * t.unitPrice), priceEffectiveFrom: null }
  })
}

const sum = (lines) => roundMoney(lines.reduce((s, l) => s + l.subtotal, 0))

function visitTotal(v) {
  const status = visitStatus(v)
  return status === 'CONFIRMED' || status === 'SETTLED' ? sum(savedLines(v)) : null
}

export function visitSummary(v) {
  const patient = byId(db.patients, v.patientId)
  const clinic = byId(db.clinics, v.clinicId)
  const dentist = byId(db.dentists, v.dentistId)
  return {
    id: v.id,
    status: visitStatus(v),
    arrivedAt: v.arrivedAt,
    patient: { id: patient.id, accountNo: patient.accountNo, name: patient.name },
    clinic: { id: clinic.id, name: clinic.name },
    dentist: { id: dentist.id, name: dentist.name },
    total: visitTotal(v)
  }
}

function allergiesOf(patient) {
  return patient.allergyIds.map((id) => byId(db.allergies, id)).filter(Boolean).map((a) => ({ id: a.id, name: a.name }))
}

export function visitView(v) {
  const patient = byId(db.patients, v.patientId)
  const appt = byId(db.appointments, v.appointmentId)
  const summary = visitSummary(v)
  return {
    id: v.id,
    status: summary.status,
    arrivedAt: v.arrivedAt,
    confirmedAt: v.confirmedAt,
    patient: { ...summary.patient, allergies: allergiesOf(patient), allergyVerifiedAt: patient.allergyVerifiedAt },
    clinic: summary.clinic,
    dentist: summary.dentist,
    intendedItems: (appt?.itemIds || []).map((id) => byId(db.items, id)).filter(Boolean).map((i) => ({ id: i.id, name: i.name })),
    problem: v.problem,
    specialNeeds: v.specialNeeds,
    relatedHistory: v.relatedHistory,
    noTreatmentReason: v.noTreatmentReason,
    treatments: savedLines(v),
    total: summary.total,
    settlementNo: settlementOf(v.id)?.settlementNo || null,
    version: v.version
  }
}

export function settlementView(s) {
  const v = byId(db.visits, s.visitId)
  const patient = byId(db.patients, v.patientId)
  return {
    settlementNo: s.settlementNo,
    visitId: s.visitId,
    issuedAt: s.issuedAt,
    issuedBy: s.issuedBy,
    patient: { accountNo: patient.accountNo, name: patient.name },
    clinic: { name: byId(db.clinics, v.clinicId).name },
    dentist: { name: byId(db.dentists, v.dentistId).name },
    lines: s.lines.map((l) => ({ ...l, priceEffectiveFrom: null })),
    total: s.total
  }
}

// ---------- 9.6 预约 ----------

/** 检查预约请求，返回 { fieldErrors, problems, conflictMessage } */
function inspectAppointment(body) {
  const c = createChecker(body)
  const b = c.data
  const problems = []
  const patient = b.patientId ? byId(db.patients, b.patientId) : null
  const dentist = b.dentistId ? byId(db.dentists, b.dentistId) : null
  const clinic = b.clinicId ? byId(db.clinics, b.clinicId) : null

  if (!b.patientId) c.add('patientId', '请选择患者')
  else if (!patient) c.add('patientId', '患者不存在')
  if (!b.dentistId) c.add('dentistId', '请选择牙医')
  else if (!dentist) c.add('dentistId', '牙医不存在')
  else if (!dentist.active) c.add('dentistId', `${dentist.name}已停用，不能安排新的预约`)
  if (!b.clinicId) c.add('clinicId', '请选择诊所')
  else if (!clinic) c.add('clinicId', '诊所不存在')
  else if (!clinic.active) c.add('clinicId', `${clinic.name}已停用，不能安排新的预约`)

  const at = c.dateTime('scheduledAt', '预约时间')
  // 按分钟比较：等于当前分钟可以（用于登记现场就诊，协作约定 9.6）
  if (at && at < `${now().slice(0, 16)}:00`) c.add('scheduledAt', '预约时间不能早于现在')

  const ids = Array.isArray(b.itemIds) ? b.itemIds.map(Number) : []
  if (!ids.length) c.add('itemIds', '请至少选择一项意向治疗')
  for (const id of ids) {
    const item = byId(db.items, id)
    if (!item) c.add('itemIds', '包含不存在的治疗项目')
    else if (!item.active) c.add('itemIds', `治疗项目“${item.name}”已停用`)
  }

  let conflictMessage = null
  if (at) {
    const same = db.appointments.filter((a) => !a.cancelledAt && a.scheduledAt === at)
    const msgs = []
    if (patient && same.some((a) => a.patientId === patient.id)) msgs.push(`患者${patient.name}在这个时间已有预约`)
    if (dentist && same.some((a) => a.dentistId === dentist.id)) msgs.push(`${dentist.name}医生在这个时间已有预约`)
    if (msgs.length) conflictMessage = msgs.join('；')
  }
  // 字段问题也逐条列入 problems，方便“检查”接口一次说清
  return { checker: c, problems, conflictMessage }
}

function listAppointments({ query, user }) {
  const range = dateRange(query, { unlimited: Boolean(query.patientId) })
  const statuses = statusList(query)
  const dentistId = user.role === 'DENTIST' ? user.dentistId : query.dentistId ? Number(query.dentistId) : null
  return db.appointments
    .filter((a) => inRange(a.scheduledAt, range))
    .filter((a) => !query.clinicId || a.clinicId === Number(query.clinicId))
    .filter((a) => !dentistId || a.dentistId === dentistId)
    .filter((a) => !query.patientId || a.patientId === Number(query.patientId))
    .map(appointmentView)
    .filter((a) => !statuses || statuses.includes(a.status))
    .sort((a, b) => a.scheduledAt.localeCompare(b.scheduledAt) || a.id - b.id)
}

function getAppointment({ params, user }) {
  const a = byId(db.appointments, params.id)
  if (!a) throw notFound('预约不存在')
  if (user.role === 'DENTIST' && a.dentistId !== user.dentistId) throw forbidden('只能查看自己的预约')
  return appointmentView(a)
}

function checkAppointment({ body }) {
  const { checker, conflictMessage } = inspectAppointment(body)
  const problems = []
  try {
    checker.done()
  } catch (e) {
    e.body.fieldErrors.forEach((f) => problems.push(f.message))
  }
  if (conflictMessage) problems.push(conflictMessage)
  return { ok: problems.length === 0, problems }
}

function createAppointment({ body, user }) {
  const { checker, conflictMessage } = inspectAppointment(body)
  checker.done('请检查预约内容')
  // 由预约申请转入（第 9.6 节）：申请必须仍是待确认；预约建不成时申请保持不变
  const request = body?.requestId ? assertRequestPending(body.requestId) : null
  if (conflictMessage) throw conflict(conflictMessage)
  const appt = {
    id: nextId('appointment'),
    patientId: Number(body.patientId),
    dentistId: Number(body.dentistId),
    clinicId: Number(body.clinicId),
    scheduledAt: body.scheduledAt,
    itemIds: [...new Set(body.itemIds.map(Number))],
    arrived: false,
    cancelledAt: null,
    cancelledBy: null,
    visitId: null,
    version: 0
  }
  db.appointments.push(appt)
  if (request) confirmRequest(request, appt.id, user)
  return { __status: 201, data: appointmentView(appt) }
}

function cancelAppointment({ params, body, user }) {
  const a = byId(db.appointments, params.id)
  if (!a) throw notFound('预约不存在')
  if (a.cancelledAt) return appointmentView(a) // 已取消：返回原预约，不重复记录
  if (a.arrived) throw stateNotAllowed('该预约已登记到诊，不能取消')
  checkVersion(body, a, appointmentView)
  a.cancelledAt = now()
  a.cancelledBy = user.displayName
  a.version += 1
  return appointmentView(a)
}

function arriveAppointment({ params, body, user }) {
  const a = byId(db.appointments, params.id)
  if (!a) throw notFound('预约不存在')
  if (a.arrived) return { appointment: appointmentView(a), visitId: a.visitId } // 已到诊：返回原结果
  if (a.cancelledAt) throw stateNotAllowed('该预约已取消，不能登记到诊；请为患者重新安排预约')
  checkVersion(body, a, appointmentView)

  const c = createChecker(body)
  const arrivedAt = c.dateTime('arrivedAt', '到诊时间')
  if (arrivedAt && arrivedAt > now()) c.add('arrivedAt', '到诊时间不能晚于现在')
  const dentistId = body.dentistId ? Number(body.dentistId) : a.dentistId
  const clinicId = body.clinicId ? Number(body.clinicId) : a.clinicId
  const dentist = byId(db.dentists, dentistId)
  const clinic = byId(db.clinics, clinicId)
  if (!dentist) c.add('dentistId', '牙医不存在')
  else if (!dentist.active) c.add('dentistId', `${dentist.name}已停用`)
  if (!clinic) c.add('clinicId', '诊所不存在')
  else if (!clinic.active) c.add('clinicId', `${clinic.name}已停用`)
  c.done('请检查到诊信息')

  const visit = {
    id: nextId('visit'),
    appointmentId: a.id,
    patientId: a.patientId,
    dentistId,
    clinicId,
    arrivedAt,
    registeredAt: now(),
    registeredBy: user.displayName,
    confirmedAt: null,
    problem: null,
    specialNeeds: null,
    relatedHistory: null,
    noTreatmentReason: null,
    treatments: [],
    version: 0
  }
  db.visits.push(visit)
  a.arrived = true
  a.visitId = visit.id
  a.version += 1
  return { appointment: appointmentView(a), visitId: visit.id }
}

// ---------- 9.7 诊疗资料 ----------

function medicalRecord({ params }) {
  const patient = byId(db.patients, params.id)
  if (!patient) throw notFound('患者不存在')
  const visits = db.visits
    .filter((v) => v.patientId === patient.id && v.confirmedAt)
    .sort((a, b) => b.arrivedAt.localeCompare(a.arrivedAt))
    .map((v) => ({
      visitId: v.id,
      arrivedAt: v.arrivedAt,
      status: visitStatus(v),
      clinic: { id: v.clinicId, name: byId(db.clinics, v.clinicId).name },
      dentist: { id: v.dentistId, name: byId(db.dentists, v.dentistId).name },
      problem: v.problem,
      specialNeeds: v.specialNeeds,
      relatedHistory: v.relatedHistory,
      noTreatmentReason: v.noTreatmentReason,
      treatments: savedLines(v).map(({ itemName, unitName, quantity, unitPrice, subtotal }) => ({ itemName, unitName, quantity, unitPrice, subtotal }))
    }))
  return {
    patient: { id: patient.id, accountNo: patient.accountNo, name: patient.name, birthDate: patient.birthDate },
    allergies: allergiesOf(patient),
    allergyVerifiedAt: patient.allergyVerifiedAt,
    visits
  }
}

// ---------- 9.8 诊疗记录 ----------

function findVisit(params, user, { own = false } = {}) {
  const v = byId(db.visits, params.id)
  if (!v) throw notFound('就诊记录不存在')
  if (user.role === 'DENTIST' && v.dentistId !== user.dentistId) {
    throw forbidden(own ? '只有接诊牙医本人可以记录这次诊疗' : '只能查看自己接诊的就诊')
  }
  return v
}

function listVisits({ query, user }) {
  const range = dateRange(query)
  const statuses = statusList(query)
  const dentistId = user.role === 'DENTIST' ? user.dentistId : query.dentistId ? Number(query.dentistId) : null
  return db.visits
    .filter((v) => inRange(v.arrivedAt, range))
    .filter((v) => !dentistId || v.dentistId === dentistId)
    .map(visitSummary)
    .filter((v) => !statuses || statuses.includes(v.status))
    .sort((a, b) => a.arrivedAt.localeCompare(b.arrivedAt) || a.id - b.id)
}

/** 按到诊日期为候选治疗计价；返回字段错误和计价行 */
function priceTreatments(visit, treatments) {
  const day = visit.arrivedAt.slice(0, 10)
  const errors = []
  const lines = []
  const seen = new Set()
  if (!Array.isArray(treatments) || !treatments.length) {
    errors.push({ field: 'treatments', message: '请至少添加一项实际治疗' })
    return { errors, lines }
  }
  treatments.forEach((t, i) => {
    const item = byId(db.items, t?.itemId)
    if (!item) {
      errors.push({ field: `treatments[${i}].itemId`, message: '治疗项目不存在' })
      return
    }
    if (seen.has(item.id)) errors.push({ field: `treatments[${i}].itemId`, message: `“${item.name}”添加了不止一行，请合并为一行` })
    seen.add(item.id)
    const q = Number(t.quantity)
    const scale = item.quantityScale
    if (t.quantity === null || t.quantity === undefined || t.quantity === '' || Number.isNaN(q) || !(q > 0)) {
      errors.push({ field: `treatments[${i}].quantity`, message: `“${item.name}”的数量必须大于 0` })
    } else if (Math.round(q * 10 ** scale) !== q * 10 ** scale) {
      errors.push({ field: `treatments[${i}].quantity`, message: scale === 0 ? `“${item.name}”的数量必须是整数` : `“${item.name}”的数量最多 ${scale} 位小数` })
    }
    const prices = db.prices.filter((p) => p.itemId === item.id && p.effectiveFrom <= day).sort((a, b) => a.effectiveFrom.localeCompare(b.effectiveFrom))
    const price = prices[prices.length - 1]
    if (!price) {
      errors.push({ field: `treatments[${i}].itemId`, message: `“${item.name}”在到诊日期 ${day} 没有适用的标准价格` })
      return
    }
    const quantity = Number.isNaN(q) ? 0 : q
    lines.push({ itemId: item.id, itemName: item.name, unitName: item.standardUnit, quantity, unitPrice: price.unitPrice, subtotal: roundMoney(quantity * price.unitPrice), priceEffectiveFrom: price.effectiveFrom })
  })
  return { errors, lines }
}

function priceLines({ params, body, user }) {
  const v = findVisit(params, user, { own: true })
  const { errors, lines } = priceTreatments(v, body?.treatments)
  return { lines, problems: errors.map((e) => e.message) }
}

function confirmVisit({ params, body, user }) {
  const v = findVisit(params, user, { own: true })
  const status = visitStatus(v)
  if (status === 'SETTLED') throw stateNotAllowed('这次就诊已开具结算单，不能再修改诊疗记录')
  if (status === 'NO_TREATMENT') throw stateNotAllowed('这次就诊已按“未实施治疗”结束，不能再录入治疗')
  checkVersion(body, v, visitView)

  const c = createChecker(body)
  c.text('problem', '患者问题描述', { max: 500 })
  c.text('specialNeeds', '特殊需求', { required: false, max: 500 })
  c.text('relatedHistory', '相关病史', { required: false, max: 500 })
  const allergyIds = body?.allergyIds
  if (!Array.isArray(allergyIds)) c.add('allergyIds', '请核实患者的过敏情况')
  else if (allergyIds.some((id) => !byId(db.allergies, id))) c.add('allergyIds', '包含不存在的过敏项目')
  const { errors } = priceTreatments(v, body?.treatments)
  errors.forEach((e) => c.add(e.field, e.message))
  c.done('请检查诊疗记录')

  // 后端按到诊日期重新取价，整体替换原有明细
  const day = v.arrivedAt.slice(0, 10)
  v.treatments = body.treatments.map((t) => ({ itemId: Number(t.itemId), quantity: Number(t.quantity), unitPrice: priceOn(Number(t.itemId), day) }))
  v.problem = body.problem.trim()
  v.specialNeeds = body.specialNeeds?.trim() || null
  v.relatedHistory = body.relatedHistory?.trim() || null
  v.confirmedAt = now()
  v.version += 1
  const patient = byId(db.patients, v.patientId)
  patient.allergyIds = [...new Set(allergyIds.map(Number))]
  patient.allergyVerifiedAt = now()
  patient.version += 1
  return visitView(v)
}

function endWithoutTreatment({ params, body, user }) {
  const v = findVisit(params, user, { own: true })
  if (visitStatus(v) !== 'UNCONFIRMED') throw stateNotAllowed('只有诊疗未确认的就诊才能按“未实施治疗”结束')
  checkVersion(body, v, visitView)
  const c = createChecker(body)
  c.text('reason', '未实施治疗的原因', { max: 500 })
  c.text('problem', '患者问题描述', { required: false, max: 500 })
  c.done('请填写未实施治疗的原因')
  v.noTreatmentReason = body.reason.trim()
  v.problem = body.problem?.trim() || null
  v.treatments = []
  v.confirmedAt = now()
  v.version += 1
  return visitView(v)
}

// ---------- 9.9 结算单 ----------

function checkTokenOf(v, lines) {
  // 简化的摘要：真实后端会对所依据的数据做哈希
  const text = JSON.stringify([v.id, v.version, lines.map((l) => [l.itemId, l.quantity, l.unitPrice])])
  let h = 0
  for (let i = 0; i < text.length; i++) h = (h * 31 + text.charCodeAt(i)) >>> 0
  return `mock-${v.id}-${v.version}-${h.toString(16)}`
}

function requireSettleable(v) {
  const status = visitStatus(v)
  if (status === 'UNCONFIRMED') throw stateNotAllowed('牙医尚未确认这次诊疗，暂时不能开具结算单')
  if (status === 'NO_TREATMENT') throw stateNotAllowed('这次就诊未实施治疗，不需要开具结算单')
}

function settlementPreview({ params, user }) {
  const v = findVisit(params, user)
  const existing = settlementOf(v.id)
  if (existing) return { existing: true, settlement: settlementView(existing), lines: null, total: null, checkToken: null }
  requireSettleable(v)
  const lines = savedLines(v)
  return { existing: false, settlement: null, lines, total: sum(lines), checkToken: checkTokenOf(v, lines) }
}

function issueSettlement({ params, body, user }) {
  const v = findVisit(params, user)
  const existing = settlementOf(v.id)
  if (existing) return settlementView(existing) // 一诊一单：已有就返回原单
  requireSettleable(v)
  const lines = savedLines(v)
  if (!body?.checkToken) throw invalid('缺少核对标识，请重新预览', [{ field: 'checkToken', message: '缺少核对标识' }])
  if (body.checkToken !== checkTokenOf(v, lines)) throw changed('预览之后诊疗记录有变化，请重新核对费用明细', null)
  const day = toApiDate().replaceAll('-', '')
  const count = db.settlements.filter((s) => s.settlementNo.startsWith(`JS${day}-`)).length
  const settlement = {
    id: nextId('settlement'),
    settlementNo: `JS${day}-${String(count + 1).padStart(4, '0')}`,
    visitId: v.id,
    issuedAt: now(),
    issuedBy: user.displayName,
    lines: lines.map(({ priceEffectiveFrom, ...l }) => l),
    total: sum(lines)
  }
  db.settlements.push(settlement)
  return { __status: 201, data: settlementView(settlement) }
}

function listSettlements({ query }) {
  const range = dateRange(query)
  const k = String(query.keyword || '').trim().toLowerCase()
  return db.settlements
    .filter((s) => inRange(s.issuedAt, range))
    .map(settlementView)
    .filter((s) => !k || s.settlementNo.toLowerCase().includes(k) || s.patient.name.includes(k) || s.patient.accountNo.toLowerCase().includes(k))
    .sort((a, b) => b.issuedAt.localeCompare(a.issuedAt))
}

function getSettlement({ params }) {
  const s = db.settlements.find((x) => x.settlementNo === params.no)
  if (!s) throw notFound('结算单不存在')
  return settlementView(s)
}

export const businessRoutes = [
  { method: 'GET', path: '/appointments', roles: ALL, handler: listAppointments },
  { method: 'POST', path: '/appointments/check', roles: FRONT, handler: checkAppointment },
  { method: 'GET', path: '/appointments/:id', roles: ALL, handler: getAppointment },
  { method: 'POST', path: '/appointments', roles: FRONT, handler: createAppointment },
  { method: 'POST', path: '/appointments/:id/cancel', roles: FRONT, handler: cancelAppointment },
  { method: 'POST', path: '/appointments/:id/arrive', roles: FRONT, handler: arriveAppointment },
  { method: 'GET', path: '/patients/:id/medical-record', roles: DENTIST, handler: medicalRecord },
  { method: 'GET', path: '/visits', roles: ALL, handler: listVisits },
  { method: 'GET', path: '/visits/:id', roles: ALL, handler: ({ params, user }) => visitView(findVisit(params, user)) },
  { method: 'POST', path: '/visits/:id/price-lines', roles: DENTIST, handler: priceLines },
  { method: 'POST', path: '/visits/:id/confirm', roles: DENTIST, handler: confirmVisit },
  { method: 'POST', path: '/visits/:id/end-without-treatment', roles: DENTIST, handler: endWithoutTreatment },
  { method: 'POST', path: '/visits/:id/settlement-preview', roles: FRONT, handler: settlementPreview },
  { method: 'POST', path: '/visits/:id/settlement', roles: FRONT, handler: issueSettlement },
  { method: 'GET', path: '/settlements', roles: FRONT, handler: listSettlements },
  { method: 'GET', path: '/settlements/:no', roles: FRONT, handler: getSettlement }
]
