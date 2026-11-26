<script setup>
// 已知过敏提示框：有过敏时醒目显示；区分“牙医已核实”和“前台登记、尚未核实”
import { Warning } from '@element-plus/icons-vue'
import { dateTime } from '@shared/format.js'

defineProps({
  allergies: { type: Array, default: () => [] },
  verifiedAt: { type: String, default: null }
})
</script>

<template>
  <div class="allergy-box" :class="{ unverified: !verifiedAt, has: allergies.length }">
    <div class="allergy-title">
      <el-icon><Warning /></el-icon>
      已知过敏
    </div>
    <div class="allergy-body">
      <template v-if="allergies.length">
        <el-tag v-for="a in allergies" :key="a.id" type="danger" effect="light" class="allergy-tag">{{ a.name }}</el-tag>
      </template>
      <span v-else>{{ verifiedAt ? '无已知过敏' : '未登记过敏项目' }}</span>
    </div>
    <div class="allergy-verify">
      <template v-if="verifiedAt">牙医已于 {{ dateTime(verifiedAt) }} 核实</template>
      <template v-else>尚未经牙医核实：{{ allergies.length ? '以上为前台登记的内容' : '“未登记”不等于没有过敏' }}</template>
    </div>
  </div>
</template>

<style scoped>
.allergy-box {
  border-radius: 10px;
  padding: 12px 14px;
  background: var(--el-fill-color-lighter);
  border: 1px solid var(--el-border-color-lighter);
}
.allergy-box.has {
  background: var(--el-color-danger-light-9);
  border-color: var(--el-color-danger-light-7);
}
.allergy-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 600;
  margin-bottom: 8px;
}
.allergy-box.has .allergy-title {
  color: var(--el-color-danger);
}
.allergy-tag {
  margin: 0 6px 6px 0;
}
.allergy-verify {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.allergy-box.unverified .allergy-verify {
  color: var(--el-color-warning-dark-2);
}
</style>
