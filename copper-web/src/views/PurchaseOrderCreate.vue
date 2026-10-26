<template>
  <div class="page">
    <div class="panel">
      <PageHeader :title="isAmend ? '变更采购订单' : isEdit ? '编辑采购订单' : '新建采购订单'" :back-to="documentId ? `/purchase-orders/${documentId}` : '/purchase-orders'">
        <el-button type="primary" :loading="saving" :disabled="!canViewProductCost" @click="submit">{{ isAmend ? '确认变更' : '保存草稿' }}</el-button>
      </PageHeader>
      <el-alert
        v-if="!canViewProductCost"
        type="error"
        :closable="false"
        show-icon
        class="amend-alert"
        title="当前账号没有查看采购价/成本权限，无法读取供应商供货版本并保存采购订单。请联系管理员授予“查看成本价”。"
      />
      <el-alert v-if="isAmend" type="warning" :closable="false" show-icon class="amend-alert" title="变更会先冲回当前采购承诺，再按新版本重新过账；已实际入库的数量和商品不能减少或替换。" />
      <div class="panel-body">
        <el-form ref="formRef" class="entry-form" :model="form" :rules="rules" label-width="88px">
          <el-row :gutter="16">
            <el-col :span="8">
              <el-form-item label="供应商" prop="supplierId">
                <PartnerRemoteSelect v-model="form.supplierId" type="SUPPLIER" :disabled="isAmend" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="采购日期" prop="businessDate">
                <el-date-picker v-model="form.businessDate" value-format="YYYY-MM-DD" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="预计到货" prop="expectedDate">
                <el-date-picker v-model="form.expectedDate" value-format="YYYY-MM-DD" placeholder="确认前必填" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="入库仓库" prop="expectedWarehouseId">
                <el-select v-model="form.expectedWarehouseId" filterable clearable placeholder="确认前必填">
                  <el-option v-for="item in enabledWarehouses" :key="item.id" :label="item.name" :value="item.id" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="方式">
                <el-select v-model="form.sourceType">
                  <el-option label="正式订单" value="FORMAL" />
                  <el-option label="电话" value="PHONE" />
                  <el-option label="微信" value="WECHAT" />
                  <el-option label="邮件" value="EMAIL" />
                  <el-option label="口头" value="ORAL" />
                  <el-option label="现场" value="ONSITE" />
                  <el-option label="其他" value="OTHER" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="价格口径">
                <div class="tax-contract-field">
                  <el-tag effect="plain">{{ priceIncludesTax ? '含税价' : '未税价' }}</el-tag>
                  <span>{{ taxRate }}%</span><span class="muted">来自供应商设置，保存后冻结</span>
                </div>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="折扣%">
                <el-input-number v-model="form.discountRate" :min="0" :max="100" :precision="2" :controls="false" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="调整金额">
                <el-input-number v-model="form.adjustmentAmount" :precision="2" :controls="false" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="凭证说明">
                <el-input v-model="form.sourceNote" placeholder="微信截图、电话确认人、邮件主题等" />
              </el-form-item>
            </el-col>
            <el-col :span="16">
              <el-form-item label="备注">
                <el-input v-model="form.memo" />
              </el-form-item>
            </el-col>
            <el-col v-if="isAmend" :span="24">
              <el-form-item label="变更原因" prop="amendmentReason">
                <el-input v-model="form.amendmentReason" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="必填：说明数量、价格、交期或其他采购承诺变化原因" />
              </el-form-item>
            </el-col>
          </el-row>
        </el-form>
      </div>
    </div>

    <div class="panel">
      <div class="panel-header purchase-items-header">
        <div class="panel-title">采购商品明细</div>
        <el-button @click="addItem">增加明细</el-button>
      </div>
      <div class="order-total-bar">
        <span>合计金额：{{ money(originalAmount) }}</span>
        <span>折扣金额：-{{ money(discountAmount) }}</span>
        <span>调整金额：{{ signedMoney(form.adjustmentAmount) }}</span>
        <span>未税：{{ money(taxPreview.amountExcludingTax) }}</span>
        <span>税额：{{ money(taxPreview.taxAmount) }}</span>
        <span class="order-total-final">实际金额：{{ money(totalAmount) }}</span>
      </div>
      <el-table :data="form.items" class="data-table purchase-items-table" size="small" border show-overflow-tooltip height="calc(100vh - 475px)">
        <el-table-column min-width="300" fixed="left">
          <template #header>商品 <span class="table-required-mark">*</span></template>
          <template #default="{ row }">
            <el-select v-model="row.productId" filterable :disabled="isAmend && Boolean(row.itemId)" @change="handleProductChange(row)">
              <el-option
                v-for="item in transactionProductOptions(products ?? [], row.productId)"
                :key="item.id"
                :label="transactionProductOptionLabel(item)"
                :value="item.id"
                :disabled="!isTransactionSelectableProduct(item)"
              />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column min-width="150" align="right">
          <template #header>采购数量 <span class="table-required-mark">*</span></template>
          <template #default="{ row }"><el-input-number v-model="row.quantity" class="number-input-right" :min="0.000001" :precision="6" :controls="false" @change="handleQuantityChange(row)" /></template>
        </el-table-column>
        <el-table-column min-width="120">
          <template #header>数量单位 <span class="table-required-mark">*</span></template>
          <template #default="{ row }">
            <el-select v-model="row.quantityUnit" filterable :disabled="!row.productId || Boolean(row.frozenSupplierOffer)" @change="handleQuantityUnitChange(row)">
              <el-option v-for="item in purchaseQuantityUnits(row)" :key="item" :label="item" :value="item" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="预计库存数量" min-width="210" align="right">
          <template #default="{ row }">
            <div class="inventory-quantity-stack">
              <div class="inventory-quantity-field">
                <el-input-number
                  v-if="requiresActualBaseQuantity(row.packagingProfile, row.quantityUnit) || row.supplierOfferEstimateApplied"
                  v-model="row.actualBaseQuantity"
                  class="number-input-right"
                  placeholder="请填写"
                  :min="0.000001"
                  :precision="6"
                  :controls="false"
                  @change="handleActualBaseQuantityChange(row)"
                />
                <el-input-number
                  v-else
                  :model-value="row.packagingPlan?.baseQuantity"
                  class="number-input-right"
                  disabled
                  :precision="6"
                  :controls="false"
                />
                <span class="inventory-quantity-unit">{{ row.packagingProfile?.baseUnit || '-' }}</span>
              </div>
              <small v-if="row.supplierOfferEstimateApplied" class="estimate-note">按供货包装参考重量预填，仅订单估算；入库仍需录实重</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="赠品" width="90" align="center">
          <template #default="{ row }"><el-checkbox v-model="row.noCharge" @change="handleNoChargeChange(row)" /></template>
        </el-table-column>
        <el-table-column min-width="205" align="right">
          <template #header>采购单价 / 来源 <span class="table-required-mark">*</span></template>
          <template #default="{ row }">
            <el-input-number v-model="row.unitPrice" class="number-input-right" :min="0" :precision="4" :controls="false" :disabled="row.noCharge || row.priceLoading" @change="markManualPrice(row)" />
            <el-tooltip :content="priceTooltip(row)" placement="top">
              <div class="price-source" :class="{ warning: row.priceWarnings.length }">{{ row.priceSource || '手工输入' }}</div>
            </el-tooltip>
            <ul v-if="rowOfferViolations(row).length" class="offer-violations">
              <li v-for="warning in rowOfferViolations(row)" :key="warning">{{ warning }}</li>
            </ul>
          </template>
        </el-table-column>
        <el-table-column min-width="130">
          <template #header>计价单位 <span class="table-required-mark">*</span></template>
          <template #default="{ row }">
            <el-select v-model="row.priceUnit" filterable :disabled="!row.productId || row.priceLoading || Boolean(row.frozenSupplierOffer)" @change="handlePriceUnitChange(row)">
              <el-option v-for="item in row.priceUnitOptions" :key="item.name" :label="unitOptionLabel(item)" :value="item.name" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="预计包装数" min-width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.packageQuantity" class="number-input-right" :min="0" :precision="6" :controls="false" :disabled="Boolean(row.frozenSupplierOffer)" />
          </template>
        </el-table-column>
        <el-table-column label="包装单位" min-width="130">
          <template #default="{ row }">
            <el-select v-model="row.packageUnit" clearable filterable :disabled="!row.productId || row.packageUnitOptions.length === 0 || Boolean(row.frozenSupplierOffer)">
              <el-option v-for="item in row.packageUnitOptions" :key="item.name" :label="unitOptionLabel(item)" :value="item.name" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="当前供货条款" min-width="260">
          <template #default="{ row }">
            <div v-if="row.supplierOffer" class="supplier-offer-card">
              <div>
                <el-tag size="small" effect="plain">供货档案 v{{ row.supplierOffer.supplierOfferRevisionNo }}</el-tag>
                <el-tag size="small" :type="row.supplierOffer.priceIncludesTax ? 'success' : 'info'">
                  {{ row.supplierOffer.currencyCode || '币种待同步' }} · {{ row.supplierOffer.priceIncludesTax ? '含税' : '未税' }} {{ row.supplierOffer.taxRate }}%
                </el-tag>
              </div>
              <span>采购：{{ row.supplierOffer.purchaseUnit }}；报价：{{ row.supplierOffer.quotedUnitPrice ?? '-' }}/{{ row.supplierOffer.priceUnit }}</span>
              <span>起订 {{ row.supplierOffer.minimumOrderQuantity ?? '-' }}；倍数 {{ row.supplierOffer.orderMultiple ?? '-' }}；交期 {{ row.supplierOffer.leadTimeDays ?? '-' }} 天</span>
              <span v-if="row.supplierOffer.packagingVariantRevisionId">
                包装：{{ row.supplierOffer.packagingVariantName }} v{{ row.supplierOffer.packagingVariantRevisionNo }}
              </span>
            </div>
            <span v-else class="muted">无当前有效供货档案，按通用价格与包装规则</span>
          </template>
        </el-table-column>
        <el-table-column label="金额" min-width="140" align="right">
          <template #default="{ row }">{{ money(itemAmount(row)) }}</template>
        </el-table-column>
        <el-table-column label="包装/换算建议" min-width="220">
          <template #default="{ row }">
            <span :class="row.planError ? 'text-danger' : 'muted'">{{ row.planError || row.packagingPlan?.suggestion || '选择商品后计算' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="132" fixed="right">
          <template #default="{ row, $index }">
            <div class="table-actions">
              <el-button text type="primary" @click="copyItem(row, $index)">复制</el-button>
              <el-button text type="danger" :disabled="form.items.length <= 1 || Number(row.fulfilledQuantity || 0) > 0" @click="removeItem($index)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { roundQuantity } from '@/utils/quantity'
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import PageHeader from '@/components/PageHeader.vue'
import {
  api,
  type PackagingPlan,
  type PackagingProfile,
  type ProductPurchaseOptions,
  type ProductPurchaseUnitOption,
  type ProductSupplierOfferContext,
} from '@/api/client'
import { currentDateText, money } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import { defaultQuantityUnit, packagingUnits, requiresActualBaseQuantity } from '@/utils/packaging'
import { convertUnitPrice, hasUnitOption, mergeUnitOptions } from '@/utils/unitPricing'
import { isTransactionSelectableProduct, transactionProductOptionLabel, transactionProductOptions } from '@/utils/productDisplay'
import { useSessionDraft } from '@/composables/useSessionDraft'
import { useAppStore } from '@/stores/app'
import { workspaceRouteKey } from '@/stores/workspaceTabs'
import { grossFromEntered, splitGross } from '@/utils/taxPricing'

const router = useRouter()
const route = useRoute()
const queryClient = useQueryClient()
const appStore = useAppStore()
const today = currentDateText()
const formRef = ref<FormInstance>()
const isEdit = computed(() => route.name === 'purchase-order-edit')
const isAmend = computed(() => route.name === 'purchase-order-amend')
const documentId = computed(() => isEdit.value || isAmend.value ? Number(route.params.id || 0) : 0)
const editId = documentId
const loadingDraft = ref(false)
const priceIncludesTax = ref(true)
const taxRate = ref(0)
const canViewProductCost = computed(() => appStore.hasPermission('product.cost.view'))
const draftWorkspaceKey = workspaceRouteKey(route)
const draftStorageKey = `copper-session-draft:v1:${appStore.session?.userId || 0}:purchase-order:${isAmend.value ? 'amend-' : ''}${editId.value || 'new'}`

type PurchaseItemForm = {
  itemId?: number
  fulfilledQuantity?: number
  productId?: number
  quantity?: number
  quantityUnit: string
  actualBaseQuantity?: number
  priceUnit: string
  packageQuantity: number
  packageUnit: string
  noCharge: boolean
  unitPrice: number
  priceUnitOptions: ProductPurchaseUnitOption[]
  packageUnitOptions: ProductPurchaseUnitOption[]
  priceLoading: boolean
  priceSource: string
  priceExplanation: string
  priceWarnings: string[]
  supplierOffer?: ProductSupplierOfferContext | null
  expectedSupplierOfferRevisionId?: number
  expectedSupplierOfferSnapshotHash?: string
  packagingVariantRevisionId?: number
  frozenSupplierOffer?: ProductSupplierOfferContext
  frozenSupplierOfferError?: string
  supplierOfferEstimateApplied: boolean
  packagingProfile?: PackagingProfile
  packagingPlan?: PackagingPlan
  expectedEffectiveRevisionId?: number
  expectedPackagingSnapshotHash?: string
  planError: string
}

const form = reactive({
  supplierId: undefined as number | undefined,
  businessDate: today,
  expectedDate: '',
  expectedWarehouseId: undefined as number | undefined,
  sourceType: 'FORMAL',
  sourceNote: '',
  discountRate: 0,
  adjustmentAmount: 0,
  memo: '',
  amendmentReason: '',
  items: [makeItem()],
})
const rules: FormRules = {
  supplierId: [{ required: true, message: '请选择供应商', trigger: 'change' }],
  businessDate: [{ required: true, message: '请选择采购日期', trigger: 'change' }],
  expectedDate: [{ validator: (_rule, value, callback) => isAmend.value && !value ? callback(new Error('变更已确认订单必须填写预计到货日期')) : callback(), trigger: 'change' }],
  expectedWarehouseId: [{ validator: (_rule, value, callback) => isAmend.value && !value ? callback(new Error('变更已确认订单必须选择入库仓库')) : callback(), trigger: 'change' }],
  amendmentReason: [{ validator: (_rule, value, callback) => isAmend.value && !String(value || '').trim() ? callback(new Error('请填写变更原因')) : callback(), trigger: 'blur' }],
}

const { data: products } = useQuery({ queryKey: ['products'], queryFn: api.products })
const { data: warehouses } = useQuery({ queryKey: ['warehouses'], queryFn: api.warehouses })
const enabledWarehouses = computed(() => (warehouses.value || []).filter((item) => item.status === 'ENABLED'))

const originalAmount = computed(() => roundMoney(form.items.reduce((sum, item) => sum + itemAmount(item), 0)))
const discountAmount = computed(() => roundMoney(originalAmount.value * Number(form.discountRate || 0) / 100))
const totalAmount = computed(() => roundMoney(originalAmount.value - discountAmount.value + Number(form.adjustmentAmount || 0)))
const taxPreview = computed(() => splitGross(totalAmount.value, taxRate.value))

const { clearDraft } = useSessionDraft({
  storageKey: () => draftStorageKey,
  snapshot: () => JSON.parse(JSON.stringify(form)) as typeof form,
  restore: (draft) => {
    Object.assign(form, draft)
    form.items = draft.items?.length ? draft.items.map((item) => ({ ...makeItem(), ...item, priceLoading: false })) : [makeItem()]
  },
  ready: computed(() => !loadingDraft.value),
  label: '采购订单',
  confirmRestore: isEdit.value || isAmend.value,
  workspaceKey: draftWorkspaceKey,
})

const createMutation = useMutation({
  mutationFn: (data: unknown) => isAmend.value
    ? api.amendPurchaseOrder(editId.value, form.amendmentReason.trim(), data)
    : isEdit.value ? api.updatePurchaseOrder(editId.value, data) : api.createPurchaseOrder(data),
  onSuccess: async (result) => {
    clearDraft()
    ElMessage.success(isAmend.value ? '采购订单变更已确认并重新过账' : '采购订单草稿已保存')
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['purchase-orders'] }),
      queryClient.invalidateQueries({ queryKey: ['purchase-order-detail', result.id] }),
      queryClient.invalidateQueries({ queryKey: ['payables'] }),
      queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
      queryClient.invalidateQueries({ queryKey: ['business-postings'] }),
      queryClient.invalidateQueries({ queryKey: ['business-order-revisions', 'PURCHASE_ORDER', result.id] }),
      queryClient.invalidateQueries({ queryKey: ['inventory-atp'] }),
    ])
    router.push(`/purchase-orders/${result.id}`)
  },
  onError: async (error) => {
    const message = requestErrorMessage(error)
    if (isSupplierOfferConflict(message)) {
      await refreshSupplierOfferContexts()
      const keepsFrozenRows = isAmend.value && form.items.some((item) => item.itemId && item.frozenSupplierOffer)
      ElMessage.error(keepsFrozenRows
        ? `${message}；原订单已有行仍保留冻结供货版本，新加行已刷新当前版本，请逐行复核后再保存`
        : `${message}；系统已按当前有效供货档案刷新报价、条款和包装估算，请复核后重新保存`)
      return
    }
    ElMessage.error(message || '采购订单保存失败')
  },
})
const saving = computed(() => createMutation.isPending.value || loadingDraft.value)

