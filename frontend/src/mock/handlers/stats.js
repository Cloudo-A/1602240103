// 模拟：取消就诊统计、治疗需求与资质对照、季度核算（协作约定第 9.10—9.12 节），仅总部可用
import { db, nextId } from '../db.js'
import { invalid, stateNotAllowed, changed } from '../errors.js'
import { toApiDate, toApiDateTime } from '@shared/format.js'
import { roundMoney } from '../seedBusiness.js'
import { appointmentView, appointmentStatus, visitStatus, visitSummary, settlementOf } from './business.js'

const HQ = ['HQ']
const byId = (list, id) => list.find((x) => x.id === Number(id))
const inDays = (dateTime, from, to) => {
  const day = dateTime.slice(0, 10)
  return day >= from && day <= to
}

function periodOf(query) {
  const { from, to } = query
  const errors = []
  if (!from) errors.push({ field: 'from', message: '请选择开始日期' })
  if (!to) errors.push({ field: 'to', message: '请选择结束日期' })
  if (from && to && from > to) errors.push({ field: 'from', message: '开始日期不能晚于结束日期' })
  if (errors.length) throw invalid('请检查统计期间', errors)
  return [from, to]
}

function clinicFilter(query) {
  return query.clinicIds ? String(query.clinicIds).split(',').map(Number).filter(Boolean) : null
}

// ---------- 9.10 取消就诊统计 ----------

function cancellations({ query }) {
  const [from, to] = periodOf(query)
  const ids = clinicFilter(query)
  const clinics = db.clinics.filter((c) => !ids || ids.includes(c.id)).sort((a, b) => a.id - b.id)
  const rows = clinics.map((c) => {
    const list = db.appointments.filter((a) => a.clinicId === c.id && inDays(a.scheduledAt, from, to))
    const total = list.length
    const cancelled = list.filter((a) => a.cancelledAt).length
    const pendingVerify = list.filter((a) => appointmentStatus(a) === 'PENDING_VERIFY').length
    return { clinicId: c.id, clinicName: c.name, total, cancelled, pendingVerify, rate: total ? Math.round((cancelled / total) * 10000) / 10000 : null }
  })
  return { from, to, generatedAt: toApiDateTime(new Date()), periodEnded: to < toApiDate(), rows }
}

function cancellationDetails({ query }) {
  const [from, to] = periodOf(query)
  if (!query.clinicId) throw invalid('请选择诊所', [{ field: 'clinicId', message: '请选择诊所' }])
  return db.appointments
    .filter((a) => a.clinicId === Number(query.clinicId) && inDays(a.scheduledAt, from, to))
    .map(appointmentView)
    .sort((a, b) => a.scheduledAt.localeCompare(b.scheduledAt) || a.id - b.id)
}

// ---------- 9.11 治疗需求与牙医资质对照 ----------

