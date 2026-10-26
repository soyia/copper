<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">仓库资料</div>
        <el-button v-if="canManage" type="primary" @click="openCreate">新增仓库</el-button>
      </div>
      <div class="list-tools">
        <el-input v-model="filters.keyword" clearable placeholder="仓库 / 位置 / 收货地址" />
        <el-select v-model="filters.status" clearable placeholder="状态">
          <el-option label="启用" value="ENABLED" />
          <el-option label="不可用" value="DISABLED" />
        </el-select>
        <div class="list-tools-actions">
          <el-button @click="resetFilters">重置</el-button>
        </div>
      </div>
      <el-table :data="filteredWarehouses" class="data-table" size="small" border show-overflow-tooltip max-height="calc(100vh - 274px)">
        <el-table-column prop="id" label="编号" width="80" fixed="left" />
        <el-table-column prop="name" label="仓库" width="180" fixed="left" />
        <el-table-column prop="location" label="位置" min-width="180" />
        <el-table-column label="收货地址列表" min-width="300">
          <template #default="{ row }"><span class="multi-line-text">{{ row.receivingAddresses || '-' }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">{{ statusName(row.status) }}</template>
        </el-table-column>
        <el-table-column v-if="canManage" label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="620px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="仓库" prop="name"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="位置"><el-input v-model="form.location" /></el-form-item>
        <el-form-item label="收货地址">
          <el-input v-model="form.receivingAddresses" type="textarea" :rows="4" placeholder="一行一个具体收货地址" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status">
            <el-option label="启用" value="ENABLED" />
            <el-option label="不可用" value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeDialog">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { api, type Warehouse } from '@/api/client'
import { statusName } from '@/api/format'
import { matchesKeyword } from '@/utils/listFilters'
import { useAppStore } from '@/stores/app'

const queryClient = useQueryClient()
const appStore = useAppStore()
const dialogVisible = ref(false)
const formRef = ref<FormInstance>()
const editingId = ref<number>()
const filters = reactive({ keyword: '', status: '' })
const form = reactive<Partial<Warehouse>>({ name: '', location: '', receivingAddresses: '', status: 'ENABLED' })
const dialogTitle = computed(() => (editingId.value ? '编辑仓库' : '新增仓库'))
const canManage = computed(() => appStore.hasPermission('warehouse.manage'))
const saving = computed(() => createMutation.isPending.value || updateMutation.isPending.value)
const rules: FormRules = {
  name: [{ required: true, message: '请填写仓库名称', trigger: 'blur' }],
}

const { data: warehouses } = useQuery({ queryKey: ['warehouses'], queryFn: api.warehouses })
const filteredWarehouses = computed(() =>
  (warehouses.value ?? []).filter(
    (item) =>
      (!filters.status || item.status === filters.status) &&
      matchesKeyword(item, filters.keyword, ['name', 'location', 'receivingAddresses']),
  ),
)

const createMutation = useMutation({
  mutationFn: api.createWarehouse,
  onSuccess: async () => {
    ElMessage.success('仓库已新增')
    closeDialog()
    await queryClient.invalidateQueries({ queryKey: ['warehouses'] })
  },
  onError: (error) => ElMessage.error(errorMessage(error, '仓库新增失败')),
})

const updateMutation = useMutation({
  mutationFn: ({ id, data }: { id: number; data: Partial<Warehouse> }) => api.updateWarehouse(id, data),
  onSuccess: async () => {
    ElMessage.success('仓库已更新')
    closeDialog()
    await queryClient.invalidateQueries({ queryKey: ['warehouses'] })
  },
  onError: (error) => ElMessage.error(errorMessage(error, '仓库更新失败')),
})

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: Warehouse) {
  editingId.value = row.id
  form.name = row.name
  form.location = row.location
  form.receivingAddresses = row.receivingAddresses
  form.status = row.status || 'ENABLED'
  dialogVisible.value = true
}

async function save() {
  if (!canManage.value || saving.value) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (editingId.value) {
    updateMutation.mutate({ id: editingId.value, data: { ...form } })
  } else {
    createMutation.mutate({ ...form })
  }
}

function errorMessage(error: unknown, fallback: string) {
  const responseMessage = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  return responseMessage || (error instanceof Error ? error.message : fallback)
}

function closeDialog() {
  dialogVisible.value = false
  resetForm()
}

function resetForm() {
  editingId.value = undefined
  form.name = ''
  form.location = ''
  form.receivingAddresses = ''
  form.status = 'ENABLED'
  formRef.value?.clearValidate()
}

function resetFilters() {
  filters.keyword = ''
  filters.status = ''
}
</script>