watch(
  () => form.supplierId,
  () => {
    if (loadingDraft.value) return
    if (form.supplierId && !isEdit.value && !isAmend.value) void loadPartnerTax(form.supplierId)
    for (const item of form.items) {
      if (item.productId) void refreshPurchaseOptions(item, item.priceUnit).then(() => refreshPackagingPlan(item))
    }
  },
)

watch(() => form.businessDate, () => {
  if (loadingDraft.value) return
  for (const item of form.items) {
    if (item.productId) void refreshPurchaseOptions(item, item.priceUnit).then(() => refreshPackagingPlan(item))
  }
})

watch(
  () => [route.name, route.params.id] as const,
  () => {
    if (isEdit.value || isAmend.value) void loadDraft(editId.value)
  },
  { immediate: true },
)

function addItem() {
  form.items.push(makeItem())
}

function copyItem(row: PurchaseItemForm, index: number) {
  const copied: PurchaseItemForm = {
    ...row,
    itemId: undefined,
    fulfilledQuantity: 0,
    supplierOffer: null,
    expectedSupplierOfferRevisionId: undefined,
    expectedSupplierOfferSnapshotHash: undefined,
    packagingVariantRevisionId: undefined,
    frozenSupplierOffer: undefined,
    frozenSupplierOfferError: '',
    priceUnitOptions: [...row.priceUnitOptions],
    packageUnitOptions: [...row.packageUnitOptions],
    priceLoading: false,
    packagingPlan: undefined,
    expectedEffectiveRevisionId: undefined,
    expectedPackagingSnapshotHash: undefined,
    planError: '',
  }
  form.items.splice(index + 1, 0, copied)
  if (copied.productId) void refreshPurchaseOptions(copied, copied.priceUnit).then(() => refreshPackagingPlan(copied))
}

