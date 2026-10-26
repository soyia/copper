<template>
  <div class="page product-matrix-page">
    <PageHeader title="商品矩阵建档" back-to="/products" back-label="返回商品资料">
      <el-button :disabled="checking || saving || matrixRows.length >= MAX_MATRIX_ROWS" @click="addRow">新增一行</el-button>
      <el-button :loading="checking" :disabled="!matrixRows.length || !selectedSeries" @click="previewRows">重复检查</el-button>
      <el-button type="primary" :loading="saving" :disabled="!canSave" @click="saveRows">创建 {{ creatableRows.length }} 个商品</el-button>
    </PageHeader>

    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">1. 公共规格与逐行维度</div>
          <div class="muted">适合牌号、状态、标准相同，仅尺寸不同的一组商品；每个商品仍独立校验和保存。</div>
        </div>
      </div>
      <div class="panel-body">
        <el-row :gutter="16">
          <el-col :span="10">
            <el-form-item label="产品系列" label-width="90px">
              <el-select v-model="seriesId" filterable placeholder="选择产品系列" style="width:100%" @change="loadSeries">
                <el-option v-for="item in series ?? []" :key="item.id" :label="`${item.categoryName || '未分类'} / ${item.name}`" :value="item.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="14">
            <el-form-item label="逐行变化字段" label-width="110px">
              <el-checkbox-group v-model="rowFieldIds" :disabled="!seriesFields.length">
                <el-checkbox v-for="field in seriesFields" :key="field.id" :value="field.id">
                  {{ field.displayName }}<el-tag v-if="isIdentityField(field)" size="small" type="warning" effect="plain">区分</el-tag>
                </el-checkbox>
              </el-checkbox-group>
            </el-form-item>
          </el-col>
        </el-row>

        <el-alert
          v-if="selectedSeries"
          :type="identityFields.length ? 'info' : 'warning'"
          :closable="false"
          :title="identityFields.length ? `用于区分商品：${identityFields.map((item) => item.displayName).join('、')}` : '该系列尚未勾选用于区分商品的规格'"
          description="这些字段由分类与规格配置自动生效，每一行仍会执行同规格重复检查。"
          class="identity-alert"
        />
        <el-alert
          v-if="seriesFields.some((field) => referenceTypeForField(field))"
          type="info"
          :closable="false"
          title="牌号、材料状态和执行标准可选择已有值，也可直接输入厂家写法。"
          class="identity-alert"
        />

        <el-row v-if="commonFields.length" :gutter="12">
          <el-col v-for="field in commonFields" :key="field.id" :span="8">
            <el-form-item :label="`${field.displayName}${field.unitName ? `(${field.unitName})` : ''}`" :required="field.required">
              <el-select
                v-if="referenceTypeForField(field)"
                :model-value="commonValues[field.id]"
                clearable
                filterable
                allow-create
                default-first-option
                placeholder="选择或直接输入"
                @update:model-value="setCommonValue(field.id, $event)"
              >
                <el-option v-for="option in referenceOptions(field)" :key="option.id" :label="`${option.code} · ${option.name}`" :value="option.code" />
              </el-select>
              <el-select
                v-else-if="fieldOptions(field).length"
                :model-value="commonValues[field.id]"
                clearable
                filterable
                @update:model-value="setCommonValue(field.id, $event)"
              >
                <el-option v-for="option in fieldOptions(field)" :key="option" :label="option" :value="option" />
              </el-select>
              <el-input-number
                v-else-if="field.fieldType === 'NUMBER'"
                :model-value="numericValue(commonValues[field.id])"
                :precision="field.decimalScale"
                :min="field.minValue ?? undefined"
                :max="field.maxValue ?? undefined"
                style="width:100%"
                @update:model-value="setCommonValue(field.id, $event)"
              :controls="false" />
              <el-input v-else :model-value="textValue(commonValues[field.id])" @update:model-value="setCommonValue(field.id, $event)" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-empty v-else-if="selectedSeries && !seriesFields.length" description="该系列没有可用规格字段，请先完成系列规格配置" :image-size="72" />

        <div v-if="rowFields.length" class="cartesian-builder">
          <div class="cartesian-builder__header">
            <div>
              <div class="cartesian-builder__title">规格组合预览</div>
              <div class="muted">为每个逐行维度选择多个值，系统按“公共字段 × 各规格维度”生成商品明细。</div>
            </div>
            <el-tag :type="cartesianPlan.error ? 'danger' : cartesianPlan.count ? 'success' : 'info'" effect="plain">
              {{ cartesianPlan.count > MAX_MATRIX_ROWS ? `超过 ${MAX_MATRIX_ROWS} 行` : cartesianPlan.error ? '待补规格值' : `${cartesianPlan.count} 个组合` }}
            </el-tag>
          </div>
          <el-row :gutter="12">
            <el-col v-for="field in rowFields" :key="field.id" :span="8">
              <el-form-item :label="`${field.displayName}${field.unitName ? `(${field.unitName})` : ''}`" required>
                <el-select
                  v-if="referenceTypeForField(field)"
                  :model-value="dimensionValues[field.id] ?? []"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  collapse-tags
                  collapse-tags-tooltip
                  placeholder="选择或输入一个或多个值"
                  @update:model-value="setDimensionValues(field, $event)"
                >
                  <el-option v-for="option in referenceOptions(field)" :key="option.id" :label="`${option.code} · ${option.name}`" :value="option.code" />
                </el-select>
                <el-select
                  v-else-if="fieldOptions(field).length"
                  :model-value="dimensionValues[field.id] ?? []"
                  multiple
                  filterable
                  collapse-tags
                  collapse-tags-tooltip
                  placeholder="选择一个或多个规格值"
                  @update:model-value="setDimensionValues(field, $event)"
                >
                  <el-option v-for="option in fieldOptions(field)" :key="option" :label="option" :value="option" />
                </el-select>
                <el-select
                  v-else
                  :model-value="dimensionValues[field.id] ?? []"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  collapse-tags
                  collapse-tags-tooltip
                  placeholder="输入一个值后按回车，可继续输入"
                  @update:model-value="setDimensionValues(field, $event)"
                />
                <div class="dimension-help">
                  已选 {{ dimensionValues[field.id]?.length ?? 0 }} 个值
                  <span v-if="field.fieldType === 'NUMBER'">；仅接受字段范围内的数字</span>
                </div>
              </el-form-item>
            </el-col>
          </el-row>
          <el-alert
            :type="cartesianPlan.error ? 'error' : cartesianPlan.count ? 'success' : 'info'"
            :closable="false"
            show-icon
            :title="cartesianPlanTitle"
            :description="cartesianPlanDescription"
          />
          <div v-if="cartesianPreviewLabels.length" class="cartesian-preview">
            <span class="muted">组合预览（仅展示前 {{ cartesianPreviewLabels.length }} 个）：</span>
            <el-tag v-for="(label, index) in cartesianPreviewLabels" :key="`${label}-${index}`" effect="plain">{{ label }}</el-tag>
          </div>
          <div class="cartesian-builder__actions">
            <span class="muted">预览不会改动下方明细；生成时如已有录入内容，必须确认后才会整体替换。</span>
            <el-button
              type="primary"
              plain
              :disabled="!canGenerateCartesian || checking || saving"
              @click="generateCartesianRows"
            >
              生成并替换为 {{ cartesianPlan.count }} 行明细
            </el-button>
          </div>
        </div>

        <el-collapse v-if="selectedSeries && canManageSupplierOffers" class="supplier-common-collapse">
          <el-collapse-item title="同时建立统一供应商供货关系（可选）" name="supplier">
            <el-row :gutter="12">
              <el-col :span="8"><el-form-item label="供应商"><PartnerRemoteSelect v-model="supplierForm.supplierId" type="SUPPLIER" clearable /></el-form-item></el-col>
              <el-col :span="4"><el-form-item label="采购单位"><el-select v-model="supplierForm.purchaseUnit" filterable><el-option v-for="unit in purchaseUnitOptions" :key="unit" :label="unit" :value="unit" /></el-select></el-form-item></el-col>
              <el-col :span="4"><el-form-item label="计价单位"><el-select v-model="supplierForm.priceUnit" filterable><el-option v-for="unit in priceUnitOptions" :key="unit" :label="unit" :value="unit" /></el-select></el-form-item></el-col>
              <el-col :span="4"><el-form-item label="参考报价（可空）"><el-input-number v-model="supplierForm.quotedUnitPrice" :min="0.00000001" :precision="8" placeholder="动态铜价可留空" style="width:100%" :controls="false" /></el-form-item></el-col>
              <el-col :span="4"><el-form-item label="起订量"><el-input-number v-model="supplierForm.minimumOrderQuantity" :min="0" :precision="6" style="width:100%" :controls="false" /></el-form-item></el-col>
              <el-col :span="4"><el-form-item label="订货倍数"><el-input-number v-model="supplierForm.orderMultiple" :min="0" :precision="6" style="width:100%" :controls="false" /></el-form-item></el-col>
              <el-col :span="4"><el-form-item label="交期(天)"><el-input-number v-model="supplierForm.leadTimeDays" :min="0" :precision="0" style="width:100%" :controls="false" /></el-form-item></el-col>
              <el-col :span="24"><el-alert type="info" :closable="false" title="报价留空时仅批量建立供货条款；采购开单另取价格中心建议价或手工价。" /></el-col>
            </el-row>
          </el-collapse-item>
        </el-collapse>
      </div>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">2. 批量粘贴和逐行确认</div>
          <div class="muted">{{ pasteHint }}</div>
        </div>
        <el-button @click="pasteVisible = !pasteVisible">{{ pasteVisible ? '收起批量粘贴' : '批量粘贴尺寸' }}</el-button>
      </div>
      <div v-if="pasteVisible" class="matrix-paste">
        <el-input v-model="pasteText" type="textarea" :rows="5" :placeholder="pastePlaceholder" :disabled="checking || saving" />
        <div class="matrix-paste-actions">
          <span class="muted">每行一个商品；字段之间使用 Tab、逗号或“×”分隔；单次最多 {{ MAX_MATRIX_ROWS }} 行。</span>
          <el-button type="primary" plain :disabled="!rowFields.length || !pasteText.trim() || checking || saving" @click="applyPaste">生成并替换矩阵行</el-button>
        </div>
      </div>
      <el-table :data="matrixRows" border size="small" row-key="key" max-height="calc(100vh - 370px)">
        <el-table-column type="index" label="#" width="52" fixed="left" />
        <el-table-column v-for="field in rowFields" :key="field.id" :label="`${field.displayName}${field.unitName ? `(${field.unitName})` : ''}`" min-width="145">
          <template #default="{ row }">
            <el-select
              v-if="referenceTypeForField(field)"
              :model-value="row.values[field.id]"
              clearable
              filterable
              allow-create
              default-first-option
              placeholder="选择或输入"
              @update:model-value="setRowValue(row, field.id, $event)"
            >
              <el-option v-for="option in referenceOptions(field)" :key="option.id" :label="`${option.code} · ${option.name}`" :value="option.code" />
            </el-select>
            <el-select
              v-else-if="fieldOptions(field).length"
              :model-value="row.values[field.id]"
              clearable
              filterable
              @update:model-value="setRowValue(row, field.id, $event)"
            >
              <el-option v-for="option in fieldOptions(field)" :key="option" :label="option" :value="option" />
            </el-select>
            <el-input-number
              v-else-if="field.fieldType === 'NUMBER'"
              :model-value="numericValue(row.values[field.id])"
              :precision="field.decimalScale"
              :min="field.minValue ?? undefined"
              :max="field.maxValue ?? undefined"
              style="width:100%"
              @update:model-value="setRowValue(row, field.id, $event)"
            :controls="false" />
            <el-input v-else :model-value="textValue(row.values[field.id])" @update:model-value="setRowValue(row, field.id, $event)" />
          </template>
        </el-table-column>
        <el-table-column label="商品名称" min-width="240">
          <template #default="{ row }"><el-input v-model="row.name" @input="invalidateRow(row)" /></template>
        </el-table-column>
        <el-table-column v-if="supplierForm.supplierId" label="供应商货号" min-width="160">
          <template #default="{ row }"><el-input v-model="row.supplierProductCode" /></template>
        </el-table-column>
        <el-table-column label="重复检查" width="170" fixed="right">
          <template #default="{ row }">
            <el-tag v-if="row.error === '已创建'" type="success">已创建</el-tag>
            <el-tag v-else-if="row.error && row.productId" type="warning">商品头已建，供货待重试</el-tag>
            <el-tag v-else-if="row.error" type="danger">{{ row.error }}</el-tag>
            <el-tag v-else-if="row.result === 'NONE'" type="success">可创建</el-tag>
            <el-tag v-else-if="row.result === 'EXACT'" type="info">已有相同商品</el-tag>
            <el-tag v-else-if="row.result === 'SKU_CONFLICT'" type="danger">系统编号冲突</el-tag>
            <el-tag v-else-if="row.result === 'INCOMPLETE'" type="warning">必填规格待补</el-tag>
            <span v-else class="muted">待检查</span>
          </template>
        </el-table-column>
        <el-table-column label="处理" width="130" fixed="right">
          <template #default="{ row, $index }">
            <el-select v-model="row.action" :disabled="checking || saving">
              <el-option label="创建" value="CREATE" :disabled="row.result !== 'NONE'" />
              <el-option label="跳过" value="SKIP" />
            </el-select>
            <el-button text type="danger" @click="removeRow($index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!matrixRows.length" class="matrix-empty"><el-empty description="选择产品系列后新增行，或批量粘贴规格" :image-size="80" /></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import PageHeader from '@/components/PageHeader.vue'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import { useAppStore } from '@/stores/app'