function demandQualification({ query }) {
  const [from, to] = periodOf(query)
  const ids = clinicFilter(query)
  const cells = new Map() // "clinicId-itemId" → { intendedCount, actualQuantity }
  const cell = (clinicId, itemId) => {
    const key = `${clinicId}-${itemId}`
    if (!cells.has(key)) cells.set(key, { clinicId, itemId, intendedCount: 0, actualQuantity: 0 })
    return cells.get(key)
  }
  db.appointments
    .filter((a) => !a.cancelledAt && inDays(a.scheduledAt, from, to) && (!ids || ids.includes(a.clinicId)))
    .forEach((a) => a.itemIds.forEach((itemId) => (cell(a.clinicId, itemId).intendedCount += 1)))
  db.visits
    .filter((v) => ['CONFIRMED', 'SETTLED'].includes(visitStatus(v)) && inDays(v.arrivedAt, from, to) && (!ids || ids.includes(v.clinicId)))
    .forEach((v) => v.treatments.forEach((t) => (cell(v.clinicId, t.itemId).actualQuantity += t.quantity)))

  const demand = [...cells.values()]
    .sort((a, b) => a.clinicId - b.clinicId || a.itemId - b.itemId)
    .map((c) => {
      const item = byId(db.items, c.itemId)
      return {
        clinicId: c.clinicId,
        clinicName: byId(db.clinics, c.clinicId).name,
        itemId: c.itemId,
        itemName: item.name,
        unitName: item.standardUnit,
        intendedCount: c.intendedCount,
        actualQuantity: Math.round(c.actualQuantity * 1e6) / 1e6
      }
    })

  const active = db.dentists.filter((d) => d.active).sort((a, b) => a.id - b.id)
  const qualifications = [...db.qualifications]
    .sort((a, b) => a.id - b.id)
    .map((q) => {
      const dentists = active.filter((d) => d.holdings.some((h) => h.qualificationId === q.id && h.verifiedAt)).map((d) => ({ employeeNo: d.employeeNo, name: d.name }))
      return { qualificationId: q.id, name: q.name, verifiedCount: dentists.length, dentists }
    })
  const incomplete = []
  active.forEach((d) => {
    d.holdings
      .filter((h) => !h.verifiedAt)
      .forEach((h) => incomplete.push({ employeeNo: d.employeeNo, name: d.name, issue: `${byId(db.qualifications, h.qualificationId)?.name || ''}资质待核实` }))
    if (!d.holdings.some((h) => h.verifiedAt)) incomplete.push({ employeeNo: d.employeeNo, name: d.name, issue: '暂无已核实资质' })
  })
  return { demand, qualifications, incomplete }
}

// ---------- 9.12 季度核算 ----------

function quarterRange(year, quarter) {
  const y = Number(year)
  const q = Number(quarter)
  if (!Number.isInteger(y) || y < 2000 || y > 2100) throw invalid('年度不正确', [{ field: 'year', message: '年度不正确' }])
  if (!Number.isInteger(q) || q < 1 || q > 4) throw invalid('季度应为 1—4', [{ field: 'quarter', message: '季度应为 1—4' }])
  const startMonth = (q - 1) * 3 + 1
  const lastDay = new Date(y, startMonth + 2, 0).getDate()
  return { year: y, quarter: q, from: `${y}-${String(startMonth).padStart(2, '0')}-01`, to: `${y}-${String(startMonth + 2).padStart(2, '0')}-${lastDay}` }
}

/** 某位牙医在该季度的收入：按实际到诊日期和实际接诊牙医归属，只算已开结算单的就诊 */
function revenueOf(dentistId, { from, to }) {
  return roundMoney(
    db.visits
      .filter((v) => v.dentistId === dentistId && inDays(v.arrivedAt, from, to))
      .map((v) => settlementOf(v.id))
      .filter(Boolean)
      .reduce((s, st) => s + st.total, 0)
  )
}

/** 从季度结束次日到首次确认当天（两端都算），周一至周五的天数 */
function workdays(afterDay, confirmedAt) {
  const d = new Date(`${afterDay}T00:00:00`)
  d.setDate(d.getDate() + 1)
  const end = confirmedAt.slice(0, 10)
  let count = 0
  while (toApiDate(d) <= end) {
    const w = d.getDay()
    if (w !== 0 && w !== 6) count += 1
    d.setDate(d.getDate() + 1)
  }
  return count
}

function dentistBrief(id) {
  const d = byId(db.dentists, id)
  return { employeeNo: d?.employeeNo || '', name: d?.name || '' }
}

export function closedView(qc) {
  const range = quarterRange(qc.year, qc.quarter)
  return {
    id: qc.id,
    year: qc.year,
    quarter: qc.quarter,
    confirmedAt: qc.confirmedAt,
    firstConfirmedAt: qc.firstConfirmedAt,
    collectionWorkdays: workdays(range.to, qc.firstConfirmedAt),
    companyNetProfit: qc.companyNetProfit,
    incentiveFund: qc.incentiveFund,
    fundStatus: qc.companyNetProfit > 0 ? 'ACCRUED' : 'TRANSFERRED',
    fundTransferAt: qc.fundTransferAt,
    fundNote: qc.fundNote,
    rows: qc.rows
      .map((r) => ({ dentistId: r.dentistId, ...dentistBrief(r.dentistId), revenue: r.revenue, supplyCost: r.supplyCost, operatingCost: r.operatingCost, basis: r.basis, netProfit: roundMoney(r.revenue - r.supplyCost - r.operatingCost) }))
      .sort((a, b) => a.dentistId - b.dentistId)
  }
}