function removeItem(index: number) {
  if (form.items.length > 1) form.items.splice(index, 1)
}

function handleNoChargeChange(row: PurchaseItemForm) {
  if (row.noCharge) {
    row.unitPrice = 0
    row.priceSource = '赠品零价'
    row.priceExplanation = '赠品不计采购价，也不会写回供应商参考价'
    return
  }
  if (row.productId) void refreshPurchaseOptions(row, row.priceUnit)
}

function markManualPrice(row: PurchaseItemForm) {
  if (row.noCharge) return
  row.priceSource = '人工改价'
  row.priceExplanation = '保存后将冻结为人工改价，并保留当时的建议价格作为审计依据'
}

async function submit() {
  if (!canViewProductCost.value) {
    ElMessage.error('没有查看采购价/成本权限，不能保存采购订单')
    return
  }
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (totalAmount.value < 0) {
    ElMessage.warning('实际金额不能小于0')
    return
  }
  if (form.items.some((item) => !item.productId || !item.quantityUnit || !item.quantity || item.quantity <= 0
    || (!item.noCharge && item.unitPrice <= 0))) {
    ElMessage.warning('请检查明细商品、数量和采购单价')
    return
  }
  if (form.items.some((item) => Number(item.packageQuantity || 0) > 0 && !item.packageUnit)) {
    ElMessage.warning('填写预计包装数后必须选择包装单位')
    return
  }
  const supplierOfferViolations = form.items.flatMap((item, index) =>
    rowOfferViolations(item).map((message) => `第 ${index + 1} 行：${message}`),
  )
  if (supplierOfferViolations.length) {
    ElMessage.warning(supplierOfferViolations.join('；'))
    return
  }
  await Promise.all(form.items.map((item) => refreshPackagingPlan(item)))
  const invalidPlan = form.items.find((item) => item.planError || !item.packagingPlan)
  if (invalidPlan) {
    ElMessage.warning(invalidPlan.planError || '请完成包装换算')
    return
  }
  const { amendmentReason: _amendmentReason, ...order } = form
  createMutation.mutate({
    ...order,
    expectedDate: form.expectedDate || null,
    expectedWarehouseId: form.expectedWarehouseId || null,
    items: form.items.map(({
      fulfilledQuantity: _fulfilledQuantity,
      noCharge,
      priceUnitOptions,
      packageUnitOptions,
      priceLoading,
      priceSource,
      priceExplanation,
      priceWarnings,
      supplierOffer,
      supplierOfferEstimateApplied,
      packagingProfile,
      packagingPlan,
      planError,
      ...item
    }) => ({
      ...item,
      noCharge,
      unitPrice: noCharge ? 0 : item.unitPrice,
    })),
  })
}

