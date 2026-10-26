<template>
  <div class="page organization-page">
    <aside class="panel organization-tree-panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">部门组织</div>
          <div class="muted">部门一经业务引用后只能停用，不能删除</div>
        </div>
        <el-button v-if="appStore.hasPermission('organization.manage')" type="primary" plain @click="openDepartment()">新增</el-button>
      </div>
      <div class="panel-body organization-tree-body">
        <el-tree
          :data="departmentTree"
          node-key="id"
          default-expand-all
          highlight-current
          :current-node-key="selectedDepartmentId"
          :expand-on-click-node="false"
          @node-click="selectDepartment"
        >
          <template #default="{ data }">
            <div class="department-tree-node">
              <span>{{ data.name }}</span>
              <span class="department-tree-count">{{ data.employeeCount }}</span>
            </div>
          </template>
        </el-tree>
      </div>
    </aside>

    <section class="panel organization-people-panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">{{ selectedDepartment?.name || '全部人员' }}</div>
          <div class="muted">人员档案与登录账号分离；未授权登录的人员仍可作为业务员引用</div>
        </div>
        <div class="panel-actions">
          <el-button v-if="selectedDepartment && appStore.hasPermission('organization.manage')" @click="openDepartment(selectedDepartment)">编辑部门</el-button>
          <el-button v-if="selectedDepartment && selectedDepartment.parentId && appStore.hasPermission('organization.manage')" type="danger" plain @click="disableDepartment">停用部门</el-button>
          <el-button v-if="appStore.hasPermission('organization.manage')" type="primary" @click="openEmployee()">新增人员</el-button>
        </div>
      </div>
      <div class="list-tools organization-tools">
        <el-input v-model="keyword" clearable placeholder="姓名 / 工号 / 手机 / 职务" />
        <el-segmented v-model="employmentFilter" :options="employmentOptions" />
        <div class="list-tools-actions"><span class="muted">共 {{ filteredEmployees.length }} 人</span></div>
      </div>
      <el-table :data="filteredEmployees" size="small" border class="data-table" max-height="calc(100vh - 278px)">
        <el-table-column label="人员" min-width="160" fixed="left">
          <template #default="{ row }">
            <div class="multiline-primary"><span>{{ row.name }}</span><el-tag v-if="row.departmentManager" size="small" effect="plain">负责人</el-tag></div>
            <div class="multiline-secondary">{{ row.employeeNo }}</div>
          </template>
        </el-table-column>
        <el-table-column label="部门 / 职务" min-width="160">
          <template #default="{ row }"><div>{{ row.departmentName }}</div><div class="multiline-secondary">{{ row.jobTitle || '未设置职务' }}</div></template>
        </el-table-column>
        <el-table-column label="登录授权" min-width="220">
          <template #default="{ row }">
            <template v-if="row.userId">
              <div class="multiline-primary"><span>{{ row.username }}</span><el-tag :type="row.loginEnabled ? 'success' : 'info'" size="small">{{ row.loginEnabled ? '可登录' : '已停用' }}</el-tag></div>
              <div class="multiline-secondary">{{ row.roleNames.join('、') || '未分配角色' }}</div>
            </template>
            <span v-else class="muted">未授权登录</span>
          </template>
        </el-table-column>
        <el-table-column label="在职状态" width="100">
          <template #default="{ row }"><el-tag :type="row.employmentStatus === 'ACTIVE' ? 'success' : 'info'" size="small">{{ row.employmentStatus === 'ACTIVE' ? '在职' : '已离职' }}</el-tag></template>
        </el-table-column>
        <el-table-column v-if="appStore.hasPermission('organization.manage')" label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEmployee(row)">编辑</el-button>
            <el-button v-if="row.employmentStatus === 'ACTIVE'" link type="danger" @click="leaveEmployee(row)">办理离职</el-button>
            <el-button v-else link type="primary" @click="reinstateEmployee(row)">恢复在职</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="departmentDialog" :title="departmentForm.id ? '编辑部门' : '新增部门'" width="520px" destroy-on-close>
      <el-form ref="departmentFormRef" :model="departmentForm" :rules="departmentRules" label-width="92px">
        <el-form-item label="上级部门" prop="parentId">
          <el-tree-select v-model="departmentForm.parentId" :data="selectableDepartmentTree" node-key="id" check-strictly default-expand-all :render-after-expand="false" />
        </el-form-item>
        <el-form-item label="部门编码" prop="code"><el-input v-model.trim="departmentForm.code" maxlength="32" /></el-form-item>
        <el-form-item label="部门名称" prop="name"><el-input v-model.trim="departmentForm.name" maxlength="64" /></el-form-item>
        <el-form-item label="部门负责人"><el-select v-model="departmentForm.managerEmployeeId" clearable filterable><el-option v-for="item in activeEmployees" :key="item.id" :label="`${item.name} · ${item.departmentName}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="departmentForm.sortNo" :min="0" :max="9999" :controls="false" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="departmentDialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveDepartment">保存</el-button></template>
    </el-dialog>

    <el-drawer v-model="employeeDrawer" :title="employeeForm.id ? '编辑人员' : '新增人员'" size="520px" destroy-on-close>
      <el-form ref="employeeFormRef" :model="employeeForm" :rules="employeeRules" label-width="96px" class="drawer-form">
        <el-form-item label="人员编码"><el-input v-model.trim="employeeForm.employeeNo" placeholder="留空自动生成" maxlength="32" /></el-form-item>
        <el-form-item label="姓名" prop="name"><el-input v-model.trim="employeeForm.name" maxlength="64" /></el-form-item>
        <el-form-item label="所属部门" prop="departmentId"><el-tree-select v-model="employeeForm.departmentId" :data="enabledDepartmentTree" node-key="id" check-strictly default-expand-all /></el-form-item>
        <el-form-item label="职务"><el-input v-model.trim="employeeForm.jobTitle" maxlength="64" /></el-form-item>
        <el-form-item label="手机"><el-input v-model.trim="employeeForm.mobile" maxlength="32" /></el-form-item>
        <el-form-item label="电子邮箱"><el-input v-model.trim="employeeForm.email" maxlength="128" /></el-form-item>
        <el-form-item label="入职日期"><el-date-picker v-model="employeeForm.hireDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="部门负责人"><el-switch v-model="employeeForm.departmentManager" /><span class="form-help">同一部门可设置负责人，便于数据范围与审批识别</span></el-form-item>
      </el-form>
      <template #footer><el-button @click="employeeDrawer = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveEmployee">保存</el-button></template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { api, type Department, type Employee, type EmploymentStatus } from '@/api/client'
