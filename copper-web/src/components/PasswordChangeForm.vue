<template>
  <el-form
    ref="formRef"
    class="settings-form password-change-form"
    :model="form"
    :rules="rules"
    label-width="90px"
    @submit.prevent="submit"
  >
    <el-form-item label="当前密码" prop="currentPassword">
      <el-input
        v-model="form.currentPassword"
        type="password"
        show-password
        autocomplete="current-password"
        :disabled="submitting"
      />
    </el-form-item>
    <el-form-item label="新密码" prop="newPassword">
      <el-input
        v-model="form.newPassword"
        type="password"
        show-password
        autocomplete="new-password"
        :disabled="submitting"
      />
    </el-form-item>
    <el-form-item label="确认密码" prop="confirmPassword">
      <el-input
        v-model="form.confirmPassword"
        type="password"
        show-password
        autocomplete="new-password"
        :disabled="submitting"
      />
    </el-form-item>
    <el-form-item class="password-change-actions">
      <slot name="before-submit" />
      <el-button type="primary" native-type="submit" :loading="submitting">
        {{ submitText }}
      </el-button>
    </el-form-item>
  </el-form>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { api } from '@/api/client'

withDefaults(defineProps<{
  submitText?: string
}>(), {
  submitText: '修改密码',
})

const emit = defineEmits<{
  success: []
}>()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
})
const rules: FormRules = {
  currentPassword: [{ required: true, message: '请填写当前密码', trigger: 'blur' }],
  newPassword: [{
    validator: (_rule, value, callback) => /^(?=.*[A-Za-z])(?=.*\d).{8,}$/.test(value)
      ? callback()
      : callback(new Error('至少 8 位，且包含字母和数字')),
    trigger: 'blur',
  }],
  confirmPassword: [{
    validator: (_rule, value, callback) => value === form.newPassword
      ? callback()
      : callback(new Error('两次输入的密码不一致')),
    trigger: 'blur',
  }],
}

async function submit() {
  if (!await formRef.value?.validate().catch(() => false)) return
  submitting.value = true
  try {
    await api.changePassword({
      currentPassword: form.currentPassword,
      newPassword: form.newPassword,
    })
    Object.assign(form, { currentPassword: '', newPassword: '', confirmPassword: '' })
    emit('success')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '密码修改失败')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.password-change-actions :deep(.el-form-item__content) {
  justify-content: flex-end;
}
</style>
