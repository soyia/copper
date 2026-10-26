<template>
  <div class="page">
    <div class="panel">
      <PageHeader :title="editId ? '编辑出库草稿' : salesOrderId ? '从销售订单出库' : '新建出库单'" :back-to="editId ? `/outbounds/${editId}` : salesOrderId ? `/sales-orders/${salesOrderId}` : '/outbounds'">
        <el-button :loading="saving" :disabled="saving" @click="submit(false)">保存草稿</el-button>
        <el-button type="primary" :loading="saving" :disabled="saving" @click="submit(true)">确认发货</el-button>
      </PageHeader>
      <div class="panel-body">
        <el-alert v-if="salesOrderDetail" :title="`按销售订单 ${salesOrderDetail.orderNo} 出库；可减少本次数量形成分批发货，同一商品跨库存批次请点击“增加批次”。`" type="info" :closable="false" show-icon style="margin-bottom:14px" />
        <el-form ref="formRef" class="entry-form" :model="form" :rules="rules" label-width="86px">
          <el-row :gutter="16">
            <el-col :span="8">
              <el-form-item label="销售订单">
                <el-select
                  v-model="form.salesOrderId"
                  clearable
                  filterable
                  :disabled="!!editId"
                  placeholder="可不选；选择后自动带入客户和待发商品"
                  @change="handleSalesOrderChange"
                >
                  <el-option
                    v-for="item in availableSalesOrders"
                    :key="item.id"
                    :label="`${item.orderNo} / ${item.customerName} / 订单金额 ${money(item.totalAmount)}`"
                    :value="item.id"
                  />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="客户" prop="customerId">
                <PartnerRemoteSelect v-model="form.customerId" type="CUSTOMER" :disabled="!!salesOrderId" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="仓库" prop="warehouseId">
                <el-select v-model="form.warehouseId">
                  <el-option v-for="item in warehouses ?? []" :key="item.id" :label="item.name" :value="item.id" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="收货地址">
                <el-select v-model="form.addressId" clearable filterable :disabled="!form.customerId" placeholder="可不选">
                  <el-option v-for="item in receivingAddresses" :key="item.id" :label="addressLabel(item)" :value="item.id" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="出库日期" prop="businessDate">
                <el-date-picker v-model="form.businessDate" value-format="YYYY-MM-DD" />
              </el-form-item>
            </el-col>
            <el-col :span="8"><el-form-item label="实际发货"><el-date-picker v-model="form.actualShippedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item></el-col>
            <el-col :span="8"><el-form-item label="接单来源"><el-select v-model="form.sourceChannel" :disabled="!!salesOrderId"><el-option label="销售订单" value="SALES_ORDER" /><el-option label="电话" value="PHONE" /><el-option label="微信" value="WECHAT" /><el-option label="现场" value="ON_SITE" /><el-option label="其他" value="OTHER" /></el-select></el-form-item></el-col>
            <el-col :span="8"><el-form-item label="录入方式"><el-select v-model="form.entryMode"><el-option label="正常录入" value="NORMAL" /><el-option label="快速发货" value="QUICK" /><el-option label="事后补录" value="BACKFILL" /></el-select></el-form-item></el-col>
            <el-col v-if="form.entryMode === 'BACKFILL'" :span="16"><el-form-item label="补录原因" required><el-input v-model="form.lateEntryReason" /></el-form-item></el-col>
            <el-col :span="8">
              <el-form-item label="信息完整度">
                <el-select v-model="form.toComplete">
                  <el-option label="信息已完整" :value="false" />
                  <el-option label="待补录单据信息" :value="true" />
                </el-select>
                <div class="form-help">指收货地址、联系人、价格依据、箱码或其他非发货必填信息，不是附件资料。</div>
              </el-form-item>
            </el-col>
            <el-col :span="8">
            <el-form-item label="已约定价格">
                <el-select v-model="form.priceAgreed" :disabled="!!salesOrderId" @change="handlePriceAgreedChange">
                  <el-option label="是，按约定单价结算" :value="true" />
                  <el-option label="否，发货后补最终价格" :value="false" />
                </el-select>
                <div v-if="salesOrderId" class="form-help">关联销售订单按已确认价格执行；变价请先走销售订单变更。</div>
              </el-form-item>
            </el-col>
            <el-col :span="8"><el-form-item label="出库折扣%"><el-input-number v-model="form.discountRate" :controls="false" :min="0" :max="100" :precision="2" /></el-form-item></el-col>
            <el-col :span="8"><el-form-item label="金额调整"><el-input-number v-model="form.adjustmentAmount" :controls="false" :precision="2" /></el-form-item></el-col>
            <el-col :span="8">
              <el-form-item label="发货前到账">
                <el-select v-model="form.shipmentPaymentPolicy" :disabled="!!salesOrderId">
                  <el-option label="不校验到账金额（可零/部分/全额）" value="NONE" />
                  <el-option label="本次发货控制金额须全额到账" value="SHIPMENT_PAID" />
                  <el-option v-if="salesOrderId" label="整张销售订单须全额到账" value="ORDER_PAID" />
                </el-select>
                <div class="form-help">
                  只控制能否发货，不表示已收款金额；实际到账仍以回款记录为准。已约定价格时按本次结算金额校验，未约定价格时按暂估最高金额校验。
                </div>
              </el-form-item>
            </el-col>
            <el-col v-if="!form.priceAgreed && form.shipmentPaymentPolicy !== 'NONE'" :span="8">
              <el-form-item label="暂估最高额" required>
                <el-input v-model="form.shipmentControlAmount" inputmode="decimal" placeholder="价格未确定时按此上限锁定资金" />
              </el-form-item>
            </el-col>
            <el-col v-if="salesOrderId" :span="8">
              <el-form-item label="允许超发">
                <el-switch v-model="form.allowOverShipment" :disabled="!canOverShip" />
                <div v-if="!canOverShip" class="form-help">仅老板或已取得“允许超订单发货”权限的人员可用</div>
              </el-form-item>
            </el-col>
            <el-col v-if="salesOrderId && form.allowOverShipment" :span="16"><el-form-item label="超发原因" required><el-input v-model="form.overShipmentReason" /></el-form-item></el-col>
            <el-col :span="24">
              <el-form-item label="备注">
                <el-input v-model="form.memo" />
              </el-form-item>
            </el-col>
          </el-row>
        </el-form>
      </div>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">出库商品明细</div>
        <el-button v-if="!salesOrderId" @click="addItem">增加明细</el-button>
      </div>
      <BarcodeScannerInput
        title="扫码拣货"
        placeholder="扫描商品包装条码，自动匹配当前仓库最早可用批次"
        :busy="scannerBusy"
        :disabled="!form.warehouseId"
        @scan="scanOutbound"
      />
      <div v-if="scannerMessage" class="outbound-scanner-message" :class="`is-${scannerMessage.type}`" role="status" aria-live="polite">{{ scannerMessage.text }}</div>
      <div class="order-total-bar">
        <template v-if="form.priceAgreed">
          <span>商品金额：{{ money(originalAmount) }}</span>
          <span>折扣金额：-{{ money(discountAmount) }}</span>
          <span>调整金额：{{ signedMoney(form.adjustmentAmount) }}</span>
          <span class="order-total-final">出库结算金额：{{ money(totalAmount) }}</span>
        </template>
        <template v-else>
          <span class="text-warning">最终价格待确定，当前不形成销售金额</span>
          <span v-if="form.shipmentPaymentPolicy !== 'NONE'" class="order-total-final">暂估最高控制金额：{{ money(Number(form.shipmentControlAmount || 0)) }}</span>
        </template>
      </div>
      <el-alert
        v-if="overShipmentLines.length"
        type="warning"
        :closable="false"
        show-icon
        style="margin: 0 12px 12px"
      >
        <template #title>
          <div>所选整包装超过销售订单剩余重量</div>
          <div v-for="line in overShipmentLines" :key="line" class="form-help">{{ line }}</div>
          <div class="form-help">
            系统将按所选包装的实际重量扣库存并计算金额；确需发货时，须勾选“允许超发”并填写原因。
          </div>
        </template>
      </el-alert>
      <el-table :data="form.items" class="data-table" size="small" border show-overflow-tooltip max-height="calc(100vh - 380px)">
        <el-table-column width="340" fixed="left">
          <template #header>库存批次 <span class="table-required-mark">*</span></template>
          <template #default="{ row }">
            <el-select v-model="row.batchId" filterable @change="handleBatchChange(row)">
              <el-option
                v-for="item in availableBatchesFor(row)"
                :key="item.id"
                :label="`${item.warehouseName} / ${item.productName} / ${item.batchNo} / 可用${quantity(item.quantityAvailable)}`"
                :value="item.id"
              />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column width="155">
          <template #header>出库数量 <span class="table-required-mark">*</span></template>
          <template #default="{ row }"><el-input-number v-model="row.quantity" :controls="false" :disabled="row.packageInstanceIds.length > 0" :min="0.000001" :precision="6" @change="refreshPackagingPlan(row)" /></template>
        </el-table-column>
        <el-table-column width="120">
          <template #header>数量单位 <span class="table-required-mark">*</span></template>
          <template #default="{ row }">
            <el-select v-model="row.quantityUnit" filterable :disabled="!row.batchId" @change="handleQuantityUnitChange(row)">
              <el-option v-for="item in packagingUnits(row.packagingProfile, 'OUTBOUND')" :key="item" :label="item" :value="item" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="实际库存数量" width="160">
          <template #default="{ row }">
            <div class="inventory-quantity-field">
              <span class="inventory-quantity-value">{{ quantity(row.packagingPlan?.baseQuantity) }}</span>
              <span class="inventory-quantity-unit">{{ row.packagingPlan?.baseUnit || row.packagingProfile?.baseUnit || '-' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column width="320">
          <template #header>具体包装 / 拆包来源 <span v-if="hasActualWeightLines" class="table-required-mark">*</span></template>
          <template #default="{ row }">
            <div class="package-selection-cell">
              <el-select
                v-model="row.packageInstanceIds"
                multiple
                clearable
                collapse-tags
                :disabled="!row.availablePackageInstances.length"
                placeholder="逐件实重须整包选择；定量包装按可拆规则换算"
                @change="handlePackageSelection(row)"
              >
                <el-option
                  v-for="item in row.availablePackageInstances"
                  :key="item.id"
                  :label="`${item.packageCode} / 剩余 ${quantity(item.remainingBaseQuantity ?? item.actualBaseQuantity)}${item.baseUnit}`"
                  :value="item.id"
                />
              </el-select>
              <el-button
                v-if="row.availablePackageInstances.length"
                link
                type="primary"
                @click="openOutboundLabelRecognition(row)"
              >
                拍照识别标签并选包装
              </el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column width="130">
          <template #header>包装数 <span v-if="hasActualWeightLines" class="table-required-mark">*</span></template>
          <template #default="{ row }"><el-input-number v-model="row.packageQuantity" :controls="false" :disabled="row.packageInstanceIds.length > 0" :min="0" :precision="6" /></template>
        </el-table-column>
        <el-table-column width="120">
          <template #header>包装单位 <span v-if="hasActualWeightLines" class="table-required-mark">*</span></template>
          <template #default="{ row }">
            <el-select v-model="row.packageUnit" clearable filterable @change="handlePackageUnitChange(row)">
              <el-option v-for="item in packageUnits(row.packagingProfile)" :key="item" :label="item" :value="item" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column width="120">
          <template #header>计价单位 <span class="table-required-mark">*</span></template>
          <template #default="{ row }">
            <el-select v-model="row.priceUnit" filterable @change="refreshPackagingPlan(row)">
              <el-option v-for="item in packagingUnits(row.packagingProfile, 'OUTBOUND')" :key="item" :label="item" :value="item" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column width="170">
          <template #header>
            {{ salesOrderId ? '订单销售单价' : '销售单价' }}
            <span v-if="form.priceAgreed" class="table-required-mark">*</span>
          </template>
          <template #default="{ row }"><el-input-number v-model="row.unitPrice" :controls="false" :disabled="!form.priceAgreed || !!salesOrderId" :min="form.priceAgreed ? 0.0001 : 0" :precision="4" /></template>
        </el-table-column>
        <el-table-column label="金额" width="140" align="right"><template #default="{ row }">{{ form.priceAgreed ? money(itemAmount(row)) : '待定' }}</template></el-table-column>
        <el-table-column label="包装/拣货建议" min-width="220"><template #default="{ row }"><span :class="row.planError ? 'text-danger' : 'muted'">{{ row.planError || row.packagingPlan?.suggestion || (row.packagingPlan ? '换算已校验' : '选择批次后计算') }}</span></template></el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row, $index }">
            <el-button v-if="salesOrderId && row.salesOrderItemId" text type="primary" @click="splitBatchAllocation(row, $index)">增加批次</el-button>
            <el-button text type="danger" @click="removeItem($index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
    <PackageLabelRecognitionDialog
      :model-value="outboundLabelRecognitionVisible"
      :expected-product-id="Number(outboundRecognitionRow?.productId || 0)"
      :expected-product-label="outboundRecognitionProductLabel"
      :inventory-packages="outboundRecognitionRow?.availablePackageInstances || []"
      @update:model-value="outboundLabelRecognitionVisible = $event"
      @confirm="applyRecognizedOutboundLabels"
    />
  </div>
</template>

<script setup lang="ts">
import { sumQuantities, sameQuantity, kilogramsToBaseQuantity } from '@/utils/quantity'
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import PageHeader from '@/components/PageHeader.vue'
import { api, type Address, type InventoryBatch, type InventoryPackageInstance, type PackagingPlan, type PackagingProfile, type SalesOrderRow } from '@/api/client'
import { currentDateText, currentDateTimeText, money, quantity } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import BarcodeScannerInput from '@/components/BarcodeScannerInput.vue'
import PackageLabelRecognitionDialog from '@/components/PackageLabelRecognitionDialog.vue'
import { defaultQuantityUnit, packagingUnits, requiresActualBaseQuantity } from '@/utils/packaging'
import { useSessionDraft } from '@/composables/useSessionDraft'
import { useAppStore } from '@/stores/app'
import { workspaceRouteKey } from '@/stores/workspaceTabs'
import { partnerAddressOptionLabel, receivingAddressOptions } from '@/utils/partnerAddresses'
import type { PackageLabelOcrResult } from '@/types/packageLabelOcr'

const router = useRouter()
const route = useRoute()
const queryClient = useQueryClient()
const appStore = useAppStore()
const today = currentDateText()
const editId = Number(route.params.id || 0)
const initialSalesOrderId = Number(route.query.salesOrderId || 0)
const nowText = currentDateTimeText(new Date(), 'T')
const formRef = ref<FormInstance>()
const draftWorkspaceKey = workspaceRouteKey(route)
const draftStorageKey = `copper-session-draft:v1:${appStore.session?.userId || 0}:outbound:${editId || `new-${initialSalesOrderId || 'standalone'}`}`
type OutboundItemForm = {
  salesOrderItemId?: number
  productId?: number
  batchId?: number
  quantity: number
  quantityUnit: string
  actualBaseQuantity?: number
  packageQuantity: number
  packageUnit: string
  priceUnit: string
  unitPrice: number
  packageInstanceIds: number[]
  availablePackageInstances: InventoryPackageInstance[]
  packagingProfile?: PackagingProfile
  packagingPlan?: PackagingPlan
  expectedEffectiveRevisionId?: number
  planError: string
}

const form = reactive({
  idempotencyKey: crypto.randomUUID(),
  salesOrderId: (initialSalesOrderId || undefined) as number | undefined,
  customerId: undefined as number | undefined,
  addressId: undefined as number | undefined,
  warehouseId: undefined as number | undefined,
  businessDate: today,
  actualShippedAt: nowText,
  sourceChannel: initialSalesOrderId ? 'SALES_ORDER' : 'PHONE',
  entryMode: 'NORMAL',
  lateEntryReason: '',
  toComplete: false,
  priceAgreed: true,
  discountRate: 0,
  adjustmentAmount: 0,
  shipmentPaymentPolicy: 'NONE',
  shipmentControlAmount: '' as number | string,
  allowOverShipment: false,
  overShipmentReason: '',
  memo: '',
  items: [makeItem()],
})
const salesOrderId = computed(() => Number(form.salesOrderId || 0))
const canOverShip = computed(() => appStore.hasPermission('outbound.over-shipment'))
const rules: FormRules = {
  customerId: [{ required: true, message: '请选择客户', trigger: 'change' }],
  warehouseId: [{ required: true, message: '请选择仓库', trigger: 'change' }],
  businessDate: [{ required: true, message: '请选择出库日期', trigger: 'change' }],
}

const addressCustomerId = computed(() => form.customerId ?? 0)
const { data: addresses } = useQuery({
  queryKey: computed(() => ['addresses', addressCustomerId.value]),
  queryFn: () => (addressCustomerId.value ? api.addresses(addressCustomerId.value) : Promise.resolve([])),
})
const receivingAddresses = computed(() => receivingAddressOptions(addresses.value, form.addressId))
const { data: warehouses } = useQuery({ queryKey: ['warehouses'], queryFn: api.warehouses })
const { data: batches } = useQuery({ queryKey: ['inventory-batches', 'available'], queryFn: () => api.inventoryBatches({ availableOnly: true }) })
const { data: salesOrders } = useQuery({ queryKey: ['sales-orders'], queryFn: api.salesOrders })
const availableSalesOrders = computed(() => (salesOrders.value ?? []).filter((item: SalesOrderRow) =>
  ['OPEN', 'PARTIAL_SHIPPED'].includes(item.status) || item.id === salesOrderId.value,
))
const { data: salesOrderDetail } = useQuery({
  queryKey: computed(() => ['sales-order-detail', salesOrderId.value]),
  queryFn: () => api.salesOrderDetail(salesOrderId.value),
  enabled: computed(() => salesOrderId.value > 0 && !editId),
})
const { data: editDetail } = useQuery({
  queryKey: ['outbound-detail', editId],
  queryFn: () => api.outboundDetail(editId),
  enabled: computed(() => editId > 0),
})
const scannerBusy = ref(false)
const scannerMessage = ref<{ type: 'success' | 'error' | 'info'; text: string }>()
const outboundLabelRecognitionVisible = ref(false)
const outboundRecognitionRow = ref<OutboundItemForm>()
const outboundRecognitionProductLabel = computed(() => {
  const row = outboundRecognitionRow.value
  const batch = (batches.value ?? []).find((item) => item.id === row?.batchId)
  return batch ? `${batch.productName} / ${batch.spec || batch.sku}` : ''
})

function availableBatchesFor(row: OutboundItemForm) {
  return (batches.value ?? []).filter((item) => (!form.warehouseId || item.warehouseId === form.warehouseId) && (!row.productId || item.productId === row.productId))
}

const sourceInitializedId = ref(0)
watch(salesOrderDetail, (detail) => {
  if (!detail || sourceInitializedId.value === detail.id) return
  sourceInitializedId.value = detail.id
  form.salesOrderId = detail.id
  form.customerId = detail.customerId
  form.warehouseId = detail.fulfillmentWarehouseId || undefined
  form.sourceChannel = 'SALES_ORDER'
  form.priceAgreed = true
  form.shipmentPaymentPolicy = detail.shipmentPaymentPolicy || 'NONE'
  form.items = detail.items
    .filter((item) => Number(item.requestedQuantity ?? item.quantity) - Number(item.shippedRequestedQuantity ?? item.shippedQuantity ?? 0) > 0)
    .map((item) => makeItem({
      salesOrderItemId: item.id,
      productId: item.productId,
      quantity: Number(item.requestedQuantity ?? item.quantity) - Number(item.shippedRequestedQuantity ?? item.shippedQuantity ?? 0),
      quantityUnit: item.requestedUnit || item.baseUnit || item.unit,
      packageQuantity: Number(item.shippedQuantity || 0) > 0 ? 0 : Number(item.packageQuantity || 0),
      priceUnit: item.priceUnit,
      packageUnit: item.packageUnit || '',
      unitPrice: Number(item.unitPrice),
    }))
  Promise.resolve().then(() => { form.addressId = detail.addressId })
}, { immediate: true })

const editInitialized = ref(false)
watch([editDetail, batches], async ([detail, batchRows]) => {
  if (!detail || !batchRows || editInitialized.value) return
  if (detail.status !== 'DRAFT') { ElMessage.warning('只有出库草稿可以编辑'); router.push(`/outbounds/${detail.id}`); return }
  editInitialized.value = true
  Object.assign(form, {
    salesOrderId: detail.salesOrderId || undefined,
    customerId: detail.customerId,
    addressId: detail.addressId || undefined,
    warehouseId: detail.warehouseId,
    businessDate: detail.businessDate,
    actualShippedAt: detail.actualShippedAt || nowText,
    sourceChannel: detail.sourceChannel,
    entryMode: detail.entryMode,
    lateEntryReason: detail.lateEntryReason,
    toComplete: detail.completenessStatus === 'TO_COMPLETE',
    priceAgreed: detail.settlementStatus !== 'PENDING_PRICING',
    discountRate: Number(detail.discountRate || 0),
    adjustmentAmount: Number(detail.adjustmentAmount || 0),
    shipmentPaymentPolicy: detail.shipmentPaymentPolicy || 'NONE',
    shipmentControlAmount: Number(detail.shipmentControlAmount || 0) || '',
    allowOverShipment: detail.allowOverShipment,
    overShipmentReason: detail.overShipmentReason,
    memo: detail.memo,
  })
  Promise.resolve().then(() => { form.addressId = detail.addressId || undefined })
  const rows = detail.items.map((item) => makeItem({
    salesOrderItemId: item.salesOrderItemId || undefined,
    productId: item.productId,
    batchId: item.batchId,
    quantity: Number(item.requestedQuantity ?? item.quantity),
    quantityUnit: item.requestedUnit || item.baseUnit || item.unit,
    actualBaseQuantity: Number(item.quantity),
    packageQuantity: Number(item.packageQuantity),
    packageUnit: item.packageUnit || '',
    priceUnit: item.priceUnit,
    unitPrice: Number(item.unitPrice),
    packageInstanceIds: parseIdList(item.selectedPackageInstanceIdsJson),
    packagingPlan: parseStoredPlan(item.packagingPlanJson),
  }))
  form.items = rows
  for (const row of rows) {
    const original = { quantity: row.quantity, quantityUnit: row.quantityUnit, actualBaseQuantity: row.actualBaseQuantity, packageQuantity: row.packageQuantity, packageUnit: row.packageUnit, priceUnit: row.priceUnit, packageInstanceIds: [...row.packageInstanceIds] }
    await handleBatchChange(row)
    Object.assign(row, original)
    await refreshPackagingPlan(row)
  }
}, { immediate: true })

watch(
  () => form.customerId,
  () => {
    form.addressId = undefined
  },
)

const createMutation = useMutation({ mutationFn: (payload: unknown) => editId ? api.updateOutbound(editId, payload) : api.createOutbound(payload) })
const saving = computed(() => createMutation.isPending.value)

const { clearDraft } = useSessionDraft({
  storageKey: () => draftStorageKey,
  snapshot: () => JSON.parse(JSON.stringify(form)) as typeof form,
  restore: (draft) => {
    Object.assign(form, draft)
    form.items = draft.items?.length ? draft.items.map((item) => ({ ...makeItem(), ...item })) : [makeItem()]
  },
  ready: computed(() => editId
    ? editInitialized.value
    : Boolean(batches.value) && (!initialSalesOrderId || sourceInitializedId.value === initialSalesOrderId)),
  label: '销售出库单',
  confirmRestore: Boolean(editId),
  workspaceKey: draftWorkspaceKey,
})

const originalAmount = computed(() => roundMoney(form.items.reduce((sum, item) => sum + itemAmount(item), 0)))
const discountAmount = computed(() => roundMoney(originalAmount.value * Number(form.discountRate || 0) / 100))
const totalAmount = computed(() => roundMoney(originalAmount.value - discountAmount.value + Number(form.adjustmentAmount || 0)))
const hasActualWeightLines = computed(() =>
  form.items.some((item) => requiresActualBaseQuantity(item.packagingProfile, item.packageUnit)))
const overShipmentLines = computed(() => {
  if (!salesOrderDetail.value) return []
  const actualByOrderItem = new Map<number, { quantity: number; baseUnit: string }>()
  for (const row of form.items) {
    if (!row.salesOrderItemId || !row.packagingPlan) continue
    const current = actualByOrderItem.get(row.salesOrderItemId)
    actualByOrderItem.set(row.salesOrderItemId, {
      quantity: Number(current?.quantity || 0) + Number(row.packagingPlan.baseQuantity || 0),
      baseUnit: row.packagingPlan.baseUnit,
    })
  }
  return salesOrderDetail.value.items.flatMap((orderItem) => {
    const actualRow = actualByOrderItem.get(orderItem.id)
    if (!actualRow) return []
    const remaining = Math.max(Number(orderItem.quantity || 0) - Number(orderItem.shippedQuantity || 0), 0)
    const actual = actualRow.quantity
    if (actual <= remaining + 0.000001) return []
    return [
      `${orderItem.productName}：订单剩余 ${quantity(remaining)}${actualRow.baseUnit}，本次整包装实发 ${quantity(actual)}${actualRow.baseUnit}，超出 ${quantity(actual - remaining)}${actualRow.baseUnit}`,
    ]
  })
})

function handleSalesOrderChange(value?: number) {
  sourceInitializedId.value = 0
  if (value) return
  form.customerId = undefined
  form.addressId = undefined
  form.sourceChannel = 'PHONE'
  form.shipmentPaymentPolicy = 'NONE'
  form.allowOverShipment = false
  form.overShipmentReason = ''
  form.items = [makeItem()]
}

function handlePriceAgreedChange(agreed: boolean) {
  if (!agreed) return
  for (const item of form.items) fillPrice(item)
}

function fillPrice(row: OutboundItemForm) {
  if (!form.priceAgreed) return
  const batch = (batches.value ?? []).find((item: InventoryBatch) => item.id === row.batchId)
  if (batch && row.unitPrice <= 0) row.unitPrice = Number(batch.unitCost)
}

function addressLabel(item: Address) {
  return partnerAddressOptionLabel(item)
}

function addItem() {
  form.items.push(makeItem())
}

function splitBatchAllocation(row: OutboundItemForm, index: number) {
  form.items.splice(index + 1, 0, makeItem({
    salesOrderItemId: row.salesOrderItemId,
    productId: row.productId,
    quantity: 0,
    quantityUnit: row.quantityUnit,
    packageUnit: row.packageUnit,
    priceUnit: row.priceUnit,
    unitPrice: row.unitPrice,
  }))
}

function removeItem(index: number) {
  if (form.items.length > 1) form.items.splice(index, 1)
}

async function submit(confirmNow: boolean) {
  if (saving.value) return
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (saving.value) return
  if (form.entryMode === 'BACKFILL' && !form.lateEntryReason.trim()) { ElMessage.warning('请填写事后补录原因'); return }
  if (salesOrderId.value && form.allowOverShipment && !form.overShipmentReason.trim()) { ElMessage.warning('请填写超发原因'); return }
  if (!form.priceAgreed && form.shipmentPaymentPolicy !== 'NONE' && Number(form.shipmentControlAmount || 0) <= 0) {
    ElMessage.warning('尚未约定价格且要求发货前到账时，必须填写暂估最高金额')
    return
  }
  if (form.items.some((item) => !item.batchId || !item.quantityUnit || item.quantity <= 0 || (form.priceAgreed && item.unitPrice <= 0))) {
    ElMessage.warning('请检查明细库存批次、数量和销售单价')
    return
  }
  if (form.items.some((item) => requiresActualBaseQuantity(item.packagingProfile, item.packageUnit)
    && (item.packageInstanceIds.length !== Number(item.packageQuantity)
      || !item.packagingProfile
      || item.quantityUnit !== item.packagingProfile.baseUnit
      || !sameQuantity(sumQuantities(item.availablePackageInstances
        .filter((instance) => item.packageInstanceIds.includes(instance.id))
        .map((instance) => instance.remainingBaseQuantity ?? instance.actualBaseQuantity)), item.quantity)))) {
    ElMessage.warning('逐件实重包装必须选择具体整盘/整箱，包装数与选择数量一致，kg 数量由所选包装实重自动汇总')
    return
  }
  await Promise.all(form.items.map((item) => refreshPackagingPlan(item)))
  if (saving.value) return
  const invalidPlan = form.items.find((item) => item.planError || !item.packagingPlan)
  if (invalidPlan) {
    ElMessage.warning(invalidPlan.planError || '请完成包装换算')
    return
  }
  const { priceAgreed, salesOrderId: _formSalesOrderId, idempotencyKey, ...formPayload } = form
  const result = await createMutation.mutateAsync({
    ...formPayload,
    salesOrderId: salesOrderId.value || undefined,
    pricePending: !priceAgreed,
    completenessStatus: form.toComplete ? 'TO_COMPLETE' : 'COMPLETE',
    confirmNow,
    idempotencyKey,
    items: form.items.map(({ packagingProfile, packagingPlan, availablePackageInstances, planError, productId, ...item }) => ({
      ...item,
      expectedEffectiveRevisionId: item.salesOrderItemId ? undefined : item.expectedEffectiveRevisionId,
      expectedPackagingSnapshotHash: undefined,
    })),
  })
  clearDraft()
  ElMessage.success(confirmNow ? (!form.priceAgreed ? '已发货，等待补充最终价格后形成应收' : '发货完成，已按实际出库形成应收') : '出库草稿已保存')
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['outbounds'] }),
    queryClient.invalidateQueries({ queryKey: ['inventory-batches'] }),
    queryClient.invalidateQueries({ queryKey: ['receivables'] }),
    queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
    queryClient.invalidateQueries({ queryKey: ['inventory-atp'] }),
    salesOrderId.value ? queryClient.invalidateQueries({ queryKey: ['sales-order-atp', salesOrderId.value] }) : Promise.resolve(),
    salesOrderId.value ? queryClient.invalidateQueries({ queryKey: ['sales-order-atp-history', salesOrderId.value] }) : Promise.resolve(),
    salesOrderId.value ? queryClient.invalidateQueries({ queryKey: ['sales-order-detail', salesOrderId.value] }) : Promise.resolve(),
  ])
  router.push(`/outbounds/${result.id}`)
}

