<script setup>
// 修改自己的密码（三种岗位都可用，协作约定 9.13）：成功后其他设备上的登录失效，当前这台继续使用
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { changePassword } from '@/api/auth.js'
import { useSubmit } from '@/composables/useSubmit.js'

const open = defineModel({ type: Boolean, default: false })
const formRef = ref()
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const { saving, fieldErrors, run, reset } = useSubmit()

const same = (rule, value, cb) => (value === form.newPassword ? cb() : cb(new Error('两次输入的新密码不一致')))
const differ = (rule, value, cb) => (value && value === form.oldPassword ? cb(new Error('新密码不能和原密码相同')) : cb())
const rules = {
  oldPassword: [{ required: true, message: '请填写原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请填写新密码', trigger: 'blur' },
    { min: 6, max: 64, message: '密码应为 6—64 位', trigger: 'blur' },
    { validator: differ, trigger: 'blur' }
  ],
  confirm: [{ required: true, message: '请再输入一次新密码', trigger: 'blur' }, { validator: same, trigger: 'blur' }]
}

watch(open, (v) => {
  if (!v) return
  Object.assign(form, { oldPassword: '', newPassword: '', confirm: '' })
  reset()
  formRef.value?.clearValidate()
})

async function save() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  const result = await run((opts) => changePassword(form.oldPassword, form.newPassword, opts))
  if (!result) return
  open.value = false
  ElMessage.success('密码已修改。其他设备上的登录已失效，下次登录请使用新密码')
}
</script>

<template>
  <el-dialog v-model="open" title="修改密码" width="420px" :close-on-click-modal="false" append-to-body>
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent>
      <el-form-item label="原密码" prop="oldPassword" :error="fieldErrors.oldPassword">
        <el-input v-model="form.oldPassword" type="password" show-password autocomplete="current-password" />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword" :error="fieldErrors.newPassword">
        <el-input v-model="form.newPassword" type="password" show-password maxlength="64" autocomplete="new-password" placeholder="6—64 位" />
      </el-form-item>
      <el-form-item label="再输入一次新密码" prop="confirm">
        <el-input v-model="form.confirm" type="password" show-password maxlength="64" autocomplete="new-password" />
      </el-form-item>
      <div class="note">修改后，你在其他电脑或手机上的登录会失效，这台设备不受影响。</div>
    </el-form>
    <template #footer>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">修改密码</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.note {
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
</style>
