// 模拟数据库：数据保存在浏览器 localStorage，刷新页面不丢失；可在登录页“重置模拟数据”。
// 示例数据与协作约定第 4、11 节一致（演示账号、诊所、牙医等）。
import { buildBusiness } from './seedBusiness.js'
import { toApiDate } from '@shared/format.js'
// v7：增加患者端（患者账号、预约申请），换新键让浏览器里旧的模拟数据自动重建
const STORAGE_KEY = 'adc-mock-db-v7'

// 演示账号统一密码，见 E:\adc-system\docs\前后端协作约定.md 第 4 节（仅用于本地演示）
const DEMO_PASSWORD = '123456'

function seed() {
  const base = {
    seq: { user: 4, clinic: 3, dentist: 4, qualification: 5, item: 7, price: 9, allergy: 4, patient: 8 },
    users: [
      { id: 1, username: 'front1', password: DEMO_PASSWORD, displayName: '王丽', role: 'FRONT_DESK', dentistId: null, active: true, version: 0 },
      { id: 2, username: 'dentist1', password: DEMO_PASSWORD, displayName: '林嘉文', role: 'DENTIST', dentistId: 1, active: true, version: 0 },
      { id: 3, username: 'dentist2', password: DEMO_PASSWORD, displayName: '周明远', role: 'DENTIST', dentistId: 2, active: true, version: 0 },
      { id: 4, username: 'hq1', password: DEMO_PASSWORD, displayName: '陈总部', role: 'HQ', dentistId: null, active: true, version: 0 }
    ],
    clinics: [
      { id: 1, name: '天河诊所', postalAddress: '广州市天河区天河路 385 号 3 楼', managerName: '张敏', phone: '020-38880001', email: 'tianhe@adc.example', officeArea: 180.5, active: true, version: 1 },
      { id: 2, name: '越秀诊所', postalAddress: '广州市越秀区中山五路 68 号 2 楼', managerName: '刘洋', phone: '020-83330002', email: 'yuexiu@adc.example', officeArea: 136, active: true, version: 0 },
      { id: 3, name: '福田诊所', postalAddress: '深圳市福田区深南大道 6008 号 5 楼', managerName: '黄蕾', phone: '0755-82220003', email: 'futian@adc.example', officeArea: 152.25, active: true, version: 0 }
    ],
    qualifications: [
      { id: 1, name: '口腔全科' },
      { id: 2, name: '牙体牙髓' },
      { id: 3, name: '口腔正畸' },
      { id: 4, name: '口腔种植' },
      { id: 5, name: '儿童口腔' }
    ],
    dentists: [
      { id: 1, employeeNo: 'D001', name: '林嘉文', birthDate: '1983-04-12', homeAddress: '广州市天河区林和西路 9 号', phone: '13800000001', email: 'd001@adc.example', active: true, version: 0,
        holdings: [{ qualificationId: 1, verifiedAt: '2026-01-05T10:00:00' }, { qualificationId: 2, verifiedAt: '2026-01-05T10:00:00' }] },
      { id: 2, employeeNo: 'D002', name: '周明远', birthDate: '1987-09-03', homeAddress: '广州市越秀区东风中路 300 号', phone: '13800000002', email: 'd002@adc.example', active: true, version: 0,
        holdings: [{ qualificationId: 1, verifiedAt: '2026-01-06T09:30:00' }, { qualificationId: 4, verifiedAt: '2026-02-10T14:00:00' }] },
      { id: 3, employeeNo: 'D003', name: '黄晓雯', birthDate: '1990-12-21', homeAddress: '深圳市福田区福华路 18 号', phone: '13800000003', email: 'd003@adc.example', active: true, version: 0,
        holdings: [{ qualificationId: 1, verifiedAt: '2026-01-08T11:00:00' }, { qualificationId: 3, verifiedAt: null }] },
      { id: 4, employeeNo: 'D004', name: '吴志强', birthDate: '1979-06-30', homeAddress: '深圳市南山区科技园南路 1 号', phone: '13800000004', email: 'd004@adc.example', active: true, version: 0,
        holdings: [{ qualificationId: 1, verifiedAt: '2026-01-08T11:00:00' }, { qualificationId: 5, verifiedAt: '2026-03-02T15:20:00' }] }
    ],
    items: [
      { id: 1, name: '口腔检查', standardUnit: '次', quantityScale: 0, active: true, version: 0 },
      { id: 2, name: '洁牙', standardUnit: '次', quantityScale: 0, active: true, version: 0 },
      { id: 3, name: '树脂补牙', standardUnit: '颗', quantityScale: 0, active: true, version: 0 },
      { id: 4, name: '根管治疗', standardUnit: '根', quantityScale: 0, active: true, version: 0 },
      { id: 5, name: '拔牙', standardUnit: '颗', quantityScale: 0, active: true, version: 0 },
      { id: 6, name: '局部麻醉', standardUnit: '毫升', quantityScale: 1, active: true, version: 0 },
      { id: 7, name: '正畸复诊调整', standardUnit: '次', quantityScale: 0, active: true, version: 0 }
    ],
    prices: [
      { id: 1, itemId: 1, effectiveFrom: '2026-01-01', unitPrice: 50 },
      { id: 2, itemId: 2, effectiveFrom: '2026-01-01', unitPrice: 120 },
      { id: 3, itemId: 2, effectiveFrom: '2026-10-01', unitPrice: 150 },
      { id: 4, itemId: 3, effectiveFrom: '2026-01-01', unitPrice: 280 },
      { id: 5, itemId: 4, effectiveFrom: '2026-01-01', unitPrice: 600 },
      { id: 6, itemId: 5, effectiveFrom: '2026-01-01', unitPrice: 200 },
      { id: 7, itemId: 6, effectiveFrom: '2026-01-01', unitPrice: 30 },
      { id: 8, itemId: 7, effectiveFrom: '2026-01-01', unitPrice: 400 },
      { id: 9, itemId: 6, effectiveFrom: '2026-07-01', unitPrice: 35 }
    ],
    allergies: [
      { id: 1, name: '青霉素' },
      { id: 2, name: '利多卡因' },
      { id: 3, name: '乳胶' },
      { id: 4, name: '碘伏' }
    ],
    patients: [
      { id: 1, accountNo: 'P000001', name: '张伟', birthDate: '1988-02-14', billingAddress: '广州市天河区体育西路 101 号', phone: '13900000001', email: 'zhangwei@example.com', emergencyName: '张芳', emergencyPhone: '13900001001', allergyIds: [1], allergyVerifiedAt: '2026-09-12T10:20:00', version: 0 },
      { id: 2, accountNo: 'P000002', name: '李娜', birthDate: '1995-07-30', billingAddress: '广州市越秀区北京路 22 号', phone: '13900000002', email: null, emergencyName: '李强', emergencyPhone: '13900001002', allergyIds: [], allergyVerifiedAt: '2026-10-08T09:40:00', version: 0 },
      { id: 3, accountNo: 'P000003', name: '王磊', birthDate: '1979-11-05', billingAddress: '深圳市福田区华强北路 3 号', phone: '13900000003', email: 'wanglei@example.com', emergencyName: '王静', emergencyPhone: '13900001003', allergyIds: [2, 3], allergyVerifiedAt: null, version: 0 },
      { id: 4, accountNo: 'P000004', name: '刘洋', birthDate: '2001-03-18', billingAddress: '广州市海珠区新港中路 135 号', phone: '13900000004', email: null, emergencyName: '刘军', emergencyPhone: '13900001004', allergyIds: [], allergyVerifiedAt: null, version: 0 },
      { id: 5, accountNo: 'P000005', name: '陈静', birthDate: '1992-08-09', billingAddress: '深圳市南山区南海大道 2000 号', phone: '13900000005', email: 'chenjing@example.com', emergencyName: '陈明', emergencyPhone: '13900001005', allergyIds: [4], allergyVerifiedAt: '2026-08-20T15:00:00', version: 0 },
      { id: 6, accountNo: 'P000006', name: '杨帆', birthDate: '1985-12-01', billingAddress: '广州市白云区机场路 88 号', phone: '13900000006', email: null, emergencyName: '杨柳', emergencyPhone: '13900001006', allergyIds: [], allergyVerifiedAt: null, version: 0 },
      { id: 7, accountNo: 'P000007', name: '赵敏', birthDate: '1998-05-25', billingAddress: '广州市天河区珠江新城华夏路 10 号', phone: '13900000007', email: 'zhaomin@example.com', emergencyName: '赵刚', emergencyPhone: '13900001007', allergyIds: [1, 2], allergyVerifiedAt: '2026-07-15T11:10:00', version: 0 },
      { id: 8, accountNo: 'P000008', name: '张伟', birthDate: '1990-06-06', billingAddress: '深圳市罗湖区人民南路 1 号', phone: '13900000008', email: null, emergencyName: '张丽', emergencyPhone: '13900001008', allergyIds: [], allergyVerifiedAt: null, version: 0 }
    ],
    tokens: {},
    requests: {}
  }
  const business = buildBusiness(base)
  base.seq.appointment = business.appointments.length
  base.seq.visit = business.visits.length
  base.seq.quarterClose = business.quarterCloses.length
  base.seq.settlement = business.settlements.length
  Object.assign(base, buildPortal(business))
  base.seq.patientAccount = base.patientAccounts.length
  base.seq.appointmentRequest = base.appointmentRequests.length
  // 操作记录在第一次请求时由 handlers/logs.js 按示例数据生成（生成时要用到各接口的视图函数）
  return { ...base, ...business }
}