import { generateProductName } from '@/utils/productNaming'
import {
  api,
  type Product,
  type ProductAllowedUnit,
  type ProductDuplicateMatchType,
  type ProductReferenceType,
  type ProductSeries,
  type SeriesField,
  type SupplierProductOfferSave,
} from '@/api/client'

type MatrixAction = 'CREATE' | 'SKIP'
type MatrixValue = string | number | undefined
type MatrixRow = {
  key: number
  values: Record<number, MatrixValue>
  sku: string
  name: string
  supplierProductCode: string
  action: MatrixAction
  result?: ProductDuplicateMatchType
  error?: string
  productId?: number
}
type CartesianDimension = {
  field: SeriesField
  values: MatrixValue[]
}
type CartesianPlan = {
  count: number
  dimensions: CartesianDimension[]
  error: string
}

const MAX_MATRIX_ROWS = 200
const CARTESIAN_PREVIEW_ROWS = 6
const DUPLICATE_CHECK_CONCURRENCY = 4

const queryClient = useQueryClient()
const appStore = useAppStore()
const canManageMatrix = computed(() =>
  appStore.hasPermission('product.manage')
  && appStore.hasPermission('product.master.complex.manage'),
)
const canManageSupplierOffers = computed(() => appStore.hasPermission('product.supplier.offer.manage'))
const { data: series } = useQuery({ queryKey: ['product-series'], queryFn: api.productSeries })
const { data: referenceValues } = useQuery({
  queryKey: ['product-reference-values', 'enabled'],
  queryFn: () => api.productReferenceValues({ status: 'ENABLED' }),
})
const seriesId = ref<number>()
const seriesFields = ref<SeriesField[]>([])
const seriesAllowedUnits = ref<ProductAllowedUnit[]>([])
const rowFieldIds = ref<number[]>([])
const commonValues = reactive<Record<number, MatrixValue>>({})
const dimensionValues = reactive<Record<number, MatrixValue[]>>({})
const matrixRows = ref<MatrixRow[]>([])
const pasteVisible = ref(false)
const pasteText = ref('')
const checking = ref(false)
const saving = ref(false)
let rowKey = 0

