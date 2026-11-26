<script setup>
// UC05 查阅患者诊疗资料（牙医）：按患者查看跨诊所的既往就诊、治疗和已知过敏
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PatientPicker from '@/components/PatientPicker.vue'
import MedicalRecord from '@/components/MedicalRecord.vue'

const route = useRoute()
const router = useRouter()
const patientId = ref(route.query.patientId ? Number(route.query.patientId) : null)
const loadedPatient = ref(null) // 从地址带入时，用诊疗资料里的患者信息回显选择框

// 选中的患者写进地址，刷新或返回时还能看到同一位
watch(patientId, (id) => router.replace({ query: id ? { patientId: id } : {} }))
</script>

<template>
  <div class="page">
    <div class="page-toolbar">
      <div class="toolbar-left">
        <PatientPicker v-model="patientId" :initial="loadedPatient" style="width: 320px" />
      </div>
    </div>
    <div class="page-intro">三家诊所共用一份病历：这里能看到患者在任何诊所、由任何牙医记录的已确认就诊。</div>

    <el-card shadow="never" class="record-card">
      <MedicalRecord v-if="patientId" :patient-id="patientId" @loaded="loadedPatient = $event" />
      <el-empty v-else description="输入姓名、电话或账号，选择一位患者查看诊疗资料" :image-size="88" />
    </el-card>
  </div>
</template>

<style scoped>
.record-card {
  max-width: 760px;
}
</style>
