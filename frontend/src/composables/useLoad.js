// 只读加载的小工具：统一管理 加载中 / 加载失败原因 / 数据，供工作台等只读卡片使用
// 连续触发时只采用最后一次的结果
import { ref } from 'vue'

export function useLoad(fn, initial = null) {
  const data = ref(initial)
  const loading = ref(false)
  const error = ref('')
  let seq = 0

  async function load(...args) {
    const mine = ++seq
    loading.value = true
    error.value = ''
    try {
      const result = await fn(...args)
      if (mine === seq) data.value = result
    } catch (e) {
      if (mine === seq) {
        data.value = initial
        error.value = e.message || '请稍后重试'
      }
    } finally {
      if (mine === seq) loading.value = false
    }
  }

  return { data, loading, error, load }
}
