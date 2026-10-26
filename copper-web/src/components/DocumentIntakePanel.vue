<template>
  <div class="document-intake">
    <div class="panel-header responsive-header">
      <div>
        <div class="panel-title">待确认单据</div>
        <div class="muted">统一保留报价单、磅单、签收单和收发货包装标签候选。上传与识别不会创建采购、入库、库存、签收或收付款事实；包装标签识别同样不会创建出库。</div>
      </div>
      <el-button v-if="canManage" type="primary" @click="uploadVisible=true">上传识别</el-button>
    </div>
    <el-table :data="query.data.value || []" border size="small" class="data-table">
      <el-table-column prop="intakeNo" label="识别编号" min-width="180" />
      <el-table-column label="类型" width="125"><template #default="{ row }">{{ typeName(row.documentType) }}</template></el-table-column>
      <el-table-column prop="originalFileName" label="原文件" min-width="190" show-overflow-tooltip />
      <el-table-column label="提取方式" width="110"><template #default="{ row }">{{ modeName(row.extractionMode) }}</template></el-table-column>
      <el-table-column label="状态" width="105"><template #default="{ row }"><el-tag :type="row.status === 'CONFIRMED' ? 'success' : row.status === 'REJECTED' ? 'info' : 'warning'">{{ statusName(row.status) }}</el-tag></template></el-table-column>
      <el-table-column prop="uploadedByName" label="上传人" width="110" />
      <el-table-column prop="createdAt" label="上传时间" width="175" />
      <el-table-column label="操作" width="100"><template #default="{ row }"><el-button link type="primary" @click="open(row.id)">{{ row.status === 'REVIEW_REQUIRED' ? '核对候选' : '查看' }}</el-button></template></el-table-column>
    </el-table>

    <el-dialog v-model="uploadVisible" title="上传单据并生成候选" width="720px" :close-on-click-modal="false">
      <el-alert type="info" :closable="false" show-icon title="可复制文字的 PDF 会自动提取；图片或扫描件可同时粘贴设备识别出的文字。图片识别服务不可用时仍会生成空白待确认候选，不阻断经营主流程。" />
      <el-form label-width="100px" style="margin-top:16px">
        <el-form-item label="单据类型" required><el-select v-model="uploadForm.documentType"><el-option label="供应商报价单" value="SUPPLIER_QUOTE" /><el-option label="磅单" value="WEIGH_TICKET" /><el-option label="签收单" value="SIGNED_RECEIPT" /></el-select></el-form-item>
        <el-form-item label="原始文件" required><input type="file" accept=".pdf,image/*,.txt,.csv" @change="selectFile" /></el-form-item>
        <el-form-item label="可复制文字"><el-input v-model="uploadForm.providedText" type="textarea" :rows="7" maxlength="12000" show-word-limit placeholder="可选：扫描图片可粘贴手机或扫描工具识别出的文字，系统只生成待核对候选。" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="uploadVisible=false">取消</el-button><el-button type="primary" :loading="uploading" @click="upload">生成候选</el-button></template>
    </el-dialog>

    <el-dialog v-model="detailVisible" title="核对识别候选" width="820px" :close-on-click-modal="false">
      <template v-if="active">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="单据类型">{{ typeName(active.documentType) }}</el-descriptions-item>
          <el-descriptions-item label="原文件">
            {{ active.originalFileName }}
            <el-button link type="primary" @click="downloadOriginal">下载核对</el-button>
          </el-descriptions-item>
          <el-descriptions-item label="SHA-256" :span="2"><span class="hash">{{ active.sha256 }}</span></el-descriptions-item>
          <el-descriptions-item v-if="active.extractionMode === 'OCR'" label="OCR 引擎">{{ active.ocrEngine }} / {{ active.ocrModel }}</el-descriptions-item>
          <el-descriptions-item v-if="active.extractionMode === 'OCR'" label="OCR 响应摘要"><span class="hash">{{ active.ocrResponseSha256 }}</span></el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="active.extractionMode === 'FILE_ONLY'" type="warning" :closable="false" show-icon title="当前文件没有可提取文字，请按原图人工填写候选值；保存后仍只是已核对候选。" />
        <el-table :data="reviewCandidates" border size="small" class="candidate-table">
          <el-table-column prop="fieldLabel" label="字段" width="145" />
          <el-table-column label="识别值" min-width="180"><template #default="{ row }">{{ row.candidateValue || '未识别' }}</template></el-table-column>
          <el-table-column label="置信度" width="100"><template #default="{ row }"><el-tag :type="row.confidence >= .8 ? 'success' : row.confidence > 0 ? 'warning' : 'danger'">{{ Math.round(row.confidence * 100) }}%</el-tag></template></el-table-column>
          <el-table-column label="人工确认值" min-width="220"><template #default="{ row }"><el-input v-model="row.confirmedValue" :disabled="active.status !== 'REVIEW_REQUIRED'" /></template></el-table-column>
        </el-table>
        <el-input v-if="active.status === 'REVIEW_REQUIRED'" v-model="reviewNote" type="textarea" :rows="2" maxlength="500" placeholder="可选：记录核对说明；驳回时必须填写原因" />
        <div v-else class="review-result">审核：{{ statusName(active.status) }} · {{ active.reviewedByName }} · {{ active.reviewNote || '无说明' }}</div>
      </template>
      <template #footer>
        <el-button @click="detailVisible=false">关闭</el-button>
        <template v-if="active?.status === 'REVIEW_REQUIRED' && canManage">
          <el-button type="danger" plain :loading="reviewing" @click="review('REJECTED')">驳回候选</el-button>
          <el-button type="primary" :loading="reviewing" @click="review('CONFIRMED')">确认候选</el-button>
        </template>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { api, type DocumentIntakeBatch } from '@/api/client'
