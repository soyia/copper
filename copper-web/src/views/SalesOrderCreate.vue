<template>
  <div class="page">
    <div class="panel">
      <PageHeader :title="isAmend ? '变更销售订单' : isEdit ? '编辑销售订单' : '新建销售订单'" :back-to="documentId ? `/sales-orders/${documentId}` : '/sales-orders'">
        <el-button type="primary" :loading="saving" @click="submit">{{ isAmend ? '确认变更' : '保存草稿' }}</el-button>
      </PageHeader>
      <el-alert v-if="isAmend" type="warning" :closable="false" show-icon class="amend-alert" title="变更会先冲回当前销售承诺，再按新版本重新过账；已实际出库的数量和商品不能减少或替换。" />
      <el-alert v-else-if="!isEdit" type="info" :closable="false" show-icon class="amend-alert" title="直接销售通道：电话、微信、柜台、补录等业务可直接保存订单，不要求先建立线索、商机或报价。" />
      <div class="panel-body">
        <el-form ref="formRef" class="entry-form" :model="form" :rules="rules" label-width="92px">
          <el-row :gutter="16">
            <el-col :span="8">
              <el-form-item label="客户" prop="customerId">
                <PartnerRemoteSelect v-model="form.customerId" type="CUSTOMER" :disabled="isAmend" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="订单标题"><el-input v-model="form.title" /></el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="订单日期" prop="businessDate"><el-date-picker v-model="form.businessDate" value-format="YYYY-MM-DD" /></el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="计划交付" prop="deliveryPlanDate"><el-date-picker v-model="form.deliveryPlanDate" value-format="YYYY-MM-DD" placeholder="确认前必填" /></el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="履约仓库" prop="fulfillmentWarehouseId">
                <el-select v-model="form.fulfillmentWarehouseId" filterable clearable placeholder="确认前必填">
                  <el-option v-for="item in enabledWarehouses" :key="item.id" :label="item.name" :value="item.id" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="价格口径">
                <div class="tax-contract-field"><el-tag effect="plain">{{ priceIncludesTax ? '含税价' : '未税价' }}</el-tag><span>{{ taxRate }}%</span><span class="muted">来自客户设置，保存后冻结</span></div>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="收货地址" prop="addressId">
                <el-select v-model="form.addressId" filterable :disabled="!form.customerId" placeholder="请选择收货地址">
                  <el-option v-for="item in enabledAddresses" :key="item.id" :label="addressLabel(item)" :value="item.id" />
                  <template #footer>
                    <div class="address-select-footer">
                      <el-button text type="primary" :disabled="!form.customerId" @click.stop="openAddressCreate">＋ 地址</el-button>
                    </div>
                  </template>
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="折扣%"><el-input-number v-model="form.discountRate" :min="0" :max="100" :precision="2" :controls="false" /></el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="调整金额"><el-input-number v-model="form.adjustmentAmount" :precision="2" :controls="false" /></el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="收款条件">
                <el-select v-model="form.paymentTerm">
                  <el-option label="确认后收款" value="POSTPAID" />
                  <el-option label="需预付款" value="PREPAYMENT_REQUIRED" />
                  <el-option label="全额预付" value="FULL_PREPAYMENT" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="发货前到账">
                <el-select v-model="form.shipmentPaymentPolicy" :disabled="form.paymentTerm === 'FULL_PREPAYMENT'">
                  <el-option label="不校验到账金额（可零/部分/全额）" value="NONE" />
                  <el-option label="本次发货控制金额须全额到账" value="SHIPMENT_PAID" />
                  <el-option label="整张销售订单须全额到账" value="ORDER_PAID" />
                </el-select>
              </el-form-item>
            </el-col>
            <template v-if="form.paymentTerm === 'PREPAYMENT_REQUIRED'">
              <el-col :span="8">
                <el-form-item label="预付方式">
                  <el-select v-model="form.depositMode">
                    <el-option label="固定金额" value="AMOUNT" />
                    <el-option label="订单比例" value="RATE" />
                  </el-select>
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item :label="form.depositMode === 'RATE' ? '预付比例%' : '要求预付'">
                  <el-input v-if="form.depositMode === 'RATE'" v-model="form.depositRate" inputmode="decimal" />
                  <el-input v-else v-model="form.requiredDepositAmount" inputmode="decimal" />
                </el-form-item>
              </el-col>
            </template>
            <el-col v-if="form.paymentTerm !== 'POSTPAID'" :span="8">
              <el-form-item label="预付门槛"><span class="payment-term-preview">{{ money(requiredDepositPreview) }}</span></el-form-item>
            </el-col>
            <el-col :span="24">
              <el-form-item label="备注"><el-input v-model="form.memo" /></el-form-item>
            </el-col>
            <el-col v-if="isAmend" :span="24">
              <el-form-item label="变更原因" prop="amendmentReason"><el-input v-model="form.amendmentReason" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="必填：说明数量、价格、交付、收款条件或其他销售承诺变化原因" /></el-form-item>
            </el-col>
          </el-row>
        </el-form>
      </div>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">销售商品明细</div>
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
      <el-table :data="form.items" class="data-table" size="small" border show-overflow-tooltip max-height="calc(100vh - 475px)">
        <el-table-column width="280" fixed="left">
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
        <el-table-column width="160">
          <template #header>销售数量 <span class="table-required-mark">*</span></template>
          <template #default="{ row }"><el-input-number v-model="row.quantity" class="sales-quantity-input" :min="0.000001" :precision="6" :controls="false" @change="handleQuantityChange(row)" /></template>
        </el-table-column>
        <el-table-column width="120">
          <template #header>数量单位 <span class="table-required-mark">*</span></template>
          <template #default="{ row }">
            <el-select v-model="row.quantityUnit" filterable :disabled="!row.productId" @change="handleQuantityUnitChange(row)">
              <el-option v-for="item in packagingUnits(row.packagingProfile, 'SALES')" :key="item" :label="item" :value="item" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="预计库存数量" width="160">
          <template #default="{ row }">
            <div class="inventory-quantity-field">
              <el-input
                v-if="requiresActualBaseQuantity(row.packagingProfile, row.quantityUnit)"
                v-model="row.actualBaseQuantity"
                placeholder="请填写"
                inputmode="decimal"
                @change="handleQuantityChange(row)"
              />
              <span v-else class="inventory-quantity-value">{{ quantity(row.packagingPlan?.baseQuantity) }}</span>
              <span class="inventory-quantity-unit">{{ row.packagingProfile?.baseUnit || '-' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column width="130">
          <template #header>计价单位 <span class="table-required-mark">*</span></template>
          <template #default="{ row }">
            <el-select v-model="row.priceUnit" filterable :disabled="!row.productId || row.unitLoading" @change="handlePriceUnitChange(row)">
              <el-option v-for="item in row.priceUnitOptions" :key="item.name" :label="unitOptionLabel(item)" :value="item.name" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="预计包装数" width="130">
          <template #default="{ row }">
            <el-input-number v-model="row.packageQuantity" :min="0" :precision="6" :controls="false" />
          </template>
        </el-table-column>
        <el-table-column label="包装单位" width="130">
          <template #default="{ row }">
            <el-select v-model="row.packageUnit" clearable filterable :disabled="!row.productId || row.packageUnitOptions.length === 0">
              <el-option v-for="item in row.packageUnitOptions" :key="item.name" :label="unitOptionLabel(item)" :value="item.name" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="赠品" width="90"><template #default="{ row }"><el-switch v-model="row.noCharge" @change="handleNoChargeChange(row)" /></template></el-table-column>
        <el-table-column width="205">
          <template #header>单价 / 来源 <span class="table-required-mark">*</span></template>
          <template #default="{ row }"><el-input v-model="row.unitPrice" inputmode="decimal" :disabled="row.unitLoading || row.noCharge" @change="markManualPrice(row)" /><el-tooltip :content="row.priceExplanation || row.priceSource" placement="top"><div class="price-source" :class="{ warning: row.priceWarnings.length }">{{ row.priceSource || '手工输入' }}</div></el-tooltip></template>
        </el-table-column>
        <el-table-column label="金额" width="140" align="right"><template #default="{ row }">{{ money(itemAmount(row)) }}</template></el-table-column>
        <el-table-column label="包装/发货建议" min-width="220">
          <template #default="{ row }"><span :class="row.planError ? 'text-danger' : 'muted'">{{ row.planError || row.packagingPlan?.suggestion || '选择商品后计算' }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right"><template #default="{ row, $index }"><el-button text type="danger" :disabled="form.items.length <= 1 || Number(row.fulfilledQuantity || 0) > 0" @click="removeItem($index)">删除</el-button></template></el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="addressDialogVisible" title="新增收货地址" width="560px" append-to-body>
      <el-form ref="addressFormRef" :model="addressForm" :rules="addressRules" label-width="92px">
        <el-form-item label="地址名称"><el-input v-model="addressForm.name" placeholder="如：仓库、工厂、门店" /></el-form-item>
        <el-form-item label="联系人" prop="contactPerson"><el-input v-model="addressForm.contactPerson" /></el-form-item>
        <el-form-item label="联系电话"><el-input v-model="addressForm.phone" /></el-form-item>
        <el-form-item label="详细地址" prop="detail"><el-input v-model="addressForm.detail" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="设为默认"><el-switch v-model="addressForm.firstChoice" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="addressForm.memo" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addressDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="addressSaving" @click="saveAddress">保存并选中</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import PageHeader from '@/components/PageHeader.vue'
import {
  api,
  type Address,
  type PackagingPlan,
  type PackagingProfile,
  type ProductPurchaseOptions,
  type ProductPurchaseUnitOption,
} from '@/api/client'
import { currentDateText, money, quantity } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import { defaultQuantityUnit, packagingUnits, requiresActualBaseQuantity } from '@/utils/packaging'
import { convertUnitPrice, hasUnitOption, mergeUnitOptions } from '@/utils/unitPricing'
import { isTransactionSelectableProduct, transactionProductOptionLabel, transactionProductOptions } from '@/utils/productDisplay'
import { useSessionDraft } from '@/composables/useSessionDraft'
import { useAppStore } from '@/stores/app'
import { workspaceRouteKey } from '@/stores/workspaceTabs'
import { partnerAddressOptionLabel, receivingAddressOptions } from '@/utils/partnerAddresses'
import { grossFromEntered, splitGross } from '@/utils/taxPricing'
import { promptSalesRiskOverrideFromBlockedMessage } from '@/utils/salesRisk'

const router = useRouter()
const route = useRoute()
const queryClient = useQueryClient()
const appStore = useAppStore()
const today = currentDateText()
const requestedCustomerId = Number(route.query.customerId || 0)
const initialCustomerId = Number.isSafeInteger(requestedCustomerId) && requestedCustomerId > 0 ? requestedCustomerId : undefined
const formRef = ref<FormInstance>()
const isEdit = computed(() => route.name === 'sales-order-edit')
const isAmend = computed(() => route.name === 'sales-order-amend')
const documentId = computed(() => isEdit.value || isAmend.value ? Number(route.params.id || 0) : 0)
const editId = documentId
const loadingDraft = ref(false)
const priceIncludesTax = ref(true)
const taxRate = ref(0)
const draftWorkspaceKey = workspaceRouteKey(route)
const draftStorageKey = `copper-session-draft:v1:${appStore.session?.userId || 0}:sales-order:${isAmend.value ? 'amend-' : ''}${editId.value || 'new'}`
const addressDialogVisible = ref(false)
const addressSaving = ref(false)
const addressFormRef = ref<FormInstance>()
const addressForm = reactive<Partial<Address>>({
  partnerId: undefined,
  name: '',
  addressType: 'RECEIVING',
  detail: '',
  contactPerson: '',
  phone: '',
  firstChoice: false,
  memo: '',
  status: 'ENABLED',
})
const addressRules: FormRules = {
  contactPerson: [{ required: true, message: '请填写联系人', trigger: 'blur' }],
  detail: [{ required: true, message: '请填写详细地址', trigger: 'blur' }],
}

type SalesItemForm = {
  itemId?: number
  fulfilledQuantity?: number
  productId?: number
  quantity?: number
  quantityUnit: string
  actualBaseQuantity?: number | string
  priceUnit: string
  packageQuantity: number
  packageUnit: string
  noCharge: boolean
  unitPrice: number | string
  priceSource: string
  priceExplanation: string
  priceWarnings: string[]
  priceUnitOptions: ProductPurchaseUnitOption[]
  packageUnitOptions: ProductPurchaseUnitOption[]
  unitLoading: boolean
  packagingProfile?: PackagingProfile
  packagingPlan?: PackagingPlan
  expectedEffectiveRevisionId?: number
  expectedPackagingSnapshotHash?: string
  planError: string
}

const form = reactive({
  customerId: initialCustomerId as number | undefined,
  addressId: undefined as number | undefined,
  title: '',
  businessDate: today,
  deliveryPlanDate: '',
  fulfillmentWarehouseId: undefined as number | undefined,
  discountRate: 0,
  adjustmentAmount: 0,
  paymentTerm: 'POSTPAID',
  shipmentPaymentPolicy: 'NONE',
  depositMode: 'AMOUNT',
  depositRate: 30 as number | string,
  requiredDepositAmount: '' as number | string,
  memo: '',
  amendmentReason: '',
  items: [makeItem()],
})
const rules: FormRules = {
  customerId: [{ required: true, message: '请选择客户', trigger: 'change' }],
  addressId: [{ required: true, message: '请选择收货地址', trigger: 'change' }],
  businessDate: [{ required: true, message: '请选择订单日期', trigger: 'change' }],
  deliveryPlanDate: [{ validator: (_rule, value, callback) => isAmend.value && !value ? callback(new Error('变更已确认订单必须填写计划交付日期')) : callback(), trigger: 'change' }],
  fulfillmentWarehouseId: [{ validator: (_rule, value, callback) => isAmend.value && !value ? callback(new Error('变更已确认订单必须选择履约仓库')) : callback(), trigger: 'change' }],
  amendmentReason: [{ validator: (_rule, value, callback) => isAmend.value && !String(value || '').trim() ? callback(new Error('请填写变更原因')) : callback(), trigger: 'blur' }],
}

const addressCustomerId = computed(() => form.customerId ?? 0)
const { data: addresses, refetch: refetchAddresses } = useQuery({
  queryKey: computed(() => ['addresses', addressCustomerId.value]),
  queryFn: () => (addressCustomerId.value ? api.addresses(addressCustomerId.value) : Promise.resolve([])),
})
const enabledAddresses = computed(() => receivingAddressOptions(addresses.value, form.addressId))
const { data: products } = useQuery({ queryKey: ['products'], queryFn: api.products })
const { data: warehouses } = useQuery({ queryKey: ['warehouses'], queryFn: api.warehouses })
const enabledWarehouses = computed(() => (warehouses.value || []).filter((item) => item.status === 'ENABLED'))

const originalAmount = computed(() => roundMoney(form.items.reduce((sum, item) => sum + itemAmount(item), 0)))
const discountAmount = computed(() => roundMoney(originalAmount.value * Number(form.discountRate || 0) / 100))
const totalAmount = computed(() => roundMoney(originalAmount.value - discountAmount.value + Number(form.adjustmentAmount || 0)))
const taxPreview = computed(() => splitGross(totalAmount.value, taxRate.value))
const requiredDepositPreview = computed(() => {
  if (form.paymentTerm === 'FULL_PREPAYMENT') return totalAmount.value
  if (form.paymentTerm !== 'PREPAYMENT_REQUIRED') return 0
  if (form.depositMode === 'RATE') return roundMoney(totalAmount.value * Number(form.depositRate || 0) / 100)
  return roundMoney(Number(form.requiredDepositAmount || 0))
})

const { clearDraft } = useSessionDraft({
  storageKey: () => draftStorageKey,
  snapshot: () => JSON.parse(JSON.stringify(form)) as typeof form,
  restore: (draft) => {
    Object.assign(form, draft)
    form.items = draft.items?.length ? draft.items.map((item) => ({ ...makeItem(), ...item, unitLoading: false })) : [makeItem()]
  },
  ready: computed(() => !loadingDraft.value),
  label: '销售订单',
  confirmRestore: isEdit.value || isAmend.value,
  workspaceKey: draftWorkspaceKey,
})

watch(
  () => form.paymentTerm,
  (value) => {
    if (value === 'FULL_PREPAYMENT') form.shipmentPaymentPolicy = 'ORDER_PAID'
  },
)

watch(
  () => form.customerId,
  () => {
    if (loadingDraft.value) return
    if (form.customerId && !isEdit.value && !isAmend.value) void loadPartnerTax(form.customerId)
    form.addressId = undefined
    for (const item of form.items) {
      if (item.productId) void applyProductUnitOptions(item, item.priceUnit).then(() => refreshPackagingPlan(item))
    }
  },
  { immediate: true },
)

watch(() => form.businessDate, () => {
  if (loadingDraft.value) return
  for (const item of form.items) if (item.productId) void applyProductUnitOptions(item, item.priceUnit)
})

watch(
  enabledAddresses,
  (items) => {
    if (loadingDraft.value || !form.customerId || form.addressId) return
    form.addressId = items.find((item) => item.firstChoice)?.id
  },
)

const createMutation = useMutation({
  mutationFn: (payload: { data: unknown; riskOverrideReason?: string }) => isAmend.value
    ? api.amendSalesOrder(editId.value, form.amendmentReason.trim(), payload.data, payload.riskOverrideReason)
    : isEdit.value ? api.updateSalesOrder(editId.value, payload.data) : api.createSalesOrder(payload.data),
  onSuccess: async (result) => {
    clearDraft()
    ElMessage.success(isAmend.value ? '销售订单变更已确认并重新过账' : '销售订单草稿已保存')
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['sales-orders'] }),
      queryClient.invalidateQueries({ queryKey: ['sales-order-detail', result.id] }),
      queryClient.invalidateQueries({ queryKey: ['receivables'] }),
      queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
      queryClient.invalidateQueries({ queryKey: ['business-postings'] }),
      queryClient.invalidateQueries({ queryKey: ['business-order-revisions', 'SALES_ORDER', result.id] }),
      queryClient.invalidateQueries({ queryKey: ['sales-risk', 'SALES_ORDER', result.id] }),
      queryClient.invalidateQueries({ queryKey: ['sales-risk-history', 'SALES_ORDER', result.id] }),
      queryClient.invalidateQueries({ queryKey: ['sales-order-atp', result.id] }),
      queryClient.invalidateQueries({ queryKey: ['sales-order-atp-history', result.id] }),
      queryClient.invalidateQueries({ queryKey: ['inventory-atp'] }),
    ])
    router.push(`/sales-orders/${result.id}`)
  },
  onError: async (error, payload) => {
    const message = error instanceof Error ? error.message : '销售订单保存失败'
    if (isAmend.value && !payload.riskOverrideReason && message.includes('销售风险控制未通过')) {
      if (!appStore.hasPermission('sales.risk.override')) {
        ElMessage.error(`${message}；当前账号无权授权，请联系老板处理`)
        return
      }
      const reason = await promptSalesRiskOverrideFromBlockedMessage(message, '订单变更')
      if (reason) createMutation.mutate({ ...payload, riskOverrideReason: reason })
      return
    }
    ElMessage.error(message)
  },
})
const saving = computed(() => createMutation.isPending.value || loadingDraft.value)

