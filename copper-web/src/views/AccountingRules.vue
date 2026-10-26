<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div><div class="panel-title">会计规则与覆盖矩阵</div><div class="muted rules-subtitle">先证明每种业务事实有且只有一种会计处置，再允许自动制证进入总账。</div></div>
        <div class="header-actions"><el-button :loading="query.isFetching.value || coverageQuery.isFetching.value" @click="refreshAll">刷新</el-button><el-button v-if="activeTab === 'rules' && appStore.hasPermission('accounting.rule.manage')" type="primary" @click="openRule()">新增规则版本</el-button></div>
      </div>
      <el-tabs v-model="activeTab" class="coverage-tabs">
        <el-tab-pane label="覆盖矩阵" name="coverage">
          <el-alert :type="coverageSummary.blockingCount ? 'error' : 'success'" :closable="false" show-icon>
            <template #title>{{ coverageSummary.blockingCount ? `发现 ${coverageSummary.blockingCount} 个阻断项：缺失、重复或未登记的会计规则必须先处理` : '覆盖门禁通过：当前生效日没有会导致漏记或不确定凭证的阻断项' }}</template>
          </el-alert>
          <div class="coverage-summary">
            <div class="summary-item"><span>覆盖场景</span><strong>{{ coverageSummary.totalCount }}</strong></div>
            <div class="summary-item success"><span>已覆盖/无需制证</span><strong>{{ coverageSummary.coveredCount }}</strong></div>
            <div class="summary-item warning"><span>自定义科目</span><strong>{{ coverageSummary.warningCount }}</strong></div>
            <div class="summary-item danger"><span>阻断项</span><strong>{{ coverageSummary.blockingCount }}</strong></div>
          </div>
          <div class="list-tools rule-tools coverage-tools">
            <el-input v-model="coverageKeyword" clearable placeholder="场景 / fact_type / 来源 / 凭证结果" />
            <el-select v-model="coverageStatus" clearable placeholder="全部状态" style="width:170px">
              <el-option label="已覆盖" value="COVERED" /><el-option label="无需制证" value="NOT_REQUIRED" />
              <el-option label="自定义科目" value="CUSTOMIZED" /><el-option label="阻断项" value="BLOCKING" />
            </el-select>
            <el-date-picker v-model="coverageDate" type="date" value-format="YYYY-MM-DD" :clearable="false" style="width:150px" />
          </div>
          <el-table v-loading="coverageQuery.isLoading.value" :data="filteredCoverage" border size="small" class="data-table" max-height="calc(100vh - 390px)">
            <el-table-column label="业务场景" min-width="215" fixed="left"><template #default="{ row }"><strong>{{ row.scenarioName }}</strong><div class="multiline-secondary">{{ row.category }}<template v-if="row.settlementMethod"> · {{ settlementName(row.settlementMethod) }}</template></div></template></el-table-column>
            <el-table-column label="业务事实" min-width="285"><template #default="{ row }"><div>{{ row.sourceType }} / {{ row.postingAction }}</div><div class="fact-code">{{ row.factType }} · {{ directionName(row.factDirection) }}</div></template></el-table-column>
            <el-table-column label="制证方式" width="125"><template #default="{ row }">{{ modeName(row.generationMode) }}</template></el-table-column>
            <el-table-column label="稳定凭证结果" min-width="260"><template #default="{ row }"><div>{{ row.resultDescription }}</div><div v-if="row.baselineDebitCode" class="multiline-secondary">基线：借 {{ row.baselineDebitCode }} / 贷 {{ row.baselineCreditCode }}</div></template></el-table-column>
            <el-table-column label="当前生效规则" min-width="215"><template #default="{ row }"><template v-if="row.actualRuleCode"><div>{{ row.actualRuleCode }}</div><div class="multiline-secondary">借 {{ row.actualDebitCode }} / 贷 {{ row.actualCreditCode }}</div></template><span v-else class="muted">{{ row.generationMode === 'ACCOUNTING_RULE' ? '未找到' : row.generationMode === 'NO_VOUCHER' ? '无需会计规则' : '内置处理器' }}</span></template></el-table-column>
            <el-table-column label="覆盖状态" width="125"><template #default="{ row }"><el-tag size="small" :type="coverageTagType(row.coverageStatus)">{{ coverageStatusName(row.coverageStatus) }}</el-tag><div v-if="row.activeRuleCount > 1" class="danger-text">{{ row.activeRuleCount }} 条同时生效</div></template></el-table-column>
            <el-table-column label="控制证据" min-width="300"><template #default="{ row }">{{ row.evidence }}</template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="规则版本" name="rules">
          <el-alert type="warning" :closable="false" show-icon title="修改规则只影响生效日之后新生成的凭证，已发布历史版本不覆盖，只结束有效期；同一业务事实在同一日期只能有一条有效映射。" />
          <div class="list-tools rule-tools"><el-input v-model="keyword" clearable placeholder="规则名称 / 编码 / 来源 / 科目" /><el-select v-model="ruleStatus" clearable placeholder="全部状态" style="width:140px"><el-option label="已发布" value="ENABLED" /><el-option label="草稿" value="DRAFT" /><el-option label="历史版本" value="DISABLED" /></el-select></div>
          <el-table v-loading="query.isLoading.value" :data="filteredRules" border size="small" class="data-table" max-height="calc(100vh - 330px)">
            <el-table-column label="规则" min-width="210" fixed="left"><template #default="{ row }"><strong>{{ row.ruleName }}</strong><div class="multiline-secondary">{{ row.ruleCode }} · V{{ row.versionNo }}</div></template></el-table-column>
            <el-table-column label="匹配条件" min-width="260"><template #default="{ row }"><div>{{ sourceName(row.sourceType) }} / {{ actionName(row.postingAction) }}</div><div class="multiline-secondary">{{ factName(row.factType) }} · {{ row.factDirection === 'INCREASE' ? '增加' : '减少' }}</div></template></el-table-column>
            <el-table-column label="借方科目" min-width="170"><template #default="{ row }">{{ row.debitAccountCode }} {{ row.debitAccountName }}</template></el-table-column>
            <el-table-column label="贷方科目" min-width="170"><template #default="{ row }">{{ row.creditAccountCode }} {{ row.creditAccountName }}</template></el-table-column>
            <el-table-column label="有效期" min-width="190"><template #default="{ row }">{{ row.effectiveFrom }} 至 {{ row.effectiveTo || '长期' }}</template></el-table-column>
            <el-table-column label="状态" width="105"><template #default="{ row }"><el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : row.status === 'DRAFT' ? 'warning' : 'info'">{{ statusName(row.status) }}</el-tag></template></el-table-column>
            <el-table-column label="变更说明" min-width="220"><template #default="{ row }"><div>{{ row.changeReason }}</div><div v-if="row.publishedAt" class="multiline-secondary">{{ row.publishedByName || '系统' }} · {{ formatDateTime(row.publishedAt) }}</div></template></el-table-column>
            <el-table-column v-if="appStore.hasPermission('accounting.rule.manage')" label="操作" width="140" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openRule(row)">新版本</el-button><el-button v-if="row.status === 'DRAFT'" link type="success" :loading="publishingId === row.id" @click="publish(row.id)">发布</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-dialog v-model="dialog" title="新增会计规则版本" width="760px" destroy-on-close>
      <el-form label-position="top">
        <div class="form-grid form-grid-2">
          <el-form-item label="规则编码" required><el-input v-model="form.ruleCode" maxlength="96" placeholder="同一规则沿用编码以形成版本链" /></el-form-item>
          <el-form-item label="规则名称" required><el-input v-model="form.ruleName" maxlength="128" /></el-form-item>
          <el-form-item label="业务来源" required><el-select v-model="form.sourceType" filterable allow-create default-first-option><el-option v-for="item in sourceOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
          <el-form-item label="过账动作" required><el-select v-model="form.postingAction" filterable allow-create default-first-option><el-option v-for="item in actionOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
          <el-form-item label="业务事实" required><el-select v-model="form.factType" filterable allow-create default-first-option><el-option v-for="item in factOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
          <el-form-item label="事实方向" required><el-select v-model="form.factDirection"><el-option label="增加" value="INCREASE" /><el-option label="减少" value="DECREASE" /></el-select></el-form-item>
          <el-form-item label="借方科目" required><el-select v-model="form.debitAccountId" filterable><el-option v-for="item in leafAccounts" :key="item.id" :label="`${item.accountCode} ${item.accountName}`" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="贷方科目" required><el-select v-model="form.creditAccountId" filterable><el-option v-for="item in leafAccounts" :key="item.id" :label="`${item.accountCode} ${item.accountName}`" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="生效日期" required><el-date-picker v-model="form.effectiveFrom" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item class="form-span-2" label="变更原因" required><el-input v-model="form.changeReason" type="textarea" :rows="2" maxlength="500" show-word-limit /></el-form-item>
        </div>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存草稿</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type AccountingRule, type AccountingRuleCoverageRow } from '@/api/client'