import { useAppStore } from '@/stores/app'
const appStore = useAppStore()
const canManage = computed(() => appStore.hasPermission('document.intake.manage'))
const query = useQuery({ queryKey: ['document-intakes'], queryFn: () => api.documentIntakes() })
const uploadVisible = ref(false); const uploading = ref(false); const selectedFile = ref<File>()
const uploadForm = reactive({ documentType: 'SUPPLIER_QUOTE', providedText: '' })
const detailVisible = ref(false); const active = ref<DocumentIntakeBatch>(); const reviewCandidates = ref<Array<any>>([]); const reviewNote = ref(''); const reviewing = ref(false)
function selectFile(event: Event) { selectedFile.value = (event.target as HTMLInputElement).files?.[0] }
async function upload() {
  if (!selectedFile.value) return ElMessage.warning('请选择原始文件')
  uploading.value = true
  try {
    const result = await api.createDocumentIntake(uploadForm.documentType, selectedFile.value, uploadForm.providedText.trim())
    ElMessage.success('识别候选已载入；如原文件曾上传，将直接打开原记录')
    uploadVisible.value = false
    selectedFile.value = undefined
    uploadForm.providedText = ''
    await query.refetch()
    await open(result.id)
  } finally { uploading.value = false }
}
async function open(id: number) {
  active.value = await api.documentIntake(id)
  reviewCandidates.value = active.value.candidates.map(item => ({ ...item, confirmedValue: item.confirmedValue || item.candidateValue }))
  reviewNote.value = active.value.reviewNote || ''
  detailVisible.value = true
}
async function downloadOriginal() {
  if (!active.value) return
  const blob = await api.documentIntakeContent(active.value.id)
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = active.value.originalFileName
  link.click()
  URL.revokeObjectURL(url)
}
async function review(decision: 'CONFIRMED' | 'REJECTED') {
  if (decision === 'REJECTED' && reviewNote.value.trim().length < 2) return ElMessage.warning('驳回候选必须填写原因')
  reviewing.value = true
  try {
    active.value = await api.reviewDocumentIntake(active.value!.id, { decision, note: reviewNote.value, candidates: reviewCandidates.value.map(item => ({ fieldCode: item.fieldCode, confirmedValue: item.confirmedValue })) })
    ElMessage.success(decision === 'CONFIRMED' ? '候选已人工确认；尚未写入正式业务' : '候选已驳回')
    detailVisible.value = false
    await query.refetch()
  } finally { reviewing.value = false }
}
function typeName(value: string) { return ({ SUPPLIER_QUOTE: '供应商报价单', WEIGH_TICKET: '磅单', SIGNED_RECEIPT: '签收单', PACKAGE_LABEL: '收发货包装标签' } as Record<string,string>)[value] || value }
function modeName(value: string) { return ({ PDF_TEXT: 'PDF文字', TEXT: '文本', PROVIDED_TEXT: '补充文字', OCR: '本地OCR', FILE_ONLY: '仅原文件' } as Record<string,string>)[value] || value }
function statusName(value: string) { return ({ REVIEW_REQUIRED: '待确认', CONFIRMED: '已确认候选', REJECTED: '已驳回', FAILED: '识别失败' } as Record<string,string>)[value] || value }
</script>

<style scoped>
.document-intake{display:flex;flex-direction:column;gap:14px}.hash{font-family:monospace;font-size:11px;word-break:break-all}.candidate-table{margin:14px 0}.review-result{padding:12px;border-radius:8px;background:var(--el-fill-color-lighter)}
</style>