const supplierForm = reactive<SupplierProductOfferSave>({
  supplierId: undefined,
  supplierProductCode: '',
  supplierModel: '',
  purchaseUnit: 'kg',
  priceUnit: 'kg',
  quotedUnitPrice: null,
  currencyCode: 'CNY',
  priceIncludesTax: true,
  taxRate: 13,
  minimumOrderQuantity: null,
  orderMultiple: null,
  leadTimeDays: null,
  effectiveFrom: todayString(),
  effectiveTo: null,
  packagingVariantRevisionId: null,
  sourceDocumentRevisionId: null,
  commercialTermsJson: '{}',
  status: 'ENABLED',
  changeReason: '矩阵建档同时建立供货关系',
  expectedRevisionNo: 0,
})

const selectedSeries = computed<ProductSeries | undefined>(() => (series.value ?? []).find((item) => item.id === seriesId.value))
const identityFieldIds = computed(() => new Set(
  seriesFields.value.filter((field) => field.skuDimension).map((field) => field.id),
))
const identityFields = computed(() => seriesFields.value.filter((field) => identityFieldIds.value.has(field.id)))
const rowFields = computed(() => seriesFields.value.filter((field) => rowFieldIds.value.includes(field.id)))
const commonFields = computed(() => seriesFields.value.filter((field) => !rowFieldIds.value.includes(field.id)))
const inventoryUnit = computed(() => allowedUnitOptions('INVENTORY')[0] || '')
const purchaseUnitOptions = computed(() => allowedUnitOptions('PURCHASE', supplierForm.purchaseUnit))
const priceUnitOptions = computed(() => allowedUnitOptions('PRICE', supplierForm.priceUnit))
const pasteHint = computed(() => rowFields.value.length
  ? `当前逐行字段顺序：${rowFields.value.map((field) => field.displayName).join(' → ')}`
  : '请先勾选至少一个逐行变化字段')
