<template>
  <div class="page" v-if="detail">
    <div class="panel">
      <PageHeader :title="`采购订单 ${detail.orderNo}`" :back-to="backPath">
        <el-button plain @click="printPurchaseOrder">打印采购订单</el-button>
        <el-button v-if="detail.status === 'DRAFT'" @click="$router.push(`/purchase-orders/${id}/edit`)">编辑草稿</el-button>
        <el-button v-if="['ORDERED', 'PARTIAL_RECEIVED'].includes(detail.status) && appStore.hasPermission('purchase.amend')" type="primary" plain @click="$router.push(`/purchase-orders/${id}/amend`)">变更订单</el-button>
        <el-button v-if="['DRAFT', 'ORDERED'].includes(detail.status)" type="danger" plain :loading="cancelling" @click="cancelOrder">{{ detail.status === 'DRAFT' ? '作废草稿' : '取消订单' }}</el-button>
        <el-button v-if="detail.status === 'DRAFT'" type="primary" :loading="confirming" :disabled="Boolean(confirmBlockedReason)" :title="confirmBlockedReason" @click="confirmOrder">确认订货</el-button>
      </PageHeader>
      <div class="panel-body">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="供应商">{{ detail.supplierName }}</el-descriptions-item>
          <el-descriptions-item label="采购日期">{{ formatDate(detail.businessDate) }}</el-descriptions-item>
          <el-descriptions-item label="预计到货">{{ formatDate(detail.expectedDate) }}</el-descriptions-item>
          <el-descriptions-item label="预计入库仓库">{{ detail.expectedWarehouseName || '确认前待指定' }}</el-descriptions-item>
          <el-descriptions-item label="方式">{{ purchaseSource(detail.sourceType) }}</el-descriptions-item>
          <el-descriptions-item label="凭证">{{ detail.sourceNote || '-' }}</el-descriptions-item>
          <el-descriptions-item label="订单状态">
            <el-tag :type="purchaseStatusType(detail.status)" size="small">{{ purchaseStatus(detail.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="采购量">{{ quantity(detail.totalQuantity) }}</el-descriptions-item>
          <el-descriptions-item label="已到货">{{ quantity(detail.receivedQuantity) }}</el-descriptions-item>
          <el-descriptions-item label="已付款">{{ money(detail.paidAmount) }}</el-descriptions-item>
          <el-descriptions-item label="价格口径">{{ detail.priceIncludesTax ? '含税价' : '未税价' }} / {{ detail.taxRateSummary }}</el-descriptions-item>
          <el-descriptions-item label="未税金额">{{ money(detail.amountExcludingTax) }}</el-descriptions-item>
          <el-descriptions-item label="税额">{{ money(detail.taxAmount) }}</el-descriptions-item>
          <el-descriptions-item label="付款状态">
            <el-tag v-if="['DRAFT', 'CANCELLED'].includes(detail.status)" type="info" size="small">不适用</el-tag>
            <el-tag v-else :type="paymentStatusType(detail.paymentStatus)" size="small">{{ paymentStatus(detail.paymentStatus) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="备注" :span="3">{{ detail.memo || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.status === 'CANCELLED'" label="作废原因" :span="2">{{ detail.cancelReason || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.status === 'CANCELLED'" label="作废时间">{{ formatDateTime(detail.cancelledAt) }}</el-descriptions-item>
        </el-descriptions>
      </div>
    </div>

    <div v-if="!['DRAFT', 'CANCELLED'].includes(detail.status)" class="panel">
      <div class="panel-header">
        <div class="panel-title">付款情况</div>
        <el-button type="primary" :disabled="remainingAmount <= 0" @click="paymentDialogVisible = true">付款</el-button>
      </div>
      <div class="panel-body">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="应付金额">{{ money(detail.totalAmount) }}</el-descriptions-item>
          <el-descriptions-item label="已付金额">{{ money(detail.paidAmount) }}</el-descriptions-item>
          <el-descriptions-item label="剩余未付">{{ money(remainingAmount) }}</el-descriptions-item>
          <el-descriptions-item label="付款状态">
            <el-tag :type="paymentStatusType(detail.paymentStatus)" size="small">{{ paymentStatus(detail.paymentStatus) }}</el-tag>
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <el-table :data="payments ?? []" class="data-table" size="small" border show-overflow-tooltip max-height="220">
        <el-table-column label="付款单号" width="190" fixed="left"><template #default="{ row }"><el-button link type="primary" @click="openPaymentRecord(row)">{{ row.paymentNo }}</el-button></template></el-table-column>
        <el-table-column prop="paidDate" label="日期" width="110" :formatter="formatDateColumn" />
        <el-table-column label="金额" width="130" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
        <el-table-column label="方式" width="130"><template #default="{ row }">{{ paymentMethodName(row.paymentMethod) }}</template></el-table-column>
        <el-table-column label="账户" width="130"><template #default="{ row }">{{ accountTypeName(row.accountType) }}</template></el-table-column>
        <el-table-column prop="verificationStatus" label="状态" width="110" />
      </el-table>
    </div>

    <div v-else-if="detail.status === 'DRAFT'" class="panel">
      <div class="panel-body">
        <el-alert title="当前订单为暂存，尚未形成应付；确认订货后才允许付款和入库。" type="info" :closable="false" show-icon />
      </div>
    </div>

    <div v-else class="panel">
      <div class="panel-body">
        <el-alert :title="`该草稿已作废，不会提交给供应商。原因：${detail.cancelReason || '-'}`" type="info" :closable="false" show-icon />
      </div>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">商品明细</div>
        <el-segmented v-model="itemViewMode" :options="documentItemViewOptions" size="small" />
      </div>
      <div class="order-total-bar">
        <span>合计金额：{{ money(detail.originalAmount) }}</span>
        <span>折扣比例：{{ percentage(detail.discountRate) }}</span>
        <span>折扣金额：-{{ money(detail.discountAmount) }}</span>
        <span>调整金额：{{ signedMoney(detail.adjustmentAmount) }}</span>
        <span>未税：{{ money(detail.amountExcludingTax) }}</span>
        <span>税额：{{ money(detail.taxAmount) }}</span>
        <span class="order-total-final">实际金额：{{ money(detail.totalAmount) }}</span>
      </div>
      <DocumentItemCompactTable v-if="itemViewMode === 'COMPACT'" :items="detail.items" kind="PURCHASE" />
      <DocumentItemMultilineView v-else-if="itemViewMode === 'MULTILINE'" :items="detail.items" kind="PURCHASE" />
      <el-table v-else :data="detail.items" class="data-table" size="small" border show-overflow-tooltip max-height="calc(100vh - 475px)">
        <el-table-column prop="sku" label="SKU" width="150" fixed="left" />
        <el-table-column label="商品" min-width="220" fixed="left">
          <template #default="{ row }">{{ productTitle(row) }}</template>
        </el-table-column>
        <el-table-column label="采购数量" width="120" align="right"><template #default="{ row }">{{ quantity(row.quantity) }}</template></el-table-column>
        <el-table-column label="已到货" width="120" align="right"><template #default="{ row }">{{ quantity(row.receivedQuantity) }}</template></el-table-column>
        <el-table-column prop="priceUnit" label="计价单位" width="100" />
        <el-table-column label="预计包装" width="120"><template #default="{ row }">{{ quantity(row.packageQuantity) }} {{ row.packageUnit || '-' }}</template></el-table-column>
        <el-table-column label="计量包装快照" min-width="210">
          <template #default="{ row }"><PackagingSnapshotCell :snapshot-json="row.packagingPlanJson" :requested-quantity="row.requestedQuantity" :requested-unit="row.requestedUnit" :quantity="row.quantity" :base-unit="row.baseUnit || row.unit" :pricing-quantity="row.pricingQuantity" :price-unit="row.priceUnit" :snapshot-hash="row.packagingSnapshotHash" /></template>
        </el-table-column>
        <el-table-column label="单价 / 来源" width="170" align="right">
          <template #default="{ row }">
            <div class="price-audit-cell">
              <span>{{ money(row.unitPrice, 4) }}</span>
              <span class="price-audit-source" :title="row.priceSnapshotJson || row.priceSourceName">{{ row.priceSourceName || '手工录入' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="税率" width="90" align="right"><template #default="{ row }">{{ row.taxRate }}%</template></el-table-column>
        <el-table-column label="未税" width="120" align="right"><template #default="{ row }">{{ money(row.amountExcludingTax) }}</template></el-table-column>
        <el-table-column label="税额" width="110" align="right"><template #default="{ row }">{{ money(row.taxAmount) }}</template></el-table-column>
        <el-table-column label="结算金额" width="130" align="right" fixed="right"><template #default="{ row }">{{ money(row.settlementTotalAmount) }}</template></el-table-column>
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">订单版本与承诺过账</div>
          <div class="muted">已确认订单的变更不会覆盖历史；系统先冲回原承诺，再按新版本重新过账。</div>
        </div>
      </div>
      <el-table :data="revisions || []" border size="small" class="data-table" empty-text="暂无版本记录">
        <el-table-column label="版本" width="90"><template #default="{ row }">V{{ row.revisionNo }}</template></el-table-column>
        <el-table-column label="类型" width="120"><template #default="{ row }">{{ revisionTypeName(row.changeType) }}</template></el-table-column>
        <el-table-column prop="changeReason" label="原因" min-width="260" />
        <el-table-column prop="operatorName" label="操作人" width="120" />
        <el-table-column label="时间" width="175"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
        <el-table-column label="过账批次" min-width="220">
          <template #default="{ row }">
            <RouterLink v-if="row.postedBatchId && appStore.hasPermission('posting.view')" class="business-link" :to="`/business-postings/${row.postedBatchId}`">新版本过账</RouterLink>
            <span v-else-if="row.postedBatchId">已过账</span>
            <span v-if="row.postedBatchId && row.reversedPostingBatchId"> · </span>
            <RouterLink v-if="row.reversedPostingBatchId && appStore.hasPermission('posting.view')" class="business-link" :to="`/business-postings/${row.reversedPostingBatchId}`">原版本冲回</RouterLink>
            <span v-else-if="row.reversedPostingBatchId">原版本已冲回</span>
            <span v-if="!row.postedBatchId && !row.reversedPostingBatchId">-</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="paymentDialogVisible" title="付款核销" width="920px">
      <SupplierPaymentForm
        v-if="detail"
        compact
        lock-source
        show-cancel
        submit-label="保存付款"
        :initial="{ supplierId: detail.supplierId, sourceType: 'PURCHASE_ORDER', sourceId: id, amount: remainingAmount }"
        @cancel="paymentDialogVisible = false"
        @success="handlePaymentSuccess"
      />
    </el-dialog>
    <PaymentRecordDrawer v-model="paymentRecordDrawerVisible" kind="PAYMENT" :record="activePaymentRecord" :origin-label="`采购订单 ${detail.orderNo}`" />

    <section class="print-sheet a4-print-sheet">
      <div class="document-print-heading">
        <div class="document-print-company">{{ appStore.settings.companyName }}</div>
        <div class="document-print-title">采购订单{{ detail.status === 'DRAFT' ? '（草稿）' : '' }}</div>
        <div class="document-print-subtitle">
          <span v-if="appStore.settings.companyPhone">电话：{{ appStore.settings.companyPhone }}</span>
          <span>打印时间：{{ printTime }}</span>
        </div>
      </div>
      <table class="document-print-meta">
        <tbody>
          <tr>
            <td>订单编号：{{ detail.orderNo }}</td>
            <td>采购日期：{{ formatDate(detail.businessDate) }}</td>
            <td>预计到货：{{ formatDate(detail.expectedDate) }}</td>
          </tr>
          <tr>
            <td>供应商：{{ detail.supplierName }}</td>
            <td>状态：{{ purchaseStatus(detail.status) }}</td>
            <td>制单人：{{ appStore.currentUserName }}</td>
          </tr>
          <tr><td colspan="3">来源凭证：{{ detail.sourceNote || '-' }}</td></tr>
        </tbody>
      </table>
      <table class="document-print-table">
        <thead><tr><th>#</th><th>商品 / SKU</th><th>规格</th><th>采购数量</th><th>预计包装</th><th>已到货</th><th>单价</th><th>金额</th></tr></thead>
        <tbody>
          <tr v-for="(row, index) in detail.items" :key="row.id">
            <td class="center">{{ index + 1 }}</td>
            <td>{{ row.productName }} / {{ row.sku }}</td>
            <td>{{ row.spec || '-' }}</td>
            <td class="right">{{ quantity(row.requestedQuantity ?? row.quantity) }} {{ row.requestedUnit || row.baseUnit || row.unit }}</td>
            <td class="center">{{ Number(row.packageQuantity) > 0 ? `${quantity(row.packageQuantity)} ${row.packageUnit}` : '-' }}</td>
            <td class="right">{{ quantity(row.receivedQuantity) }} {{ row.baseUnit || row.unit }}</td>
            <td class="right">{{ money(row.unitPrice, 4) }} / {{ row.priceUnit }}</td>
            <td class="right">{{ money(row.amount) }}</td>
          </tr>
        </tbody>
      </table>
      <table class="document-print-total">
        <tbody>
          <tr><td>原金额：{{ money(detail.originalAmount) }}</td><td>折扣：-{{ money(detail.discountAmount) }}</td><td>调整：{{ signedMoney(detail.adjustmentAmount) }}</td><td>订单金额：{{ money(detail.totalAmount) }}</td></tr>
          <tr><td colspan="4">备注：{{ detail.memo || '无' }}</td></tr>
        </tbody>
      </table>
      <div class="document-print-signatures"><span>制单：</span><span>采购：</span><span>供应商确认：</span><span>审核：</span></div>
    </section>
    <PurchaseFollowupPanel
      v-if="detail.sourceType !== 'DIRECT_TRADE' && detail.status !== 'CANCELLED'"
      :purchase-order-id="id"
    />
    <PurchaseQuoteComparisonPanel
      :purchase-order-id="id"
      :status="detail.status"
      :items="detail.items"
    />
    <DocumentSharePanel
      v-if="detail.status !== 'CANCELLED' && appStore.hasPermission('external.share.manage')"
      source-type="PURCHASE_ORDER"
      :source-id="id"
    />
    <EvidenceAttachments
      document-type="PURCHASE_ORDER"
      :document-id="id"
      title="采购确认、合同与供应商签字凭据"
      default-evidence-type="ORIGINAL_DOCUMENT"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type PurchaseOrderItemDetail, type SupplierPaymentRow } from '@/api/client'
import { accountTypeName, formatDate, formatDateColumn, formatDateTime, money, paymentMethodName, paymentStatus, paymentStatusType, purchaseSource, purchaseStatus, purchaseStatusType, quantity } from '@/api/format'
import PageHeader from '@/components/PageHeader.vue'
import SupplierPaymentForm from '@/components/SupplierPaymentForm.vue'
import PackagingSnapshotCell from '@/components/PackagingSnapshotCell.vue'
import PaymentRecordDrawer from '@/components/PaymentRecordDrawer.vue'
import DocumentItemMultilineView from '@/components/DocumentItemMultilineView.vue'
import DocumentItemCompactTable from '@/components/DocumentItemCompactTable.vue'
import EvidenceAttachments from '@/components/EvidenceAttachments.vue'
import PurchaseQuoteComparisonPanel from '@/components/PurchaseQuoteComparisonPanel.vue'
import PurchaseFollowupPanel from '@/components/PurchaseFollowupPanel.vue'
import DocumentSharePanel from '@/components/DocumentSharePanel.vue'
import { useDocumentItemView } from '@/composables/useDocumentItemView'
import { printFileName, useDocumentPrint } from '@/composables/useDocumentPrint'
import { returnPath } from '@/utils/navigation'
import { useAppStore } from '@/stores/app'

const route = useRoute()
const queryClient = useQueryClient()
const appStore = useAppStore()
const id = Number(route.params.id)
const { data: detail, refetch } = useQuery({ queryKey: ['purchase-order-detail', id], queryFn: () => api.purchaseOrderDetail(id) })
const { data: payments } = useQuery({ queryKey: ['supplier-payments', 'purchase-order', id], queryFn: () => api.supplierPayments({ purchaseOrderId: id }) })
const { data: revisions } = useQuery({ queryKey: ['business-order-revisions', 'PURCHASE_ORDER', id], queryFn: () => api.purchaseOrderRevisions(id) })
const paymentDialogVisible = ref(false)
const paymentRecordDrawerVisible = ref(false)
const activePaymentRecord = ref<SupplierPaymentRow>()
const backPath = computed(() => returnPath(route, '/purchase-orders'))
const remainingAmount = computed(() => Math.max(0, Number(detail.value?.totalAmount ?? 0) - Number(detail.value?.paidAmount ?? 0)))
const confirmBlockedReason = computed(() => {
  if (!detail.value) return ''
  if (!detail.value.expectedWarehouseId) return '请先编辑草稿并选择预计入库仓库'
  if (!detail.value.expectedDate) return '请先编辑草稿并填写预计到货日期'
  return ''
})
const { itemViewMode, documentItemViewOptions } = useDocumentItemView()
const { printTime, printDocument } = useDocumentPrint(['is-a4-printing'])
function openPaymentRecord(payment: SupplierPaymentRow) {
  activePaymentRecord.value = payment
  paymentRecordDrawerVisible.value = true
}

async function printPurchaseOrder() {
  if (!detail.value) return
  await printDocument(printFileName(detail.value.supplierName || '供应商', '采购订单', detail.value.orderNo || '未编号'))
}
const confirmMutation = useMutation({
  mutationFn: () => api.confirmPurchaseOrder(id),
  onSuccess: async () => {
    ElMessage.success('采购订单已确认订货')
    await Promise.all([
      refetch(),
      queryClient.invalidateQueries({ queryKey: ['purchase-orders'] }),
      queryClient.invalidateQueries({ queryKey: ['payables'] }),
      queryClient.invalidateQueries({ queryKey: ['partner-summaries'] }),
      queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
      queryClient.invalidateQueries({ queryKey: ['inventory-atp'] }),
    ])
  },
})
const confirming = computed(() => confirmMutation.isPending.value)
const cancelMutation = useMutation({
  mutationFn: (reason: string) => api.cancelPurchaseOrder(id, reason),
  onSuccess: async () => {
    ElMessage.success(detail.value?.status === 'DRAFT' ? '采购订单草稿已作废' : '采购订单已取消，承诺过账已冲回')
    await Promise.all([
      refetch(),
      queryClient.invalidateQueries({ queryKey: ['purchase-orders'] }),
      queryClient.invalidateQueries({ queryKey: ['payables'] }),
      queryClient.invalidateQueries({ queryKey: ['partner-summaries'] }),
      queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
      queryClient.invalidateQueries({ queryKey: ['inventory-atp'] }),
    ])
  },
})
const cancelling = computed(() => cancelMutation.isPending.value)

async function confirmOrder() {
  const confirmed = await ElMessageBox.confirm('确认后订单将进入“已订货”，不能再直接修改。是否继续？', '确认订货', {
    type: 'warning',
    confirmButtonText: '确认订货',
    cancelButtonText: '继续编辑',
  }).then(() => true).catch(() => false)
  if (!confirmed || !detail.value || detail.value.status !== 'DRAFT') return
  confirmMutation.mutate()
}

function productTitle(row: PurchaseOrderItemDetail) {
  return [row.productName, row.spec].filter(Boolean).join(' / ')
}

function signedMoney(value: number) {
  const amount = Number(value || 0)
  return `${amount >= 0 ? '+' : '-'}${money(Math.abs(amount))}`
}

function percentage(value: number) {
  return `${Number(value || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}%`
}

async function cancelOrder() {
  if (!detail.value || !['DRAFT', 'ORDERED'].includes(detail.value.status)) return
  const draft = detail.value.status === 'DRAFT'
  const result = await ElMessageBox.prompt(
    draft ? '作废后不能再编辑或确认订货，且该订单不会提交给供应商。请填写作废原因。' : '取消会冲回采购承诺；已有入库或付款时系统会拒绝。请填写取消原因。',
    draft ? '作废采购订单草稿' : '取消已确认采购订单',
    {
      type: 'warning',
      confirmButtonText: '确认作废',
      cancelButtonText: '取消',
      inputPlaceholder: '请输入作废原因',
      inputValidator: (value) => {
        const reason = String(value || '').trim()
        if (!reason) return '请填写作废原因'
        if (reason.length > 500) return '作废原因不能超过500字'
        return true
      },
    },
  ).catch(() => null)
  if (!result) return
  cancelMutation.mutate(result.value.trim())
}

function revisionTypeName(value: string) {
  return ({ INITIAL: '初始版本', AMENDMENT: '订单变更', CANCEL: '订单取消' } as Record<string, string>)[value] || value
}

async function handlePaymentSuccess() {
  paymentDialogVisible.value = false
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['purchase-order-detail', id] }),
    queryClient.invalidateQueries({ queryKey: ['supplier-payments', 'purchase-order', id] }),
  ])
}
</script>

<style scoped>
.price-audit-cell {
  display: flex;
  flex-direction: column;
  min-width: 0;
  line-height: 1.35;
}

.price-audit-source {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
