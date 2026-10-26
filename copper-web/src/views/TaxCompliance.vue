<template>
  <div class="page" v-loading="loading">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="电子凭证解析不等于验真或验签；本页生成的是企业复核工作底稿，不是税务机关申报表或申报回执。"
    >
      <template #default>XML 按财政部电子凭证会计数据标准解析；PDF、OFD 原件完整留存，须使用税务、财政或银行认可工具核验并上传证据。正式申报前仍须由企业会计或税务专业人员复核。</template>
    </el-alert>

    <div class="page-heading">
      <div>
        <div class="panel-title">电子凭证与票账税工作台</div>
        <div class="muted">统一接收原始凭证、留存验真验签证据、关联业务事实，并按期间生成可追溯的增值税工作底稿</div>
      </div>
      <div class="heading-actions">
        <el-button :loading="loading" @click="loadAll">刷新</el-button>
        <el-button v-if="appStore.hasPermission('tax.compliance.manage')" type="primary" @click="openImport">接收电子凭证</el-button>
      </div>
    </div>

    <div class="kpi-grid compliance-kpis">
      <div v-for="item in kpis" :key="item.label" class="kpi">
        <div class="kpi-label">{{ item.label }}</div>
        <div class="kpi-value" :class="item.danger ? 'danger-text' : ''">{{ item.value }}</div>
        <div class="kpi-note">{{ item.note }}</div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div><div class="panel-title">电子凭证</div><div class="muted">源文件 SHA-256 去重；原件、验证证据、业务关联和审计事件分别留存</div></div>
        <div class="filters">
          <el-select v-model="filters.type" clearable placeholder="全部类型" style="width: 160px" @change="loadCredentials">
            <el-option label="数电发票" value="DIGITAL_INVOICE" />
            <el-option label="银行电子回单" value="BANK_RECEIPT" />
            <el-option label="银行对账单" value="BANK_STATEMENT" />
          </el-select>
          <el-checkbox v-model="filters.exceptionOnly" @change="loadCredentials">只看待处理 / 异常</el-checkbox>
        </div>
      </div>
      <el-table :data="credentials" border size="small" class="data-table section-table" empty-text="暂无电子凭证">
        <el-table-column label="凭证" min-width="210">
          <template #default="{ row }"><RouterLink class="business-link" :to="`/tax-compliance/credentials/${row.id}`"><strong>{{ row.credentialNo }}</strong></RouterLink><div class="multiline-secondary" :title="row.originalFileName">{{ credentialTypeName(row.credentialType) }} · {{ row.originalFileName }}</div></template>
        </el-table-column>
        <el-table-column label="外部编号 / 对方" min-width="180"><template #default="{ row }">{{ row.externalNo || '-' }}<div class="multiline-secondary">{{ row.counterpartyName || row.accountingEntityName || '-' }}</div></template></el-table-column>
        <el-table-column label="标准与格式" width="160"><template #default="{ row }">{{ row.standardCode || '待识别' }}<div class="multiline-secondary">{{ row.fileFormat }} · {{ fileSize(row.fileSize) }}</div></template></el-table-column>
        <el-table-column label="金额" width="130" align="right"><template #default="{ row }"><strong>{{ money(row.totalAmount) }}</strong><div v-if="row.taxAmount" class="multiline-secondary">税额 {{ money(row.taxAmount) }}</div></template></el-table-column>
        <el-table-column label="解析" width="120"><template #default="{ row }"><el-tag size="small" :type="parseTag(row.parseStatus)">{{ parseName(row.parseStatus) }}</el-tag></template></el-table-column>
        <el-table-column label="验证" width="125"><template #default="{ row }"><el-tag size="small" :type="verifyTag(row.verificationStatus)">{{ verificationName(row) }}</el-tag></template></el-table-column>
        <el-table-column label="关联" width="90" align="center"><template #default="{ row }">{{ row.activeRelationCount }}</template></el-table-column>
        <el-table-column label="接收时间" width="165"><template #default="{ row }">{{ dateTime(row.createdAt) }}</template></el-table-column>
        <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="$router.push(`/tax-compliance/credentials/${row.id}`)">查看</el-button></template></el-table-column>
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-header">
        <div><div class="panel-title">增值税申报工作底稿</div><div class="muted">按会计期间、规则版本和数据截止时点冻结；新版本替代旧版本但不覆盖历史</div></div>
        <el-button v-if="appStore.hasPermission('tax.compliance.review')" type="primary" plain @click="openGenerate">生成本期底稿</el-button>
      </div>
      <el-table :data="workpapers" border size="small" class="data-table section-table" empty-text="尚未生成工作底稿">
        <el-table-column label="底稿编号" min-width="195"><template #default="{ row }"><RouterLink class="business-link" :to="`/tax-compliance/workpapers/${row.id}`"><strong>{{ row.workpaperNo }}</strong></RouterLink><div class="multiline-secondary">{{ row.ruleCode }}/v{{ row.ruleVersion }} · 第 {{ row.versionNo }} 版</div></template></el-table-column>
        <el-table-column prop="periodName" label="期间" width="110" />
        <el-table-column label="销项税额" width="125" align="right"><template #default="{ row }">{{ money(row.outputTaxAmount) }}</template></el-table-column>
        <el-table-column label="可抵扣进项" width="135" align="right"><template #default="{ row }">{{ money(row.deductibleInputTax) }}</template></el-table-column>
        <el-table-column label="预计应纳税额" width="140" align="right"><template #default="{ row }"><strong>{{ money(row.estimatedVatPayable) }}</strong></template></el-table-column>
        <el-table-column label="票账差异" width="130" align="right"><template #default="{ row }"><span :class="difference(row) ? 'danger-text' : ''">{{ money(Number(row.outputLedgerDifference) + Number(row.inputLedgerDifference)) }}</span></template></el-table-column>
        <el-table-column label="阻断 / 提醒" width="120"><template #default="{ row }"><el-tag size="small" :type="row.blockerCount ? 'danger' : 'success'">{{ row.blockerCount }} / {{ row.warningCount }}</el-tag></template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag size="small" :type="workpaperTag(row.status)">{{ workpaperStatusName(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="生成时间" width="165"><template #default="{ row }">{{ dateTime(row.generatedAt) }}</template></el-table-column>
        <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="$router.push(`/tax-compliance/workpapers/${row.id}`)">查看</el-button></template></el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="importVisible" title="接收电子凭证原件" width="560px" destroy-on-close>
      <el-alert class="dialog-alert" type="info" :closable="false" show-icon title="建议优先上传财政部标准 XML；PDF/OFD 将完整留存并等待外部工具核验。" />
      <el-form label-width="110px">
        <el-form-item label="凭证类型"><el-select v-model="importForm.declaredType" style="width: 100%"><el-option label="自动识别（仅 XML）" value="AUTO" /><el-option label="数电发票" value="DIGITAL_INVOICE" /><el-option label="银行电子回单" value="BANK_RECEIPT" /><el-option label="银行对账单" value="BANK_STATEMENT" /></el-select></el-form-item>
        <el-form-item label="原始文件">
          <el-upload drag :auto-upload="false" :limit="1" accept=".xml,.pdf,.ofd" :on-change="selectImportFile" :on-remove="removeImportFile">
            <el-icon class="upload-icon"><UploadFilled /></el-icon><div>拖入或点击选择 XML、PDF、OFD</div><template #tip><div class="el-upload__tip">单个原件最大 30 MB；同一 SHA-256 不重复接收</div></template>
          </el-upload>
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="importVisible = false">取消</el-button><el-button type="primary" :loading="acting" @click="submitImport">接收并解析</el-button></template>
    </el-dialog>

    <el-dialog v-model="generateVisible" title="生成增值税申报工作底稿" width="520px">
      <el-alert class="dialog-alert" type="warning" :closable="false" show-icon title="生成新版本会将该期间原草稿或已复核版本标记为“已被替代”，历史不会删除。" />
      <el-form label-width="110px"><el-form-item label="税款所属期"><el-date-picker v-model="generatePeriod" type="month" value-format="YYYY-MM" placeholder="选择月份" style="width: 100%" /></el-form-item></el-form>
      <template #footer><el-button @click="generateVisible = false">取消</el-button><el-button type="primary" :loading="acting" @click="submitGenerate">生成并检查</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { UploadFile } from 'element-plus'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { api, type ElectronicCredentialRow, type ElectronicCredentialType, type TaxComplianceDashboard, type TaxWorkpaperRow } from '@/api/client'
