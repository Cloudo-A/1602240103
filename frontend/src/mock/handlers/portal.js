// 模拟：患者端（协作约定第 9.15 公开资料、9.16 患者注册登录、9.17 预约申请）
import { db, nextId } from '../db.js'
import { MockError, invalid, notFound, stateNotAllowed, conflict } from '../errors.js'
import { checkVersion } from '../validate.js'
import { toApiDate, toApiDateTime } from '@shared/format.js'
import { newRequestId } from '@shared/requestId.js'
import { priceOn } from './base.js'
import { appointmentStatus } from './business.js'
import { writeLog } from './logs.js'

const DEMO_CODE = '123456' // 课程演示：验证码统一为 123456
const TOKEN_DAYS = 7
const PHONE_RE = /^1\d{10}$/
const SLOTS = ['MORNING', 'AFTERNOON', 'EVENING']
const MAX_PENDING = 3

const now = () => toApiDateTime(new Date())
const byId = (list, id) => list.find((x) => x.id === Number(id))
const field = (f, message) => invalid(message, [{ field: f, message }])

// ---------- 9.15 公开资料 ----------

function publicClinics() {
  return db.clinics.filter((c) => c.active).map((c) => ({ id: c.id, name: c.name, postalAddress: c.postalAddress, phone: c.phone }))
}

function publicDentists() {
  return db.dentists
    .filter((d) => d.active)
    .sort((a, b) => a.employeeNo.localeCompare(b.employeeNo))
    .map((d) => ({
      id: d.id,
      name: d.name,
      employeeNo: d.employeeNo,
      qualifications: d.holdings
        .filter((h) => h.verifiedAt)
        .map((h) => byId(db.qualifications, h.qualificationId)?.name)
        .filter(Boolean)
        .sort((a, b) => a.localeCompare(b, 'zh'))
    }))
}

function publicItems() {
  return db.items
    .filter((i) => i.active)
    .map((i) => ({ id: i.id, name: i.name, standardUnit: i.standardUnit, unitPrice: priceOn(i.id) }))
    .filter((i) => i.unitPrice !== null && i.unitPrice !== undefined)
}

// ---------- 9.16 患者注册与登录 ----------

const accountView = (a) => ({ id: a.id, phone: a.phone, name: a.name, createdAt: a.createdAt })

function issueToken(account) {
  const token = 'p' + newRequestId().replace(/-/g, '')
  const expiresAt = toApiDateTime(new Date(Date.now() + TOKEN_DAYS * 86400 * 1000))
  db.patientTokens[token] = { accountId: account.id, expiresAt }
  return { token, expiresAt, patient: accountView(account) }
}

function checkPhone(phone) {
  if (!PHONE_RE.test(String(phone || ''))) throw field('phone', '请输入 11 位手机号')
}

function checkPassword(password, name) {
  const p = String(password || '')
  if (p.length < 6 || p.length > 64) throw field(name, '密码为 6—64 个字符')
  if (new TextEncoder().encode(p).length > 72) throw field(name, '密码太长（最多 72 个字节，约 24 个汉字）')
}

function checkCode(code) {
  if (String(code || '') !== DEMO_CODE) throw field('code', '验证码不正确')
}

const findAccount = (phone) => db.patientAccounts.find((a) => a.phone === phone)

function smsCode({ body }) {
  checkPhone(body?.phone)
  const purpose = body?.purpose
  if (!['REGISTER', 'RESET_PASSWORD'].includes(purpose)) throw field('purpose', '用途不正确')
  if (purpose === 'REGISTER' && findAccount(body.phone)) throw conflict('该手机号已注册，请直接登录')
  if (purpose === 'RESET_PASSWORD' && !findAccount(body.phone)) throw notFound('该手机号尚未注册')
  return { sent: true, message: '课程演示：验证码统一为 123456' }
}

function register({ body }) {
  checkPhone(body?.phone)
  checkCode(body?.code)
  const name = String(body?.name || '').trim()
  if (!name || name.length > 20) throw field('name', '请填写姓名（最多 20 个字）')
  checkPassword(body?.password, 'password')
  if (findAccount(body.phone)) throw conflict('该手机号已注册，请直接登录')
  const account = { id: nextId('patientAccount'), phone: body.phone, name, password: body.password, createdAt: now() }
  db.patientAccounts.push(account)
  return { __status: 201, data: issueToken(account) }
}

