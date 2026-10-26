<template>
  <div class="page field-library-page">
    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">规格字段库</div>
          <div class="muted">统一维护可复用的规格字段，再到“系列规格模板”分配给产品系列。</div>
        </div>
        <el-button type="primary" @click="openCreate">新增字段</el-button>
      </div>
      <div class="list-tools">
        <el-input v-model="filters.keyword" clearable placeholder="字段名称 / 输入类型 / 选项" />
        <el-select v-model="filters.status" clearable placeholder="状态">
          <el-option label="启用" value="ENABLED" />
          <el-option label="不可用" value="DISABLED" />
        </el-select>
        <div class="list-tools-actions">
          <el-button @click="resetFilters">重置</el-button>
        </div>
      </div>
      <el-table
        :data="filteredFields"
        class="data-table"
        size="small"
        border
        show-overflow-tooltip
        :row-class-name="fieldRowClassName"
        max-height="calc(100vh - 274px)"
      >
        <el-table-column prop="id" label="编号" width="80" fixed="left" />
        <el-table-column prop="name" label="字段名称" min-width="150" fixed="left" />
        <el-table-column label="输入方式" width="110">
          <template #default="{ row }">{{ fieldTypeName(row.fieldType) }}</template>
        </el-table-column>
        <el-table-column label="数值类型" width="110">
          <template #default="{ row }">{{ valueTypeName(row.valueType) }}</template>
        </el-table-column>
        <el-table-column label="默认单位分类" min-width="140">
          <template #default="{ row }">{{ unitCategoryName(row.unitCategoryId) }}</template>
        </el-table-column>
        <el-table-column label="可选值" min-width="260">
          <template #default="{ row }">{{ optionSummary(row.optionJson) }}</template>
        </el-table-column>
        <el-table-column label="使用情况" width="180" align="center">
          <template #default="{ row }">
            <el-button v-if="row.seriesCount" text type="primary" @click="openUsages(row)">{{ row.seriesCount }} 个系列</el-button>
            <span v-else class="muted">未使用</span>
            <div v-if="row.productCount || row.presetCount" class="muted">商品 {{ row.productCount }} / 预设 {{ row.presetCount }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">{{ statusName(row.status) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="560px" @closed="resetForm">
      <el-alert
        v-if="editingUsedField"
        type="info"
        :closable="false"
        show-icon
        :title="`该字段已用于 ${form.seriesCount || 0} 个系列、${form.productCount || 0} 个商品和 ${form.presetCount || 0} 个预设；字段名称和输入类型不可修改。`"
        style="margin-bottom: 16px"
      />
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item label="字段名称" prop="name">
          <el-input v-model="form.name" :disabled="editingUsedField" placeholder="例如：外径、壁厚、截面类型" />
        </el-form-item>
        <el-form-item label="输入方式" prop="fieldType">
          <el-select v-model="form.fieldType" :disabled="editingUsedField" style="width: 100%">
            <el-option label="文本输入" value="TEXT" />
            <el-option label="数字输入" value="NUMBER" />
            <el-option label="选项选择" value="SELECT" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.fieldType === 'NUMBER'" label="数值类型">
          <el-select v-model="form.valueType" :disabled="editingUsedField" style="width: 100%">
            <el-option label="小数" value="DECIMAL" />
            <el-option label="整数" value="INTEGER" />
          </el-select>
        </el-form-item>
        <el-form-item label="默认单位分类">
          <el-select v-model="form.unitCategoryId" clearable filterable placeholder="无默认单位分类" style="width: 100%">
            <el-option v-for="item in unitCategories ?? []" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.fieldType === 'SELECT'" label="可选值" required>
          <div class="field-option-editor">
            <div v-for="option in fieldOptions" :key="option.id" class="field-option-row">
              <el-input v-model="option.value" placeholder="输入一个可选值" />
              <el-button text type="danger" @click="removeOption(option.id)">删除</el-button>
            </div>
            <el-button text type="primary" @click="addOption">添加选项</el-button>
          </div>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%">
            <el-option label="启用" value="ENABLED" />
            <el-option label="不可用" value="DISABLED" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="closeDialog">取消</el-button>
        <el-button type="primary" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <RelatedDetailDrawer
      v-model="usageDialogVisible"
      :title="`${usageFieldName} · 使用位置`"
      subtitle="查看该规格字段在产品系列、商品和预设中的引用情况"
      :trail="[{ label: '规格字段库' }, { label: usageFieldName }]"
      :loading="usageLoading"
      size="1000px"
      @breadcrumb="usageDialogVisible = false"
    >
      <el-table v-loading="usageLoading" :data="usageRows" size="small" border show-overflow-tooltip max-height="520">
        <el-table-column prop="categoryName" label="产品分类" min-width="130" />
        <el-table-column prop="productSeriesName" label="产品系列" min-width="170" />
        <el-table-column prop="displayName" label="系列内显示名" min-width="140" />
        <el-table-column prop="unitName" label="单位" width="80" />
        <el-table-column label="业务用途" min-width="230">
          <template #default="{ row }">
            <div class="field-purpose-tags">
              <el-tag v-if="row.skuDimension" type="danger" size="small">SKU识别</el-tag>
              <el-tag v-if="row.includeInSpec" type="success" size="small">规格文本</el-tag>
              <el-tag v-if="row.searchable" size="small">列表筛选</el-tag>
              <el-tag v-if="row.orderVisible" type="warning" size="small">业务单据</el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="建档" width="80" align="center"><template #default="{ row }">{{ row.required ? '必填' : '选填' }}</template></el-table-column>
        <el-table-column label="数据量" width="150" align="center"><template #default="{ row }">商品 {{ row.productCount }} / 预设 {{ row.presetCount }}</template></el-table-column>
        <el-table-column label="状态" width="80"><template #default="{ row }">{{ statusName(row.status) }}</template></el-table-column>
      </el-table>
      <template #footer><el-button @click="usageDialogVisible = false">关闭</el-button></template>
    </RelatedDetailDrawer>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { api, type CustomField, type CustomFieldUsage } from '@/api/client'
import { statusName } from '@/api/format'
import { matchesKeyword } from '@/utils/listFilters'
import RelatedDetailDrawer from '@/components/RelatedDetailDrawer.vue'

type FieldOption = { id: number; value: string }

const queryClient = useQueryClient()
const filters = reactive({ keyword: '', status: '' })
const dialogVisible = ref(false)
const usageDialogVisible = ref(false)
const usageLoading = ref(false)
const usageFieldName = ref('')
const usageRows = ref<CustomFieldUsage[]>([])
const formRef = ref<FormInstance>()
const editingId = ref<number>()
const editingUsedField = ref(false)
let optionId = 0
const createOption = (value = ''): FieldOption => ({ id: ++optionId, value })
const fieldOptions = ref<FieldOption[]>([createOption()])
const form = reactive<Partial<CustomField>>({
  name: '',
  fieldType: 'TEXT',
  valueType: 'TEXT',
  unitCategoryId: undefined,
  optionJson: '[]',
  status: 'ENABLED',
})

const { data: fields } = useQuery({ queryKey: ['custom-fields'], queryFn: api.customFields })
const { data: unitCategories } = useQuery({ queryKey: ['unit-categories'], queryFn: api.unitCategories })
const unitCategoryNames = computed(() => new Map((unitCategories.value ?? []).map((item) => [item.id, item.name])))
const dialogTitle = computed(() => (editingId.value ? '编辑规格字段' : '新增规格字段'))
const filteredFields = computed(() =>
  (fields.value ?? []).filter((item) =>
    (!filters.status || item.status === filters.status)
      && matchesKeyword(
        { ...item, optionText: optionSummary(item.optionJson), typeText: fieldTypeName(item.fieldType) },
        filters.keyword,
        ['id', 'name', 'optionText', 'typeText'],
      ),
  ),
)
const rules: FormRules = {
  name: [{ required: true, message: '请填写字段名称', trigger: 'blur' }],
  fieldType: [{ required: true, message: '请选择输入方式', trigger: 'change' }],
}

watch(
  () => form.fieldType,
  (value) => {
    if (value === 'NUMBER') {
      if (!['DECIMAL', 'INTEGER'].includes(String(form.valueType))) form.valueType = 'DECIMAL'
      return
    }
    form.valueType = 'TEXT'
    if (value === 'SELECT' && !fieldOptions.value.length) fieldOptions.value = [createOption()]
  },
)

const createMutation = useMutation({
  mutationFn: api.createCustomField,
  onSuccess: async () => {
    ElMessage.success('规格字段已新增')
    closeDialog()
    await queryClient.invalidateQueries({ queryKey: ['custom-fields'] })
  },
})

const updateMutation = useMutation({
  mutationFn: ({ id, data }: { id: number; data: Partial<CustomField> }) => api.updateCustomField(id, data),
  onSuccess: async () => {
    ElMessage.success('规格字段已更新')
    closeDialog()
    await queryClient.invalidateQueries({ queryKey: ['custom-fields'] })
  },
})

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function openEdit(row: CustomField) {
  editingId.value = row.id
  editingUsedField.value = row.seriesCount > 0
  form.name = row.name
  form.fieldType = row.fieldType
  form.valueType = row.valueType
  form.unitCategoryId = row.unitCategoryId || undefined
  form.optionJson = row.optionJson || '[]'
  form.status = row.status || 'ENABLED'
  form.seriesCount = row.seriesCount
  form.productCount = row.productCount
  form.presetCount = row.presetCount
  fieldOptions.value = parseOptions(row.optionJson).map(createOption)
  if (!fieldOptions.value.length) fieldOptions.value = [createOption()]
  dialogVisible.value = true
}

async function openUsages(row: CustomField) {
  usageFieldName.value = row.name
  usageRows.value = []
  usageDialogVisible.value = true
  usageLoading.value = true
  try {
    usageRows.value = await api.customFieldUsages(row.id)
  } finally {
    usageLoading.value = false
  }
}

async function save() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  const options = fieldOptions.value.map((item) => item.value.trim()).filter(Boolean)
  if (form.fieldType === 'SELECT' && !options.length) {
    ElMessage.warning('选项字段至少需要一个可选值')
    return
  }
  if (new Set(options).size !== options.length) {
    ElMessage.warning('可选值不能重复')
    return
  }
  const payload = {
    ...form,
    name: form.name?.trim(),
    valueType: form.fieldType === 'NUMBER' ? form.valueType : 'TEXT',
    optionJson: form.fieldType === 'SELECT' ? JSON.stringify(options) : '[]',
  }
  if (editingId.value) {
    updateMutation.mutate({ id: editingId.value, data: payload })
  } else {
    createMutation.mutate(payload)
  }
}

