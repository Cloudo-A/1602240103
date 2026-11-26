<script setup>
// 数字滚动：进入视野时从 0 滚到目标值；目标值变化（示例数据换成真实数据）时从当前值继续滚动
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({ value: { type: [Number, String], default: 0 }, duration: { type: Number, default: 1200 } })
const el = ref()
const shown = ref(0)
let started = false
let raf = 0
let io

function run() {
  const to = Number(props.value) || 0
  const from = shown.value
  const t0 = performance.now()
  cancelAnimationFrame(raf)
  const step = (t) => {
    const k = Math.min(1, (t - t0) / props.duration)
    shown.value = Math.round(from + (to - from) * (1 - Math.pow(1 - k, 3)))
    if (k < 1) raf = requestAnimationFrame(step)
  }
  raf = requestAnimationFrame(step)
}
watch(() => props.value, () => started && run())
onMounted(() => {
  io = new IntersectionObserver(([e]) => {
    if (e.isIntersecting && !started) {
      started = true
      run()
      io.disconnect()
    }
  }, { threshold: 0.6 })
  io.observe(el.value)
})
onBeforeUnmount(() => { cancelAnimationFrame(raf); io?.disconnect() })
</script>

<template>
  <span ref="el">{{ shown }}</span>
</template>
