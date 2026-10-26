<template>
  <div class="page" v-if="detail">
    <div class="panel">
      <PageHeader :title="`进货入库 ${detail.orderNo}`" :back-to="backPath">
        <el-button plain @click="printInbound">打印入库单</el-button>
        <el-button v-if="['DRAFT', 'REJECTED'].includes(detail.status)" @click="$router.push(`/inbounds/${id}/edit`)">继续编辑</el-button>
        <el-button v-if="['DRAFT', 'REJECTED'].includes(detail.status)" type="primary" :loading="submitting" @click="submitDraft">提交审核</el-button>
        <el-button v-if="detail.status === 'PENDING'" :loading="withdrawing" @click="withdrawReview">撤回审核</el-button>
        <el-button v-if="detail.status === 'STOCKED' && appStore.hasPermission('inbound.audit')" type="danger" plain :loading="reversing" @click="reverseInbound">冲正入库</el-button>
        <el-button type="primary" :plain="detail.status !== 'PENDING'" @click="openReviewOrLogs">{{ detail.status === 'PENDING' ? '审核入库' : '审核记录' }}</el-button>
      </PageHeader>
      <div class="panel-body">
        <el-alert v-if="detail.status === 'REJECTED'" type="error" :closable="false" :title="`拒绝原因：${detail.rejectionReason || '-'}`" style="margin-bottom: 14px" />
        <el-descriptions :column="3" border>
          <el-descriptions-item label="供应商">{{ detail.supplierName }}</el-descriptions-item>
          <el-descriptions-item label="采购订单">
            <router-link v-if="detail.purchaseOrderId" class="table-link" :to="relatedDetailRoute(route, `/purchase-orders/${detail.purchaseOrderId}`)">{{ detail.purchaseOrderNo }}</router-link>
            <span v-else>-</span>
          </el-descriptions-item>
          <el-descriptions-item label="仓库">{{ detail.warehouseName }}</el-descriptions-item>
          <el-descriptions-item label="来源">{{ purchaseSource(detail.sourceType) }}</el-descriptions-item>
          <el-descriptions-item label="入库日期">{{ formatDate(detail.businessDate) }}</el-descriptions-item>
          <el-descriptions-item label="发货日期">{{ formatDate(detail.sendDate) }}</el-descriptions-item>
          <el-descriptions-item label="发货单号">{{ detail.sendNumber || '-' }}</el-descriptions-item>
          <el-descriptions-item label="总数量">{{ quantity(detail.totalQuantity) }}</el-descriptions-item>
          <el-descriptions-item label="总金额">{{ money(detail.totalAmount) }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="inboundStatusType(detail.status)" size="small">
              {{ inboundStatus(detail.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="原金额">{{ money(detail.originalAmount) }}</el-descriptions-item>
          <el-descriptions-item label="折扣%">{{ money(detail.discountRate) }}</el-descriptions-item>
          <el-descriptions-item label="调整金额">{{ money(detail.adjustmentAmount) }}</el-descriptions-item>
          <el-descriptions-item label="应付">{{ money(detail.payableAmount) }}</el-descriptions-item>
          <el-descriptions-item label="已付">{{ money(detail.paidAmount) }}</el-descriptions-item>
          <el-descriptions-item label="付款状态">
            <el-tag :type="paymentStatusType(detail.paymentStatus)" size="small">{{ paymentStatus(detail.paymentStatus) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="来源说明">{{ detail.sourceNote || '-' }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="3">{{ detail.memo || '-' }}</el-descriptions-item>
        </el-descriptions>
      </div>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">发货单图片</div>
          <div class="muted">与整张入库单关联，可随时补录并用于供应商对账。</div>
        </div>
        <el-upload
          ref="attachmentUploadRef"
          v-model:file-list="attachmentUploadFiles"
          action="#"
          accept="image/jpeg,image/png,image/webp"
          multiple
          :show-file-list="true"
          :http-request="uploadAttachment"
        >
          <el-button type="primary" plain>补录图片</el-button>
        </el-upload>
      </div>
      <div class="panel-body">
        <el-alert v-if="failedAttachmentCount" type="error" :closable="false" show-icon class="attachment-retry-alert">
          <template #title>
            <span>{{ failedAttachmentCount }} 张图片上传失败；已保留选择，可直接重试。</span>
            <el-button type="danger" plain size="small" @click="retryFailedAttachments">重试失败图片</el-button>
          </template>
        </el-alert>
        <div v-if="attachments?.length" class="inbound-attachment-grid">
          <figure v-for="item in attachments" :key="item.id" class="inbound-attachment-card">
            <el-image
              :src="attachmentUrl(item.id)"
              :preview-src-list="attachmentPreviewUrls"
              fit="cover"
              preview-teleported
            />
            <figcaption>
              <span>{{ item.fileName }}</span>
              <span class="muted">{{ fileSizeText(item.fileSize) }}</span>
            </figcaption>
          </figure>
        </div>
        <div v-else class="inbound-attachment-empty muted">尚未上传发货单图片，可随时补录。</div>
      </div>
    </div>

    <div v-if="!detail.purchaseOrderId" class="panel">
      <div class="panel-header">
        <div class="panel-title">付款情况</div>
        <el-button type="primary" :disabled="remainingAmount <= 0 || detail.status !== 'STOCKED'" @click="paymentDialogVisible = true">付款</el-button>
      </div>
      <div class="panel-body">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="应付金额">{{ money(detail.payableAmount) }}</el-descriptions-item>
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

    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">商品明细</div>
        <el-segmented v-model="itemViewMode" :options="documentItemViewOptions" size="small" />
      </div>
      <DocumentItemCompactTable v-if="itemViewMode === 'COMPACT'" :items="detail.items" kind="INBOUND" />
      <DocumentItemMultilineView v-else-if="itemViewMode === 'MULTILINE'" :items="detail.items" kind="INBOUND" />
      <el-table v-else :data="detail.items" class="data-table" size="small" border show-overflow-tooltip max-height="calc(100vh - 420px)">
        <el-table-column prop="sku" label="SKU" width="150" fixed="left" />
        <el-table-column label="商品" min-width="220" fixed="left">
          <template #default="{ row }">{{ productTitle(row) }}</template>
        </el-table-column>
        <el-table-column prop="spec" label="规格摘要" min-width="260" />
        <el-table-column prop="batchNo" label="批次号" min-width="150" />
        <el-table-column prop="serialNumber" label="厂家序列" min-width="130" />
        <el-table-column label="入库数量" width="140" align="right">
          <template #default="{ row }">{{ stockQuantity(row.quantity, row) }}</template>
        </el-table-column>
        <el-table-column label="送样/领用/损耗已扣" width="170" align="right">
          <template #default="{ row }">{{ stockQuantity(row.nonTradeIssuedQuantity, row, true) }}</template>
        </el-table-column>
        <el-table-column label="当前账面结存" width="150" align="right">
          <template #default="{ row }">{{ stockQuantity(row.currentBookQuantity, row) }}</template>
        </el-table-column>
        <el-table-column label="当前自由可用" width="150" align="right">
          <template #default="{ row }">
            <div>{{ stockQuantity(row.currentFreeQuantity, row) }}</div>
            <div v-if="Number(row.reservedQuantity) > 0" class="muted inventory-reserved-note">
              已占用 {{ stockQuantity(row.reservedQuantity, row, true) }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="包装数" width="110" align="right"><template #default="{ row }">{{ quantity(row.packageQuantity) }}</template></el-table-column>
        <el-table-column prop="priceUnit" label="计价单位" width="100" />
        <el-table-column prop="packageUnit" label="包装单位" width="100" />
        <el-table-column prop="packageBarcode" label="包装条码" min-width="150" />
        <el-table-column label="计量包装快照" min-width="210">
          <template #default="{ row }"><PackagingSnapshotCell :snapshot-json="row.packagingPlanJson" :requested-quantity="row.requestedQuantity" :requested-unit="row.requestedUnit" :quantity="row.quantity" :base-unit="row.baseUnit || row.unit" :pricing-quantity="row.pricingQuantity" :price-unit="row.priceUnit" :snapshot-hash="row.packagingSnapshotHash" /></template>
        </el-table-column>
        <el-table-column label="成本单价" width="120" align="right"><template #default="{ row }">{{ money(row.unitCost, 4) }}</template></el-table-column>
        <el-table-column label="金额" width="130" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
        <el-table-column prop="productionDate" label="生产日期" width="120" :formatter="formatDateColumn" />
        <el-table-column label="保质期" width="120"><template #default="{ row }">{{ shelfLifeText(row) }}</template></el-table-column>
        <el-table-column prop="expirationDate" label="到期日" width="120" fixed="right" :formatter="formatDateColumn" />
      </el-table>
    </div>

    <div v-if="hasInventoryBatch" class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">送样 / 领用 / 损耗记录</div>
          <div class="muted">按本入库单形成的库存批次列示；已发放待签字和已归档记录计入当前账面结存，已冲正记录仅保留审计。</div>
        </div>
      </div>
      <el-table v-if="(detail.nonTradeRecords || []).length" :data="detail.nonTradeRecords || []" class="data-table" size="small" border show-overflow-tooltip>
        <el-table-column label="单号" width="155">
          <template #default="{ row }">
            <router-link class="table-link" :to="relatedDetailRoute(route, `/inventory/non-trade/${row.id}`)">
              {{ row.documentNo }}
            </router-link>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ nonTradeTypeName(row.businessType) }}</template>
        </el-table-column>
        <el-table-column label="业务日期" width="112">
          <template #default="{ row }">{{ formatDate(row.businessDate) }}</template>
        </el-table-column>
        <el-table-column label="商品 / 批次" min-width="205">
          <template #default="{ row }">
            <div>{{ nonTradeProductName(row.productId) }}</div>
            <div class="muted">{{ row.batchNo }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="recipient" label="实际领用人" width="105" />
        <el-table-column prop="handlerName" label="经办人 / 发放人" width="125" />
        <el-table-column label="扣取数量" width="110" align="right">
          <template #default="{ row }">{{ quantity(row.quantity) }} {{ row.baseUnit }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="nonTradeStatusType(row.status)" size="small">{{ nonTradeStatusName(row.status) }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="当前入库批次暂无已发放的送样、领用或损耗记录" :image-size="72" />
    </div>

    <el-dialog v-model="paymentDialogVisible" title="付款核销" width="920px">
      <SupplierPaymentForm
        v-if="detail"
        compact
        lock-source
        show-cancel
        submit-label="保存付款"
        :initial="{ supplierId: detail.supplierId, sourceType: 'INBOUND', sourceId: id, amount: remainingAmount }"
        @cancel="paymentDialogVisible = false"
        @success="handlePaymentSuccess"
      />
    </el-dialog>
    <PaymentRecordDrawer v-model="paymentRecordDrawerVisible" kind="PAYMENT" :record="activePaymentRecord" :origin-label="`进货入库 ${detail.orderNo}`" />

    <section class="print-sheet a4-print-sheet">
      <div class="document-print-heading">
        <div class="document-print-company">{{ appStore.settings.companyName }}</div>
        <div class="document-print-title">进货入库单{{ detail.status === 'DRAFT' ? '（草稿）' : '' }}</div>
        <div class="document-print-subtitle">
          <span v-if="appStore.settings.companyPhone">电话：{{ appStore.settings.companyPhone }}</span>
          <span>打印时间：{{ printTime }}</span>
        </div>
      </div>
      <table class="document-print-meta">
        <tbody>
          <tr><td>入库单号：{{ detail.orderNo }}</td><td>入库日期：{{ formatDate(detail.businessDate) }}</td><td>仓库：{{ detail.warehouseName }}</td></tr>
          <tr><td>供应商：{{ detail.supplierName }}</td><td>采购订单：{{ detail.purchaseOrderNo || '-' }}</td><td>制单人：{{ appStore.currentUserName }}</td></tr>
          <tr><td>发货单号：{{ detail.sendNumber || '-' }}</td><td>发货日期：{{ formatDate(detail.sendDate) }}</td><td>状态：{{ inboundStatus(detail.status) }}</td></tr>
        </tbody>
      </table>
      <table class="document-print-table">
        <thead><tr><th>#</th><th>商品 / SKU</th><th>规格</th><th>批次</th><th>入库数量</th><th>成本单价</th><th>金额</th></tr></thead>
        <tbody>
          <tr v-for="(row, index) in detail.items" :key="row.id">
            <td class="center">{{ index + 1 }}</td>
            <td>{{ row.productName }} / {{ row.sku }}</td>
            <td>{{ row.spec || '-' }}</td>
            <td>{{ row.batchNo || '-' }}</td>
            <td class="right">{{ quantity(row.requestedQuantity ?? row.quantity) }} {{ row.requestedUnit || row.baseUnit || row.unit }}</td>
            <td class="right">{{ money(row.unitCost, 4) }} / {{ row.priceUnit }}</td>
            <td class="right">{{ money(row.amount) }}</td>
          </tr>
        </tbody>
      </table>
      <table class="document-print-total">
        <tbody>
          <tr><td>合计数量：{{ quantity(detail.totalQuantity) }}</td><td>原金额：{{ money(detail.originalAmount) }}</td><td>调整：{{ money(detail.adjustmentAmount) }}</td><td>应付金额：{{ money(detail.payableAmount) }}</td></tr>
          <tr><td colspan="4">备注：{{ detail.memo || '无' }}</td></tr>
        </tbody>
      </table>
      <div class="document-print-signatures"><span>制单：</span><span>仓库：</span><span>送货：</span><span>供应商/经办：</span></div>
    </section>
    <EvidenceAttachments
      document-type="INBOUND"
      :document-id="id"
      title="签收、验收与补充凭据"
      default-evidence-type="SIGNED_RECEIPT"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox, type UploadInstance, type UploadRequestOptions, type UploadUserFile } from 'element-plus'
import { api, type InboundItemDetail, type SupplierPaymentRow } from '@/api/client'
import { accountTypeName, currentDateText, formatDate, formatDateColumn, money, paymentMethodName, paymentStatus, paymentStatusType, purchaseSource, quantity } from '@/api/format'
import PageHeader from '@/components/PageHeader.vue'
import SupplierPaymentForm from '@/components/SupplierPaymentForm.vue'
import PackagingSnapshotCell from '@/components/PackagingSnapshotCell.vue'
import PaymentRecordDrawer from '@/components/PaymentRecordDrawer.vue'
import DocumentItemMultilineView from '@/components/DocumentItemMultilineView.vue'
import DocumentItemCompactTable from '@/components/DocumentItemCompactTable.vue'
import EvidenceAttachments from '@/components/EvidenceAttachments.vue'
import { useDocumentItemView } from '@/composables/useDocumentItemView'
import { printFileName, useDocumentPrint } from '@/composables/useDocumentPrint'
import { relatedDetailRoute, returnPath } from '@/utils/navigation'
import { useAppStore } from '@/stores/app'
import { sha256File } from '@/utils/fileHash'

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const appStore = useAppStore()
const id = Number(route.params.id)
const { data: detail } = useQuery({ queryKey: ['inbound-detail', id], queryFn: () => api.inboundDetail(id) })
const { data: attachments } = useQuery({ queryKey: ['inbound-attachments', id], queryFn: () => api.inboundAttachments(id) })
const { data: payments } = useQuery({ queryKey: ['supplier-payments', 'inbound', id], queryFn: () => api.supplierPayments({ inboundId: id }) })
const paymentDialogVisible = ref(false)
const paymentRecordDrawerVisible = ref(false)
const activePaymentRecord = ref<SupplierPaymentRow>()
const attachmentUploadRef = ref<UploadInstance>()
const attachmentUploadFiles = ref<UploadUserFile[]>([])
const backPath = computed(() => returnPath(route, '/inbounds'))
const remainingAmount = computed(() => Math.max(0, Number(detail.value?.payableAmount ?? 0) - Number(detail.value?.paidAmount ?? 0)))
const { itemViewMode, documentItemViewOptions } = useDocumentItemView()
const { printTime, printDocument } = useDocumentPrint(['is-a4-printing'])
const attachmentPreviewUrls = computed(() => (attachments.value ?? []).map((item) => attachmentUrl(item.id)))
const failedAttachmentCount = computed(() => attachmentUploadFiles.value.filter((item) => item.status === 'fail').length)
const hasInventoryBatch = computed(() => (detail.value?.items || []).some((item) => Number(item.inventoryBatchId) > 0))
const submitMutation = useMutation({
  mutationFn: () => api.submitInbound(id, appStore.currentUserName),
  onSuccess: async () => {
    ElMessage.success('入库单已提交审核')
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['inbound-detail', id] }),
      queryClient.invalidateQueries({ queryKey: ['inbound-audit-logs', id] }),
      queryClient.invalidateQueries({ queryKey: ['inbounds'] }),
    ])
  },
  onError: (error) => ElMessage.error(error instanceof Error ? error.message : '提交审核失败'),
})
const submitting = computed(() => submitMutation.isPending.value)
const withdrawMutation = useMutation({
  mutationFn: () => api.withdrawInbound(id, appStore.currentUserName),
  onSuccess: async () => {
    ElMessage.success('已撤回为草稿，可以继续修改')
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['inbound-detail', id] }),
      queryClient.invalidateQueries({ queryKey: ['inbounds'] }),
    ])
  },
  onError: (error) => ElMessage.error(error instanceof Error ? error.message : '撤回失败'),
})
const withdrawing = computed(() => withdrawMutation.isPending.value)
const reverseMutation = useMutation({
  mutationFn: (reason: string) => api.reverseInbound(id, { reason, businessDate: currentDateText() }),
  onSuccess: async () => {
    ElMessage.success('入库已冲正，库存、应付、采购到货及反向过账均已同步')
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['inbound-detail', id] }),
      queryClient.invalidateQueries({ queryKey: ['inbounds'] }),
      queryClient.invalidateQueries({ queryKey: ['inventory'] }),
      queryClient.invalidateQueries({ queryKey: ['inventory-movements'] }),
      queryClient.invalidateQueries({ queryKey: ['purchase-orders'] }),
      queryClient.invalidateQueries({ queryKey: ['payables'] }),
      queryClient.invalidateQueries({ queryKey: ['business-postings'] }),
      queryClient.invalidateQueries({ queryKey: ['inventory-atp'] }),
    ])
  },
})
const reversing = computed(() => reverseMutation.isPending.value)