function makeItem(initial: Partial<OutboundItemForm> = {}): OutboundItemForm {
  return {
    batchId: undefined,
    quantity: 1000,
    quantityUnit: '',
    actualBaseQuantity: undefined,
    packageQuantity: 0,
    packageUnit: '',
    priceUnit: '',
    unitPrice: 85,
    packageInstanceIds: [],
    availablePackageInstances: [],
    packagingProfile: undefined,
    packagingPlan: undefined,
    expectedEffectiveRevisionId: undefined,
    planError: '',
    ...initial,
  }
}

function packageUnits(profile?: PackagingProfile) {
  if (!profile) return []
  return [...new Set(profile.rules.filter((item) => item.outboundAllowed).map((item) => item.parentUnit))]
}

async function handleBatchChange(row: OutboundItemForm) {
  fillPrice(row)
  const batch = (batches.value ?? []).find((item) => item.id === row.batchId)
  if (!batch) return
  row.productId = batch.productId
  const [profile, packageInstances] = await Promise.all([
    api.batchPackagingProfile(batch.id),
    api.inventoryPackageInstances(batch.id),
  ])
  if (row.batchId !== batch.id) return
  row.packagingProfile = profile
  row.availablePackageInstances = packageInstances
  row.packageInstanceIds = []
  row.actualBaseQuantity = undefined
  if (!row.salesOrderItemId) row.quantityUnit = defaultQuantityUnit(profile, 'OUTBOUND')
  if (!row.priceUnit) row.priceUnit = profile.baseUnit
  await refreshPackagingPlan(row)
}

