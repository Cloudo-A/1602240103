// 网页和小程序共用：岗位、状态的中文名称（与协作约定第 4、7 节一致）

export const ROLES = {
  FRONT_DESK: '前台工作人员',
  DENTIST: '牙医',
  HQ: '总部管理人员'
}

/** 预约状态 */
export const APPOINTMENT_STATUS = {
  VALID: { text: '有效', type: 'primary' },
  PENDING_VERIFY: { text: '待核实', type: 'warning' },
  CANCELLED: { text: '已取消', type: 'info' },
  ARRIVED: { text: '已到诊', type: 'success' }
}

/** 就诊状态 */
export const VISIT_STATUS = {
  UNCONFIRMED: { text: '诊疗未确认', type: 'warning' },
  CONFIRMED: { text: '已确认待结算', type: 'primary' },
  SETTLED: { text: '已结算', type: 'success' },
  NO_TREATMENT: { text: '未实施治疗结束', type: 'info' }
}

/** 季度核算的基金状态 */
export const FUND_STATUS = {
  ACCRUED: { text: '已计提激励基金', type: 'success' },
  TRANSFERRED: { text: '基金已转总部处理', type: 'warning' }
}

export function roleName(role) {
  return ROLES[role] || role || ''
}

/** 操作记录的操作名（协作约定第 9.14 节）；group 用于筛选下拉分组 */
export const LOG_ACTIONS = {
  CREATE: { text: '新建', type: 'success', group: '基础资料与档案' },
  UPDATE: { text: '修改', type: 'primary', group: '基础资料与档案' },
  ADD_STANDARD_PRICE: { text: '调价', type: 'warning', group: '基础资料与档案' },
  CREATE_APPOINTMENT: { text: '新建预约', type: 'success', group: '前台业务' },
  CANCEL_APPOINTMENT: { text: '取消预约', type: 'danger', group: '前台业务' },
  REGISTER_ARRIVAL: { text: '登记到诊', type: 'primary', group: '前台业务' },
  ISSUE_SETTLEMENT: { text: '开具结算单', type: 'success', group: '前台业务' },
  CONFIRM_VISIT: { text: '确认诊疗', type: 'success', group: '诊疗' },
  END_WITHOUT_TREATMENT: { text: '未实施治疗结束', type: 'info', group: '诊疗' },
  CONFIRM_QUARTER: { text: '确认季度核算', type: 'warning', group: '季度核算' },
  CREATE_USER: { text: '开设账号', type: 'success', group: '账号' },
  UPDATE_USER: { text: '修改账号', type: 'primary', group: '账号' },
  RESET_PASSWORD: { text: '重置密码', type: 'warning', group: '账号' },
  CHANGE_PASSWORD: { text: '修改密码', type: 'info', group: '账号' },
  CONFIRM_APPOINTMENT_REQUEST: { text: '预约申请转预约', type: 'success', group: '前台业务' },
  INVALIDATE_APPOINTMENT_REQUEST: { text: '预约申请判无效', type: 'info', group: '前台业务' }
}

/** 操作记录的对象类型 */
export const LOG_TARGETS = {
  CLINIC: '诊所',
  DENTIST: '牙医',
  QUALIFICATION: '资质',
  TREATMENT_ITEM: '治疗项目',
  PATIENT: '患者档案',
  APPOINTMENT: '预约',
  VISIT: '就诊',
  SETTLEMENT: '结算单',
  QUARTER_CLOSE: '季度核算',
  USER: '账号',
  APPOINTMENT_REQUEST: '预约申请'
}

/** 预约申请状态（协作约定第 7 节）：患者端（官网、小程序）和前台共用 */
export const REQUEST_STATUS = {
  PENDING: { text: '待确认', type: 'warning', hint: '前台会在营业时间内致电与您确认' },
  CONFIRMED: { text: '已确认', type: 'success', hint: '已为您安排预约，请按时到诊' },
  INVALID: { text: '无效', type: 'info', hint: '未能确认，可以重新登记或致电门店' },
  WITHDRAWN: { text: '已撤回', type: 'info', hint: '您已撤回这条预约' }
}

/** 期望时段 */
export const TIME_SLOTS = {
  MORNING: { text: '上午', range: '09:00—12:00' },
  AFTERNOON: { text: '下午', range: '14:00—17:00' },
  EVENING: { text: '傍晚', range: '17:00—20:00' }
}
export const timeSlotText = (v) => (TIME_SLOTS[v] ? `${TIME_SLOTS[v].text} ${TIME_SLOTS[v].range}` : v || '')