watch(
  () => [route.name, route.params.id] as const,
  () => {
    if (isEdit.value || isAmend.value) void loadDraft(editId.value)
  },
  { immediate: true },
)

function addressLabel(item: Address) {
  return partnerAddressOptionLabel(item)
}

function openAddressCreate() {
  if (!form.customerId) {
    ElMessage.warning('请先选择客户')
    return
  }
  Object.assign(addressForm, {
    partnerId: form.customerId,
    name: '',
    addressType: 'RECEIVING',
    provinceCode: '',
    cityCode: '',
    districtCode: '',
    detail: '',
    contactPerson: '',
    phone: '',
    firstChoice: enabledAddresses.value.length === 0,
    memo: '',
    status: 'ENABLED',
  })
  addressFormRef.value?.clearValidate()
  addressDialogVisible.value = true
}

async function saveAddress() {
  const valid = await addressFormRef.value?.validate().catch(() => false)
  if (!valid || !form.customerId) return
  addressSaving.value = true
  try {
    const result = await api.createAddress({ ...addressForm, partnerId: form.customerId })
    await refetchAddresses()
    form.addressId = result.id
    addressDialogVisible.value = false
    formRef.value?.clearValidate('addressId')
    ElMessage.success('收货地址已保存并选中')
  } finally {
    addressSaving.value = false
  }
}

