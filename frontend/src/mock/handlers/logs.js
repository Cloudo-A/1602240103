// 模拟：操作记录（协作约定第 9.14 节、第 10 节）
// - 写：index.js 在写接口成功后按 LOG_RULES 记一条，detail 与真实后端一致——
//   新建类记接口返回的对象，修改类记 { before, after }，到诊记 { visitId, arrivedAt, registeredAt, dentistId, clinicId }
// - 读：GET /operation-logs，筛选、分页、按时间倒序
// - 示例数据里的历史业务（预约、取消、到诊、诊疗、开单、第 2 季度核算）在第一次请求时补成操作记录
import { db, nextId } from '../db.js'
import { invalid } from '../errors.js'
import { toApiDate, toApiDateTime } from '@shared/format.js'
import { clinicView, dentistView, itemView } from './base.js'
import { patientView } from './patients.js'
import { appointmentView, visitView, settlementView } from './business.js'
import { closedView } from './stats.js'
import { userView } from './users.js'
import { addMinutes } from '../seedBusiness.js'

const byId = (list, id) => list.find((x) => x.id === Number(id))
const clone = (v) => (v === undefined ? undefined : JSON.parse(JSON.stringify(v)))

function push(user, action, targetType, targetId, detail, occurredAt = toApiDateTime(new Date())) {
  db.logs.push({ id: nextId('log'), occurredAt, userId: user.id, username: user.username, action, targetType, targetId: targetId ?? null, detail: clone(detail) ?? null })
}

/** 接口里需要额外再写一条操作记录时用（例如转为预约时的 CONFIRM_APPOINTMENT_REQUEST） */
export function writeLog(user, action, targetType, targetId, detail) {
  if (!Array.isArray(db.logs)) return
  push(user, action, targetType, targetId, detail)
}

// ---------- 写接口 → 操作记录 ----------
// before(params, user)：执行前的视图，有它就记 { before, after }
// done(params)：执行前已经办过（重复取消、重复到诊）→ 接口原样返回，不再记录
// id(result, params, user)、detail(result, ctx)：缺省时取 result.id、result
const LOG_RULES = {
  'POST /clinics': { action: 'CREATE', type: 'CLINIC' },
  'PUT /clinics/:id': { action: 'UPDATE', type: 'CLINIC', before: (p) => clinicView(byId(db.clinics, p.id)) },
  'POST /dentists': { action: 'CREATE', type: 'DENTIST' },
  'PUT /dentists/:id': { action: 'UPDATE', type: 'DENTIST', before: (p) => dentistView(byId(db.dentists, p.id)) },
  'POST /qualifications': { action: 'CREATE', type: 'QUALIFICATION' },
  'POST /treatment-items': { action: 'CREATE', type: 'TREATMENT_ITEM' },
  'PUT /treatment-items/:id': { action: 'UPDATE', type: 'TREATMENT_ITEM', before: (p) => itemView(byId(db.items, p.id)) },
  'POST /treatment-items/:id/prices': { action: 'ADD_STANDARD_PRICE', type: 'TREATMENT_ITEM', before: (p) => itemView(byId(db.items, p.id)) },
  'POST /patients': { action: 'CREATE', type: 'PATIENT' },
  'PUT /patients/:id': { action: 'UPDATE', type: 'PATIENT', before: (p) => patientView(byId(db.patients, p.id)) },
  'POST /appointments': { action: 'CREATE_APPOINTMENT', type: 'APPOINTMENT', detail: (r, { body }) => (body?.requestId ? { ...r, requestId: Number(body.requestId) } : r) },
  'POST /appointments/:id/cancel': { action: 'CANCEL_APPOINTMENT', type: 'APPOINTMENT', done: (p) => Boolean(byId(db.appointments, p.id)?.cancelledAt) },
  'POST /appointments/:id/arrive': {
    action: 'REGISTER_ARRIVAL',
    type: 'APPOINTMENT',
    done: (p) => Boolean(byId(db.appointments, p.id)?.arrived),
    id: (r) => r.appointment.id,
    detail: (r) => arrivalDetail(byId(db.visits, r.visitId))
  },
  'POST /visits/:id/confirm': { action: 'CONFIRM_VISIT', type: 'VISIT', before: (p) => visitView(byId(db.visits, p.id)) },
  'POST /visits/:id/end-without-treatment': { action: 'END_WITHOUT_TREATMENT', type: 'VISIT', before: (p) => visitView(byId(db.visits, p.id)) },
  'POST /visits/:id/settlement': {
    action: 'ISSUE_SETTLEMENT',
    type: 'SETTLEMENT',
    done: (p) => db.settlements.some((s) => s.visitId === Number(p.id)), // 一诊一单：已有就返回原单
    id: (r) => db.settlements.find((s) => s.settlementNo === r.settlementNo)?.id
  },
  'POST /quarters/:year/:quarter/confirm': { action: 'CONFIRM_QUARTER', type: 'QUARTER_CLOSE' },
  'POST /users': { action: 'CREATE_USER', type: 'USER' },
  'PUT /users/:id': { action: 'UPDATE_USER', type: 'USER', before: (p) => userView(byId(db.users, p.id)) },
  'POST /users/:id/reset-password': { action: 'RESET_PASSWORD', type: 'USER' },
  'POST /auth/change-password': {
    action: 'CHANGE_PASSWORD',
    type: 'USER',
    id: (r, p, user) => user.id,
    detail: (r, { user }) => userView(byId(db.users, user.id))
  }
}

