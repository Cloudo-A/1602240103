// 模拟后端：作为 axios 的 adapter 使用，接口路径、字段、错误格式与协作约定一致。
// 页面代码走的是和真实后端完全相同的请求流程，只有最底层的“发送”被换成这里。
import { AxiosError } from 'axios'
import { db, save } from './db.js'
import { routes } from './routes.js'
import { MockError } from './errors.js'
import { ensureLogs, prepareLog, recordLog } from './handlers/logs.js'

const WRITE_METHODS = ['POST', 'PUT']

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

function compile(path) {
  const keys = []
  const pattern = path.replace(/:(\w+)/g, (_, key) => {
    keys.push(key)
    return '([^/]+)'
  })
  return { regex: new RegExp(`^${pattern}$`), keys }
}

const table = routes.map((r) => ({ ...r, ...compile(r.path) }))

function match(method, path) {
  for (const route of table) {
    if (route.method !== method) continue
    const m = path.match(route.regex)
    if (m) {
      const params = {}
      route.keys.forEach((k, i) => (params[k] = decodeURIComponent(m[i + 1])))
      return { route, params }
    }
  }
  return null
}

function bearer(headers) {
  const value = headers.get ? headers.get('Authorization') : headers.Authorization
  return value && String(value).startsWith('Bearer ') ? String(value).slice(7) : null
}

/** 患者端令牌对应的患者账号（协作约定第 9.16 节） */
function currentPatient(headers) {
  const token = bearer(headers)
  const record = token && db.patientTokens?.[token]
  if (!record || new Date(record.expiresAt) < new Date()) return null
  const account = db.patientAccounts.find((a) => a.id === record.accountId)
  return account ? { ...account, token } : null
}

function currentUser(headers) {
  const token = bearer(headers)
  if (!token) return null
  const record = db.tokens[token]
  if (!record || new Date(record.expiresAt) < new Date()) return null
  const user = db.users.find((u) => u.id === record.userId && u.active)
  return user ? { ...user, token } : null
}

function handle(config) {
  const method = (config.method || 'get').toUpperCase()
  const [path] = (config.url || '').split('?')
  const query = { ...(config.params || {}) }
  let body = config.data
  if (typeof body === 'string' && body.length) body = JSON.parse(body)
  if (body === undefined || body === '') body = null

  if (ensureLogs()) save() // 第一次请求（或重置模拟数据后）补出示例操作记录

  const found = match(method, path)
  if (!found) throw new MockError(404, 'NOT_FOUND', `模拟后端尚未提供接口 ${method} ${path}`)
  const { route, params } = found

  // 三类接口：公开（免登录）、患者端（患者令牌）、员工端（员工令牌）；两类令牌互相不能访问对方的接口
  const hdrs = config.headers || {}
  let user = null
  let patient = null
  if (route.patient) {
    patient = currentPatient(hdrs)
    if (!patient) {
      if (currentUser(hdrs)) throw new MockError(403, 'FORBIDDEN', '员工账号不能使用患者端功能')
      throw new MockError(401, 'UNAUTHENTICATED', '登录已过期，请重新登录')
    }
  } else if (!route.public) {
    user = currentUser(hdrs)
    if (!user) {
      if (currentPatient(hdrs)) throw new MockError(403, 'FORBIDDEN', '患者账号无权访问此功能')
      throw new MockError(401, 'UNAUTHENTICATED', '登录已过期，请重新登录')
    }
    if (route.roles && !route.roles.includes(user.role)) throw new MockError(403, 'FORBIDDEN', '当前岗位无权办理此业务')
  }

  // 请求标识去重（协作约定第 6 节第 3 条）
  const headers = config.headers || {}
  const requestId = WRITE_METHODS.includes(method) ? (headers.get ? headers.get('X-Request-Id') : headers['X-Request-Id']) : null
  const fingerprint = JSON.stringify({ method, path, body, who: patient ? `P${patient.id}` : `U${user?.id ?? ''}` })
  if (requestId && db.requests[requestId]) {
    const seen = db.requests[requestId]
    if (seen.fingerprint !== fingerprint) throw new MockError(409, 'DUPLICATE_REQUEST', '同一请求标识被用于不同内容，请重新操作')
    return { status: seen.status, data: seen.data }
  }

  // 操作记录（协作约定第 10 节）：执行前取“修改前”的样子，成功后写一条；失败（抛错）不记
  const pendingLog = WRITE_METHODS.includes(method) ? prepareLog(method, route.path, params, user) : null
  const result = route.handler({ params, query, body, user, patient, requestId })
  const response = result && result.__status ? { status: result.__status, data: result.data } : { status: 200, data: result ?? null }
  recordLog(pendingLog, { params, user, result: response.data, body })

  if (requestId) db.requests[requestId] = { fingerprint, status: response.status, data: response.data }
  if (method !== 'GET') save()
  return response
}

export async function mockAdapter(config) {
  await delay(120 + Math.random() * 220)
  let status
  let data
  try {
    ;({ status, data } = handle(config))
  } catch (e) {
    if (e instanceof MockError) {
      status = e.status
      data = e.body
    } else {
      console.error('[模拟后端] 处理出错', e)
      status = 500
      data = { code: 'INTERNAL', message: '系统错误，请稍后重试', fieldErrors: null, current: null }
    }
  }
  // 模拟网络传输：返回深拷贝，避免页面直接改到模拟数据库里的对象
  const response = { data: data === null ? null : JSON.parse(JSON.stringify(data)), status, statusText: String(status), headers: {}, config, request: {} }
  if (status >= 400) {
    throw new AxiosError(data?.message || 'Request failed', status >= 500 ? AxiosError.ERR_BAD_RESPONSE : AxiosError.ERR_BAD_REQUEST, config, {}, response)
  }
  return response
}