import { useAppStore } from '@/stores/app'

type DepartmentNode = Department & { label: string; children: DepartmentNode[]; disabled?: boolean }

const appStore = useAppStore()
const departments = ref<Department[]>([])
const employees = ref<Employee[]>([])
const selectedDepartmentId = ref<number | null>(null)
const keyword = ref('')
const employmentFilter = ref<'ALL' | EmploymentStatus>('ACTIVE')
const saving = ref(false)
const departmentDialog = ref(false)
const employeeDrawer = ref(false)
const departmentFormRef = ref<FormInstance>()
const employeeFormRef = ref<FormInstance>()
const departmentForm = reactive({ id: 0, parentId: 0, code: '', name: '', managerEmployeeId: null as number | null, sortNo: 0 })
const employeeForm = reactive({ id: 0, employeeNo: '', name: '', departmentId: 0, jobTitle: '', mobile: '', email: '', hireDate: '', departmentManager: false })
const employmentOptions = [{ label: '在职', value: 'ACTIVE' }, { label: '已离职', value: 'LEFT' }, { label: '全部', value: 'ALL' }]
const departmentRules: FormRules = { name: [{ required: true, message: '请填写部门名称', trigger: 'blur' }], code: [{ required: true, message: '请填写部门编码', trigger: 'blur' }] }
const employeeRules: FormRules = { name: [{ required: true, message: '请填写姓名', trigger: 'blur' }], departmentId: [{ required: true, message: '请选择所属部门', trigger: 'change' }] }

const departmentTree = computed(() => buildDepartmentTree(departments.value))
const enabledDepartmentTree = computed(() => buildDepartmentTree(departments.value.filter((item) => item.status === 'ENABLED')))
const selectableDepartmentTree = computed(() => markDisabled(enabledDepartmentTree.value, departmentForm.id))
const selectedDepartment = computed(() => departments.value.find((item) => item.id === selectedDepartmentId.value))
const activeEmployees = computed(() => employees.value.filter((item) => item.employmentStatus === 'ACTIVE'))
const filteredEmployees = computed(() => employees.value.filter((item) => {
  const statusMatches = employmentFilter.value === 'ALL' || item.employmentStatus === employmentFilter.value
  const departmentMatches = !selectedDepartmentId.value || departmentDescendantIds.value.has(item.departmentId)
  const text = `${item.employeeNo} ${item.name} ${item.mobile} ${item.jobTitle}`.toLowerCase()
  return statusMatches && departmentMatches && (!keyword.value || text.includes(keyword.value.toLowerCase()))
}))
const departmentDescendantIds = computed(() => {
  if (!selectedDepartmentId.value) return new Set<number>()
  const ids = new Set<number>([selectedDepartmentId.value])
  let changed = true
  while (changed) {
    changed = false
    for (const item of departments.value) if (ids.has(item.parentId) && !ids.has(item.id)) { ids.add(item.id); changed = true }
  }
  return ids
})

