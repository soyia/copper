<template>
  <div class="page">
    <div class="panel inventory-form-panel">
      <PageHeader :title="formTitle" :back-to="backTo" />
      <el-alert :title="guidance" type="info" :closable="false" show-icon class="inventory-form-alert" />
      <el-form label-position="top" class="inventory-document-form">
        <div class="form-grid">
          <el-form-item label="业务日期" required><el-date-picker v-model="form.businessDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <template v-if="kind === 'transfer'">
            <el-form-item label="调出仓" required><el-select v-model="form.fromWarehouseId" filterable @change="warehouseChanged"><el-option v-for="w in enabledWarehouses" :key="w.id" :label="w.name" :value="w.id" /></el-select></el-form-item>
            <el-form-item label="调入仓" required><el-select v-model="form.toWarehouseId" filterable><el-option v-for="w in enabledWarehouses" :key="w.id" :label="w.name" :value="w.id" :disabled="w.id === form.fromWarehouseId" /></el-select></el-form-item>
            <el-form-item label="预计到达" required><el-date-picker v-model="form.expectedArrivalDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          </template>
          <el-form-item v-else-if="kind === 'stocktake'" label="盘点仓库" required><el-select v-model="form.warehouseId" filterable @change="warehouseChanged"><el-option v-for="w in enabledWarehouses" :key="w.id" :label="w.name" :value="w.id" /></el-select></el-form-item>
          <el-form-item v-else-if="kind === 'purchase-return'" label="原入库单" required class="form-span-2">
            <el-select v-model="form.inboundId" filterable @change="inboundChanged">
              <el-option v-for="item in stockedInbounds" :key="item.id" :label="`${item.orderNo} · ${item.supplierName} · ${item.warehouseName}`" :value="item.id" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item v-if="kind === 'purchase-return' || kind === 'cost-adjustment'" label="业务原因" required>
          <el-input v-model="form.reason" maxlength="500" show-word-limit :placeholder="kind === 'purchase-return' ? '例如：质量不符、规格错误、供应商召回' : '说明成本变化的原始依据和调整边界'" />
        </el-form-item>

        <div class="inventory-line-header">
          <div>
            <div class="panel-title">{{ lineTitle }}</div>
            <div class="muted">{{ lineGuidance }}</div>
          </div>
          <el-button type="primary" plain @click="addLine">添加明细</el-button>
        </div>
        <el-table :data="form.items" border size="small" class="inventory-lines">
          <el-table-column type="index" width="50" label="#" />
          <el-table-column min-width="310">
            <template #header>
              {{ kind === 'purchase-return' ? '原入库商品 / 批次' : '库存批次' }}
              <span class="table-required-mark">*</span>
            </template>
            <template #default="{ row, $index }">
              <el-select v-if="kind !== 'purchase-return'" v-model="row.batchId" filterable placeholder="选择商品批次" @change="batchChanged($index)">
                <el-option v-for="batch in selectableBatches" :key="batch.id" :value="batch.id" :label="batchLabel(batch)" />
              </el-select>
              <el-select v-else v-model="row.inboundItemId" filterable placeholder="选择原入库明细" @change="inboundItemChanged($index)">
                <el-option v-for="item in inboundDetail?.items || []" :key="item.id" :value="item.id" :label="`${item.sku} · ${item.productName} · ${item.spec || '-'} · 批次 ${item.batchNo}`" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column v-if="kind === 'stocktake'" label="账面结存" width="145" align="right"><template #default="{ row }">{{ quantity(batchOf(row)?.quantityAvailable || 0) }} {{ batchOf(row)?.baseUnit || '-' }}</template></el-table-column>
          <el-table-column v-if="kind === 'transfer' || kind === 'purchase-return'" width="190">
            <template #header>数量 <span class="table-required-mark">*</span></template>
            <template #default="{ row }"><el-input-number v-model="row.quantity" :min="0" :precision="6" :step="0.000001" :controls="false" /><span class="line-unit">{{ batchOf(row)?.baseUnit || '' }}</span></template>
          </el-table-column>
          <el-table-column v-if="kind === 'stocktake'" width="190">
            <template #header>实盘数量 <span class="table-required-mark">*</span></template>
            <template #default="{ row }"><el-input-number v-model="row.countedQuantity" :min="0" :precision="6" :step="0.000001" :controls="false" /><span class="line-unit">{{ batchOf(row)?.baseUnit || '' }}</span></template>
          </el-table-column>
          <el-table-column v-if="kind === 'stocktake'" label="差异原因" min-width="220"><template #default="{ row }"><el-input v-model="row.differenceReason" maxlength="500" placeholder="有差异时必填" /></template></el-table-column>
          <template v-if="kind === 'cost-adjustment'">
            <el-table-column label="当前成本" width="135" align="right"><template #default="{ row }">{{ money(batchOf(row)?.unitCost || 0, 4) }}</template></el-table-column>
            <el-table-column width="200">
              <template #header>新成本单价 <span class="table-required-mark">*</span></template>
              <template #default="{ row }"><el-input-number v-model="row.newUnitCost" :min="0" :precision="4" :step="0.01" :controls="false" /></template>
            </el-table-column>
          </template>
          <el-table-column v-if="kind === 'transfer' || kind === 'purchase-return'" label="逐件实重包装" min-width="230">
            <template #default="{ row, $index }">
              <el-select v-if="batchOf(row)?.packagingTrackingMode === 'ACTUAL_INSTANCE'" v-model="row.packageInstanceIds" multiple collapse-tags placeholder="选择包装码" @visible-change="(open:boolean) => open && loadPackages($index)">
                <el-option v-for="item in packageOptions[$index] || []" :key="item.id" :value="item.id" :label="`${item.packageCode} · 剩余 ${quantity(item.remainingBaseQuantity ?? item.actualBaseQuantity)}${item.baseUnit}`" />
              </el-select>
              <span v-else class="muted">按包装余额自动扣减</span>
            </template>
          </el-table-column>
          <el-table-column width="70" fixed="right"><template #default="{ $index }"><el-button link type="danger" @click="removeLine($index)">删除</el-button></template></el-table-column>
        </el-table>
        <el-empty v-if="!form.items.length" description="请添加至少一条明细" :image-size="56" />

        <el-form-item label="备注" class="inventory-memo"><el-input v-model="form.memo" type="textarea" :rows="3" maxlength="500" show-word-limit /></el-form-item>
        <div class="form-actions">
          <el-button @click="router.push(backTo)">取消</el-button>
          <el-button type="primary" :loading="submitting" @click="submit">{{ isEdit ? '更新草稿' : '保存草稿' }}</el-button>
        </div>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, type InboundDetail, type InventoryBatch, type InventoryPackageInstance } from '@/api/client'