async function scanOutbound(barcode: string) {
  if (!form.warehouseId) {
    scannerMessage.value = { type: 'error', text: '请先选择出库仓库。' }
    return
  }
  scannerBusy.value = true
  try {
    const match = await api.packageBarcode(barcode)
    const candidates = (batches.value ?? [])
      .filter((item) => item.productId === match.productId
        && item.warehouseId === form.warehouseId
        && Number(item.quantityFree ?? item.quantityAvailable) > 0)
      .sort((a, b) => String(a.expirationDate || a.receivedAt).localeCompare(String(b.expirationDate || b.receivedAt)))
    const batch = candidates[0]
    if (!batch) throw new Error(`当前仓库没有 ${match.productName} 的自由可用库存`)
    let row = form.items.find((item) => item.batchId === batch.id && item.packageUnit === match.packageUnit)
    if (row) {
      row.packageQuantity = Number(row.packageQuantity || 0) + 1
      scannerMessage.value = { type: 'info', text: `${match.productName} / ${match.packageUnit} 包装数已累加为 ${quantity(row.packageQuantity)}；请填写 kg 数量或选择具体实重包装。` }
      return
    }
    row = form.items.find((item) => item.productId === match.productId && !item.batchId)
      ?? (!salesOrderId.value ? form.items.find((item) => !item.batchId && !item.productId) : undefined)
    if (!row) {
      if (salesOrderId.value) throw new Error('销售订单中没有可继续扫描的该商品明细，请核对订单剩余数量')
      row = makeItem({ productId: match.productId })
      form.items.push(row)
    }
    row.batchId = batch.id
    await handleBatchChange(row)
    row.packageUnit = match.packageUnit
    row.packageQuantity = 1
    scannerMessage.value = { type: 'success', text: `已识别 ${match.productName}，自动选择批次 ${batch.batchNo}；包装记为 1${match.packageUnit}，请填写 kg 数量或选择具体实重包装，核对后确认发货。` }
  } catch (error) {
    scannerMessage.value = { type: 'error', text: requestErrorMessage(error) }
  } finally {
    scannerBusy.value = false
  }
}

