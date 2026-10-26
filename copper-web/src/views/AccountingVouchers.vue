<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">凭证中心</div>
          <div class="muted accounting-subtitle">业务过账只生成凭证草稿；审核、记账和冲销均保留独立审计记录。</div>
        </div>
        <div class="header-actions">
          <el-button v-if="appStore.hasPermission('accounting.manage')" @click="$router.push('/accounting/vouchers/new')">新增手工凭证</el-button>
          <el-button v-if="appStore.hasPermission('accounting.manage')" type="primary" :loading="generating" @click="generateMissing">补生成业务凭证</el-button>
        </div>
      </div>

      <el-tabs v-model="activeTab">
        <el-tab-pane label="会计凭证" name="vouchers">
          <div class="list-tools">
            <el-input v-model="keyword" clearable placeholder="凭证号 / 摘要 / 来源单号 / 制单人" />
            <el-select v-model="status" clearable placeholder="全部状态" style="width: 150px">
              <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" />
            <el-button :loading="voucherQuery.isFetching.value" @click="refresh">刷新</el-button>
          </div>
          <el-table v-loading="voucherQuery.isLoading.value" :data="filteredVouchers" border size="small" class="data-table" max-height="calc(100vh - 310px)">
            <el-table-column label="凭证号" min-width="210" fixed="left"><template #default="{ row }"><RouterLink class="business-link" :to="`/accounting/vouchers/${row.id}`">{{ row.voucherNo }}</RouterLink><div class="multiline-secondary">{{ voucherTypeName(row.voucherType) }}</div></template></el-table-column>
            <el-table-column prop="voucherDate" label="凭证日期" width="115" />
            <el-table-column prop="summary" label="摘要" min-width="230" show-overflow-tooltip />
            <el-table-column label="来源" min-width="180"><template #default="{ row }"><span>{{ row.sourceNo || '手工凭证' }}</span><div v-if="row.sourceType" class="multiline-secondary">{{ sourceTypeName(row.sourceType) }}</div></template></el-table-column>
            <el-table-column label="状态" width="105"><template #default="{ row }"><el-tag size="small" :type="voucherStatusType(row.status)">{{ voucherStatusName(row.status) }}</el-tag></template></el-table-column>
            <el-table-column label="借方合计" width="130" align="right"><template #default="{ row }">{{ money(row.debitTotal) }}</template></el-table-column>
            <el-table-column label="贷方合计" width="130" align="right"><template #default="{ row }">{{ money(row.creditTotal) }}</template></el-table-column>
            <el-table-column label="经办" min-width="150"><template #default="{ row }"><div>制单：{{ row.makerName || '系统' }}</div><div class="multiline-secondary">审核：{{ row.reviewerName || '-' }} · 记账：{{ row.posterName || '-' }}</div></template></el-table-column>
            <el-table-column label="操作" width="230" fixed="right"><template #default="{ row }">
              <el-button link type="primary" @click="$router.push(`/accounting/vouchers/${row.id}`)">详情</el-button>
              <el-button v-if="row.voucherType === 'MANUAL' && row.status === 'DRAFT' && appStore.hasPermission('accounting.manage')" link @click="$router.push(`/accounting/vouchers/${row.id}/edit`)">编辑</el-button>
              <el-button v-if="row.status === 'DRAFT' && appStore.hasPermission('accounting.review')" link type="success" :loading="loadingActionId === row.id" @click="review(row.id)">审核</el-button>
              <el-button v-if="row.status === 'REVIEWED' && appStore.hasPermission('accounting.review')" link type="warning" :loading="loadingActionId === row.id" @click="reject(row.id)">退回</el-button>
              <el-button v-if="row.status === 'REVIEWED' && appStore.hasPermission('accounting.post')" link type="success" :loading="loadingActionId === row.id" @click="post(row.id)">记账</el-button>
              <el-button v-if="['DRAFT','REVIEWED'].includes(row.status) && appStore.hasPermission('accounting.manage')" link type="danger" :loading="loadingActionId === row.id" @click="cancel(row.id)">作废</el-button>
              <el-button v-if="row.status === 'POSTED' && appStore.hasPermission('accounting.post')" link type="danger" :loading="loadingActionId === row.id" @click="reverse(row.id)">冲销</el-button>
            </template></el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`制证异常${issues.length ? ` (${issues.length})` : ''}`" name="issues">
          <el-alert v-if="!issues.length" type="success" :closable="false" show-icon title="当前没有待处理的自动制证异常" />
          <el-table v-else v-loading="issueQuery.isLoading.value" :data="issues" border size="small" class="data-table">
            <el-table-column label="业务过账" min-width="210"><template #default="{ row }"><RouterLink class="business-link" :to="`/business-postings/${row.businessPostingBatchId}`">{{ row.postingNo }}</RouterLink><div class="multiline-secondary">{{ row.sourceNo }}</div></template></el-table-column>
            <el-table-column prop="issueMessage" label="异常说明" min-width="320" />
            <el-table-column prop="issueCode" label="异常代码" width="180" />
            <el-table-column prop="retryCount" label="尝试次数" width="100" align="right" />
            <el-table-column label="最近尝试" width="170"><template #default="{ row }">{{ formatDateTime(row.lastAttemptAt) }}</template></el-table-column>
            <el-table-column label="操作" width="90"><template #default="{ row }"><el-button v-if="appStore.hasPermission('accounting.manage')" link type="primary" :loading="loadingIssueId === row.id" @click="retryIssue(row.id)">重试</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="电子凭证归档" name="archives">
          <el-alert type="warning" :closable="false" show-icon title="“正式归档”只表示会计期间已经结账，不等同于系统自动判定法定合规。企业仍须落实来源查验、审批、防重复入账、防篡改、备份、长期可读和档案移交制度。" />
          <div class="archive-tools">
            <el-select v-model="archivePeriodId" filterable placeholder="选择会计期间" style="width:260px">
              <el-option v-for="period in periods" :key="period.id" :value="period.id" :label="`${period.periodName} · ${period.status === 'CLOSED' ? '已结账' : '开放'}`" />
            </el-select>
            <el-button v-if="appStore.hasPermission('accounting.archive')" :disabled="!archivePeriodId" :loading="archiving" @click="createArchive(false)">生成预归档</el-button>
            <el-button v-if="appStore.hasPermission('accounting.archive')" type="primary" :disabled="selectedPeriod?.status !== 'CLOSED'" :loading="archiving" @click="createArchive(true)">生成结账后正式归档</el-button>
            <span class="muted">自动按“期间 / 凭证号_日期_摘要”改名，打包凭证 HTML、JSON、分录 CSV、来源附件、清单及 SHA-256。</span>
          </div>
          <el-table v-loading="archiveQuery.isLoading.value" :data="archives" border size="small" class="data-table archive-table" empty-text="尚未生成归档包">
            <el-table-column prop="archiveNo" label="归档批次" min-width="210" fixed="left" />
            <el-table-column prop="periodName" label="会计期间" width="120" />
            <el-table-column label="类型" width="115"><template #default="{ row }"><el-tag size="small" :type="row.archiveMode === 'FINAL' ? 'success' : 'warning'">{{ row.archiveMode === 'FINAL' ? '结账后归档' : '预归档' }}</el-tag></template></el-table-column>
            <el-table-column label="内容" min-width="170"><template #default="{ row }">{{ row.voucherCount }} 张凭证<div class="multiline-secondary">{{ row.sourceAttachmentCount }} 份来源附件 · {{ fileSize(row.archiveBytes) }}</div></template></el-table-column>
            <el-table-column label="文件摘要" min-width="190"><template #default="{ row }"><span class="archive-hash" :title="row.archiveSha256">{{ shortHash(row.archiveSha256) }}</span></template></el-table-column>
            <el-table-column label="生成" min-width="175"><template #default="{ row }">{{ row.generatedByName || '系统' }}<div class="multiline-secondary">{{ formatDateTime(row.generatedAt) }}</div></template></el-table-column>
            <el-table-column label="状态" width="125"><template #default="{ row }"><el-tooltip v-if="row.status === 'SUPERSEDED'" :content="row.errorMessage || '来源期间已反结账'" placement="top"><el-tag size="small" type="info">已失效留痕</el-tag></el-tooltip><el-tag v-else size="small" :type="row.status === 'COMPLETED' ? 'success' : row.status === 'FAILED' ? 'danger' : 'warning'">{{ archiveStatusName(row.status) }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button v-if="row.downloadable" link type="primary" @click="downloadArchive(row.id, row.fileName)">下载</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import { formatDateTime, money } from '@/api/format'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()