const pastePlaceholder = computed(() => rowFields.value.map((field) => field.displayName).join('\t') || '外径\t壁厚\t长度')
const cartesianPlan = computed<CartesianPlan>(() => {
  if (!rowFields.value.length) {
    return { count: 0, dimensions: [], error: '请先选择逐行变化字段' }
  }
  const dimensions = rowFields.value.map((field) => ({
    field,
    values: uniqueDimensionValues(field, dimensionValues[field.id] ?? []),
  }))
  const missing = dimensions.filter((item) => !item.values.length).map((item) => item.field.displayName)
  if (missing.length) {
    return {
      count: 0,
      dimensions,
      error: `请为${missing.join('、')}至少选择一个规格值`,
    }
  }
  let count = 1
  for (const dimension of dimensions) {
    if (count > Math.floor(MAX_MATRIX_ROWS / dimension.values.length)) {
      return {
        count: MAX_MATRIX_ROWS + 1,
        dimensions,
        error: `组合数量超过 ${MAX_MATRIX_ROWS} 行，请减少某些维度值后再生成`,
      }
    }
    count *= dimension.values.length
  }
  return { count, dimensions, error: '' }
})
const cartesianPreviewLabels = computed(() => {
  if (cartesianPlan.value.error || !cartesianPlan.value.count) return []
  return buildCartesianValueMaps(
    cartesianPlan.value.dimensions,
    Math.min(cartesianPlan.value.count, CARTESIAN_PREVIEW_ROWS),
  ).map((values) => rowValueSummary(values))
})
const cartesianPlanTitle = computed(() => {
  if (cartesianPlan.value.error) return cartesianPlan.value.error
  return `将生成 ${cartesianPlan.value.count} 个独立商品`
})
const cartesianPlanDescription = computed(() => {
  if (cartesianPlan.value.count > MAX_MATRIX_ROWS) {
    return `系统已在构造明细前阻断超量组合；单次上限为 ${MAX_MATRIX_ROWS} 行。`
  }
  if (cartesianPlan.value.error) return '补齐每个逐行维度的候选值后，系统才会显示组合预览和生成按钮。'
  const commonCount = commonFields.value.filter((field) => commonValues[field.id] !== undefined).length
  return `已填写 ${commonCount} 个公共字段；${cartesianPlan.value.dimensions
    .map((item) => `${item.field.displayName} ${item.values.length} 个值`)
    .join(' × ')}。请先核对预览，再生成明细并执行重复检查。`
})
const canGenerateCartesian = computed(() => Boolean(
  selectedSeries.value
  && cartesianPlan.value.count > 0
  && cartesianPlan.value.count <= MAX_MATRIX_ROWS
  && !cartesianPlan.value.error,
))
const creatableRows = computed(() => matrixRows.value.filter((row) =>
  row.action === 'CREATE' && row.result === 'NONE' && (!row.error || Boolean(row.productId))))
