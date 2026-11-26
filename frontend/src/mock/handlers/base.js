// 模拟：下拉选项、诊所、牙医与资质、治疗项目与价格（协作约定第 9.1—9.4 节）
import { db, nextId } from '../db.js'
import { notFound, conflict } from '../errors.js'
import { createChecker, checkVersion } from '../validate.js'
import { toApiDate } from '@shared/format.js'

const HQ = ['HQ']

// ---------- 视图：把模拟库里的记录转成接口返回的格式 ----------

export function clinicView(c) {
  return { ...c }
}

export function dentistView(d) {
  const qualifications = d.holdings
    .map((h) => ({ qualificationId: h.qualificationId, name: db.qualifications.find((q) => q.id === h.qualificationId)?.name || '', verifiedAt: h.verifiedAt }))
    .sort((a, b) => a.qualificationId - b.qualificationId)
  const { holdings, ...rest } = d
  return { ...rest, qualifications }
}

/** 某一天适用的价格：适用起始日期不晚于这一天的最后一条 */
export function priceOn(itemId, day = toApiDate()) {
  const list = db.prices.filter((p) => p.itemId === itemId && p.effectiveFrom <= day).sort((a, b) => a.effectiveFrom.localeCompare(b.effectiveFrom))
  return list.length ? list[list.length - 1].unitPrice : null
}

export function itemView(i) {
  const prices = db.prices
    .filter((p) => p.itemId === i.id)
    .sort((a, b) => a.effectiveFrom.localeCompare(b.effectiveFrom))
    .map((p) => ({ id: p.id, effectiveFrom: p.effectiveFrom, unitPrice: p.unitPrice }))
  return { ...i, currentPrice: priceOn(i.id), prices }
}

function activeFilter(query) {
  return String(query.activeOnly ?? 'true') !== 'false'
}

function findOr404(list, id, what) {
  const record = list.find((x) => x.id === Number(id))
  if (!record) throw notFound(`${what}不存在或已被删除`)
  return record
}

// ---------- 校验 ----------

function checkClinic(body, selfId = null) {
  const c = createChecker(body)
  const name = c.text('name', '诊所名称', { max: 60 })
  c.text('postalAddress', '邮政地址')
  c.text('managerName', '主管姓名', { max: 60 })
  c.text('phone', '电话', { max: 30 })
  c.email('email', '电子邮箱')
  c.number('officeArea', '办公面积', { minExclusive: 0, scale: 2 })
  if (selfId !== null) c.bool('active', '使用状态')
  c.done()
  if (db.clinics.some((x) => x.name === name && x.id !== selfId)) throw conflict(`已有名为“${name}”的诊所，诊所名称不能重复`)
}

function clinicFields(body) {
  return {
    name: body.name.trim(),
    postalAddress: body.postalAddress.trim(),
    managerName: body.managerName.trim(),
    phone: body.phone.trim(),
    email: body.email.trim(),
    officeArea: Number(body.officeArea)
  }
}

function checkDentist(body, selfId = null) {
  const c = createChecker(body)
  const employeeNo = c.text('employeeNo', '员工编号', { max: 30 })
  c.text('name', '姓名', { max: 60 })
  c.date('birthDate', '出生日期')
  c.text('homeAddress', '家庭地址')
  c.text('phone', '电话', { max: 30 })
  c.email('email', '电子邮箱')
  if (selfId !== null) c.bool('active', '使用状态')
  const list = body?.qualifications
  if (!Array.isArray(list)) c.add('qualifications', '资质格式不正确')
  else {
    const ids = list.map((q) => Number(q.qualificationId))
    if (ids.some((id) => !db.qualifications.some((q) => q.id === id))) c.add('qualifications', '包含不存在的资质')
    if (new Set(ids).size !== ids.length) c.add('qualifications', '同一资质不能重复添加')
    if (list.some((q) => q.verifiedAt && !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/.test(q.verifiedAt))) c.add('qualifications', '核实时间格式不正确')
  }
  c.done()
  if (db.dentists.some((x) => x.employeeNo === employeeNo && x.id !== selfId)) throw conflict(`员工编号 ${employeeNo} 已被使用`)
}