import { money } from '@/api/format'
import { useAppStore } from '@/stores/app'

const router = useRouter()
const appStore = useAppStore()
const loading = ref(false)
const acting = ref(false)
const dashboard = ref<TaxComplianceDashboard | null>(null)
const credentials = ref<ElectronicCredentialRow[]>([])
const workpapers = ref<TaxWorkpaperRow[]>([])
const filters = reactive<{ type: ElectronicCredentialType | ''; exceptionOnly: boolean }>({ type: '', exceptionOnly: false })
const importVisible = ref(false)
const importForm = reactive<{ declaredType: ElectronicCredentialType | 'AUTO'; file: File | null }>({ declaredType: 'AUTO', file: null })
const generateVisible = ref(false)
const generatePeriod = ref(currentPeriod())

const kpis = computed(() => [
  { label: '已接收电子凭证', value: dashboard.value?.credentialCount ?? 0, note: '原始文件按摘要去重并完整留存' },
  { label: '待验真 / 验签', value: dashboard.value?.pendingVerificationCount ?? 0, note: '必须补外部验证证据', danger: Boolean(dashboard.value?.pendingVerificationCount) },
  { label: '解析待处理', value: dashboard.value?.parseExceptionCount ?? 0, note: '含待外部工具解析及解析失败', danger: Boolean(dashboard.value?.parseExceptionCount) },
  { label: '尚未关联业务', value: dashboard.value?.unlinkedCount ?? 0, note: '未形成凭证到单据的双向追溯', danger: Boolean(dashboard.value?.unlinkedCount) },
  { label: '验证异常', value: dashboard.value?.failedVerificationCount ?? 0, note: '不得作为正常入账依据', danger: Boolean(dashboard.value?.failedVerificationCount) },
  { label: '最新底稿', value: dashboard.value?.latestWorkpaper?.periodName || '-', note: dashboard.value?.latestWorkpaper ? `${workpaperStatusName(dashboard.value.latestWorkpaper.status)} · 阻断 ${dashboard.value.latestWorkpaper.blockerCount}` : '尚未生成' },
])

