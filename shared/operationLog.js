// 网页和小程序共用：把操作记录的 detail（写入时保存的 JSON）变成看得懂的中文
// - summary(log)：一行摘要，例如“张伟 · 2026-11-21 10:30 · 林嘉文 · 天河诊所”
// - changes(log)：修改类（detail 为 { before, after }）逐个字段对比，只列出改了的
// - fields(log)：其他记录列出 detail 的各个字段
// 只负责显示，不做业务判断；detail 缺字段或不是对象时尽量显示原样。
import { APPOINTMENT_STATUS, VISIT_STATUS, FUND_STATUS, LOG_TARGETS, REQUEST_STATUS, roleName, timeSlotText } from './labels.js'
import { money, dateTime, quantity, scaleText } from './format.js'

// ---------- 字段中文名（与协作约定第 14 节一致） ----------
const COMMON = { id: '编号', active: '使用状态', version: '版本号', phone: '电话', email: '电子邮箱' }
const LABELS = {
  CLINIC: { name: '诊所名称', postalAddress: '邮政地址', managerName: '主管姓名', officeArea: '办公面积（㎡）' },
  DENTIST: { employeeNo: '员工编号', name: '姓名', birthDate: '出生日期', homeAddress: '家庭地址', qualifications: '专业资质' },
  QUALIFICATION: { name: '资质名称' },
  TREATMENT_ITEM: { name: '项目名称', standardUnit: '标准单位', quantityScale: '数量精度', currentPrice: '当前单价', prices: '价格记录' },
  PATIENT: {
    accountNo: '患者账号', name: '姓名', birthDate: '出生日期', billingAddress: '账单地址', emergencyName: '紧急联系人',
    emergencyPhone: '紧急联系人电话', allergies: '已知过敏', allergyIds: '已知过敏', allergyVerifiedAt: '过敏核实时间'
  },
  APPOINTMENT: {
    status: '预约状态', scheduledAt: '预约时间', patient: '患者', dentist: '牙医', clinic: '诊所', items: '意向治疗', arrived: '已到诊',
    cancelledAt: '取消时间', cancelledBy: '取消经办人', visitId: '就诊编号',
    arrivedAt: '实际到诊时间', registeredAt: '登记时间', dentistId: '接诊牙医', clinicId: '就诊诊所'
  },
  VISIT: {
    status: '就诊状态', arrivedAt: '到诊时间', confirmedAt: '诊疗确认时间', patient: '患者', clinic: '诊所', dentist: '牙医',
    intendedItems: '意向治疗', problem: '本次问题', specialNeeds: '特殊需求', relatedHistory: '相关既往病史',
    noTreatmentReason: '未实施治疗原因', treatments: '实际治疗', total: '合计金额', settlementNo: '结算单号'
  },
  SETTLEMENT: {
    settlementNo: '结算单号', visitId: '就诊编号', issuedAt: '开具时间', issuedBy: '开具人', patient: '患者', clinic: '诊所',
    dentist: '牙医', lines: '费用明细', total: '结算总额'
  },
  APPOINTMENT_REQUEST: { status: '状态', contactName: '联系人', contactPhone: '联系电话', clinic: '门店', preferredDate: '期望日期', timeSlot: '期望时段', items: '想做的项目', note: '补充说明', createdAt: '提交时间', handledAt: '处理时间', handledBy: '处理人', invalidReason: '无效原因', withdrawnAt: '撤回时间', appointment: '转成的预约' },
  QUARTER_CLOSE: {
    year: '年度', quarter: '季度', confirmedAt: '确认时间', firstConfirmedAt: '首次确认时间', collectionWorkdays: '汇总周期（工作日）',
    companyNetProfit: '公司净利润', incentiveFund: '激励基金总额', fundStatus: '基金状态', fundTransferAt: '基金转处理时间',
    fundNote: '基金处理说明', rows: '牙医业绩'
  },
  USER: { username: '登录名', displayName: '显示姓名', role: '岗位', dentist: '绑定牙医', dentistId: '绑定牙医' }
}
// 对比和字段列表里不显示的字段：版本号每次都变，看了没有意义
const HIDDEN = new Set(['version'])
const MONEY = new Set(['currentPrice', 'total', 'unitPrice', 'subtotal', 'companyNetProfit', 'incentiveFund', 'revenue', 'supplyCost', 'operatingCost', 'netProfit'])

export function fieldLabel(targetType, key) {
  return LABELS[targetType]?.[key] || COMMON[key] || key
}

// ---------- 值的显示 ----------
const EMPTY = '—'
const isDateTime = (v) => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(v)
const nameOf = (o) => (o && typeof o === 'object' ? o.name || o.displayName || o.username || '' : '')
const withCode = (o) => {
  const code = o.accountNo || o.employeeNo
  return code ? `${nameOf(o)}（${code}）` : nameOf(o)
}
const listText = (arr, fn) => (arr.length ? arr.map(fn) : ['无'])

