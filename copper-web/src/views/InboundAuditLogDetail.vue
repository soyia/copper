<template>
  <div class="page" v-if="detail">
    <div class="panel">
      <PageHeader :title="`审核记录 #${detail.auditLog.id}`" :back-to="backPath" />
      <div class="panel-body audit-event-summary">
        <el-alert
          v-if="!detail.snapshot"
          type="warning"
          :closable="false"
          title="这条历史记录创建时尚未启用审核快照，系统不会使用当前入库单数据伪造当时内容。"
        />
        <el-descriptions :column="3" border>
          <el-descriptions-item label="记录号">#{{ detail.auditLog.id }}</el-descriptions-item>
          <el-descriptions-item label="操作时间">{{ formatDateTime(detail.auditLog.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="操作人">{{ detail.auditLog.operatorName || '系统用户' }}</el-descriptions-item>
          <el-descriptions-item label="审核操作"><el-tag :type="auditActionType(detail.auditLog.action)" size="small" effect="plain">{{ auditActionName(detail.auditLog.action) }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="操作结果">{{ auditResultName(detail.auditLog.action) }}</el-descriptions-item>
          <el-descriptions-item label="审核批次">{{ detail.snapshot ? `第 ${detail.snapshot.roundNo} 轮` : '-' }}</el-descriptions-item>
          <el-descriptions-item label="原因" :span="3"><div class="pre-wrap">{{ detail.auditLog.reason || '无补充原因' }}</div></el-descriptions-item>
        </el-descriptions>
      </div>
    </div>

    <template v-if="snapshot">
      <div class="panel snapshot-diff-panel">
        <div class="panel-header">
          <div>
            <div class="panel-title">与上一轮快照对比</div>
            <div class="muted">只比较冻结快照，不读取当前入库单。</div>
          </div>
          <div v-if="previousSnapshot && snapshotDiff" class="snapshot-diff-counts">
            <el-tag :type="snapshotDiff.hasChanges ? 'warning' : 'success'" effect="plain">{{ snapshotDiff.hasChanges ? `共 ${snapshotDiff.totalChangeCount} 项变化` : '内容未变化' }}</el-tag>
            <el-tag effect="plain">单头 {{ snapshotDiff.headerChanges.length }}</el-tag>
            <el-tag effect="plain">商品 {{ snapshotDiff.changedItemCount }}</el-tag>
            <el-tag effect="plain">影像 +{{ snapshotDiff.addedAttachments.length }} / -{{ snapshotDiff.removedAttachments.length }}</el-tag>
          </div>
        </div>
        <div class="panel-body snapshot-diff-body">
          <el-alert v-if="!previousSnapshot" type="info" :closable="false" title="这是首轮提交快照，没有上一轮可比较。" />
          <template v-else-if="snapshotDiff">
            <el-alert v-if="!snapshotDiff.hasChanges" type="warning" :closable="false" title="本轮快照与上一轮完全一致。" />
            <div v-if="snapshotDiff.headerChanges.length" class="snapshot-diff-section">
              <div class="snapshot-diff-title">单据信息变化</div>
              <div class="snapshot-diff-list">
                <div v-for="change in snapshotDiff.headerChanges" :key="change.key" class="snapshot-diff-line">
                  <strong>{{ change.label }}</strong><span class="diff-before">{{ changeValue(change.before, change.key) }}</span><span>→</span><span class="diff-after">{{ changeValue(change.after, change.key) }}</span>
                </div>
              </div>
            </div>
            <div v-if="changedSnapshotItems.length" class="snapshot-diff-section">
              <div class="snapshot-diff-title">商品明细变化</div>
              <div class="snapshot-diff-list">
                <div v-for="item in changedSnapshotItems" :key="`${item.lineNo}-${item.changeType}`" class="snapshot-diff-item">
                  <el-tag :type="snapshotChangeTypeTag(item.changeType)" size="small" effect="plain">{{ snapshotChangeTypeName(item.changeType) }}</el-tag>
                  <strong>第 {{ item.lineNo }} 行 {{ item.productName || item.sku }}</strong>
                  <span v-if="item.changes.length" class="muted">{{ itemChangeSummary(item) }}</span>
                </div>
              </div>
            </div>
            <div v-if="snapshotDiff.addedAttachments.length || snapshotDiff.removedAttachments.length" class="snapshot-diff-section">
              <div class="snapshot-diff-title">发货单影像变化</div>
              <div class="snapshot-attachment-diff-grid">
                <figure v-for="item in snapshotDiff.addedAttachments" :key="`add-${item.sha256}`" class="snapshot-attachment-diff-card is-added">
                  <el-image :src="snapshotAttachmentUrlFor(snapshot.id, item.id)" fit="cover" :preview-src-list="[snapshotAttachmentUrlFor(snapshot.id, item.id)]" preview-teleported />
                  <figcaption><el-tag type="success" size="small">新增</el-tag><span>{{ item.fileName }}</span></figcaption>
                </figure>
                <figure v-for="item in snapshotDiff.removedAttachments" :key="`remove-${item.sha256}`" class="snapshot-attachment-diff-card is-removed">
                  <el-image :src="snapshotAttachmentUrlFor(previousSnapshot.id, item.id)" fit="cover" :preview-src-list="[snapshotAttachmentUrlFor(previousSnapshot.id, item.id)]" preview-teleported />
                  <figcaption><el-tag type="danger" size="small">删除</el-tag><span>{{ item.fileName }}</span></figcaption>
                </figure>
              </div>
            </div>
          </template>
        </div>
      </div>

      <div class="panel">
        <div class="panel-header">
          <div>
            <div class="panel-title">第 {{ snapshot.roundNo }} 轮提交快照</div>
            <div class="muted">以下内容冻结于 {{ formatDateTime(snapshot.createdAt) }}，不会随当前入库单修改。</div>
          </div>
          <el-tag v-if="snapshot.captureSource === 'LEGACY_ACTION'" type="warning" effect="plain">旧待审核单操作时补捕获</el-tag>
          <el-tag v-else type="success" effect="plain">提交时冻结</el-tag>
        </div>
        <div class="panel-body snapshot-summary">
          <el-descriptions :column="3" border>
            <el-descriptions-item label="入库单号">{{ snapshot.orderNo }}</el-descriptions-item>
            <el-descriptions-item label="供应商">{{ snapshot.supplierName }}</el-descriptions-item>
            <el-descriptions-item label="仓库">{{ snapshot.warehouseName }}</el-descriptions-item>
            <el-descriptions-item label="采购订单">{{ snapshot.purchaseOrderNo || '-' }}</el-descriptions-item>
            <el-descriptions-item label="入库日期">{{ formatDate(snapshot.businessDate) }}</el-descriptions-item>
            <el-descriptions-item label="发货日期">{{ formatDate(snapshot.sendDate) }}</el-descriptions-item>
            <el-descriptions-item label="发货单号">{{ snapshot.sendNumber || '-' }}</el-descriptions-item>
            <el-descriptions-item label="业务数量">{{ snapshot.quantitySummary || '-' }}</el-descriptions-item>
            <el-descriptions-item label="总金额">{{ money(snapshot.totalAmount) }}</el-descriptions-item>
            <el-descriptions-item label="来源">{{ purchaseSource(snapshot.sourceType) }}</el-descriptions-item>
            <el-descriptions-item label="来源说明" :span="2">{{ snapshot.sourceNote || '-' }}</el-descriptions-item>
            <el-descriptions-item label="备注">{{ snapshot.memo || '-' }}</el-descriptions-item>
            <el-descriptions-item label="快照提交人">{{ snapshot.submittedByName }}</el-descriptions-item>
            <el-descriptions-item label="快照校验值" :span="2"><span class="snapshot-hash">{{ snapshot.snapshotHash }}</span></el-descriptions-item>
          </el-descriptions>
        </div>
      </div>

      <div class="panel">
        <div class="panel-header">
          <div>
            <div class="panel-title">商品明细快照</div>
            <div class="muted">共 {{ snapshot.items.length }} 行，展示本轮提交时的商品、计量、批次和成本。</div>
          </div>
        </div>
        <el-table :data="snapshotItemRows" class="data-table" border size="small" show-overflow-tooltip :row-class-name="snapshotItemRowClass">
          <el-table-column label="变更" width="90" fixed="left"><template #default="{ row }"><el-tag :type="snapshotChangeTypeTag(row.changeType)" size="small" effect="plain">{{ snapshotChangeTypeName(row.changeType) }}</el-tag></template></el-table-column>
          <el-table-column prop="lineNo" label="行号" width="70" />
          <el-table-column prop="sku" label="SKU" width="145" fixed="left" />
          <el-table-column prop="productName" label="商品" min-width="185" fixed="left" />
          <el-table-column prop="spec" label="规格摘要" min-width="230" />
          <el-table-column label="业务数量" width="135"><template #default="{ row }">{{ quantity(row.requestedQuantity) }} {{ row.requestedUnit }}</template></el-table-column>
          <el-table-column label="库存数量" width="135"><template #default="{ row }">{{ quantity(row.quantity) }} {{ row.baseUnit }}</template></el-table-column>
          <el-table-column prop="batchNo" label="批次号" min-width="145" />
          <el-table-column label="包装数" width="120"><template #default="{ row }">{{ quantity(row.packageQuantity) }} {{ row.packageUnit }}</template></el-table-column>
          <el-table-column label="成本单价" width="145"><template #default="{ row }">{{ money(row.unitCost, 4) }} / {{ row.priceUnit }}</template></el-table-column>
          <el-table-column label="金额" width="125" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
          <el-table-column label="生产日期" width="115"><template #default="{ row }">{{ formatDate(row.productionDate) }}</template></el-table-column>
          <el-table-column label="改动内容" min-width="280" fixed="right"><template #default="{ row }"><span v-if="row.changes.length">{{ itemChangeSummary(row) }}</span><span v-else>-</span></template></el-table-column>
        </el-table>
      </div>

      <div class="panel">
        <div class="panel-header">
          <div>
            <div class="panel-title">发货单影像快照（{{ snapshot.attachments.length }}）</div>
            <div class="muted">影像内容和 SHA-256 校验值在本轮提交时复制冻结。</div>
          </div>
        </div>
        <div class="panel-body">
          <div v-if="snapshot.attachments.length" class="snapshot-gallery">
            <div class="snapshot-gallery-stage">
              <el-image :key="activeAttachment.id" :src="snapshotAttachmentUrl(activeAttachment.id)" :preview-src-list="snapshotAttachmentUrls" :initial-index="activeAttachmentIndex" fit="contain" preview-teleported />
            </div>
            <div class="snapshot-gallery-caption">
              <strong>{{ activeAttachment.fileName }}</strong>
              <span class="muted">{{ activeAttachmentIndex + 1 }} / {{ snapshot.attachments.length }} · {{ fileSizeText(activeAttachment.fileSize) }} · 原上传时间 {{ formatDateTime(activeAttachment.originalUploadedAt) }}</span>
              <span class="snapshot-hash">SHA-256：{{ activeAttachment.sha256 }}</span>
            </div>
            <div class="snapshot-gallery-strip">
              <button v-for="(item, index) in snapshot.attachments" :key="item.id" type="button" class="snapshot-gallery-thumb" :class="{ 'is-active': index === activeAttachmentIndex }" @click="activeAttachmentIndex = index">
                <el-image :src="snapshotAttachmentUrl(item.id)" fit="cover" />
                <span>{{ index + 1 }}</span>
              </button>
            </div>
          </div>
          <el-empty v-else description="本轮提交时没有发货单影像" :image-size="72" />
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { api, type InboundAuditLog } from '@/api/client'
import { formatDate, formatDateTime, money, purchaseSource, quantity } from '@/api/format'
import PageHeader from '@/components/PageHeader.vue'
import { returnPath } from '@/utils/navigation'
import { compareInboundSnapshots, snapshotChangeTypeName, snapshotChangeTypeTag, type SnapshotItemComparison } from '@/utils/inboundSnapshotDiff'

const route = useRoute()
const inboundId = Number(route.params.id)
const auditLogId = Number(route.params.auditLogId)
const backPath = computed(() => returnPath(route, `/inbounds/${inboundId}/audit-logs`))
const activeAttachmentIndex = ref(0)
const { data: detail } = useQuery({
  queryKey: ['inbound-audit-log-detail', inboundId, auditLogId],
  queryFn: () => api.inboundAuditLogDetail(inboundId, auditLogId),
})
const snapshot = computed(() => detail.value?.snapshot ?? undefined)
const previousSnapshot = computed(() => detail.value?.previousSnapshot ?? undefined)
const snapshotDiff = computed(() => snapshot.value ? compareInboundSnapshots(snapshot.value, previousSnapshot.value) : undefined)
const snapshotItemRows = computed(() => snapshotDiff.value?.itemRows ?? [])
const changedSnapshotItems = computed(() => snapshotItemRows.value.filter((item) => item.changeType !== 'UNCHANGED'))
const activeAttachment = computed(() => snapshot.value?.attachments[activeAttachmentIndex.value] ?? snapshot.value?.attachments[0]!)
const snapshotAttachmentUrls = computed(() => (snapshot.value?.attachments ?? []).map((item) => snapshotAttachmentUrl(item.id)))
watch(snapshot, (value) => { if (!value?.attachments[activeAttachmentIndex.value]) activeAttachmentIndex.value = 0 })

function snapshotAttachmentUrl(attachmentId: number) {
  return api.inboundReviewSnapshotAttachmentUrl(inboundId, snapshot.value?.id || 0, attachmentId)
}
function snapshotAttachmentUrlFor(snapshotId: number, attachmentId: number) {
  return api.inboundReviewSnapshotAttachmentUrl(inboundId, snapshotId, attachmentId)
}
function auditActionName(action: InboundAuditLog['action']) {
  return ({ SUBMIT: '提交审核', WITHDRAW: '撤回审核', APPROVE: '审核通过并入库', REJECT: '审核拒绝' } as const)[action] || action
}
function auditResultName(action: InboundAuditLog['action']) {
  return ({ SUBMIT: '进入待审核', WITHDRAW: '撤回为草稿', APPROVE: '审核通过并完成入库', REJECT: '审核拒绝，可修改后重新提交' } as const)[action] || action
}
function auditActionType(action: InboundAuditLog['action']) {
  if (action === 'APPROVE') return 'success'
  if (action === 'REJECT') return 'danger'
  if (action === 'WITHDRAW') return 'warning'
  return 'primary'
}
function fileSizeText(value: number) {
  return value >= 1024 * 1024 ? `${(value / 1024 / 1024).toFixed(1)} MB` : `${Math.ceil(value / 1024)} KB`
}
function changeValue(value: unknown, key: string) {
  if (value == null || value === '') return '-'
  if (['originalAmount', 'adjustmentAmount', 'totalAmount', 'unitCost', 'amount'].includes(key)) return money(Number(value), key === 'unitCost' ? 4 : 2)
  return String(value)
}
function snapshotItemRowClass({ row }: { row: SnapshotItemComparison }) {
  return row.changeType === 'UNCHANGED' ? '' : `snapshot-row-${row.changeType.toLowerCase()}`
}
function itemChangeSummary(item: SnapshotItemComparison) {
  return item.changes.map((change) => `${change.label}：${changeValue(change.before, change.key)} → ${changeValue(change.after, change.key)}`).join('；')
}
</script>

<style scoped>
.audit-event-summary, .snapshot-summary { display: grid; gap: 14px; }
.pre-wrap { white-space: pre-wrap; }
.snapshot-hash { overflow-wrap: anywhere; color: var(--el-text-color-secondary); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.snapshot-diff-panel { border-color: var(--el-color-warning-light-5); }
.snapshot-diff-counts { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.snapshot-diff-body, .snapshot-diff-section, .snapshot-diff-list { display: grid; gap: 10px; }
.snapshot-diff-title { font-weight: 650; }
.snapshot-diff-line { display: grid; grid-template-columns: minmax(110px, 0.35fr) minmax(160px, 1fr) auto minmax(160px, 1fr); gap: 10px; align-items: start; padding: 8px 10px; border: 1px solid var(--el-border-color-lighter); border-radius: 6px; }
.snapshot-diff-item { display: flex; flex-wrap: wrap; gap: 8px; align-items: flex-start; padding: 8px 10px; border: 1px solid var(--el-border-color-lighter); border-radius: 6px; }
.diff-before { color: var(--el-color-danger); text-decoration: line-through; }
.diff-after { color: var(--el-color-success); font-weight: 600; }
.snapshot-attachment-diff-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 12px; }
.snapshot-attachment-diff-card { min-width: 0; margin: 0; overflow: hidden; border: 1px solid var(--el-border-color-light); border-radius: 7px; }
.snapshot-attachment-diff-card :deep(.el-image) { width: 100%; height: 120px; }
.snapshot-attachment-diff-card figcaption { display: flex; gap: 7px; align-items: center; padding: 8px; }
.snapshot-attachment-diff-card figcaption span:last-child { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
:deep(.snapshot-row-added > td.el-table__cell) { background: var(--el-color-success-light-9); }
:deep(.snapshot-row-removed > td.el-table__cell) { background: var(--el-color-danger-light-9); text-decoration-color: var(--el-color-danger); }
:deep(.snapshot-row-modified > td.el-table__cell) { background: var(--el-color-warning-light-9); }
.snapshot-gallery { display: grid; gap: 12px; }
.snapshot-gallery-stage { height: min(56vh, 640px); min-height: 340px; overflow: hidden; border: 1px solid var(--el-border-color); border-radius: 8px; background: var(--el-fill-color-light); }
.snapshot-gallery-stage :deep(.el-image) { width: 100%; height: 100%; cursor: zoom-in; }
.snapshot-gallery-caption { display: grid; gap: 4px; }
.snapshot-gallery-strip { display: flex; gap: 10px; overflow-x: auto; padding: 2px 2px 8px; }
.snapshot-gallery-thumb { position: relative; flex: 0 0 112px; height: 82px; padding: 0; overflow: hidden; border: 2px solid transparent; border-radius: 7px; background: transparent; cursor: pointer; }
.snapshot-gallery-thumb.is-active { border-color: var(--el-color-primary); }
.snapshot-gallery-thumb :deep(.el-image) { width: 100%; height: 100%; }
.snapshot-gallery-thumb span { position: absolute; right: 4px; bottom: 4px; min-width: 20px; padding: 1px 5px; border-radius: 9px; color: #fff; background: rgb(0 0 0 / 58%); font-size: 11px; }
@media (max-width: 900px) { .snapshot-gallery-stage { min-height: 260px; } }
</style>