function attachmentUrl(attachmentId: number) {
  return api.inboundAttachmentUrl(id, attachmentId)
}

async function printInbound() {
  if (!detail.value) return
  await printDocument(printFileName(detail.value.supplierName || '供应商', '进货入库单', detail.value.orderNo || '未编号'))
}

async function uploadAttachment(options: UploadRequestOptions) {
  try {
    const uploaded = await api.uploadInboundAttachment(id, options.file)
    await queryClient.invalidateQueries({ queryKey: ['inbound-attachments', id] })
    ElMessage.success('发货单图片已补录')
    return uploaded
  } catch (error) {
    if (await attachmentExistsAfterUncertainFailure(options.file, error)) {
      await queryClient.invalidateQueries({ queryKey: ['inbound-attachments', id] })
      ElMessage.success('图片已在服务器中确认，无需重复上传')
      return undefined
    }
    ElMessage.error(error instanceof Error ? error.message : '发货单图片上传失败')
    throw error
  }
}

function retryFailedAttachments() {
  for (const item of attachmentUploadFiles.value) {
    if (item.status === 'fail') item.status = 'ready'
  }
  attachmentUploadRef.value?.submit()
}

async function attachmentExistsAfterUncertainFailure(file: File, error: unknown) {
  const message = error instanceof Error ? error.message : ''
  if (message.includes('已经上传') || message.includes('重复')) return true
  try {
    const [digest, stored] = await Promise.all([sha256File(file), api.inboundAttachments(id)])
    return stored.some((attachment) => attachment.sha256.toLowerCase() === digest)
  } catch {
    return false
  }
}

