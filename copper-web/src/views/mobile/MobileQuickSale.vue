<template>
  <div class="mobile-page">
    <el-alert
      title="快速入口只保存草稿"
      description="报价草稿不占库存、不产生应收；销售订单草稿也不会自动确认、出库或记账。正式履约继续按原流程复核。"
      type="info"
      :closable="false"
      show-icon
    />

    <section class="mobile-card">
      <div class="mobile-card-title">
        <div><strong>客户与交付</strong><span>只填开单必需信息</span></div>
      </div>
      <el-form label-position="top">
        <el-form-item label="客户" required>
          <PartnerRemoteSelect v-model="form.customerId" type="CUSTOMER" @change="handleCustomerChange" />
        </el-form-item>
        <el-form-item label="收货地址" required>
          <el-select v-model="form.addressId" filterable :disabled="!form.customerId" placeholder="请选择客户收货地址">
            <el-option v-for="item in enabledAddresses" :key="item.id" :label="addressLabel(item)" :value="item.id" />
          </el-select>
          <div v-if="form.customerId && !loadingCustomer && !enabledAddresses.length" class="field-warning">
            客户没有启用的收货地址，请先到电脑版往来单位补齐后再开单。
          </div>
        </el-form-item>
        <el-form-item label="主题（选填）"><el-input v-model="form.title" maxlength="255" placeholder="如：7月紫铜管报价" /></el-form-item>
      </el-form>
    </section>

    <section class="mobile-card">
      <div class="mobile-card-title">
        <div><strong>商品明细</strong><span>支持不同铜材形态和受控计量单位</span></div>
        <el-button plain @click="addItem">增加</el-button>
      </div>
      <div class="quick-items">
        <article v-for="(row, index) in form.items" :key="row.key" class="quick-item">
          <div class="quick-item-heading">
            <span>商品 {{ index + 1 }}</span>
            <el-button v-if="form.items.length > 1" link type="danger" @click="removeItem(index)">删除</el-button>
          </div>
          <el-form label-position="top">
            <el-form-item label="商品" required>
              <el-select v-model="row.productId" filterable placeholder="按 SKU、名称、规格搜索" @change="() => handleProductChange(row)">
                <el-option
                  v-for="item in transactionProductOptions(products, row.productId)"
                  :key="item.id"
                  :label="transactionProductOptionLabel(item)"
                  :value="item.id"
                  :disabled="!isTransactionSelectableProduct(item)"
                />
              </el-select>
            </el-form-item>
            <div class="quick-item-grid">
              <el-form-item label="数量" required><el-input v-model="row.quantity" inputmode="decimal" @change="() => refreshItem(row)" /></el-form-item>
              <el-form-item label="数量单位" required>
                <el-select v-model="row.quantityUnit" filterable @change="() => refreshItem(row)">
                  <el-option v-for="unit in row.unitOptions" :key="unit" :label="unit" :value="unit" />
                </el-select>
              </el-form-item>
              <el-form-item v-if="needsActualQuantity(row)" label="实际基础量" required>
                <el-input v-model="row.actualBaseQuantity" inputmode="decimal" :placeholder="`填写${row.profile?.baseUnit || '基础单位'}数量`" @change="() => refreshItem(row)" />
              </el-form-item>
              <el-form-item label="计价单位" required>
                <el-select v-model="row.priceUnit" filterable @change="() => refreshItem(row)">
                  <el-option v-for="unit in row.priceUnitOptions" :key="unit" :label="unit" :value="unit" />
                </el-select>
              </el-form-item>
              <el-form-item label="单价" required><el-input v-model="row.unitPrice" inputmode="decimal" @change="markManualPrice(row)" /></el-form-item>
            </div>
          </el-form>
          <div class="plan-line" :class="{ error: row.planError }">
            <span v-if="row.loading">正在计算包装与价格…</span>
            <span v-else-if="row.planError">{{ row.planError }}</span>
            <span v-else-if="row.plan">{{ quantity(row.plan.baseQuantity) }} {{ row.plan.baseUnit }} · 计价 {{ quantity(row.plan.pricingQuantity) }} {{ row.plan.priceUnit }}</span>
            <span v-else>选择商品后由服务器计算包装换算</span>
            <small v-if="row.priceSource">{{ row.priceSource }}</small>
          </div>
        </article>
      </div>
    </section>

    <section class="mobile-card settlement-card">
      <div class="settlement-line"><span>价格口径</span><strong>{{ partner?.customerTaxIncluded === false ? '未税价' : '含税价' }} · 税率 {{ taxRate }}%</strong></div>
      <div class="settlement-line total"><span>预计价税合计</span><strong>{{ money(estimatedGross) }}</strong></div>
      <el-form label-position="top">
        <el-form-item label="备注（选填）"><el-input v-model="form.memo" type="textarea" :rows="2" maxlength="500" /></el-form-item>
      </el-form>
    </section>

    <section v-if="created" class="mobile-created-card">
      <el-icon><CircleCheckFilled /></el-icon>
      <div>
        <strong>{{ created.type === 'QUOTE' ? '报价草稿' : '销售订单草稿' }}已保存</strong>
        <span>编号将在详情页显示；当前没有确认、占库存或产生应收。</span>
      </div>
      <el-button plain @click="openCreated">查看详情</el-button>
    </section>

    <div class="mobile-sticky-actions">
      <el-button v-if="canQuote" size="large" :loading="saving === 'QUOTE'" :disabled="!connectivity.isAvailable" @click="save('QUOTE')">保存报价草稿</el-button>
      <el-button v-if="canOrder" type="primary" size="large" :loading="saving === 'ORDER'" :disabled="!connectivity.isAvailable" @click="save('ORDER')">保存订单草稿</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { CircleCheckFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import {
  api,
  type Address,
  type PackagingPlan,
  type PackagingProfile,
  type Partner,
  type Product,
} from '@/api/client'
import { currentDateText, money, quantity } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import { useSessionDraft } from '@/composables/useSessionDraft'
import { mobileDraftKey } from '@/utils/mobileDrafts'
import { useAppStore } from '@/stores/app'
import { useConnectivityStore } from '@/stores/connectivity'
import { requiresActualBaseQuantity } from '@/utils/packaging'
import { partnerAddressOptionLabel, receivingAddressOptions } from '@/utils/partnerAddresses'
import { isTransactionSelectableProduct, transactionProductOptionLabel, transactionProductOptions } from '@/utils/productDisplay'

type QuickItem = {
  key: number
  productId?: number
  quantity: number | string
  quantityUnit: string
  actualBaseQuantity?: number | string
  priceUnit: string
  unitPrice: number | string
  unitOptions: string[]
  priceUnitOptions: string[]
  profile?: PackagingProfile
  plan?: PackagingPlan
  planError: string
  priceSource: string
  loading: boolean
}
type QuickItemDraft = Pick<QuickItem, 'key' | 'productId' | 'quantity' | 'quantityUnit' | 'actualBaseQuantity' | 'priceUnit' | 'unitPrice' | 'priceSource'>
type QuickSaleDraft = { customerId?: number; addressId?: number; title: string; memo: string; items: QuickItemDraft[] }

const router = useRouter()
const appStore = useAppStore()
const connectivity = useConnectivityStore()
const today = currentDateText()
const products = ref<Product[]>([])
const partner = ref<Partner>()
const addresses = ref<Address[]>([])
const ready = ref(false)
const loadingCustomer = ref(false)
const saving = ref<'' | 'QUOTE' | 'ORDER'>('')
const created = ref<{ type: 'QUOTE' | 'ORDER'; id: number }>()
let nextKey = 1

const form = reactive({
  customerId: undefined as number | undefined,
  addressId: undefined as number | undefined,
  title: '',
  memo: '',
  items: [makeItem()] as QuickItem[],
})
const enabledAddresses = computed(() => receivingAddressOptions(addresses.value, form.addressId))
const canQuote = computed(() => appStore.hasPermission('crm.record.manage'))
const canOrder = computed(() => appStore.hasPermission('sales.manage'))
const taxRate = computed(() => Number(partner.value?.customerTaxRate ?? 13))
const estimatedGross = computed(() => roundMoney(form.items.reduce((sum, row) => {
  const entered = Number(row.plan?.pricingQuantity || 0) * Number(row.unitPrice || 0)
  return sum + (partner.value?.customerTaxIncluded === false ? entered * (1 + taxRate.value / 100) : entered)
}, 0)))

const { clearDraft } = useSessionDraft<QuickSaleDraft>({
  storageKey: () => mobileDraftKey(appStore.session?.userId, 'mobile-quick-sale'),
  snapshot: () => ({
    customerId: form.customerId,
    addressId: form.addressId,
    title: form.title,
    memo: form.memo,
    items: form.items.map(({ key, productId, quantity: itemQuantity, quantityUnit, actualBaseQuantity, priceUnit, unitPrice, priceSource }) =>
      ({ key, productId, quantity: itemQuantity, quantityUnit, actualBaseQuantity, priceUnit, unitPrice, priceSource })),
  }),
  restore: async (draft) => {
    form.customerId = draft.customerId
    form.addressId = draft.addressId
    form.title = draft.title || ''
    form.memo = draft.memo || ''
    form.items = draft.items?.length ? draft.items.map((item) => ({ ...makeItem(), ...item })) : [makeItem()]
    nextKey = Math.max(...form.items.map((item) => item.key), 0) + 1
    if (form.customerId) await loadCustomer(form.customerId, true)
    await Promise.all(form.items.filter((item) => item.productId).map((item) => loadProduct(item, true)))
  },
  ready,
  label: '手机快速报价 / 下单',
  confirmRestore: true,
})

onMounted(async () => {
  try {
    products.value = await api.products()
  } finally {
    ready.value = true
  }
})

function makeItem(): QuickItem {
  return {
    key: nextKey++,
    productId: undefined,
    quantity: 1,
    quantityUnit: '',
    actualBaseQuantity: undefined,
    priceUnit: '',
    unitPrice: '',
    unitOptions: [],
    priceUnitOptions: [],
    profile: undefined,
    plan: undefined,
    planError: '',
    priceSource: '',
    loading: false,
  }
}
function addItem() { form.items.push(makeItem()) }
function removeItem(index: number) { if (form.items.length > 1) form.items.splice(index, 1) }
function addressLabel(item: Address) { return partnerAddressOptionLabel(item) }
function needsActualQuantity(row: QuickItem) { return requiresActualBaseQuantity(row.profile, row.quantityUnit) }

async function handleCustomerChange(value?: number | string) {
  form.addressId = undefined
  partner.value = undefined
  addresses.value = []
  const id = typeof value === 'number' ? value : Number(value || 0)
  if (!id) return
  await loadCustomer(id, false)
  await Promise.all(form.items.filter((item) => item.productId).map((item) => loadProduct(item, false)))
}

async function loadCustomer(id: number, preserveAddress: boolean) {
  loadingCustomer.value = true
  const selectedAddress = preserveAddress ? form.addressId : undefined
  try {
    [partner.value, addresses.value] = await Promise.all([api.partner(id), api.addresses(id)])
    form.addressId = selectedAddress && enabledAddresses.value.some((item) => item.id === selectedAddress)
      ? selectedAddress
      : enabledAddresses.value.find((item) => item.firstChoice)?.id ?? enabledAddresses.value[0]?.id
  } finally {
    loadingCustomer.value = false
  }
}

async function handleProductChange(row: QuickItem) {
  row.actualBaseQuantity = undefined
  row.unitPrice = ''
  row.priceSource = ''
  await loadProduct(row, false)
}

async function loadProduct(row: QuickItem, preserveValues: boolean) {
  const product = products.value.find((item) => item.id === row.productId)
  if (!product || !row.productId) return
  row.loading = true
  try {
    const [profile, options] = await Promise.all([
      api.packagingProfile(row.productId),
      api.productUnitOptions(row.productId, {
        partnerId: form.customerId,
        partnerType: 'CUSTOMER',
        quantity: Number(row.quantity || 1),
        businessDate: today,
      }),
    ])
    row.profile = profile
    row.unitOptions = [...new Set([
      product.unit,
      profile.defaultSalesUnit,
      profile.baseUnit,
      ...options.priceUnits.map((item) => item.name),
      ...options.packageUnits.map((item) => item.name),
    ].filter(Boolean))]
    row.priceUnitOptions = [...new Set([options.defaultPriceUnit, ...options.priceUnits.map((item) => item.name), product.unit].filter(Boolean))]
    if (!preserveValues || !row.quantityUnit) row.quantityUnit = profile.defaultSalesUnit || product.unit
    if (!preserveValues || !row.priceUnit) row.priceUnit = options.defaultPriceUnit || product.unit
    if ((!preserveValues || !Number(row.unitPrice)) && options.priceSuggestion.unitPrice > 0) row.unitPrice = options.priceSuggestion.unitPrice
    if (!preserveValues || !row.priceSource) row.priceSource = options.priceSuggestion.sourceName || '手工输入'
    await refreshItem(row)
  } finally {
    row.loading = false
  }
}

async function refreshItem(row: QuickItem) {
  if (!row.productId || !Number(row.quantity) || !row.quantityUnit || !row.priceUnit) {
    row.plan = undefined
    return
  }
  row.loading = true
  try {
    row.plan = await api.packagingPlan(row.productId, {
      quantity: Number(row.quantity),
      quantityUnit: row.quantityUnit,
      actualBaseQuantity: row.actualBaseQuantity === '' || row.actualBaseQuantity == null ? undefined : Number(row.actualBaseQuantity),
      priceUnit: row.priceUnit,
      purpose: 'SALES_ORDER',
    })
    row.planError = ''
  } catch (error) {
    row.plan = undefined
    row.planError = error instanceof Error ? error.message : '包装换算失败'
  } finally {
    row.loading = false
  }
}

function markManualPrice(row: QuickItem) {
  row.priceSource = '人工输入'
}

async function save(type: 'QUOTE' | 'ORDER') {
  if (!connectivity.isAvailable) return ElMessage.warning('当前无法连接 Copper 主机，不能保存业务草稿')
  if (!form.customerId || !form.addressId) return ElMessage.warning('请选择客户和收货地址')
  if (!form.items.length) return ElMessage.warning('请至少添加一个商品')
  await Promise.all(form.items.map(refreshItem))
  if (form.items.some((item) => !item.productId || !item.plan || item.planError || Number(item.quantity) <= 0 || Number(item.unitPrice) <= 0)) {
    return ElMessage.warning('请完整填写商品、数量、单位和正数单价，并解决包装换算问题')
  }
  saving.value = type
  try {
    const items = form.items.map((item) => ({
      productId: item.productId,
      quantity: Number(item.quantity),
      quantityUnit: item.quantityUnit,
      actualBaseQuantity: item.actualBaseQuantity === '' || item.actualBaseQuantity == null ? undefined : Number(item.actualBaseQuantity),
      priceUnit: item.priceUnit,
      packageUnit: '',
      noCharge: false,
      unitPrice: Number(item.unitPrice),
    }))
    if (type === 'QUOTE') {
      const validUntil = new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10)
      const id = await api.createCrmQuote({
        partnerId: form.customerId,
        addressId: form.addressId,
        title: form.title,
        quoteDate: today,
        validUntil,
        priceIncludesTax: partner.value?.customerTaxIncluded !== false,
        taxRate: taxRate.value,
        taxRuleSnapshot: '按报价日客户税务设置生成，正式开票由会计复核',
        discountRate: 0,
        adjustmentAmount: 0,
        paymentTerm: 'POSTPAID',
        shipmentPaymentPolicy: 'NONE',
        depositMode: 'AMOUNT',
        depositRate: 0,
        requiredDepositAmount: 0,
        deliveryPlanDate: '',
        memo: form.memo,
        items,
      })
      created.value = { type, id }
    } else {
      const result = await api.createSalesOrder({
        customerId: form.customerId,
        addressId: form.addressId,
        title: form.title,
        businessDate: today,
        deliveryPlanDate: null,
        fulfillmentWarehouseId: null,
        discountRate: 0,
        adjustmentAmount: 0,
        paymentTerm: 'POSTPAID',
        shipmentPaymentPolicy: 'NONE',
        depositMode: 'AMOUNT',
        depositRate: 0,
        requiredDepositAmount: 0,
        memo: form.memo,
        items,
      })
      created.value = { type, id: result.id }
    }
    clearDraft()
    ElMessage.success(type === 'QUOTE' ? '报价草稿已保存' : '销售订单草稿已保存')
    reset()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '草稿保存失败，请核对后重试')
  } finally {
    saving.value = ''
  }
}

