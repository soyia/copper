<template>
  <div class="page">
    <div class="kpi-grid security-kpis">
      <div class="kpi"><div class="kpi-label">登录用户</div><div class="kpi-value">{{ users.length }}</div><div class="kpi-note">与人员档案一一关联</div></div>
      <div class="kpi"><div class="kpi-label">正常可登录</div><div class="kpi-value">{{ enabledCount }}</div><div class="kpi-note">在职且账号已启用</div></div>
      <div class="kpi"><div class="kpi-label">待授权人员</div><div class="kpi-value">{{ availableEmployees.length }}</div><div class="kpi-note">可开单引用，但尚无登录账号</div></div>
    </div>
    <div class="panel">
      <div class="panel-header">
        <div><div class="panel-title">用户管理</div><div class="muted">账号只负责登录身份；姓名、部门和在离职状态以人员档案为准</div></div>
        <el-button v-if="appStore.hasPermission('user.manage')" type="primary" :disabled="!availableEmployees.length" @click="openUser()">授权人员登录</el-button>
      </div>
      <div class="list-tools system-user-tools">
        <el-input v-model="keyword" clearable placeholder="姓名 / 账号 / 手机 / 部门" />
        <el-select v-model="statusFilter" clearable placeholder="账号状态"><el-option label="正常" value="ENABLED" /><el-option label="停用" value="DISABLED" /></el-select>
        <div class="list-tools-actions"><span class="muted">角色权限采用累计授权</span></div>
      </div>
      <el-table :data="filteredUsers" border size="small" class="data-table" max-height="calc(100vh - 384px)">
        <el-table-column label="人员" min-width="180" fixed="left">
          <template #default="{ row }"><div class="multiline-primary"><span>{{ row.employeeName }}</span><el-tag v-if="row.passwordChangeRequired" size="small" type="warning">需改密</el-tag></div><div class="multiline-secondary">{{ row.employeeNo }} · {{ row.departmentName }}</div></template>
        </el-table-column>
        <el-table-column label="登录账号" min-width="160"><template #default="{ row }"><div>{{ row.username }}</div><div class="multiline-secondary">{{ row.mobile || '未绑定手机号' }}</div></template></el-table-column>
        <el-table-column label="角色" min-width="230">
          <template #default="{ row }"><el-tag v-for="name in row.roleNames" :key="name" size="small" effect="plain" class="role-tag">{{ name }}</el-tag><span v-if="!row.roleNames.length" class="danger-text">未分配角色</span></template>
        </el-table-column>
        <el-table-column label="安全状态" min-width="170"><template #default="{ row }"><div><el-tag :type="row.loginEnabled && row.status === 'ENABLED' ? 'success' : 'info'" size="small">{{ row.loginEnabled && row.status === 'ENABLED' ? '正常' : '已停用' }}</el-tag></div><div class="multiline-secondary">{{ row.lastLoginAt ? `最近登录 ${row.lastLoginAt.replace('T', ' ')}` : '从未登录' }}</div></template></el-table-column>
        <el-table-column v-if="appStore.hasPermission('user.manage')" label="操作" width="292" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openUser(row)">授权</el-button>
            <el-button link type="primary" @click="resetPassword(row)">重置密码</el-button>
            <el-button link type="primary" :disabled="row.id === appStore.session?.userId" @click="revokeSessions(row)">撤销会话</el-button>
            <el-button v-if="row.loginEnabled" link type="danger" :disabled="row.id === appStore.session?.userId" @click="toggleUser(row, false)">停用</el-button>
            <el-button v-else link type="primary" @click="toggleUser(row, true)">启用</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-drawer v-model="drawer" :title="form.id ? '编辑用户授权' : '授权人员登录'" size="520px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="96px" class="drawer-form">
        <el-form-item label="关联人员" prop="employeeId">
          <el-select v-model="form.employeeId" filterable :disabled="Boolean(form.id)" placeholder="选择在职人员">
            <el-option v-for="item in availableForForm" :key="item.id" :label="`${item.name} · ${item.employeeNo} · ${item.departmentName}`" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="登录账号" prop="username"><el-input v-model.trim="form.username" autocomplete="off" maxlength="64" /></el-form-item>
        <el-form-item label="手机号"><el-input v-model.trim="form.mobile" maxlength="32" /></el-form-item>
        <el-form-item v-if="!form.id" label="初始密码" prop="initialPassword" required><el-input v-model="form.initialPassword" type="password" show-password autocomplete="new-password" /><div class="form-help">至少 8 位，必须同时包含字母和数字；首次登录强制修改</div></el-form-item>
        <el-form-item label="角色" prop="roleIds">
          <el-select v-model="form.roleIds" multiple filterable collapse-tags :max-collapse-tags="3" placeholder="至少选择一个角色">
            <el-option v-for="role in enabledRoles" :key="role.id" :label="role.name" :value="role.id" />
          </el-select>
          <div class="form-help">多个角色权限取并集；建议按岗位授权，临时例外优先复制角色后调整</div>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="drawer = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存授权</el-button></template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { api, type Employee, type SecurityRole, type SystemUser } from '@/api/client'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()
