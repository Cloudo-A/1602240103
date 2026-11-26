<script setup>
// 选择患者：输入姓名、电话或账号远程查找（协作约定 9.5 的查询接口）
import { ref, watch } from 'vue'
import { patientApi } from '@/api/base.js'
import { date } from '@shared/format.js'

const props = defineProps({
  modelValue: { type: Number, default: null },
  /** 已选中的患者摘要，用于回显（例如从别的页面带过来） */
  initial: { type: Object, default: null },
  placeholder: { type: String, default: '输入姓名、电话或账号查找患者' }
})
const emit = defineEmits(['update:modelValue', 'select'])

const loading = ref(false)
const options = ref(props.initial ? [props.initial] : [])

watch(
  () => props.initial,
  (p) => {
    if (p && !options.value.some((o) => o.id === p.id)) options.value = [p, ...options.value]
  }
)

let seq = 0
async function search(keyword) {
  const k = keyword.trim()
  if (!k) return
  const mine = ++seq
  loading.value = true
  try {
    const result = await patientApi.search({ keyword: k, page: 0, size: 20 })
    if (mine === seq) options.value = result.items
  } catch {
    /* 已统一提示 */
  } finally {
    if (mine === seq) loading.value = false
  }
}

function onChange(id) {
  emit('update:modelValue', id ?? null)
  emit('select', options.value.find((o) => o.id === id) || null)
}
</script>

<template>
  <el-select
    :model-value="modelValue"
    filterable
    remote
    clearable
    reserve-keyword
    :remote-method="search"
    :loading="loading"
    :placeholder="placeholder"
    no-data-text="没有找到，请换个关键字"
    loading-text="正在查找…"
    @change="onChange"
  >
    <el-option v-for="p in options" :key="p.id" :value="p.id" :label="`${p.name}（${p.accountNo}）`">
      <div class="picker-row">
        <span>{{ p.name }} <span class="muted">{{ p.accountNo }}</span></span>
        <span class="muted">{{ date(p.birthDate) }}<template v-if="p.phone"> · {{ p.phone }}</template></span>
      </div>
    </el-option>
  </el-select>
</template>

<style scoped>
.picker-row {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}
</style>
