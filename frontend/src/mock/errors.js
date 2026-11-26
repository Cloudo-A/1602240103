// 模拟后端抛出的错误，响应体格式与协作约定第 5 节一致
export class MockError extends Error {
  constructor(status, code, message, { fieldErrors = null, current = null } = {}) {
    super(message)
    this.status = status
    this.body = { code, message, fieldErrors, current }
  }
}

export const invalid = (message, fieldErrors = null) => new MockError(400, 'INVALID_INPUT', message, { fieldErrors })
export const notFound = (message = '要查找的记录不存在') => new MockError(404, 'NOT_FOUND', message)
export const stateNotAllowed = (message) => new MockError(409, 'STATE_NOT_ALLOWED', message)
export const changed = (message, current) => new MockError(409, 'CHANGED', message, { current })
export const conflict = (message, current = null) => new MockError(409, 'CONFLICT', message, { current })
