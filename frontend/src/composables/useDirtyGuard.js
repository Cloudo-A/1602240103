// 未保存修改提醒（报告第十二章：工作副本有未提交的变更时，离开前提示可能丢失输入）
// 用法：打开表单填好初值后调用 mark()；保存成功后调用 clear()；
// 弹窗用 :before-close="guard.beforeClose"，“取消”按钮用 guard.close(() => (open = false))
import { onBeforeUnmount, onMounted } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'

export function useDirtyGuard(source, { routeLeave = false } = {}) {
  let snapshot = null

  const mark = () => {
    snapshot = JSON.stringify(source())
  }
  const clear = () => {
    snapshot = null
  }
  const isDirty = () => snapshot !== null && JSON.stringify(source()) !== snapshot

  async function confirmLeave(message = '有修改还没有保存，离开后这些修改会丢失。确定离开吗？', confirmText = '不保存，离开') {
    if (!isDirty()) return true
    try {
      await ElMessageBox.confirm(message, '还没保存', { type: 'warning', confirmButtonText: confirmText, cancelButtonText: '继续编辑' })
      clear()
      return true
    } catch {
      return false
    }
  }

  /** 给 el-dialog 的 before-close 使用（点右上角关闭、按 Esc） */
  const beforeClose = async (done) => {
    if (await confirmLeave('有修改还没有保存，关闭后这些修改会丢失。确定关闭吗？', '不保存，关闭')) done()
  }
  /** 给“取消”按钮使用 */
  const close = (doClose) => beforeClose(doClose)

  // 刷新或关闭浏览器标签页时，由浏览器弹出它自己的提示
  const onUnload = (e) => {
    if (isDirty()) {
      e.preventDefault()
      e.returnValue = ''
    }
  }
  onMounted(() => window.addEventListener('beforeunload', onUnload))
  onBeforeUnmount(() => window.removeEventListener('beforeunload', onUnload))

  // 切换菜单（路由离开）时提醒
  if (routeLeave) onBeforeRouteLeave(() => confirmLeave())

  return { mark, clear, isDirty, confirmLeave, beforeClose, close }
}
