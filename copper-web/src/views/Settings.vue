<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">公司信息</div>
      </div>
      <div class="panel-body">
        <el-form ref="formRef" class="settings-form" :model="form" :rules="rules" label-width="90px">
          <el-form-item label="公司名称" prop="companyName">
            <el-input v-model="form.companyName" />
          </el-form-item>
          <el-form-item label="联系电话">
            <el-input v-model="form.companyPhone" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" @click="saveSettings">保存</el-button>
          </el-form-item>
        </el-form>
      </div>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">登录设备</div>
          <div class="muted">最多保留 5 个有效会话；撤销后设备需重新登录，历史记录不会被删除。</div>
        </div>
        <el-button :loading="sessionsLoading" @click="loadSessions">刷新</el-button>
      </div>
      <el-table v-loading="sessionsLoading" :data="sessions" border size="small" class="data-table device-table">
        <el-table-column label="设备" min-width="190">
          <template #default="{ row }">
            <div class="multiline-primary"><span>{{ row.clientName }}</span><el-tag v-if="row.current" size="small" type="success">当前设备</el-tag></div>
            <div class="multiline-secondary">IP {{ row.ipAddress || '-' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="最近使用" min-width="170"><template #default="{ row }">{{ formatDateTime(row.lastSeenAt) }}</template></el-table-column>
        <el-table-column label="登录时间" min-width="170"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
        <el-table-column label="到期时间" min-width="170"><template #default="{ row }">{{ formatDateTime(row.expiresAt) }}</template></el-table-column>
        <el-table-column label="操作" width="96" fixed="right">
          <template #default="{ row }"><el-button link type="danger" @click="revokeSession(row)">{{ row.current ? '退出' : '撤销' }}</el-button></template>
        </el-table-column>
        <template #empty><el-empty description="暂无有效登录设备" :image-size="72" /></template>
      </el-table>
    </div>

    <div class="panel" :class="{ 'settings-attention': appStore.session?.passwordChangeRequired }">
      <div class="panel-header">
        <div>
          <div class="panel-title">登录密码</div>
          <div class="muted">{{ appStore.session?.passwordChangeRequired ? '当前为临时密码，必须修改后再继续日常业务。' : '定期更换密码，不与其他系统共用。' }}</div>
        </div>
      </div>
      <div class="panel-body">
        <PasswordChangeForm @success="handlePasswordChanged" />
      </div>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">界面设置</div>
      </div>
      <div class="panel-body">
        <el-form class="settings-form" :model="form" label-width="90px">
          <el-form-item label="页面字体">
            <el-segmented v-model="form.fontSize" :options="fontSizeOptions" @change="changeFontSize" />
          </el-form-item>
        </el-form>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { api, type DeviceSession } from '@/api/client'
import { formatDateTime } from '@/api/format'
import PasswordChangeForm from '@/components/PasswordChangeForm.vue'
import { useAppStore, type FontSizeLevel, type UserSettings } from '@/stores/app'

const appStore = useAppStore()
const formRef = ref<FormInstance>()
const sessionsLoading = ref(false)
const sessions = ref<DeviceSession[]>([])
const form = reactive<UserSettings>({ ...appStore.settings })
const rules: FormRules = {
  companyName: [{ required: true, message: '请填写公司名称', trigger: 'blur' }],
}
const fontSizeOptions = [
  { label: '大', value: 'large' },
  { label: '中', value: 'medium' },
  { label: '小', value: 'small' },
  { label: '微小', value: 'tiny' },
]

onMounted(loadSessions)

async function loadSessions() {
  sessionsLoading.value = true
  try { sessions.value = await api.currentUserSessions() }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '登录设备加载失败') }
  finally { sessionsLoading.value = false }
}

async function revokeSession(row: DeviceSession) {
  const message = row.current
    ? '退出当前设备后，需要重新输入账号密码才能继续。'
    : `确认撤销“${row.clientName}”的登录状态？`
  const confirmed = await ElMessageBox.confirm(message, row.current ? '退出当前设备' : '撤销登录设备', {
    type: 'warning', confirmButtonText: row.current ? '确认退出' : '确认撤销',
  }).then(() => true).catch(() => false)
  if (!confirmed) return
  try {
    const result = await api.revokeCurrentUserSession(row.id)
    if (result.current) {
      appStore.session = null
      appStore.sessionChecked = true
      window.dispatchEvent(new CustomEvent('copper:unauthorized'))
      return
    }
    ElMessage.success('登录设备已撤销')
    await loadSessions()
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '撤销失败') }
}

function changeFontSize(value: string | number | boolean) {
  appStore.updateSettings({ fontSize: value as FontSizeLevel })
}

function handlePasswordChanged() {
  window.dispatchEvent(new CustomEvent('copper:password-changed'))
}

async function saveSettings() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  appStore.updateSettings({ ...form })
  ElMessage.success('设置已保存')
}
</script>

<style scoped>
.device-table { margin: 0 20px 20px; width: calc(100% - 40px); }
</style>
