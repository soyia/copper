<template>
  <div class="panel evidence-panel">
    <div class="panel-header evidence-header">
      <div>
        <div class="panel-title">{{ title }}</div>
        <div class="muted evidence-subtitle">可随时补传。已确认、已审核或无需审核的单据附件不能删除；错误文件只能留痕作废。</div>
      </div>
      <el-upload
        v-if="canManage"
        :show-file-list="false"
        :http-request="upload"
        accept=".jpg,.jpeg,.png,.webp,.pdf,.ofd,.xml"
      >
        <el-button type="primary" plain :loading="uploading">上传附件</el-button>
      </el-upload>
    </div>

    <div v-if="canManage" class="evidence-controls">
      <el-select v-model="evidenceType" style="width: 170px">
        <el-option v-for="item in evidenceTypes" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-select v-model="sourceChannel" style="width: 170px">
        <el-option v-for="item in sourceChannels" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-input v-model="note" clearable maxlength="500" placeholder="选填：签收人、查验批次或凭据说明" />
    </div>

    <el-alert
      class="archive-guidance"
      type="info"
      :closable="false"
      show-icon
      title="打印后请完成经办人、对方单位或复核人的签字盖章，再选择“签字盖章归档件”拍照或扫描补传；系统会保留原文件哈希、上传人和时间。"
    />

    <div v-if="imageAttachments.length" class="evidence-preview-section">
      <div class="evidence-section-title">签字盖章与现场影像</div>
      <div class="evidence-preview-grid">
        <div v-for="(item, index) in imageAttachments" :key="item.id" class="evidence-preview-card">
          <el-image
            :src="previewUrl(item.id)"
            :preview-src-list="previewUrls"
            :initial-index="index"
            fit="cover"
            preview-teleported
          />
          <div class="evidence-preview-meta">
            <strong>{{ evidenceTypeName(item.evidenceType) }}</strong>
            <span>{{ item.uploadedByName || '系统' }} · {{ formatDateTime(item.uploadedAt) }}</span>
          </div>
        </div>
      </div>
    </div>

    <el-table v-loading="query.isLoading.value" :data="attachments" border size="small" class="data-table evidence-table" empty-text="尚未上传附件，可在业务完成后补传">
      <el-table-column label="凭据" min-width="220">
        <template #default="{ row }">
          <a class="business-link evidence-file" :href="contentUrl(row.id)" target="_blank" rel="noopener">{{ row.originalFileName }}</a>
          <div class="multiline-secondary">{{ evidenceTypeName(row.evidenceType) }} · {{ fileSize(row.fileSize) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="来源与留存" min-width="210">
        <template #default="{ row }">
          <div>{{ sourceChannelName(row.sourceChannel) }} · {{ row.uploadedByName || '系统' }}</div>
          <div class="multiline-secondary">{{ formatDateTime(row.uploadedAt) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="SHA-256" min-width="200">
        <template #default="{ row }"><span class="hash" :title="row.sha256">{{ shortHash(row.sha256) }}</span></template>
      </el-table-column>
      <el-table-column prop="note" label="说明" min-width="180" show-overflow-tooltip />
      <el-table-column label="状态" width="105">
        <template #default="{ row }">
          <el-tag v-if="row.status === 'ACTIVE'" size="small" type="success">有效</el-tag>
          <el-tooltip v-else :content="`作废原因：${row.voidReason || '-'}；${row.voidedByName || '系统'} ${formatDateTime(row.voidedAt)}`">
            <el-tag size="small" type="info">已作废留痕</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column v-if="canManage" label="操作" width="145" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :href="contentUrl(row.id)" tag="a">下载</el-button>
          <el-button v-if="row.canDelete" link type="danger" @click="remove(row.id)">删除</el-button>
          <el-button v-else-if="row.status === 'ACTIVE'" link type="warning" @click="voidAttachment(row.id)">作废留痕</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="showOutputHistory" class="output-history">
      <div class="evidence-section-title">打印 / 另存 PDF 留痕</div>
      <el-table v-loading="outputQuery.isLoading.value" :data="outputRecords" border size="small" empty-text="尚无打印或导出记录">
        <el-table-column prop="outputFileName" label="输出文件" min-width="220" />
        <el-table-column label="单据状态" width="120"><template #default="{ row }">{{ row.statusSnapshot }}</template></el-table-column>
        <el-table-column label="数据摘要" min-width="190"><template #default="{ row }"><span class="hash" :title="row.snapshotHash">{{ shortHash(row.snapshotHash) }}</span></template></el-table-column>
        <el-table-column prop="templateVersion" label="模板版本" min-width="180" />
        <el-table-column prop="operatorName" label="操作人" width="120" />
        <el-table-column label="时间" width="170"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox, type UploadRequestOptions } from 'element-plus'
import { api, type EvidenceAttachment, type EvidenceDocumentType } from '@/api/client'
import { formatDateTime } from '@/api/format'
import { useAppStore } from '@/stores/app'

const props = withDefaults(defineProps<{
  documentType: EvidenceDocumentType
  documentId: number
  title?: string
  defaultEvidenceType?: EvidenceAttachment['evidenceType']
  defaultSourceChannel?: EvidenceAttachment['sourceChannel']
  showOutputHistory?: boolean
}>(), {
  title: '签字、签收与业务附件',
  defaultEvidenceType: 'SIGNED_RECEIPT',
  defaultSourceChannel: 'UPLOAD',
  showOutputHistory: false,
})

const appStore = useAppStore()
const queryClient = useQueryClient()
const uploading = ref(false)
const evidenceType = ref<EvidenceAttachment['evidenceType']>(props.defaultEvidenceType)
const sourceChannel = ref<EvidenceAttachment['sourceChannel']>(props.defaultSourceChannel)
const note = ref('')
const queryKey = computed(() => ['evidence-attachments', props.documentType, props.documentId])
const query = useQuery({ queryKey, queryFn: () => api.evidenceAttachments(props.documentType, props.documentId) })
const attachments = computed(() => query.data.value || [])
const imageAttachments = computed(() => attachments.value.filter((item) => item.status === 'ACTIVE' && item.contentType.startsWith('image/')))
const previewUrls = computed(() => imageAttachments.value.map((item) => previewUrl(item.id)))
const outputQueryKey = computed(() => ['evidence-output-records', props.documentType, props.documentId])
const outputQuery = useQuery({
  queryKey: outputQueryKey,
  queryFn: () => api.evidenceOutputRecords(props.documentType, props.documentId),
  enabled: computed(() => props.showOutputHistory),
})
const outputRecords = computed(() => outputQuery.data.value || [])
const canManage = computed(() =>
  appStore.hasPermission('evidence.manage')
  || (props.documentType === 'INVENTORY_NON_TRADE' && appStore.hasPermission('inventory.nontrade.manage')))

const allEvidenceTypes: Array<{ value: EvidenceAttachment['evidenceType']; label: string }> = [
  { value: 'ORIGINAL_DOCUMENT', label: '原始电子凭证' },
  { value: 'SIGNED_RECEIPT', label: '签字 / 签收单' },
  { value: 'SIGNED_STAMPED_COPY', label: '签字盖章归档件' },
  { value: 'DELIVERY_PROOF', label: '收发货证明' },
  { value: 'PAYMENT_PROOF', label: '收付款凭据' },
  { value: 'VERIFICATION_PROOF', label: '发票查验凭证' },
  { value: 'VISUAL_COPY', label: 'PDF / 图片可视件' },
  { value: 'OTHER', label: '其他附件' },
]
const evidenceTypes = computed(() => allEvidenceTypes.filter((item) => item.value !== 'VERIFICATION_PROOF' || props.documentType === 'TAX_INVOICE'))
const allSourceChannels: Array<{ value: EvidenceAttachment['sourceChannel']; label: string }> = [
  { value: 'UPLOAD', label: '业务人员上传' },
  { value: 'TAX_DIGITAL_ACCOUNT', label: '税务数字账户' },
  { value: 'IMPORT', label: '系统导入' },
  { value: 'SCAN', label: '纸质扫描' },
  { value: 'SYSTEM', label: '系统生成' },
]
const sourceChannels = computed(() => allSourceChannels.filter((item) =>
  item.value !== 'SYSTEM' && (!['TAX_DIGITAL_ACCOUNT', 'IMPORT'].includes(item.value) || props.documentType === 'TAX_INVOICE')))

async function upload(options: UploadRequestOptions) {
  uploading.value = true
  try {
    await api.uploadEvidenceAttachment(props.documentType, props.documentId, options.file,
      evidenceType.value, sourceChannel.value, note.value)
    ElMessage.success('附件已留存并计算 SHA-256，可继续补传其他凭据')
    note.value = ''
    await queryClient.invalidateQueries({ queryKey: queryKey.value })
  } finally { uploading.value = false }
}

async function remove(id: number) {
  await ElMessageBox.confirm('仅草稿附件允许删除。删除后文件不可恢复，确认继续？', '删除草稿附件', { type: 'warning' })
  await api.deleteEvidenceAttachment(props.documentType, props.documentId, id)
  ElMessage.success('草稿附件已删除')
  await queryClient.invalidateQueries({ queryKey: queryKey.value })
}

async function voidAttachment(id: number) {
  const result = await ElMessageBox.prompt('文件会继续保留在审计和归档包中，请说明作废原因并补传正确文件。', '作废附件（保留原件）', {
    type: 'warning', inputValidator: (value) => String(value || '').trim() || '必须填写作废原因',
  })
  await api.voidEvidenceAttachment(props.documentType, props.documentId, id, result.value.trim())
  ElMessage.success('附件已作废留痕，原始文件仍被保留')
  await queryClient.invalidateQueries({ queryKey: queryKey.value })
}

function contentUrl(id: number) { return api.evidenceAttachmentUrl(props.documentType, props.documentId, id) }
function previewUrl(id: number) { return api.evidenceAttachmentPreviewUrl(props.documentType, props.documentId, id) }
function evidenceTypeName(value: string) { return allEvidenceTypes.find((item) => item.value === value)?.label || value }
function sourceChannelName(value: string) { return allSourceChannels.find((item) => item.value === value)?.label || value }
function fileSize(bytes: number) { if (bytes < 1024) return `${bytes} B`; if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`; return `${(bytes / 1024 / 1024).toFixed(1)} MB` }
function shortHash(value: string) { return value ? `${value.slice(0, 12)}…${value.slice(-8)}` : '-' }
</script>

<style scoped>
.evidence-panel { margin-top: 14px; }
.evidence-header { align-items: flex-start; }
.evidence-subtitle { margin-top: 5px; font-size: 12px; }
.evidence-controls { display: grid; grid-template-columns: 170px 170px minmax(240px, 1fr); gap: 10px; margin: 14px 0; }
.evidence-table { margin-top: 12px; }
.archive-guidance { margin-top: 12px; }
.evidence-preview-section, .output-history { margin-top: 16px; }
.evidence-section-title { margin-bottom: 9px; font-size: 14px; font-weight: 650; }
.evidence-preview-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(170px, 1fr)); gap: 10px; }
.evidence-preview-card { min-width: 0; overflow: hidden; border: 1px solid #dfe6eb; border-radius: 8px; background: #fafcfd; }
.evidence-preview-card :deep(.el-image) { display: block; width: 100%; height: 150px; background: #eef2f5; cursor: zoom-in; }
.evidence-preview-meta { display: flex; flex-direction: column; gap: 3px; padding: 8px 10px; font-size: 12px; }
.evidence-preview-meta span { color: #73818c; }
.evidence-file { display: inline-block; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; vertical-align: bottom; }
.hash { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
@media (max-width: 900px) { .evidence-controls { grid-template-columns: 1fr; } }
</style>
