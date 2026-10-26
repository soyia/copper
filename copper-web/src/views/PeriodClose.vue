<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">期末结账</div>
          <div class="muted close-subtitle">结账会同时校验业务单据、库存、往来、资金、税票、凭证、报表和总账；任何一层未闭环都不能锁定期间。</div>
        </div>
        <div class="header-actions">
          <el-button v-if="preview" plain :loading="printing" @click="printCloseChecklist">打印 / 导出关账检查表</el-button>
          <el-button :loading="previewQuery.isFetching.value" @click="refresh">重新检查</el-button>
        </div>
      </div>

      <div class="list-tools">
        <el-input-number v-model="fiscalYear" :min="2000" :max="2200" :controls="false" />
        <el-select v-model="periodId" placeholder="选择会计期间">
          <el-option v-for="item in periods" :key="item.id" :label="`${item.periodName} · ${item.status === 'OPEN' ? '开放' : '已结账'}`" :value="item.id" />
        </el-select>
        <el-tag v-if="preview" :type="preview.periodStatus === 'CLOSED' ? 'danger' : preview.closable ? 'success' : 'warning'">{{ preview.periodStatus === 'CLOSED' ? '已结账' : preview.closable ? '允许结账' : `${blockedCount} 项阻断` }}</el-tag>
      </div>

      <el-alert v-if="preview?.periodStatus === 'CLOSED'" type="warning" :closable="false" show-icon title="该期间已经结账。历史凭证和余额已锁定；确需修正时必须填写原因并按时间倒序反结账，系统会冲销原损益结转。" />
      <el-alert v-else-if="preview && !preview.closable" type="error" :closable="false" show-icon title="当前不能结账。请逐项处理阻断问题后重新检查，不能通过改余额、删记录或跳过对账强行关闭。" />
      <el-alert v-else-if="preview" type="success" :closable="false" show-icon title="全部硬性检查通过。确认业务截止、附件和线下凭据已归档后，可以执行正式结账。" />

      <div v-if="preview" class="close-cards">
        <div><span>资产</span><strong>{{ money(preview.balanceSheetAssets) }}</strong></div>
        <div><span>负债和权益</span><strong>{{ money(preview.balanceSheetLiabilitiesEquity) }}</strong></div>
        <div><span>本期净利润</span><strong>{{ money(preview.incomeNetProfit) }}</strong></div>
        <div><span>现金净增加额</span><strong>{{ money(preview.cashNetIncrease) }}</strong></div>
        <div :class="{ danger: !isZero(preview.trialDifference) }"><span>试算差额</span><strong>{{ money(preview.trialDifference) }}</strong></div>
      </div>

      <el-table v-if="preview" v-loading="previewQuery.isLoading.value" :data="preview.checks" border size="small" class="data-table check-table">
        <el-table-column label="结果" width="90" fixed="left"><template #default="{ row }"><el-tag size="small" :type="row.status === 'PASS' ? 'success' : row.status === 'WARNING' ? 'warning' : 'danger'">{{ row.status === 'PASS' ? '通过' : row.status === 'WARNING' ? '提示' : '阻断' }}</el-tag></template></el-table-column>
        <el-table-column prop="checkName" label="检查项目" min-width="190" />
        <el-table-column prop="detail" label="检查结果" min-width="360" />
        <el-table-column label="问题数" width="90" align="right"><template #default="{ row }">{{ row.issueCount || '-' }}</template></el-table-column>
        <el-table-column label="差额" width="130" align="right"><template #default="{ row }">{{ Number(row.amountDifference) ? money(row.amountDifference) : '-' }}</template></el-table-column>
        <el-table-column label="处理入口" width="135"><template #default="{ row }"><RouterLink v-if="checkRoute(row.checkCode)" class="business-link" :to="checkRoute(row.checkCode)!">{{ checkAction(row.checkCode) }}</RouterLink><span v-else>-</span></template></el-table-column>
      </el-table>

      <div v-if="preview" class="profit-panel">
        <div>
          <strong>损益结转凭证</strong>
          <div v-if="preview.profitVoucherId" class="muted profit-detail"><RouterLink class="business-link" :to="`/accounting/vouchers/${preview.profitVoucherId}`">{{ preview.profitVoucherNo || `#${preview.profitVoucherId}` }}</RouterLink> · {{ voucherStatus(preview.profitVoucherStatus) }}。凭证必须审核并记账后，结账检查才会通过。</div>
          <div v-else class="muted profit-detail">本期有损益发生时，系统按收入、成本和费用科目生成结转至“本年利润”的凭证草稿；不会自动绕过审核。</div>
        </div>
        <el-button v-if="canClose && preview.periodStatus === 'OPEN' && !preview.profitVoucherId" :loading="acting" @click="generateProfit">生成损益结转</el-button>
      </div>

      <div v-if="preview" class="close-actions">
        <div v-if="preview.lastCloseRunId" class="snapshot-info">
          <span>最近结账批次 #{{ preview.lastCloseRunId }} · {{ preview.lastCloseRunStatus }}</span>
          <span v-if="preview.lastSnapshotHash">快照摘要 {{ preview.lastSnapshotHash }}</span>
        </div>
        <el-button v-if="canClose && preview.periodStatus === 'OPEN'" type="danger" size="large" :loading="acting" :disabled="!preview.closable" @click="formalClose">正式结账并锁定期间</el-button>
        <el-button v-if="canReopen && preview.periodStatus === 'CLOSED'" type="warning" size="large" :loading="acting" @click="formalReopen">规范反结账</el-button>
      </div>
    </div>
    <section v-if="preview" class="print-sheet a4-print-sheet">
      <div class="document-print-heading">
        <div class="document-print-company">{{ appStore.settings.companyName }}</div>
        <div class="document-print-title">期末关账检查表</div>
        <div class="document-print-subtitle"><span>{{ preview.periodName }} · {{ preview.startDate }} 至 {{ preview.endDate }}</span><span>检查 / 打印时间：{{ printTime }}</span></div>
      </div>
      <table class="document-print-meta"><tbody>
        <tr><td>期间状态：{{ preview.periodStatus === 'CLOSED' ? '已结账' : '开放' }}</td><td>结论：{{ preview.closable ? '允许结账' : `${blockedCount} 项阻断` }}</td><td>最近结账批次：{{ preview.lastCloseRunId ? `#${preview.lastCloseRunId} ${preview.lastCloseRunStatus}` : '-' }}</td></tr>
        <tr><td>资产：{{ money(preview.balanceSheetAssets) }}</td><td>负债和权益：{{ money(preview.balanceSheetLiabilitiesEquity) }}</td><td>试算差额：{{ money(preview.trialDifference) }}</td></tr>
        <tr><td>本期净利润：{{ money(preview.incomeNetProfit) }}</td><td>现金净增加额：{{ money(preview.cashNetIncrease) }}</td><td>损益结转凭证：{{ preview.profitVoucherNo || '-' }}</td></tr>
      </tbody></table>
      <table class="document-print-table"><thead><tr><th>#</th><th>结果</th><th>检查项目</th><th>问题数 / 差额</th><th>检查说明</th></tr></thead><tbody>
        <tr v-for="(row, index) in preview.checks" :key="row.checkCode"><td class="center">{{ index + 1 }}</td><td>{{ row.status === 'PASS' ? '通过' : row.status === 'WARNING' ? '提示' : '阻断' }}</td><td>{{ row.checkName }}</td><td>{{ row.issueCount || 0 }} / {{ money(row.amountDifference) }}</td><td>{{ row.detail }}</td></tr>
      </tbody></table>
      <div class="document-print-audit-title">期间锁定与反结账审计</div>
      <table class="document-print-table document-print-audit"><thead><tr><th>#</th><th>动作</th><th>状态变化</th><th>操作人 / 时间</th><th>原因</th></tr></thead><tbody>
        <tr v-for="(event, index) in preview.events" :key="event.id"><td class="center">{{ index + 1 }}</td><td>{{ periodEventName(event.action) }}</td><td>{{ event.fromStatus || '-' }} → {{ event.toStatus || '-' }}</td><td>{{ event.operatorName || '系统' }}<br>{{ formatDateTime(event.createdAt) }}</td><td>{{ event.reason || '-' }}</td></tr>
        <tr v-if="!preview.events.length"><td class="center">-</td><td colspan="4">尚无结账或反结账事件</td></tr>
      </tbody></table>
      <div v-if="preview.lastSnapshotHash" class="document-print-hash">最近结账快照：{{ preview.lastSnapshotHash }}</div>
      <div class="document-print-hash">本次打印数据摘要（SHA-256）：{{ snapshotHash }}</div>
      <div class="document-print-signatures"><span>制表：</span><span>会计复核：</span><span>业务确认：</span><span>负责人批准：</span></div>
    </section>
    <EvidenceAttachments
      v-if="preview && periodId"
      document-type="PERIOD_CLOSE"
      :document-id="periodId"
      :title="`${preview.periodName}关账检查、签字审批与结账依据`"
      default-evidence-type="SIGNED_STAMPED_COPY"
      show-output-history
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import { formatDateTime, money } from '@/api/format'
import EvidenceAttachments from '@/components/EvidenceAttachments.vue'
import { useAuditedDocumentPrint } from '@/composables/useAuditedDocumentPrint'
import { printFileName } from '@/composables/useDocumentPrint'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()
const route = useRoute()
const queryClient = useQueryClient()
const requestedFiscalYear = Number(route.query.fiscalYear)
const fiscalYear = ref(Number.isInteger(requestedFiscalYear) && requestedFiscalYear >= 2000 ? requestedFiscalYear : new Date().getFullYear())
const periodId = ref(0)
const acting = ref(false)
const periodQuery = useQuery({ queryKey: computed(() => ['accounting-periods', fiscalYear.value]), queryFn: () => api.accountingPeriods(fiscalYear.value) })
const previewQuery = useQuery({ queryKey: computed(() => ['period-close-preview', periodId.value]), queryFn: () => api.periodClosePreview(periodId.value), enabled: computed(() => periodId.value > 0) })
const periods = computed(() => periodQuery.data.value || [])
const preview = computed(() => previewQuery.data.value)
const blockedCount = computed(() => preview.value?.checks.filter((item) => item.status === 'BLOCKED').length || 0)
const canClose = computed(() => appStore.hasPermission('financial.close.manage'))
const canReopen = computed(() => appStore.hasPermission('financial.close.reopen'))
const { printTime, snapshotHash, printing, printAndRecord } = useAuditedDocumentPrint('PERIOD_CLOSE', () => periodId.value)