function addItem() {
  form.items.push(makeItem())
}

function removeItem(index: number) {
  if (form.items.length > 1) form.items.splice(index, 1)
}

function handleNoChargeChange(row: SalesItemForm) {
  if (row.noCharge) {
    row.unitPrice = 0
    row.priceSource = '赠品零价'
    row.priceExplanation = '赠品不计销售价'
    return
  }
  if (row.productId) void applyProductUnitOptions(row, row.priceUnit)
}

function markManualPrice(row: SalesItemForm) {
  if (row.noCharge) return
  row.priceSource = '人工改价'
  row.priceExplanation = '保存后将冻结为人工改价，并保留当时的建议价格作为审计依据'
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (totalAmount.value < 0) {
    ElMessage.warning('实际金额不能小于0')
    return
  }
  if (form.paymentTerm !== 'POSTPAID' && (requiredDepositPreview.value <= 0 || requiredDepositPreview.value > totalAmount.value)) {
    ElMessage.warning('预付款门槛必须大于0且不能超过订单实际金额')
    return
  }
  if (form.items.some((item) => !item.productId || !item.quantityUnit || !item.quantity || item.quantity <= 0
    || (!item.noCharge && Number(item.unitPrice) <= 0))) {
    ElMessage.warning('请检查明细商品、数量和单价')
    return
  }
  if (form.items.some((item) => Number(item.packageQuantity || 0) > 0 && !item.packageUnit)) {
    ElMessage.warning('填写预计包装数后必须选择包装单位')
    return
  }
  await Promise.all(form.items.map((item) => refreshPackagingPlan(item)))
  const invalidPlan = form.items.find((item) => item.planError || !item.packagingPlan)
  if (invalidPlan) {
    ElMessage.warning(invalidPlan.planError || '请完成包装换算')
    return
  }
  const { amendmentReason: _amendmentReason, ...order } = form
  createMutation.mutate({ data: {
    ...order,
    depositRate: Number(form.depositRate || 0),
    requiredDepositAmount: Number(form.requiredDepositAmount || 0),
    deliveryPlanDate: form.deliveryPlanDate || null,
    fulfillmentWarehouseId: form.fulfillmentWarehouseId || null,
    items: form.items.map(({ fulfilledQuantity: _fulfilledQuantity, priceSource, priceExplanation, priceWarnings, priceUnitOptions, packageUnitOptions, unitLoading, packagingProfile, packagingPlan, planError, ...item }) => ({
      ...item,
      actualBaseQuantity: item.actualBaseQuantity === '' || item.actualBaseQuantity == null ? undefined : Number(item.actualBaseQuantity),
      unitPrice: Number(item.unitPrice || 0),
    })),
  } })
}

