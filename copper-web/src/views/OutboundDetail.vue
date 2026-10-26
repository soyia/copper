<template>
  <div class="page" v-if="detail">
    <div class="panel">
      <PageHeader :title="`销售出库 ${detail.orderNo}`" :back-to="backPath">
        <el-button v-if="detail.status === 'DRAFT'" @click="$router.push({ path: `/outbounds/${id}/edit`, query: detail.salesOrderId ? { salesOrderId: detail.salesOrderId } : {} })">编辑草稿</el-button>
        <el-button v-if="detail.status === 'DRAFT'" type="danger" plain @click="cancelOutbound">作废草稿</el-button>
        <el-button
          v-if="detail.status === 'DRAFT'"
          type="primary"
          :disabled="detail.shipmentPaymentPolicy !== 'NONE' && Number(detail.shipmentPaymentShortfall) > 0"
          :title="detail.shipmentPaymentPolicy !== 'NONE' && Number(detail.shipmentPaymentShortfall) > 0 ? `放货资金还差 ${money(detail.shipmentPaymentShortfall)}` : ''"
          @click="confirmOutbound"
        >确认发货</el-button>
        <el-button v-if="detail.completenessStatus === 'TO_COMPLETE' && !['REVERSED', 'CANCELLED'].includes(detail.status)" @click="completeInformation">补录单据信息</el-button>
        <el-button v-if="detail.status === 'SHIPPED' && detail.settlementStatus === 'PENDING_PRICING'" type="warning" @click="openSettlement">确定最终价格并结算</el-button>
        <el-button v-if="detail.status === 'SHIPPED'" @click="openReturn">销售退货</el-button>
        <el-button v-if="detail.status === 'SHIPPED'" type="danger" plain :disabled="!detail.salesOrderId && Number(detail.paidAmount) > 0" :title="!detail.salesOrderId && Number(detail.paidAmount) > 0 ? '已有回款请办理销售退货' : ''" @click="reverseOutbound">反向出库</el-button>
        <el-button v-if="detail.status === 'SHIPPED'" type="primary" plain @click="printOutbound">针式打印</el-button>
      </PageHeader>
      <div class="panel-body">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="客户">{{ detail.customerName }}</el-descriptions-item>
          <el-descriptions-item label="仓库">{{ detail.warehouseName }}</el-descriptions-item>
          <el-descriptions-item label="出库日期">{{ formatDate(detail.businessDate) }}</el-descriptions-item>
          <el-descriptions-item label="实际发货">{{ formatDateTime(detail.actualShippedAt) }}</el-descriptions-item>
          <el-descriptions-item label="来源">{{ sourceTypeName(detail.sourceType, detail.sourceChannel) }}</el-descriptions-item>
          <el-descriptions-item label="物理状态">{{ outboundStatusName(detail.status) }}</el-descriptions-item>
          <el-descriptions-item label="信息完整度">{{ detail.completenessStatus === 'TO_COMPLETE' ? '待补录单据信息' : '信息已完整' }}</el-descriptions-item>
          <el-descriptions-item label="结算状态">{{ settlementStatusName(detail.settlementStatus) }}</el-descriptions-item>
          <el-descriptions-item label="收货地址" :span="2">{{ formatPhone(detail.addressSnapshot) }}</el-descriptions-item>
          <el-descriptions-item label="销售订单">
            <router-link v-if="detail.salesOrderId" class="table-link" :to="relatedDetailRoute(route, `/sales-orders/${detail.salesOrderId}`)">{{ detail.salesOrderNo }}</router-link>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="总数量">{{ quantity(detail.totalQuantity) }}</el-descriptions-item>
          <el-descriptions-item label="总金额">{{ money(detail.totalAmount) }}</el-descriptions-item>
          <el-descriptions-item :label="detail.salesOrderId ? '回款归集' : '已回款'">{{ detail.salesOrderId ? '按销售订单统一核销' : money(detail.paidAmount) }}</el-descriptions-item>
          <el-descriptions-item :label="detail.salesOrderId ? '核销查看' : '回款状态'">
            <router-link v-if="detail.salesOrderId" class="table-link" :to="relatedDetailRoute(route, `/sales-orders/${detail.salesOrderId}`)">查看销售订单</router-link>
            <el-tag v-else :type="paymentStatusType(detail.paymentStatus)" size="small">{{ paymentStatus(detail.paymentStatus) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="原金额">{{ money(detail.originalAmount) }}</el-descriptions-item>
          <el-descriptions-item label="折扣%">{{ money(detail.discountRate) }}</el-descriptions-item>
          <el-descriptions-item label="调整金额">{{ money(detail.adjustmentAmount) }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="3">{{ detail.memo || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.status === 'CANCELLED'" label="作废原因" :span="2">{{ detail.cancelReason || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.status === 'CANCELLED'" label="作废时间">{{ formatDateTime(detail.cancelledAt) }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.entryMode === 'BACKFILL'" label="补录原因" :span="3">{{ detail.lateEntryReason || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.reversalReason" label="反向出库" :span="3">{{ detail.reversalNo }} / {{ detail.reversalReason }}</el-descriptions-item>
        </el-descriptions>
      </div>
    </div>

    <SalesRiskControlPanel document-type="OUTBOUND" :document-id="id" />

    <div v-if="detail.shipmentPaymentPolicy !== 'NONE'" class="panel">
      <div class="panel-header">
        <div class="panel-title">款到发货控制</div>
        <el-button
          v-if="detail.status === 'DRAFT' && detail.salesOrderId"
          type="primary"
          @click="$router.push(`/sales-orders/${detail.salesOrderId}`)"
        >前往订单补款</el-button>
        <el-button
          v-else-if="detail.status === 'DRAFT'"
          type="primary"
          @click="paymentDialogVisible = true"
        >登记发货预收款</el-button>
      </div>
      <div class="panel-body">
        <el-alert
          title="只认可系统已登记到账的银行、现金及已占用预收款；付款截图、口头承诺和未成熟承兑不计入放货资金。"
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom:14px"
        />
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="控制方式">{{ shipmentPaymentPolicyName(detail.shipmentPaymentPolicy) }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.settlementStatus === 'PENDING_PRICING'" label="暂估最高金额">{{ money(detail.shipmentControlAmount) }}</el-descriptions-item>
          <el-descriptions-item label="放货要求资金">{{ money(detail.shipmentRequiredAmount) }}</el-descriptions-item>
          <el-descriptions-item label="已落实资金">{{ money(detail.shipmentFundedAmount) }}</el-descriptions-item>
          <el-descriptions-item label="尚差">
            <strong :class="Number(detail.shipmentPaymentShortfall) > 0 ? 'text-danger' : 'text-success'">{{ money(detail.shipmentPaymentShortfall) }}</strong>
          </el-descriptions-item>
          <el-descriptions-item v-if="!detail.salesOrderId" label="客户可用预收">{{ money(detail.customerAdvanceAvailable) }}</el-descriptions-item>
        </el-descriptions>
      </div>
    </div>

    <div v-if="!detail.salesOrderId && detail.status === 'SHIPPED' && detail.settlementStatus === 'SETTLED'" class="panel">
      <div class="panel-header">
        <div class="panel-title">回款情况</div>
        <el-button type="primary" :disabled="remainingAmount <= 0" @click="paymentDialogVisible = true">收款</el-button>
      </div>
      <div class="panel-body">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="应收金额">{{ money(detail.receivableAmount) }}</el-descriptions-item>
          <el-descriptions-item label="已收金额">{{ money(detail.paidAmount) }}</el-descriptions-item>
          <el-descriptions-item label="剩余未收">{{ money(remainingAmount) }}</el-descriptions-item>
          <el-descriptions-item label="回款状态">
            <el-tag :type="paymentStatusType(detail.paymentStatus)" size="small">{{ paymentStatus(detail.paymentStatus) }}</el-tag>
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <el-table :data="payments ?? []" class="data-table" size="small" border show-overflow-tooltip max-height="220">
        <el-table-column label="回款单号" width="190" fixed="left"><template #default="{ row }"><el-button link type="primary" @click="openPaymentRecord(row)">{{ row.paymentNo }}</el-button></template></el-table-column>
        <el-table-column prop="receivedDate" label="日期" width="110" :formatter="formatDateColumn" />
        <el-table-column label="金额" width="130" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
        <el-table-column label="方式" width="130"><template #default="{ row }">{{ paymentMethodName(row.paymentMethod) }}</template></el-table-column>
        <el-table-column label="账户" width="130"><template #default="{ row }">{{ accountTypeName(row.accountType) }}</template></el-table-column>
        <el-table-column prop="verificationStatus" label="核销状态" width="110" />
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">商品明细</div>
        <el-segmented v-model="itemViewMode" :options="documentItemViewOptions" size="small" />
      </div>
      <DocumentItemCompactTable v-if="itemViewMode === 'COMPACT'" :items="detail.items" kind="OUTBOUND" />
      <DocumentItemMultilineView v-else-if="itemViewMode === 'MULTILINE'" :items="detail.items" kind="OUTBOUND" />
      <el-table v-else :data="detail.items" class="data-table" size="small" border show-overflow-tooltip max-height="calc(100vh - 420px)">
        <el-table-column prop="sku" label="SKU" width="150" fixed="left" />
        <el-table-column label="商品" min-width="220" fixed="left">
          <template #default="{ row }">{{ productTitle(row) }}</template>
        </el-table-column>
        <el-table-column prop="spec" label="规格摘要" min-width="260" />
        <el-table-column prop="batchNo" label="批次号" min-width="150" />
        <el-table-column label="数量" width="120" align="right"><template #default="{ row }">{{ quantity(row.quantity) }}</template></el-table-column>
        <el-table-column label="包装数" width="110" align="right"><template #default="{ row }">{{ quantity(row.packageQuantity) }}</template></el-table-column>
        <el-table-column prop="priceUnit" label="计价单位" width="100" />
        <el-table-column label="计量包装快照" min-width="210">
          <template #default="{ row }"><PackagingSnapshotCell :snapshot-json="row.packagingPlanJson" :requested-quantity="row.requestedQuantity" :requested-unit="row.requestedUnit" :quantity="row.quantity" :base-unit="row.baseUnit || row.unit" :pricing-quantity="row.pricingQuantity" :price-unit="row.priceUnit" :snapshot-hash="row.packagingSnapshotHash" /></template>
        </el-table-column>
        <el-table-column label="销售单价" width="120" align="right"><template #default="{ row }">{{ money(row.unitPrice, 4) }}</template></el-table-column>
        <el-table-column label="金额" width="130" align="right" fixed="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
        <el-table-column label="已退数量" width="110" align="right"><template #default="{ row }">{{ quantity(row.returnedQuantity) }}</template></el-table-column>
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-header"><div class="panel-title">销售退货记录</div></div>
      <el-table :data="salesReturns ?? []" class="data-table" size="small" border empty-text="暂无销售退货">
        <el-table-column prop="returnNo" label="退货单号" width="190" />
        <el-table-column prop="businessDate" label="日期" width="110" :formatter="formatDateColumn" />
        <el-table-column label="数量" width="120" align="right"><template #default="{ row }">{{ quantity(row.totalQuantity) }}</template></el-table-column>
        <el-table-column label="退货金额" width="130" align="right"><template #default="{ row }">{{ money(row.totalAmount) }}</template></el-table-column>
        <el-table-column label="未税" width="120" align="right"><template #default="{ row }">{{ money(row.amountExcludingTax) }}</template></el-table-column>
        <el-table-column label="税额" width="110" align="right"><template #default="{ row }">{{ money(row.taxAmount) }}</template></el-table-column>
        <el-table-column label="转预收" width="120" align="right"><template #default="{ row }">{{ money(row.advanceCreditAmount) }}</template></el-table-column>
        <el-table-column prop="reason" label="原因" min-width="180" />
        <el-table-column label="状态" width="100"><template #default="{ row }">{{ row.status === 'REVERSED' ? '已冲正' : '已确认' }}</template></el-table-column>
        <el-table-column label="操作" width="150"><template #default="{ row }"><el-button text type="primary" @click="returnEvidenceId = row.id">附件</el-button><el-button v-if="row.status === 'CONFIRMED'" text type="danger" @click="reverseReturn(row.id)">冲正</el-button></template></el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="settlementDialogVisible" title="补价格并完成结算" width="720px">
      <el-alert title="结算后将按最终价格形成应收；已出库的库存数量不会再次变化。" type="warning" :closable="false" style="margin-bottom:14px" />
      <el-table :data="settlementItems" size="small" border>
        <el-table-column prop="productName" label="商品" min-width="220" />
        <el-table-column label="数量" width="120"><template #default="{ row }">{{ quantity(row.quantity) }}</template></el-table-column>
        <el-table-column label="最终单价" width="180"><template #default="{ row }"><el-input-number v-model="row.unitPrice" :min="0.0001" :precision="4" :controls="false" /></template></el-table-column>
      </el-table>
      <el-input v-model="settlementReason" placeholder="定价依据或说明" style="margin-top:12px" />
      <template #footer><el-button @click="settlementDialogVisible=false">取消</el-button><el-button type="primary" @click="submitSettlement">确认结算</el-button></template>
    </el-dialog>

    <el-dialog v-model="returnDialogVisible" title="新增销售退货" width="760px">
      <el-alert title="退货将恢复原批次的散装基础库存并减少应收；已收金额超过剩余应收的部分转入客户预收余额。" type="warning" :closable="false" style="margin-bottom:14px" />
      <el-date-picker v-model="returnDate" value-format="YYYY-MM-DD" style="margin-bottom:12px" />
      <el-table :data="returnItems" size="small" border>
        <el-table-column prop="productName" label="商品" min-width="220" />
        <el-table-column label="可退" width="120"><template #default="{ row }">{{ quantity(row.available) }}</template></el-table-column>
        <el-table-column label="本次退货" width="180"><template #default="{ row }"><el-input-number v-model="row.quantity" :min="0" :max="row.available" :precision="6" :controls="false" /></template></el-table-column>
      </el-table>
      <el-input v-model="returnReason" placeholder="退货原因（必填）" style="margin-top:12px" />
      <template #footer><el-button @click="returnDialogVisible=false">取消</el-button><el-button type="primary" @click="submitReturn">确认退货</el-button></template>
    </el-dialog>

    <el-dialog :model-value="returnEvidenceId > 0" title="销售退货签收与留存附件" width="1000px" destroy-on-close @close="returnEvidenceId = 0">
      <EvidenceAttachments v-if="returnEvidenceId" document-type="SALES_RETURN" :document-id="returnEvidenceId" title="退货接收、客户确认与冲正凭据" default-evidence-type="SIGNED_RECEIPT" />
    </el-dialog>

    <el-dialog v-model="paymentDialogVisible" :title="detail.status === 'DRAFT' ? '登记发货预收款' : '回款核销'" width="920px">
      <PaymentForm
        v-if="detail"
        compact
        lock-source
        show-cancel
        submit-label="保存回款"
        :initial="detail.status === 'DRAFT'
          ? { customerId: detail.customerId }
          : { customerId: detail.customerId, sourceType: 'OUTBOUND', sourceId: id, amount: remainingAmount }"
        :balance-amount="detail.status === 'DRAFT' ? undefined : remainingAmount"
        @cancel="paymentDialogVisible = false"
        @success="handlePaymentSuccess"
      />
    </el-dialog>

    <PaymentRecordDrawer v-model="paymentRecordDrawerVisible" kind="RECEIPT" :record="activePaymentRecord" :origin-label="`销售出库 ${detail.orderNo}`" />

    <section class="print-sheet dot-print-sheet">
      <div class="dot-print-company">{{ appStore.settings.companyName }}</div>
      <div class="dot-print-title">销售出库单</div>
      <div class="dot-print-subtitle">
        <span v-if="appStore.settings.companyPhone">电话：{{ appStore.settings.companyPhone }}</span>
        <span>打印时间：{{ printTime }}</span>
      </div>

      <table class="dot-print-meta">
        <tbody>
          <tr>
            <td>出库单号：{{ detail.orderNo }}</td>
            <td>日期：{{ formatDate(detail.businessDate) }}</td>
            <td>仓库：{{ detail.warehouseName }}</td>
          </tr>
          <tr>
            <td>客户：{{ detail.customerName }}</td>
            <td>销售订单：{{ detail.salesOrderNo || '-' }}</td>
            <td>制单人：{{ appStore.currentUserName }}</td>
          </tr>
          <tr>
            <td colspan="3">收货地址：{{ formatPhone(detail.addressSnapshot) || '-' }}</td>
          </tr>
        </tbody>
      </table>

      <table class="dot-print-table">
        <thead>
          <tr>
            <th>序号</th>
            <th>商品</th>
            <th>规格</th>
            <th>批次</th>
            <th>数量</th>
            <th>包装数</th>
            <th>单位</th>
            <th>单价</th>
            <th>金额</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in detail.items" :key="row.id">
            <td class="center">{{ index + 1 }}</td>
            <td>{{ printProductTitle(row) }}</td>
            <td>{{ row.spec || '-' }}</td>
            <td>{{ row.batchNo || '-' }}</td>
            <td class="right">{{ quantity(row.quantity) }}</td>
            <td class="right">{{ quantity(row.packageQuantity) }}</td>
            <td class="center">{{ row.priceUnit || row.unit || '-' }}</td>
            <td class="right">{{ money(row.unitPrice, 4) }}</td>
            <td class="right">{{ money(row.amount) }}</td>
          </tr>
        </tbody>
      </table>

      <table class="dot-print-total">
        <tbody>
          <tr>
            <td>合计数量：{{ quantity(detail.totalQuantity) }}</td>
            <td>合计包装数：{{ quantity(detail.packageQuantity) }}</td>
            <td>合计金额：{{ money(detail.totalAmount) }}</td>
          </tr>
          <tr>
            <td colspan="3">备注：{{ detail.memo || '' }}</td>
          </tr>
        </tbody>
      </table>

      <div class="dot-print-signatures">
        <span>制单：</span>
        <span>仓库：</span>
        <span>送货：</span>
        <span>客户签收：</span>
      </div>
    </section>
    <OutboundDeliveryPanel
      v-if="!['DRAFT', 'CANCELLED'].includes(detail.status)"
      :outbound-id="id"
      :outbound-status="detail.status"
    />
    <SalesContributionPanel
      v-if="!detail.salesOrderId && detail.status === 'SHIPPED' && appStore.hasPermission('operations.insight.view')"
      source-type="OUTBOUND"
      :source-id="id"
    />
    <EvidenceAttachments
      document-type="OUTBOUND"
      :document-id="id"
      title="发货、出库与客户签收凭据"
      default-evidence-type="DELIVERY_PROOF"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type OutboundItemDetail, type PaymentRow } from '@/api/client'
import { accountTypeName, currentDateText, formatDate, formatDateColumn, formatDateTime, formatPhone, money, paymentMethodName, paymentStatus, paymentStatusType, quantity } from '@/api/format'
import PageHeader from '@/components/PageHeader.vue'
import PaymentForm from '@/components/PaymentForm.vue'
import PackagingSnapshotCell from '@/components/PackagingSnapshotCell.vue'
import PaymentRecordDrawer from '@/components/PaymentRecordDrawer.vue'
import DocumentItemMultilineView from '@/components/DocumentItemMultilineView.vue'
import DocumentItemCompactTable from '@/components/DocumentItemCompactTable.vue'
import EvidenceAttachments from '@/components/EvidenceAttachments.vue'
import OutboundDeliveryPanel from '@/components/OutboundDeliveryPanel.vue'
import SalesContributionPanel from '@/components/SalesContributionPanel.vue'
import SalesRiskControlPanel from '@/components/SalesRiskControlPanel.vue'
import { useDocumentItemView } from '@/composables/useDocumentItemView'
import { printFileName, useDocumentPrint } from '@/composables/useDocumentPrint'
import { relatedDetailRoute, returnPath } from '@/utils/navigation'
import { useAppStore } from '@/stores/app'
import { resolveSalesRiskOverride } from '@/utils/salesRisk'

const route = useRoute()
const queryClient = useQueryClient()
const appStore = useAppStore()
const id = Number(route.params.id)
const { data: detail } = useQuery({ queryKey: ['outbound-detail', id], queryFn: () => api.outboundDetail(id) })
const { data: payments } = useQuery({ queryKey: ['payments', 'outbound', id], queryFn: () => api.payments({ outboundId: id }) })
const { data: salesReturns } = useQuery({ queryKey: ['sales-returns', 'outbound', id], queryFn: () => api.salesReturns({ outboundId: id }) })
const paymentDialogVisible = ref(false)
const paymentRecordDrawerVisible = ref(false)
const activePaymentRecord = ref<PaymentRow>()
const backPath = computed(() => returnPath(route, '/outbounds'))
const remainingAmount = computed(() => Math.max(0, Number(detail.value?.receivableAmount ?? 0) - Number(detail.value?.paidAmount ?? 0)))
const { printTime, printDocument } = useDocumentPrint()
const settlementDialogVisible = ref(false)
const settlementReason = ref('')
const settlementItems = ref<Array<{ id: number; productName: string; quantity: number; unitPrice: number }>>([])
const returnDialogVisible = ref(false)
const returnEvidenceId = ref(0)
const returnDate = ref(currentDateText())
const returnReason = ref('')
const returnItems = ref<Array<{ id: number; productName: string; available: number; quantity: number }>>([])
const { itemViewMode, documentItemViewOptions } = useDocumentItemView()

function productTitle(row: OutboundItemDetail) {
  return [row.productName, row.unit ? `单位:${row.unit}` : ''].filter(Boolean).join(' / ')
}

function printProductTitle(row: OutboundItemDetail) {
  return [row.sku, row.productName].filter(Boolean).join(' / ')
}

function openPaymentRecord(payment: PaymentRow) {
  activePaymentRecord.value = payment
  paymentRecordDrawerVisible.value = true
}

function sourceTypeName(sourceType: string, channel: string) {
  if (sourceType === 'SALES_ORDER') return '销售订单'
  return ({ PHONE: '无订单 / 电话', WECHAT: '无订单 / 微信', ON_SITE: '无订单 / 现场', OTHER: '无订单 / 其他' } as Record<string, string>)[channel] || '无订单直接出库'
}

function outboundStatusName(status: string) {
  return ({ DRAFT: '草稿', SHIPPED: '已发货', REVERSED: '已反向出库', CANCELLED: '已作废' } as Record<string, string>)[status] || status
}

function settlementStatusName(status: string) {
  return ({ READY: '价格已约定，待发货结算', PENDING_PRICING: '最终价格待确定', SETTLED: '已结算' } as Record<string, string>)[status] || status
}

function shipmentPaymentPolicyName(value: string) {
  if (value === 'ORDER_PAID') return '整张销售订单须全额到账'
  if (value === 'SHIPMENT_PAID') return '本次发货控制金额须全额到账'
  return '不校验到账金额'
}

async function refreshOutbound() {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['outbound-detail', id] }),
    queryClient.invalidateQueries({ queryKey: ['sales-returns', 'outbound', id] }),
    queryClient.invalidateQueries({ queryKey: ['outbounds'] }),
    queryClient.invalidateQueries({ queryKey: ['inventory-batches'] }),
    queryClient.invalidateQueries({ queryKey: ['receivables'] }),
    queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
    queryClient.invalidateQueries({ queryKey: ['sales-risk', 'OUTBOUND', id] }),
    queryClient.invalidateQueries({ queryKey: ['sales-risk-history', 'OUTBOUND', id] }),
    queryClient.invalidateQueries({ queryKey: ['inventory-atp'] }),
    detail.value?.salesOrderId ? queryClient.invalidateQueries({ queryKey: ['sales-order-atp', detail.value.salesOrderId] }) : Promise.resolve(),
    detail.value?.salesOrderId ? queryClient.invalidateQueries({ queryKey: ['sales-order-atp-history', detail.value.salesOrderId] }) : Promise.resolve(),
    detail.value?.salesOrderId ? queryClient.invalidateQueries({ queryKey: ['sales-order-detail', detail.value.salesOrderId] }) : Promise.resolve(),
  ])
}

async function confirmOutbound() {
  const risk = await api.outboundRisk(id)
  const riskDecision = await resolveSalesRiskOverride(risk, '确认发货')
  if (!riskDecision.allowed) return
  const confirmed = await ElMessageBox.confirm(
    '确认后将按实际批次扣减库存；已约定价格则同时形成应收，未约定价格则按暂估最高金额控制并等待最终定价。',
    '确认发货',
    { type: 'warning' },
  ).then(() => true).catch(() => false)
  if (!confirmed) return
  await api.confirmOutbound(id, riskDecision.reason, riskDecision.approvalId)
  ElMessage.success('出库已经确认发货')
  await refreshOutbound()
}

async function cancelOutbound() {
  const result = await ElMessageBox.prompt(
    '作废后不会占用销售订单待发数量，也不能再编辑或确认。请填写作废原因。',
    '作废出库草稿',
    {
      type: 'warning',
      confirmButtonText: '确认作废',
      cancelButtonText: '返回',
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
  await api.cancelOutbound(id, result.value.trim())
  ElMessage.success('出库草稿已作废，待发数量已释放')
  await refreshOutbound()
}

async function completeInformation() {
  const { value } = await ElMessageBox.prompt('请说明本次补录了哪些单据信息，例如收货地址、联系人、价格依据、箱码或备注：', '补录单据信息', { inputValidator: (text) => !!text.trim() || '必须填写补录说明' })
  await api.completeOutboundInformation(id, value)
  ElMessage.success('出库单据信息已标记完整')
  await refreshOutbound()
}

function openSettlement() {
  settlementReason.value = ''
  settlementItems.value = (detail.value?.items ?? []).map((item) => ({ id: item.id, productName: item.productName, quantity: Number(item.quantity), unitPrice: Number(item.unitPrice) || 0 }))
  settlementDialogVisible.value = true
}

async function submitSettlement() {
  if (!settlementReason.value.trim() || settlementItems.value.some((item) => item.unitPrice <= 0)) { ElMessage.warning('请填写全部最终单价和定价说明'); return }
  await api.settleOutbound(id, { reason: settlementReason.value, items: settlementItems.value.map((item) => ({ outboundItemId: item.id, unitPrice: item.unitPrice })) })
  settlementDialogVisible.value = false
  ElMessage.success('最终价格已结算，应收已经形成')
  await refreshOutbound()
}

async function reverseOutbound() {
  const { value } = await ElMessageBox.prompt('反向出库会恢复库存并冲回应收。请输入原因：', '反向出库', { inputValidator: (text) => !!text.trim() || '必须填写原因', type: 'warning' })
  await api.reverseOutbound(id, { reason: value, businessDate: currentDateText() })
  ElMessage.success('出库已反向处理')
  await refreshOutbound()
}

function openReturn() {
  returnDate.value = currentDateText()
  returnReason.value = ''
  returnItems.value = (detail.value?.items ?? []).map((item) => ({
    id: item.id,
    productName: item.productName,
    available: Math.max(0, Number(item.quantity) - Number(item.returnedQuantity || 0)),
    quantity: 0,
  }))
  returnDialogVisible.value = true
}

async function submitReturn() {
  const items = returnItems.value.filter((item) => item.quantity > 0)
  if (!returnReason.value.trim() || !items.length) { ElMessage.warning('请填写退货原因和退货数量'); return }
  await api.createSalesReturn({ outboundId: id, businessDate: returnDate.value, reason: returnReason.value, items: items.map((item) => ({ outboundItemId: item.id, quantity: item.quantity })) })
  returnDialogVisible.value = false
  ElMessage.success('销售退货已确认，库存和应收已同步处理')
  await refreshOutbound()
}

async function reverseReturn(returnId: number) {
  const { value } = await ElMessageBox.prompt('请输入退货冲正原因：', '冲正销售退货', { inputValidator: (text) => !!text.trim() || '必须填写原因', type: 'warning' })
  await api.reverseSalesReturn(returnId, { reason: value, businessDate: currentDateText() })
  ElMessage.success('销售退货已冲正')
  await refreshOutbound()
}

async function handlePaymentSuccess() {
  paymentDialogVisible.value = false
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['outbound-detail', id] }),
    queryClient.invalidateQueries({ queryKey: ['payments', 'outbound', id] }),
  ])
}

async function printOutbound() {
  if (!detail.value) return
  await printDocument(printFileName(detail.value.customerName || '客户', '销售出库单', detail.value.orderNo || '未编号'))
}
</script>
