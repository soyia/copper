<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">{{ businessTypeName(header?.businessType) }}单 {{ header?.documentNo || '' }}</div>
          <div class="muted detail-subtitle">非购销库存事项 · {{ header?.itemCount || items.length }} 项商品明细</div>
        </div>
        <div class="header-actions no-print">
          <el-button @click="router.push('/inventory/non-trade')">返回列表</el-button>
          <el-button @click="printSignedDocument">打印签字单</el-button>
          <el-button
            v-if="header?.status === 'DRAFT' && appStore.hasPermission('inventory.nontrade.manage')"
            type="primary"
            :loading="issuing"
            @click="issueDocument"
          >
            确认发放并扣库存
          </el-button>
          <el-button
            v-if="header?.status === 'ISSUED_PENDING_SIGNATURE' && appStore.hasPermission('inventory.nontrade.manage')"
            type="primary"
            :loading="archiving"
            @click="archiveSignature"
          >
            确认签字归档
          </el-button>
          <el-button
            v-if="['ISSUED_PENDING_SIGNATURE', 'CONFIRMED'].includes(header?.status || '') && appStore.hasPermission('inventory.nontrade.manage')"
            type="danger"
            plain
            @click="reverseDocument"
          >
            冲正
          </el-button>
        </div>
      </div>

      <el-alert
        v-if="header?.status === 'DRAFT'"
        class="no-print"
        title="当前仅为草稿，尚未扣库存。实物交给领用人时请确认发放，系统会立即逐项扣减库存；签字单可随后上传归档。"
        type="warning"
        :closable="false"
        show-icon
      />
      <el-alert
        v-else-if="header?.status === 'ISSUED_PENDING_SIGNATURE'"
        class="no-print"
        title="实物已经发放并扣减库存，当前等待实际领用人签字回传。请上传并选择签字单完成归档；不要再次办理扣库。"
        type="warning"
        :closable="false"
        show-icon
      />
      <el-alert
        v-else-if="header?.status === 'REVERSED'"
        class="no-print"
        title="当前单据已冲正。下方扣前、扣取和扣后数量保留原办理时的审计快照；恢复库存请以当前库存批次和冲正流水为准。"
        type="info"
        :closable="false"
        show-icon
      />

      <el-descriptions v-if="header" :column="3" border class="detail-descriptions">
        <el-descriptions-item label="状态"><el-tag :type="statusType(header.status)">{{ statusName(header.status, header.direction) }}</el-tag></el-descriptions-item>
        <el-descriptions-item label="业务日期">{{ formatDate(header.businessDate) }}</el-descriptions-item>
        <el-descriptions-item label="商品明细">{{ header.itemCount || items.length }} 项</el-descriptions-item>
        <el-descriptions-item label="实际领用人（签字人）"><strong>{{ header.recipient || '-' }}</strong></el-descriptions-item>
        <el-descriptions-item label="经办人 / 发放人">{{ header.handlerName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="系统制单人">{{ header.createdByName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="确认发放人">{{ header.issuedByName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="库存扣减时间">{{ formatDateTime(header.issuedAt) }}</el-descriptions-item>
        <el-descriptions-item label="签字归档">
          {{ header.confirmedByName ? `${header.confirmedByName} · ${formatDateTime(header.confirmedAt)}` : '-' }}
        </el-descriptions-item>
        <el-descriptions-item label="原因" :span="3">{{ header.reason }}</el-descriptions-item>
        <el-descriptions-item label="备注" :span="3">{{ header.memo || '-' }}</el-descriptions-item>
      </el-descriptions>

      <div class="section-title">商品扣取明细</div>
      <div v-for="item in items" :key="item.id" class="item-card">
        <el-descriptions :column="4" border>
          <el-descriptions-item :label="`商品 ${item.lineNo}`" :span="3">
            {{ item.productName }} / {{ item.spec || '无规格' }}
          </el-descriptions-item>
          <el-descriptions-item label="SKU">{{ item.sku || '-' }}</el-descriptions-item>
          <el-descriptions-item label="仓库 / 批次">{{ item.warehouseName }}/{{ item.batchNo }}</el-descriptions-item>
          <el-descriptions-item :label="header?.direction === 'IN' ? '入库前账面结存' : '扣前账面结存'">{{ quantity(item.quantityBefore) }} {{ item.baseUnit }}</el-descriptions-item>
          <el-descriptions-item :label="header?.direction === 'IN' ? '本商品增加数量' : '本商品扣取数量'"><strong>{{ quantity(item.quantity) }} {{ item.baseUnit }}</strong></el-descriptions-item>
          <el-descriptions-item :label="header?.direction === 'IN' ? '入库后账面结存' : '扣后账面结存'">{{ quantity(item.quantityAfter) }} {{ item.baseUnit }}</el-descriptions-item>
        </el-descriptions>
        <el-table v-if="item.packageAllocations.length" :data="item.packageAllocations" border class="allocation-table">
          <el-table-column label="来源包装" min-width="240">
            <template #default="{ row }">{{ row.packageCode || `包装#${row.packageInstanceId}` }}</template>
          </el-table-column>
          <el-table-column label="包装单位" width="130">
            <template #default="{ row }">{{ row.unitName || '-' }}</template>
          </el-table-column>
          <el-table-column label="扣前数量" width="180" align="right">
            <template #default="{ row }">{{ allocationQuantity(row.quantityBefore, item.baseUnit) }}</template>
          </el-table-column>
          <el-table-column label="扣取数量" width="180" align="right">
            <template #default="{ row }">{{ quantity(row.quantity) }} {{ item.baseUnit }}</template>
          </el-table-column>
          <el-table-column label="扣后数量" width="180" align="right">
            <template #default="{ row }">{{ allocationQuantity(row.quantityAfter, item.baseUnit) }}</template>
          </el-table-column>
        </el-table>
        <div v-else class="quantity-only-note">该批次按数量口径扣取，无逐包装实重明细。</div>
      </div>

      <div v-if="header?.status === 'ISSUED_PENDING_SIGNATURE'" class="confirmation-box no-print">
        <div class="section-title">实际领用人签字单据</div>
        <el-select v-model="selectedSignedAttachmentId" placeholder="上传后选择实际领用人已经签字的单据" style="width: 100%">
          <el-option
            v-for="item in signedAttachments"
            :key="item.id"
            :value="item.id"
            :label="`${item.originalFileName} · ${formatDateTime(item.uploadedAt)}`"
          />
        </el-select>
        <div class="muted confirmation-help">归档时冻结该附件的 ID 与 SHA-256；库存已在确认发放时扣减，本操作不会再次扣库存。</div>
      </div>
    </div>

    <EvidenceAttachments
      v-if="documentId"
      class="no-print"
      document-type="INVENTORY_NON_TRADE"
      :document-id="documentId"
      title="实际领用人签字单据与附件"
      default-evidence-type="SIGNED_RECEIPT"
    />

    <section v-if="header" class="print-sheet a4-print-sheet">
      <div class="document-print-heading">
        <div class="document-print-company">{{ appStore.settings.companyName }}</div>
        <div class="document-print-title">{{ businessTypeName(header.businessType) }}单（签字回传）</div>
        <div class="document-print-subtitle">
          <span v-if="appStore.settings.companyPhone">电话：{{ appStore.settings.companyPhone }}</span>
          <span>打印时间：{{ printTime }}</span>
        </div>
      </div>
      <table class="document-print-meta">
        <tbody>
          <tr>
            <td>单据编号：{{ header.documentNo }}</td>
            <td>业务日期：{{ formatDate(header.businessDate) }}</td>
            <td>商品明细：{{ header.itemCount || items.length }} 项</td>
          </tr>
          <tr>
            <td>实际领用人：{{ header.recipient || '-' }}</td>
            <td>经办人 / 发放人：{{ header.handlerName || '-' }}</td>
            <td>制单人：{{ header.createdByName || '-' }}</td>
          </tr>
          <tr><td colspan="3">原因：{{ header.reason }}</td></tr>
        </tbody>
      </table>
      <table class="document-print-table">
        <thead>
          <tr><th>#</th><th>商品 / 规格</th><th>仓库 / 批次</th><th>来源包装</th><th>扣取数量</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in printRows" :key="row.key">
            <td class="center">{{ row.lineNo }}</td>
            <td>{{ row.productText }}</td>
            <td>{{ row.batchText }}</td>
            <td>{{ row.packageText }}</td>
            <td class="right">{{ quantity(row.quantity) }} {{ row.baseUnit }}</td>
          </tr>
        </tbody>
      </table>
      <table class="document-print-total">
        <tbody>
          <tr><td>共 {{ header.itemCount || items.length }} 项商品明细；各商品按自身库存单位分别扣取，不跨单位合并总数量。</td></tr>
          <tr><td>备注：{{ header.memo || '无' }}</td></tr>
        </tbody>
      </table>
      <div class="document-print-signatures">
        <span>制单人：{{ header.createdByName || '' }}</span>
        <span>经办人 / 发放人：{{ header.handlerName || '' }}</span>
        <span>实际领用人签字：</span>
        <span>日期：</span>
      </div>
      <div class="document-print-note">实物交付并确认发放时，系统立即逐项扣减库存；实际领用人签字后，请拍照或扫描上传本单原件完成归档。</div>
    </section>

    <div v-if="detail?.events?.length" class="panel no-print">
      <div class="panel-title">操作记录</div>
      <el-table :data="detail.events" border class="data-table">
        <el-table-column label="动作" width="130"><template #default="{ row }">{{ eventName(row.action) }}</template></el-table-column>
        <el-table-column prop="reason" label="说明" min-width="260" />
        <el-table-column prop="operatorName" label="操作人" width="140" />
        <el-table-column label="时间" width="190" align="center"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import { formatDate, formatDateTime, quantity } from '@/api/format'
import EvidenceAttachments from '@/components/EvidenceAttachments.vue'
import { useAppStore } from '@/stores/app'
import { printFileName, useDocumentPrint } from '@/composables/useDocumentPrint'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const queryClient = useQueryClient()
const documentId = Number(route.params.id)
const issuing = ref(false)
const archiving = ref(false)
const selectedSignedAttachmentId = ref<number>()
const { printTime, printDocument } = useDocumentPrint(['is-a4-printing'])

const detailQuery = useQuery({
  queryKey: ['inventory-non-trade-detail', documentId],
  queryFn: () => api.inventoryNonTradeDetail(documentId),
})
const detail = computed(() => detailQuery.data.value)
const header = computed(() => detail.value?.header)
const items = computed(() => detail.value?.items || [])

const evidenceKey = computed(() => ['evidence-attachments', 'INVENTORY_NON_TRADE', documentId])
const evidenceQuery = useQuery({
  queryKey: evidenceKey,
  queryFn: () => api.evidenceAttachments('INVENTORY_NON_TRADE', documentId),
})
const signedAttachments = computed(() => (evidenceQuery.data.value || []).filter((item) =>
  item.status === 'ACTIVE' && ['SIGNED_RECEIPT', 'SIGNED_STAMPED_COPY'].includes(item.evidenceType)))
watch(signedAttachments, (rows) => {
  if (!selectedSignedAttachmentId.value && rows.length) selectedSignedAttachmentId.value = rows[rows.length - 1].id
}, { immediate: true })

const printRows = computed(() => items.value.flatMap((item) => {
  const allocations = item.packageAllocations
  if (!allocations.length) {
    return [{
      key: `${item.id}-quantity`,
      lineNo: item.lineNo,
      productText: `${item.productName} / ${item.spec || '无规格'}`,
      batchText: `${item.warehouseName}/${item.batchNo}`,
      packageText: '批次数量口径',
      quantity: item.quantity,
      baseUnit: item.baseUnit,
    }]
  }
  return allocations.map((allocation) => ({
    key: `${item.id}-${allocation.packageInstanceId}`,
    lineNo: item.lineNo,
    productText: `${item.productName} / ${item.spec || '无规格'}`,
    batchText: `${item.warehouseName}/${item.batchNo}`,
    packageText: allocation.packageCode || `包装#${allocation.packageInstanceId}`,
    quantity: allocation.quantity,
    baseUnit: item.baseUnit,
  }))
}))

async function issueDocument() {
  await ElMessageBox.confirm(
    `确认 ${header.value?.itemCount || items.value.length} 项商品已经实际交给“${header.value?.recipient || '-'}”？确认后立即逐项扣减库存并生成成本过账，签字单可随后补传。`,
    '确认实际发放',
    { type: 'warning', confirmButtonText: '确认发放并扣库存' },
  )
  issuing.value = true
  try {
    await api.issueInventoryNonTrade(documentId)
    ElMessage.success('已按实际发放逐项扣减库存，单据进入待签字状态')
    await Promise.all([
      detailQuery.refetch(),
      queryClient.invalidateQueries({ queryKey: ['inventory-batches'] }),
      queryClient.invalidateQueries({ queryKey: ['inventory-non-trade'] }),
    ])
  } finally {
    issuing.value = false
  }
}

async function archiveSignature() {
  if (!selectedSignedAttachmentId.value) return ElMessage.warning('请先上传并选择实际领用人签字单据')
  await ElMessageBox.confirm(
    `确认实际领用人“${header.value?.recipient || '-'}”已经签字，并将所选附件作为本次真实领用凭证归档？`,
    '确认签字归档',
    { type: 'warning', confirmButtonText: '确认签字归档' },
  )
  archiving.value = true
  try {
    await api.archiveInventoryNonTradeSignature(documentId, selectedSignedAttachmentId.value)
    ElMessage.success('实际领用人签字单据已冻结归档；库存未重复扣减')
    await Promise.all([
      detailQuery.refetch(),
      evidenceQuery.refetch(),
      queryClient.invalidateQueries({ queryKey: ['inventory-non-trade'] }),
    ])
  } finally {
    archiving.value = false
  }
}

async function reverseDocument() {
  const result = await ElMessageBox.prompt('冲正会逐项恢复原批次及包装余额，请填写原因。', '冲正送样 / 领用 / 损耗单', {
    type: 'warning',
    inputValidator: (value) => String(value || '').trim() || '必须填写冲正原因',
  })
  await api.reverseInventoryNonTrade(documentId, result.value.trim())
  ElMessage.success('已冲正并逐项恢复库存')
  await detailQuery.refetch()
  await queryClient.invalidateQueries({ queryKey: ['inventory-batches'] })
}

async function printSignedDocument() {
  if (!header.value) return
  await printDocument(printFileName(header.value.recipient || '领用人', businessTypeName(header.value.businessType), header.value.documentNo))
}
function businessTypeName(value?: string) { return ({ SAMPLE_OUT: '送样', INTERNAL_USE: '内部领用', NORMAL_LOSS: '合理损耗', OTHER_OUT: '其他出库', OTHER_IN: '其他入库' } as Record<string, string>)[value || ''] || value || '' }
function statusName(value: string, direction?: string) {
  if (value === 'CONFIRMED') return direction === 'IN' ? '已确认入库' : '已签字归档'
  return ({ DRAFT: '草稿待发放', ISSUED_PENDING_SIGNATURE: '已发放待签字', CANCELLED: '已取消', REVERSED: '已冲正' } as Record<string, string>)[value] || value
}
function statusType(value: string) { return value === 'CONFIRMED' ? 'success' : ['DRAFT', 'ISSUED_PENDING_SIGNATURE'].includes(value) ? 'warning' : 'info' }
function eventName(value: string) { return ({ CREATE: '建立草稿', ISSUE: '确认发放并扣库存', RECEIVE: '确认入库', SIGN_ARCHIVE: '签字归档', CONFIRM: '确认扣库', REVERSE: '冲正' } as Record<string, string>)[value] || value }
function allocationQuantity(value: number | null | undefined, unit: string) {
  return value === null || value === undefined ? '-' : `${quantity(value)} ${unit}`
}
</script>

<style scoped>
.detail-subtitle { margin-top: 5px; }
.header-actions { display: flex; gap: 8px; }
.detail-descriptions { margin-top: 16px; }
.section-title { margin: 20px 0 10px; font-size: var(--app-title-font-size); font-weight: 650; }
.item-card { margin-bottom: 14px; padding: 14px; border: 1px solid var(--el-border-color); border-radius: 8px; }
.allocation-table { margin-top: 10px; }
.quantity-only-note { padding: 10px 4px 0; color: var(--el-text-color-secondary); }
.confirmation-box { margin-top: 18px; padding: 16px; border: 1px solid var(--el-color-warning-light-5); border-radius: 8px; background: var(--el-color-warning-light-9); }
.confirmation-box .section-title { margin-top: 0; }
.confirmation-help { margin-top: 8px; }
@media print {
  .no-print { display: none !important; }
  .page { padding: 0; }
  .panel { border: 0; box-shadow: none; }
}
</style>