function login({ body }) {
  const account = findAccount(String(body?.phone || ''))
  if (!account || account.password !== body?.password) throw new MockError(401, 'UNAUTHENTICATED', '手机号或密码不正确')
  return issueToken(account)
}

function resetPassword({ body }) {
  checkPhone(body?.phone)
  checkCode(body?.code)
  checkPassword(body?.newPassword, 'newPassword')
  const account = findAccount(body.phone)
  if (!account) throw notFound('该手机号尚未注册')
  account.password = body.newPassword
  Object.keys(db.patientTokens).forEach((t) => db.patientTokens[t].accountId === account.id && delete db.patientTokens[t])
  return { ok: true }
}

// ---------- 9.17 预约申请 ----------

export function requestView(r) {
  const clinic = byId(db.clinics, r.clinicId)
  const appt = r.appointmentId ? byId(db.appointments, r.appointmentId) : null
  return {
    id: r.id,
    status: r.status,
    createdAt: r.createdAt,
    contactName: r.contactName,
    contactPhone: r.contactPhone,
    clinic: { id: clinic.id, name: clinic.name, phone: clinic.phone },
    preferredDate: r.preferredDate,
    timeSlot: r.timeSlot,
    items: r.itemIds.map((id) => byId(db.items, id)).filter(Boolean).map((i) => ({ id: i.id, name: i.name })),
    note: r.note || '',
    handledAt: r.handledAt,
    handledBy: r.handledBy,
    invalidReason: r.invalidReason,
    withdrawnAt: r.withdrawnAt,
    appointment: appt
      ? {
          id: appt.id,
          status: appointmentStatus(appt),
          scheduledAt: appt.scheduledAt,
          dentist: { id: appt.dentistId, name: byId(db.dentists, appt.dentistId)?.name },
          clinic: { id: appt.clinicId, name: byId(db.clinics, appt.clinicId)?.name }
        }
      : null,
    version: r.version
  }
}

const newestFirst = (a, b) => b.createdAt.localeCompare(a.createdAt) || b.id - a.id

function myRequests({ patient }) {
  return db.appointmentRequests.filter((r) => r.accountId === patient.id).sort(newestFirst).map(requestView)
}

function createRequest({ body, patient }) {
  const errors = []
  const clinic = byId(db.clinics, body?.clinicId)
  if (!clinic || !clinic.active) errors.push({ field: 'clinicId', message: '请选择营业中的门店' })
  const today = toApiDate()
  const last = new Date()
  last.setDate(last.getDate() + 30)
  const date = String(body?.preferredDate || '')
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || date < today || date > toApiDate(last)) errors.push({ field: 'preferredDate', message: '期望日期须在今天起 30 天内' })
  if (!SLOTS.includes(body?.timeSlot)) errors.push({ field: 'timeSlot', message: '请选择时段' })
  const itemIds = [...new Set((body?.itemIds || []).map(Number))]
  if (itemIds.some((id) => !byId(db.items, id)?.active)) errors.push({ field: 'itemIds', message: '所选项目不存在或已停用' })
  if (String(body?.note || '').length > 200) errors.push({ field: 'note', message: '补充说明最多 200 字' })
  if (errors.length) throw invalid('请检查预约内容', errors)
  const pending = db.appointmentRequests.filter((r) => r.accountId === patient.id && r.status === 'PENDING').length
  if (pending >= MAX_PENDING) throw stateNotAllowed(`您已有 ${MAX_PENDING} 条待确认的预约，请等待前台联系或撤回后再提交`)
  const r = {
    id: nextId('appointmentRequest'),
    accountId: patient.id,
    contactName: patient.name,
    contactPhone: patient.phone,
    clinicId: clinic.id,
    preferredDate: date,
    timeSlot: body.timeSlot,
    itemIds,
    note: String(body?.note || '').trim(),
    createdAt: now(),
    status: 'PENDING',
    handledAt: null,
    handledBy: null,
    invalidReason: null,
    withdrawnAt: null,
    appointmentId: null,
    version: 0
  }
  db.appointmentRequests.push(r)
  return { __status: 201, data: requestView(r) }
}