function handleQuantityUnitChange(row: OutboundItemForm) {
  row.packageInstanceIds = []
  row.actualBaseQuantity = undefined
  void refreshPackagingPlan(row)
}

function handlePackageUnitChange(row: OutboundItemForm) {
  row.packageInstanceIds = []
  row.actualBaseQuantity = undefined
  void refreshPackagingPlan(row)
}

function handlePackageSelection(row: OutboundItemForm) {
  const selected = row.availablePackageInstances.filter((item) => row.packageInstanceIds.includes(item.id))
  if (selected.length) {
    const selectedUnits = [...new Set(selected.map((item) => item.unitName))]
    if (selectedUnits.length !== 1) {
      ElMessage.warning('一次出库只能选择同一种实重包装')
      row.packageInstanceIds = []
      row.actualBaseQuantity = undefined
      return
    }
    const total = sumQuantities(selected.map((item) => item.remainingBaseQuantity ?? item.actualBaseQuantity))
    row.quantityUnit = row.packagingProfile?.baseUnit || selected[0].baseUnit
    row.quantity = total
    row.actualBaseQuantity = total || undefined
    row.packageQuantity = selected.length
    row.packageUnit = selectedUnits[0]
  } else if (requiresActualBaseQuantity(row.packagingProfile, row.packageUnit)) {
    row.quantity = 0
    row.actualBaseQuantity = undefined
    row.packageQuantity = 0
  }
  void refreshPackagingPlan(row)
}

