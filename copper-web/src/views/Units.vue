<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">计量分类</div>
        <el-button type="primary" @click="openCategoryCreate">新增分类</el-button>
      </div>
      <div class="list-tools">
        <el-input v-model="categoryFilters.keyword" clearable placeholder="分类 / 说明" />
        <div class="list-tools-actions">
          <el-button @click="categoryFilters.keyword = ''">重置</el-button>
        </div>
      </div>
      <el-table :data="filteredCategories" class="data-table" size="small" border show-overflow-tooltip max-height="320">
        <el-table-column prop="id" label="编号" width="80" fixed="left" />
        <el-table-column prop="name" label="分类" width="160" fixed="left" />
        <el-table-column prop="description" label="说明" min-width="220" />
        <el-table-column label="状态" width="100" fixed="right">
          <template #default="{ row }">{{ statusName(row.status) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" @click="openCategoryEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">计量单位</div>
        <el-button type="primary" @click="openUnitCreate">新增单位</el-button>
      </div>
      <div class="list-tools">
        <el-input v-model="unitFilters.keyword" clearable placeholder="单位 / 缩写 / 别名 / 说明" />
        <el-select v-model="unitFilters.unitCategoryId" clearable placeholder="分类">
          <el-option v-for="item in categories ?? []" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <div class="list-tools-actions">
          <el-button @click="resetUnitFilters">重置</el-button>
        </div>
      </div>
      <el-table :data="filteredUnits" class="data-table" size="small" border show-overflow-tooltip max-height="calc(100vh - 520px)">
        <el-table-column prop="id" label="编号" width="80" fixed="left" />
        <el-table-column prop="unitCategoryName" label="分类" width="140" fixed="left" />
        <el-table-column prop="name" label="单位" width="140" fixed="left" />
        <el-table-column prop="abbreviation" label="缩写" width="100" />
        <el-table-column label="单位类型" width="110"><template #default="{ row }">{{ unitKindName(row.unitKind) }}</template></el-table-column>
        <el-table-column label="受控别名" min-width="180"><template #default="{ row }">{{ row.aliases?.join('、') || '-' }}</template></el-table-column>
        <el-table-column prop="description" label="说明" min-width="220" />
        <el-table-column label="状态" width="100" fixed="right">
          <template #default="{ row }">{{ statusName(row.status) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" @click="openUnitEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="categoryDialogVisible" :title="categoryDialogTitle" width="460px">
      <el-form ref="categoryFormRef" :model="categoryForm" :rules="categoryRules" label-width="80px">
        <el-form-item label="名称" prop="name"><el-input v-model="categoryForm.name" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="categoryForm.description" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="categoryForm.status">
            <el-option label="启用" value="ENABLED" />
            <el-option label="不可用" value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeCategoryDialog">取消</el-button>
        <el-button type="primary" @click="saveCategory">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="unitDialogVisible" :title="unitDialogTitle" width="460px">
      <el-form ref="unitFormRef" :model="unitForm" :rules="unitRules" label-width="80px">
        <el-form-item label="分类" prop="unitCategoryId">
          <el-select v-model="unitForm.unitCategoryId">
            <el-option v-for="item in categories ?? []" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称" prop="name"><el-input v-model="unitForm.name" /></el-form-item>
        <el-form-item label="缩写"><el-input v-model="unitForm.abbreviation" /></el-form-item>
        <el-form-item label="单位类型">
          <el-select v-model="unitForm.unitKind">
            <el-option label="度量单位" value="MEASURE" />
            <el-option label="计数单位" value="COUNT" />
            <el-option label="包装单位" value="PACKAGE" />
          </el-select>
        </el-form-item>
        <el-form-item label="受控别名">
          <el-select v-model="unitForm.aliases" multiple filterable allow-create default-first-option placeholder="输入一个别名后按回车">
            <el-option v-for="alias in unitForm.aliases ?? []" :key="alias" :label="alias" :value="alias" />
          </el-select>
        </el-form-item>
        <el-alert type="info" :closable="false" title="名称、缩写和别名全局唯一；导入时只按这些受控值归一化，不会静默创建新单位。" style="margin-bottom: 12px" />
        <el-form-item label="说明"><el-input v-model="unitForm.description" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="unitForm.status">
            <el-option label="启用" value="ENABLED" />
            <el-option label="不可用" value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeUnitDialog">取消</el-button>
        <el-button type="primary" @click="saveUnit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { api, type Unit, type UnitCategory } from '@/api/client'
import { statusName } from '@/api/format'
import { matchesKeyword } from '@/utils/listFilters'

const queryClient = useQueryClient()
const categoryDialogVisible = ref(false)
const unitDialogVisible = ref(false)
const categoryFormRef = ref<FormInstance>()
const unitFormRef = ref<FormInstance>()
const editingCategoryId = ref<number>()
const editingUnitId = ref<number>()
const categoryForm = reactive<Partial<UnitCategory>>({ name: '', description: '', status: 'ENABLED' })
const unitForm = reactive<Partial<Unit>>({ unitCategoryId: undefined, name: '', abbreviation: '', description: '', unitKind: 'MEASURE', aliases: [], status: 'ENABLED' })
const categoryFilters = reactive({ keyword: '' })
const unitFilters = reactive({ keyword: '', unitCategoryId: undefined as number | undefined })
const categoryDialogTitle = computed(() => (editingCategoryId.value ? '编辑计量分类' : '新增计量分类'))
const unitDialogTitle = computed(() => (editingUnitId.value ? '编辑计量单位' : '新增计量单位'))