const queryClient = useQueryClient()
const activeTab = ref('vouchers')
const keyword = ref('')
const status = ref('')
const dateRange = ref<[string, string] | null>(null)
const loadingActionId = ref(0)
const loadingIssueId = ref(0)
const generating = ref(false)
const archiving = ref(false)
const archivePeriodId = ref<number>()
const params = computed(() => ({ status: status.value || undefined, from: dateRange.value?.[0], to: dateRange.value?.[1] }))
const voucherQuery = useQuery({ queryKey: computed(() => ['accounting-vouchers', params.value]), queryFn: () => api.accountingVouchers(params.value) })
const issueQuery = useQuery({ queryKey: ['accounting-generation-issues', 'OPEN'], queryFn: () => api.accountingGenerationIssues('OPEN') })
const periodQuery = useQuery({ queryKey: ['accounting-periods'], queryFn: () => api.accountingPeriods() })
const archiveQuery = useQuery({ queryKey: ['accounting-archives'], queryFn: () => api.accountingArchives() })
const issues = computed(() => issueQuery.data.value || [])
const periods = computed(() => periodQuery.data.value || [])
const archives = computed(() => archiveQuery.data.value || [])
const selectedPeriod = computed(() => periods.value.find((period) => period.id === archivePeriodId.value))
const filteredVouchers = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  const rows = voucherQuery.data.value || []
  return text ? rows.filter((row) => [row.voucherNo, row.summary, row.sourceNo, row.makerName].some((value) => String(value || '').toLowerCase().includes(text))) : rows
})
const statusOptions = [
  { label: '暂存', value: 'DRAFT' }, { label: '已审核', value: 'REVIEWED' }, { label: '已记账', value: 'POSTED' },
  { label: '已被冲销', value: 'REVERSED' }, { label: '已作废', value: 'CANCELLED' },
]

