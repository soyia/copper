<template>
  <div class="page" v-if="detail">
    <div class="panel">
      <PageHeader :title="`入库审核 ${detail.orderNo}`" :back-to="backPath">
        <el-button plain @click="openAuditLogs">审核记录</el-button>
        <el-button v-if="detail.status === 'PENDING'" type="danger" plain :loading="reviewing" @click="reject">审核拒绝</el-button>
        <el-button v-if="detail.status === 'PENDING'" type="primary" :loading="reviewing" @click="approve">审核通过并入库</el-button>
      </PageHeader>
      <div class="panel-body review-summary">
        <el-alert
          v-if="detail.status === 'PENDING'"
          type="warning"
          :closable="false"
          title="请逐项核对商品明细与下方发货单影像。审核通过后立即增加库存并形成应付，不能通过普通编辑撤销。"
        />
        <el-alert v-else-if="detail.status === 'REJECTED'" type="error" :closable="false" :title="`本轮审核已拒绝：${detail.rejectionReason || '-'}`" />
        <el-descriptions :column="3" border>
          <el-descriptions-item label="入库单号">{{ detail.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="供应商">{{ detail.supplierName }}</el-descriptions-item>
          <el-descriptions-item label="仓库">{{ detail.warehouseName }}</el-descriptions-item>
          <el-descriptions-item label="入库日期">{{ formatDate(detail.businessDate) }}</el-descriptions-item>
          <el-descriptions-item label="发货日期">{{ formatDate(detail.sendDate) }}</el-descriptions-item>
          <el-descriptions-item label="发货单号">{{ detail.sendNumber || '-' }}</el-descriptions-item>
          <el-descriptions-item label="业务数量">{{ detail.quantitySummary || quantitySummary }}</el-descriptions-item>
          <el-descriptions-item label="总金额">{{ money(detail.totalAmount) }}</el-descriptions-item>
          <el-descriptions-item label="来源">{{ purchaseSource(detail.sourceType) }}</el-descriptions-item>
          <el-descriptions-item label="来源说明" :span="2">{{ detail.sourceNote || '-' }}</el-descriptions-item>
          <el-descriptions-item label="备注">{{ detail.memo || '-' }}</el-descriptions-item>
        </el-descriptions>
      </div>
    </div>

    <div v-if="previousRejectLog && reviewSnapshotDiff" class="panel review-diff-panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">本轮相较上一轮的改动</div>
          <div class="muted">上一轮被 {{ previousRejectLog.operatorName || '系统用户' }} 拒绝：{{ previousRejectLog.reason || '未填写原因' }}</div>
        </div>
        <div class="review-diff-counts">
          <el-tag type="warning" effect="plain">共 {{ reviewSnapshotDiff.totalChangeCount }} 项变化</el-tag>
          <el-tag effect="plain">单头 {{ reviewSnapshotDiff.headerChanges.length }}</el-tag>
          <el-tag effect="plain">商品 {{ reviewSnapshotDiff.changedItemCount }}</el-tag>
          <el-tag effect="plain">影像 +{{ reviewSnapshotDiff.addedAttachments.length }} / -{{ reviewSnapshotDiff.removedAttachments.length }}</el-tag>
        </div>
      </div>
      <div class="panel-body review-diff-body">
        <el-alert v-if="!reviewSnapshotDiff.hasChanges" type="error" :closable="false" title="本轮提交内容与上一轮快照一致，请重点确认是否真正处理了拒绝原因。" />
        <div v-if="reviewSnapshotDiff.headerChanges.length" class="review-diff-section">
          <div class="review-diff-title">单据信息变化</div>
          <div class="review-diff-list">
            <div v-for="change in reviewSnapshotDiff.headerChanges" :key="change.key" class="review-diff-line">
              <strong>{{ change.label }}</strong><span class="diff-before">{{ changeValue(change.before, change.key) }}</span><span>→</span><span class="diff-after">{{ changeValue(change.after, change.key) }}</span>
            </div>
          </div>
        </div>
        <div v-if="changedReviewItems.length" class="review-diff-section">
          <div class="review-diff-title">商品明细变化</div>
          <div class="review-diff-list">
            <div v-for="item in changedReviewItems" :key="`${item.lineNo}-${item.changeType}`" class="review-diff-item">
              <el-tag :type="snapshotChangeTypeTag(item.changeType)" size="small" effect="plain">{{ snapshotChangeTypeName(item.changeType) }}</el-tag>
              <strong>第 {{ item.lineNo }} 行 {{ item.productName || item.sku }}</strong>
              <span v-if="item.changes.length" class="muted">{{ itemChangeSummary(item) }}</span>
            </div>
          </div>
        </div>
        <div v-if="reviewSnapshotDiff.addedAttachments.length || reviewSnapshotDiff.removedAttachments.length" class="review-diff-section">
          <div class="review-diff-title">发货单影像变化</div>
          <div class="review-diff-list">
            <div v-for="item in reviewSnapshotDiff.addedAttachments" :key="`add-${item.sha256}`" class="review-diff-item"><el-tag type="success" size="small">新增</el-tag><span>{{ item.fileName }}</span></div>
            <div v-for="item in reviewSnapshotDiff.removedAttachments" :key="`remove-${item.sha256}`" class="review-diff-item"><el-tag type="danger" size="small">删除</el-tag><span>{{ item.fileName }}</span></div>
          </div>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">商品明细</div>
          <div class="muted">先核对系统录入的商品、数量、计量单位、批次和成本。</div>
        </div>
      </div>
      <el-table :data="detail.items" class="data-table" border size="small" show-overflow-tooltip>
        <el-table-column prop="sku" label="SKU" width="145" fixed="left" />
        <el-table-column prop="productName" label="商品" min-width="180" fixed="left" />
        <el-table-column prop="spec" label="规格摘要" min-width="230" />
        <el-table-column label="业务数量" width="135"><template #default="{ row }">{{ quantity(row.requestedQuantity ?? row.quantity) }} {{ row.requestedUnit || row.unit }}</template></el-table-column>
        <el-table-column label="库存数量" width="135"><template #default="{ row }">{{ quantity(row.quantity) }} {{ row.baseUnit || row.unit }}</template></el-table-column>
        <el-table-column prop="batchNo" label="批次号" min-width="145" />
        <el-table-column label="成本单价" width="145"><template #default="{ row }">{{ money(row.unitCost, 4) }} / {{ row.priceUnit }}</template></el-table-column>
        <el-table-column label="金额" width="125" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
        <el-table-column prop="productionDate" label="生产日期" width="115"><template #default="{ row }">{{ formatDate(row.productionDate) }}</template></el-table-column>
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">发货单 Gallery（{{ attachments?.length || 0 }}）</div>
          <div class="muted">对照上方商品明细逐张核验；点击主图可放大查看原始影像。</div>
        </div>
      </div>
      <div class="panel-body">
        <div v-if="attachments?.length" class="document-gallery">
          <div class="gallery-stage">
            <el-image
              :key="activeAttachment.id"
              :src="attachmentUrl(activeAttachment.id)"
              :preview-src-list="attachmentUrls"
              :initial-index="activeImageIndex"
              fit="contain"
              preview-teleported
            />
          </div>
          <div class="gallery-caption">
            <strong>{{ activeAttachment.fileName }}</strong>
            <span class="muted">{{ activeImageIndex + 1 }} / {{ attachments.length }} · {{ fileSizeText(activeAttachment.fileSize) }} · 上传于 {{ formatDateTime(activeAttachment.uploadedAt) }}</span>
          </div>
          <div class="gallery-strip" role="list" aria-label="发货单图片">
            <button
              v-for="(item, index) in attachments"
              :key="item.id"
              type="button"
              class="gallery-thumb"
              :class="{ 'is-active': index === activeImageIndex }"
              :aria-label="`查看第 ${index + 1} 张：${item.fileName}`"
              @click="activeImageIndex = index"
            >
              <el-image :src="attachmentUrl(item.id)" fit="cover" />
              <span>{{ index + 1 }}</span>
            </button>
          </div>
        </div>
        <el-empty v-else description="未上传发货单图片，请按企业审核制度判断是否允许无影像审核" :image-size="72" />
      </div>
    </div>

  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type InboundAuditLog, type InboundAuditLogDetail } from '@/api/client'
import { formatDate, formatDateTime, money, purchaseSource, quantity } from '@/api/format'
import PageHeader from '@/components/PageHeader.vue'
import { relatedDetailRoute, returnPath } from '@/utils/navigation'
import { useAppStore } from '@/stores/app'
import { compareInboundSnapshots, snapshotChangeTypeName, snapshotChangeTypeTag, type SnapshotItemComparison } from '@/utils/inboundSnapshotDiff'

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const appStore = useAppStore()
const id = Number(route.params.id)
const backPath = computed(() => returnPath(route, `/inbounds/${id}`))
const activeImageIndex = ref(0)
const { data: detail } = useQuery({ queryKey: ['inbound-detail', id], queryFn: () => api.inboundDetail(id) })
const { data: attachments } = useQuery({ queryKey: ['inbound-attachments', id], queryFn: () => api.inboundAttachments(id) })
const { data: reviewComparison } = useQuery({
  queryKey: ['inbound-review-comparison', id],
  queryFn: async (): Promise<{ detail?: InboundAuditLogDetail; previousReject?: InboundAuditLog }> => {
    const logs = await api.inboundAuditLogs(id)
    const latestSnapshotLog = logs.find((item) => item.snapshotId > 0)
    if (!latestSnapshotLog) return {}
    const auditDetail = await api.inboundAuditLogDetail(id, latestSnapshotLog.id)
    const previousReject = auditDetail.previousSnapshot
      ? logs.find((item) => item.action === 'REJECT' && item.snapshotId === auditDetail.previousSnapshot?.id)
      : undefined
    return { detail: auditDetail, previousReject }
  },
})
const attachmentUrls = computed(() => (attachments.value ?? []).map((item) => attachmentUrl(item.id)))
const activeAttachment = computed(() => attachments.value?.[activeImageIndex.value] ?? attachments.value?.[0]!)
const quantitySummary = computed(() => (detail.value?.items ?? []).map((item) => `${quantity(item.requestedQuantity ?? item.quantity)}${item.requestedUnit || item.unit}`).join(' / '))
const previousRejectLog = computed(() => reviewComparison.value?.previousReject)
const reviewSnapshotDiff = computed(() => {
  const auditDetail = reviewComparison.value?.detail
  return auditDetail?.snapshot && auditDetail.previousSnapshot
    ? compareInboundSnapshots(auditDetail.snapshot, auditDetail.previousSnapshot)
    : undefined
})
const changedReviewItems = computed(() => reviewSnapshotDiff.value?.itemRows.filter((item) => item.changeType !== 'UNCHANGED') ?? [])
watch(attachments, (value) => { if (!value?.[activeImageIndex.value]) activeImageIndex.value = 0 })

const auditMutation = useMutation({
  mutationFn: ({ approved, reason }: { approved: boolean; reason: string }) => api.auditInbound(id, approved, reason, appStore.currentUserName),
  onSuccess: async (_result, variables) => {
    ElMessage.success(variables.approved ? '审核通过，库存和应付已生成' : '已拒绝入库，可修改后重新提交')
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['inbound-detail', id] }),
      queryClient.invalidateQueries({ queryKey: ['inbound-audit-logs', id] }),
      queryClient.invalidateQueries({ queryKey: ['inbound-review-comparison', id] }),
      queryClient.invalidateQueries({ queryKey: ['inbounds'] }),
      queryClient.invalidateQueries({ queryKey: ['purchase-orders'] }),
      queryClient.invalidateQueries({ queryKey: ['payables'] }),
      queryClient.invalidateQueries({ queryKey: ['inventory-batches'] }),
      queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
      queryClient.invalidateQueries({ queryKey: ['inventory-atp'] }),
    ])
  },
  onError: (error) => ElMessage.error(error instanceof Error ? error.message : '审核操作失败'),
})
const reviewing = computed(() => auditMutation.isPending.value)