const canSave = computed(() => Boolean(
  selectedSeries.value
  && canManageMatrix.value
  && identityFields.value.length
  && inventoryUnit.value
  && (!canManageSupplierOffers.value || !supplierForm.supplierId || (
    supplierForm.purchaseUnit
    && supplierForm.priceUnit
    && (supplierForm.quotedUnitPrice == null || Number(supplierForm.quotedUnitPrice) > 0)
  ))
  && creatableRows.value.length
  && !saving.value
  && !checking.value,
))

async function loadSeries(value: number) {
  seriesFields.value = []
  seriesAllowedUnits.value = []
  rowFieldIds.value = []
  matrixRows.value = []
  Object.keys(commonValues).forEach((key) => delete commonValues[Number(key)])
  Object.keys(dimensionValues).forEach((key) => delete dimensionValues[Number(key)])
  if (!value) return
  const [fieldRows, allowedUnits] = await Promise.all([
    api.seriesFields(value),
    api.productSeriesAllowedUnits(value).catch(() => []),
  ])
  const fields = fieldRows.filter((field) => field.status === 'ENABLED')
  seriesFields.value = fields
  seriesAllowedUnits.value = allowedUnits
  const currentIdentityFieldIds = new Set(fields.filter((field) => field.skuDimension).map((field) => field.id))
  rowFieldIds.value = fields
    .filter((field) => currentIdentityFieldIds.has(field.id) && (field.fieldType === 'NUMBER' || sizeLikeField(field)))
    .map((field) => field.id)
  if (!rowFieldIds.value.length) {
    rowFieldIds.value = fields.filter((field) => currentIdentityFieldIds.has(field.id)).slice(-2).map((field) => field.id)
  }
  applyDefaultUnits()
  addRow()
}

function applyDefaultUnits() {
  supplierForm.purchaseUnit = defaultAllowedUnit('PURCHASE')
  supplierForm.priceUnit = defaultAllowedUnit('PRICE')
}

function addRow() {
  if (matrixRows.value.length >= MAX_MATRIX_ROWS) {
    ElMessage.warning(`单次矩阵最多保留 ${MAX_MATRIX_ROWS} 行，请先保存或删除部分明细`)
    return
  }
  matrixRows.value.push(createMatrixRow())
}

function createMatrixRow(values: Record<number, MatrixValue> = {}): MatrixRow {
  const row: MatrixRow = {
    key: ++rowKey,
    values: { ...values },
    sku: '',
    name: '',
    supplierProductCode: '',
    action: 'CREATE',
  }
  if (Object.keys(values).length) generateRowText(row)
  return row
}

function removeRow(index: number) {
  matrixRows.value.splice(index, 1)
}

function setRowValue(row: MatrixRow, fieldId: number, value: unknown) {
  setValue(row.values, fieldId, value)
  generateRowText(row)
  invalidateRow(row)
}

function setCommonValue(fieldId: number, value: unknown) {
  setValue(commonValues, fieldId, value)
  matrixRows.value.forEach((row) => {
    generateRowText(row)
    invalidateRow(row)
  })
}

function setValue(target: Record<number, MatrixValue>, fieldId: number, value: unknown) {
  if (value === '' || value === null || value === undefined) delete target[fieldId]
  else target[fieldId] = value as string | number
}

function setDimensionValues(field: SeriesField, input: unknown) {
  const source = Array.isArray(input) ? input : []
  if (source.length > MAX_MATRIX_ROWS) {
    ElMessage.warning(`单个规格维度最多选择 ${MAX_MATRIX_ROWS} 个值`)
    return
  }
  const values: MatrixValue[] = []
  const rejected: string[] = []
  const controlledValues = new Set(dimensionControlledValues(field)
    .map((value) => normalizeDimensionValue(field, value))
    .filter((value): value is string | number => value !== undefined)
    .map((value) => dimensionValueKey(field, value)))
  for (const raw of source) {
    const normalized = normalizeDimensionValue(field, raw)
    if (normalized === undefined
      || (controlledValues.size && !controlledValues.has(dimensionValueKey(field, normalized)))) {
      rejected.push(String(raw ?? ''))
      continue
    }
    values.push(normalized)
  }
  dimensionValues[field.id] = uniqueDimensionValues(field, values)
  if (rejected.length) {
    ElMessage.warning(`${field.displayName}已忽略无效值：${rejected.slice(0, 3).join('、')}${rejected.length > 3 ? '等' : ''}`)
  }
}

