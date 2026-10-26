<template>
  <div v-if="detail" class="page">
    <div class="panel">
      <PageHeader :title="`直运购销 ${detail.header.tradeNo}`" back-to="/direct-trades">
        <el-button v-if="detail.header.status === 'DRAFT' && canConfirm" type="primary" :loading="acting" @click="confirmTrade">确认购销</el-button>
        <el-button v-if="canDispatch" type="primary" :loading="acting" @click="openDispatch">登记供应商发出</el-button>
        <el-button v-if="canReturn" :loading="acting" @click="openReturn">登记直运退货</el-button>
        <el-button v-if="['DRAFT', 'CONFIRMED'].includes(detail.header.status) && canConfirm" type="danger" plain :loading="acting" @click="cancelTrade">作废</el-button>
      </PageHeader>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="直运不经过本企业仓库；客户确认实收才形成应收、应付，收付款、发票仍分别登记和勾稽。"
      />
      <div class="panel-body">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="状态"><el-tag :type="statusType(detail.header.status)">{{ statusName(detail.header.status) }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="业务日期">{{ detail.header.businessDate }}</el-descriptions-item>
          <el-descriptions-item label="预计送达">{{ detail.header.expectedDeliveryDate || '-' }}</el-descriptions-item>
          <el-descriptions-item label="客户"><router-link class="table-link" :to="`/partners/${detail.header.customerId}`">{{ detail.header.customerName }}</router-link></el-descriptions-item>
          <el-descriptions-item label="供应商"><router-link class="table-link" :to="`/partners/${detail.header.supplierId}`">{{ detail.header.supplierName }}</router-link></el-descriptions-item>
          <el-descriptions-item label="客户收货地址">{{ detail.addressSnapshot }}</el-descriptions-item>
          <el-descriptions-item label="销售订单"><router-link class="table-link" :to="`/sales-orders/${detail.salesOrderId}`">{{ detail.salesOrderNo }}</router-link></el-descriptions-item>
          <el-descriptions-item label="采购订单"><router-link class="table-link" :to="`/purchase-orders/${detail.purchaseOrderId}`">{{ detail.purchaseOrderNo }}</router-link></el-descriptions-item>
          <el-descriptions-item label="购销含税口径">{{ detail.salePriceIncludesTax ? '销售含税' : '销售未税' }} / {{ detail.purchasePriceIncludesTax ? '采购含税' : '采购未税' }}</el-descriptions-item>
          <el-descriptions-item label="已形成应收">{{ money(detail.header.saleReceivableAmount) }}</el-descriptions-item>
          <el-descriptions-item label="已收/应收">{{ money(detail.header.receivedAmount) }} / {{ money(detail.header.saleReceivableAmount) }}</el-descriptions-item>
          <el-descriptions-item label="客户结清">{{ paymentName(detail.header.paymentStatus) }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewCost" label="已形成应付">{{ money(detail.header.purchasePayableAmount) }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewCost" label="已付/应付">{{ money(detail.header.paidAmount) }} / {{ money(detail.header.purchasePayableAmount) }}</el-descriptions-item>
          <el-descriptions-item v-if="canViewCost" label="已实现购销毛利"><strong :class="realizedMargin < 0 ? 'text-danger' : 'text-success'">{{ money(realizedMargin) }}</strong></el-descriptions-item>
          <el-descriptions-item label="备注" :span="3">{{ detail.memo || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.cancelReason" label="作废原因" :span="3">{{ detail.cancelReason }}</el-descriptions-item>
        </el-descriptions>
        <div v-if="!['DRAFT', 'CANCELLED'].includes(detail.header.status)" class="settlement-actions">
          <el-button v-if="appStore.hasPermission('payment.manage')" type="success" plain @click="openReceipt">登记客户收款</el-button>
          <el-button v-if="appStore.hasPermission('supplier.payment.manage')" type="warning" plain @click="openSupplierPayment">登记供应商付款</el-button>
          <span class="muted">收付款只核销已由客户实收形成的应收、应付。</span>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-header"><div class="panel-title">商品与交付进度</div></div>
      <el-table :data="detail.items" class="data-table" size="small" border show-overflow-tooltip>
        <el-table-column prop="productSku" label="SKU" width="145" />
        <el-table-column prop="productName" label="商品" min-width="190" />
        <el-table-column prop="productSpec" label="规格" min-width="150" />
        <el-table-column label="约定数量" width="120" align="right"><template #default="{ row }">{{ quantity(row.quantity) }} {{ row.baseUnit }}</template></el-table-column>
        <el-table-column label="净实收" width="120" align="right"><template #default="{ row }">{{ quantity(Number(row.deliveredQuantity) - Number(row.returnedQuantity)) }} {{ row.baseUnit }}</template></el-table-column>
        <el-table-column label="待交付" width="120" align="right"><template #default="{ row }">{{ quantity(row.remainingQuantity) }} {{ row.baseUnit }}</template></el-table-column>
        <el-table-column label="销售金额" width="125" align="right"><template #default="{ row }">{{ money(row.saleAmount) }}</template></el-table-column>
        <el-table-column v-if="canViewCost" label="采购金额" width="125" align="right"><template #default="{ row }">{{ money(row.purchaseAmount) }}</template></el-table-column>
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-header"><div class="panel-title">供应商发出与客户实收</div></div>
      <el-empty v-if="detail.deliveries.length === 0" description="尚未登记供应商发出" />
      <el-table v-else :data="detail.deliveries" class="data-table" size="small" border>
        <el-table-column prop="deliveryNo" label="批次号" width="210" />
        <el-table-column prop="businessDate" label="发出日期" width="110" />
        <el-table-column label="物流" min-width="180"><template #default="{ row }">{{ [row.carrierName, row.trackingNo].filter(Boolean).join(' / ') || '-' }}</template></el-table-column>
        <el-table-column label="发出/实收" width="150" align="right"><template #default="{ row }">{{ quantity(row.dispatchedQuantity) }} / {{ quantity(row.acceptedQuantity) }}</template></el-table-column>
        <el-table-column label="差异" min-width="180"><template #default="{ row }">{{ row.differenceReason || '-' }}</template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="row.status === 'DISPATCHED' ? 'warning' : 'success'">{{ deliveryStatusName(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }"><el-button v-if="row.status === 'DISPATCHED' && canConfirm" text type="primary" @click="openAccept(row)">确认客户实收</el-button></template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="detail.returns.length" class="panel">
      <div class="panel-header"><div class="panel-title">直运退货</div></div>
      <el-table :data="detail.returns" class="data-table" size="small" border>
        <el-table-column prop="returnNo" label="退货单号" width="210" />
        <el-table-column prop="businessDate" label="日期" width="110" />
        <el-table-column label="数量" width="120"><template #default="{ row }">{{ quantity(row.totalQuantity) }}</template></el-table-column>
        <el-table-column label="冲减应收" width="125" align="right"><template #default="{ row }">{{ money(row.saleReductionAmount) }}</template></el-table-column>
        <el-table-column v-if="canViewCost" label="冲减应付" width="125" align="right"><template #default="{ row }">{{ money(row.purchaseReductionAmount) }}</template></el-table-column>
        <el-table-column prop="reason" label="原因" min-width="180" />
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.status === 'REVERSED' ? 'info' : 'success'">{{ row.status === 'REVERSED' ? '已冲正' : '已确认' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="100"><template #default="{ row }"><el-button v-if="row.status === 'CONFIRMED' && canConfirm" text type="danger" @click="reverseReturn(row.id)">冲正</el-button></template></el-table-column>
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-header"><div class="panel-title">操作记录</div></div>
      <el-timeline class="event-timeline">
        <el-timeline-item v-for="item in detail.events" :key="item.id" :timestamp="formatDateTime(item.createdAt)" placement="top">
          <strong>{{ eventName(item.eventType) }}</strong>
          <span> · {{ item.operatorName }}</span>
          <div class="muted">{{ item.reason || `${item.fromStatus} → ${item.toStatus}` }}</div>
        </el-timeline-item>
      </el-timeline>
    </div>

    <DocumentSharePanel
      v-if="detail.header.status !== 'CANCELLED' && appStore.hasPermission('external.share.manage')"
      source-type="SALES_ORDER"
      :source-id="detail.salesOrderId"
    />

    <el-dialog v-model="dispatchVisible" title="登记供应商发出" width="720px" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="发出日期"><el-date-picker v-model="dispatchForm.businessDate" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="物流公司"><el-input v-model="dispatchForm.carrierName" /></el-form-item>
        <el-form-item label="物流单号"><el-input v-model="dispatchForm.trackingNo" /></el-form-item>
        <el-form-item label="供应商经办"><el-input v-model="dispatchForm.handlerName" /></el-form-item>
      </el-form>
      <el-table :data="dispatchForm.items" size="small" border>
        <el-table-column prop="name" label="商品" min-width="220" />
        <el-table-column prop="unit" label="单位" width="80" />
        <el-table-column label="本次发出" width="180"><template #default="{ row }"><el-input-number v-model="row.quantity" :min="0" :max="row.maximum" :precision="6" :controls="false" /></template></el-table-column>
      </el-table>
      <template #footer><el-button @click="dispatchVisible = false">取消</el-button><el-button type="primary" :loading="acting" @click="submitDispatch">保存发出</el-button></template>
    </el-dialog>

    <el-dialog v-model="acceptVisible" title="确认客户实收" width="720px" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="实收日期"><el-date-picker v-model="acceptForm.acceptedDate" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="签收人"><el-input v-model="acceptForm.recipientName" /></el-form-item>
        <el-form-item label="差异原因"><el-input v-model="acceptForm.differenceReason" placeholder="实收少于发出时必填" /></el-form-item>
      </el-form>
      <el-table :data="acceptForm.items" size="small" border>
        <el-table-column prop="name" label="商品" min-width="220" />
        <el-table-column label="发出数量" width="120"><template #default="{ row }">{{ quantity(row.maximum) }}</template></el-table-column>
        <el-table-column label="客户实收" width="180"><template #default="{ row }"><el-input-number v-model="row.quantity" :min="0" :max="row.maximum" :precision="6" :controls="false" /></template></el-table-column>
      </el-table>
      <template #footer><el-button @click="acceptVisible = false">取消</el-button><el-button type="primary" :loading="acting" @click="submitAccept">确认实收并过账</el-button></template>
    </el-dialog>

    <el-dialog v-model="returnVisible" title="登记直运退货" width="720px" append-to-body>
      <el-form label-width="90px">
        <el-form-item label="退货日期"><el-date-picker v-model="returnForm.businessDate" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="退货原因"><el-input v-model="returnForm.reason" maxlength="500" /></el-form-item>
      </el-form>
      <el-table :data="returnForm.items" size="small" border>
        <el-table-column prop="name" label="商品" min-width="220" />
        <el-table-column label="可退数量" width="120"><template #default="{ row }">{{ quantity(row.maximum) }}</template></el-table-column>
        <el-table-column label="本次退货" width="180"><template #default="{ row }"><el-input-number v-model="row.quantity" :min="0" :max="row.maximum" :precision="6" :controls="false" /></template></el-table-column>
      </el-table>
      <template #footer><el-button @click="returnVisible = false">取消</el-button><el-button type="primary" :loading="acting" @click="submitReturn">确认退货</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type DirectTradeDelivery } from '@/api/client'
import { currentDateText, formatDateTime, money, quantity } from '@/api/format'
import PageHeader from '@/components/PageHeader.vue'
import DocumentSharePanel from '@/components/DocumentSharePanel.vue'
import { useAppStore } from '@/stores/app'
import { useWorkspaceTabsStore, workspaceRouteKey } from '@/stores/workspaceTabs'

type QuantityRow = { id: number; name: string; unit?: string; maximum: number; quantity: number }
const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const workspaceTabs = useWorkspaceTabsStore()
const id = computed(() => Number(route.params.id))
const acting = ref(false)
const dispatchVisible = ref(false)
const acceptVisible = ref(false)
const returnVisible = ref(false)
const selectedDeliveryId = ref(0)
const dispatchForm = reactive({ businessDate: currentDateText(), carrierName: '', trackingNo: '', handlerName: '', items: [] as QuantityRow[] })
const acceptForm = reactive({ acceptedDate: currentDateText(), recipientName: '', differenceReason: '', items: [] as QuantityRow[] })
const returnForm = reactive({ businessDate: currentDateText(), reason: '', items: [] as QuantityRow[] })
const { data: detail, refetch } = useQuery({ queryKey: computed(() => ['direct-trade', id.value]), queryFn: () => api.directTradeDetail(id.value) })
watch(
  () => detail.value?.header.tradeNo,
  (tradeNo) => {
    if (tradeNo) workspaceTabs.setTitle(workspaceRouteKey(route), `直运购销 ${tradeNo}`)
  },
  { immediate: true },
)
const canViewCost = computed(() => appStore.hasPermission('inventory.cost.view') || appStore.hasPermission('purchase.view'))
const canConfirm = computed(() => appStore.hasPermission('direct.trade.confirm'))
const canDispatch = computed(() => canConfirm.value && ['CONFIRMED', 'PARTIAL_DELIVERED'].includes(detail.value?.header.status || '') && (detail.value?.items.some((item) => Number(item.remainingQuantity) > 0) ?? false))
const canReturn = computed(() => canConfirm.value && (detail.value?.items.some((item) => Number(item.deliveredQuantity) - Number(item.returnedQuantity) > 0) ?? false))
const realizedMargin = computed(() => (detail.value?.items ?? []).reduce((sum, item) => sum + Number(item.recognizedSaleAmount) - Number(item.recognizedPurchaseAmount), 0))
const statusLabels: Record<string, string> = { DRAFT: '暂存', CONFIRMED: '待交付', PARTIAL_DELIVERED: '部分交付', DELIVERED: '已交付', CANCELLED: '已作废' }
function statusName(value: string) { return statusLabels[value] || value }
function statusType(value: string) { return value === 'DELIVERED' ? 'success' : value === 'PARTIAL_DELIVERED' ? 'warning' : value === 'CANCELLED' || value === 'DRAFT' ? 'info' : 'primary' }
function deliveryStatusName(value: string) { return ({ DISPATCHED: '待客户确认', ACCEPTED: '已实收', PARTIAL_ACCEPTED: '差异实收' } as Record<string, string>)[value] || value }
function paymentName(value: string) { return ({ UNPAID: '未结清', PARTIAL: '部分结清', PAID: '已结清' } as Record<string, string>)[value] || value }
function eventName(value: string) {
  return ({
    CREATE: '创建草稿',
    CONFIRM: '确认购销',
    CANCEL: '作废',
    DISPATCH: '供应商发出',
    ACCEPT: '客户实收',
    ACCEPT_WITH_DIFFERENCE: '客户差异实收',
    RETURN: '直运退货',
    RETURN_REVERSE: '退货冲正',
  } as Record<string, string>)[value] || value
}
async function run(action: () => Promise<unknown>, message: string) {
  acting.value = true
  try { await action(); ElMessage.success(message); await refetch() } finally { acting.value = false }
}
async function confirmTrade() {
  try {
    await run(() => api.confirmDirectTrade(id.value), '直运购销已确认')
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    if (!message.includes('风险') && !message.includes('毛利')) throw error
    const result = await ElMessageBox.prompt('销售风险校验未通过。如确认继续，请填写真实业务依据；系统仍会保留审计记录。', '风险确认', {
      inputType: 'textarea',
      confirmButtonText: '确认并继续',
      cancelButtonText: '取消',
      inputValidator: (value) => String(value || '').trim().length >= 2 || '至少填写2个字',
    }).catch(() => null)
    if (result) await run(() => api.confirmDirectTrade(id.value, { riskOverrideReason: result.value.trim() }), '直运购销已按说明确认')
  }
}
async function cancelTrade() {
  const result = await ElMessageBox.prompt('作废不会生成库存或结算事实，请填写原因。', '作废直运购销', {
    inputType: 'textarea',
    confirmButtonText: '确认作废',
    cancelButtonText: '取消',
    inputValidator: (value) => String(value || '').trim().length >= 2 || '至少填写2个字',
  }).catch(() => null)
  if (result) await run(() => api.cancelDirectTrade(id.value, result.value.trim()), '直运购销已作废')
}
function openDispatch() {
  dispatchForm.businessDate = currentDateText()
  dispatchForm.carrierName = ''; dispatchForm.trackingNo = ''; dispatchForm.handlerName = ''
  dispatchForm.items = (detail.value?.items ?? []).filter((item) => Number(item.remainingQuantity) > 0).map((item) => ({ id: item.id, name: item.productName, unit: item.baseUnit, maximum: Number(item.remainingQuantity), quantity: Number(item.remainingQuantity) }))
  dispatchVisible.value = true
}
async function submitDispatch() {
  const items = dispatchForm.items.filter((item) => item.quantity > 0).map((item) => ({ itemId: item.id, quantity: item.quantity }))
  if (!dispatchForm.businessDate || !items.length) return ElMessage.warning('请选择发出日期并填写本次发出数量')
  await run(() => api.dispatchDirectTrade(id.value, { ...dispatchForm, items }), '供应商发出已登记')
  dispatchVisible.value = false
}
function openAccept(delivery: DirectTradeDelivery) {
  selectedDeliveryId.value = delivery.id
  acceptForm.acceptedDate = currentDateText(); acceptForm.recipientName = ''; acceptForm.differenceReason = ''
  acceptForm.items = delivery.items.map((item) => ({ id: item.id, name: item.productName, maximum: Number(item.dispatchedQuantity), quantity: Number(item.dispatchedQuantity) }))
  acceptVisible.value = true
}
async function submitAccept() {
  const difference = acceptForm.items.some((item) => item.quantity < item.maximum)
  if (!acceptForm.acceptedDate || !acceptForm.items.some((item) => item.quantity > 0)) return ElMessage.warning('请填写客户实收数量')
  if (difference && acceptForm.differenceReason.trim().length < 2) return ElMessage.warning('实收与发出不一致，请填写差异原因')
  await run(() => api.acceptDirectTradeDelivery(id.value, selectedDeliveryId.value, {
    acceptedDate: acceptForm.acceptedDate,
    recipientName: acceptForm.recipientName,
    differenceReason: acceptForm.differenceReason,
    items: acceptForm.items.map((item) => ({ deliveryItemId: item.id, acceptedQuantity: item.quantity })),
  }), '客户实收已确认，应收应付已同步形成')
  acceptVisible.value = false
}
function openReturn() {
  returnForm.businessDate = currentDateText(); returnForm.reason = ''
  returnForm.items = (detail.value?.items ?? []).map((item) => {
    const maximum = Number(item.deliveredQuantity) - Number(item.returnedQuantity)
    return { id: item.id, name: item.productName, unit: item.baseUnit, maximum, quantity: 0 }
  }).filter((item) => item.maximum > 0)
  returnVisible.value = true
}
async function submitReturn() {
  const items = returnForm.items.filter((item) => item.quantity > 0).map((item) => ({ itemId: item.id, quantity: item.quantity }))
  if (!returnForm.businessDate || returnForm.reason.trim().length < 2 || !items.length) return ElMessage.warning('请选择退货商品并填写至少2个字的原因')
  await run(() => api.createDirectTradeReturn(id.value, { businessDate: returnForm.businessDate, reason: returnForm.reason.trim(), items }), '直运退货已确认，应收应付已冲减')
  returnVisible.value = false
}
async function reverseReturn(returnId: number) {
  const result = await ElMessageBox.prompt('冲正后将恢复该退货对应的应收、应付，请填写原因。', '冲正直运退货', {
    inputType: 'textarea',
    confirmButtonText: '确认冲正',
    cancelButtonText: '取消',
    inputValidator: (value) => String(value || '').trim().length >= 2 || '至少填写2个字',
  }).catch(() => null)
  if (result) await run(() => api.reverseDirectTradeReturn(id.value, returnId, result.value.trim()), '直运退货已冲正')
}
function openReceipt() {
  router.push({ path: '/payments/new', query: { sourceType: 'SALES_ORDER', sourceId: detail.value?.salesOrderId, from: route.fullPath } })
}
function openSupplierPayment() {
  router.push({ path: '/supplier-payments/new', query: { sourceType: 'PURCHASE_ORDER', sourceId: detail.value?.purchaseOrderId, from: route.fullPath } })
}
</script>

<style scoped>
.settlement-actions { display: flex; align-items: center; gap: 12px; margin-top: 16px; }
.event-timeline { padding: 20px 26px 0; }
</style>
