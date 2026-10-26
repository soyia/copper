<template>
  <div class="mobile-page">
    <el-alert
      title="照片必须归到原业务单"
      description="拍照不会新建采购、入库、交付、应收或收付款；上传成功后服务器会计算 SHA-256 并记录上传人、时间和业务来源。"
      type="info"
      :closable="false"
      show-icon
    />

    <section class="mobile-card">
      <el-form label-position="top">
        <el-form-item label="业务单类型" required>
          <el-select v-model="documentType" @change="loadDocuments">
            <el-option v-for="item in documentTypes" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="归属业务单" required>
          <el-select v-model="documentId" filterable :loading="loadingDocuments" placeholder="按单号、往来单位或日期搜索">
            <el-option v-for="item in candidates" :key="item.id" :label="item.label" :value="item.id">
              <div class="document-option"><span>{{ item.no }} · {{ item.partner }}</span><small>{{ item.date }} · {{ item.status }}</small></div>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="凭据类型" required>
          <el-select v-model="evidenceType">
            <el-option v-for="item in evidenceTypes" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明（选填）">
          <el-input v-model="note" maxlength="500" placeholder="如：司机回传、客户王师傅签收、第二次过磅" />
        </el-form-item>
      </el-form>
    </section>

    <section class="mobile-card capture-card">
      <input
        ref="cameraInput"
        class="native-file-input"
        type="file"
        tabindex="-1"
        aria-hidden="true"
        accept="image/jpeg,image/png,image/webp"
        capture="environment"
        @change="selectFile"
      />
      <input
        ref="albumInput"
        class="native-file-input"
        type="file"
        tabindex="-1"
        aria-hidden="true"
        accept="image/jpeg,image/png,image/webp,application/pdf"
        @change="selectFile"
      />
      <div v-if="previewUrl" class="photo-preview">
        <img :src="previewUrl" alt="待上传凭据预览" />
        <button type="button" aria-label="移除待上传文件" @click="clearFile"><el-icon><Close /></el-icon></button>
      </div>
      <div v-else class="capture-placeholder">
        <el-icon><Camera /></el-icon>
        <strong>拍磅单、签收单或回单</strong>
        <span>先选原业务单，再拍照；原图只在确认上传后进入归档。</span>
      </div>
      <div class="capture-actions">
        <el-button type="primary" size="large" @click="cameraInput?.click()"><el-icon><Camera /></el-icon>立即拍照</el-button>
        <el-button size="large" @click="albumInput?.click()">从相册 / 文件选择</el-button>
      </div>
      <div v-if="selectedFile" class="selected-file">
        <span>{{ selectedFile.name }}</span>
        <strong>{{ fileSize(selectedFile.size) }}</strong>
      </div>
    </section>

    <section v-if="uploaded" class="uploaded-card">
      <el-icon><CircleCheckFilled /></el-icon>
      <div>
        <strong>照片已经归档</strong>
        <span>{{ uploaded.originalFileName }} · SHA-256 {{ shortHash(uploaded.sha256) }}</span>
      </div>
      <el-button plain @click="openDocument">查看业务单</el-button>
    </section>

    <el-button class="upload-button" type="primary" size="large" :loading="uploading" :disabled="!connectivity.isAvailable" @click="upload">
      确认归档
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { Camera, CircleCheckFilled, Close } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { api, type EvidenceAttachment, type EvidenceDocumentType } from '@/api/client'
import { useConnectivityStore } from '@/stores/connectivity'

type Candidate = { id: number; no: string; partner: string; date: string; status: string; label: string }
type EvidenceType = EvidenceAttachment['evidenceType']

const route = useRoute()
const router = useRouter()
const connectivity = useConnectivityStore()
const documentTypes: Array<{ value: EvidenceDocumentType; label: string }> = [
  { value: 'OUTBOUND', label: '销售出库 / 签收单' },
  { value: 'INBOUND', label: '采购入库 / 磅单' },
  { value: 'SALES_ORDER', label: '销售订单' },
  { value: 'PURCHASE_ORDER', label: '采购订单 / 供应商报价' },
  { value: 'PAYMENT', label: '客户回款 / 回单' },
  { value: 'INVENTORY_STOCKTAKE', label: '库存盘点' },
]
const evidenceTypes: Array<{ value: EvidenceType; label: string }> = [
  { value: 'SIGNED_RECEIPT', label: '签字 / 签收单' },
  { value: 'DELIVERY_PROOF', label: '收发货证明 / 磅单' },
  { value: 'PAYMENT_PROOF', label: '收付款凭据' },
  { value: 'ORIGINAL_DOCUMENT', label: '原始电子凭证' },
  { value: 'SIGNED_STAMPED_COPY', label: '签字盖章归档件' },
  { value: 'OTHER', label: '其他业务附件' },
]
const initialType = String(route.query.documentType || '')
const documentType = ref<EvidenceDocumentType>(
  documentTypes.some((item) => item.value === initialType) ? initialType as EvidenceDocumentType : 'OUTBOUND',
)
const documentId = ref<number>()
const evidenceType = ref<EvidenceType>('SIGNED_RECEIPT')
const note = ref('')
const candidates = ref<Candidate[]>([])
const loadingDocuments = ref(false)
const uploading = ref(false)
const selectedFile = ref<File>()
const previewUrl = ref('')
const uploaded = ref<EvidenceAttachment>()
const cameraInput = ref<HTMLInputElement>()
const albumInput = ref<HTMLInputElement>()

onMounted(async () => {
  await loadDocuments()
  const initialId = Number(route.query.documentId || 0)
  if (initialId && candidates.value.some((item) => item.id === initialId)) documentId.value = initialId
})
onBeforeUnmount(revokePreview)