import { currentDateText, money, quantity } from '@/api/format'
import PageHeader from '@/components/PageHeader.vue'
import { useSessionDraft } from '@/composables/useSessionDraft'
import { useAppStore } from '@/stores/app'
import { workspaceRouteKey } from '@/stores/workspaceTabs'
import { inventoryDocumentConfig, type InventoryDocumentKind } from '@/utils/inventoryDocuments'

type Line = {
  batchId?: number
  inboundItemId?: number
  quantity: number
  countedQuantity: number
  newUnitCost: number
  differenceReason: string
  packageInstanceIds: number[]
}

const props = defineProps<{ kind: InventoryDocumentKind }>()
const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const kind = computed(() => props.kind)
const config = computed(() => inventoryDocumentConfig[props.kind])
const editId = computed(() => route.params.id ? Number(route.params.id) : 0)
const isEdit = computed(() => editId.value > 0)
const formTitle = computed(() => isEdit.value ? `编辑${config.value.title}草稿` : config.value.newTitle)
const backTo = computed(() => typeof route.query.from === 'string' ? route.query.from : config.value.listPath)
const submitting = ref(false)
const initialized = ref(false)
const inboundDetail = ref<InboundDetail>()
const packageOptions = reactive<Record<number, InventoryPackageInstance[]>>({})
const form = reactive({
  businessDate: currentDateText(),
  expectedArrivalDate: currentDateText(),
  fromWarehouseId: undefined as number | undefined,
  toWarehouseId: undefined as number | undefined,
  warehouseId: undefined as number | undefined,
  inboundId: undefined as number | undefined,
  reason: '',
  memo: '',
  items: [] as Line[],
})
const draftWorkspaceKey = workspaceRouteKey(route)
const draftStorageKey = computed(() => `copper-session-draft:v1:${appStore.session?.userId || 0}:inventory-${props.kind}:${editId.value || 'new'}`)

