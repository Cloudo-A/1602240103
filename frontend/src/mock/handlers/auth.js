// 模拟：登录、当前用户、退出（协作约定第 4 节）
import { db } from '../db.js'
import { MockError, invalid } from '../errors.js'
import { toApiDateTime } from '@shared/format.js'
import { newRequestId } from '@shared/requestId.js'

const TOKEN_HOURS = 12

function publicUser(u) {
  return { id: u.id, username: u.username, displayName: u.displayName, role: u.role, dentistId: u.dentistId }
}

export const authRoutes = [
  {
    method: 'POST',
    path: '/auth/login',
    public: true,
    handler({ body }) {
      const username = (body?.username || '').trim()
      const password = body?.password || ''
      if (!username || !password) {
        throw invalid('请输入登录名和密码', [
          ...(!username ? [{ field: 'username', message: '请输入登录名' }] : []),
          ...(!password ? [{ field: 'password', message: '请输入密码' }] : [])
        ])
      }
      const user = db.users.find((u) => u.username === username)
      if (!user || user.password !== password) throw new MockError(401, 'UNAUTHENTICATED', '用户名或密码错误')
      if (!user.active) throw new MockError(401, 'UNAUTHENTICATED', '账号已停用，请联系总部')
      const token = newRequestId().replace(/-/g, '')
      const expiresAt = toApiDateTime(new Date(Date.now() + TOKEN_HOURS * 3600 * 1000))
      db.tokens[token] = { userId: user.id, expiresAt }
      return { token, expiresAt, user: publicUser(user) }
    }
  },
  {
    method: 'GET',
    path: '/auth/me',
    handler({ user }) {
      return publicUser(user)
    }
  },
  {
    method: 'POST',
    path: '/auth/logout',
    handler({ user }) {
      delete db.tokens[user.token] // 立即作废令牌
      return null
    }
  }
]
