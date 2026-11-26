// 模拟：患者档案（协作约定第 9.5 节）。前台可写，牙医只读。
import { db, nextId } from '../db.js'
import { notFound, conflict } from '../errors.js'
import { createChecker, checkVersion } from '../validate.js'
import { toApiDate } from '@shared/format.js'

const READ = ['FRONT_DESK', 'DENTIST']
const WRITE = ['FRONT_DESK']

export function patientSummary(p) {
  return { id: p.id, accountNo: p.accountNo, name: p.name, birthDate: p.birthDate, phone: p.phone }
}

export function patientView(p) {
  const { allergyIds, ...rest } = p
  return { ...rest, allergies: allergyIds.map((id) => db.allergies.find((a) => a.id === id)).filter(Boolean).map((a) => ({ ...a })) }
}

function checkPatient(body) {
  const c = createChecker(body)
  c.text('name', '姓名', { max: 60 })
  c.date('birthDate', '出生日期')
  c.text('billingAddress', '账单地址')
  c.text('phone', '电话', { max: 30 })
  c.email('email', '电子邮箱', { required: false })
  c.text('emergencyName', '紧急联系人姓名', { max: 60 })
  c.text('emergencyPhone', '紧急联系人电话', { max: 30 })
  const ids = body?.allergyIds
  if (ids !== undefined && !Array.isArray(ids)) c.add('allergyIds', '过敏项目格式不正确')
  else if ((ids || []).some((id) => !db.allergies.some((a) => a.id === Number(id)))) c.add('allergyIds', '包含不存在的过敏项目')
  if (body?.birthDate && body.birthDate > toApiDate()) c.add('birthDate', '出生日期不能晚于今天')
  c.done()
}

function patientFields(body) {
  const email = typeof body.email === 'string' ? body.email.trim() : ''
  return {
    name: body.name.trim(),
    birthDate: body.birthDate,
    billingAddress: body.billingAddress.trim(),
    phone: body.phone.trim(),
    email: email || null,
    emergencyName: body.emergencyName.trim(),
    emergencyPhone: body.emergencyPhone.trim(),
    allergyIds: [...new Set((body.allergyIds || []).map(Number))]
  }
}

export const patientRoutes = [
  {
    method: 'GET',
    path: '/patients',
    roles: READ,
    handler({ query }) {
      const keyword = String(query.keyword || '').trim().toLowerCase()
      const page = Math.max(0, Number(query.page) || 0)
      const size = Math.min(100, Math.max(1, Number(query.size) || 20))
      const list = db.patients
        .filter((p) => !keyword || p.name.toLowerCase().includes(keyword) || p.phone.includes(keyword) || p.accountNo.toLowerCase().includes(keyword))
        .sort((a, b) => a.accountNo.localeCompare(b.accountNo))
      return { items: list.slice(page * size, page * size + size).map(patientSummary), total: list.length }
    }
  },
  {
    method: 'GET',
    path: '/patients/:id',
    roles: READ,
    handler({ params }) {
      const p = db.patients.find((x) => x.id === Number(params.id))
      if (!p) throw notFound('患者档案不存在')
      return patientView(p)
    }
  },
  {
    method: 'POST',
    path: '/patients',
    roles: WRITE,
    handler({ body }) {
      checkPatient(body)
      const fields = patientFields(body)
      // 疑似重复：姓名相同，且出生日期相同或电话相同
      const candidates = db.patients.filter((p) => p.name === fields.name && (p.birthDate === fields.birthDate || p.phone === fields.phone))
      if (candidates.length && body.confirmNotDuplicate !== true) {
        throw conflict(`已有 ${candidates.length} 位同名且出生日期或电话相同的患者，请核对是否为同一人`, candidates.map(patientSummary))
      }
      const id = nextId('patient')
      const patient = { id, accountNo: 'P' + String(id).padStart(6, '0'), ...fields, allergyVerifiedAt: null, version: 0 }
      db.patients.push(patient)
      return { __status: 201, data: patientView(patient) }
    }
  },
  {
    method: 'PUT',
    path: '/patients/:id',
    roles: WRITE,
    handler({ params, body }) {
      const patient = db.patients.find((x) => x.id === Number(params.id))
      if (!patient) throw notFound('患者档案不存在')
      checkVersion(body, patient, patientView)
      checkPatient(body)
      // 过敏核实时间只由牙医在记录诊疗时写入，前台修改不改变它
      Object.assign(patient, patientFields(body), { version: patient.version + 1 })
      return patientView(patient)
    }
  }
]
