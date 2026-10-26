<template>
  <div class="page role-permission-page">
    <aside class="panel role-list-panel">
      <div class="panel-header">
        <div><div class="panel-title">角色</div><div class="muted">按岗位配置，一次授权多人</div></div>
        <el-button v-if="appStore.hasPermission('role.manage')" type="primary" plain @click="createRole">新增</el-button>
      </div>
      <div class="role-list">
        <button v-for="role in roles" :key="role.id" type="button" class="role-list-item" :class="{ 'is-active': role.id === selectedRoleId, 'is-disabled': role.status === 'DISABLED' }" @click="selectRole(role)">
          <span class="role-list-main"><strong>{{ role.name }}</strong><small>{{ role.description || '未填写说明' }}</small></span>
          <span class="role-user-count">{{ role.userCount }} 人</span>
        </button>
      </div>
    </aside>

    <section class="panel role-editor-panel">
      <div v-if="editor" class="role-editor">
        <div class="panel-header role-editor-header">
          <div>
            <div class="panel-title">{{ editor.id ? editor.name : '新增角色' }} <el-tag v-if="editor.systemPreset" size="small" effect="plain">系统预置</el-tag></div>
            <div class="muted">功能权限、敏感字段和数据范围同时生效；多个角色取并集</div>
          </div>
          <div class="panel-actions">
            <el-button v-if="editor.id" @click="copyCurrentRole">复制角色</el-button>
            <el-button v-if="editor.id && !editor.systemPreset && editor.status === 'ENABLED' && canEdit" type="danger" plain @click="disableCurrentRole">停用</el-button>
            <el-button v-if="canEdit" type="primary" :loading="saving" @click="saveRole">保存</el-button>
          </div>
        </div>

        <div class="role-basic-form">
          <el-form :model="editor" label-width="80px" inline>
            <el-form-item label="角色名称"><el-input v-model.trim="editor.name" :disabled="editor.systemPreset" maxlength="64" /></el-form-item>
            <el-form-item label="说明"><el-input v-model.trim="editor.description" :disabled="editor.systemPreset" maxlength="255" /></el-form-item>
          </el-form>
          <el-alert v-if="editor.systemPreset" title="系统预置角色保持稳定，避免升级时权限漂移。需要调整时请先复制为自定义角色。" type="info" :closable="false" show-icon />
        </div>

        <el-tabs v-model="activeTab" class="role-tabs">
          <el-tab-pane label="功能与字段权限" name="permissions">
            <div class="permission-toolbar">
              <span class="muted">按业务职责授权，不建议直接给所有人开放审核、冲正和敏感金额</span>
              <div v-if="canEdit"><el-button link type="primary" @click="selectAllPermissions">全选</el-button><el-button link @click="clearPermissions">清空</el-button></div>
            </div>
            <el-checkbox-group v-model="editor.permissionCodes" :disabled="!canEdit" class="permission-groups">
              <section v-for="group in permissionGroups" :key="group.moduleCode" class="permission-group">
                <div class="permission-group-title">
                  <strong>{{ group.moduleName }}</strong>
                  <el-button v-if="canEdit" link type="primary" @click="togglePermissionGroup(group.moduleCode, !group.items.every((item) => editor?.permissionCodes.includes(item.code)))">{{ group.items.every((item) => editor?.permissionCodes.includes(item.code)) ? '取消本组' : '本组全选' }}</el-button>
                </div>
                <div class="permission-items">
                  <el-checkbox v-for="item in group.items" :key="item.code" :value="item.code">
                    <span>{{ item.name }}</span><small v-if="item.description">{{ item.description }}</small>
                  </el-checkbox>
                </div>
              </section>
            </el-checkbox-group>
          </el-tab-pane>
          <el-tab-pane label="数据范围" name="dataScopes">
            <el-alert title="数据范围在后端查询和写入校验中执行，隐藏菜单不能替代数据权限。" type="warning" :closable="false" show-icon />
            <div class="data-scope-list">
              <section v-for="definition in scopeDefinitions" :key="definition.dimension" class="data-scope-card">
                <div class="data-scope-heading"><div><strong>{{ definition.name }}</strong><p>{{ definition.description }}</p></div></div>
                <el-radio-group v-model="scopeFor(definition.dimension).mode" :disabled="!canEdit">
                  <el-radio-button v-for="mode in definition.modes" :key="mode.value" :value="mode.value">{{ mode.label }}</el-radio-button>
                </el-radio-group>
                <el-select v-if="scopeFor(definition.dimension).mode === 'CUSTOM'" v-model="scopeFor(definition.dimension).selectedIds" multiple filterable collapse-tags :max-collapse-tags="4" :disabled="!canEdit" class="scope-option-select" placeholder="选择可访问范围">
                  <el-option v-for="option in scopeOptions[definition.dimension]" :key="option.id" :label="option.secondaryText ? `${option.name} · ${option.secondaryText}` : option.name" :value="option.id" />
                </el-select>
              </section>
            </div>
          </el-tab-pane>
          <el-tab-pane label="默认岗位验收" name="acceptance">
            <el-alert
              :type="acceptance?.passed ? 'success' : 'error'"
              :title="acceptance?.passed ? '老板、财务、仓库、销售、采购默认授权全部通过' : `默认岗位仍有 ${acceptance?.failedCheckCount || 0} 项偏差`"
              :description="acceptance ? `按实时数据库授权检查，不以权限码存在代替验收。检查时间：${formatDateTime(acceptance.checkedAt)}` : '正在读取默认岗位实时授权'"
              :closable="false"
              show-icon
            />
            <div class="acceptance-list">
              <section v-for="role in acceptance?.roles || []" :key="role.roleCode" class="data-scope-card">
                <div class="data-scope-heading">
                  <div><strong>{{ role.roleName }}</strong><p>{{ role.userCount }} 个账号使用此岗位</p></div>
                  <el-tag :type="role.passed ? 'success' : 'danger'">{{ role.passed ? '通过' : '需修正' }}</el-tag>
                </div>
                <el-table :data="role.checks" border size="small" class="data-table acceptance-table">
                  <el-table-column label="检查项" min-width="240"><template #default="{ row }"><strong>{{ row.checkName }}</strong><div class="multiline-secondary">{{ row.checkCode }}</div></template></el-table-column>
                  <el-table-column prop="expectedValue" label="应当" width="110" />
                  <el-table-column prop="actualValue" label="实际" width="110" />
                  <el-table-column label="结果" width="85"><template #default="{ row }"><el-tag size="small" :type="row.passed ? 'success' : 'danger'">{{ row.passed ? '通过' : '失败' }}</el-tag></template></el-table-column>
                </el-table>
              </section>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
      <div v-else class="empty-state">请选择或新增角色</div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type DataScopeDimension, type DataScopeMode, type DataScopeOption, type DataScopeRule, type DefaultRoleAcceptance, type PermissionItem, type SecurityRole } from '@/api/client'
