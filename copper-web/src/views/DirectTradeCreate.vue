<template>
  <div class="page">
    <div class="panel">
      <PageHeader title="新建直运购销" back-to="/direct-trades">
        <el-button type="primary" :loading="saving" @click="submit">保存草稿</el-button>
      </PageHeader>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="一次录入客户、供应商和购销价格；供应商直接发给客户，不生成入库、出库或库存流水。"
      />
      <div class="panel-body">
        <el-form ref="formRef" :model="form" :rules="rules" label-width="92px">
          <el-row :gutter="16">
            <el-col :span="8">
              <el-form-item label="客户" prop="customerId">
                <PartnerRemoteSelect v-model="form.customerId" type="CUSTOMER" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="收货地址" prop="addressId">
                <el-select v-model="form.addressId" filterable placeholder="客户直接收货地址">
                  <el-option v-for="item in enabledAddresses" :key="item.id" :label="addressLabel(item)" :value="item.id" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="供应商" prop="supplierId">
                <PartnerRemoteSelect v-model="form.supplierId" type="SUPPLIER" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="业务日期" prop="businessDate">
                <el-date-picker v-model="form.businessDate" value-format="YYYY-MM-DD" />
              </el-form-item>
            </el-col>
            <el-col :span="8">
              <el-form-item label="预计送达">
                <el-date-picker v-model="form.expectedDeliveryDate" value-format="YYYY-MM-DD" clearable />
              </el-form-item>
            </el-col>
            <el-col :span="16">
              <el-form-item label="备注">
                <el-input v-model="form.memo" maxlength="500" placeholder="可填写供应商联系人、交付约定等" />
              </el-form-item>
            </el-col>
          </el-row>
        </el-form>
      </div>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">购销商品</div>
        <el-button @click="addItem">增加明细</el-button>
      </div>
      <div class="direct-trade-summary">
        <span>销售 {{ money(saleTotal) }}</span>
        <span v-if="canViewCost">采购 {{ money(purchaseTotal) }}</span>
        <strong v-if="canViewCost" :class="grossProfit < 0 ? 'text-danger' : 'text-success'">预计毛利 {{ money(grossProfit) }}</strong>
      </div>
      <el-table :data="form.items" class="data-table" size="small" border>
        <el-table-column min-width="300">
          <template #header>商品 <span class="table-required-mark">*</span></template>
          <template #default="{ row }">
            <el-select v-model="row.productId" filterable @change="selectProduct(row)">
              <el-option v-for="item in products ?? []" :key="item.id" :label="`${item.name} · ${item.sku}${item.spec ? ` · ${item.spec}` : ''}`" :value="item.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column width="150">
          <template #header>数量 <span class="table-required-mark">*</span></template>
          <template #default="{ row }"><el-input-number v-model="row.quantity" :min="0.000001" :precision="6" :controls="false" /></template>
        </el-table-column>
        <el-table-column label="单位" width="100"><template #default="{ row }">{{ row.quantityUnit || '-' }}</template></el-table-column>
        <el-table-column width="170">
          <template #header>采购单价 <span class="table-required-mark">*</span></template>
          <template #default="{ row }"><el-input-number v-model="row.purchaseUnitPrice" :min="0" :precision="4" :controls="false" /></template>
        </el-table-column>
        <el-table-column width="170">
          <template #header>销售单价 <span class="table-required-mark">*</span></template>
          <template #default="{ row }"><el-input-number v-model="row.saleUnitPrice" :min="0" :precision="4" :controls="false" /></template>
        </el-table-column>
        <el-table-column label="销售金额" width="130" align="right"><template #default="{ row }">{{ money(lineAmount(row.quantity, row.saleUnitPrice)) }}</template></el-table-column>
        <el-table-column v-if="canViewCost" label="毛利" width="130" align="right">
          <template #default="{ row }"><span :class="lineMargin(row) < 0 ? 'text-danger' : ''">{{ money(lineMargin(row)) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ $index }"><el-button text type="danger" :disabled="form.items.length === 1" @click="form.items.splice($index, 1)">删除</el-button></template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { api, type Address, type Product } from '@/api/client'
import { currentDateText, money } from '@/api/format'
import PageHeader from '@/components/PageHeader.vue'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import { useAppStore } from '@/stores/app'

type ItemForm = {
  productId?: number
  quantity: number
  quantityUnit: string
  priceUnit: string
  packageUnit: string
  purchaseUnitPrice: number
  saleUnitPrice: number
}