watch([periods, () => route.query.periodId], ([rows, requested]) => {
  if (!rows.length) return
  const requestedId = Number(requested)
  if (Number.isInteger(requestedId) && requestedId > 0 && rows.some((item) => item.id === requestedId)) {
    periodId.value = requestedId
    return
  }
  if (!periodId.value) periodId.value = rows.find((item) => item.status === 'OPEN')?.id || rows[0].id
}, { immediate: true })
async function refresh() { if (periodId.value) await previewQuery.refetch() }
async function printCloseChecklist() {
  if (!preview.value) return
  await printAndRecord(
    printFileName(preview.value.periodName, '期末关账检查表'),
    preview.value,
    'copper-period-close-checklist-print/v1',
  )
}
async function generateProfit() { acting.value = true; try { const id = await api.generatePeriodProfitVoucher(periodId.value); if (id) ElMessage.success('损益结转凭证草稿已生成，请进入凭证详情完成审核和记账'); else ElMessage.success('本期没有需要结转的损益余额'); await refresh(); await queryClient.invalidateQueries({ queryKey: ['accounting-vouchers'] }) } finally { acting.value = false } }
async function formalClose() { if (!preview.value?.closable) return; const { value } = await ElMessageBox.prompt(`本操作会锁定 ${preview.value.periodName} 的业务制证、凭证审核记账和银行对账。请输入“${preview.value.periodName}结账”并同时说明结账依据。`, '正式期末结账', { confirmButtonText: '确认结账', type: 'warning', inputValidator: (text) => String(text).includes(`${preview.value?.periodName}结账`) || `必须包含“${preview.value?.periodName}结账”` }); acting.value = true; try { const result = await api.formalClosePeriod(periodId.value, value); ElMessage.success(`期间已结账，快照摘要 ${result.snapshotHash.slice(0, 12)}…`); await reload() } finally { acting.value = false } }
async function formalReopen() { if (!preview.value) return; const { value } = await ElMessageBox.prompt('反结账会重新开放历史期间，并对原损益结转凭证执行作废或生成冲销凭证。请填写具体差错、修正方案和复核人。', `规范反结账 · ${preview.value.periodName}`, { confirmButtonText: '确认反结账', type: 'warning', inputValidator: (text) => String(text).trim().length >= 10 || '请至少填写10个字的具体原因' }); acting.value = true; try { await api.formalReopenPeriod(periodId.value, value); ElMessage.success('期间已重新开放；原损益结转已按状态作废或冲销'); await reload() } finally { acting.value = false } }
async function reload() { await Promise.all([periodQuery.refetch(), previewQuery.refetch(), queryClient.invalidateQueries({ queryKey: ['accounting-periods'] }), queryClient.invalidateQueries({ queryKey: ['accounting-vouchers'] })]) }
function checkRoute(code: string) { return ({ VOUCHER_STATUS: '/accounting/vouchers', BUSINESS_POSTING: '/business-postings', INVENTORY_DOCUMENTS: '/inventory/reconciliation', INVENTORY_RECONCILIATION: '/inventory/reconciliation', PARTNER_BALANCE: '/finance', ACCEPTANCE_ACCOUNTING: '/acceptance-bills', TAX_INVOICE: '/tax-invoices/reconciliation', BANK_RECONCILIATION: '/accounting/bank-reconciliation', CASH_FLOW_RECONCILIATION: '/accounting/reports', TRIAL_BALANCE: '/accounting/ledger', REPORT_MAPPING: '/accounting/reports', BALANCE_SHEET: '/accounting/reports', PROFIT_CARRY_FORWARD: preview.value?.profitVoucherId ? `/accounting/vouchers/${preview.value.profitVoucherId}` : '/accounting/vouchers' } as Record<string, string>)[code] }
function checkAction(code: string) { return ({ VOUCHER_STATUS: '处理凭证', BUSINESS_POSTING: '查看制证问题', INVENTORY_DOCUMENTS: '查看库存勾稽', INVENTORY_RECONCILIATION: '查看库存勾稽', PARTNER_BALANCE: '查看往来资金', ACCEPTANCE_ACCOUNTING: '查看承兑票据', TAX_INVOICE: '处理税票异常', BANK_RECONCILIATION: '完成银行对账', CASH_FLOW_RECONCILIATION: '查看现金流量表', TRIAL_BALANCE: '查看试算平衡', REPORT_MAPPING: '查看财务报表', BALANCE_SHEET: '查看资产负债表', PROFIT_CARRY_FORWARD: '处理结转凭证' } as Record<string, string>)[code] || '查看' }
function voucherStatus(value: string) { return ({ DRAFT: '草稿', REVIEWED: '已审核', POSTED: '已记账', REVERSED: '已冲销', CANCELLED: '已作废' } as Record<string, string>)[value] || value }
function periodEventName(value: string) { return ({ FORMAL_CLOSE: '正式结账', FORMAL_REOPEN: '规范反结账' } as Record<string, string>)[value] || value }
function isZero(value: number) { return Math.abs(Number(value || 0)) < 0.01 }
</script>