async function loadDraft(id: number) {
  loadingDraft.value = true
  try {
    const detail = await api.purchaseOrderDetail(id)
    const allowed = isAmend.value ? ['ORDERED', 'PARTIAL_RECEIVED'].includes(detail.status) : detail.status === 'DRAFT'
    if (!allowed) {
      ElMessage.warning(isAmend.value ? '只有已订货或部分入库的采购订单可以变更' : '只有暂存的采购订单可以修改')
      await router.replace(`/purchase-orders/${id}`)
      return
    }

    form.supplierId = detail.supplierId
    form.businessDate = detail.businessDate
    form.expectedDate = detail.expectedDate || ''
    form.expectedWarehouseId = detail.expectedWarehouseId || undefined
    form.sourceType = detail.sourceType
    form.sourceNote = detail.sourceNote || ''
    form.discountRate = Number(detail.discountRate || 0)
    form.adjustmentAmount = Number(detail.adjustmentAmount || 0)
    priceIncludesTax.value = detail.priceIncludesTax
    taxRate.value = Number(detail.items.find((item) => item.status === 'ENABLED')?.taxRate || 0)
    form.memo = detail.memo || ''
    form.amendmentReason = ''
    form.items = detail.items.map((item) => ({
      ...makeItem(),
      itemId: isAmend.value ? item.id : undefined,
      fulfilledQuantity: Number(item.receivedQuantity || 0),
      productId: item.productId,
      quantity: Number(item.requestedQuantity ?? item.quantity),
      quantityUnit: item.requestedUnit || item.unit,
      actualBaseQuantity: Number(item.quantity),
      priceUnit: item.priceUnit,
      packageQuantity: Number(item.packageQuantity || 0),
      packageUnit: item.packageUnit || '',
      noCharge: item.noCharge,
      unitPrice: Number(item.unitPrice),
      priceSource: item.priceSourceName || '历史价格快照',
      priceExplanation: item.priceSnapshotJson || item.priceSourceName || '',
    }))

    for (let index = 0; index < form.items.length; index += 1) {
      const row = form.items[index]
      const item = detail.items[index]
      const frozenOffer = isAmend.value
        ? frozenSupplierOfferFromPriceSnapshot(item.priceSnapshotJson)
        : { offer: null, error: '' }
      const quantityUnit = item.requestedUnit || item.unit
      const priceUnit = item.priceUnit
      const packageUnit = item.packageUnit || ''
      const unitPrice = Number(item.unitPrice)
      await loadPackagingProfile(row)
      row.quantityUnit = quantityUnit
      row.actualBaseQuantity = requiresActualBaseQuantity(row.packagingProfile, quantityUnit)
        ? Number(item.quantity)
        : undefined
      try {
        await applyPurchaseOptions(row, priceUnit)
      } catch (error) {
        if (!frozenOffer.offer) throw error
        row.priceWarnings = [`当前供货建议读取失败，变更继续使用原订单冻结版本：${requestErrorMessage(error)}`]
      }
      if (frozenOffer.offer) {
        restoreFrozenSupplierOffer(row, frozenOffer.offer)
      }
      row.frozenSupplierOfferError = frozenOffer.error
      row.priceUnit = priceUnit
      row.packageUnit = packageUnit
      row.unitPrice = unitPrice
      row.priceSource = item.priceSourceName || '历史价格快照'
      row.priceExplanation = item.priceSnapshotJson || item.priceSourceName || ''
      await refreshPackagingPlan(row)
    }
  } finally {
    loadingDraft.value = false
  }
}