function openOutboundLabelRecognition(row: OutboundItemForm) {
  if (!row.batchId || !row.productId || !row.availablePackageInstances.length) {
    ElMessage.warning('请先选择有可用实重包装的库存批次')
    return
  }
  outboundRecognitionRow.value = row
  outboundLabelRecognitionVisible.value = true
}

function applyRecognizedOutboundLabels(results: PackageLabelOcrResult[]) {
  const row = outboundRecognitionRow.value
  if (!row) return
  const matchedIds: number[] = []
  for (const result of results) {
    const matched = row.availablePackageInstances.find((item) =>
      item.packageCode.trim().toUpperCase() === result.packageCode.trim().toUpperCase())
    if (!matched) {
      ElMessage.warning(`包装编号 ${result.packageCode} 已不在当前批次可用库存中，请刷新后重试`)
      return
    }
    let recognizedBaseQuantity: number
    try {
      recognizedBaseQuantity = kilogramsToBaseQuantity(result.actualWeightKg, matched.baseUnit)
    } catch (error) {
      ElMessage.warning(error instanceof Error ? error.message : '标签重量单位无法换算，请人工选择')
      return
    }
    if (!sameQuantity(matched.actualBaseQuantity, recognizedBaseQuantity)) {
      ElMessage.warning(`包装 ${result.packageCode} 的标签重量与库存记录不一致，未选择`)
      return
    }
    matchedIds.push(matched.id)
  }
  row.packageInstanceIds = [...new Set([...row.packageInstanceIds, ...matchedIds])]
  handlePackageSelection(row)
  ElMessage.success(`已按 ${matchedIds.length} 个经人工确认的标签选择现有库存包装`)
}