function fileSizeText(value: number) {
  return value >= 1024 * 1024 ? `${(value / 1024 / 1024).toFixed(1)} MB` : `${Math.ceil(value / 1024)} KB`
}

function shelfLifeText(row: InboundItemDetail) {
  if (row.shelfLifeMode !== 'PERIOD') return '无保质期'
  const unit = ({ DAY: '天', MONTH: '月', YEAR: '年' } as Record<string, string>)[row.shelfLifeUnit || ''] || ''
  return `${row.shelfLifeValue || '-'}${unit}`
}

function openPaymentRecord(payment: SupplierPaymentRow) {
  activePaymentRecord.value = payment
  paymentRecordDrawerVisible.value = true
}

function inboundStatus(status: string) {
  if (status === 'DRAFT') return '草稿'
  if (status === 'REJECTED') return '已拒绝'
  if (status === 'STOCKED') return '已入库'
  if (status === 'CANCELLED') return '已取消'
  if (status === 'REVERSED') return '已冲正'
  return '待审核'
}

function inboundStatusType(status: string) {
  if (status === 'STOCKED') return 'success'
  if (status === 'PENDING') return 'warning'
  if (status === 'REJECTED') return 'danger'
  if (status === 'REVERSED') return 'info'
  return 'info'
}

async function withdrawReview() {
  try {
    await ElMessageBox.confirm('撤回后单据恢复为草稿，可以修改并重新提交。', '撤回审核', {
      confirmButtonText: '确认撤回', cancelButtonText: '取消', type: 'warning',
    })
    withdrawMutation.mutate()
  } catch { /* 取消撤回 */ }
}