const { data: categories } = useQuery({ queryKey: ['unit-categories'], queryFn: api.unitCategories })
const { data: units } = useQuery({ queryKey: ['units'], queryFn: api.units })
const filteredCategories = computed(() => (categories.value ?? []).filter((item) => matchesKeyword(item, categoryFilters.keyword, ['name', 'description'])))
const filteredUnits = computed(() =>
  (units.value ?? []).filter(
    (item) =>
      (!unitFilters.unitCategoryId || item.unitCategoryId === unitFilters.unitCategoryId) &&
      matchesKeyword({ ...item, aliasesText: item.aliases?.join(' ') }, unitFilters.keyword, ['unitCategoryName', 'name', 'abbreviation', 'aliasesText', 'description']),
  ),
)
const categoryRules: FormRules = {
  name: [{ required: true, message: '请填写分类名称', trigger: 'blur' }],
}
const unitRules: FormRules = {
  unitCategoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
  name: [{ required: true, message: '请填写单位名称', trigger: 'blur' }],
}

const createCategoryMutation = useMutation({
  mutationFn: api.createUnitCategory,
  onSuccess: async () => {
    ElMessage.success('计量分类已新增')
    closeCategoryDialog()
    await queryClient.invalidateQueries({ queryKey: ['unit-categories'] })
  },
})

const updateCategoryMutation = useMutation({
  mutationFn: ({ id, data }: { id: number; data: Partial<UnitCategory> }) => api.updateUnitCategory(id, data),
  onSuccess: async () => {
    ElMessage.success('计量分类已更新')
    closeCategoryDialog()
    await queryClient.invalidateQueries({ queryKey: ['unit-categories'] })
  },
})

const createUnitMutation = useMutation({
  mutationFn: api.createUnit,
  onSuccess: async () => {
    ElMessage.success('计量单位已新增')
    closeUnitDialog()
    await queryClient.invalidateQueries({ queryKey: ['units'] })
  },
})

const updateUnitMutation = useMutation({
  mutationFn: ({ id, data }: { id: number; data: Partial<Unit> }) => api.updateUnit(id, data),
  onSuccess: async () => {
    ElMessage.success('计量单位已更新')
    closeUnitDialog()
    await queryClient.invalidateQueries({ queryKey: ['units'] })
  },
})

function openCategoryCreate() {
  resetCategoryForm()
  categoryDialogVisible.value = true
}

function openCategoryEdit(row: UnitCategory) {
  editingCategoryId.value = row.id
  categoryForm.name = row.name
  categoryForm.description = row.description
  categoryForm.status = row.status || 'ENABLED'
  categoryDialogVisible.value = true
}

function openUnitCreate() {
  resetUnitForm()
  unitDialogVisible.value = true
}

function openUnitEdit(row: Unit) {
  editingUnitId.value = row.id
  unitForm.unitCategoryId = row.unitCategoryId
  unitForm.name = row.name
  unitForm.abbreviation = row.abbreviation
  unitForm.description = row.description
  unitForm.unitKind = row.unitKind || 'MEASURE'
  unitForm.aliases = [...(row.aliases ?? [])]
  unitForm.status = row.status || 'ENABLED'
  unitDialogVisible.value = true
}

async function saveCategory() {
  const valid = await categoryFormRef.value?.validate().catch(() => false)
  if (!valid) return
  if (editingCategoryId.value) {
    updateCategoryMutation.mutate({ id: editingCategoryId.value, data: { ...categoryForm } })
  } else {
    createCategoryMutation.mutate({ ...categoryForm })
  }
}

async function saveUnit() {
  const valid = await unitFormRef.value?.validate().catch(() => false)
  if (!valid) return
  if (editingUnitId.value) {
    updateUnitMutation.mutate({ id: editingUnitId.value, data: { ...unitForm } })
  } else {
    createUnitMutation.mutate({ ...unitForm })
  }
}

function closeCategoryDialog() {
  categoryDialogVisible.value = false
  resetCategoryForm()
}

function closeUnitDialog() {
  unitDialogVisible.value = false
  resetUnitForm()
}

function resetCategoryForm() {
  editingCategoryId.value = undefined
  categoryForm.name = ''
  categoryForm.description = ''
  categoryForm.status = 'ENABLED'
  categoryFormRef.value?.clearValidate()
}

function resetUnitForm() {
  editingUnitId.value = undefined
  unitForm.unitCategoryId = undefined
  unitForm.name = ''
  unitForm.abbreviation = ''
  unitForm.description = ''
  unitForm.unitKind = 'MEASURE'
  unitForm.aliases = []
  unitForm.status = 'ENABLED'
  unitFormRef.value?.clearValidate()
}

function resetUnitFilters() {
  unitFilters.keyword = ''
  unitFilters.unitCategoryId = undefined
}

function unitKindName(kind?: string) {
  return kind === 'COUNT' ? '计数' : kind === 'PACKAGE' ? '包装' : '度量'
}
</script>