/**
 * 把一个字段的值变成若干行文字（数组字段每项一行）
 * refs：{ dentists: Map(id→名称), clinics: Map(id→名称) }，用于 dentistId / clinicId
 */
export function valueLines(targetType, key, value, refs = {}) {
  if (value === null || value === undefined || value === '') return [EMPTY]
  if (key === 'active') return [value ? '启用' : '停用']
  if (typeof value === 'boolean') return [value ? '是' : '否']
  if (key === 'status') {
    const map = targetType === 'VISIT' ? VISIT_STATUS : targetType === 'APPOINTMENT_REQUEST' ? REQUEST_STATUS : APPOINTMENT_STATUS
    return [map[value]?.text || value]
  }
  if (key === 'fundStatus') return [FUND_STATUS[value]?.text || value]
  if (key === 'timeSlot') return [timeSlotText(value)]
  if (key === 'appointment' && typeof value === 'object') return [`预约 #${value.id} · ${dateTime(value.scheduledAt)} · ${value.dentist?.name || ''} · ${value.clinic?.name || ''}`]
  if (key === 'role') return [roleName(value)]
  if (key === 'quantityScale') return [scaleText(value)]
  if (key === 'quarter') return [`第 ${value} 季度`]
  if (key === 'dentistId' || key === 'clinicId') {
    const name = (key === 'dentistId' ? refs.dentists : refs.clinics)?.get?.(Number(value))
    return [name ? `${name}（#${value}）` : `#${value}`]
  }
  if (MONEY.has(key)) return [`¥${money(value)}`]
  if (isDateTime(value)) return [dateTime(value)]
  if (Array.isArray(value)) {
    switch (key) {
      case 'qualifications':
        return listText(value, (q) => `${q.name || `资质 #${q.qualificationId}`}${q.verifiedAt ? `（${dateTime(q.verifiedAt).slice(0, 10)} 核实）` : '（待核实）'}`)
      case 'prices':
        return listText(value, (p) => `${p.effectiveFrom} 起 ¥${money(p.unitPrice)}`)
      case 'treatments':
      case 'lines':
        return listText(value, (l) => `${l.itemName || `项目 #${l.itemId}`} ${quantity(l.quantity)}${l.unitName || ''} × ¥${money(l.unitPrice)}${l.subtotal !== undefined && l.subtotal !== null ? ` = ¥${money(l.subtotal)}` : ''}`)
      case 'rows':
        return listText(value, (r) => `${r.name || `牙医 #${r.dentistId}`}：收入 ¥${money(r.revenue)}，用品 ¥${money(r.supplyCost)}，运营 ¥${money(r.operatingCost)}，净利润 ¥${money(r.netProfit)}`)
      default:
        return listText(value, (x) => (x && typeof x === 'object' ? withCode(x) || JSON.stringify(x) : String(x)))
    }
  }
  // 就诊里的患者带着过敏信息：诊疗确认会更新过敏核实时间，只显示姓名会看不出改了什么
  if (key === 'patient' && typeof value === 'object' && ('allergies' in value || 'allergyVerifiedAt' in value)) {
    const allergies = Array.isArray(value.allergies) && value.allergies.length ? value.allergies.map((a) => a.name).join('、') : '无'
    return [
      withCode(value) || value.name,
      `已知过敏：${allergies}`,
      value.allergyVerifiedAt ? `过敏核实：${dateTime(value.allergyVerifiedAt)}` : '过敏未核实'
    ]
  }
  if (typeof value === 'object') return [withCode(value) || JSON.stringify(value)]
  return [String(value)]
}

const same = (a, b) => JSON.stringify(a ?? null) === JSON.stringify(b ?? null)

/** detail 是不是 { before, after } 形式 */
export function isChangeDetail(detail) {
  return Boolean(detail && typeof detail === 'object' && 'before' in detail && 'after' in detail)
}

/**
 * 修改类记录的字段对比
 * 返回 [{ key, label, before: [行], after: [行], changed }]；all 为 false 时只返回改了的字段
 */
export function changes(log, { all = false, refs } = {}) {
  if (!isChangeDetail(log.detail)) return []
  const before = log.detail.before || {}
  const after = log.detail.after || {}
  const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])].filter((k) => !HIDDEN.has(k))
  return keys
    .map((key) => ({
      key,
      label: fieldLabel(log.targetType, key),
      before: valueLines(log.targetType, key, before[key], refs),
      after: valueLines(log.targetType, key, after[key], refs),
      changed: !same(before[key], after[key])
    }))
    .filter((row) => all || row.changed)
}

/** 非修改类记录：列出 detail 的字段 [{ key, label, lines }] */
export function fields(log, { refs } = {}) {
  const d = log.detail
  if (!d || typeof d !== 'object' || isChangeDetail(d)) return []
  return Object.keys(d)
    .filter((k) => !HIDDEN.has(k))
    .map((key) => ({ key, label: fieldLabel(log.targetType, key), lines: valueLines(log.targetType, key, d[key], refs) }))
}