import { formatDateTime } from '@/api/format'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()
const queryClient = useQueryClient()
const activeTab = ref('coverage')
const keyword = ref('')
const ruleStatus = ref('')
const coverageKeyword = ref('')
const coverageStatus = ref('')
const coverageDate = ref(localDate())
const dialog = ref(false)
const saving = ref(false)
const publishingId = ref(0)
const query = useQuery({ queryKey: ['accounting-rules'], queryFn: api.accountingRules })
const coverageQuery = useQuery({ queryKey: computed(() => ['accounting-rule-coverage', coverageDate.value]), queryFn: () => api.accountingRuleCoverage(coverageDate.value) })
const accountQuery = useQuery({ queryKey: ['accounting-accounts'], queryFn: () => api.accountingAccounts(false) })
const leafAccounts = computed(() => (accountQuery.data.value || []).filter((item) => item.leaf && item.status === 'ENABLED'))
const filteredRules = computed(() => (query.data.value || []).filter((row) => (!ruleStatus.value || row.status === ruleStatus.value) && (!keyword.value.trim() || [row.ruleCode, row.ruleName, row.sourceType, row.debitAccountCode, row.debitAccountName, row.creditAccountCode, row.creditAccountName].some((value) => String(value).toLowerCase().includes(keyword.value.trim().toLowerCase())))))
const blockingStatuses = new Set(['MISSING_RULE', 'AMBIGUOUS_RULE', 'UNREGISTERED_RULE', 'UNREGISTERED_FACT'])
const coverageSummary = computed(() => coverageQuery.data.value?.summary || { totalCount: 0, coveredCount: 0, warningCount: 0, blockingCount: 0, accountingRuleCount: 0, noVoucherCount: 0, asOf: coverageDate.value })
const filteredCoverage = computed(() => (coverageQuery.data.value?.rows || []).filter((row) => {
  const statusMatch = !coverageStatus.value || (coverageStatus.value === 'BLOCKING' ? blockingStatuses.has(row.coverageStatus) : row.coverageStatus === coverageStatus.value)
  const needle = coverageKeyword.value.trim().toLowerCase()
  const keywordMatch = !needle || [row.scenarioName, row.category, row.sourceType, row.postingAction, row.factType, row.resultDescription, row.actualRuleCode].some((value) => String(value).toLowerCase().includes(needle))
  return statusMatch && keywordMatch
}))
const form = reactive({ ruleCode: '', ruleName: '', sourceType: '', postingAction: '', factType: '', factDirection: 'INCREASE' as 'INCREASE' | 'DECREASE', debitAccountId: 0, creditAccountId: 0, effectiveFrom: localDate(), changeReason: '' })
const sourceOptions = [['INBOUND','采购入库'],['OUTBOUND','销售出库'],['SALES_RETURN','销售退货'],['PURCHASE_RETURN','采购退货'],['INVENTORY_TRANSFER','库存调拨'],['INVENTORY_STOCKTAKE','库存盘点'],['INVENTORY_COST_ADJUSTMENT','成本调整'],['LANDED_COST_ALLOCATION','采购费用分摊'],['PAYMENT','客户收款'],['SUPPLIER_PAYMENT','供应商付款'],['CUSTOMER_ADVANCE_REFUND','客户预收退款'],['PARTNER_FUND_ADJUSTMENT','余额调整单'],['ACCEPTANCE_EVENT','承兑票据处置'],['RECEIVABLE_ADJUSTMENT','应收调整']].map(([value,label]) => ({ value, label }))
const actionOptions = [['POST','确认过账'],['SHIP','销售出库'],['SETTLE','定价结算'],['DISPATCH','调拨发出'],['RECEIVE','调拨收货'],['ROLLBACK','按当前结存回退']].map(([value,label]) => ({ value, label }))
const factOptions = [['INVENTORY_COST','库存成本'],['LANDED_COST_CAPITALIZED','采购附加费用资本化'],['LANDED_COST_RETURN_EXPENSE','退货附加费用转成本'],['INVENTORY_ON_HAND','实际结存'],['INVENTORY_IN_TRANSIT','在途库存'],['PAYABLE','应付'],['RECEIVABLE','应收'],['RECEIVABLE_SETTLEMENT','应收核销'],['RECEIVABLE_ACCEPTANCE','承兑核销应收'],['PAYABLE_SETTLEMENT','应付核销'],['CUSTOMER_ADVANCE','客户预收'],['CUSTOMER_ADVANCE_REFUND','客户预收退款'],['SUPPLIER_PREPAY','供应商预付'],['CUSTOMER_ADVANCE_SETTLEMENT','预收余额核销'],['CUSTOMER_REWARD_SETTLEMENT','奖励余额核销'],['SUPPLIER_PREPAY_SETTLEMENT','预付余额核销'],['SUPPLIER_REWARD_SETTLEMENT','返利余额核销'],['CUSTOMER_ADVANCE_OPENING','客户预收期初'],['SUPPLIER_PREPAY_OPENING','供应商预付期初'],['CUSTOMER_REWARD_GRANT','客户奖励确认'],['SUPPLIER_REBATE_GRANT','供应商返利确认'],['CUSTOMER_ADVANCE_CORRECTION','客户预收差错'],['CUSTOMER_REWARD_CORRECTION','客户奖励差错'],['SUPPLIER_PREPAY_CORRECTION','供应商预付差错'],['SUPPLIER_REWARD_CORRECTION','供应商返利差错'],['ACCEPTANCE_CASH_RECEIPT','承兑实收'],['ACCEPTANCE_DISCOUNT_FEE','承兑贴现费用'],['ACCEPTANCE_BOUNCE_CASH','承兑退票追索'],['RECEIVABLE_ADJUSTMENT','应收调整']].map(([value,label]) => ({ value, label }))