function makeItem(): PurchaseItemForm {
  return {
    itemId: undefined,
    fulfilledQuantity: 0,
    productId: undefined,
    quantity: undefined,
    quantityUnit: '',
    actualBaseQuantity: undefined,
    priceUnit: '',
    packageQuantity: 0,
    packageUnit: '',
    noCharge: false,
    unitPrice: 0,
    priceUnitOptions: [],
    packageUnitOptions: [],
    priceLoading: false,
    priceSource: '',
    priceExplanation: '',
    priceWarnings: [],
    supplierOffer: null,
    expectedSupplierOfferRevisionId: undefined,
    expectedSupplierOfferSnapshotHash: undefined,
    packagingVariantRevisionId: undefined,
    frozenSupplierOffer: undefined,
    frozenSupplierOfferError: '',
    supplierOfferEstimateApplied: false,
    packagingProfile: undefined,
    packagingPlan: undefined,
    expectedEffectiveRevisionId: undefined,
    expectedPackagingSnapshotHash: undefined,
    planError: '',
  }
}

function itemAmount(item: PurchaseItemForm) {
  if (item.noCharge) return 0
  const entered = Number(item.packagingPlan?.pricingQuantity || item.quantity || 0) * Number(item.unitPrice || 0)
  return grossFromEntered(entered, priceIncludesTax.value, taxRate.value)
}

async function loadPartnerTax(partnerId: number) {
  const partner = await api.partner(partnerId)
  priceIncludesTax.value = partner.supplierTaxIncluded
  taxRate.value = Number(partner.supplierTaxRate || 0)
}

function roundMoney(value: number) {
  return Math.round((Number(value || 0) + Number.EPSILON) * 100) / 100
}

function signedMoney(value: number) {
  const n = Number(value || 0)
  return `${n >= 0 ? '+' : '-'}${money(Math.abs(n))}`
}

function unitOptionLabel(item: ProductPurchaseUnitOption) {
  return item.unitName && item.unitName !== item.name ? `${item.name} / ${item.unitName}` : item.name
}

function primaryUnitName(options: ProductPurchaseUnitOption[], fallback = '') {
  return options.find((item) => item.primaryUnit)?.name || options[0]?.name || fallback
}

async function handleProductChange(row: PurchaseItemForm) {
  if (!row.productId) {
    resetPurchaseOptions(row)
    return
  }
  await loadPackagingProfile(row)
  await refreshPurchaseOptions(row)
  await refreshPackagingPlan(row)
}