const findClose = (year, quarter) => db.quarterCloses.find((q) => q.year === year && q.quarter === quarter) || null

function prepare(range) {
  const existing = findClose(range.year, range.quarter)
  const dentists = db.dentists.filter((d) => d.active || revenueOf(d.id, range) > 0).sort((a, b) => a.id - b.id)
  const rows = dentists.map((d) => {
    const old = existing?.rows.find((r) => r.dentistId === d.id)
    return { dentistId: d.id, employeeNo: d.employeeNo, name: d.name, revenue: revenueOf(d.id, range), supplyCost: old ? old.supplyCost : null, operatingCost: old ? old.operatingCost : null, basis: old ? old.basis : null }
  })
  const unsettledVisits = db.visits
    .filter((v) => inDays(v.arrivedAt, range.from, range.to) && visitStatus(v) === 'CONFIRMED')
    .map(visitSummary)
    .sort((a, b) => a.arrivedAt.localeCompare(b.arrivedAt))
  return { year: range.year, quarter: range.quarter, quarterEnded: range.to < toApiDate(), existing: existing ? closedView(existing) : null, unsettledVisits, rows }
}

const twoDecimals = (n) => Math.round(n * 100) === n * 100

/** 预览计算：返回结果和字段错误（问题同时转成中文 problems） */
function compute(range, body) {
  const prep = prepare(range)
  const problems = []
  const fieldErrors = []
  const input = Array.isArray(body?.rows) ? body.rows : []
  const inputIds = input.map((r) => Number(r?.dentistId))
  const missing = prep.rows.filter((r) => !inputIds.includes(r.dentistId))
  const extra = inputIds.filter((id) => !prep.rows.some((r) => r.dentistId === id))
  if (missing.length) problems.push(`缺少牙医：${missing.map((r) => r.name).join('、')}`)
  if (extra.length) problems.push(`多出了不在本季度名单里的牙医（编号 ${extra.join('、')}）`)

  let allFilled = true
  const rows = prep.rows.map((p) => {
    const i = input.findIndex((r) => Number(r?.dentistId) === p.dentistId)
    const r = i >= 0 ? input[i] : {}
    const money = (field, label) => {
      const v = r[field]
      if (v === null || v === undefined || v === '') {
        problems.push(`${p.name}的${label}还没填写`)
        if (i >= 0) fieldErrors.push({ field: `rows[${i}].${field}`, message: `请填写${label}` })
        return null
      }
      const n = Number(v)
      if (Number.isNaN(n) || n < 0) {
        problems.push(`${p.name}的${label}不能为负数`)
        if (i >= 0) fieldErrors.push({ field: `rows[${i}].${field}`, message: `${label}不能为负数` })
        return null
      }
      if (!twoDecimals(n)) {
        problems.push(`${p.name}的${label}最多两位小数`)
        if (i >= 0) fieldErrors.push({ field: `rows[${i}].${field}`, message: `${label}最多两位小数` })
        return null
      }
      return n
    }
    const supplyCost = money('supplyCost', '医疗用品成本')
    const operatingCost = money('operatingCost', '运营费用')
    const basis = typeof r.basis === 'string' ? r.basis.trim() : ''
    if (!basis) {
      problems.push(`${p.name}的核对依据还没填写`)
      if (i >= 0) fieldErrors.push({ field: `rows[${i}].basis`, message: '请填写核对依据' })
    }
    const ok = supplyCost !== null && operatingCost !== null
    if (!ok) allFilled = false
    return { dentistId: p.dentistId, employeeNo: p.employeeNo, name: p.name, revenue: p.revenue, supplyCost, operatingCost, basis: basis || null, netProfit: ok ? roundMoney(p.revenue - supplyCost - operatingCost) : null }
  })
  if (prep.unsettledVisits.length) problems.push(`本季度还有 ${prep.unsettledVisits.length} 次已确认但未开结算单的就诊，请前台开单后再确认`)
  if (!prep.quarterEnded) problems.push('季度还没有结束，现在只能预览，不能确认')

  const companyNetProfit = allFilled && !missing.length ? roundMoney(rows.reduce((s, r) => s + r.netProfit, 0)) : null
  const incentiveFund = companyNetProfit !== null && companyNetProfit > 0 ? roundMoney(companyNetProfit * 0.1) : null
  const fundNoteRequired = companyNetProfit !== null && companyNetProfit <= 0
  const checkToken = problems.length ? null : tokenOf(range, rows)
  return { result: { rows, companyNetProfit, incentiveFund, fundNoteRequired, problems, checkToken }, prep, fieldErrors }
}