/** 对象的称呼：“患者档案 #3” */
export function targetText(log) {
  const type = LOG_TARGETS[log.targetType] || log.targetType
  return log.targetId === null || log.targetId === undefined ? type : `${type} #${log.targetId}`
}

// ---------- 一行摘要 ----------
const join = (...parts) => parts.filter((p) => p !== null && p !== undefined && p !== '').join(' · ')

function changedLabels(log) {
  const rows = changes(log)
  if (!rows.length) return '内容没有变化'
  const names = rows.map((r) => r.label)
  return `改了${names.slice(0, 3).join('、')}${names.length > 3 ? ` 等 ${names.length} 项` : ''}`
}

function objectName(o) {
  if (!o || typeof o !== 'object') return ''
  return withCode(o)
}

export function summary(log, refs = {}) {
  const d = log.detail
  if (d === null || d === undefined) return ''
  if (typeof d !== 'object') return String(d).slice(0, 80)
  const after = isChangeDetail(d) ? d.after || {} : d
  const before = isChangeDetail(d) ? d.before || {} : {}
  switch (log.action) {
    case 'CREATE':
      return objectName(d)
    case 'UPDATE':
      if (before.active !== undefined && before.active !== after.active) return join(objectName(after), after.active ? '重新启用' : '停用', changes(log).length > 1 ? changedLabels(log) : '')
      return join(objectName(after), changedLabels(log))
    case 'ADD_STANDARD_PRICE': {
      const old = new Set((before.prices || []).map((p) => `${p.effectiveFrom}|${p.unitPrice}`))
      const added = (after.prices || []).filter((p) => !old.has(`${p.effectiveFrom}|${p.unitPrice}`))
      return join(after.name, ...added.map((p) => `${p.effectiveFrom} 起 ¥${money(p.unitPrice)}`))
    }
    case 'CREATE_APPOINTMENT':
      return join(d.patient?.name, dateTime(d.scheduledAt, ''), d.dentist?.name, d.clinic?.name)
    case 'CONFIRM_APPOINTMENT_REQUEST':
      return join(after.contactName, after.appointment ? `转为预约 ${dateTime(after.appointment.scheduledAt)} · ${after.appointment.dentist?.name || ''}` : '')
    case 'INVALIDATE_APPOINTMENT_REQUEST':
      return join(after.contactName, after.invalidReason ? `原因：${after.invalidReason}` : '')
    case 'CANCEL_APPOINTMENT':
      return join(d.patient?.name, d.scheduledAt ? `原定 ${dateTime(d.scheduledAt)}` : '', d.dentist?.name)
    case 'REGISTER_ARRIVAL':
      return join(d.arrivedAt ? `到诊 ${dateTime(d.arrivedAt)}` : '', d.dentistId ? `接诊 ${valueLines('APPOINTMENT', 'dentistId', d.dentistId, refs)[0]}` : '', d.visitId ? `就诊 #${d.visitId}` : '')
    case 'CONFIRM_VISIT':
      return join(after.patient?.name, Array.isArray(after.treatments) ? `${after.treatments.length} 项治疗` : '', after.total !== undefined && after.total !== null ? `合计 ¥${money(after.total)}` : '')
    case 'END_WITHOUT_TREATMENT':
      return join(after.patient?.name, after.noTreatmentReason ? `原因：${after.noTreatmentReason}` : '')
    case 'ISSUE_SETTLEMENT':
      return join(d.settlementNo, d.patient?.name, d.total !== undefined ? `¥${money(d.total)}` : '')
    case 'CONFIRM_QUARTER':
      return join(d.year && d.quarter ? `${d.year} 年第 ${d.quarter} 季度` : '', d.companyNetProfit !== undefined ? `公司净利润 ¥${money(d.companyNetProfit)}` : '', d.fundStatus ? FUND_STATUS[d.fundStatus]?.text : '')
    case 'CREATE_USER':
      return join(d.username, d.displayName, roleName(d.role))
    case 'UPDATE_USER':
      if (before.active !== undefined && before.active !== after.active) return join(after.username, after.active ? '重新启用账号' : '停用账号')
      return join(after.username, changedLabels(log))
    case 'RESET_PASSWORD':
      return join(d.username, '密码已重置，原登录全部下线')
    case 'CHANGE_PASSWORD':
      return join(d.username, '本人修改密码')
    default:
      return objectName(after)
  }
}

/** 导出 Excel 用：把改动写成一段文字“电话：a → b；地址：c → d” */
export function changeText(log, refs) {
  if (isChangeDetail(log.detail)) {
    return changes(log, { refs }).map((r) => `${r.label}：${r.before.join('，')} → ${r.after.join('，')}`).join('；')
  }
  return ''
}