async function approve() {
  try {
    await ElMessageBox.confirm('确认审核通过？确认后将立即增加库存并形成应付，不能通过普通编辑撤销。', '确认审核入库', {
      confirmButtonText: '确认通过并入库', cancelButtonText: '返回继续核对', type: 'warning',
    })
    auditMutation.mutate({ approved: true, reason: '' })
  } catch { /* 返回继续核对 */ }
}

async function reject() {
  try {
    const result = await ElMessageBox.prompt('拒绝后单据可修改并重新提交。请填写明确原因。', '审核拒绝', {
      confirmButtonText: '确认拒绝', cancelButtonText: '返回继续核对', inputType: 'textarea',
      inputPlaceholder: '例如：实收数量与发货单不一致，请重新核对',
      inputValidator: (value) => String(value || '').trim() ? true : '拒绝原因不能为空',
    })
    auditMutation.mutate({ approved: false, reason: result.value.trim() })
  } catch { /* 返回继续核对 */ }
}

function attachmentUrl(attachmentId: number) { return api.inboundAttachmentUrl(id, attachmentId) }
function fileSizeText(value: number) { return value >= 1024 * 1024 ? `${(value / 1024 / 1024).toFixed(1)} MB` : `${Math.ceil(value / 1024)} KB` }
function openAuditLogs() {
  router.push(relatedDetailRoute(route, `/inbounds/${id}/audit-logs`))
}
function changeValue(value: unknown, key: string) {
  if (value == null || value === '') return '-'
  if (['originalAmount', 'adjustmentAmount', 'totalAmount', 'unitCost', 'amount'].includes(key)) return money(Number(value), key === 'unitCost' ? 4 : 2)
  return String(value)
}
function itemChangeSummary(item: SnapshotItemComparison) {
  return item.changes.map((change) => `${change.label}：${changeValue(change.before, change.key)} → ${changeValue(change.after, change.key)}`).join('；')
}
</script>