function reset() {
  form.customerId = undefined
  form.addressId = undefined
  form.title = ''
  form.memo = ''
  form.items = [makeItem()]
  partner.value = undefined
  addresses.value = []
}

function openCreated() {
  if (!created.value) return
  router.push(created.value.type === 'QUOTE' ? `/crm/quotes/${created.value.id}` : `/sales-orders/${created.value.id}`)
}
function roundMoney(value: number) { return Math.round((value + Number.EPSILON) * 100) / 100 }
</script>

<style scoped>
.mobile-page { display: grid; gap: 12px; }
.mobile-card { padding: 15px; border: 1px solid #e2e7ea; border-radius: 17px; background: #fff; }
.mobile-card-title { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 14px; }
.mobile-card-title > div { display: grid; gap: 3px; }
.mobile-card-title strong { font-size: 15px; }
.mobile-card-title span { color: #7a8790; font-size: 11px; }
.mobile-card :deep(.el-select),
.mobile-card :deep(.el-date-editor) { width: 100%; }
.quick-items { display: grid; gap: 12px; }
.quick-item { padding: 12px; border: 1px solid #e5e9ec; border-radius: 14px; background: #fafcfd; }
.quick-item-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; color: #52626d; font-size: 12px; font-weight: 700; }
.quick-item-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 10px; }
.plan-line { display: grid; gap: 3px; padding: 9px 10px; border-radius: 9px; color: #315b43; background: #edf7f1; font-size: 12px; }
.plan-line.error { color: #a64232; background: #fff0ed; }
.plan-line small { overflow: hidden; color: #74847b; text-overflow: ellipsis; white-space: nowrap; }
.field-warning { margin-top: 6px; color: var(--el-color-danger); font-size: 11px; line-height: 1.5; }
.settlement-line { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 7px 0; font-size: 12px; }
.settlement-line span { color: #72808a; }
.settlement-line.total { margin-bottom: 10px; border-bottom: 1px solid #e7ebee; padding-bottom: 13px; }
.settlement-line.total strong { color: #bd4f20; font-size: 19px; }
.mobile-created-card { display: grid; grid-template-columns: 28px 1fr auto; align-items: center; gap: 10px; padding: 14px; border: 1px solid #cce5d3; border-radius: 15px; color: #27613e; background: #f0f9f3; }
.mobile-created-card > .el-icon { font-size: 23px; }
.mobile-created-card div { display: grid; gap: 3px; }
.mobile-created-card span { color: #668273; font-size: 10px; line-height: 1.45; }
.mobile-sticky-actions { position: sticky; z-index: 20; bottom: calc(8px + env(safe-area-inset-bottom)); display: grid; grid-template-columns: 1fr 1fr; gap: 10px; padding: 10px; border: 1px solid #dfe5e9; border-radius: 15px; background: rgb(255 255 255 / 94%); box-shadow: 0 10px 30px rgb(12 38 54 / 12%); backdrop-filter: blur(12px); }
.mobile-sticky-actions .el-button { min-height: 45px; margin: 0; }
@media (max-width: 520px) {
  .quick-item-grid { grid-template-columns: 1fr 1fr; }
  .mobile-created-card { grid-template-columns: 28px 1fr; }
  .mobile-created-card .el-button { grid-column: 1 / -1; }
  .mobile-sticky-actions { grid-template-columns: 1fr; }
}
</style>
