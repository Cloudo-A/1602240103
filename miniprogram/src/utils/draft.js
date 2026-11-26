// 从首页带到预约页的预选内容（switchTab 不能带参数，用共享状态传递）
import { reactive } from 'vue'

export const draft = reactive({ clinicId: null, itemIds: [] })