<style scoped>
.close-subtitle { margin-top: 5px; font-size: 12px; }
.header-actions { display: flex; gap: 8px; }
.close-cards { display: grid; grid-template-columns: repeat(5, minmax(150px, 1fr)); gap: 10px; margin: 14px 0; }
.close-cards > div { padding: 12px 14px; border: 1px solid #dfe6ea; border-radius: 8px; background: #fafcfd; }
.close-cards span { display: block; color: #73828c; font-size: 12px; }
.close-cards strong { display: block; margin-top: 6px; font-size: 17px; }
.close-cards .danger { border-color: #efb7b7; background: #fff5f5; color: #c43434; }
.check-table { margin-top: 4px; }
.profit-panel { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 14px; padding: 14px 16px; border: 1px solid #dfe6ea; border-radius: 8px; background: #fafcfd; }
.profit-detail { margin-top: 5px; font-size: 12px; }
.close-actions { display: flex; justify-content: flex-end; align-items: center; gap: 16px; margin-top: 18px; }
.snapshot-info { display: flex; flex: 1; flex-direction: column; gap: 4px; min-width: 0; color: #78868f; font-size: 11px; word-break: break-all; }
@media (max-width: 1100px) { .close-cards { grid-template-columns: repeat(2, minmax(150px, 1fr)); } }
</style>
