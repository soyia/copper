<template>
  <div class="page" v-loading="loading">
    <template v-if="detail">
      <PageHeader :title="`${credentialTypeName(item.credentialType)} ${item.credentialNo}`" back-to="/tax-compliance">
        <el-button @click="downloadOriginal">查看 / 下载原件</el-button>
        <el-button v-if="canManage && item.status === 'ACTIVE'" type="primary" @click="openVerify">登记{{ item.credentialType === 'DIGITAL_INVOICE' ? '验真' : '验签' }}</el-button>
        <el-button v-if="canManage && item.status === 'ACTIVE'" @click="openLink">关联业务单据</el-button>
        <el-button v-if="canManage && item.status === 'ACTIVE'" type="danger" plain @click="voidCredential">作废</el-button>
      </PageHeader>

      <el-alert v-if="item.status === 'VOIDED'" type="info" :closable="false" show-icon title="该电子凭证已作废；原件、验证记录、历史关联和审计事件继续保留。" />
      <el-alert v-else-if="item.parseStatus !== 'PARSED'" type="warning" :closable="false" show-icon :title="item.parseMessage || '该格式需使用外部工具解析并人工复核'" />
      <el-alert v-else-if="currentVerification !== 'VERIFIED'" type="warning" :closable="false" show-icon title="结构化解析已经完成，但尚未取得有效的外部验真/验签证据，不能把解析结果当作验证结论。" />

      <div class="panel detail-panel">
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="凭证编号">{{ item.credentialNo }}</el-descriptions-item>
          <el-descriptions-item label="类型">{{ credentialTypeName(item.credentialType) }}</el-descriptions-item>
          <el-descriptions-item label="格式 / 标准">{{ item.fileFormat }} · {{ item.standardCode || '待识别' }} {{ item.standardVersion }}</el-descriptions-item>
          <el-descriptions-item label="状态"><el-tag size="small" :type="item.status === 'ACTIVE' ? 'success' : 'info'">{{ item.status === 'ACTIVE' ? '有效' : '已作废' }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="原始文件" :span="2">{{ item.originalFileName }} · {{ fileSize(item.fileSize) }}</el-descriptions-item>
          <el-descriptions-item label="解析"><el-tag size="small" :type="parseTag(item.parseStatus)">{{ parseName(item.parseStatus) }}</el-tag></el-descriptions-item>
          <el-descriptions-item :label="item.credentialType === 'DIGITAL_INVOICE' ? '验真' : '验签'"><el-tag size="small" :type="verifyTag(currentVerification)">{{ verifyName(currentVerification) }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="外部编号">{{ item.externalNo || '-' }}</el-descriptions-item>
          <el-descriptions-item label="开具 / 交易日期">{{ item.issueDate || '-' }}</el-descriptions-item>
          <el-descriptions-item label="凭证期间">{{ periodText }}</el-descriptions-item>
          <el-descriptions-item label="币种">{{ item.currency || 'CNY' }}</el-descriptions-item>
          <el-descriptions-item label="会计主体" :span="2">{{ item.accountingEntityName || '-' }}<div class="multiline-secondary">{{ item.accountingEntityCode || '-' }}</div></el-descriptions-item>
          <el-descriptions-item label="交易对方" :span="2">{{ item.counterpartyName || '-' }}<div class="multiline-secondary">{{ item.counterpartyCode || '-' }}</div></el-descriptions-item>
          <el-descriptions-item label="银行账号" :span="2">{{ item.bankAccountNo || '-' }}</el-descriptions-item>
          <el-descriptions-item label="明细数">{{ item.detailCount }}</el-descriptions-item>
          <el-descriptions-item label="业务关联">{{ item.activeRelationCount }}</el-descriptions-item>
          <el-descriptions-item label="未税金额" align="right">{{ money(item.amountExcludingTax) }}</el-descriptions-item>
          <el-descriptions-item label="税额" align="right">{{ money(item.taxAmount) }}</el-descriptions-item>
          <el-descriptions-item label="价税 / 交易金额" align="right"><strong>{{ money(item.totalAmount) }}</strong></el-descriptions-item>
          <el-descriptions-item label="接收人 / 时间">{{ item.createdByName }} · {{ dateTime(item.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="源文件 SHA-256" :span="4"><span class="hash">{{ item.sourceFileSha256 }}</span></el-descriptions-item>
          <el-descriptions-item label="解析说明" :span="4">{{ item.parseMessage || '-' }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <div class="panel">
        <div class="panel-header"><div><div class="panel-title">验真 / 验签记录</div><div class="muted">验证结论必须有来源、参考号、时间、结果快照和原始证明文件；失败记录不会被后续成功覆盖</div></div></div>
        <el-table :data="detail.verifications" border size="small" class="data-table section-table" empty-text="尚未登记验证证据">
          <el-table-column label="验证" width="105"><template #default="{ row }">{{ row.verificationKind === 'AUTHENTICITY' ? '验真' : '验签' }}</template></el-table-column>
          <el-table-column label="结果" width="105"><template #default="{ row }"><el-tag size="small" :type="row.verificationResult === 'VERIFIED' ? 'success' : 'danger'">{{ row.verificationResult === 'VERIFIED' ? '通过' : '失败' }}</el-tag></template></el-table-column>
          <el-table-column label="来源 / 参考号" min-width="230"><template #default="{ row }">{{ sourceName(row.verificationSource) }}<div class="multiline-secondary">{{ row.verificationReference }}</div></template></el-table-column>
          <el-table-column prop="resultSnapshot" label="结果快照" min-width="230" show-overflow-tooltip />
          <el-table-column label="验证时间" width="165"><template #default="{ row }">{{ dateTime(row.checkedAt) }}</template></el-table-column>
          <el-table-column label="证明文件" min-width="190"><template #default="{ row }"><el-button link type="primary" @click="downloadProof(row.id, row.proofFileName)">{{ row.proofFileName }}</el-button><div class="multiline-secondary hash">{{ shortHash(row.proofSha256) }}</div></template></el-table-column>
          <el-table-column label="登记人" width="120"><template #default="{ row }">{{ row.operatorName }}</template></el-table-column>
        </el-table>
      </div>

      <div class="panel">
        <div class="panel-header"><div><div class="panel-title">业务关联</div><div class="muted">关联只建立追溯关系，不会改写已确认订单、库存、资金、发票或会计事实；解除时必须留原因</div></div><el-button v-if="canManage && item.status === 'ACTIVE'" type="primary" plain @click="openLink">新增关联</el-button></div>
        <el-table :data="detail.relations" border size="small" class="data-table section-table" empty-text="尚未关联业务单据">
          <el-table-column label="关系" width="105"><template #default="{ row }">{{ relationTypeName(row.relationType) }}</template></el-table-column>
          <el-table-column label="业务单据" min-width="210"><template #default="{ row }"><RouterLink v-if="relationPath(row.documentType, row.documentId)" class="business-link" :to="relationPath(row.documentType, row.documentId)!">{{ documentTypeName(row.documentType) }} {{ row.documentNo }}</RouterLink><span v-else>{{ documentTypeName(row.documentType) }} {{ row.documentNo }}</span><div class="multiline-secondary">ID {{ row.documentId }}</div></template></el-table-column>
          <el-table-column prop="note" label="关联说明" min-width="240" show-overflow-tooltip />
          <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status === 'ACTIVE' ? '有效' : '已解除' }}</el-tag></template></el-table-column>
          <el-table-column label="操作记录" min-width="210"><template #default="{ row }">{{ row.createdByName }} · {{ dateTime(row.createdAt) }}<div v-if="row.status === 'REVERSED'" class="multiline-secondary">{{ row.reversedByName }}：{{ row.reversedReason }}</div></template></el-table-column>
          <el-table-column v-if="canManage" label="操作" width="90" fixed="right"><template #default="{ row }"><el-button v-if="row.status === 'ACTIVE'" link type="danger" @click="reverseRelation(row.id)">解除</el-button></template></el-table-column>
        </el-table>
      </div>

      <div class="panel">
        <div class="panel-title">审计事件</div>
        <el-timeline class="event-timeline"><el-timeline-item v-for="event in detail.events" :key="event.id" :timestamp="`${event.operatorName} · ${dateTime(event.createdAt)}`" placement="top"><strong>{{ eventName(event.action) }}</strong><div class="muted">{{ event.fromStatus }}<span v-if="event.fromStatus || event.toStatus"> → </span>{{ event.toStatus }}</div><div>{{ event.summary }}</div></el-timeline-item></el-timeline>
      </div>
    </template>

    <template v-if="detail">
    <el-dialog v-model="verifyVisible" :title="`登记${item.credentialType === 'DIGITAL_INVOICE' ? '验真' : '验签'}证据`" width="620px" destroy-on-close>
      <el-alert class="dialog-alert" type="warning" :closable="false" show-icon title="系统只登记外部工具或平台给出的验证事实，不会自行伪造验真/验签结论。" />
      <el-form label-width="120px">
        <el-form-item label="验证来源"><el-select v-model="verifyForm.source" style="width: 100%"><el-option v-for="source in verificationSources" :key="source.value" :label="source.label" :value="source.value" /></el-select></el-form-item>
        <el-form-item label="验证结果"><el-radio-group v-model="verifyForm.result"><el-radio value="VERIFIED">验证通过</el-radio><el-radio value="FAILED">验证失败</el-radio></el-radio-group></el-form-item>
        <el-form-item label="参考号"><el-input v-model="verifyForm.reference" maxlength="120" placeholder="平台流水、查验编号或工具报告编号" /></el-form-item>
        <el-form-item label="实际验证时间"><el-date-picker v-model="verifyForm.checkedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width: 100%" /></el-form-item>
        <el-form-item label="结果快照"><el-input v-model="verifyForm.snapshot" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="记录平台显示的票面状态、签名结果或异常信息" /></el-form-item>
        <el-form-item label="证明文件"><el-upload :auto-upload="false" :limit="1" accept=".pdf,.png,.jpg,.jpeg,.xml,.ofd" :on-change="selectProof" :on-remove="removeProof"><el-button>选择外部验证证明</el-button><template #tip><div class="el-upload__tip">必须上传可复核的查验截图、报告或平台导出文件，最大 10 MB</div></template></el-upload></el-form-item>
      </el-form>
      <template #footer><el-button @click="verifyVisible = false">取消</el-button><el-button type="primary" :loading="acting" @click="submitVerification">保存验证事实</el-button></template>
    </el-dialog>

    <el-dialog v-model="linkVisible" title="关联业务单据" width="820px" destroy-on-close>
      <el-alert class="dialog-alert" type="info" :closable="false" show-icon title="优先选择系统按编号、金额、日期和往来单位给出的候选；人工关联必须核对单据类型与业务主键。" />
      <el-table :data="candidates" border size="small" max-height="280" empty-text="未找到自动候选，可在下方人工填写">
        <el-table-column label="候选单据" min-width="180"><template #default="{ row }">{{ documentTypeName(row.documentType) }} {{ row.documentNo }}</template></el-table-column>
        <el-table-column prop="businessDate" label="业务日期" width="115" />
        <el-table-column label="金额" width="125" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
        <el-table-column label="匹配" min-width="210"><template #default="{ row }"><el-tag size="small" :type="row.matchLevel === 'EXACT' ? 'success' : row.matchLevel === 'POSSIBLE' ? 'warning' : 'info'">{{ matchName(row.matchLevel) }}</el-tag><div class="multiline-secondary">{{ row.matchReason }}</div></template></el-table-column>
        <el-table-column label="操作" width="85"><template #default="{ row }"><el-button link type="primary" @click="useCandidate(row)">选择</el-button></template></el-table-column>
      </el-table>
      <el-form class="manual-link-form" label-width="110px">
        <el-form-item label="关系类型"><el-select v-model="linkForm.relationType" style="width: 100%"><el-option label="原始来源" value="SOURCE" /><el-option label="入账关联" value="BOOKING" /><el-option label="对账关联" value="RECONCILIATION" /></el-select></el-form-item>
        <el-form-item label="单据类型"><el-select v-model="linkForm.documentType" filterable allow-create style="width: 100%"><el-option v-for="type in allowedDocumentTypes" :key="type" :label="documentTypeName(type)" :value="type" /></el-select></el-form-item>
        <el-form-item label="业务主键 ID"><el-input-number v-model="linkForm.documentId" :min="1" :precision="0" style="width: 100%" :controls="false" /></el-form-item>
        <el-form-item label="关联说明"><el-input v-model="linkForm.note" type="textarea" :rows="2" maxlength="300" show-word-limit /></el-form-item>
      </el-form>
      <template #footer><el-button @click="linkVisible = false">取消</el-button><el-button type="primary" :loading="acting" @click="submitLink">保存关联</el-button></template>
    </el-dialog>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { UploadFile } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute } from 'vue-router'
import { api, type ElectronicCredentialDetail, type ElectronicCredentialLinkCandidate } from '@/api/client'
import { money } from '@/api/format'
import PageHeader from '@/components/PageHeader.vue'
import { useAppStore } from '@/stores/app'

const route = useRoute()
const appStore = useAppStore()
const id = Number(route.params.id)
const loading = ref(false)
const acting = ref(false)
const detail = ref<ElectronicCredentialDetail | null>(null)
const item = computed(() => detail.value!.credential)
const canManage = computed(() => appStore.hasPermission('tax.compliance.manage'))
const currentVerification = computed(() => item.value?.credentialType === 'DIGITAL_INVOICE' ? item.value.verificationStatus : item.value?.signatureStatus || 'UNVERIFIED')
const periodText = computed(() => item.value?.periodFrom ? `${item.value.periodFrom}${item.value.periodTo && item.value.periodTo !== item.value.periodFrom ? ` 至 ${item.value.periodTo}` : ''}` : '-')
const verifyVisible = ref(false)
const verifyForm = reactive({ source: 'TAX_DIGITAL_ACCOUNT', result: 'VERIFIED', reference: '', checkedAt: localDateTime(), snapshot: '', proof: null as File | null })
const linkVisible = ref(false)
const candidates = ref<ElectronicCredentialLinkCandidate[]>([])
const linkForm = reactive({ relationType: 'SOURCE', documentType: '', documentId: 1, note: '' })
const allowedDocumentTypes = computed(() => item.value?.credentialType === 'DIGITAL_INVOICE' ? ['TAX_INVOICE', 'ACCOUNTING_VOUCHER'] : item.value?.credentialType === 'BANK_STATEMENT' ? ['BANK_RECONCILIATION', 'ACCOUNTING_VOUCHER'] : ['PAYMENT', 'SUPPLIER_PAYMENT', 'TREASURY_DOCUMENT', 'ACCOUNTING_VOUCHER'])
const verificationSources = computed(() => item.value?.credentialType === 'DIGITAL_INVOICE' ? [{ value: 'TAX_DIGITAL_ACCOUNT', label: '税务数字账户' }, { value: 'NATIONAL_PLATFORM', label: '全国增值税发票查验平台' }, { value: 'MOF_TOOLKIT', label: '财政部电子凭证工具包' }, { value: 'MANUAL_REVIEW', label: '人工专业复核' }] : [{ value: 'BANK_VALIDATION_TOOL', label: '开户银行验证工具 / 网银' }, { value: 'MOF_TOOLKIT', label: '财政部电子凭证工具包' }, { value: 'MANUAL_REVIEW', label: '人工专业复核' }])

onMounted(load)
async function load() { loading.value = true; try { detail.value = await api.electronicCredential(id) } finally { loading.value = false } }
async function downloadOriginal() { const blob = await api.downloadElectronicCredential(id); saveBlob(blob, item.value.originalFileName) }
async function downloadProof(verificationId: number, name: string) { const blob = await api.electronicCredentialVerificationProof(id, verificationId); saveBlob(blob, name) }
function openVerify() { Object.assign(verifyForm, { source: item.value.credentialType === 'DIGITAL_INVOICE' ? 'TAX_DIGITAL_ACCOUNT' : 'BANK_VALIDATION_TOOL', result: 'VERIFIED', reference: '', checkedAt: localDateTime(), snapshot: '', proof: null }); verifyVisible.value = true }
function selectProof(file: UploadFile) { verifyForm.proof = file.raw || null }
function removeProof() { verifyForm.proof = null }
async function submitVerification() { if (!verifyForm.reference.trim() || !verifyForm.checkedAt || !verifyForm.proof) return ElMessage.warning('请完整填写参考号、验证时间并上传证明文件'); if (verifyForm.proof.size > 10 * 1024 * 1024) return ElMessage.warning('证明文件不能超过 10 MB'); acting.value = true; try { await api.verifyElectronicCredential(id, { verificationKind: item.value.credentialType === 'DIGITAL_INVOICE' ? 'AUTHENTICITY' : 'SIGNATURE', verificationSource: verifyForm.source as never, verificationResult: verifyForm.result as 'VERIFIED' | 'FAILED', verificationReference: verifyForm.reference.trim(), checkedAt: verifyForm.checkedAt, resultSnapshot: verifyForm.snapshot.trim(), proof: verifyForm.proof }); ElMessage.success('验证事实与证明文件已保存'); verifyVisible.value = false; await load() } finally { acting.value = false } }
async function openLink() { candidates.value = await api.electronicCredentialLinkCandidates(id); Object.assign(linkForm, { relationType: 'SOURCE', documentType: allowedDocumentTypes.value[0] || '', documentId: 1, note: '' }); linkVisible.value = true }
function useCandidate(row: ElectronicCredentialLinkCandidate) { Object.assign(linkForm, { relationType: row.relationType, documentType: row.documentType, documentId: row.documentId, note: `按系统候选关联：${row.matchReason}` }) }
async function submitLink() { if (!linkForm.documentType || !linkForm.documentId || !linkForm.note.trim()) return ElMessage.warning('请填写业务单据和关联说明'); acting.value = true; try { await api.linkElectronicCredential(id, linkForm); ElMessage.success('业务关联已建立，未改写原业务事实'); linkVisible.value = false; await load() } finally { acting.value = false } }
async function reverseRelation(relationId: number) { const { value } = await ElMessageBox.prompt('解除关联不会删除历史或原业务事实。请填写解除原因', '解除业务关联', { inputValidator: text => Boolean(String(text).trim()) || '必须填写原因' }); await api.reverseElectronicCredentialRelation(id, relationId, value); ElMessage.success('关联已解除，审计记录已保留'); await load() }
async function voidCredential() { const { value } = await ElMessageBox.prompt('作废后不能新增验证或关联，但原件和历史不会删除。请填写原因', '作废电子凭证', { type: 'warning', inputValidator: text => Boolean(String(text).trim()) || '必须填写原因' }); await api.voidElectronicCredential(id, value); ElMessage.success('电子凭证已作废'); await load() }
function credentialTypeName(value: string) { return ({ DIGITAL_INVOICE: '数电发票', BANK_RECEIPT: '银行电子回单', BANK_STATEMENT: '银行对账单' } as Record<string, string>)[value] || value }
function parseName(value: string) { return ({ PARSED: '已结构化', PENDING_EXTERNAL: '待外部解析', FAILED: '解析失败' } as Record<string, string>)[value] || value }
function parseTag(value: string) { return value === 'PARSED' ? 'success' : value === 'FAILED' ? 'danger' : 'warning' }
function verifyName(value: string) { return ({ UNVERIFIED: '待验证', VERIFIED: '验证通过', FAILED: '验证失败', NOT_APPLICABLE: '不适用' } as Record<string, string>)[value] || value }
function verifyTag(value: string) { return value === 'VERIFIED' ? 'success' : value === 'FAILED' ? 'danger' : value === 'NOT_APPLICABLE' ? 'info' : 'warning' }
function sourceName(value: string) { return ({ MOF_TOOLKIT: '财政部电子凭证工具包', TAX_DIGITAL_ACCOUNT: '税务数字账户', NATIONAL_PLATFORM: '全国增值税发票查验平台', BANK_VALIDATION_TOOL: '银行验证工具 / 网银', MANUAL_REVIEW: '人工专业复核' } as Record<string, string>)[value] || value }
function relationTypeName(value: string) { return ({ SOURCE: '原始来源', BOOKING: '入账关联', RECONCILIATION: '对账关联' } as Record<string, string>)[value] || value }
function documentTypeName(value: string) { return ({ TAX_INVOICE: '税务发票', ACCOUNTING_VOUCHER: '会计凭证', BANK_RECONCILIATION: '银行对账批次', PAYMENT: '客户收款', SUPPLIER_PAYMENT: '供应商付款', TREASURY_DOCUMENT: '出纳业务单据' } as Record<string, string>)[value] || value }
function relationPath(type: string, documentId: number) { return ({ TAX_INVOICE: `/tax-invoices/${documentId}`, ACCOUNTING_VOUCHER: `/accounting/vouchers/${documentId}`, TREASURY_DOCUMENT: `/treasury/documents/${documentId}`, BANK_RECONCILIATION: '/accounting/bank-reconciliation', PAYMENT: '/payments', SUPPLIER_PAYMENT: '/supplier-payments' } as Record<string, string>)[type] || '' }
function matchName(value: string) { return ({ EXACT: '精确匹配', POSSIBLE: '可能匹配', REVIEW: '需人工核对' } as Record<string, string>)[value] || value }
function eventName(value: string) { return ({ RECEIVE: '接收原件', VERIFY: '登记验证', RELATION_ADD: '建立关联', RELATION_REVERSE: '解除关联', VOID: '作废凭证' } as Record<string, string>)[value] || value }
function fileSize(value: number) { if (value < 1024) return `${value} B`; if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`; return `${(value / 1024 / 1024).toFixed(1)} MB` }
function dateTime(value: string) { return value ? value.replace('T', ' ').slice(0, 19) : '-' }
function shortHash(value: string) { return value ? `${value.slice(0, 12)}…${value.slice(-8)}` : '-' }
function localDateTime() { const now = new Date(); const pad = (value: number) => String(value).padStart(2, '0'); return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}T${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}` }
function saveBlob(blob: Blob, name: string) { const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = name || '电子凭证'; link.click(); URL.revokeObjectURL(url) }
</script>

<style scoped>
.detail-panel { margin-top: 14px; padding: 16px; }
.section-table { margin-top: 14px; }
.hash { overflow-wrap: anywhere; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.event-timeline { margin-top: 20px; }
.dialog-alert { margin-bottom: 18px; }
.manual-link-form { display: grid; grid-template-columns: 1fr 1fr; column-gap: 16px; margin-top: 18px; }
.manual-link-form .el-form-item:last-child { grid-column: 1 / -1; }
@media (max-width: 800px) { .manual-link-form { display: block; } }
</style>