function openRule(row?: AccountingRule) { Object.assign(form, row ? { ruleCode: row.ruleCode, ruleName: row.ruleName, sourceType: row.sourceType, postingAction: row.postingAction, factType: row.factType, factDirection: row.factDirection, debitAccountId: row.debitAccountId, creditAccountId: row.creditAccountId, effectiveFrom: localDate(), changeReason: '' } : { ruleCode: '', ruleName: '', sourceType: '', postingAction: '', factType: '', factDirection: 'INCREASE', debitAccountId: 0, creditAccountId: 0, effectiveFrom: localDate(), changeReason: '' }); dialog.value = true }
async function save() { if (![form.ruleCode, form.ruleName, form.sourceType, form.postingAction, form.factType, form.effectiveFrom, form.changeReason].every((value) => String(value).trim()) || !form.debitAccountId || !form.creditAccountId) { ElMessage.warning('请完整填写规则、科目、生效日期和变更原因'); return } saving.value = true; try { await api.createAccountingRule({ ...form }); ElMessage.success('规则版本已保存为草稿'); dialog.value = false; await queryClient.invalidateQueries({ queryKey: ['accounting-rules'] }) } finally { saving.value = false } }
async function publish(id: number) { await ElMessageBox.confirm('发布后将从生效日期起用于新制证，并结束同编码旧版本。确认发布？', '发布会计规则', { type: 'warning' }); publishingId.value = id; try { await api.publishAccountingRule(id); ElMessage.success('规则已发布'); await queryClient.invalidateQueries({ queryKey: ['accounting-rules'] }) } finally { publishingId.value = 0 } }
async function refreshAll() { await Promise.all([query.refetch(), coverageQuery.refetch()]) }
function localDate() { const now = new Date(); return `${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-${String(now.getDate()).padStart(2,'0')}` }
function statusName(value: string) { return ({ ENABLED: '已发布', DRAFT: '草稿', DISABLED: '历史版本' } as Record<string,string>)[value] || value }
function sourceName(value: string) { return Object.fromEntries(sourceOptions.map((item) => [item.value,item.label]))[value] || value }
function actionName(value: string) { return Object.fromEntries(actionOptions.map((item) => [item.value,item.label]))[value] || value }
function factName(value: string) { return Object.fromEntries(factOptions.map((item) => [item.value,item.label]))[value] || value }
function directionName(value: string) { return ({ INCREASE: '增加', DECREASE: '减少', REVERSE: '反向冲销' } as Record<string,string>)[value] || value }
function settlementName(value: string) { return ({ CASH: '现金', BANK: '银行', ACCEPTANCE: '承兑' } as Record<string,string>)[value] || value }
function modeName(value: AccountingRuleCoverageRow['generationMode']) { return ({ ACCOUNTING_RULE: '规则制证', SOURCE_REVERSAL: '来源冲销', DIRECT_VOUCHER: '税票直制', NO_VOUCHER: '明确不制证', UNKNOWN: '未登记' } as Record<string,string>)[value] || value }
function coverageStatusName(value: AccountingRuleCoverageRow['coverageStatus']) { return ({ COVERED: '已覆盖', NOT_REQUIRED: '无需制证', CUSTOMIZED: '已自定义', MISSING_RULE: '缺少规则', AMBIGUOUS_RULE: '规则重复', UNREGISTERED_RULE: '规则未登记', UNREGISTERED_FACT: '事实未登记' } as Record<string,string>)[value] || value }
function coverageTagType(value: AccountingRuleCoverageRow['coverageStatus']) { if (blockingStatuses.has(value)) return 'danger'; if (value === 'CUSTOMIZED') return 'warning'; if (value === 'NOT_REQUIRED') return 'info'; return 'success' }
</script>