async function handlePriceUnitChange(row: PurchaseItemForm) {
  if (!row.productId || !row.priceUnit) return
  await refreshPurchaseOptions(row, row.priceUnit)
  await refreshPackagingPlan(row)
}

async function handleQuantityChange(row: PurchaseItemForm) {
  if (row.productId) await refreshPurchaseOptions(row, row.priceUnit, true, true)
  await refreshPackagingPlan(row)
}

async function handleActualBaseQuantityChange(row: PurchaseItemForm) {
  row.supplierOfferEstimateApplied = false
  await refreshPackagingPlan(row)
  if (row.productId) await refreshPurchaseOptions(row, row.priceUnit, false, true)
}

async function refreshPurchaseOptions(
  row: PurchaseItemForm,
  priceUnit?: string,
  refreshEstimate = true,
  preserveCurrentPrice = false,
) {
  const productId = row.productId
  const currentPrice = preserveCurrentPrice
    ? {
        unitPrice: row.unitPrice,
        priceSource: row.priceSource,
        priceExplanation: row.priceExplanation,
      }
    : undefined
  try {
    const frozen = isAmend.value && row.itemId ? row.frozenSupplierOffer : undefined
    if (!frozen) {
      await applyPurchaseOptions(row, priceUnit, refreshEstimate)
      return
    }
    restoreFrozenSupplierOffer(row, frozen)
    if (refreshEstimate) applySupplierOfferEstimate(row)
  } finally {
    if (currentPrice && row.productId === productId) {
      row.unitPrice = currentPrice.unitPrice
      row.priceSource = currentPrice.priceSource
      row.priceExplanation = currentPrice.priceExplanation
    }
  }
}

async function applyPurchaseOptions(row: PurchaseItemForm, priceUnit?: string, refreshEstimate = true) {
  if (!row.productId) return
  const productId = row.productId
  const requestedUnit = priceUnit || row.priceUnit || undefined
  row.priceLoading = true
  try {
    const options = await api.productPurchaseOptions(productId, {
      supplierId: form.supplierId,
      priceUnit: requestedUnit,
      quantity: Number(row.packagingPlan?.pricingQuantity || row.quantity || 1),
      businessDate: form.businessDate,
    })
    if (row.productId !== productId) return
    applyOptionsToRow(row, options, requestedUnit)
    if (refreshEstimate) applySupplierOfferEstimate(row)
  } finally {
    if (row.productId === productId) row.priceLoading = false
  }
}

function applyOptionsToRow(row: PurchaseItemForm, options: ProductPurchaseOptions, requestedPriceUnit?: string) {
  const allowedPackageNames = row.packagingProfile?.rules
    .filter((item) => item.purchaseAllowed)
    .map((item) => item.parentUnit) ?? []
  row.packageUnitOptions = options.packageUnits
  row.priceUnitOptions = mergeUnitOptions(options.priceUnits, options.packageUnits, allowedPackageNames)
  const suggestedUnit = options.priceSuggestion.priceUnit || primaryUnitName(options.priceUnits, options.defaultPriceUnit)
  const selectedUnit = requestedPriceUnit && hasUnitOption(row.priceUnitOptions, requestedPriceUnit)
    ? requestedPriceUnit
    : suggestedUnit
  row.priceUnit = selectedUnit
  if (row.packageUnit && !options.packageUnits.some((item) => item.name === row.packageUnit)) row.packageUnit = ''
  row.priceSource = options.priceSuggestion.sourceName
  row.priceExplanation = options.priceSuggestion.explanation || options.priceSuggestion.sourceName
  row.priceWarnings = options.priceSuggestion.warnings || []
  const previousEstimate = row.supplierOfferEstimateApplied
  row.supplierOffer = options.supplierOffer
  row.expectedSupplierOfferRevisionId = options.supplierOffer?.supplierOfferRevisionId
  row.expectedSupplierOfferSnapshotHash = options.supplierOffer?.snapshotHash
  row.packagingVariantRevisionId = options.supplierOffer?.packagingVariantRevisionId ?? undefined
  if (options.supplierOffer) {
    if (!row.itemId) row.quantityUnit = options.supplierOffer.purchaseUnit
    if (!row.packageUnit && options.supplierOffer.packageUnit) row.packageUnit = options.supplierOffer.packageUnit
  } else {
    row.supplierOfferEstimateApplied = false
    if (previousEstimate && !requiresActualBaseQuantity(row.packagingProfile, row.quantityUnit)) {
      row.actualBaseQuantity = undefined
    }
  }
  if (!row.noCharge) {
    const suggestedPrice = Number(options.priceSuggestion.unitPrice || 0)
    row.unitPrice = convertUnitPrice(
      suggestedPrice,
      suggestedUnit,
      selectedUnit,
      [...row.priceUnitOptions, ...options.packageUnits],
    )
    if (suggestedUnit !== selectedUnit && row.unitPrice > 0) {
      row.priceSource = `${options.priceSuggestion.sourceName}（按${suggestedUnit}换算为${selectedUnit}）`
    }
  }
}

function resetPurchaseOptions(row: PurchaseItemForm) {
  row.priceUnit = ''
  row.packageUnit = ''
  row.unitPrice = 0
  row.priceUnitOptions = []
  row.packageUnitOptions = []
  row.priceSource = ''
  row.priceExplanation = ''
  row.priceWarnings = []
  row.supplierOffer = null
  row.expectedSupplierOfferRevisionId = undefined
  row.expectedSupplierOfferSnapshotHash = undefined
  row.packagingVariantRevisionId = undefined
  row.frozenSupplierOffer = undefined
  row.frozenSupplierOfferError = ''
  row.supplierOfferEstimateApplied = false
  row.priceLoading = false
  row.quantityUnit = ''
  row.actualBaseQuantity = undefined
  row.packagingProfile = undefined
  row.packagingPlan = undefined
  row.planError = ''
}