function closeDialog() {
  dialogVisible.value = false
  resetForm()
}

function resetForm() {
  editingId.value = undefined
  editingUsedField.value = false
  form.name = ''
  form.fieldType = 'TEXT'
  form.valueType = 'TEXT'
  form.unitCategoryId = undefined
  form.optionJson = '[]'
  form.status = 'ENABLED'
  form.seriesCount = 0
  form.productCount = 0
  form.presetCount = 0
  fieldOptions.value = [createOption()]
  formRef.value?.clearValidate()
}

function addOption() {
  fieldOptions.value.push(createOption())
}

function removeOption(id: number) {
  if (fieldOptions.value.length === 1) {
    fieldOptions.value = [createOption()]
    return
  }
  fieldOptions.value = fieldOptions.value.filter((item) => item.id !== id)
}

function parseOptions(value?: string) {
  try {
    const parsed = JSON.parse(value || '[]')
    return Array.isArray(parsed) ? parsed.map((item) => String(item)) : []
  } catch {
    return []
  }
}

function optionSummary(value?: string) {
  const options = parseOptions(value)
  return options.length ? options.join('、') : '—'
}

function fieldTypeName(value?: string) {
  return ({ TEXT: '文本输入', NUMBER: '数字输入', SELECT: '选项选择' } as Record<string, string>)[String(value)] || value || '—'
}

function valueTypeName(value?: string) {
  return ({ TEXT: '文本', DECIMAL: '小数', INTEGER: '整数' } as Record<string, string>)[String(value)] || value || '—'
}

function unitCategoryName(id?: number) {
  if (!id) return '—'
  return unitCategoryNames.value.get(id) || `未知分类（${id}）`
}

function fieldRowClassName({ row }: { row: CustomField }) {
  return row.status === 'DISABLED' ? 'master-data-row-disabled' : ''
}

function resetFilters() {
  filters.keyword = ''
  filters.status = ''
}
</script>
