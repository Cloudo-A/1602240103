// 网页和小程序共用的显示格式。只负责“怎么显示”，不做业务计算。

function pad(n) {
  return String(n).padStart(2, '0')
}

/** 金额：120 → "120.00"；null → 占位符 */
export function money(value, empty = '—') {
  if (value === null || value === undefined || value === '') return empty
  const n = Number(value)
  if (Number.isNaN(n)) return empty
  return n.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

/** 比率：0.1 → "10.00%"；null → "不适用"（取消率分母为 0 时） */
export function rate(value, empty = '不适用') {
  if (value === null || value === undefined) return empty
  return (Number(value) * 100).toFixed(2) + '%'
}

/** 后端日期时间 "2026-11-20T09:30:00" → "2026-11-20 09:30" */
export function dateTime(value, empty = '—') {
  if (!value) return empty
  return String(value).replace('T', ' ').slice(0, 16)
}

/** 只取时间部分 "09:30" */
export function timeOnly(value, empty = '—') {
  if (!value) return empty
  return String(value).slice(11, 16)
}

/** 日期 "2026-11-20" */
export function date(value, empty = '—') {
  if (!value) return empty
  return String(value).slice(0, 10)
}

/** 把 Date 转成接口用的本地日期 "YYYY-MM-DD" */
export function toApiDate(d = new Date()) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** 把 Date 转成接口用的本地日期时间 "YYYY-MM-DDTHH:mm:ss" */
export function toApiDateTime(d = new Date()) {
  return `${toApiDate(d)}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 数量：去掉多余的 0，0.500000 → "0.5" */
export function quantity(value, empty = '—') {
  if (value === null || value === undefined) return empty
  return String(Number(value))
}

/** 由出生日期 "1990-05-01" 算周岁；today 默认今天 */
export function age(birthDate, today = new Date()) {
  if (!birthDate) return null
  const [y, m, d] = String(birthDate).slice(0, 10).split('-').map(Number)
  let years = today.getFullYear() - y
  if (today.getMonth() + 1 < m || (today.getMonth() + 1 === m && today.getDate() < d)) years -= 1
  return years
}

/** 数量精度的文字：0 → "整数"，1 → "1 位小数" */
export function scaleText(scale) {
  return Number(scale) === 0 ? '整数' : `${scale} 位小数`
}