async function loadPackagingProfile(row: PurchaseItemForm) {
  if (!row.productId) return
  const productId = row.productId
  const profile = await api.packagingProfile(productId)
  if (row.productId !== productId) return
  row.packagingProfile = profile
  row.quantityUnit = defaultQuantityUnit(profile, 'PURCHASE')
  const configuredPackages = profile.rules
    .filter((item) => item.purchaseAllowed)
    .map((item) => item.parentUnit)
  if (configuredPackages.length) {
    const existingOptions = row.packageUnitOptions
    row.packageUnitOptions = [...new Set(configuredPackages)].map((name) =>
      existingOptions.find((item) => item.name === name)
      ?? { name, unitId: null, unitName: name, exchangeRate: null, primaryUnit: false },
    )
    row.priceUnitOptions = mergeUnitOptions(row.priceUnitOptions, row.packageUnitOptions, configuredPackages)
  }
  if (row.packageUnit && !row.packageUnitOptions.some((item) => item.name === row.packageUnit)) row.packageUnit = ''
}

async function handleQuantityUnitChange(row: PurchaseItemForm) {
  if (!requiresActualBaseQuantity(row.packagingProfile, row.quantityUnit)
    && !supplierOfferNeedsBaseQuantity(row)) {
    row.actualBaseQuantity = undefined
    row.supplierOfferEstimateApplied = false
  }
  await handleQuantityChange(row)
}

async function refreshPackagingPlan(row: PurchaseItemForm) {
  if (!row.productId || !row.quantityUnit || !row.quantity || row.quantity <= 0) {
    row.packagingPlan = undefined
    row.expectedEffectiveRevisionId = undefined
    row.planError = ''
    return
  }
  const productId = row.productId
  try {
    const request = {
      quantity: row.quantity,
      quantityUnit: row.quantityUnit,
      actualBaseQuantity: row.actualBaseQuantity,
      priceUnit: row.priceUnit || row.packagingProfile?.baseUnit,
      purpose: 'PURCHASE_ORDER',
      supplierOfferRevisionId: row.expectedSupplierOfferRevisionId,
      packagingVariantRevisionId: row.packagingVariantRevisionId,
    } as const
    const plan = isAmend.value && row.itemId
      ? await api.purchaseAmendmentPackagingPlan(editId.value, row.itemId, request)
      : await api.packagingPlan(productId, request)
    if (row.productId !== productId) return
    row.packagingPlan = plan
    row.expectedEffectiveRevisionId = plan.effectiveRevisionId ?? undefined
    row.expectedPackagingSnapshotHash = plan.snapshotHash || undefined
    row.planError = ''
  } catch (error) {
    row.packagingPlan = undefined
    row.expectedEffectiveRevisionId = undefined
    row.expectedPackagingSnapshotHash = undefined
    row.planError = requestErrorMessage(error)
  }
}

function requestErrorMessage(error: unknown) {
  const responseMessage = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  return responseMessage || (error instanceof Error ? error.message : '包装换算失败')
}

function purchaseQuantityUnits(row: PurchaseItemForm) {
  return [...new Set([
    ...packagingUnits(row.packagingProfile, 'PURCHASE'),
    ...row.packageUnitOptions.map((item) => item.name),
    row.supplierOffer?.purchaseUnit || '',
  ].filter(Boolean))]
}

function supplierOfferNeedsBaseQuantity(row: PurchaseItemForm) {
  const offer = row.supplierOffer
  if (!offer || !sameUnit(row.quantityUnit, offer.purchaseUnit)) return false
  return offer.conversionMode === 'ACTUAL_WEIGHT' || offer.conversionMode === 'THEORETICAL'
}

function applySupplierOfferEstimate(row: PurchaseItemForm) {
  const offer = row.supplierOffer
  const quantity = Number(row.quantity || 0)
  if (!offer || quantity <= 0 || !sameUnit(row.quantityUnit, offer.purchaseUnit)) {
    row.supplierOfferEstimateApplied = false
    return
  }
  if (!supplierOfferNeedsBaseQuantity(row)) {
    row.supplierOfferEstimateApplied = false
    return
  }
  const reference = Number(offer.referenceBaseQuantity || 0)
  if (reference <= 0) {
    row.supplierOfferEstimateApplied = false
    return
  }
  row.actualBaseQuantity = roundQuantity(quantity * reference)
  row.supplierOfferEstimateApplied = true
}

function rowOfferViolations(row: PurchaseItemForm) {
  const violations: string[] = []
  if (row.frozenSupplierOfferError) violations.push(row.frozenSupplierOfferError)
  const offer = row.supplierOffer
  if (!offer) return violations
  const quantity = Number(row.quantity || 0)
  if (row.quantityUnit && !sameUnit(row.quantityUnit, offer.purchaseUnit)) {
    violations.push(`当前供货版本要求按“${offer.purchaseUnit}”采购`)
  }
  const minimum = Number(offer.minimumOrderQuantity || 0)
  if (quantity > 0 && minimum > 0 && quantity + 1e-9 < minimum) {
    violations.push(`数量低于最小起订量 ${minimum}${offer.purchaseUnit}`)
  }
  const multiple = Number(offer.orderMultiple || 0)
  if (quantity > 0 && multiple > 0 && !isOrderMultiple(quantity, multiple)) {
    violations.push(`数量须为 ${multiple}${offer.purchaseUnit} 的整数倍`)
  }
  if (offer.priceIncludesTax !== priceIncludesTax.value
    || Math.abs(Number(offer.taxRate || 0) - Number(taxRate.value || 0)) > 0.000001) {
    violations.push('供货报价的含税口径或税率与供应商税务主数据不一致')
  }
  if (offer.currencyCode && offer.currencyCode !== 'CNY') {
    violations.push(`当前采购订单仅支持人民币，不能直接使用 ${offer.currencyCode} 报价`)
  }
  if (form.businessDate && (
    form.businessDate < offer.effectiveFrom
    || Boolean(offer.effectiveTo && form.businessDate > offer.effectiveTo)
  )) {
    violations.push(`订单日期不在冻结供货版本有效期 ${offer.effectiveFrom} 至 ${offer.effectiveTo || '长期'} 内`)
  }
  return violations
}