import { formatDateTime } from '@/api/format'
import { useAppStore } from '@/stores/app'

type RoleEditor = SecurityRole & { permissionCodes: string[]; dataScopes: DataScopeRule[] }

const appStore = useAppStore()
const roles = ref<SecurityRole[]>([])
const permissions = ref<PermissionItem[]>([])
const acceptance = ref<DefaultRoleAcceptance>()
const selectedRoleId = ref(0)
const editor = ref<RoleEditor | null>(null)
const activeTab = ref('permissions')
const saving = ref(false)
const scopeOptions = reactive<Record<DataScopeDimension, DataScopeOption[]>>({ DOCUMENT: [], WAREHOUSE: [], PARTNER: [], PRODUCT: [], ACCOUNT: [] })
const scopeDefinitions: Array<{ dimension: DataScopeDimension; name: string; description: string; modes: Array<{ label: string; value: DataScopeMode }> }> = [
  { dimension: 'DOCUMENT', name: '业务单据', description: '控制采购、销售、出入库、收付款及往来记录的人员/部门范围。', modes: [{ label: '全部数据', value: 'ALL' }, { label: '本部门及下级', value: 'DEPARTMENT_AND_CHILDREN' }, { label: '仅本部门', value: 'DEPARTMENT' }, { label: '仅本人', value: 'SELF' }, { label: '指定部门', value: 'CUSTOM' }] },
  { dimension: 'WAREHOUSE', name: '仓库', description: '限制可查看、开单和调整库存的仓库。', modes: [{ label: '全部仓库', value: 'ALL' }, { label: '指定仓库', value: 'CUSTOM' }] },
  { dimension: 'PARTNER', name: '往来单位', description: '限制可查看和用于业务单据的客户、供应商。', modes: [{ label: '全部往来单位', value: 'ALL' }, { label: '指定往来单位', value: 'CUSTOM' }] },
  { dimension: 'PRODUCT', name: '商品', description: '限制可查看、采购、销售和盘点的商品。', modes: [{ label: '全部商品', value: 'ALL' }, { label: '指定商品', value: 'CUSTOM' }] },
  { dimension: 'ACCOUNT', name: '往来账户', description: '限制可查看和调整的客户预收、供应商预付及其他往来账户。', modes: [{ label: '全部账户', value: 'ALL' }, { label: '指定账户', value: 'CUSTOM' }] },
]
const permissionGroups = computed(() => {
  const groups = new Map<string, { moduleCode: string; moduleName: string; items: PermissionItem[] }>()
  for (const item of permissions.value) {
    if (!groups.has(item.moduleCode)) groups.set(item.moduleCode, { moduleCode: item.moduleCode, moduleName: item.moduleName, items: [] })
    groups.get(item.moduleCode)!.items.push(item)
  }
  return [...groups.values()]
})
const canEdit = computed(() => Boolean(editor.value && !editor.value.systemPreset && appStore.hasPermission('role.manage')))