function dentistFields(body) {
  return {
    employeeNo: body.employeeNo.trim(),
    name: body.name.trim(),
    birthDate: body.birthDate,
    homeAddress: body.homeAddress.trim(),
    phone: body.phone.trim(),
    email: body.email.trim(),
    holdings: body.qualifications.map((q) => ({ qualificationId: Number(q.qualificationId), verifiedAt: q.verifiedAt || null }))
  }
}

function checkItem(body, { creating }) {
  const c = createChecker(body)
  const name = c.text('name', '项目名称', { max: 100 })
  c.text('standardUnit', '标准单位', { max: 30 })
  c.number('quantityScale', '数量精度', { min: 0, max: 6, scale: 0 })
  if (creating) {
    const fp = body?.firstPrice
    if (!fp) c.add('firstPrice', '请填写首次价格')
    else {
      if (!fp.effectiveFrom || !/^\d{4}-\d{2}-\d{2}$/.test(fp.effectiveFrom)) c.add('firstPrice.effectiveFrom', '请选择适用起始日期')
      const p = Number(fp.unitPrice)
      if (fp.unitPrice === null || fp.unitPrice === undefined || fp.unitPrice === '' || Number.isNaN(p)) c.add('firstPrice.unitPrice', '请填写单位价格')
      else if (p < 0) c.add('firstPrice.unitPrice', '单位价格不能小于 0')
      else if (Math.round(p * 100) !== p * 100) c.add('firstPrice.unitPrice', '单位价格最多保留 2 位小数')
    }
  } else {
    c.bool('active', '使用状态')
  }
  c.done()
  return name.trim()
}

// ---------- 接口 ----------