function tokenOf(range, rows) {
  const text = JSON.stringify([range.year, range.quarter, rows.map((r) => [r.dentistId, r.revenue, r.supplyCost, r.operatingCost, r.basis])])
  let h = 0
  for (let i = 0; i < text.length; i++) h = (h * 31 + text.charCodeAt(i)) >>> 0
  return `mock-q-${h.toString(16)}`
}

function confirmQuarter({ params, body, user }) {
  const range = quarterRange(params.year, params.quarter)
  const { result, prep, fieldErrors } = compute(range, body)
  if (!prep.quarterEnded) throw stateNotAllowed('季度还没有结束，不能确认核算')
  if (prep.unsettledVisits.length) throw stateNotAllowed(`本季度还有 ${prep.unsettledVisits.length} 次已确认但未开结算单的就诊，请前台开单后再确认`)
  const fundNote = typeof body?.fundNote === 'string' ? body.fundNote.trim() : ''
  if (result.fundNoteRequired && !fundNote) fieldErrors.push({ field: 'fundNote', message: '公司净利润不为正，请填写基金处理说明' })
  if (fieldErrors.length || result.problems.length) throw invalid('请检查核算内容', fieldErrors.length ? fieldErrors : null)
  if (!body?.checkToken || body.checkToken !== result.checkToken) throw changed('预览之后收入或填写内容有变化，请重新预览核对', null)

  const now = toApiDateTime(new Date())
  const old = findClose(range.year, range.quarter)
  const record = {
    id: old ? old.id : nextId('quarterClose'),
    year: range.year,
    quarter: range.quarter,
    confirmedAt: now,
    firstConfirmedAt: old ? old.firstConfirmedAt : now,
    companyNetProfit: result.companyNetProfit,
    incentiveFund: result.incentiveFund,
    fundTransferAt: result.companyNetProfit > 0 ? null : now,
    fundNote: fundNote || null,
    confirmedBy: user.displayName,
    rows: result.rows.map(({ dentistId, revenue, supplyCost, operatingCost, basis }) => ({ dentistId, revenue, supplyCost, operatingCost, basis }))
  }
  if (old) db.quarterCloses.splice(db.quarterCloses.indexOf(old), 1, record)
  else db.quarterCloses.push(record)
  return closedView(record)
}

export const statsRoutes = [
  { method: 'GET', path: '/stats/cancellations', roles: HQ, handler: cancellations },
  { method: 'GET', path: '/stats/cancellations/details', roles: HQ, handler: cancellationDetails },
  { method: 'GET', path: '/stats/demand-qualification', roles: HQ, handler: demandQualification },
  { method: 'GET', path: '/quarters', roles: HQ, handler: () => [...db.quarterCloses].sort((a, b) => b.year - a.year || b.quarter - a.quarter).map(closedView) },
  {
    method: 'GET',
    path: '/quarters/:year/:quarter',
    roles: HQ,
    handler: ({ params }) => prepare(quarterRange(params.year, params.quarter))
  },
  {
    method: 'POST',
    path: '/quarters/:year/:quarter/preview',
    roles: HQ,
    handler: ({ params, body }) => compute(quarterRange(params.year, params.quarter), body).result
  },
  { method: 'POST', path: '/quarters/:year/:quarter/confirm', roles: HQ, handler: confirmQuarter }
]