function arrivalDetail(v) {
  return { visitId: v.id, arrivedAt: v.arrivedAt, registeredAt: v.registeredAt || addMinutes(v.arrivedAt, 1), dentistId: v.dentistId, clinicId: v.clinicId }
}

/** 写接口执行前调用：返回本次要记的规则和“执行前”的视图；不需要记录时返回 null */
export function prepareLog(method, routePath, params, user) {
  const rule = LOG_RULES[`${method} ${routePath}`]
  if (!rule) return null
  if (rule.done?.(params)) return null
  let before
  try {
    before = rule.before ? clone(rule.before(params, user)) : undefined
  } catch {
    before = undefined // 对象不存在：接口自己会返回 404，不会走到写记录
  }
  return { rule, before }
}

/** 写接口成功后调用 */
export function recordLog(prepared, { params, user, result, body }) {
  if (!prepared) return
  const { rule, before } = prepared
  const targetId = rule.id ? rule.id(result, params, user) : result?.id
  const detail = rule.detail ? rule.detail(result, { params, user, body }) : before !== undefined ? { before, after: result } : result
  push(user, rule.action, rule.type, targetId, detail)
}

// ---------- 示例数据 → 历史操作记录 ----------

/** 第一次用到时（含“重置模拟数据”之后）按示例数据补出历史操作记录 */
export function ensureLogs() {
  if (Array.isArray(db.logs)) return false
  db.logs = []
  db.seq.log = 0
  const now = toApiDateTime(new Date())
  const front = db.users.find((u) => u.username === 'front1')
  const hq = db.users.find((u) => u.username === 'hq1')
  const dentistUser = (dentistId) => db.users.find((u) => u.role === 'DENTIST' && u.dentistId === dentistId)
  const entries = []
  const add = (user, occurredAt, action, targetType, targetId, detail) => {
    if (user && occurredAt <= now) entries.push({ user, occurredAt, action, targetType, targetId, detail: clone(detail) })
  }

  // 基础资料：两次调价、一次修改诊所电话（演示“修改前 / 修改后”对比）
  for (const [itemId, priceId, at] of [[6, 9, '2026-06-25T10:20:00'], [2, 3, '2026-09-20T15:05:00']]) {
    const item = byId(db.items, itemId)
    if (!item) continue
    const after = itemView(item)
    const before = { ...after, version: Math.max(0, item.version - 1), prices: after.prices.filter((p) => p.id !== priceId) }
    before.currentPrice = before.prices.filter((p) => p.effectiveFrom <= at.slice(0, 10)).pop()?.unitPrice ?? null
    add(hq, at, 'ADD_STANDARD_PRICE', 'TREATMENT_ITEM', itemId, { before, after: { ...after, currentPrice: after.prices.filter((p) => p.effectiveFrom <= at.slice(0, 10)).pop()?.unitPrice ?? null } })
  }
  const clinic = byId(db.clinics, 1)
  if (clinic) {
    const after = clinicView(clinic)
    add(hq, '2026-11-19T11:12:30', 'UPDATE', 'CLINIC', 1, { before: { ...after, phone: '020-38880000', managerName: '李华', version: Math.max(0, clinic.version - 1) }, after })
  }

  // 预约：建预约（预约日前三天）、取消、到诊
  for (const a of db.appointments) {
    const view = appointmentView(a)
    const created = `${addMinutes(a.scheduledAt, -3 * 24 * 60).slice(0, 10)}T${String(9 + (a.id % 8)).padStart(2, '0')}:${String((a.id * 7) % 60).padStart(2, '0')}:00`
    add(front, created, 'CREATE_APPOINTMENT', 'APPOINTMENT', a.id, { ...view, status: 'VALID', arrived: false, cancelledAt: null, cancelledBy: null, visitId: null, version: 0 })
    if (a.cancelledAt) add(front, a.cancelledAt, 'CANCEL_APPOINTMENT', 'APPOINTMENT', a.id, { ...view, version: 1 })
    const v = a.visitId ? byId(db.visits, a.visitId) : null
    if (v) {
      const detail = arrivalDetail(v)
      add(front, detail.registeredAt, 'REGISTER_ARRIVAL', 'APPOINTMENT', a.id, detail)
    }
  }

  // 诊疗：确认诊疗 / 未实施治疗结束（只记有登录账号的牙医）
  for (const v of db.visits) {
    if (!v.confirmedAt) continue
    const current = visitView(v)
    const after = { ...current, status: v.noTreatmentReason ? 'NO_TREATMENT' : 'CONFIRMED', settlementNo: null, version: 1 }
    const before = { ...current, status: 'UNCONFIRMED', confirmedAt: null, problem: null, specialNeeds: null, relatedHistory: null, noTreatmentReason: null, treatments: [], total: null, settlementNo: null, version: 0 }
    add(dentistUser(v.dentistId), v.confirmedAt, v.noTreatmentReason ? 'END_WITHOUT_TREATMENT' : 'CONFIRM_VISIT', 'VISIT', v.id, { before, after })
  }

  // 开单、季度核算
  for (const s of db.settlements) add(front, s.issuedAt, 'ISSUE_SETTLEMENT', 'SETTLEMENT', s.id, settlementView(s))
  for (const q of db.quarterCloses) add(hq, q.confirmedAt, 'CONFIRM_QUARTER', 'QUARTER_CLOSE', q.id, closedView(q))

  entries.sort((x, y) => x.occurredAt.localeCompare(y.occurredAt))
  for (const e of entries) push(e.user, e.action, e.targetType, e.targetId, e.detail, e.occurredAt)
  return true
}