async function submitDraft() {
  try {
    await ElMessageBox.confirm('提交后进入待审核，不能直接修改；审核通过后才增加库存并形成应付。', '提交入库审核', {
      confirmButtonText: '确认提交',
      cancelButtonText: '返回检查',
      type: 'warning',
    })
  } catch {
    return
  }
  if (detail.value && ['DRAFT', 'REJECTED'].includes(detail.value.status)) submitMutation.mutate()
}

async function reverseInbound() {
  const result = await ElMessageBox.prompt('仅当未付款、未采购退货、批次未发生出库/调拨/盘点/成本调整且未被草稿占用时才能冲正。请填写原因。', '冲正已入库单', { type: 'warning', confirmButtonText: '确认冲正', cancelButtonText: '取消', inputPlaceholder: '请输入冲正原因', inputValidator: (value) => String(value || '').trim() || '请填写冲正原因' }).catch(() => null)
  if (result) reverseMutation.mutate(result.value.trim())
}

function openReviewOrLogs() {
  const path = detail.value?.status === 'PENDING' ? `/inbounds/${id}/review` : `/inbounds/${id}/audit-logs`
  router.push(relatedDetailRoute(route, path))
}

function productTitle(row: InboundItemDetail) {
  return [row.productName, row.unit ? `单位:${row.unit}` : ''].filter(Boolean).join(' / ')
}