onMounted(load)
async function load() {
  try {
    const [roleRows, permissionRows, acceptanceResult, documentOptions, warehouseOptions, partnerOptions, productOptions, accountOptions] = await Promise.all([
      api.roles(), api.permissions(), api.defaultRoleAcceptance(), api.dataScopeOptions('DOCUMENT'), api.dataScopeOptions('WAREHOUSE'), api.dataScopeOptions('PARTNER'), api.dataScopeOptions('PRODUCT'), api.dataScopeOptions('ACCOUNT'),
    ])
    roles.value = roleRows; permissions.value = permissionRows; acceptance.value = acceptanceResult
    Object.assign(scopeOptions, { DOCUMENT: documentOptions, WAREHOUSE: warehouseOptions, PARTNER: partnerOptions, PRODUCT: productOptions, ACCOUNT: accountOptions })
    const selected = roles.value.find((item) => item.id === selectedRoleId.value) || roles.value[0]
    if (selected) selectRole(selected)
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '角色权限加载失败') }
}

function normalizeScopes(scopes: DataScopeRule[]) {
  return scopeDefinitions.map((definition) => {
    const source = scopes.find((item) => item.dimension === definition.dimension)
    return { dimension: definition.dimension, mode: source?.mode || 'ALL', selectedIds: [...(source?.selectedIds || [])] } as DataScopeRule
  })
}
function selectRole(role: SecurityRole) {
  selectedRoleId.value = role.id
  editor.value = { ...role, permissionCodes: [...role.permissionCodes], dataScopes: normalizeScopes(role.dataScopes) }
}
function createRole() {
  selectedRoleId.value = 0
  editor.value = { id: 0, code: '', name: '', description: '', systemPreset: false, status: 'ENABLED', userCount: 0, permissionCodes: [], dataScopes: normalizeScopes([]) }
  activeTab.value = 'permissions'
}
function scopeFor(dimension: DataScopeDimension) { return editor.value!.dataScopes.find((item) => item.dimension === dimension)! }
function togglePermissionGroup(moduleCode: string, checked: string | number | boolean) {
  if (!editor.value || !canEdit.value) return
  const codes = permissions.value.filter((item) => item.moduleCode === moduleCode).map((item) => item.code)
  editor.value.permissionCodes = checked ? [...new Set([...editor.value.permissionCodes, ...codes])] : editor.value.permissionCodes.filter((code) => !codes.includes(code))
}
function selectAllPermissions() { if (editor.value) editor.value.permissionCodes = permissions.value.map((item) => item.code) }
function clearPermissions() { if (editor.value) editor.value.permissionCodes = [] }
async function saveRole() {
  if (!editor.value) return
  if (!editor.value.name.trim()) return ElMessage.warning('请填写角色名称')
  for (const scope of editor.value.dataScopes) if (scope.mode === 'CUSTOM' && !scope.selectedIds.length) return ElMessage.warning(`“${scopeDefinitions.find((item) => item.dimension === scope.dimension)?.name}”选择了指定范围，请至少选择一项`)
  saving.value = true
  try {
    const payload = { name: editor.value.name, description: editor.value.description, permissionCodes: editor.value.permissionCodes, dataScopes: editor.value.dataScopes }
    const result = editor.value.id ? await api.updateRole(editor.value.id, payload) : await api.createRole(payload)
    selectedRoleId.value = result.id; ElMessage.success('角色权限已保存'); await load()
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存失败') } finally { saving.value = false }
}
async function copyCurrentRole() {
  if (!editor.value?.id) return
  try {
    const { value } = await ElMessageBox.prompt('复制会保留当前功能权限和数据范围，请填写新角色名称。', '复制角色', { inputValue: `${editor.value.name}-副本`, inputPattern: /\S+/, inputErrorMessage: '请填写角色名称' })
    const result = await api.copyRole(editor.value.id, value); selectedRoleId.value = result.id; ElMessage.success('角色已复制'); await load()
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '复制失败') }
}
async function disableCurrentRole() {
  if (!editor.value?.id) return
  try {
    const { value } = await ElMessageBox.prompt('已分配给用户的角色不能直接停用。请填写停用原因。', `停用“${editor.value.name}”`, { inputPattern: /\S+/, inputErrorMessage: '必须填写停用原因', type: 'warning' })
    await api.disableRole(editor.value.id, value); ElMessage.success('角色已停用'); await load()
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '停用失败') }
}
</script>
