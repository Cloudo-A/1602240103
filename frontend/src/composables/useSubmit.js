// 保存类操作的统一处理（协作约定第 5、6 节）：
// - 每次“确认保存”生成一个请求标识；网络中断导致结果不确定时，保留同一个标识，
//   用户直接再点一次保存，后端会识别为同一请求，不会重复保存
// - 400 的字段错误显示在对应输入框下面
// - 409 CHANGED（别人刚改过）交给页面刷新后让用户重新核对
import { reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { newRequestId } from '@shared/requestId.js'

export function useSubmit() {
  const saving = ref(false)
  const fieldErrors = reactive({})
  let requestId = null

  function clearErrors() {
    Object.keys(fieldErrors).forEach((k) => delete fieldErrors[k])
  }

  /** 打开新表单时调用：清空字段错误，丢弃上一次的请求标识 */
  function reset() {
    clearErrors()
    requestId = null
  }

  /**
   * 执行保存。fn(opts) 发出请求，opts 里带 requestId。
   * handle(e) 返回 true 表示页面已自己处理这个错误（例如疑似重复、记录已变化）。
   * 成功返回接口结果；失败返回 undefined（错误已提示）。
   */
  async function run(fn, { handle } = {}) {
    if (saving.value) return undefined
    clearErrors()
    requestId = requestId || newRequestId()
    saving.value = true
    try {
      const result = await fn({ requestId })
      requestId = null
      return result
    } catch (e) {
      if (e.status === 401) return undefined // 已统一跳转登录页
      if (e.uncertain) {
        // 保留 requestId：再点一次保存是安全的
        ElMessage({ type: 'warning', duration: 6000, message: '网络中断，暂时不确定是否已保存。可以直接再点一次“保存”，系统不会重复保存。' })
        return undefined
      }
      requestId = null
      if (e.code === 'INVALID_INPUT' && Array.isArray(e.fieldErrors)) {
        e.fieldErrors.forEach((f) => {
          fieldErrors[f.field] = f.message
          // 列表里某一行的错误（例如 qualifications[0].qualificationId）同时显示在整个列表下面
          const listMatch = /^([^.[]+)\[\d+\]/.exec(f.field)
          if (listMatch && !fieldErrors[listMatch[1]]) fieldErrors[listMatch[1]] = f.message
        })
      }
      if (handle && handle(e)) return undefined
      if (e.code === 'DUPLICATE_REQUEST') {
        ElMessage.warning('内容在重试前被改动过，请先刷新确认上一次是否已保存，再重新提交')
        return undefined
      }
      ElMessage({ type: 'error', message: e.message, grouping: true, duration: 4000 })
      return undefined
    } finally {
      saving.value = false
    }
  }

  return { saving, fieldErrors, run, reset, clearErrors }
}

/**
 * 处理 409 CHANGED：提示用户记录刚被别人修改，用最新内容 current 刷新表单，让用户核对后再保存。
 * 返回 true 表示已处理，可直接作为 run 的 handle 使用。
 */
export function handleChanged(e, apply) {
  if (e.code !== 'CHANGED') return false
  ElMessageBox.alert(e.message || '这条记录刚被其他人修改过，已显示最新内容，请核对后再保存', '记录已变化', {
    type: 'warning',
    confirmButtonText: '我知道了'
  }).catch(() => {})
  if (e.current) apply(e.current)
  return true
}