const { clearDraft } = useSessionDraft({
  storageKey: () => draftStorageKey.value,
  snapshot: () => JSON.parse(JSON.stringify(form)) as typeof form,
  restore: async (draft) => {
    Object.assign(form, draft)
    form.items = draft.items?.length
      ? draft.items.map((item) => Object.assign(emptyLine(), item))
      : [emptyLine()]
    if (props.kind === 'purchase-return' && form.inboundId) inboundDetail.value = await api.inboundDetail(form.inboundId)
  },
  ready: initialized,
  label: config.value.title,
  confirmRestore: isEdit.value,
  workspaceKey: draftWorkspaceKey,
})

const { data: warehouses } = useQuery({ queryKey: ['warehouses'], queryFn: api.warehouses })
const { data: batches } = useQuery({
  queryKey: ['inventory-batches-create', 'including-zero-stock'],
  queryFn: () => api.inventoryBatches({ availableOnly: false }),
})
const { data: inbounds } = useQuery({ queryKey: ['stocked-inbounds-return'], queryFn: () => api.inbounds() })
const enabledWarehouses = computed(() => (warehouses.value || []).filter((item) => item.status === 'ENABLED'))
const stockedInbounds = computed(() => (inbounds.value || []).filter((item) => item.status === 'STOCKED'))
const selectableBatches = computed(() => (batches.value || []).filter((batch) => {
  if (props.kind === 'transfer') return batch.warehouseId === form.fromWarehouseId && batch.quantityFree > 0
  if (props.kind === 'stocktake') return batch.warehouseId === form.warehouseId
  if (props.kind === 'cost-adjustment') return batch.quantityAvailable > 0 && batch.quantityInTransit <= 0
  return true
}))
const guidance = computed(() => ({
  transfer: '保存不改变库存；发出后从调出仓转为在途，调入仓确认收货后才增加实存。',
  stocktake: '保存时固化账面数量；确认前若批次发生出入库或成本变化，系统会拒绝过账并要求重新盘点。',
  'purchase-return': '退货必须关联已入库批次；确认后减少库存并冲减应付，已付款超出部分转入供应商预付权益。',
  'cost-adjustment': '采用批次实际成本法；成本调整只影响当前结存，不追溯重算历史出库。',
}[props.kind]))
const lineTitle = computed(() => ({ transfer: '调拨明细', stocktake: '盘点明细', 'purchase-return': '退货明细', 'cost-adjustment': '成本调整明细' }[props.kind]))
const lineGuidance = computed(() => ({
  transfer: '数量使用批次主库存单位；逐件实重批次必须选择具体包装实物。',
  stocktake: '按批次记录账面数、实盘数和差异原因；确认后形成盘盈或盘亏流水。',
  'purchase-return': '数量使用批次主库存单位；逐件实重批次必须选择具体包装实物。',
  'cost-adjustment': '按批次填写新成本单价；系统固化当前数量、原成本和调整金额。',
}[props.kind]))

function addLine() {
  form.items.push(emptyLine())
}

function emptyLine(): Line {
  return { quantity: 0, countedQuantity: 0, newUnitCost: 0, differenceReason: '', packageInstanceIds: [] }
}

function removeLine(index: number) {
  form.items.splice(index, 1)
  delete packageOptions[index]
}

function warehouseChanged() {
  form.items = []
}

async function inboundChanged() {
  form.items = []
  inboundDetail.value = form.inboundId ? await api.inboundDetail(form.inboundId) : undefined
}

function batchChanged(index: number) {
  const line = form.items[index]
  const batch = batchOf(line)
  if (!line || !batch) return
  line.packageInstanceIds = []
  if (props.kind === 'stocktake') line.countedQuantity = batch.quantityAvailable
  if (props.kind === 'cost-adjustment') line.newUnitCost = batch.unitCost
  delete packageOptions[index]
}

function inboundItemChanged(index: number) {
  const line = form.items[index]
  const item = inboundDetail.value?.items.find((candidate) => candidate.id === line?.inboundItemId)
  if (!line || !item) return
  line.batchId = item.inventoryBatchId
  line.quantity = 0
  line.packageInstanceIds = []
  delete packageOptions[index]
}

