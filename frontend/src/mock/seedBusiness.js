// 模拟数据：预约、就诊、实际治疗、结算单、季度核算（协作约定第 11 节的业务部分）
// - 2026 年 7—11 月的历史记录：含取消、待核实（过期未到诊）、未实施治疗结束，
//   以及一条第 3 季度“已确认未开单”的就诊（演示“有未开单就诊不能确认季度核算”）
// - 今天的几条预约：日期按生成模拟数据的当天计算，方便演示到诊、诊疗和开单
import { toApiDate } from '@shared/format.js'

// [预约时间, 患者, 牙医, 诊所, 意向项目, 结果, 实际治疗 [项目, 数量], 未实施治疗原因]
// 结果：S 已结算  K 已确认未开单  N 未实施治疗结束  C 患者取消  P 过期未到诊（待核实）
const HISTORY = [
  ['2026-07-03T09:00:00', 1, 1, 1, [1, 2], 'S', [[1, 1], [2, 1]]],
  ['2026-07-03T10:00:00', 3, 1, 1, [4], 'S', [[1, 1], [4, 2], [6, 1.5]]],
  ['2026-07-08T14:00:00', 2, 2, 2, [2], 'C'],
  ['2026-07-10T09:30:00', 5, 2, 2, [3], 'S', [[3, 2]]],
  ['2026-07-15T11:00:00', 7, 3, 3, [1], 'S', [[1, 1]]],
  ['2026-07-22T15:00:00', 4, 4, 3, [1, 5], 'P'],
  ['2026-07-29T10:00:00', 6, 1, 1, [5], 'S', [[5, 1], [6, 2]]],
  ['2026-08-05T09:00:00', 8, 2, 2, [1], 'C'],
  ['2026-08-12T14:30:00', 1, 2, 2, [3], 'S', [[3, 1]]],
  ['2026-08-20T15:00:00', 5, 3, 3, [7], 'S', [[7, 1]]],
  ['2026-08-26T10:00:00', 3, 4, 3, [1], 'N', [], '患者血压偏高，建议控制后再来'],
  ['2026-09-02T09:00:00', 2, 1, 1, [2], 'C'],
  ['2026-09-09T11:30:00', 4, 2, 2, [3, 6], 'S', [[3, 1], [6, 1]]],
  ['2026-09-12T10:00:00', 1, 1, 1, [1], 'S', [[1, 1]]],
  ['2026-09-18T16:00:00', 6, 4, 3, [1], 'P'],
  ['2026-09-25T14:00:00', 7, 3, 3, [2], 'C'],
  ['2026-09-29T10:30:00', 8, 1, 1, [4], 'K', [[4, 1], [6, 1]]],
  ['2026-10-08T09:30:00', 2, 2, 2, [2], 'S', [[2, 1]]],
  ['2026-10-14T14:00:00', 5, 1, 1, [3], 'S', [[3, 1]]],
  ['2026-10-20T10:00:00', 3, 3, 3, [1], 'C'],
  ['2026-10-27T15:30:00', 6, 2, 2, [5], 'S', [[5, 1], [6, 1.5]]],
  ['2026-11-03T09:00:00', 4, 4, 3, [2], 'P'],
  ['2026-11-06T11:00:00', 7, 1, 1, [4], 'S', [[4, 1]]],
  ['2026-11-12T14:30:00', 1, 2, 2, [7], 'C'],
  ['2026-11-17T10:00:00', 8, 3, 3, [1], 'N', [], '患者临时身体不适，改日再诊'],
  ['2026-11-18T16:00:00', 3, 1, 1, [2], 'S', [[2, 1]]]
]

// 今天：[时间, 患者, 牙医, 诊所, 意向项目, 结果, 实际治疗]
// A 已到诊、诊疗未确认   K 已确认待结算   V 有效预约
const TODAY = [
  ['09:00', 1, 1, 1, [1, 2], 'A'],
  ['09:30', 5, 2, 2, [3], 'K', [[3, 1]]],
  ['10:30', 3, 1, 1, [4], 'V'],
  ['14:00', 7, 2, 2, [2], 'V'],
  ['15:30', 2, 1, 1, [1], 'V'],
  ['16:00', 4, 3, 3, [1], 'V']
]

// 已确认的季度核算：2026 年第 2 季度（系统 7 月启用，二季度没有结算收入，只有运营费用，
// 公司净利润为负，激励基金转总部处理）。第 3 季度不确认，留给演示。
const QUARTER_CLOSES = [
  {
    year: 2026,
    quarter: 2,
    confirmedAt: '2026-07-08T16:00:00',
    firstConfirmedAt: '2026-07-08T16:00:00',
    fundNote: '第二季度系统尚未启用，各诊所只发生筹备期运营费用，没有可分配利润，激励基金转总部统一处理。',
    rows: [
      { dentistId: 1, revenue: 0, supplyCost: 0, operatingCost: 1800, basis: '天河诊所筹备期运营费用，按财务台账 2026-Q2' },
      { dentistId: 2, revenue: 0, supplyCost: 0, operatingCost: 1500, basis: '越秀诊所筹备期运营费用，按财务台账 2026-Q2' },
      { dentistId: 3, revenue: 0, supplyCost: 0, operatingCost: 1200, basis: '福田诊所筹备期运营费用（按人头分摊），按财务台账 2026-Q2' },
      { dentistId: 4, revenue: 0, supplyCost: 0, operatingCost: 1000, basis: '福田诊所筹备期运营费用（按人头分摊），按财务台账 2026-Q2' }
    ]
  }
]

