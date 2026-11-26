// 官网的门店、医生、项目价格：读后端公开接口（协作约定第 9.15 节）。
// 后端暂时不可用时用示例数据兜底，页面不会空着；sample 为 true 时页面可以据此提示。
import { reactive } from 'vue'
import { publicApi } from '@/api/portal.js'
import { CLINICS, DENTISTS, ITEMS } from './portalData.js'

const state = reactive({ clinics: CLINICS, dentists: DENTISTS, items: ITEMS, loaded: false, sample: true })
let loading = null

export function usePortalData() {
  if (!loading) {
    loading = Promise.all([publicApi.clinics(), publicApi.dentists(), publicApi.treatmentItems()])
      .then(([clinics, dentists, items]) => {
        state.clinics = clinics
        state.dentists = dentists
        state.items = items
        state.sample = false
      })
      .catch((e) => {
        console.warn('[官网] 公开接口暂不可用，先显示示例数据：', e?.message)
        loading = null // 下次进入页面再试
      })
      .finally(() => (state.loaded = true))
  }
  return state
}
