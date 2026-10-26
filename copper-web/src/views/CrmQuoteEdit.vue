<template>
  <div v-loading="loading">
    <PageHeader :title="editing ? '编辑报价草稿' : '新建报价'" back-to="/crm/quotes">
      <el-button type="primary" :loading="saving" @click="save">保存报价草稿</el-button>
    </PageHeader>
    <section class="panel">
      <el-alert title="报价与订单分层" description="保存只形成 CRM 报价草稿，不占库存、不产生应收、不开票；客户接受后仍由用户选择是否转换销售订单草稿。" type="info" :closable="false" show-icon />
      <el-form label-width="105px" class="quote-form">
        <div class="form-grid">
          <el-form-item label="客户" required><PartnerRemoteSelect v-model="form.partnerId" type="CUSTOMER" :disabled="editing" @change="onPartnerChange" /></el-form-item>
          <el-form-item label="商机"><el-select v-model="form.opportunityId" clearable filterable placeholder="可不选"><el-option v-for="item in opportunities" :key="item.id" :label="`${item.opportunityNo} · ${item.title}`" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="联系人"><el-select v-model="form.contactId" clearable placeholder="可不选"><el-option v-for="item in contacts" :key="item.id" :label="`${item.name} ${item.phone}`" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="收货地址" required><el-select v-model="form.addressId" placeholder="请选择客户收货地址"><el-option v-for="item in receivingAddresses" :key="item.id" :label="`${item.name || '收货地址'} · ${item.detail} · ${item.contactPerson}`" :value="item.id" /></el-select><div v-if="form.partnerId && !receivingAddresses.length" class="field-warning">客户暂无启用收货地址，请先在往来单位主档补齐</div></el-form-item>
          <el-form-item label="报价主题"><el-input v-model="form.title" maxlength="255" /></el-form-item>
          <el-form-item label="报价日期" required><el-date-picker v-model="form.quoteDate" type="date" value-format="YYYY-MM-DD" @change="refreshAllPrices" /></el-form-item>
          <el-form-item label="有效期至" required><el-date-picker v-model="form.validUntil" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item label="预计交付"><el-date-picker v-model="form.deliveryPlanDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item label="价格口径"><el-radio-group v-model="form.priceIncludesTax" @change="refreshAllPlans"><el-radio :value="true">含税价</el-radio><el-radio :value="false">未税价</el-radio></el-radio-group></el-form-item>
          <el-form-item label="税率"><el-input-number v-model="form.taxRate" :min="0" :max="100" :precision="4" :controls="false" /><span class="unit-suffix">%</span></el-form-item>
          <el-form-item label="税务快照" class="wide"><el-input v-model="form.taxRuleSnapshot" placeholder="记录当时采用的税率规则或会计复核说明" /></el-form-item>
        </div>
      </el-form>
    </section>

    <section class="panel items-panel">
      <div class="panel-header"><div><div class="panel-title">报价商品</div><div class="muted">数量、单位和包装按商品当前有效规则计算，保存后冻结快照；原包装重量可原样报价，可拆包装按规则换算。</div></div><el-button @click="addItem">添加商品</el-button></div>
      <el-table :data="form.items" class="quote-items">
        <el-table-column min-width="220"><template #header>商品 <span class="table-required-mark">*</span></template><template #default="{ row }"><el-select v-model="row.productId" filterable @change="() => onProductChange(row)"><el-option v-for="item in transactionProductOptions(products, row.productId)" :key="item.id" :label="transactionProductOptionLabel(item)" :value="item.id" :disabled="!isTransactionSelectableProduct(item)" /></el-select></template></el-table-column>
        <el-table-column min-width="190"><template #header>报价数量 / 单位 <span class="table-required-mark">*</span></template><template #default="{ row }"><div class="quantity-input"><el-input-number v-model="row.quantity" :min="0.000001" :precision="6" :controls="false" @change="() => refreshPriceAndPlan(row, true)" /><el-select v-model="row.quantityUnit" filterable allow-create @change="() => refreshPriceAndPlan(row, true)"><el-option v-for="item in row.unitOptions" :key="item" :label="item" :value="item" /></el-select></div></template></el-table-column>
        <el-table-column label="实际基础量" width="140"><template #default="{ row }"><el-input-number v-model="row.actualBaseQuantity" :min="0" :precision="6" :controls="false" placeholder="按需填写" @change="() => refreshPriceAndPlan(row, true)" /></template></el-table-column>
        <el-table-column width="125"><template #header>计价单位 <span class="table-required-mark">*</span></template><template #default="{ row }"><el-select v-model="row.priceUnit" filterable allow-create @change="() => refreshPriceAndPlan(row)"><el-option v-for="item in row.unitOptions" :key="item" :label="item" :value="item" /></el-select></template></el-table-column>
        <el-table-column label="赠品" width="75"><template #default="{ row }"><el-switch v-model="row.noCharge" @change="() => handleNoChargeChange(row)" /></template></el-table-column>
        <el-table-column width="190"><template #header>单价 / 来源 <span class="table-required-mark">*</span></template><template #default="{ row }"><el-input-number v-model="row.unitPrice" :min="0" :precision="4" :controls="false" :disabled="row.noCharge" @change="() => markManualPrice(row)" /><el-tooltip :content="row.priceExplanation || row.priceSource"><div class="price-source">{{ row.priceSource || '手工输入' }}</div></el-tooltip></template></el-table-column>
        <el-table-column label="换算结果" min-width="170"><template #default="{ row }"><span v-if="row.planError" class="field-warning">{{ row.planError }}</span><span v-else-if="row.packagingPlan">{{ row.packagingPlan.baseQuantity }} {{ row.packagingPlan.baseUnit }} / 计价 {{ row.packagingPlan.pricingQuantity }} {{ row.packagingPlan.priceUnit }}</span><span v-else class="muted">选择商品后计算</span></template></el-table-column>
        <el-table-column label="金额" width="120" align="right"><template #default="{ row }">¥{{ moneyText(itemGross(row)) }}</template></el-table-column>
        <el-table-column width="60"><template #default="{ $index }"><el-button link type="danger" @click="removeItem($index)">删</el-button></template></el-table-column>
      </el-table>
    </section>

    <section class="panel settlement-panel">
      <el-form label-width="125px"><div class="settlement-grid"><el-form-item label="整单折扣"><el-input-number v-model="form.discountRate" :min="0" :max="100" :precision="2" :controls="false" /><span class="unit-suffix">%</span></el-form-item><el-form-item label="整单调整"><el-input-number v-model="form.adjustmentAmount" :precision="2" :controls="false" /></el-form-item><el-form-item label="付款条件"><el-select v-model="form.paymentTerm"><el-option label="账期 / 后付" value="POSTPAID" /><el-option label="要求预付款" value="PREPAYMENT_REQUIRED" /><el-option label="全额预付" value="FULL_PREPAYMENT" /></el-select></el-form-item><el-form-item label="发货控制"><el-select v-model="form.shipmentPaymentPolicy"><el-option label="不控制" value="NONE" /><el-option label="本次发货金额已覆盖" value="SHIPMENT_PAID" /><el-option label="整单已收足" value="ORDER_PAID" /></el-select></el-form-item><el-form-item v-if="form.paymentTerm === 'PREPAYMENT_REQUIRED'" label="预付方式"><el-select v-model="form.depositMode"><el-option label="固定金额" value="AMOUNT" /><el-option label="比例" value="RATE" /></el-select></el-form-item><el-form-item v-if="form.paymentTerm === 'PREPAYMENT_REQUIRED' && form.depositMode === 'AMOUNT'" label="要求预付"><el-input-number v-model="form.requiredDepositAmount" :min="0" :precision="2" :controls="false" /></el-form-item><el-form-item v-if="form.paymentTerm === 'PREPAYMENT_REQUIRED' && form.depositMode === 'RATE'" label="预付比例"><el-input-number v-model="form.depositRate" :min="0" :max="100" :precision="2" :controls="false" /><span class="unit-suffix">%</span></el-form-item><el-form-item label="备注" class="wide"><el-input v-model="form.memo" type="textarea" :rows="2" /></el-form-item></div></el-form>
      <div class="amount-summary"><span>商品价税合计 ¥{{ moneyText(originalAmount) }}</span><span>折扣 -¥{{ moneyText(discountAmount) }}</span><span>调整 {{ form.adjustmentAmount >= 0 ? '+' : '' }}¥{{ moneyText(form.adjustmentAmount) }}</span><strong>报价合计 ¥{{ moneyText(totalAmount) }}</strong></div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, type Address, type Contact, type CrmOpportunity, type PackagingPlan, type Product } from '@/api/client'