export const baseRoutes = [
  // 9.1 下拉选项（全）
  {
    method: 'GET',
    path: '/options/clinics',
    handler: ({ query }) => db.clinics.filter((c) => !activeFilter(query) || c.active).map((c) => ({ id: c.id, name: c.name, active: c.active }))
  },
  {
    method: 'GET',
    path: '/options/dentists',
    handler: ({ query }) =>
      db.dentists
        .filter((d) => !activeFilter(query) || d.active)
        .map((d) => ({
          id: d.id,
          employeeNo: d.employeeNo,
          name: d.name,
          active: d.active,
          verifiedQualifications: d.holdings.filter((h) => h.verifiedAt).map((h) => db.qualifications.find((q) => q.id === h.qualificationId)?.name)
        }))
  },
  {
    method: 'GET',
    path: '/options/treatment-items',
    handler: ({ query }) =>
      db.items
        .filter((i) => !activeFilter(query) || i.active)
        .map((i) => ({ id: i.id, name: i.name, standardUnit: i.standardUnit, quantityScale: i.quantityScale, currentPrice: priceOn(i.id), active: i.active }))
  },
  { method: 'GET', path: '/options/allergies', handler: () => db.allergies.map((a) => ({ ...a })) },
  { method: 'GET', path: '/options/qualifications', handler: () => db.qualifications.map((q) => ({ ...q })) },

  // 9.2 诊所（总）
  { method: 'GET', path: '/clinics', roles: HQ, handler: () => db.clinics.map(clinicView) },
  { method: 'GET', path: '/clinics/:id', roles: HQ, handler: ({ params }) => clinicView(findOr404(db.clinics, params.id, '诊所')) },
  {
    method: 'POST',
    path: '/clinics',
    roles: HQ,
    handler({ body }) {
      checkClinic(body)
      const clinic = { id: nextId('clinic'), ...clinicFields(body), active: true, version: 0 }
      db.clinics.push(clinic)
      return { __status: 201, data: clinicView(clinic) }
    }
  },
  {
    method: 'PUT',
    path: '/clinics/:id',
    roles: HQ,
    handler({ params, body }) {
      const clinic = findOr404(db.clinics, params.id, '诊所')
      checkVersion(body, clinic, clinicView)
      checkClinic(body, clinic.id)
      Object.assign(clinic, clinicFields(body), { active: body.active, version: clinic.version + 1 })
      return clinicView(clinic)
    }
  },

  // 9.3 牙医与资质（总）
  { method: 'GET', path: '/dentists', roles: HQ, handler: () => db.dentists.map(dentistView) },
  { method: 'GET', path: '/dentists/:id', roles: HQ, handler: ({ params }) => dentistView(findOr404(db.dentists, params.id, '牙医')) },
  {
    method: 'POST',
    path: '/dentists',
    roles: HQ,
    handler({ body }) {
      checkDentist(body)
      const dentist = { id: nextId('dentist'), ...dentistFields(body), active: true, version: 0 }
      db.dentists.push(dentist)
      return { __status: 201, data: dentistView(dentist) }
    }
  },
  {
    method: 'PUT',
    path: '/dentists/:id',
    roles: HQ,
    handler({ params, body }) {
      const dentist = findOr404(db.dentists, params.id, '牙医')
      checkVersion(body, dentist, dentistView)
      checkDentist(body, dentist.id)
      Object.assign(dentist, dentistFields(body), { active: body.active, version: dentist.version + 1 })
      return dentistView(dentist)
    }
  },
  {
    method: 'POST',
    path: '/qualifications',
    roles: HQ,
    handler({ body }) {
      const c = createChecker(body)
      const name = c.text('name', '资质名称', { max: 100 })
      c.done()
      if (db.qualifications.some((q) => q.name === name)) throw conflict(`已有名为“${name}”的资质`)
      const q = { id: nextId('qualification'), name }
      db.qualifications.push(q)
      return { __status: 201, data: { ...q } }
    }
  },

  // 9.4 治疗项目与价格（总）
  { method: 'GET', path: '/treatment-items', roles: HQ, handler: () => db.items.map(itemView) },
  { method: 'GET', path: '/treatment-items/:id', roles: HQ, handler: ({ params }) => itemView(findOr404(db.items, params.id, '治疗项目')) },
  {
    method: 'POST',
    path: '/treatment-items',
    roles: HQ,
    handler({ body }) {
      const name = checkItem(body, { creating: true })
      if (db.items.some((i) => i.name === name)) throw conflict(`已有名为“${name}”的治疗项目`)
      const item = { id: nextId('item'), name, standardUnit: body.standardUnit.trim(), quantityScale: Number(body.quantityScale), active: true, version: 0 }
      db.items.push(item)
      db.prices.push({ id: nextId('price'), itemId: item.id, effectiveFrom: body.firstPrice.effectiveFrom, unitPrice: Number(body.firstPrice.unitPrice) })
      return { __status: 201, data: itemView(item) }
    }
  },
  {
    method: 'PUT',
    path: '/treatment-items/:id',
    roles: HQ,
    handler({ params, body }) {
      const item = findOr404(db.items, params.id, '治疗项目')
      checkVersion(body, item, itemView)
      const name = checkItem(body, { creating: false })
      if (db.items.some((i) => i.name === name && i.id !== item.id)) throw conflict(`已有名为“${name}”的治疗项目`)
      Object.assign(item, { name, standardUnit: body.standardUnit.trim(), quantityScale: Number(body.quantityScale), active: body.active, version: item.version + 1 })
      return itemView(item)
    }
  },
  {
    method: 'POST',
    path: '/treatment-items/:id/prices',
    roles: HQ,
    handler({ params, body }) {
      const item = findOr404(db.items, params.id, '治疗项目')
      const c = createChecker(body)
      const day = c.date('effectiveFrom', '适用起始日期')
      c.number('unitPrice', '单位价格', { min: 0, scale: 2 })
      c.done()
      if (db.prices.some((p) => p.itemId === item.id && p.effectiveFrom === day)) {
        throw conflict(`“${item.name}”在 ${day} 已有一条价格，同一天只能有一个价格`)
      }
      db.prices.push({ id: nextId('price'), itemId: item.id, effectiveFrom: day, unitPrice: Number(body.unitPrice) })
      item.version += 1 // 与后端一致：调价后项目版本号加 1
      return itemView(item)
    }
  }
]