onMounted(loadAll)
async function loadAll() { loading.value = true; try { await Promise.all([loadDashboard(), loadCredentials(), loadWorkpapers()]) } finally { loading.value = false } }
async function loadDashboard() { dashboard.value = await api.taxComplianceDashboard() }
async function loadCredentials() { credentials.value = await api.electronicCredentials({ type: filters.type || undefined, exceptionOnly: filters.exceptionOnly }) }
async function loadWorkpapers() { workpapers.value = await api.taxWorkpapers() }
function openImport() { importForm.declaredType = 'AUTO'; importForm.file = null; importVisible.value = true }
function selectImportFile(file: UploadFile) { importForm.file = file.raw || null }
function removeImportFile() { importForm.file = null }
async function submitImport() { if (!importForm.file) return ElMessage.warning('请选择原始凭证文件'); if (importForm.file.size > 30 * 1024 * 1024) return ElMessage.warning('原始文件不能超过 30 MB'); acting.value = true; try { const result = await api.importElectronicCredential(importForm.file, importForm.declaredType); ElMessage.success('电子凭证已接收，原件摘要和解析结果已留存'); importVisible.value = false; await router.push(`/tax-compliance/credentials/${result.id}`) } finally { acting.value = false } }
function openGenerate() { generatePeriod.value = currentPeriod(); generateVisible.value = true }
async function submitGenerate() { if (!generatePeriod.value) return ElMessage.warning('请选择税款所属期'); acting.value = true; try { const result = await api.generateTaxWorkpaper(generatePeriod.value); ElMessage.success(result.workpaper.blockerCount ? `底稿已生成，发现 ${result.workpaper.blockerCount} 项阻断` : '底稿已生成，票账差异检查通过'); generateVisible.value = false; await router.push(`/tax-compliance/workpapers/${result.workpaper.id}`) } finally { acting.value = false } }
function credentialTypeName(value: string) { return ({ DIGITAL_INVOICE: '数电发票', BANK_RECEIPT: '银行电子回单', BANK_STATEMENT: '银行对账单' } as Record<string, string>)[value] || value }
function parseName(value: string) { return ({ PARSED: '已结构化', PENDING_EXTERNAL: '待外部解析', FAILED: '解析失败' } as Record<string, string>)[value] || value }
function parseTag(value: string) { return value === 'PARSED' ? 'success' : value === 'FAILED' ? 'danger' : 'warning' }
function verificationName(row: ElectronicCredentialRow) { const value = row.credentialType === 'DIGITAL_INVOICE' ? row.verificationStatus : row.signatureStatus; return ({ UNVERIFIED: '待验证', VERIFIED: '已验证', FAILED: '验证失败', NOT_APPLICABLE: '不适用' } as Record<string, string>)[value] || value }
function verifyTag(value: string) { return value === 'VERIFIED' ? 'success' : value === 'FAILED' ? 'danger' : 'warning' }
function workpaperStatusName(value: string) { return ({ DRAFT: '待复核', REVIEWED: '已复核', SUPERSEDED: '已被替代' } as Record<string, string>)[value] || value }
function workpaperTag(value: string) { return value === 'REVIEWED' ? 'success' : value === 'SUPERSEDED' ? 'info' : 'warning' }
function difference(row: TaxWorkpaperRow) { return Number(row.outputLedgerDifference) !== 0 || Number(row.inputLedgerDifference) !== 0 }
function fileSize(value: number) { if (value < 1024) return `${value} B`; if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`; return `${(value / 1024 / 1024).toFixed(1)} MB` }
function dateTime(value: string) { return value ? value.replace('T', ' ').slice(0, 19) : '-' }
function currentPeriod() { const now = new Date(); return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}` }
</script>

<style scoped>
.page-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin: 16px 0; }
.page-heading .muted { margin-top: 5px; }
.heading-actions, .filters { display: flex; align-items: center; gap: 10px; }
.compliance-kpis { grid-template-columns: repeat(6, minmax(0, 1fr)); }
.danger-text { color: var(--el-color-danger); }
.section-table { margin-top: 14px; }
.dialog-alert { margin-bottom: 18px; }
.upload-icon { margin-bottom: 8px; font-size: 34px; color: #c85d2a; }
@media (max-width: 1280px) { .compliance-kpis { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 800px) { .page-heading, .panel-header { align-items: stretch; flex-direction: column; } .compliance-kpis { grid-template-columns: 1fr 1fr; } .filters { flex-wrap: wrap; } }
</style>
