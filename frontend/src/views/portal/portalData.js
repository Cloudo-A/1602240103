// 官网首页用到的内容。
// 门店、医生、项目价格目前是与演示数据一致的示例，第 3 步改为读后端公开接口（总部改资料，官网跟着变）。

export const SITE = {
  name: 'ADC 安德鲁牙科',
  fullName: '安德鲁牙科连锁诊所',
  hotline: '020-3888 0000',
  hours: '周一至周日 09:00—20:00'
}

export const FEATURES = [
  { icon: 'Connection', title: '一份病历，连锁通用', text: '无论在哪家门店就诊，牙医都能查阅您在 ADC 的历次就诊、过敏信息和治疗记录，换门店不用重复说明病史。' },
  { icon: 'Tickets', title: '收费透明，逐项列明', text: '全连锁统一标准价格，每次就诊开具结算单，列出项目、数量、单价和小计，费用一目了然。' },
  { icon: 'Medal', title: '资质核实，专业对口', text: '牙医的专业资质由总部逐项核实后才对外展示，预约时按您的需求安排合适的牙医。' },
  { icon: 'FirstAidKit', title: '过敏核对，安心就诊', text: '每次诊疗前核对已知过敏，未核实的信息不会被当作“无过敏”，把安全放在第一位。' }
]

// 以下三组是“后端公开接口暂不可用时”的兜底示例，字段与协作约定第 9.15 节一致
export const CLINICS = [
  { id: 1, name: '天河诊所', postalAddress: '广州市天河区天河路 385 号 3 楼', phone: '020-38880001' },
  { id: 2, name: '越秀诊所', postalAddress: '广州市越秀区中山五路 68 号 2 楼', phone: '020-83330002' },
  { id: 3, name: '福田诊所', postalAddress: '深圳市福田区深南大道 6008 号 5 楼', phone: '0755-82220003' }
]

export const DENTISTS = [
  { id: 1, name: '林嘉文', employeeNo: 'D001', qualifications: ['口腔全科', '牙体牙髓'] },
  { id: 2, name: '周明远', employeeNo: 'D002', qualifications: ['口腔全科', '口腔种植'] },
  { id: 3, name: '黄晓雯', employeeNo: 'D003', qualifications: ['口腔全科'] },
  { id: 4, name: '吴志强', employeeNo: 'D004', qualifications: ['口腔全科', '儿童口腔'] }
]

export const ITEMS = [
  { id: 1, name: '口腔检查', standardUnit: '次', unitPrice: 50 },
  { id: 2, name: '洁牙', standardUnit: '次', unitPrice: 150 },
  { id: 3, name: '树脂补牙', standardUnit: '颗', unitPrice: 280 },
  { id: 4, name: '根管治疗', standardUnit: '根', unitPrice: 600 },
  { id: 5, name: '拔牙', standardUnit: '颗', unitPrice: 200 },
  { id: 6, name: '局部麻醉', standardUnit: '毫升', unitPrice: 35 },
  { id: 7, name: '正畸复诊调整', standardUnit: '次', unitPrice: 400 }
]

/** 按资质生成的医生介绍（系统里没有“简介”字段，官网按已核实资质给出一句说明） */
const QUAL_INTRO = {
  口腔全科: '口腔常见病的检查、诊断与综合治疗',
  牙体牙髓: '龋齿、牙髓病的诊治与根管治疗',
  口腔种植: '缺失牙的种植修复方案',
  口腔正畸: '牙齿排列与咬合的矫正',
  儿童口腔: '儿童口腔保健与乳牙、恒牙期治疗'
}
export function dentistIntro(d) {
  const special = d.qualifications.filter((q) => q !== '口腔全科')
  const pick = special.length ? special : d.qualifications
  return pick.length ? `擅长${pick.map((q) => QUAL_INTRO[q] || q).join('；')}。` : '资质核实中，详情请咨询门店。'
}

export const STEPS = [
  { icon: 'Calendar', title: '预约', text: '官网、小程序提交预约申请，或直接致电门店。前台回电确认门店、牙医和时间。' },
  { icon: 'Location', title: '到诊', text: '按约到店，前台核对身份并登记到诊，首次就诊同步建立患者档案。' },
  { icon: 'FirstAidKit', title: '诊疗', text: '牙医核对过敏与病史，记录每一项实际治疗，诊疗结束后确认。' },
  { icon: 'Tickets', title: '结算', text: '前台开具结算单，逐项列明费用；单号可追溯到本次就诊。' }
]

