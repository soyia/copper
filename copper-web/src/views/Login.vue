<template>
  <div class="login-page" :class="appStore.fontSizeClass">
    <div class="login-panel">
      <div class="login-brand">
        <div class="brand-mark">铜</div>
        <div>
          <div class="login-title">{{ appStore.settings.companyName }}</div>
          <div class="login-subtitle">Copper V2</div>
        </div>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @keyup.enter="submit">
        <el-form-item label="账号" prop="username">
          <el-input v-model.trim="form.username" autocomplete="username" placeholder="请输入账号或手机号" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" autocomplete="current-password" show-password />
        </el-form-item>
        <el-button type="primary" class="login-button" :loading="submitting" @click="submit">登录</el-button>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useAppStore } from '@/stores/app'
import { resolvePostLoginPath } from '@/utils/mobileNavigation'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({ username: '', password: '' })
const rules: FormRules = {
  username: [{ required: true, message: '请填写账号或手机号', trigger: 'blur' }],
  password: [{ required: true, message: '请填写密码', trigger: 'blur' }],
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    const session = await appStore.login(form.username, form.password)
    ElMessage.success(`欢迎回来，${session.employeeName}`)
    const redirect = resolvePostLoginPath(route.query.redirect)
    router.replace(session.passwordChangeRequired ? '/settings?section=password' : redirect)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '登录失败')
  } finally {
    submitting.value = false
  }
}
</script>
