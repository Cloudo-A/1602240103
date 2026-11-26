<script setup>
// 工作台：各岗位登录后的首页，先看今天要处理的事，再进入具体功能
import { computed } from 'vue'
import { auth } from '@/stores/auth.js'
import { roleName } from '@shared/labels.js'
import FrontDeskBench from '@/components/workbench/FrontDeskBench.vue'
import DentistBench from '@/components/workbench/DentistBench.vue'
import HqBench from '@/components/workbench/HqBench.vue'
import '@/components/workbench/bench.css'

const user = computed(() => auth.state.user)
const now = new Date()
const greeting = computed(() => {
  const h = now.getHours()
  if (h < 6) return '夜深了'
  if (h < 11) return '早上好'
  if (h < 13) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})
const WEEK = ['日', '一', '二', '三', '四', '五', '六']
const todayText = `${now.getFullYear()} 年 ${now.getMonth() + 1} 月 ${now.getDate()} 日 星期${WEEK[now.getDay()]}`
</script>

<template>
  <div class="page">
    <div class="hello">
      <div>
        <div class="hello-title">{{ greeting }}，{{ user?.displayName }}</div>
        <div class="muted">{{ todayText }} · {{ roleName(user?.role) }}</div>
      </div>
    </div>
    <FrontDeskBench v-if="user?.role === 'FRONT_DESK'" />
    <DentistBench v-else-if="user?.role === 'DENTIST'" />
    <HqBench v-else-if="user?.role === 'HQ'" />
  </div>
</template>

<style scoped>
.hello {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  margin-bottom: 16px;
}
.hello-title {
  font-size: 20px;
  font-weight: 600;
  margin-bottom: 4px;
}
</style>