function withdrawRequest({ params, patient }) {
  const r = byId(db.appointmentRequests, params.id)
  if (!r || r.accountId !== patient.id) throw notFound('预约记录不存在')
  if (r.status === 'WITHDRAWN') return requestView(r)
  if (r.status !== 'PENDING') throw stateNotAllowed('前台已处理，如需取消请致电门店')
  r.status = 'WITHDRAWN'
  r.withdrawnAt = now()
  r.version += 1
  return requestView(r)
}

function listRequests({ query }) {
  let from = query.from || null
  let to = query.to || null
  if (!from && !to) {
    const d = new Date()
    d.setDate(d.getDate() - 30)
    from = toApiDate(d)
  }
  const statuses = query.status ? String(query.status).split(',').filter(Boolean) : null
  return db.appointmentRequests
    .filter((r) => (!from || r.createdAt.slice(0, 10) >= from) && (!to || r.createdAt.slice(0, 10) <= to))
    .filter((r) => !statuses || statuses.includes(r.status))
    .filter((r) => !query.clinicId || r.clinicId === Number(query.clinicId))
    .sort(newestFirst)
    .map(requestView)
}

function getRequest({ params }) {
  const r = byId(db.appointmentRequests, params.id)
  if (!r) throw notFound('预约申请不存在')
  return requestView(r)
}

function invalidateRequest({ params, body, user }) {
  const r = byId(db.appointmentRequests, params.id)
  if (!r) throw notFound('预约申请不存在')
  if (r.status !== 'PENDING') throw stateNotAllowed('该预约申请已被处理')
  const reason = String(body?.reason || '').trim()
  if (!reason || reason.length > 200) throw field('reason', '请填写无效原因（最多 200 字）')
  checkVersion(body, r, requestView)
  const before = requestView(r)
  r.status = 'INVALID'
  r.invalidReason = reason
  r.handledAt = now()
  r.handledBy = user.displayName
  r.version += 1
  const after = requestView(r)
  writeLog(user, 'INVALIDATE_APPOINTMENT_REQUEST', 'APPOINTMENT_REQUEST', r.id, { before, after })
  return after
}

/** 新建预约带 requestId 时：创建前检查（第 9.6 节） */
export function assertRequestPending(requestId) {
  const r = byId(db.appointmentRequests, requestId)
  if (!r) throw notFound('预约申请不存在')
  if (r.status !== 'PENDING') throw stateNotAllowed('该预约申请已被处理')
  return r
}

/** 新建预约成功后：把预约申请改为已确认并关联预约，另写一条操作记录 */
export function confirmRequest(r, appointmentId, user) {
  const before = requestView(r)
  r.status = 'CONFIRMED'
  r.appointmentId = appointmentId
  r.handledAt = now()
  r.handledBy = user.displayName
  r.version += 1
  const after = requestView(r)
  writeLog(user, 'CONFIRM_APPOINTMENT_REQUEST', 'APPOINTMENT_REQUEST', r.id, { before, after })
}

const FRONT = ['FRONT_DESK']

export const portalRoutes = [
  { method: 'GET', path: '/public/clinics', public: true, handler: publicClinics },
  { method: 'GET', path: '/public/dentists', public: true, handler: publicDentists },
  { method: 'GET', path: '/public/treatment-items', public: true, handler: publicItems },
  { method: 'POST', path: '/patient-auth/sms-code', public: true, handler: smsCode },
  { method: 'POST', path: '/patient-auth/register', public: true, handler: register },
  { method: 'POST', path: '/patient-auth/login', public: true, handler: login },
  { method: 'POST', path: '/patient-auth/reset-password', public: true, handler: resetPassword },
  { method: 'GET', path: '/patient-auth/me', patient: true, handler: ({ patient }) => accountView(patient) },
  { method: 'POST', path: '/patient-auth/logout', patient: true, handler: ({ patient }) => { delete db.patientTokens[patient.token]; return null } },
  { method: 'GET', path: '/patient/appointment-requests', patient: true, handler: myRequests },
  { method: 'POST', path: '/patient/appointment-requests', patient: true, handler: createRequest },
  { method: 'POST', path: '/patient/appointment-requests/:id/withdraw', patient: true, handler: withdrawRequest },
  { method: 'GET', path: '/appointment-requests', roles: FRONT, handler: listRequests },
  { method: 'GET', path: '/appointment-requests/:id', roles: FRONT, handler: getRequest },
  { method: 'POST', path: '/appointment-requests/:id/invalidate', roles: FRONT, handler: invalidateRequest }
]