<style scoped>
.rules-subtitle { margin-top: 5px; font-size: 12px; }
.header-actions { display: flex; gap: 8px; }
.rule-tools { margin-top: 14px; }
.coverage-tabs { margin-top: 8px; }
.coverage-summary { display: grid; grid-template-columns: repeat(4, minmax(130px, 1fr)); gap: 10px; margin: 14px 0 4px; }
.summary-item { display: flex; align-items: center; justify-content: space-between; min-height: 56px; padding: 0 14px; border: 1px solid var(--el-border-color-light); border-radius: 8px; background: var(--el-fill-color-extra-light); }
.summary-item span { color: var(--el-text-color-secondary); font-size: 12px; }
.summary-item strong { font-size: 22px; }
.summary-item.success strong { color: var(--el-color-success); }
.summary-item.warning strong { color: var(--el-color-warning); }
.summary-item.danger strong, .danger-text { color: var(--el-color-danger); }
.coverage-tools { display: grid; grid-template-columns: minmax(260px, 1fr) 170px 150px; }
.fact-code { margin-top: 3px; color: var(--el-text-color-secondary); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.danger-text { margin-top: 3px; font-size: 11px; }
@media (max-width: 900px) { .coverage-summary { grid-template-columns: repeat(2, 1fr); } .coverage-tools { grid-template-columns: 1fr; } }
</style>