// ---------- 9.14 查询 ----------

function listLogs({ query }) {
  const day = /^\d{4}-\d{2}-\d{2}$/
  let from = query.from || ''
  let to = query.to || ''
  const errors = []
  if (from && !day.test(from)) errors.push({ field: 'from', message: '开始日期格式不正确' })
  if (to && !day.test(to)) errors.push({ field: 'to', message: '结束日期格式不正确' })
  if (!from && !to) {
    const today = new Date()
    to = toApiDate(today)
    from = toApiDate(new Date(today.getFullYear(), today.getMonth(), today.getDate() - 6))
  }
  if (from && to && from > to) errors.push({ field: 'from', message: '开始日期不能晚于结束日期' })
  const page = query.page === undefined || query.page === '' ? 0 : Number(query.page)
  const size = query.size === undefined || query.size === '' ? 20 : Number(query.size)
  if (!Number.isInteger(page) || page < 0) errors.push({ field: 'page', message: '页码不能小于0' })
  if (!Number.isInteger(size) || size < 1 || size > 100) errors.push({ field: 'size', message: '每页条数应为1至100' })
  if (errors.length) throw invalid(errors[0].message, errors)

  const userId = query.userId ? Number(query.userId) : null
  const action = String(query.action || '').trim()
  const targetType = String(query.targetType || '').trim().toUpperCase()
  const keyword = String(query.keyword || '').trim()
  const nameOf = (id) => byId(db.users, id)?.displayName || ''

  const rows = db.logs
    .filter((l) => (!from || l.occurredAt.slice(0, 10) >= from) && (!to || l.occurredAt.slice(0, 10) <= to))
    .filter((l) => !userId || l.userId === userId)
    .filter((l) => !action || l.action === action)
    .filter((l) => !targetType || l.targetType === targetType)
    .filter((l) => !keyword || l.username.includes(keyword) || nameOf(l.userId).includes(keyword) || String(l.targetId ?? '').includes(keyword))
    .sort((x, y) => y.occurredAt.localeCompare(x.occurredAt) || y.id - x.id)

  return {
    items: rows.slice(page * size, page * size + size).map((l) => ({ ...l, displayName: nameOf(l.userId) })),
    total: rows.length
  }
}

export const logRoutes = [{ method: 'GET', path: '/operation-logs', roles: ['HQ'], handler: listLogs }]