onMounted(load)

async function load() {
  try { [departments.value, employees.value] = await Promise.all([api.departments(), api.employees()]) }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '组织数据加载失败') }
}

function buildDepartmentTree(rows: Department[]): DepartmentNode[] {
  const map = new Map<number, DepartmentNode>()
  rows.forEach((item) => map.set(item.id, { ...item, label: item.name, children: [] }))
  const roots: DepartmentNode[] = []
  map.forEach((item) => { const parent = map.get(item.parentId); (parent ? parent.children : roots).push(item) })
  const sort = (nodes: DepartmentNode[]) => nodes.sort((a, b) => a.sortNo - b.sortNo || a.id - b.id).forEach((item) => sort(item.children))
  sort(roots)
  return roots
}

function markDisabled(nodes: DepartmentNode[], currentId: number): DepartmentNode[] {
  const descendants = new Set<number>()
  const walk = (items: DepartmentNode[], blocked: boolean) => items.forEach((item) => { const next = blocked || item.id === currentId; if (next) descendants.add(item.id); walk(item.children, next) })
  walk(departmentTree.value, false)
  return nodes.map((item) => ({ ...item, disabled: descendants.has(item.id), children: markDisabled(item.children, currentId) }))
}

function selectDepartment(data: Department) { selectedDepartmentId.value = data.id }
function openDepartment(row?: Department) {
  Object.assign(departmentForm, row ? { id: row.id, parentId: row.parentId, code: row.code, name: row.name, managerEmployeeId: row.managerEmployeeId, sortNo: row.sortNo } : { id: 0, parentId: selectedDepartmentId.value || departments.value.find((item) => item.parentId === 0)?.id || 0, code: '', name: '', managerEmployeeId: null, sortNo: 0 })
  departmentDialog.value = true
}
async function saveDepartment() {
  if (!await departmentFormRef.value?.validate().catch(() => false)) return
  saving.value = true
  try {
    departmentForm.id ? await api.updateDepartment(departmentForm.id, departmentForm) : await api.createDepartment(departmentForm)
    ElMessage.success('部门已保存'); departmentDialog.value = false; await load()
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存失败') } finally { saving.value = false }
}
async function disableDepartment() {
  if (!selectedDepartment.value) return
  try {
    const { value } = await ElMessageBox.prompt('停用后不可再新增人员或业务归属，请填写停用原因。', `停用“${selectedDepartment.value.name}”`, { inputPattern: /\S+/, inputErrorMessage: '必须填写停用原因', type: 'warning' })
    await api.disableDepartment(selectedDepartment.value.id, value); ElMessage.success('部门已停用'); selectedDepartmentId.value = null; await load()
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '停用失败') }
}

function openEmployee(row?: Employee) {
  Object.assign(employeeForm, row ? { id: row.id, employeeNo: row.employeeNo, name: row.name, departmentId: row.departmentId, jobTitle: row.jobTitle, mobile: row.mobile, email: row.email, hireDate: row.hireDate || '', departmentManager: row.departmentManager } : { id: 0, employeeNo: '', name: '', departmentId: selectedDepartmentId.value || departments.value.find((item) => item.status === 'ENABLED')?.id || 0, jobTitle: '', mobile: '', email: '', hireDate: new Date().toISOString().slice(0, 10), departmentManager: false })
  employeeDrawer.value = true
}
async function saveEmployee() {
  if (!await employeeFormRef.value?.validate().catch(() => false)) return
  saving.value = true
  try {
    employeeForm.id ? await api.updateEmployee(employeeForm.id, employeeForm) : await api.createEmployee(employeeForm)
    ElMessage.success('人员档案已保存'); employeeDrawer.value = false; await load()
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '保存失败') } finally { saving.value = false }
}
async function leaveEmployee(row: Employee) {
  try {
    const { value } = await ElMessageBox.prompt('离职后将立即禁止登录且不能再被新单据引用，请填写离职原因。', `办理“${row.name}”离职`, { inputPattern: /\S+/, inputErrorMessage: '必须填写离职原因', type: 'warning' })
    await api.leaveEmployee(row.id, { leaveDate: new Date().toISOString().slice(0, 10), reason: value }); ElMessage.success('已办理离职'); await load()
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '办理失败') }
}
async function reinstateEmployee(row: Employee) {
  try { await ElMessageBox.confirm(`确认将“${row.name}”恢复为在职？登录权限仍需在用户管理中单独启用。`, '恢复在职'); await api.reinstateEmployee(row.id); ElMessage.success('已恢复在职'); await load() }
  catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '恢复失败') }
}
</script>