// 患者端示例数据（协作约定第 11 节）：两个患者账号、五条预约申请，日期按今天计算
function buildPortal(business) {
  const day = (offset) => {
    const d = new Date()
    d.setDate(d.getDate() + offset)
    return toApiDate(d)
  }
  const at = (offset, time) => `${day(offset)}T${time}:00`
  const zhangToday = business.appointments.find((a) => a.patientId === 1 && a.scheduledAt.startsWith(day(0)) && !a.cancelledAt)
  const base = { handledAt: null, handledBy: null, invalidReason: null, withdrawnAt: null, appointmentId: null, version: 0 }
  const requests = [
    { ...base, id: 1, accountId: 2, contactName: '李娜', contactPhone: '13900000002', clinicId: 3, preferredDate: day(-2), timeSlot: 'EVENING', itemIds: [2], note: '', createdAt: at(-4, '21:10'), status: 'WITHDRAWN', withdrawnAt: at(-3, '08:30') },
    { ...base, id: 2, accountId: 2, contactName: '李娜', contactPhone: '13900000002', clinicId: 2, preferredDate: day(-1), timeSlot: 'MORNING', itemIds: [], note: '想咨询种植牙', createdAt: at(-3, '19:42'), status: 'INVALID', handledAt: at(-2, '10:05'), handledBy: '王丽', invalidReason: '三次致电无人接听，已无法确认时间' },
    { ...base, id: 3, accountId: 1, contactName: '张伟', contactPhone: '13900000001', clinicId: 1, preferredDate: day(0), timeSlot: 'MORNING', itemIds: [1, 2], note: '', createdAt: at(-1, '20:15'), status: zhangToday ? 'CONFIRMED' : 'PENDING', handledAt: zhangToday ? at(-1, '20:40') : null, handledBy: zhangToday ? '王丽' : null, appointmentId: zhangToday?.id ?? null, version: zhangToday ? 1 : 0 },
    { ...base, id: 4, accountId: 1, contactName: '张伟', contactPhone: '13900000001', clinicId: 1, preferredDate: day(1), timeSlot: 'MORNING', itemIds: [1, 2], note: '牙齿遇冷敏感', createdAt: at(0, '08:12'), status: 'PENDING' },
    { ...base, id: 5, accountId: 2, contactName: '李娜', contactPhone: '13900000002', clinicId: 2, preferredDate: day(2), timeSlot: 'AFTERNOON', itemIds: [3], note: '', createdAt: at(0, '09:26'), status: 'PENDING' }
  ]
  return {
    patientAccounts: [
      { id: 1, phone: '13900000001', name: '张伟', password: DEMO_PASSWORD, createdAt: at(-30, '10:00') },
      { id: 2, phone: '13900000002', name: '李娜', password: DEMO_PASSWORD, createdAt: at(-20, '15:30') }
    ],
    patientTokens: {},
    appointmentRequests: requests
  }
}

function load() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) return JSON.parse(raw)
  } catch {
    /* 数据损坏时重新生成 */
  }
  return seed()
}

export const db = load()

export function save() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(db))
  } catch {
    /* 存储已满或被禁止时只在内存中保留 */
  }
}

/** 恢复为初始示例数据（同时作废所有模拟令牌） */
export function resetMockDb() {
  const fresh = seed()
  Object.keys(db).forEach((k) => delete db[k])
  Object.assign(db, fresh)
  save()
}

export function nextId(kind) {
  db.seq[kind] = (db.seq[kind] || 0) + 1
  return db.seq[kind]
}