const users = ref<SystemUser[]>([])
const employees = ref<Employee[]>([])
const roles = ref<SecurityRole[]>([])
const keyword = ref('')
const statusFilter = ref('')
const drawer = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({ id: 0, employeeId: 0, username: '', mobile: '', initialPassword: '', roleIds: [] as number[], loginEnabled: true })
const rules: FormRules = {
  employeeId: [{ required: true, message: '请选择关联人员', trigger: 'change' }],
  username: [{ required: true, message: '请填写登录账号', trigger: 'blur' }],
  initialPassword: [{
    validator: (_rule, value, callback) => {
      if (form.id) return callback()
      if (!String(value || '').trim()) return callback(new Error('请填写初始密码'))
      if (!/^(?=.*[A-Za-z])(?=.*\d).{8,}$/.test(value)) return callback(new Error('密码至少 8 位，且包含字母和数字'))
      callback()
    },
    trigger: ['blur', 'change'],
  }],
  roleIds: [{ type: 'array', required: true, min: 1, message: '至少选择一个角色', trigger: 'change' }],
}
const enabledRoles = computed(() => roles.value.filter((item) => item.status === 'ENABLED'))
const enabledCount = computed(() => users.value.filter((item) => item.loginEnabled && item.status === 'ENABLED').length)
const availableEmployees = computed(() => employees.value.filter((item) => item.employmentStatus === 'ACTIVE' && !item.userId))
const availableForForm = computed(() => form.id ? employees.value.filter((item) => item.id === form.employeeId) : availableEmployees.value)
const filteredUsers = computed(() => users.value.filter((item) => {
  const text = `${item.employeeName} ${item.employeeNo} ${item.username} ${item.mobile} ${item.departmentName}`.toLowerCase()
  const status = item.loginEnabled && item.status === 'ENABLED' ? 'ENABLED' : 'DISABLED'
  return (!keyword.value || text.includes(keyword.value.toLowerCase())) && (!statusFilter.value || status === statusFilter.value)
}))

onMounted(load)
async function load() {
  try { [users.value, employees.value, roles.value] = await Promise.all([api.systemUsers(), api.employees(), api.roles()]) }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '用户数据加载失败') }
}
function openUser(row?: SystemUser) {
  Object.assign(form, row ? { id: row.id, employeeId: row.employeeId, username: row.username, mobile: row.mobile, initialPassword: '', roleIds: [...row.roleIds], loginEnabled: row.loginEnabled } : { id: 0, employeeId: availableEmployees.value[0]?.id || 0, username: '', mobile: '', initialPassword: '', roleIds: [], loginEnabled: true })
  drawer.value = true
}
async function save() {
  if (!await formRef.value?.validate().catch(() => false)) return
  saving.value = true
  try {
    if (form.id) await api.updateSystemUser(form.id, { username: form.username, mobile: form.mobile, roleIds: form.roleIds })
    else await api.createSystemUser({ employeeId: form.employeeId, username: form.username, mobile: form.mobile, initialPassword: form.initialPassword, roleIds: form.roleIds })
    ElMessage.success('用户授权已保存'); drawer.value = false; await load()
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存失败') } finally { saving.value = false }
}
async function resetPassword(row: SystemUser) {
  try {
    const { value } = await ElMessageBox.prompt('设置新的临时密码。用户下次登录时必须修改。', `重置“${row.employeeName}”的密码`, { inputType: 'password', inputPattern: /^(?=.*[A-Za-z])(?=.*\d).{8,}$/, inputErrorMessage: '至少 8 位，且包含字母和数字' })
    await api.resetUserPassword(row.id, value); ElMessage.success('密码已重置')
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '重置失败') }
}
async function toggleUser(row: SystemUser, enabled: boolean) {
  try {
    let reason = ''
    if (!enabled) {
      const result = await ElMessageBox.prompt('停用后该用户现有会话立即失效，请填写原因。', `停用“${row.employeeName}”`, { inputPattern: /\S+/, inputErrorMessage: '必须填写停用原因', type: 'warning' })
      reason = result.value
    } else await ElMessageBox.confirm(`确认启用“${row.employeeName}”的登录权限？`, '启用用户')
    await api.setUserLoginEnabled(row.id, enabled, reason); ElMessage.success(enabled ? '用户已启用' : '用户已停用'); await load()
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '操作失败') }
}

async function revokeSessions(row: SystemUser) {
  try {
    const result = await ElMessageBox.prompt(
      '该用户在所有设备上的登录会立即失效，历史会话仍保留用于审计。请填写原因。',
      `撤销“${row.employeeName}”的全部会话`,
      { inputPattern: /\S+/, inputErrorMessage: '必须填写撤销原因', type: 'warning', confirmButtonText: '确认撤销' },
    )
    await api.revokeUserSessions(row.id, result.value)
    ElMessage.success('该用户的有效会话已全部撤销')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '撤销失败')
  }
}
</script>