async function loadDraft(id: number) {
  loadingDraft.value = true
  try {
    const detail = await api.salesOrderDetail(id)
    const allowed = isAmend.value ? ['OPEN', 'PARTIAL_SHIPPED'].includes(detail.status) : detail.status === 'DRAFT'
    if (!allowed) {
      ElMessage.warning(isAmend.value ? '只有待发货或部分发货的销售订单可以变更' : '只有暂存的销售订单可以修改')
      await router.replace(`/sales-orders/${id}`)
      return
    }
    form.customerId = detail.customerId
    form.addressId = detail.addressId || undefined
    form.title = detail.title || ''
    form.businessDate = detail.businessDate
    form.deliveryPlanDate = detail.deliveryPlanDate || ''
    form.fulfillmentWarehouseId = detail.fulfillmentWarehouseId || undefined
    form.discountRate = Number(detail.discountRate || 0)
    form.adjustmentAmount = Number(detail.adjustmentAmount || 0)
    priceIncludesTax.value = detail.priceIncludesTax
    taxRate.value = Number(detail.items.find((item) => item.status === 'ENABLED')?.taxRate || 0)
    form.paymentTerm = detail.paymentTerm || 'POSTPAID'
    form.shipmentPaymentPolicy = detail.shipmentPaymentPolicy || (detail.paymentTerm === 'FULL_PREPAYMENT' ? 'ORDER_PAID' : 'NONE')
    form.depositMode = detail.depositMode || 'AMOUNT'
    form.depositRate = Number(detail.depositRate || 0)
    form.requiredDepositAmount = Number(detail.requiredDepositAmount || 0)
    form.memo = detail.memo || ''
    form.amendmentReason = ''
    form.items = detail.items.map((item) => ({
      ...makeItem(),
      itemId: isAmend.value ? item.id : undefined,
      fulfilledQuantity: Number(item.shippedQuantity || 0),
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
      const quantityUnit = item.requestedUnit || item.unit
      const priceUnit = item.priceUnit
      const packageUnit = item.packageUnit || ''
      const unitPrice = Number(item.unitPrice)
      await loadPackagingProfile(row)
      row.quantityUnit = quantityUnit
      row.actualBaseQuantity = requiresActualBaseQuantity(row.packagingProfile, quantityUnit)
        ? Number(item.quantity)
        : undefined
      await applyProductUnitOptions(row, priceUnit)
      row.priceUnit = priceUnit
      row.packageUnit = packageUnit
      row.unitPrice = unitPrice
      row.priceSource = item.priceSourceName || '历史价格快照'
      row.priceExplanation = item.priceSnapshotJson || item.priceSourceName || ''
      row.noCharge = item.noCharge
      await refreshPackagingPlan(row)
    }
  } finally {
    loadingDraft.value = false
  }
}

function makeItem(): SalesItemForm {
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
    priceSource: '',
    priceExplanation: '',
    priceWarnings: [],
    priceUnitOptions: [],
    packageUnitOptions: [],
    unitLoading: false,
    packagingProfile: undefined,
    packagingPlan: undefined,
    expectedEffectiveRevisionId: undefined,
    expectedPackagingSnapshotHash: undefined,
    planError: '',
  }
}

function unitOptionLabel(item: ProductPurchaseUnitOption) {
  return item.unitName && item.unitName !== item.name ? `${item.name} / ${item.unitName}` : item.name
}

function primaryUnitName(options: ProductPurchaseUnitOption[], fallback = '') {
  return options.find((item) => item.primaryUnit)?.name || options[0]?.name || fallback
}

async function handleProductChange(row: SalesItemForm) {
  if (!row.productId) {
    resetProductUnitOptions(row)
    return
  }
  await applyProductUnitOptions(row)
  await loadPackagingProfile(row)
  await refreshPackagingPlan(row)
}

async function handlePriceUnitChange(row: SalesItemForm) {
  if (!row.productId || !row.priceUnit) return
  await refreshPackagingPlan(row)
  await applyProductUnitOptions(row, row.priceUnit)
}

async function handleQuantityChange(row: SalesItemForm) {
  await refreshPackagingPlan(row)
  if (row.productId) await applyProductUnitOptions(row, row.priceUnit, true)
}

async function applyProductUnitOptions(row: SalesItemForm, priceUnit?: string, preserveCurrentPrice = false) {
  if (!row.productId) return
  const productId = row.productId
  const requestedUnit = priceUnit || row.priceUnit || undefined
  const currentPrice = preserveCurrentPrice
    ? {
        unitPrice: row.unitPrice,
        priceSource: row.priceSource,
        priceExplanation: row.priceExplanation,
      }
    : undefined
  row.unitLoading = true
  try {
    const options = await api.productUnitOptions(productId, {
      partnerId: form.customerId,
      partnerType: 'CUSTOMER',
      priceUnit: requestedUnit,
      quantity: Number(row.packagingPlan?.pricingQuantity || row.quantity || 1),
      businessDate: form.businessDate,
    })
    if (row.productId !== productId) return
    applyOptionsToRow(row, options, requestedUnit)
  } finally {
    if (row.productId === productId) {
      if (currentPrice) {
        row.unitPrice = currentPrice.unitPrice
        row.priceSource = currentPrice.priceSource
        row.priceExplanation = currentPrice.priceExplanation
      }
      row.unitLoading = false
    }
  }
}

function applyOptionsToRow(row: SalesItemForm, options: ProductPurchaseOptions, requestedPriceUnit?: string) {
  const allowedPackageNames = row.packagingProfile?.rules
    .filter((item) => item.salesAllowed)
    .map((item) => item.parentUnit) ?? []
  row.packageUnitOptions = options.packageUnits
  row.priceUnitOptions = mergeUnitOptions(options.priceUnits, options.packageUnits, allowedPackageNames)
  const suggestedUnit = options.priceSuggestion.priceUnit || primaryUnitName(options.priceUnits, options.defaultPriceUnit)
  const selectedUnit = requestedPriceUnit && hasUnitOption(row.priceUnitOptions, requestedPriceUnit)
    ? requestedPriceUnit
    : suggestedUnit
  row.priceUnit = selectedUnit
  row.priceSource = options.priceSuggestion.sourceName
  row.priceExplanation = options.priceSuggestion.explanation || options.priceSuggestion.sourceName
  row.priceWarnings = options.priceSuggestion.warnings || []
  if (row.packageUnit && !options.packageUnits.some((item) => item.name === row.packageUnit)) row.packageUnit = ''
  if (!row.noCharge) {
    row.unitPrice = convertUnitPrice(
      Number(options.priceSuggestion.unitPrice || 0),
      suggestedUnit,
      selectedUnit,
      [...row.priceUnitOptions, ...options.packageUnits],
    )
  }
}

function resetProductUnitOptions(row: SalesItemForm) {
  row.priceUnit = ''
  row.packageUnit = ''
  row.unitPrice = 0
  row.priceSource = ''
  row.priceExplanation = ''
  row.priceWarnings = []
  row.priceUnitOptions = []
  row.packageUnitOptions = []
  row.unitLoading = false
  row.quantityUnit = ''
  row.actualBaseQuantity = undefined
  row.packagingProfile = undefined
  row.packagingPlan = undefined
  row.planError = ''
}

function itemAmount(row: SalesItemForm) {
  if (row.noCharge) return 0
  const entered = Number(row.packagingPlan?.pricingQuantity || row.quantity || 0) * Number(row.unitPrice || 0)
  return grossFromEntered(entered, priceIncludesTax.value, taxRate.value)
}

async function loadPartnerTax(partnerId: number) {
  const partner = await api.partner(partnerId)
  priceIncludesTax.value = partner.customerTaxIncluded
  taxRate.value = Number(partner.customerTaxRate || 0)
}

function roundMoney(value: number) {
  return Math.round((Number(value || 0) + Number.EPSILON) * 100) / 100
}

function signedMoney(value: number) {
  const amount = Number(value || 0)
  return `${amount >= 0 ? '+' : '-'}${money(Math.abs(amount))}`
}

async function loadPackagingProfile(row: SalesItemForm) {
  if (!row.productId) return
  const productId = row.productId
  const profile = await api.packagingProfile(productId)
  if (row.productId !== productId) return
  row.packagingProfile = profile
  row.quantityUnit = defaultQuantityUnit(profile, 'SALES')
  const configuredPackages = profile.rules.filter((item) => item.salesAllowed).map((item) => item.parentUnit)
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

async function handleQuantityUnitChange(row: SalesItemForm) {
  if (!requiresActualBaseQuantity(row.packagingProfile, row.quantityUnit)) row.actualBaseQuantity = undefined
  await handleQuantityChange(row)
}

async function refreshPackagingPlan(row: SalesItemForm) {
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
      actualBaseQuantity: row.actualBaseQuantity === '' || row.actualBaseQuantity == null ? undefined : Number(row.actualBaseQuantity),
      priceUnit: row.priceUnit || row.packagingProfile?.baseUnit,
      purpose: 'SALES_ORDER',
    } as const
    const plan = isAmend.value && row.itemId
      ? await api.salesAmendmentPackagingPlan(editId.value, row.itemId, request)
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
</script>

<style scoped>
.price-source{margin-top:4px;color:var(--el-color-success);font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.price-source.warning{color:var(--el-color-warning)}
</style>

<style scoped>
.amend-alert { margin: 0 20px 16px; }
.tax-contract-field { display: flex; align-items: center; gap: 8px; white-space: nowrap; }
.inventory-quantity-field {
  display: flex;
  align-items: center;
  gap: 8px;
}

.inventory-quantity-field :deep(.el-input) {
  flex: 1;
  min-width: 0;
}

.sales-quantity-input {
  width: 100%;
}

.inventory-quantity-value {
  flex: 1;
  min-width: 0;
  padding: 0 8px;
  color: var(--el-text-color-primary);
  text-align: right;
}

.inventory-quantity-unit {
  flex: none;
  min-width: 24px;
  color: var(--el-text-color-regular);
}

.payment-term-preview {
  color: var(--el-color-primary);
  font-weight: 700;
}

.address-select-footer {
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 6px;
  text-align: left;
}
</style>