function frozenSupplierOfferFromPriceSnapshot(snapshotJson?: string): {
  offer: ProductSupplierOfferContext | null
  error: string
} {
  if (!snapshotJson?.trim()) return { offer: null, error: '' }
  try {
    const root = JSON.parse(snapshotJson) as {
      supplierOfferSnapshot?: Partial<ProductSupplierOfferContext> | null
      supplierOfferSnapshotHash?: string
    }
    if (!root.supplierOfferSnapshot) return { offer: null, error: '' }
    const frozen = root.supplierOfferSnapshot
    const snapshotHash = String(root.supplierOfferSnapshotHash || '').trim()
    if (!Number(frozen.supplierOfferId)
      || !Number(frozen.supplierOfferRevisionId)
      || !Number(frozen.supplierId)
      || !String(frozen.purchaseUnit || '').trim()
      || !String(frozen.priceUnit || '').trim()
      || !snapshotHash) {
      return { offer: null, error: '原订单供应商供货快照不完整，禁止继续变更' }
    }
    const snapshotText = JSON.stringify(frozen)
    return {
      offer: {
        ...(frozen as ProductSupplierOfferContext),
        snapshotJson: snapshotText,
        snapshotHash,
      },
      error: '',
    }
  } catch {
    return { offer: null, error: '原订单供应商供货快照无法解析，禁止继续变更' }
  }
}

function restoreFrozenSupplierOffer(row: PurchaseItemForm, offer: ProductSupplierOfferContext) {
  row.frozenSupplierOffer = offer
  row.supplierOffer = offer
  row.expectedSupplierOfferRevisionId = offer.supplierOfferRevisionId
  row.expectedSupplierOfferSnapshotHash = offer.snapshotHash
  row.packagingVariantRevisionId = offer.packagingVariantRevisionId ?? undefined
  row.quantityUnit = row.quantityUnit || offer.purchaseUnit
  row.priceUnit = row.priceUnit || offer.priceUnit
  row.packageUnit = row.packageUnit || offer.packageUnit || ''
  row.priceUnitOptions = ensurePurchaseUnitOption(row.priceUnitOptions, offer.priceUnit)
  row.packageUnitOptions = ensurePurchaseUnitOption(row.packageUnitOptions, offer.packageUnit)
}

function ensurePurchaseUnitOption(options: ProductPurchaseUnitOption[], unitName?: string | null) {
  const name = String(unitName || '').trim()
  if (!name || options.some((item) => item.name === name)) return options
  return [...options, { name, unitId: null, unitName: name, exchangeRate: null, primaryUnit: false }]
}

function priceTooltip(row: PurchaseItemForm) {
  return [...new Set([
    row.priceExplanation || row.priceSource,
    ...row.priceWarnings,
    ...rowOfferViolations(row),
  ].filter(Boolean))].join('；')
}

function sameUnit(left?: string | null, right?: string | null) {
  return String(left || '').trim().toLocaleLowerCase() === String(right || '').trim().toLocaleLowerCase()
}

function isOrderMultiple(quantity: number, multiple: number) {
  const quotient = quantity / multiple
  return Math.abs(quotient - Math.round(quotient)) <= 1e-8
}

function isSupplierOfferConflict(message: string) {
  return /供货档案|供货条款|供货版本|包装方案版本|重新核对起订量|报价.*税率/.test(message)
}

async function refreshSupplierOfferContexts() {
  for (const row of form.items) {
    if (!row.productId) continue
    await refreshPurchaseOptions(row, row.priceUnit)
    await refreshPackagingPlan(row)
  }
}
</script>

<style scoped>
.price-source{margin-top:4px;color:var(--el-color-success);font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.price-source.warning{color:var(--el-color-warning)}
.amend-alert { margin: 0 20px 16px; }
.tax-contract-field { display: flex; align-items: center; gap: 8px; white-space: nowrap; }
.inventory-quantity-field {
  display: flex;
  align-items: center;
  gap: 8px;
}

.inventory-quantity-stack,
.supplier-offer-card {
  display: grid;
  gap: 4px;
}

.inventory-quantity-field :deep(.el-input-number) {
  flex: 1;
  min-width: 0;
}

.estimate-note {
  color: var(--el-color-warning-dark-2);
  line-height: 1.35;
  text-align: left;
}

.supplier-offer-card > div {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.supplier-offer-card span {
  color: var(--el-text-color-regular);
  font-size: 12px;
  line-height: 1.4;
}

.offer-violations {
  padding-left: 16px;
  margin: 4px 0 0;
  color: var(--el-color-danger);
  font-size: 12px;
  line-height: 1.35;
  text-align: left;
  white-space: normal;
}

.inventory-quantity-unit {
  flex: none;
  min-width: 24px;
  color: var(--el-text-color-regular);
  text-align: left;
}
</style>