async function refresh() {
  await Promise.all([queryClient.invalidateQueries({ queryKey: ['accounting-vouchers'] }), queryClient.invalidateQueries({ queryKey: ['accounting-generation-issues'] }), queryClient.invalidateQueries({ queryKey: ['accounting-dashboard'] })])
}
async function runAction(id: number, action: () => Promise<unknown>, message: string) {
  loadingActionId.value = id
  try { await action(); ElMessage.success(message); await refresh() } finally { loadingActionId.value = 0 }
}
async function review(id: number) {
  await ElMessageBox.confirm('审核后凭证内容将冻结，需退回后才能修改。确认审核？', '审核凭证', { type: 'warning' })
  await runAction(id, () => api.reviewAccountingVoucher(id), '凭证已审核')
}
async function reject(id: number) {
  const { value } = await ElMessageBox.prompt('请填写退回原因', '退回凭证', { inputValidator: (text) => Boolean(String(text).trim()) || '必须填写原因' })
  await runAction(id, () => api.rejectAccountingVoucher(id, value), '凭证已退回')
}
async function post(id: number) {
  await ElMessageBox.confirm('记账后将形成不可直接修改的总账分录，只能通过冲销更正。确认记账？', '凭证记账', { type: 'warning' })
  await runAction(id, () => api.postAccountingVoucher(id), '凭证已记账')
}
async function cancel(id: number) {
  const { value } = await ElMessageBox.prompt('作废保留原凭证和审计记录，请填写原因', '作废凭证', { inputValidator: (text) => Boolean(String(text).trim()) || '必须填写原因' })
  await runAction(id, () => api.cancelAccountingVoucher(id, value), '凭证已作废')
}
async function reverse(id: number) {
  const { value } = await ElMessageBox.prompt('系统会生成借贷相反的冲销凭证草稿，请填写原因', '冲销凭证', { inputValidator: (text) => Boolean(String(text).trim()) || '必须填写原因' })
  await runAction(id, () => api.reverseAccountingVoucher(id, value, localDate()), '冲销凭证草稿已生成')
}
async function generateMissing() {
  generating.value = true
  try {
    const result = await api.generateMissingAccountingVouchers()
    ElMessage.success(`生成 ${result.generatedCount} 张，作废来源草稿 ${result.cancelledCount} 张，异常 ${result.issueCount} 条`)
    await refresh()
  } finally { generating.value = false }
}
async function retryIssue(id: number) {
  loadingIssueId.value = id
  try { await api.retryAccountingGeneration(id); ElMessage.success('制证已重试'); await refresh() } finally { loadingIssueId.value = 0 }
}
async function createArchive(finalArchive: boolean) {
  if (!archivePeriodId.value) return
  const label = finalArchive ? '结账后正式归档' : '预归档'
  await ElMessageBox.confirm(`系统将校验全部来源附件的大小和 SHA-256，并生成不可覆盖的新归档批次。确认生成${label}？`, label, { type: 'warning' })
  archiving.value = true
  try {
    await api.createAccountingArchive(archivePeriodId.value, finalArchive)
    ElMessage.success(`${label}已生成，可下载核验`)
    await queryClient.invalidateQueries({ queryKey: ['accounting-archives'] })
  } finally { archiving.value = false }
}
async function downloadArchive(id: number, fallbackName: string) { const result = await api.downloadAccountingArchive(id, fallbackName); const url = URL.createObjectURL(result.blob); const anchor = document.createElement('a'); anchor.href = url; anchor.download = result.fileName; anchor.click(); URL.revokeObjectURL(url) }
function archiveStatusName(value: string) { return ({ GENERATING: '生成中', COMPLETED: '已完成', FAILED: '失败', SUPERSEDED: '已失效留痕' } as Record<string, string>)[value] || value }
function fileSize(bytes: number) { if (!bytes) return '0 B'; if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`; return `${(bytes / 1024 / 1024).toFixed(1)} MB` }
function shortHash(value: string) { return value ? `${value.slice(0, 12)}…${value.slice(-8)}` : '-' }
function localDate() { const now = new Date(); return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}` }
function voucherTypeName(value: string) { return ({ AUTO: '业务自动', MANUAL: '手工录入', REVERSAL: '冲销凭证' } as Record<string, string>)[value] || value }
function voucherStatusName(value: string) { return ({ DRAFT: '暂存', REVIEWED: '已审核', POSTED: '已记账', REVERSED: '已被冲销', CANCELLED: '已作废' } as Record<string, string>)[value] || value }
function voucherStatusType(value: string) { return value === 'POSTED' ? 'success' : value === 'REVIEWED' ? 'warning' : value === 'CANCELLED' ? 'info' : value === 'REVERSED' ? 'danger' : 'primary' }
function sourceTypeName(value: string) { return ({ INBOUND: '采购入库', OUTBOUND: '销售出库', SALES_RETURN: '销售退货', PURCHASE_RETURN: '采购退货', INVENTORY_TRANSFER: '库存调拨', INVENTORY_STOCKTAKE: '库存盘点', INVENTORY_COST_ADJUSTMENT: '成本调整', LANDED_COST_ALLOCATION: '采购费用分摊', PAYMENT: '客户收款', SUPPLIER_PAYMENT: '供应商付款', RECEIVABLE_ADJUSTMENT: '应收调整', TAX_INVOICE_BASE: '发票税额重分类', TAX_INVOICE_USAGE: '进项用途确认', TAX_INVOICE_TRANSFER: '进项税额转出' } as Record<string, string>)[value] || value }
</script>

<style scoped>
.accounting-subtitle { margin-top: 5px; font-size: 12px; }
.header-actions { display: flex; gap: 8px; }
.archive-tools { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin: 14px 0; }
.archive-tools .muted { flex: 1 1 420px; }
.archive-hash { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
</style>