watch(rowFieldIds, (ids) => {
  const activeIds = new Set(ids)
  Object.keys(dimensionValues).forEach((key) => {
    if (!activeIds.has(Number(key))) delete dimensionValues[Number(key)]
  })
  ids.forEach((fieldId) => {
    if (!dimensionValues[fieldId]) dimensionValues[fieldId] = []
  })
  matrixRows.value.forEach((row) => {
    generateRowText(row)
    invalidateRow(row)
  })
})

async function generateCartesianRows() {
  const plan = cartesianPlan.value
  if (plan.error || plan.count <= 0 || plan.count > MAX_MATRIX_ROWS) {
    ElMessage.warning(plan.error || `单次矩阵最多生成 ${MAX_MATRIX_ROWS} 行`)
    return
  }
  const values = buildCartesianValueMaps(plan.dimensions, plan.count)
  const generated = values.map((item) => createMatrixRow(item))
  if (await replaceMatrixRows(generated, '规格组合生成')) {
    ElMessage.success(`已按预览生成 ${generated.length} 行；请执行重复检查后再创建商品`)
  }
}

async function applyPaste() {
  const fields = rowFields.value
  if (!fields.length) return
  const rows = pasteText.value.split(/\r?\n/).map((line) => line.trim()).filter(Boolean)
  if (rows.length > MAX_MATRIX_ROWS) {
    ElMessage.warning(`粘贴内容有 ${rows.length} 行，超过单次 ${MAX_MATRIX_ROWS} 行上限；请拆分后再处理`)
    return
  }
  const overflowIndex = rows.findIndex((line) =>
    line.split(/\t|,|，|×/).slice(fields.length).some((item) => item.trim()))
  if (overflowIndex >= 0) {
    ElMessage.warning(`粘贴第 ${overflowIndex + 1} 行的值多于当前 ${fields.length} 个逐行字段，请核对字段顺序`)
    return
  }
  const generated = rows.map((line) => {
    const parts = line.split(/\t|,|，|×/).map((item) => item.trim())
    const values: Record<number, MatrixValue> = {}
    fields.forEach((field, index) => {
      if (!parts[index]) return
      const numeric = field.fieldType === 'NUMBER' ? Number(parts[index]) : Number.NaN
      values[field.id] = Number.isFinite(numeric) ? numeric : parts[index]
    })
    return createMatrixRow(values)
  })
  if (await replaceMatrixRows(generated, '批量粘贴')) {
    pasteText.value = ''
    pasteVisible.value = false
    ElMessage.success(`已从粘贴内容生成 ${generated.length} 行；原内容已按确认结果替换`)
  }
}

async function replaceMatrixRows(generated: MatrixRow[], sourceLabel: string) {
  if (generated.length > MAX_MATRIX_ROWS) {
    ElMessage.warning(`${sourceLabel}结果超过单次 ${MAX_MATRIX_ROWS} 行上限，已停止生成`)
    return false
  }
  if (hasMeaningfulMatrixRows()) {
    const confirmed = await ElMessageBox.confirm(
      `当前已有 ${matrixRows.value.length} 行录入内容。${sourceLabel}会完整替换下方明细、名称修改和重复检查结果，不能合并保留。`,
      '替换现有矩阵明细',
      {
        type: 'warning',
        confirmButtonText: `确认替换为 ${generated.length} 行`,
        cancelButtonText: '保留现有明细',
      },
    ).then(() => true).catch(() => false)
    if (!confirmed) return false
  }
  matrixRows.value = generated
  return true
}

function hasMeaningfulMatrixRows() {
  return matrixRows.value.some((row) =>
    Object.values(row.values).some((value) => value !== undefined && String(value).trim() !== '')
    || Boolean(row.name.trim())
    || Boolean(row.supplierProductCode.trim())
    || Boolean(row.result)
    || Boolean(row.error)
    || Boolean(row.productId))
}

function generateRowText(row: MatrixRow) {
  if (!selectedSeries.value) return
  const values = Object.fromEntries(seriesFields.value.map((field) => [
    field.id,
    row.values[field.id] ?? commonValues[field.id],
  ]))
  row.name = generateProductName(selectedSeries.value, seriesFields.value, values)
}

function invalidateRow(row: MatrixRow) {
  row.result = undefined
  row.error = undefined
}

async function previewRows() {
  if (!selectedSeries.value) return
  if (matrixRows.value.length > MAX_MATRIX_ROWS) {
    ElMessage.warning(`单次最多检查 ${MAX_MATRIX_ROWS} 行，请拆分矩阵后再处理`)
    return
  }
  checking.value = true
  try {
    await runWithConcurrency(
      [...matrixRows.value],
      DUPLICATE_CHECK_CONCURRENCY,
      checkRowDuplicate,
    )
    const available = creatableRows.value.length
    ElMessage.success(`检查完成：${available} 行可创建，${matrixRows.value.length - available} 行需修正或跳过`)
  } finally {
    checking.value = false
  }
}

