// 模拟后端的输入校验：收集字段错误，统一以 400 INVALID_INPUT 返回（协作约定第 5 节）
import { invalid, changed } from './errors.js'

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/
const DATETIME_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/

export function createChecker(body) {
  const errors = []
  const data = body || {}
  const add = (field, message) => {
    if (!errors.some((e) => e.field === field)) errors.push({ field, message })
  }
  const str = (v) => (typeof v === 'string' ? v.trim() : v)

  return {
    data,
    add,
    /** 必填文本，可限制最大长度 */
    text(field, label, { required = true, max = 255 } = {}) {
      const v = str(data[field])
      if (v === undefined || v === null || v === '') {
        if (required) add(field, `${label}不能为空`)
        return null
      }
      if (typeof v !== 'string') add(field, `${label}格式不正确`)
      else if (v.length > max) add(field, `${label}不能超过 ${max} 个字`)
      return v
    },
    email(field, label, { required = true } = {}) {
      const v = this.text(field, label, { required, max: 254 })
      if (v && !EMAIL_RE.test(v)) add(field, `${label}格式不正确`)
      return v
    },
    date(field, label, { required = true } = {}) {
      const v = data[field]
      if (!v) {
        if (required) add(field, `${label}不能为空`)
        return null
      }
      if (!DATE_RE.test(v)) add(field, `${label}格式应为 YYYY-MM-DD`)
      return v
    },
    dateTime(field, label, { required = true } = {}) {
      const v = data[field]
      if (!v) {
        if (required) add(field, `${label}不能为空`)
        return null
      }
      if (!DATETIME_RE.test(v)) add(field, `${label}格式不正确`)
      return v
    },
    number(field, label, { required = true, min, minExclusive, max, scale } = {}) {
      const v = data[field]
      if (v === undefined || v === null || v === '') {
        if (required) add(field, `${label}不能为空`)
        return null
      }
      const n = Number(v)
      if (Number.isNaN(n)) {
        add(field, `${label}必须是数字`)
        return null
      }
      if (minExclusive !== undefined && !(n > minExclusive)) add(field, `${label}必须大于 ${minExclusive}`)
      if (min !== undefined && n < min) add(field, `${label}不能小于 ${min}`)
      if (max !== undefined && n > max) add(field, `${label}不能大于 ${max}`)
      if (scale !== undefined && Math.round(n * 10 ** scale) !== n * 10 ** scale) add(field, `${label}最多保留 ${scale} 位小数`)
      return n
    },
    bool(field, label, { required = true } = {}) {
      const v = data[field]
      if (typeof v !== 'boolean') {
        if (required || v !== undefined) add(field, `${label}必须选择`)
        return null
      }
      return v
    },
    /** 有错误就抛出 400 */
    done(message = '请检查填写的内容') {
      if (errors.length) throw invalid(message, errors)
    }
  }
}

/** 乐观锁：请求带回的 version 与当前不一致时返回 409 CHANGED，current 为最新内容 */
export function checkVersion(body, record, view) {
  if (body?.version === undefined || body?.version === null) throw invalid('缺少版本号，请刷新后重试', [{ field: 'version', message: '缺少版本号' }])
  if (Number(body.version) !== record.version) {
    throw changed('这条记录刚被其他人修改过，已显示最新内容，请核对后再保存', view(record))
  }
}
