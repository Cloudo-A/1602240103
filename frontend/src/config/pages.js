// 页面清单：路由和左侧菜单都从这里生成，保证两者一致。
// roles：允许访问的岗位；round：在第几轮完成。
import { House, User, Calendar, Tickets, FirstAidKit, Notebook, OfficeBuilding, Avatar, PriceTag, DataAnalysis, Histogram, Coin, Key, Document, Message } from '@element-plus/icons-vue'

const WorkbenchView = () => import('@/views/WorkbenchView.vue')
const PatientsView = () => import('@/views/front/PatientsView.vue')
const ClinicsView = () => import('@/views/hq/ClinicsView.vue')
const DentistsView = () => import('@/views/hq/DentistsView.vue')
const TreatmentItemsView = () => import('@/views/hq/TreatmentItemsView.vue')
const AppointmentsView = () => import('@/views/front/AppointmentsView.vue')
const SettlementsView = () => import('@/views/front/SettlementsView.vue')
const VisitsView = () => import('@/views/dentist/VisitsView.vue')
const CancellationsView = () => import('@/views/hq/CancellationsView.vue')
const DemandView = () => import('@/views/hq/DemandView.vue')
const QuartersView = () => import('@/views/hq/QuartersView.vue')
const UsersView = () => import('@/views/hq/UsersView.vue')
const OperationLogsView = () => import('@/views/hq/OperationLogsView.vue')
const RecordsView = () => import('@/views/dentist/RecordsView.vue')
const RequestsView = () => import('@/views/front/RequestsView.vue')

export const MENU_GROUPS = {
  home: '概览',
  front: '前台业务',
  dentist: '诊疗',
  base: '基础资料',
  analysis: '运营分析',
  system: '系统管理'
}

export const pages = [
  // ---------- 三种岗位共用 ----------
  { path: 'workbench', name: 'workbench', title: '工作台', roles: ['FRONT_DESK', 'DENTIST', 'HQ'], group: 'home', icon: House, round: 5, component: WorkbenchView,
    plan: ['今天要处理的事', '常用操作入口'] },

  // ---------- 前台 ----------
  { path: 'front/patients', name: 'patients', title: '患者档案', uc: 'UC01', roles: ['FRONT_DESK'], group: 'front', icon: User, round: 2, component: PatientsView,
    plan: ['按姓名、电话或账号查找患者', '新建档案时提示疑似重复的患者', '维护联系方式、紧急联系人和已知过敏'] },
  { path: 'front/appointments', name: 'appointments', title: '预约与到诊', uc: 'UC02—UC04', roles: ['FRONT_DESK'], group: 'front', icon: Calendar, round: 3, component: AppointmentsView,
    plan: ['按日期、诊所、牙医查看预约', '安排新预约并检查时间冲突', '取消预约（保留原记录）', '登记到诊'] },
  { path: 'front/requests', name: 'requests', title: '预约申请', roles: ['FRONT_DESK'], group: 'front', icon: Message, round: 6, component: RequestsView,
    plan: ['查看患者在官网、小程序登记的预约申请', '回电确认后转为正式预约', '联系不上时判定无效并写明原因'] },
  { path: 'front/settlements', name: 'settlements', title: '就诊结算', uc: 'UC07', roles: ['FRONT_DESK'], group: 'front', icon: Tickets, round: 3, component: SettlementsView,
    plan: ['查看已确认待结算的就诊', '预览费用明细后开具结算单', '查询并重新打印原结算单'] },

  // ---------- 牙医 ----------
  { path: 'dentist/visits', name: 'visits', title: '今日就诊', uc: 'UC06', roles: ['DENTIST'], group: 'dentist', icon: FirstAidKit, round: 3, component: VisitsView,
    plan: ['查看今天自己接诊的患者', '逐项录入实际治疗并自动带出单价', '核对后确认诊疗完成', '到诊但未实施治疗时填写原因结束'] },
  { path: 'dentist/records', name: 'records', title: '查阅诊疗资料', uc: 'UC05', roles: ['DENTIST'], group: 'dentist', icon: Notebook, round: 3, component: RecordsView,
    plan: ['按患者查阅跨诊所的既往就诊和治疗', '查看已知过敏及核实情况'] },

  // ---------- 总部 ----------
  { path: 'hq/clinics', name: 'clinics', title: '诊所', uc: 'UC08', roles: ['HQ'], group: 'base', icon: OfficeBuilding, round: 2, component: ClinicsView,
    plan: ['新增、修改诊所资料', '停用诊所（历史记录保留）'] },
  { path: 'hq/dentists', name: 'dentists', title: '牙医', uc: 'UC09', roles: ['HQ'], group: 'base', icon: Avatar, round: 2, component: DentistsView,
    plan: ['维护牙医个人资料', '维护多项专业资质及核实时间', '停用牙医'] },
  { path: 'hq/treatment-items', name: 'treatment-items', title: '治疗项目与价格', uc: 'UC10', roles: ['HQ'], group: 'base', icon: PriceTag, round: 2, component: TreatmentItemsView,
    plan: ['维护治疗项目、标准单位和数量精度', '新增调价记录并保留价格历史'] },
  { path: 'hq/cancellations', name: 'cancellations', title: '取消就诊统计', uc: 'UC11', roles: ['HQ'], group: 'analysis', icon: DataAnalysis, round: 4, component: CancellationsView,
    plan: ['按期间、诊所统计预约数、取消数和取消率', '分母为零显示“不适用”，期间未结束时提示', '查看某诊所的预约明细'] },
  { path: 'hq/demand', name: 'demand', title: '需求与资质对照', uc: 'UC12', roles: ['HQ'], group: 'analysis', icon: Histogram, round: 4, component: DemandView,
    plan: ['按诊所、项目查看预约意向次数和实际治疗数量', '对照已核实的牙医资质与名单', '列出资质待核实的牙医'] },
  { path: 'hq/quarters', name: 'quarters', title: '季度核算', uc: 'UC13', roles: ['HQ'], group: 'analysis', icon: Coin, round: 4, component: QuartersView,
    plan: ['汇总各牙医已开单收入', '录入医疗用品成本和运营费用并预览利润', '确认核算，记录激励基金或基金处理说明', '显示汇总周期（愿景指标）'] },
  { path: 'hq/users', name: 'users', title: '账号管理', roles: ['HQ'], group: 'system', icon: Key, round: 5, component: UsersView,
    plan: ['开设前台、牙医、总部账号', '停用账号、重置密码'] },
  { path: 'hq/operation-logs', name: 'operation-logs', title: '操作记录', roles: ['HQ'], group: 'system', icon: Document, round: 5, component: OperationLogsView,
    plan: ['按时间、人员、操作、对象查找谁在什么时候做了什么', '查看修改前后的对比', '导出 Excel'] }
]

/** 各岗位登录后的首页 */
export const HOME_BY_ROLE = {
  FRONT_DESK: '/workbench',
  DENTIST: '/workbench',
  HQ: '/workbench'
}

export const HomeIcon = House

export function pagesForRole(role) {
  return pages.filter((p) => p.roles.includes(role))
}