import PageHeader from '@/components/PageHeader.vue'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import { moneyText } from '@/utils/crm'
import { isTransactionSelectableProduct, transactionProductOptionLabel, transactionProductOptions } from '@/utils/productDisplay'

type QuoteItemForm = { itemId?: number; productId?: number; quantity: number; quantityUnit: string; actualBaseQuantity?: number; priceUnit: string; packageUnit: string; noCharge: boolean; unitPrice: number; priceSource: string; priceExplanation: string; unitOptions: string[]; packagingPlan?: PackagingPlan; planError: string }
const today = new Date().toISOString().slice(0, 10); const validDate = new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10)
const route = useRoute(); const router = useRouter(); const editing = computed(() => Boolean(route.params.id)); const id = computed(() => Number(route.params.id || 0)); const loading = ref(false); const saving = ref(false)
const products = ref<Product[]>([]); const contacts = ref<Contact[]>([]); const addresses = ref<Address[]>([]); const opportunities = ref<CrmOpportunity[]>([])
const receivingAddresses = computed(() => addresses.value.filter((item) => item.status === 'ENABLED' && item.addressType === 'RECEIVING'))
const form = reactive({ opportunityId: undefined as number | undefined, partnerId: undefined as number | undefined, contactId: undefined as number | undefined, addressId: undefined as number | undefined, title: '', quoteDate: today, validUntil: validDate, priceIncludesTax: true, taxRate: 13, taxRuleSnapshot: '按报价日适用税率，正式开票由会计复核', discountRate: 0, adjustmentAmount: 0, paymentTerm: 'POSTPAID', shipmentPaymentPolicy: 'NONE', depositMode: 'AMOUNT', depositRate: 30, requiredDepositAmount: 0, deliveryPlanDate: '', memo: '', items: [makeItem()] as QuoteItemForm[] })
const originalAmount = computed(() => roundMoney(form.items.reduce((sum, item) => sum + itemGross(item), 0))); const discountAmount = computed(() => roundMoney(originalAmount.value * Number(form.discountRate || 0) / 100)); const totalAmount = computed(() => roundMoney(originalAmount.value - discountAmount.value + Number(form.adjustmentAmount || 0)))

