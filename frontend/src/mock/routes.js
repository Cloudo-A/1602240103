// 模拟接口清单。每一轮补充对应模块的接口，路径与协作约定第 9 节一致。
import { authRoutes } from './handlers/auth.js'
import { baseRoutes } from './handlers/base.js'
import { patientRoutes } from './handlers/patients.js'
import { businessRoutes } from './handlers/business.js'
import { statsRoutes } from './handlers/stats.js'
import { userRoutes } from './handlers/users.js'
import { logRoutes } from './handlers/logs.js'
import { portalRoutes } from './handlers/portal.js'

export const routes = [...authRoutes, ...baseRoutes, ...patientRoutes, ...businessRoutes, ...statsRoutes, ...userRoutes, ...logRoutes, ...portalRoutes]
