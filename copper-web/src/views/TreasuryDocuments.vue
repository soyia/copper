<template>
  <div class="page">
    <div class="panel treasury-hero">
      <div class="panel-header">
        <div><div class="panel-title">费用与出纳</div><div class="muted">费用、其他往来、付款申请、实际资金和凭证各自留痕；付款申请获批不代表已经付款。</div></div>
        <div class="header-actions">
          <el-button @click="router.push('/treasury/journal')">统一出纳日记账</el-button>
          <el-button v-if="canFund" @click="openTransfer">内部账户转账</el-button>
          <el-button v-if="canManage" type="primary" @click="openCreate()">新建业务源单</el-button>
        </div>
      </div>
      <el-alert v-if="dashboard?.restricted" class="scope-alert" type="warning" :closable="false" show-icon
        title="当前角色仅可查看授权业务单据；为避免用局部数据冒充完整资金汇总，顶部经营合计已隐藏。" />
      <div class="summary-grid">
        <div class="summary-card warning"><span>待确认源单</span><strong>{{ dashboard?.draftDocumentCount ?? 0 }}</strong><small>{{ money(dashboard?.draftDocumentAmount) }}</small></div>
        <div class="summary-card"><span>其他往来未结</span><strong>{{ dashboard?.outstandingDocumentCount ?? 0 }}</strong><small>{{ money(dashboard?.outstandingDocumentAmount) }}</small></div>
        <div class="summary-card"><span>付款申请待处理</span><strong>{{ dashboard?.pendingRequestCount ?? 0 }}</strong><small>{{ money(dashboard?.pendingRequestAmount) }}</small></div>
        <div class="summary-card danger"><span>银行未对账</span><strong>{{ dashboard?.unreconciledBankCount ?? 0 }}</strong><small>{{ money(dashboard?.unreconciledBankAmount) }}</small></div>
      </div>
    </div>

    <div class="panel">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="业务源单" name="documents">
          <div class="list-tools filters">
            <el-input v-model="filters.keyword" clearable placeholder="单号 / 往来单位 / 摘要 / 依据" />
            <el-select v-model="filters.type" clearable placeholder="业务类型"><el-option v-for="item in documentTypes" :key="item.value" :label="item.label" :value="item.value" /></el-select>
            <el-select v-model="filters.status" clearable placeholder="状态"><el-option v-for="item in documentStatuses" :key="item.value" :label="item.label" :value="item.value" /></el-select>
            <PartnerRemoteSelect v-model="filters.partnerId" clearable placeholder="往来单位" />
            <div class="list-tools-actions"><el-button @click="resetFilters">重置</el-button></div>
          </div>
          <el-table v-loading="documentsQuery.isLoading.value" :data="filteredDocuments" class="data-table" size="small" border show-overflow-tooltip>
            <el-table-column label="源单号" min-width="190" fixed="left"><template #default="{ row }"><router-link class="table-link" :to="detailRoute(row.id)">{{ row.documentNo }}</router-link></template></el-table-column>
            <el-table-column prop="businessDate" label="业务日期" width="112" :formatter="formatDateColumn" />
            <el-table-column prop="partnerName" label="往来单位" min-width="170" />
            <el-table-column label="业务类型" width="142"><template #default="{ row }">{{ documentTypeName(row.documentType) }}</template></el-table-column>
            <el-table-column label="价税合计" width="135" align="right"><template #default="{ row }"><strong>{{ money(row.totalAmount) }}</strong></template></el-table-column>
            <el-table-column label="未结金额" width="135" align="right"><template #default="{ row }"><span :class="row.outstandingAmount > 0 ? 'amount-warn' : ''">{{ money(row.outstandingAmount) }}</span></template></el-table-column>
            <el-table-column label="到期日" width="112"><template #default="{ row }"><span :class="isOverdue(row) ? 'amount-danger' : ''">{{ row.dueDate }}</span></template></el-table-column>
            <el-table-column label="状态" width="106"><template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ statusName(row.status) }}</el-tag></template></el-table-column>
            <el-table-column prop="summary" label="摘要" min-width="200" />
            <el-table-column label="会计凭证" min-width="145"><template #default="{ row }"><router-link v-if="row.accountingVoucherId" class="table-link" :to="`/accounting/vouchers/${row.accountingVoucherId}`">{{ row.accountingVoucherNo }}</router-link><span v-else>-</span></template></el-table-column>
          </el-table>
          <el-empty v-if="!documentsQuery.isLoading.value && filteredDocuments.length === 0" description="没有符合条件的费用或其他往来源单" />
        </el-tab-pane>

        <el-tab-pane label="付款申请" name="requests">
          <el-alert class="tab-alert" type="info" :closable="false" show-icon title="付款申请只表达授权和计划；只有登记实际付款后，才会产生出纳日记账和资金凭证。" />
          <el-table v-loading="requestsQuery.isLoading.value" :data="requestsQuery.data.value ?? []" class="data-table" size="small" border show-overflow-tooltip>
            <el-table-column prop="requestNo" label="申请单号" min-width="190" fixed="left" />
            <el-table-column label="来源单" min-width="180"><template #default="{ row }"><router-link class="table-link" :to="detailRoute(row.documentId)">{{ row.documentNo }}</router-link><div class="multiline-secondary">{{ documentTypeName(row.documentType) }}</div></template></el-table-column>
            <el-table-column prop="partnerName" label="收款方" min-width="160" />
            <el-table-column label="申请金额" width="132" align="right"><template #default="{ row }">{{ money(row.requestedAmount) }}</template></el-table-column>
            <el-table-column label="未支付" width="132" align="right"><template #default="{ row }"><strong>{{ money(row.unpaidAmount) }}</strong></template></el-table-column>
            <el-table-column prop="expectedPaymentDate" label="预计付款" width="112" />
            <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="requestStatusType(row.status)" size="small">{{ requestStatusName(row.status) }}</el-tag></template></el-table-column>
            <el-table-column prop="purpose" label="付款用途" min-width="220" />
            <el-table-column label="操作" width="165" fixed="right"><template #default="{ row }"><el-button v-if="row.status === 'DRAFT' && canRequest" link type="primary" @click="approveRequest(row.id)">批准</el-button><el-button v-if="['DRAFT','APPROVED'].includes(row.status) && canRequest" link type="danger" @click="rejectRequest(row.id)">退回</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-dialog v-model="createVisible" title="新建费用或其他往来源单" width="760px" destroy-on-close>
      <el-alert type="info" :closable="false" show-icon :title="createForm.documentType === 'PURCHASE_LANDED_COST' ? '采购附加费用确认后先形成其他应付；系统按本单冻结的进项税处理方式决定计入批次成本的金额，付款和发票仍分别处理。' : '源单确认时形成其他应收/应付和草稿凭证；实际收付款、发票勾稽分别登记。'" />
      <el-form label-width="118px" class="dialog-form">
        <el-form-item label="业务类型" required><el-select v-model="createForm.documentType" @change="applyDocumentDefaults"><el-option v-for="item in documentTypes" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="往来单位" required><PartnerRemoteSelect v-model="createForm.partnerId" :type="partnerRole" /></el-form-item>
        <div class="form-grid form-grid-2"><el-form-item label="业务日期" required><el-date-picker v-model="createForm.businessDate" value-format="YYYY-MM-DD" :clearable="false" /></el-form-item><el-form-item label="到期日" required><el-date-picker v-model="createForm.dueDate" value-format="YYYY-MM-DD" :clearable="false" /></el-form-item></div>
        <el-form-item label="费用/收入科目" required><el-select v-model="createForm.offsetAccountId" filterable><el-option v-for="item in availableOffsetAccounts" :key="item.id" :label="`${item.accountCode} ${item.accountName}`" :value="item.id" /></el-select></el-form-item>
        <div class="form-grid form-grid-2"><el-form-item label="价格口径" required><el-segmented v-model="createForm.priceIncludesTax" :options="[{ label: '含税金额', value: true }, { label: '未税金额', value: false }]" /></el-form-item><el-form-item label="税率"><el-input-number v-model="createForm.taxRate" :min="0" :max="100" :precision="4" :controls="false"><template #suffix>%</template></el-input-number></el-form-item></div>
        <el-form-item v-if="createForm.documentType === 'PURCHASE_LANDED_COST'" label="进项税处理" required>
          <el-radio-group v-model="createForm.inputTaxTreatment">
            <el-radio-button value="DEDUCTIBLE">可抵扣，不计成本</el-radio-button>
            <el-radio-button value="INCLUDED_IN_COST">不抵扣，计入成本</el-radio-button>
          </el-radio-group>
          <div class="field-help">一般纳税人且用途允许抵扣时选前者；小规模纳税人或明确不抵扣时通常选后者，最终由企业会计复核。</div>
        </el-form-item>
        <el-form-item :label="createForm.priceIncludesTax ? '含税金额' : '未税金额'" required><el-input-number v-model="createForm.amount" :min="0" :precision="2" :controls="false" /></el-form-item>
        <div class="tax-preview"><span>未税 {{ money(taxPreview.amountExcludingTax) }}</span><span>税额 {{ money(taxPreview.taxAmount) }}</span><strong>价税合计 {{ money(taxPreview.totalAmount) }}</strong><strong v-if="createForm.documentType === 'PURCHASE_LANDED_COST'">计入成本 {{ money(capitalizablePreview) }}</strong></div>
        <el-form-item label="业务依据" required><el-input v-model="createForm.basisNo" maxlength="128" placeholder="合同、账单、结算单或其他依据编号" /></el-form-item>
        <el-form-item label="摘要" required><el-input v-model="createForm.summary" maxlength="255" placeholder="例如：7月仓库房租、装卸费、利息收入" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="createForm.memo" type="textarea" :rows="3" maxlength="500" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="createVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveDocument">保存草稿</el-button></template>
    </el-dialog>

    <el-dialog v-model="transferVisible" title="内部账户转账" width="660px" destroy-on-close>
      <el-alert type="warning" :closable="false" show-icon title="内部转账不属于收入或支出。系统会生成转出、转入两条日记账，两个银行账户分别对账。" />
      <el-form label-width="112px" class="dialog-form">
        <el-form-item label="转出账户" required><el-select v-model="transferForm.sourceBankAccountId" filterable><el-option v-for="item in enabledBankAccounts" :key="item.id" :label="`${item.accountName} ${item.maskedAccountNo}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="转入账户" required><el-select v-model="transferForm.destinationBankAccountId" filterable><el-option v-for="item in enabledBankAccounts" :key="item.id" :label="`${item.accountName} ${item.maskedAccountNo}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="转账日期" required><el-date-picker v-model="transferForm.transactionDate" value-format="YYYY-MM-DD" :clearable="false" /></el-form-item>
        <el-form-item label="转账金额" required><el-input-number v-model="transferForm.amount" :min="0" :precision="2" :controls="false" /></el-form-item>
        <el-form-item label="转账用途" required><el-input v-model="transferForm.purpose" type="textarea" :rows="3" maxlength="500" placeholder="写明账户调拨原因和银行回单编号" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="transferVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveTransfer">确认转账</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type TreasuryDocumentRow, type TreasuryDocumentType } from '@/api/client'
import { currentDateText, formatDateColumn, money } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import { useAppStore } from '@/stores/app'
import { relatedDetailRoute } from '@/utils/navigation'
import { grossFromEntered, splitGross } from '@/utils/taxPricing'
import { resolveRiskException } from '@/utils/riskException'

const router = useRouter()
const route = useRoute()
const queryClient = useQueryClient()
const appStore = useAppStore()
const activeTab = ref('documents')
const createVisible = ref(false)
const transferVisible = ref(false)
const saving = ref(false)
const canManage = computed(() => appStore.hasPermission('treasury.document.manage'))
const canFund = computed(() => appStore.hasPermission('treasury.fund.manage'))
const canRequest = computed(() => appStore.hasPermission('treasury.payment.request'))
const documentsQuery = useQuery({ queryKey: ['treasury-documents'], queryFn: () => api.treasuryDocuments() })
const dashboardQuery = useQuery({ queryKey: ['treasury-dashboard'], queryFn: api.treasuryDashboard })
const requestsQuery = useQuery({ queryKey: ['treasury-payment-requests'], queryFn: () => api.treasuryPaymentRequests() })
const accountsQuery = useQuery({ queryKey: ['accounting-accounts'], queryFn: () => api.accountingAccounts(false) })
const banksQuery = useQuery({ queryKey: ['business-bank-accounts'], queryFn: () => api.businessBankAccounts(false) })
const dashboard = computed(() => dashboardQuery.data.value)
const filters = reactive({ keyword: '', type: '' as TreasuryDocumentType | '', status: '', partnerId: undefined as number | undefined })
const createForm = reactive({ documentType: 'CORPORATE_EXPENSE' as TreasuryDocumentType, partnerId: undefined as number | undefined, businessDate: currentDateText(), dueDate: currentDateText(), basisNo: '', summary: '', offsetAccountId: undefined as number | undefined, priceIncludesTax: true, taxRate: 0, amount: 0, memo: '', inputTaxTreatment: 'DEDUCTIBLE' as 'DEDUCTIBLE' | 'INCLUDED_IN_COST' })
const transferForm = reactive({ sourceBankAccountId: undefined as number | undefined, destinationBankAccountId: undefined as number | undefined, amount: 0, transactionDate: currentDateText(), purpose: '' })
const documentTypes = [{ value: 'CORPORATE_EXPENSE', label: '对公费用' }, { value: 'PURCHASE_LANDED_COST', label: '采购附加费用' }, { value: 'OTHER_EXPENSE', label: '其他支出' }, { value: 'OTHER_INCOME', label: '其他收入' }, { value: 'OTHER_RECEIVABLE', label: '其他应收' }, { value: 'OTHER_PAYABLE', label: '其他应付' }] as const
const documentStatuses = [{ value: 'DRAFT', label: '草稿' }, { value: 'CONFIRMED', label: '已确认' }, { value: 'PARTIALLY_SETTLED', label: '部分结算' }, { value: 'SETTLED', label: '已结清' }, { value: 'CANCELLED', label: '已作废' }, { value: 'REVERSED', label: '已冲正' }]
const outgoing = computed(() => ['CORPORATE_EXPENSE', 'OTHER_EXPENSE', 'OTHER_PAYABLE', 'PURCHASE_LANDED_COST'].includes(createForm.documentType))
const partnerRole = computed<'CUSTOMER' | 'SUPPLIER'>(() => outgoing.value ? 'SUPPLIER' : 'CUSTOMER')
const availableOffsetAccounts = computed(() => (accountsQuery.data.value ?? []).filter((item) => item.leaf && item.status === 'ENABLED' && (createForm.documentType === 'PURCHASE_LANDED_COST' ? item.accountCode === '5401' : outgoing.value ? ['EXPENSE', 'COST'].includes(item.category) : item.category === 'REVENUE')))
const enabledBankAccounts = computed(() => (banksQuery.data.value ?? []).filter((item) => item.status === 'ENABLED'))
const taxPreview = computed(() => splitGross(grossFromEntered(createForm.amount, createForm.priceIncludesTax, createForm.taxRate), createForm.taxRate))
const capitalizablePreview = computed(() => createForm.inputTaxTreatment === 'INCLUDED_IN_COST' ? taxPreview.value.totalAmount : taxPreview.value.amountExcludingTax)
const filteredDocuments = computed(() => (documentsQuery.data.value ?? []).filter((row) => {
  const keyword = filters.keyword.trim().toLowerCase()
  return (!keyword || [row.documentNo, row.partnerName, row.summary, row.basisNo].some((value) => value.toLowerCase().includes(keyword)))
    && (!filters.type || row.documentType === filters.type) && (!filters.status || row.status === filters.status)
    && (!filters.partnerId || row.partnerId === filters.partnerId)
}))

function openCreate(type: TreasuryDocumentType = 'CORPORATE_EXPENSE') { Object.assign(createForm, { documentType: type, partnerId: undefined, businessDate: currentDateText(), dueDate: currentDateText(), basisNo: '', summary: type === 'PURCHASE_LANDED_COST' ? '采购运输、装卸或检验附加费用' : '', offsetAccountId: undefined, priceIncludesTax: true, taxRate: 0, amount: 0, memo: '', inputTaxTreatment: 'DEDUCTIBLE' }); applyDocumentDefaults(); createVisible.value = true }
function applyDocumentDefaults() { createForm.partnerId = undefined; createForm.offsetAccountId = createForm.documentType === 'PURCHASE_LANDED_COST' ? availableOffsetAccounts.value[0]?.id : undefined; if (createForm.documentType !== 'PURCHASE_LANDED_COST') createForm.inputTaxTreatment = 'DEDUCTIBLE' }
function openTransfer() { Object.assign(transferForm, { sourceBankAccountId: undefined, destinationBankAccountId: undefined, amount: 0, transactionDate: currentDateText(), purpose: '' }); transferVisible.value = true }
function resetFilters() { filters.keyword = ''; filters.type = ''; filters.status = ''; filters.partnerId = undefined }
function detailRoute(id: number) { return relatedDetailRoute(route, `/treasury/documents/${id}`) }
function isOverdue(row: TreasuryDocumentRow) { return row.outstandingAmount > 0 && row.dueDate < currentDateText() && !['CANCELLED', 'REVERSED'].includes(row.status) }
async function refresh() { await Promise.all([documentsQuery.refetch(), requestsQuery.refetch(), dashboardQuery.refetch(), queryClient.invalidateQueries({ queryKey: ['treasury-journal'] })]) }
async function saveDocument() {
  if (!createForm.partnerId || !createForm.offsetAccountId || createForm.amount <= 0 || !createForm.basisNo.trim() || !createForm.summary.trim()) { ElMessage.warning('请完整填写业务类型、往来单位、日期、科目、金额、依据和摘要'); return }
  if (createForm.dueDate < createForm.businessDate) { ElMessage.warning('到期日不能早于业务日期'); return }
  saving.value = true
  try { const result = await api.createTreasuryDocument({ ...createForm, partnerId: createForm.partnerId, offsetAccountId: createForm.offsetAccountId, basisNo: createForm.basisNo.trim(), summary: createForm.summary.trim(), memo: createForm.memo.trim() }); createVisible.value = false; ElMessage.success('业务源单草稿已保存，尚未影响往来和资金'); await refresh(); await router.push(`/treasury/documents/${result.id}`) }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '源单保存失败') } finally { saving.value = false }
}
async function saveTransfer() {
  if (!transferForm.sourceBankAccountId || !transferForm.destinationBankAccountId || transferForm.sourceBankAccountId === transferForm.destinationBankAccountId || transferForm.amount <= 0 || transferForm.purpose.trim().length < 4) { ElMessage.warning('请选择不同的转出、转入账户并填写金额和具体用途'); return }
  saving.value = true
  try { await api.createTreasuryTransfer({ ...transferForm, sourceBankAccountId: transferForm.sourceBankAccountId, destinationBankAccountId: transferForm.destinationBankAccountId, purpose: transferForm.purpose.trim() }); transferVisible.value = false; ElMessage.success('内部转账已登记，转出和转入日记账均已生成'); await refresh(); await router.push('/treasury/journal') }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '内部转账失败') } finally { saving.value = false }
}
async function approveRequest(id: number) { const ok = await ElMessageBox.confirm('批准只表示允许付款，不会自动扣减银行资金。实际付款仍需在来源单详情登记。', '批准付款申请', { type: 'warning' }).then(() => true).catch(() => false); if (!ok) return; const authorization = await resolveRiskException('TREASURY_PAYMENT_REQUEST', id, 'APPROVE', '批准大额付款申请'); if (!authorization.allowed) return; await api.approveTreasuryPaymentRequest(id, authorization); ElMessage.success('付款申请已批准，尚未发生资金支付'); await refresh() }
async function rejectRequest(id: number) { const result = await ElMessageBox.prompt('请输入退回原因', '退回付款申请', { inputValidator: (value) => String(value || '').trim().length >= 4 || '原因至少4个字' }).catch(() => null); if (!result) return; await api.rejectTreasuryPaymentRequest(id, result.value.trim()); ElMessage.success('付款申请已退回'); await refresh() }
function documentTypeName(value: string) { return documentTypes.find((item) => item.value === value)?.label ?? value }
function statusName(value: string) { return ({ DRAFT: '草稿', CONFIRMED: '已确认', PARTIALLY_SETTLED: '部分结算', SETTLED: '已结清', CANCELLED: '已作废', REVERSED: '已冲正' } as Record<string, string>)[value] ?? value }
function statusType(value: string) { return value === 'SETTLED' ? 'success' : value === 'PARTIALLY_SETTLED' ? 'warning' : value === 'DRAFT' ? 'info' : ['CANCELLED', 'REVERSED'].includes(value) ? 'info' : 'primary' }
function requestStatusName(value: string) { return ({ DRAFT: '待批准', APPROVED: '已批准', PARTIALLY_PAID: '部分支付', PAID: '已支付', REJECTED: '已退回', CANCELLED: '已作废' } as Record<string, string>)[value] ?? value }
function requestStatusType(value: string) { return value === 'PAID' ? 'success' : ['APPROVED', 'PARTIALLY_PAID'].includes(value) ? 'warning' : ['REJECTED', 'CANCELLED'].includes(value) ? 'info' : 'primary' }
watch(availableOffsetAccounts, (items) => { if (createForm.documentType === 'PURCHASE_LANDED_COST' && !createForm.offsetAccountId) createForm.offsetAccountId = items[0]?.id }, { immediate: true })
onMounted(() => { if (route.query.createType === 'PURCHASE_LANDED_COST') openCreate('PURCHASE_LANDED_COST') })
</script>

<style scoped>
.header-actions { display: flex; flex-wrap: wrap; gap: 10px; }
.summary-grid { display: grid; grid-template-columns: repeat(4,minmax(150px,1fr)); gap: 12px; padding: 0 18px 18px; }
.summary-card { display: grid; gap: 4px; padding: 14px 16px; border: 1px solid #dfe6ec; border-radius: 10px; background: #fafcfd; }
.summary-card span,.summary-card small { color: #74818c; }.summary-card strong { font-size: 22px; }.summary-card.warning { background:#fffaf2 }.summary-card.danger { background:#fff7f5 }
.filters { grid-template-columns: minmax(220px,1.2fr) 155px 145px minmax(180px,1fr) auto; }.tab-alert { margin-bottom:14px }.dialog-form { margin-top:18px }.dialog-form :deep(.el-select),.dialog-form :deep(.el-input-number),.dialog-form :deep(.el-date-editor),.dialog-form :deep(.el-segmented) { width:100% }
.tax-preview { display:flex; justify-content:flex-end; gap:20px; margin:-4px 0 18px; padding:10px 14px; border-radius:8px; background:#f6f8fa; color:#65727d }.tax-preview strong { color:#c85d30 }.amount-warn { color:#c56a16;font-weight:650 }.amount-danger { color:#c5483c;font-weight:650 }
.field-help{width:100%;margin-top:6px;color:#77838d;font-size:12px;line-height:1.5}
@media(max-width:1000px){.summary-grid{grid-template-columns:repeat(2,1fr)}.filters{grid-template-columns:1fr 1fr}}@media(max-width:650px){.summary-grid,.filters{grid-template-columns:1fr}}
</style>