<style scoped>
.review-summary { display: grid; gap: 14px; }
.document-gallery { display: grid; gap: 12px; }
.gallery-stage { height: min(58vh, 680px); min-height: 360px; overflow: hidden; border: 1px solid var(--el-border-color); border-radius: 8px; background: var(--el-fill-color-light); }
.gallery-stage :deep(.el-image) { width: 100%; height: 100%; cursor: zoom-in; }
.gallery-caption { display: flex; justify-content: space-between; gap: 16px; align-items: center; }
.gallery-strip { display: flex; gap: 10px; overflow-x: auto; padding: 2px 2px 8px; }
.gallery-thumb { position: relative; flex: 0 0 112px; height: 82px; padding: 0; overflow: hidden; border: 2px solid transparent; border-radius: 7px; background: transparent; cursor: pointer; }
.gallery-thumb.is-active { border-color: var(--el-color-primary); }
.gallery-thumb :deep(.el-image) { width: 100%; height: 100%; }
.gallery-thumb span { position: absolute; right: 4px; bottom: 4px; min-width: 20px; padding: 1px 5px; border-radius: 9px; color: #fff; background: rgb(0 0 0 / 58%); font-size: 11px; }
.review-diff-panel { border-color: var(--el-color-warning-light-5); }
.review-diff-counts { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.review-diff-body, .review-diff-section, .review-diff-list { display: grid; gap: 10px; }
.review-diff-title { font-weight: 650; }
.review-diff-line { display: grid; grid-template-columns: minmax(110px, 0.35fr) minmax(160px, 1fr) auto minmax(160px, 1fr); gap: 10px; align-items: start; padding: 8px 10px; border: 1px solid var(--el-border-color-lighter); border-radius: 6px; }
.review-diff-item { display: flex; flex-wrap: wrap; gap: 8px; align-items: flex-start; padding: 8px 10px; border: 1px solid var(--el-border-color-lighter); border-radius: 6px; }
.diff-before { color: var(--el-color-danger); text-decoration: line-through; }
.diff-after { color: var(--el-color-success); font-weight: 600; }
@media (max-width: 900px) {
  .gallery-stage { min-height: 280px; }
  .gallery-caption { align-items: flex-start; flex-direction: column; gap: 4px; }
}
</style>