async function checkRowDuplicate(row: MatrixRow) {
  if (row.productId) {
    row.result = 'NONE'
    row.action = 'CREATE'
    row.error = '商品头已创建，仅待重试供应商供货关系'
    return
  }
  const missing = missingField(row)
  if (missing) {
    row.error = `缺少${missing.displayName}`
    row.result = undefined
    row.action = 'SKIP'
    return
  }
  if (!row.name) generateRowText(row)
  const fingerprint = rowFingerprint(row)
  try {
    const duplicate = await api.checkProductDuplicate(productPayload(row))
    if (fingerprint !== rowFingerprint(row)) {
      row.result = undefined
      row.error = '规格在检查期间已修改，请重新执行重复检查'
      row.action = 'SKIP'
      return
    }
    row.result = duplicate.result
    row.error = duplicate.result === 'NONE' ? undefined : duplicate.warnings.join('；')
    row.action = duplicate.result === 'NONE' ? 'CREATE' : 'SKIP'
  } catch {
    if (fingerprint !== rowFingerprint(row)) {
      row.result = undefined
      row.error = '规格在检查期间已修改，请重新执行重复检查'
      row.action = 'SKIP'
      return
    }
    row.result = undefined
    row.error = '重复检查失败，请重试'
    row.action = 'SKIP'
  }
}

async function runWithConcurrency<T>(
  items: readonly T[],
  concurrency: number,
  task: (item: T) => Promise<void>,
) {
  let nextIndex = 0
  const workerCount = Math.min(Math.max(1, concurrency), items.length)
  const workers = Array.from({ length: workerCount }, async () => {
    while (nextIndex < items.length) {
      const index = nextIndex
      nextIndex += 1
      const item = items[index]
      if (item !== undefined) await task(item)
    }
  })
  await Promise.all(workers)
}

function rowFingerprint(row: MatrixRow) {
  return JSON.stringify(productPayload(row))
}

async function saveRows() {
  if (!canSave.value) return
  const plannedRows = [...creatableRows.value]
  const plannedCount = plannedRows.length
  saving.value = true
  let saved = 0
  try {
    for (const row of plannedRows) {
      try {
        if (!row.productId) {
          const result = await api.createProduct(productPayload(row))
          row.productId = result.id
        }
        const productId = row.productId
        if (!productId) throw new Error('商品头创建结果缺少商品编号')
        if (canManageSupplierOffers.value && supplierForm.supplierId) {
          await api.createSupplierProductOffer(productId, {
            ...supplierForm,
            supplierProductCode: row.supplierProductCode,
          })
        }
        row.action = 'SKIP'
        row.error = '已创建'
        saved += 1
      } catch (error) {
        row.error = row.productId
          ? `商品头 #${row.productId} 已创建，供货关系未保存：${errorMessage(error)}。修正后可直接重试，不会重复建商品。`
          : errorMessage(error)
      }
    }
    await queryClient.invalidateQueries({ queryKey: ['products'] })
    if (saved) ElMessage.success(`已创建 ${saved} 个商品，每个商品均保留独立规格和包装继承关系`)
    if (saved < plannedCount) ElMessage.warning('部分行保存失败，已成功的行不会重复创建，请按逐行结果继续处理')
  } finally {
    saving.value = false
  }
}

function productPayload(row: MatrixRow): Partial<Product> {
  const attributes: Record<string, string | number> = {}
  const legacyReferences: Partial<Pick<Product, 'grade' | 'temper' | 'standard'>> = {}
  const fieldValues = seriesFields.value.map((field) => {
    const value = row.values[field.id] ?? commonValues[field.id] ?? null
    if (value !== null) {
      attributes[field.fieldName] = value
      const type = referenceTypeForField(field)
      if (type === 'GRADE') legacyReferences.grade = String(value)
      if (type === 'TEMPER') legacyReferences.temper = String(value)
      if (type === 'STANDARD') legacyReferences.standard = String(value)
    }
    return { seriesFieldId: field.id, value }
  })
  return {
    categoryId: selectedSeries.value?.categoryId,
    productSeriesId: selectedSeries.value?.id,
    sku: row.sku,
    name: row.name,
    attributeJson: JSON.stringify(attributes),
    barcode: '',
    originalNumber: '',
    unit: inventoryUnit.value,
    defaultPrice: 0,
    status: 'ENABLED',
    identityStatus: 'PUBLISHED',
    fieldValues,
    ...legacyReferences,
  }
}

function isIdentityField(field: SeriesField) {
  return identityFieldIds.value.has(field.id)
}

function missingField(row: MatrixRow) {
  return seriesFields.value.find((field) => field.required && (row.values[field.id] ?? commonValues[field.id]) === undefined)
}

function normalizeDimensionValue(field: SeriesField, raw: unknown): MatrixValue {
  if (raw === null || raw === undefined) return undefined
  const text = String(raw).trim()
  if (!text) return undefined
  if (field.fieldType !== 'NUMBER') return text
  const number = Number(text)
  if (!Number.isFinite(number)) return undefined
  if (field.minValue != null && number < Number(field.minValue)) return undefined
  if (field.maxValue != null && number > Number(field.maxValue)) return undefined
  const scale = Math.max(0, Number(field.decimalScale ?? 0))
  const mantissa = text.toLowerCase().split('e')[0]
  const fraction = (mantissa.split('.')[1] ?? '').replace(/0+$/, '')
  if (fraction.length > scale) return undefined
  return number
}