onMounted(async () => { products.value = await api.products(); if (editing.value) await loadDraft(); else { const partnerId = Number(route.query.partnerId); const opportunityId = Number(route.query.opportunityId); if (partnerId) { form.partnerId = partnerId; await onPartnerChange(partnerId) } if (opportunityId) form.opportunityId = opportunityId } })
function makeItem(): QuoteItemForm { return { quantity: 1, quantityUnit: '', priceUnit: '', packageUnit: '', noCharge: false, unitPrice: 0, priceSource: '', priceExplanation: '', unitOptions: [], planError: '' } }
function addItem() { form.items.push(makeItem()) } function removeItem(index: number) { if (form.items.length === 1) { ElMessage.warning('报价至少保留一行商品'); return } form.items.splice(index, 1) }
async function onPartnerChange(value?: number | string) { form.contactId = undefined; form.addressId = undefined; const partnerId = typeof value === 'number' ? value : 0; [contacts.value, addresses.value, opportunities.value] = partnerId ? await Promise.all([api.contacts(partnerId), api.addresses(partnerId), api.crmOpportunities({ partnerId })]) : [[], [], []]; form.addressId = receivingAddresses.value.find((item) => item.firstChoice)?.id ?? receivingAddresses.value[0]?.id }
async function onProductChange(row: QuoteItemForm) { const product = products.value.find((item) => item.id === row.productId); if (!product) return; row.itemId = undefined; row.quantityUnit = product.unit; row.priceUnit = product.unit; row.unitPrice = Number(product.defaultPrice || 0); const options = await api.productUnitOptions(product.id, { partnerId: form.partnerId, partnerType: 'CUSTOMER', quantity: row.quantity, businessDate: form.quoteDate }); row.unitOptions = [...new Set([...options.priceUnits.map((item) => item.name), ...options.packageUnits.map((item) => item.name), product.unit])]; if (options.defaultPriceUnit) row.priceUnit = options.defaultPriceUnit; await refreshPriceAndPlan(row) }
async function refreshPriceAndPlan(row: QuoteItemForm, preserveCurrentPrice = false) {
  if (!row.productId) return
  const productId = row.productId
  const currentPrice = preserveCurrentPrice
    ? {
        unitPrice: row.unitPrice,
        priceSource: row.priceSource,
        priceExplanation: row.priceExplanation,
      }
    : undefined
  try {
    await refreshPlan(row)
    if (!row.packagingPlan) return
    const options = await api.productUnitOptions(row.productId, {
      partnerId: form.partnerId,
      partnerType: 'CUSTOMER',
      priceUnit: row.priceUnit,
      quantity: row.packagingPlan.pricingQuantity,
      businessDate: form.quoteDate,
    })
    if (options.priceSuggestion.unitPrice > 0) row.unitPrice = options.priceSuggestion.unitPrice
    row.priceSource = options.priceSuggestion.sourceName
    row.priceExplanation = options.priceSuggestion.explanation || options.priceSuggestion.sourceName
  } finally {
    if (currentPrice && row.productId === productId) {
      row.unitPrice = currentPrice.unitPrice
      row.priceSource = currentPrice.priceSource
      row.priceExplanation = currentPrice.priceExplanation
    }
  }
}
async function refreshPlan(row: QuoteItemForm) { if (!row.productId || !row.quantity || !row.quantityUnit || !row.priceUnit) { row.packagingPlan = undefined; return } try { row.packagingPlan = await api.packagingPlan(row.productId, { quantity: row.quantity, quantityUnit: row.quantityUnit, actualBaseQuantity: row.actualBaseQuantity, priceUnit: row.priceUnit, purpose: 'SALES' }); row.planError = '' } catch (error) { row.packagingPlan = undefined; row.planError = error instanceof Error ? error.message : '包装换算失败' } }
function handleNoChargeChange(row: QuoteItemForm) { if (row.noCharge) { row.unitPrice = 0; row.priceSource = '赠品零价'; row.priceExplanation = '赠品不计销售价'; return } void refreshPriceAndPlan(row) }
function markManualPrice(row: QuoteItemForm) { if (row.noCharge) return; row.priceSource = '人工改价'; row.priceExplanation = '保存后将冻结为人工改价，并保留当时的建议价格作为审计依据' }
function refreshAllPlans() { form.items.forEach((item) => void refreshPlan(item)) }
function refreshAllPrices() { form.items.forEach((item) => { if (item.productId) void refreshPriceAndPlan(item) }) }
function itemGross(row: QuoteItemForm) { const entered = Number(row.packagingPlan?.pricingQuantity || row.quantity || 0) * Number(row.noCharge ? 0 : row.unitPrice || 0); return roundMoney(form.priceIncludesTax ? entered : entered * (1 + Number(form.taxRate || 0) / 100)) }
async function loadDraft() { loading.value = true; try { const detail = await api.crmQuote(id.value); if (detail.quote.status !== 'DRAFT') { ElMessage.warning('只有报价草稿可以编辑'); router.replace(`/crm/quotes/${id.value}`); return } Object.assign(form, { ...detail.quote, deliveryPlanDate: detail.quote.deliveryPlanDate || '' }); await onPartnerChange(detail.quote.partnerId); form.contactId = detail.quote.contactId || undefined; form.addressId = detail.quote.addressId || undefined; form.items = detail.items.map((item) => ({ itemId: item.id, productId: item.productId, quantity: item.requestedQuantity, quantityUnit: item.requestedUnit, actualBaseQuantity: item.actualBaseQuantity || undefined, priceUnit: item.priceUnit, packageUnit: item.packageUnit, noCharge: item.noCharge, unitPrice: item.unitPrice, priceSource: item.priceSourceName || '历史价格快照', priceExplanation: item.priceSnapshotJson || item.priceSourceName || '', unitOptions: [...new Set([item.requestedUnit, item.priceUnit, item.baseUnit, item.packageUnit].filter(Boolean))], packagingPlan: { requestedQuantity: item.requestedQuantity, requestedUnit: item.requestedUnit, baseQuantity: item.baseQuantity, baseUnit: item.baseUnit, pricingQuantity: item.pricingQuantity, priceUnit: item.priceUnit, actualWeightRequired: false, rounded: false, roundingPolicy: 'REJECT', suggestion: '按报价草稿快照显示', components: [], snapshotJson: item.packagingPlanJson, effectiveRevisionId: item.packagingEffectiveRevisionId, snapshotHash: item.packagingSnapshotHash }, planError: '' })) } finally { loading.value = false } }
async function save() { if (!form.partnerId || !form.addressId || !form.quoteDate || !form.validUntil) { ElMessage.warning('请完整选择客户、收货地址和报价日期'); return } if (form.items.some((item) => !item.productId || !item.packagingPlan || item.planError || (!item.noCharge && item.unitPrice <= 0))) { ElMessage.warning('请完整选择商品、填写正数单价并解决包装换算错误；零价请使用赠品标识'); return } saving.value = true; try { const payload = { ...form, items: form.items.map((item) => ({ itemId: item.itemId, productId: item.productId, quantity: item.quantity, quantityUnit: item.quantityUnit, actualBaseQuantity: item.actualBaseQuantity, priceUnit: item.priceUnit, packageUnit: item.packageUnit, noCharge: item.noCharge, unitPrice: item.noCharge ? 0 : item.unitPrice })) }; const quoteId = editing.value ? await api.updateCrmQuote(id.value, payload) : await api.createCrmQuote(payload); ElMessage.success('报价草稿已保存'); router.push(`/crm/quotes/${quoteId}`) } finally { saving.value = false } }
function roundMoney(value: number) { return Math.round((value + Number.EPSILON) * 100) / 100 }
</script>

<style scoped>
.quote-form{margin-top:16px}.form-grid,.settlement-grid{display:grid;grid-template-columns:1fr 1fr;gap:0 16px}.wide{grid-column:1/-1}.items-panel,.settlement-panel{margin-top:16px}.panel-header{display:flex;justify-content:space-between;align-items:center}.quantity-input{display:grid;grid-template-columns:1fr 82px;gap:6px}.quote-items :deep(.el-select){width:100%}.field-warning{color:var(--el-color-danger);font-size:12px;line-height:1.4}.price-source{margin-top:4px;color:var(--el-color-success);font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.unit-suffix{margin-left:6px}.amount-summary{display:flex;justify-content:flex-end;align-items:center;gap:22px;border-top:1px solid var(--el-border-color-lighter);padding-top:14px}.amount-summary strong{font-size:18px;color:var(--el-color-primary)}
</style>