const router = useRouter()
const appStore = useAppStore()
const formRef = ref<FormInstance>()
const saving = ref(false)
const canViewCost = computed(() => appStore.hasPermission('inventory.cost.view') || appStore.hasPermission('purchase.view'))
const form = reactive({
  customerId: undefined as number | undefined,
  supplierId: undefined as number | undefined,
  addressId: undefined as number | undefined,
  businessDate: currentDateText(),
  expectedDeliveryDate: '',
  memo: '',
  items: [makeItem()],
})
const rules: FormRules = {
  customerId: [{ required: true, message: '请选择客户', trigger: 'change' }],
  supplierId: [{ required: true, message: '请选择供应商', trigger: 'change' }],
  addressId: [{ required: true, message: '请选择客户收货地址', trigger: 'change' }],
  businessDate: [{ required: true, message: '请选择业务日期', trigger: 'change' }],
}
const customerId = computed(() => form.customerId || 0)
const { data: addresses } = useQuery({
  queryKey: computed(() => ['addresses', customerId.value]),
  queryFn: () => customerId.value ? api.addresses(customerId.value) : Promise.resolve([]),
})
const { data: products } = useQuery({ queryKey: ['products'], queryFn: api.products })
const enabledAddresses = computed(() => (addresses.value ?? []).filter((item) => item.status === 'ENABLED' && (!item.addressType || ['RECEIVING', 'BOTH'].includes(item.addressType))))
const saleTotal = computed(() => form.items.reduce((sum, item) => sum + lineAmount(item.quantity, item.saleUnitPrice), 0))
const purchaseTotal = computed(() => form.items.reduce((sum, item) => sum + lineAmount(item.quantity, item.purchaseUnitPrice), 0))
const grossProfit = computed(() => saleTotal.value - purchaseTotal.value)

watch(enabledAddresses, (rows) => {
  if (!form.addressId) form.addressId = rows.find((item) => item.firstChoice)?.id ?? rows[0]?.id
})
watch(() => form.customerId, () => { form.addressId = undefined })

function makeItem(): ItemForm {
  return { quantity: 1, quantityUnit: '', priceUnit: '', packageUnit: '', purchaseUnitPrice: 0, saleUnitPrice: 0 }
}
function addItem() {
  form.items.push(makeItem())
}
function selectProduct(row: ItemForm) {
  const product = (products.value ?? []).find((item) => item.id === row.productId)
  applyProduct(row, product)
}
function applyProduct(row: ItemForm, product?: Product) {
  row.quantityUnit = product?.unit || ''
  row.priceUnit = product?.unit || ''
  row.packageUnit = ''
  if (product && !row.saleUnitPrice) row.saleUnitPrice = Number(product.defaultPrice || 0)
}
function addressLabel(address: Address) {
  return [address.name, address.contactPerson, address.phone, address.detail].filter(Boolean).join(' / ')
}
function lineAmount(quantity: number, unitPrice: number) {
  return Math.round(Number(quantity || 0) * Number(unitPrice || 0) * 100) / 100
}
function lineMargin(row: ItemForm) {
  return lineAmount(row.quantity, row.saleUnitPrice) - lineAmount(row.quantity, row.purchaseUnitPrice)
}
async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (form.customerId === form.supplierId) return ElMessage.warning('客户与供应商不能是同一往来单位')
  if (form.expectedDeliveryDate && form.expectedDeliveryDate < form.businessDate) return ElMessage.warning('预计送达不能早于业务日期')
  if (form.items.some((item) => !item.productId || !item.quantityUnit || item.quantity <= 0 || item.purchaseUnitPrice < 0 || item.saleUnitPrice < 0)) {
    return ElMessage.warning('请完整填写商品、数量和购销单价')
  }
  saving.value = true
  try {
    const result = await api.createDirectTrade({
      customerId: form.customerId,
      supplierId: form.supplierId,
      addressId: form.addressId,
      businessDate: form.businessDate,
      expectedDeliveryDate: form.expectedDeliveryDate || null,
      memo: form.memo,
      items: form.items.map((item) => ({
        productId: item.productId,
        quantity: Number(item.quantity),
        quantityUnit: item.quantityUnit,
        priceUnit: item.priceUnit,
        packageUnit: item.packageUnit || null,
        purchaseUnitPrice: Number(item.purchaseUnitPrice),
        saleUnitPrice: Number(item.saleUnitPrice),
      })),
    })
    ElMessage.success('直运购销草稿已保存')
    await router.replace(`/direct-trades/${result.id}`)
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.direct-trade-summary { display: flex; justify-content: flex-end; gap: 28px; padding: 10px 16px; background: var(--el-fill-color-lighter); }
</style>