function uniqueDimensionValues(field: SeriesField, values: readonly MatrixValue[]) {
  const unique = new Map<string, MatrixValue>()
  for (const raw of values) {
    const value = normalizeDimensionValue(field, raw)
    if (value === undefined) continue
    const key = dimensionValueKey(field, value)
    if (!unique.has(key)) unique.set(key, value)
  }
  return [...unique.values()]
}

function dimensionValueKey(field: SeriesField, value: string | number) {
  return field.fieldType === 'NUMBER'
    ? `N:${value}`
    : `S:${String(value).trim().toLocaleUpperCase('zh-CN')}`
}

function dimensionControlledValues(field: SeriesField) {
  const reference = referenceOptions(field).map((item) => item.code)
  return reference.length ? reference : fieldOptions(field)
}

function buildCartesianValueMaps(dimensions: readonly CartesianDimension[], limit: number) {
  const rows: Array<Record<number, MatrixValue>> = []
  const current: Record<number, MatrixValue> = {}
  function visit(index: number) {
    if (rows.length >= limit) return
    if (index >= dimensions.length) {
      rows.push({ ...current })
      return
    }
    const dimension = dimensions[index]
    for (const value of dimension.values) {
      current[dimension.field.id] = value
      visit(index + 1)
      if (rows.length >= limit) break
    }
    delete current[dimension.field.id]
  }
  visit(0)
  return rows
}

function rowValueSummary(values: Record<number, MatrixValue>) {
  return rowFields.value.map((field) => {
    const value = values[field.id]
    return `${field.displayName} ${value ?? '-'}${field.unitName || ''}`
  }).join(' × ')
}

function fieldOptions(field: SeriesField) {
  try {
    const parsed = JSON.parse(field.optionJson || '[]')
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch {
    return []
  }
}

function referenceTypeForField(field: SeriesField): ProductReferenceType | undefined {
  const names = [field.fieldName, field.displayName].map((item) => String(item || '').replace(/\s/g, ''))
  if (names.some((name) => ['牌号', '材质牌号', '磁材牌号'].includes(name))) return 'GRADE'
  if (names.some((name) => ['材料状态', '状态', '软硬状态'].includes(name))) return 'TEMPER'
  if (names.some((name) => ['执行标准', '标准'].includes(name))) return 'STANDARD'
  return undefined
}

function referenceOptions(field: SeriesField) {
  const type = referenceTypeForField(field)
  return type ? (referenceValues.value ?? []).filter((item) => item.referenceType === type && item.status === 'ENABLED') : []
}

function allowedUnitOptions(purpose: ProductAllowedUnit['purpose'], current?: string | null) {
  const values = seriesAllowedUnits.value
    .filter((item) => item.status === 'ENABLED' && item.purpose === purpose)
    .map((item) => item.abbreviation || item.unitName)
  return [...new Set([current, ...values].map((item) => String(item || '').trim()).filter(Boolean))]
}

function defaultAllowedUnit(purpose: ProductAllowedUnit['purpose']) {
  const rows = seriesAllowedUnits.value.filter((item) => item.status === 'ENABLED' && item.purpose === purpose)
  const selected = rows.find((item) => item.defaultUnit) ?? rows[0]
  return selected?.abbreviation || selected?.unitName || ''
}

function sizeLikeField(field: SeriesField) {
  return /外径|内径|壁厚|厚度|宽度|长度|直径|对边|截面|口径|线规|尺寸/.test(`${field.fieldName}${field.displayName}`)
}

function numericValue(value: MatrixValue) {
  const number = Number(value)
  return Number.isFinite(number) ? number : undefined
}

function textValue(value: MatrixValue) {
  return value == null ? '' : String(value)
}

function errorMessage(error: unknown) {
  return (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    || (error instanceof Error ? error.message : '保存失败')
}

function todayString() {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date())
}
</script>

<style scoped>
.product-matrix-page {
  display: grid;
  gap: 14px;
}

.product-matrix-page :deep(.el-select) {
  width: 100%;
}

.identity-alert {
  margin-bottom: 16px;
}

.identity-alert :deep(.el-alert__description) {
  margin-top: 4px;
}

.structured-matrix-action {
  margin: -6px 0 16px;
  text-align: right;
}

.cartesian-builder {
  display: grid;
  gap: 12px;
  margin: 8px 0 16px;
  padding: 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
}

.cartesian-builder__header,
.cartesian-builder__actions {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
}

.cartesian-builder__title {
  margin-bottom: 3px;
  font-weight: 600;
}

.dimension-help {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.4;
}

.cartesian-preview {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
}

.supplier-common-collapse {
  margin-top: 8px;
}

.matrix-paste {
  display: grid;
  gap: 10px;
  padding: 14px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-lighter);
}

.matrix-paste-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.matrix-empty {
  padding: 18px;
}

.el-checkbox .el-tag {
  margin-left: 4px;
}

@media (max-width: 900px) {
  .cartesian-builder__header,
  .cartesian-builder__actions,
  .matrix-paste-actions {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