async function loadDocuments() {
  documentId.value = undefined
  candidates.value = []
  loadingDocuments.value = true
  try {
    if (documentType.value === 'OUTBOUND') {
      candidates.value = (await api.outbounds()).map((row) => candidate(row.id, row.orderNo, row.customerName, row.businessDate, row.status))
    } else if (documentType.value === 'INBOUND') {
      candidates.value = (await api.inbounds()).map((row) => candidate(row.id, row.orderNo, row.supplierName, row.businessDate, row.status))
    } else if (documentType.value === 'SALES_ORDER') {
      candidates.value = (await api.salesOrders()).map((row) => candidate(row.id, row.orderNo, row.customerName, row.businessDate, row.status))
    } else if (documentType.value === 'PURCHASE_ORDER') {
      candidates.value = (await api.purchaseOrders()).map((row) => candidate(row.id, row.orderNo, row.supplierName, row.businessDate, row.status))
    } else if (documentType.value === 'PAYMENT') {
      candidates.value = (await api.payments()).map((row) => candidate(row.id, row.paymentNo, row.customerName, row.receivedDate, row.verificationStatus))
    } else {
      candidates.value = (await api.inventoryStocktakes()).map((row) => candidate(row.id, row.orderNo, row.warehouseName, row.businessDate, row.status))
    }
  } finally {
    loadingDocuments.value = false
  }
}

function candidate(id: number, no: string, partner: string, date: string, status: string): Candidate {
  return { id, no, partner, date, status, label: `${no} · ${partner} · ${date} · ${status}` }
}

function selectFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > 20 * 1024 * 1024) {
    ElMessage.warning('单个文件不能超过 20 MB，请降低照片分辨率后重试')
    return
  }
  selectedFile.value = file
  uploaded.value = undefined
  revokePreview()
  if (file.type.startsWith('image/')) previewUrl.value = URL.createObjectURL(file)
}

function clearFile() {
  selectedFile.value = undefined
  revokePreview()
}
function revokePreview() {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
}

async function upload() {
  if (!connectivity.isAvailable) return ElMessage.warning('当前无法连接 Copper 主机，不能上传业务附件')
  if (!documentId.value) return ElMessage.warning('请选择照片归属的原业务单')
  if (!selectedFile.value) return ElMessage.warning('请先拍照或选择文件')
  uploading.value = true
  try {
    uploaded.value = await api.uploadEvidenceAttachment(
      documentType.value,
      documentId.value,
      selectedFile.value,
      evidenceType.value,
      'UPLOAD',
      note.value.trim(),
    )
    clearFile()
    note.value = ''
    ElMessage.success('附件已归到原业务单并计算 SHA-256')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '附件上传失败，请核对后重试')
  } finally {
    uploading.value = false
  }
}

function openDocument() {
  if (!documentId.value) return
  const paths: Partial<Record<EvidenceDocumentType, string>> = {
    OUTBOUND: `/outbounds/${documentId.value}`,
    INBOUND: `/inbounds/${documentId.value}`,
    SALES_ORDER: `/sales-orders/${documentId.value}`,
    PURCHASE_ORDER: `/purchase-orders/${documentId.value}`,
    INVENTORY_STOCKTAKE: `/inventory/stocktakes/${documentId.value}`,
    PAYMENT: '/payments',
  }
  router.push(paths[documentType.value] || '/mobile')
}
function fileSize(bytes: number) { return bytes < 1024 * 1024 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB` }
function shortHash(value: string) { return `${value.slice(0, 12)}…${value.slice(-8)}` }
</script>

<style scoped>
.mobile-page { display: grid; gap: 12px; }
.mobile-card { padding: 15px; border: 1px solid #e2e7ea; border-radius: 17px; background: #fff; }
.mobile-card :deep(.el-select) { width: 100%; }
.document-option { display: grid; gap: 2px; }
.document-option small { color: #82909a; font-size: 10px; }
.native-file-input { position: fixed; width: 1px; height: 1px; opacity: 0; pointer-events: none; }
.capture-card { display: grid; gap: 12px; }
.capture-placeholder { display: grid; min-height: 210px; place-items: center; align-content: center; gap: 7px; border: 1px dashed #ccd7dd; border-radius: 15px; color: #536570; background: #f7f9fa; text-align: center; }
.capture-placeholder > .el-icon { color: #bc5528; font-size: 36px; }
.capture-placeholder span { max-width: 260px; color: #84919a; font-size: 11px; line-height: 1.55; }
.photo-preview { position: relative; overflow: hidden; min-height: 220px; border-radius: 15px; background: #e9eef1; }
.photo-preview img { display: block; width: 100%; max-height: 440px; object-fit: contain; }
.photo-preview button { position: absolute; top: 10px; right: 10px; display: grid; width: 34px; height: 34px; place-items: center; border: 0; border-radius: 50%; color: #fff; background: rgb(0 0 0 / 58%); }
.capture-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.capture-actions .el-button { min-height: 46px; margin: 0; }
.selected-file { display: flex; justify-content: space-between; gap: 10px; color: #6e7d86; font-size: 11px; }
.selected-file span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.uploaded-card { display: grid; grid-template-columns: 28px 1fr auto; align-items: center; gap: 10px; padding: 14px; border: 1px solid #cce5d3; border-radius: 15px; color: #27613e; background: #f0f9f3; }
.uploaded-card > .el-icon { font-size: 23px; }
.uploaded-card div { display: grid; min-width: 0; gap: 3px; }
.uploaded-card span { overflow: hidden; color: #668273; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.upload-button { min-height: 47px; width: 100%; }
@media (max-width: 520px) {
  .capture-actions { grid-template-columns: 1fr; }
  .uploaded-card { grid-template-columns: 28px 1fr; }
  .uploaded-card .el-button { grid-column: 1 / -1; }
}
</style>
