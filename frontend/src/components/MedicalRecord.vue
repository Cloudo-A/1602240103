<script setup>
// 患者跨诊所的既往诊疗资料（UC05，协作约定 9.7）：已知过敏 + 按到诊时间从近到远的就诊记录
import { ref, watch } from 'vue'
import { recordApi } from '@/api/business.js'
import AllergyBox from '@/components/AllergyBox.vue'
import { VISIT_STATUS } from '@shared/labels.js'
import { age, dateTime, money, quantity } from '@shared/format.js'

const props = defineProps({
  patientId: { type: Number, required: true },
  /** 当前正在接诊的就诊，在记录里标出“本次” */
  currentVisitId: { type: Number, default: null }
})
const emit = defineEmits(['loaded'])

const loading = ref(false)
const record = ref(null)
const failed = ref(false)

async function load() {
  loading.value = true
  failed.value = false
  try {
    record.value = await recordApi.get(props.patientId)
    emit('loaded', record.value.patient)
  } catch {
    record.value = null
    failed.value = true
  } finally {
    loading.value = false
  }
}
watch(() => props.patientId, load, { immediate: true })

const total = (v) => v.treatments.reduce((s, t) => s + Number(t.subtotal), 0)
defineExpose({ reload: load })
</script>

<template>
  <div v-loading="loading" class="record">
    <template v-if="record">
      <div class="record-head">
        <div class="avatar">{{ record.patient.name.slice(0, 1) }}</div>
        <div>
          <div class="name">{{ record.patient.name }} <span class="muted">{{ record.patient.accountNo }}</span></div>
          <div class="muted">{{ record.patient.birthDate }}（{{ age(record.patient.birthDate) }} 岁）· 既往就诊 {{ record.visits.length }} 次</div>
        </div>
      </div>
      <AllergyBox :allergies="record.allergies" :verified-at="record.allergyVerifiedAt" class="record-allergy" />

      <el-empty v-if="!record.visits.length" description="还没有已确认的就诊记录" :image-size="72" />
      <el-timeline v-else class="timeline">
        <el-timeline-item v-for="v in record.visits" :key="v.visitId" :timestamp="dateTime(v.arrivedAt)" placement="top" :type="v.visitId === currentVisitId ? 'primary' : ''" :hollow="v.status === 'NO_TREATMENT'">
          <div class="visit-card" :class="{ current: v.visitId === currentVisitId }">
            <div class="visit-top">
              <span>{{ v.clinic.name }} · {{ v.dentist.name }}</span>
              <span>
                <el-tag v-if="v.visitId === currentVisitId" size="small" effect="dark">本次</el-tag>
                <el-tag :type="VISIT_STATUS[v.status].type" size="small" effect="light">{{ VISIT_STATUS[v.status].text }}</el-tag>
              </span>
            </div>
            <dl class="facts">
              <template v-if="v.problem"><dt>问题</dt><dd>{{ v.problem }}</dd></template>
              <template v-if="v.specialNeeds"><dt>特殊需求</dt><dd>{{ v.specialNeeds }}</dd></template>
              <template v-if="v.relatedHistory"><dt>相关病史</dt><dd>{{ v.relatedHistory }}</dd></template>
              <template v-if="v.noTreatmentReason"><dt>未治疗原因</dt><dd class="warn">{{ v.noTreatmentReason }}</dd></template>
            </dl>
            <table v-if="v.treatments.length" class="mini-table">
              <tr v-for="(t, i) in v.treatments" :key="i">
                <td>{{ t.itemName }}</td>
                <td class="num">{{ quantity(t.quantity) }} {{ t.unitName }}</td>
                <td class="num money">{{ money(t.subtotal) }}</td>
              </tr>
              <tr class="sum">
                <td colspan="2">合计</td>
                <td class="num money">{{ money(total(v)) }}</td>
              </tr>
            </table>
          </div>
        </el-timeline-item>
      </el-timeline>
    </template>
    <el-empty v-else-if="failed" description="诊疗资料加载失败" :image-size="72">
      <el-button type="primary" plain @click="load">重新加载</el-button>
    </el-empty>
  </div>
</template>

<style scoped>
.record {
  min-height: 160px;
}
.record-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}
.avatar {
  width: 44px;
  height: 44px;
  flex: none;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 18px;
  font-weight: 600;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.name {
  font-size: 16px;
  font-weight: 600;
}
.record-allergy {
  margin-bottom: 18px;
}
.timeline {
  padding-left: 2px;
}
.visit-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 10px 12px;
  background: #fff;
}
.visit-card.current {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
}
.visit-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  margin-bottom: 6px;
}
.visit-top .el-tag + .el-tag {
  margin-left: 4px;
}
.facts {
  display: grid;
  grid-template-columns: 72px 1fr;
  gap: 4px 8px;
  margin: 0 0 6px;
  font-size: 13px;
  line-height: 1.6;
}
.facts dt {
  color: var(--el-text-color-secondary);
}
.facts dd {
  margin: 0;
}
.facts .warn {
  color: var(--el-color-warning-dark-2);
}
.mini-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.mini-table td {
  padding: 4px 0;
  border-top: 1px dashed var(--el-border-color-lighter);
}
.mini-table .num {
  text-align: right;
  white-space: nowrap;
  padding-left: 12px;
}
.mini-table .sum td {
  font-weight: 600;
}
</style>