async function refreshPackagingPlan(row: OutboundItemForm) {
  const batch = (batches.value ?? []).find((item) => item.id === row.batchId)
  if (!batch || !row.quantityUnit || row.quantity <= 0) return
  try {
    const plan = await api.batchPackagingPlan(batch.id, {
      quantity: row.quantity,
      quantityUnit: row.quantityUnit,
      actualBaseQuantity: row.actualBaseQuantity,
      priceUnit: row.priceUnit || row.packagingProfile?.baseUnit,
      purpose: 'OUTBOUND',
    })
    if (row.batchId !== batch.id) return
    row.packagingPlan = plan
    row.expectedEffectiveRevisionId = plan.effectiveRevisionId ?? undefined
    row.planError = plan.baseQuantity > Number(batch.quantityAvailable) ? '当前批次库存不足' : ''
  } catch (error) {
    row.packagingPlan = undefined
    row.expectedEffectiveRevisionId = undefined
    row.planError = requestErrorMessage(error)
  }
}

function itemAmount(row: OutboundItemForm) {
  return Number(row.packagingPlan?.pricingQuantity || row.quantity || 0) * Number(row.unitPrice || 0)
}

function roundMoney(value: number) {
  return Math.round((Number(value || 0) + Number.EPSILON) * 100) / 100
}