function batchOf(line: Line) {
  return (batches.value || []).find((batch) => batch.id === line.batchId)
}

function batchLabel(batch: InventoryBatch) {
  const stockLabel = props.kind === 'stocktake'
    ? `账面 ${quantity(batch.quantityAvailable)}${batch.baseUnit || ''}`
    : `可用 ${quantity(batch.quantityFree)}${batch.baseUnit || ''}`
  return `${batch.sku} · ${batch.productName} · ${batch.spec || '-'} · ${batch.warehouseName}/${batch.batchNo} · ${stockLabel}`
}

async function loadPackages(index: number) {
  const batchId = form.items[index]?.batchId
  if (!batchId || packageOptions[index]) return
  packageOptions[index] = await api.inventoryPackageInstances(batchId)
}

function validate() {
  if (!form.businessDate) return '请选择业务日期'
  if (!form.items.length) return '请添加至少一条明细'
  if (props.kind === 'transfer' && (!form.fromWarehouseId || !form.toWarehouseId || form.fromWarehouseId === form.toWarehouseId)) return '请选择不同的调出仓和调入仓'
  if (props.kind === 'transfer' && (!form.expectedArrivalDate || form.expectedArrivalDate < form.businessDate)) return '预计到达日期不能为空且不能早于业务日期'
  if (props.kind === 'stocktake' && !form.warehouseId) return '请选择盘点仓库'
  if (props.kind === 'purchase-return' && (!form.inboundId || !form.reason.trim())) return '请选择原入库单并填写退货原因'
  if (props.kind === 'cost-adjustment' && !form.reason.trim()) return '请填写成本调整原因'
  if (form.items.some((line) => !line.batchId)) return '每条明细都必须选择库存批次'
  if (new Set(form.items.map((line) => line.batchId)).size !== form.items.length) return '同一批次不能重复选择'
  if (props.kind === 'purchase-return' && new Set(form.items.map((line) => line.inboundItemId)).size !== form.items.length) return '同一原入库明细不能重复选择'
  if ((props.kind === 'transfer' || props.kind === 'purchase-return') && form.items.some((line) => line.quantity <= 0)) return '数量必须大于0'
  if ((props.kind === 'transfer' || props.kind === 'purchase-return') && form.items.some((line) => line.quantity > Number(batchOf(line)?.quantityFree || 0))) return '数量不能超过批次自由可用库存'
  if (props.kind === 'stocktake' && form.items.some((line) => line.countedQuantity < 0)) return '实盘数量不能为负数'
  if (props.kind === 'stocktake' && form.items.some((line) => line.countedQuantity !== (batchOf(line)?.quantityAvailable || 0) && !line.differenceReason.trim())) return '盘盈盘亏明细必须填写差异原因'
  if (props.kind === 'cost-adjustment' && form.items.some((line) => line.newUnitCost < 0)) return '新成本单价不能为负数'
  if ((props.kind === 'transfer' || props.kind === 'purchase-return') && form.items.some((line) => batchOf(line)?.packagingTrackingMode === 'ACTUAL_INSTANCE' && !line.packageInstanceIds.length)) return '逐件实重批次必须选择具体包装实物'
  return ''
}

