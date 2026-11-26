// 接口地址：小程序直接访问线上后端（需在小程序后台把 https://zengziyu.cn 设为 request 合法域名，
// 开发阶段也可在开发者工具里勾选“不校验合法域名”）；H5 调试时走 vite 代理，避免跨域
let base = 'https://zengziyu.cn/api'
// #ifdef H5
base = '/api'
// #endif
export const API_BASE = base

export const THEME = '#0E7C74'
export const SITE = {
  name: 'ADC 安德鲁牙科',
  slogan: '一份病历，连锁通用',
  hotline: '020-3888 0000',
  hours: '周一至周日 09:00—20:00',
  web: 'https://zengziyu.cn'
}