function signedMoney(value: number | string) {
  const amount = Number(value || 0)
  return `${amount >= 0 ? '+' : '-'}${money(Math.abs(amount))}`
}

function requestErrorMessage(error: unknown) {
  const responseMessage = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  return responseMessage || (error instanceof Error ? error.message : '包装换算失败')
}

function parseIdList(value?: string) {
  if (!value) return []
  try { return (JSON.parse(value) as unknown[]).map(Number).filter((id) => Number.isFinite(id) && id > 0) } catch { return [] }
}

function parseStoredPlan(value?: string): PackagingPlan | undefined {
  if (!value) return undefined
  try {
    const root = JSON.parse(value) as Record<string, any>
    const calculation = root.calculation || root
    return {
      requestedQuantity: Number(root.requestedQuantity ?? calculation.requestedQuantity ?? 0),
      requestedUnit: String(root.requestedUnit ?? calculation.requestedUnit ?? ''),
      baseQuantity: Number(root.baseQuantity ?? calculation.baseQuantity ?? 0),
      baseUnit: String(root.baseUnit ?? calculation.baseUnit ?? ''),
      pricingQuantity: Number(root.pricingQuantity ?? calculation.pricingQuantity ?? 0),
      priceUnit: String(root.priceUnit ?? calculation.priceUnit ?? ''),
      actualWeightRequired: Boolean(calculation.actualWeightRequired),
      rounded: false,
      roundingPolicy: String(root.policy?.roundingPolicy || 'REJECT') as PackagingPlan['roundingPolicy'],
      suggestion: `${root.requestedQuantity ?? calculation.requestedQuantity ?? ''}${root.requestedUnit ?? calculation.requestedUnit ?? ''}`,
      components: (root.components ?? calculation.components ?? []),
      snapshotJson: value,
      effectiveRevisionId: Number(root.effectiveRevisionId || 0) || undefined,
    }
  } catch { return undefined }
}
</script>

<style scoped>
.form-help {
  width: 100%;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.45;
}

.entry-form :deep(.el-input-number) {
  width: 100%;
}

.inventory-quantity-field {
  display: flex;
  align-items: baseline;
  justify-content: flex-end;
  gap: 6px;
  min-height: 32px;
  padding: 0 10px;
  border-radius: var(--el-border-radius-base);
  background: var(--el-fill-color-light);
  font-variant-numeric: tabular-nums;
}

.inventory-quantity-unit {
  color: var(--el-text-color-secondary);
}

.outbound-scanner-message {
  padding: 8px 14px;
  border-bottom: 1px solid var(--el-border-color-light);
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
}

.outbound-scanner-message.is-error {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
}

.outbound-scanner-message.is-info {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.package-selection-cell {
  display: grid;
  gap: 4px;
}

.package-selection-cell .el-button {
  justify-self: start;
}
</style>