function stockQuantity(value: unknown, row: InboundItemDetail, zeroWhenMissing = false) {
  if ((value === null || value === undefined) && !zeroWhenMissing) return '-'
  return `${quantity(Number(value ?? 0))} ${row.baseUnit || row.unit || ''}`.trim()
}

function nonTradeProductName(productId: number) {
  return detail.value?.items.find((item) => item.productId === productId)?.productName || `商品#${productId}`
}

function nonTradeTypeName(value: string) {
  return ({ SAMPLE_OUT: '送样', INTERNAL_USE: '内部领用', NORMAL_LOSS: '合理损耗', OTHER_OUT: '其他出库' } as Record<string, string>)[value] || value
}

function nonTradeStatusName(value: string) {
  return ({ ISSUED_PENDING_SIGNATURE: '已发放待签字', CONFIRMED: '已签字归档', REVERSED: '已冲正' } as Record<string, string>)[value] || value
}

function nonTradeStatusType(value: string) {
  if (value === 'CONFIRMED') return 'success'
  if (value === 'ISSUED_PENDING_SIGNATURE') return 'warning'
  return 'info'
}

async function handlePaymentSuccess() {
  paymentDialogVisible.value = false
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['inbound-detail', id] }),
    queryClient.invalidateQueries({ queryKey: ['supplier-payments', 'inbound', id] }),
  ])
}
</script>

<style scoped>
.inbound-attachment-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 14px;
}

.attachment-retry-alert {
  margin-bottom: 14px;
}

.attachment-retry-alert :deep(.el-alert__title) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

.inbound-attachment-empty {
  padding: 10px 2px;
}

.inventory-reserved-note {
  margin-top: 2px;
  font-size: 12px;
}

.inbound-attachment-card {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.inbound-attachment-card :deep(.el-image) {
  display: block;
  width: 100%;
  height: 150px;
}

.inbound-attachment-card figcaption {
  display: grid;
  gap: 3px;
  padding: 9px 10px;
  font-size: 12px;
}

.inbound-attachment-card figcaption span:first-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