async function submit() {
  const error = validate()
  if (error) return ElMessage.warning(error)
  submitting.value = true
  try {
    let result: { id: number }
    if (props.kind === 'transfer') {
      const payload = { businessDate: form.businessDate, expectedArrivalDate: form.expectedArrivalDate, fromWarehouseId: form.fromWarehouseId, toWarehouseId: form.toWarehouseId, memo: form.memo, items: form.items.map(({ batchId, quantity, packageInstanceIds }) => ({ batchId, quantity, packageInstanceIds })) }
      result = isEdit.value ? await api.updateInventoryTransfer(editId.value, payload) : await api.createInventoryTransfer(payload)
    } else if (props.kind === 'stocktake') {
      const payload = { businessDate: form.businessDate, warehouseId: form.warehouseId, memo: form.memo, items: form.items.map(({ batchId, countedQuantity, differenceReason }) => ({ batchId, countedQuantity, differenceReason })) }
      result = isEdit.value ? await api.updateInventoryStocktake(editId.value, payload) : await api.createInventoryStocktake(payload)
    } else if (props.kind === 'purchase-return') {
      const payload = { businessDate: form.businessDate, inboundId: form.inboundId, reason: form.reason, memo: form.memo, items: form.items.map(({ inboundItemId, batchId, quantity, packageInstanceIds }) => ({ inboundItemId, batchId, quantity, packageInstanceIds })) }
      result = isEdit.value ? await api.updatePurchaseReturn(editId.value, payload) : await api.createPurchaseReturn(payload)
    } else {
      const payload = { businessDate: form.businessDate, reason: form.reason, memo: form.memo, items: form.items.map(({ batchId, newUnitCost }) => ({ batchId, newUnitCost })) }
      result = isEdit.value ? await api.updateInventoryCostAdjustment(editId.value, payload) : await api.createInventoryCostAdjustment(payload)
    }
    clearDraft()
    ElMessage.success(isEdit.value ? '草稿已更新' : '草稿已保存')
    await router.replace({ path: `${config.value.listPath}/${result.id}`, query: { from: config.value.listPath } })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  if (!isEdit.value) {
    addLine()
    initialized.value = true
    return
  }
  try {
    if (props.kind === 'transfer') {
      const detail = await api.inventoryTransfer(editId.value)
      Object.assign(form, { businessDate: detail.header.businessDate, expectedArrivalDate: detail.header.expectedArrivalDate || detail.header.businessDate, fromWarehouseId: detail.header.fromWarehouseId,
        toWarehouseId: detail.header.toWarehouseId, memo: detail.header.memo,
        items: detail.items.map((item) => ({ batchId: item.sourceBatchId, quantity: item.quantity, countedQuantity: 0,
          newUnitCost: 0, differenceReason: '', packageInstanceIds: item.packageInstanceIds || [] })) })
    } else if (props.kind === 'stocktake') {
      const detail = await api.inventoryStocktake(editId.value)
      Object.assign(form, { businessDate: detail.header.businessDate, warehouseId: detail.header.warehouseId,
        memo: detail.header.memo, items: detail.items.map((item) => ({ batchId: item.batchId, quantity: 0,
          countedQuantity: item.countedQuantity, newUnitCost: 0, differenceReason: item.differenceReason,
          packageInstanceIds: [] })) })
    } else if (props.kind === 'purchase-return') {
      const detail = await api.purchaseReturn(editId.value)
      form.businessDate = detail.header.businessDate; form.inboundId = detail.header.inboundId
      form.reason = detail.header.reason; form.memo = detail.header.memo
      inboundDetail.value = await api.inboundDetail(detail.header.inboundId)
      form.items = detail.items.map((item) => ({ batchId: item.batchId, inboundItemId: item.inboundItemId,
        quantity: item.quantity, countedQuantity: 0, newUnitCost: 0, differenceReason: '',
        packageInstanceIds: item.packageInstanceIds || [] }))
    } else {
      const detail = await api.inventoryCostAdjustment(editId.value)
      Object.assign(form, { businessDate: detail.header.businessDate, reason: detail.header.reason, memo: detail.header.memo,
        items: detail.items.map((item) => ({ batchId: item.batchId, quantity: 0, countedQuantity: 0,
          newUnitCost: item.newUnitCost, differenceReason: '', packageInstanceIds: [] })) })
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '草稿加载失败')
  } finally {
    initialized.value = true
  }
})
</script>

<style scoped>
.inventory-form-panel { max-width: 1480px; margin: 0 auto; }
.inventory-form-alert { margin: 0 18px 18px; }
.inventory-document-form { padding: 0 18px 20px; }
.form-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0 16px; }
.form-span-2 { grid-column: span 2; }
.inventory-line-header { display: flex; align-items: center; justify-content: space-between; margin: 18px 0 10px; }
.inventory-lines :deep(.el-input-number) { width: 125px; }
.line-unit { margin-left: 7px; color: var(--el-text-color-secondary); }
.inventory-memo { margin-top: 20px; }
.form-actions { display: flex; justify-content: flex-end; gap: 10px; }
@media (max-width: 960px) { .form-grid { grid-template-columns: 1fr; } .form-span-2 { grid-column: auto; } }
</style>