function pad(n) {
  return String(n).padStart(2, '0')
}

/** 在 "YYYY-MM-DDTHH:mm:ss" 上加减分钟（按本地时间） */
export function addMinutes(dateTime, minutes) {
  const d = new Date(dateTime)
  d.setMinutes(d.getMinutes() + minutes)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 金额舍入到分，中点远离零（与协作约定第 8 节一致；用整数分计算避免浮点误差） */
export function roundMoney(value) {
  const cents = Math.round(Math.abs(value) * 100 + 1e-9)
  return (Math.sign(value) * cents) / 100
}

export function buildBusiness({ items, prices }) {
  const priceOn = (itemId, day) => {
    const list = prices.filter((p) => p.itemId === itemId && p.effectiveFrom <= day).sort((a, b) => a.effectiveFrom.localeCompare(b.effectiveFrom))
    return list.length ? list[list.length - 1].unitPrice : null
  }
  const appointments = []
  const visits = []
  const settlements = []
  const daySeq = {}

  function addVisit(appt, outcome, treatments = [], reason = null) {
    const arrivedAt = addMinutes(appt.scheduledAt, 5)
    const day = arrivedAt.slice(0, 10)
    const visit = {
      id: visits.length + 1,
      appointmentId: appt.id,
      patientId: appt.patientId,
      dentistId: appt.dentistId,
      clinicId: appt.clinicId,
      arrivedAt,
      confirmedAt: null,
      problem: null,
      specialNeeds: null,
      relatedHistory: null,
      noTreatmentReason: null,
      treatments: [],
      version: 0
    }
    if (outcome !== 'A') {
      visit.confirmedAt = addMinutes(appt.scheduledAt, 45)
      visit.problem = reason ? '复诊评估' : '常规检查与治疗'
      visit.specialNeeds = '无'
      visit.noTreatmentReason = reason
      visit.treatments = treatments.map(([itemId, quantity]) => ({ itemId, quantity, unitPrice: priceOn(itemId, day) }))
    }
    visits.push(visit)
    appt.arrived = true
    appt.visitId = visit.id
    if (outcome === 'S') {
      const key = day.replaceAll('-', '')
      daySeq[key] = (daySeq[key] || 0) + 1
      const lines = visit.treatments.map((t) => {
        const item = items.find((i) => i.id === t.itemId)
        return { itemId: t.itemId, itemName: item.name, unitName: item.standardUnit, quantity: t.quantity, unitPrice: t.unitPrice, subtotal: roundMoney(t.quantity * t.unitPrice) }
      })
      settlements.push({
        id: settlements.length + 1,
        settlementNo: `JS${key}-${String(daySeq[key]).padStart(4, '0')}`,
        visitId: visit.id,
        issuedAt: addMinutes(appt.scheduledAt, 70),
        issuedBy: '王丽',
        lines,
        total: roundMoney(lines.reduce((s, l) => s + l.subtotal, 0))
      })
    }
  }

  function addAppointment(scheduledAt, patientId, dentistId, clinicId, itemIds) {
    const appt = { id: appointments.length + 1, patientId, dentistId, clinicId, scheduledAt, itemIds, arrived: false, cancelledAt: null, cancelledBy: null, visitId: null, version: 0 }
    appointments.push(appt)
    return appt
  }

  for (const [at, patientId, dentistId, clinicId, itemIds, outcome, treatments, reason] of HISTORY) {
    const appt = addAppointment(at, patientId, dentistId, clinicId, itemIds)
    if (outcome === 'C') {
      appt.cancelledAt = `${addMinutes(at, -24 * 60).slice(0, 10)}T18:00:00`
      appt.cancelledBy = '王丽'
    } else if (outcome !== 'P') {
      addVisit(appt, outcome, treatments, reason)
    }
  }

  const today = toApiDate()
  for (const [time, patientId, dentistId, clinicId, itemIds, outcome, treatments] of TODAY) {
    const appt = addAppointment(`${today}T${time}:00`, patientId, dentistId, clinicId, itemIds)
    if (outcome !== 'V') addVisit(appt, outcome, treatments)
  }

  const quarterCloses = QUARTER_CLOSES.map((q, i) => {
    const companyNetProfit = roundMoney(q.rows.reduce((sum, r) => sum + r.revenue - r.supplyCost - r.operatingCost, 0))
    return {
      id: i + 1,
      ...q,
      companyNetProfit,
      incentiveFund: companyNetProfit > 0 ? roundMoney(companyNetProfit * 0.1) : null,
      fundTransferAt: companyNetProfit > 0 ? null : q.confirmedAt,
      rows: q.rows.map((r) => ({ ...r }))
    }
  })

  return { appointments, visits, settlements, quarterCloses, seedDate: today }
}
