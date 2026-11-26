// 模拟：账号管理（总部）与修改密码（三种岗位），协作约定第 9.13 节
import { db, nextId } from '../db.js'
import { notFound, conflict, stateNotAllowed, invalid } from '../errors.js'
import { createChecker, checkVersion } from '../validate.js'

const HQ = ['HQ']
const ROLES = ['FRONT_DESK', 'DENTIST', 'HQ']
const USERNAME_RE = /^[A-Za-z0-9_]{3,40}$/

export function userView(u) {
  const d = u.dentistId ? db.dentists.find((x) => x.id === u.dentistId) : null
  return {
    id: u.id,
    username: u.username,
    displayName: u.displayName,
    role: u.role,
    dentist: d ? { id: d.id, employeeNo: d.employeeNo, name: d.name } : null,
    active: u.active,
    version: u.version ?? 0
  }
}

/** 作废某个账号的令牌；keep 为要保留的令牌（修改密码时保留当前这一个） */
function revokeTokens(userId, keep = null) {
  Object.keys(db.tokens).forEach((t) => {
    if (db.tokens[t].userId === userId && t !== keep) delete db.tokens[t]
  })
}

function checkPassword(c, field, label) {
  const v = c.data[field]
  if (typeof v !== 'string' || !v) c.add(field, `请填写${label}`)
  else if (v.length < 6 || v.length > 64) c.add(field, `${label}应为 6—64 位`)
  else if (new TextEncoder().encode(v).length > 72) c.add(field, '密码太长（最多 72 个字节，约 24 个汉字）')
  return v
}

/** 牙医岗位绑定的牙医：必须存在、未被其他账号绑定；新绑定的必须启用（保持原来绑定的已停用牙医不算，否则停不掉离职牙医的账号） */
function checkDentistBinding(c, role, dentistId, selfId = null) {
  if (role !== 'DENTIST') {
    if (dentistId !== null && dentistId !== undefined) c.add('dentistId', '只有牙医岗位才能绑定牙医')
    return null
  }
  if (!dentistId) {
    c.add('dentistId', '请选择这个账号对应的牙医')
    return null
  }
  const d = db.dentists.find((x) => x.id === Number(dentistId))
  if (!d) c.add('dentistId', '牙医不存在')
  else if (!d.active && db.users.find((u) => u.id === selfId)?.dentistId !== d.id) c.add('dentistId', `${d.name}已停用，不能绑定新账号`)
  return d ? { dentist: d, bound: db.users.find((u) => u.dentistId === d.id && u.id !== selfId) } : null
}

function createUser({ body }) {
  const c = createChecker(body)
  const username = c.text('username', '登录名', { max: 40 })
  if (username && !USERNAME_RE.test(username)) c.add('username', '登录名为 3—40 位字母、数字或下划线')
  c.text('displayName', '显示姓名', { max: 60 })
  const role = body?.role
  if (!ROLES.includes(role)) c.add('role', '请选择岗位')
  const binding = checkDentistBinding(c, role, body?.dentistId)
  checkPassword(c, 'password', '初始密码')
  c.done('请检查账号信息')
  if (db.users.some((u) => u.username.toLowerCase() === username.toLowerCase())) throw conflict(`登录名“${username}”已被使用`)
  if (binding?.bound) throw conflict(`${binding.dentist.name}已经绑定了账号 ${binding.bound.username}`)
  const user = {
    id: nextId('user'),
    username,
    password: body.password,
    displayName: body.displayName.trim(),
    role,
    dentistId: role === 'DENTIST' ? Number(body.dentistId) : null,
    active: true,
    version: 0
  }
  db.users.push(user)
  return { __status: 201, data: userView(user) }
}

function updateUser({ params, body, user: me }) {
  const u = db.users.find((x) => x.id === Number(params.id))
  if (!u) throw notFound('账号不存在')
  checkVersion(body, { ...u, version: u.version ?? 0 }, () => userView(u))
  const c = createChecker(body)
  c.text('displayName', '显示姓名', { max: 60 })
  const active = c.bool('active', '使用状态')
  const binding = checkDentistBinding(c, u.role, body?.dentistId, u.id)
  c.done('请检查账号信息')
  if (binding?.bound) throw conflict(`${binding.dentist.name}已经绑定了账号 ${binding.bound.username}`)
  if (u.id === me.id && !active) throw stateNotAllowed('不能停用自己正在使用的账号')
  u.displayName = body.displayName.trim()
  if (u.role === 'DENTIST') u.dentistId = Number(body.dentistId)
  if (u.active && !active) revokeTokens(u.id) // 停用后已登录的设备立即下线
  u.active = active
  u.version = (u.version ?? 0) + 1
  return userView(u)
}

function resetPassword({ params, body }) {
  const u = db.users.find((x) => x.id === Number(params.id))
  if (!u) throw notFound('账号不存在')
  const c = createChecker(body)
  checkPassword(c, 'newPassword', '新密码')
  c.done('请检查新密码')
  u.password = body.newPassword
  u.version = (u.version ?? 0) + 1
  revokeTokens(u.id)
  return userView(u)
}

function changePassword({ body, user: me }) {
  const u = db.users.find((x) => x.id === me.id)
  const c = createChecker(body)
  if (!body?.oldPassword) c.add('oldPassword', '请填写原密码')
  checkPassword(c, 'newPassword', '新密码')
  c.done('请检查填写的密码')
  if (body.oldPassword !== u.password) throw invalid('原密码不正确', [{ field: 'oldPassword', message: '原密码不正确' }])
  if (body.newPassword === u.password) throw invalid('新密码不能和原密码相同', [{ field: 'newPassword', message: '新密码不能和原密码相同' }])
  u.password = body.newPassword
  u.version = (u.version ?? 0) + 1
  revokeTokens(u.id, me.token) // 其他设备下线，当前这台继续使用
  return { ok: true }
}

export const userRoutes = [
  { method: 'GET', path: '/users', roles: HQ, handler: () => [...db.users].sort((a, b) => a.id - b.id).map(userView) },
  { method: 'POST', path: '/users', roles: HQ, handler: createUser },
  { method: 'PUT', path: '/users/:id', roles: HQ, handler: updateUser },
  { method: 'POST', path: '/users/:id/reset-password', roles: HQ, handler: resetPassword },
  { method: 'POST', path: '/auth/change-password', handler: changePassword }
]
