<template>
  <div class="page" v-loading="query.isLoading.value">
    <template v-if="detail">
      <div class="panel">
        <div class="panel-header detail-header">
          <div><div class="title-line"><div class="panel-title">{{ documentTypeName(header.documentType) }} {{ header.documentNo }}</div><el-tag :type="statusType(header.status)">{{ statusName(header.status) }}</el-tag></div><div class="muted">{{ header.partnerName }} · {{ header.summary }}</div></div>
          <div class="header-actions">
            <el-button @click="router.push('/treasury')">返回列表</el-button>
            <el-button v-if="header.status === 'DRAFT' && canManage" @click="cancelDocument">作废草稿</el-button>
            <el-button v-if="header.status === 'DRAFT' && canConfirm" type="primary" :loading="acting" @click="confirmDocument">确认业务事实</el-button>
            <el-button v-if="header.documentType === 'PURCHASE_LANDED_COST' && canLanded && ['CONFIRMED','PARTIALLY_SETTLED','SETTLED'].includes(header.status)" type="primary" plain @click="router.push({ path: '/inventory/landed-cost-allocations/new', query: { sourceId: header.id, from: route.fullPath } })">编制费用分摊</el-button>
            <el-button v-if="canRequest && outgoing && ['CONFIRMED','PARTIALLY_SETTLED'].includes(header.status)" @click="openRequest">申请付款</el-button>
            <el-button v-if="canFund && ['CONFIRMED','PARTIALLY_SETTLED'].includes(header.status)" type="primary" @click="openSettlement">{{ outgoing ? '登记实际付款' : '登记实际收款' }}</el-button>
            <el-button v-if="canReverse && !openingDocument && ['CONFIRMED','PARTIALLY_SETTLED','SETTLED'].includes(header.status)" type="danger" plain @click="reverseDocument">冲正源单</el-button>
          </div>
        </div>
      </div>

      <el-alert v-if="header.status === 'DRAFT'" type="warning" :closable="false" show-icon title="草稿尚未形成其他应收/应付、资金或会计事实。" />
      <el-alert v-else-if="header.status === 'REVERSED'" type="info" :closable="false" show-icon :title="`源单已冲正：${header.reversalReason}`" />
      <el-alert v-else-if="openingDocument" type="success" :closable="false" show-icon title="该往来来自期初迁移。确认切换前只能在期初迁移工作台整批撤销；切换后必须通过调整或冲正保留历史。" />
      <el-alert v-else type="success" :closable="false" show-icon title="业务事实和价税快照已冻结；收付款、发票和凭证分别追溯，错误必须通过冲正处理。" />

      <div class="detail-grid">
        <div class="panel">
          <div class="section-title">业务与价税事实</div>
          <el-descriptions :column="2" border class="fixed-detail-descriptions" label-width="130px">
            <el-descriptions-item label="业务类型">{{ documentTypeName(header.documentType) }}</el-descriptions-item><el-descriptions-item label="往来单位">{{ header.partnerName }}</el-descriptions-item>
            <el-descriptions-item label="业务日期">{{ header.businessDate }}</el-descriptions-item><el-descriptions-item label="到期日"><span :class="header.outstandingAmount > 0 && header.dueDate < today ? 'amount-danger' : ''">{{ header.dueDate }}</span></el-descriptions-item>
            <el-descriptions-item label="业务依据">{{ header.basisNo }}</el-descriptions-item><el-descriptions-item label="费用/收入科目">{{ header.offsetAccountCode }} {{ header.offsetAccountName }}</el-descriptions-item>
            <el-descriptions-item label="价格口径">{{ header.priceIncludesTax ? '含税金额' : '未税金额' }}</el-descriptions-item><el-descriptions-item label="税率">{{ header.taxRate }}%</el-descriptions-item>
            <el-descriptions-item label="未税金额">{{ money(header.amountExcludingTax) }}</el-descriptions-item><el-descriptions-item label="税额">{{ money(header.taxAmount) }}</el-descriptions-item>
            <el-descriptions-item label="价税合计"><strong>{{ money(header.totalAmount) }}</strong></el-descriptions-item><el-descriptions-item label="舍入差额">{{ money(header.taxRoundingDifference) }}</el-descriptions-item>
            <el-descriptions-item v-if="header.documentType === 'PURCHASE_LANDED_COST'" label="进项税处理" :span="2">{{ header.inputTaxTreatment === 'INCLUDED_IN_COST' ? '不抵扣，税额计入存货成本' : '可抵扣，税额不计入存货成本' }}</el-descriptions-item>
            <el-descriptions-item label="税务规则快照" :span="2">{{ header.taxRuleSnapshot }}</el-descriptions-item><el-descriptions-item label="摘要" :span="2">{{ header.summary }}</el-descriptions-item><el-descriptions-item label="备注" :span="2">{{ header.memo || '-' }}</el-descriptions-item>
          </el-descriptions>
        </div>
        <div class="panel">
          <div class="section-title">结算与会计</div>
          <div class="amount-summary"><div><span>价税合计</span><strong>{{ money(header.totalAmount) }}</strong></div><div><span>已收付</span><strong>{{ money(header.settledAmount) }}</strong></div><div class="outstanding"><span>未结金额</span><strong>{{ money(header.outstandingAmount) }}</strong></div></div>
          <el-descriptions :column="1" border class="fixed-detail-descriptions" label-width="132px">
            <el-descriptions-item label="编制人">{{ header.createdByName }} · {{ formatDateTime(header.createdAt) }}</el-descriptions-item>
            <el-descriptions-item label="确认人">{{ header.confirmedByName || '-' }}<span v-if="header.confirmedAt"> · {{ formatDateTime(header.confirmedAt) }}</span></el-descriptions-item>
            <el-descriptions-item label="业务凭证"><router-link v-if="header.accountingVoucherId" class="table-link" :to="`/accounting/vouchers/${header.accountingVoucherId}`">{{ header.accountingVoucherNo }} · {{ voucherStatusName(header.accountingVoucherStatus) }}</router-link><span v-else>-</span></el-descriptions-item>
          </el-descriptions>
          <div class="boundary-note">{{ header.documentType === 'PURCHASE_LANDED_COST' ? `确认源单先形成其他应付；费用分摊按冻结口径计入${header.inputTaxTreatment === 'INCLUDED_IN_COST' ? '价税合计' : '未税净额'}。付款、发票和库存成本各自留痕，不能互相替代。` : '确认源单只形成其他应收/应付；付款申请只形成授权；实际收付款才进入出纳日记账。发票仍需在发票工作台分别勾稽“业务事实、往来和资金”。' }}</div>
        </div>
      </div>

      <div class="panel">
        <div class="panel-header"><div><div class="panel-title">实际资金</div><div class="muted">一张源单可分次收付；资金冲正前必须先解除银行对账。</div></div><el-button @click="router.push('/treasury/journal')">查看统一日记账</el-button></div>
        <el-table :data="detail.funds" class="data-table" size="small" border show-overflow-tooltip>
          <el-table-column prop="fundNo" label="资金单号" min-width="190" /><el-table-column prop="transactionDate" label="日期" width="112" />
          <el-table-column label="方向" width="90"><template #default="{ row }">{{ row.direction === 'DEBIT' ? '支出' : '收入' }}</template></el-table-column>
          <el-table-column label="账户" min-width="160"><template #default="{ row }">{{ row.settlementMethod === 'CASH' ? '库存现金' : row.bankAccountName }}</template></el-table-column>
          <el-table-column label="金额" width="130" align="right"><template #default="{ row }"><strong>{{ money(row.amount) }}</strong></template></el-table-column>
          <el-table-column prop="purpose" label="用途" min-width="220" /><el-table-column label="凭证" min-width="145"><template #default="{ row }"><router-link v-if="row.accountingVoucherId" class="table-link" :to="`/accounting/vouchers/${row.accountingVoucherId}`">{{ row.accountingVoucherNo }}</router-link></template></el-table-column>
          <el-table-column label="状态" width="92"><template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">{{ row.status === 'ACTIVE' ? '有效' : '已冲正' }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button v-if="row.status === 'ACTIVE' && canReverse" link type="danger" @click="reverseFund(row.id)">冲正</el-button></template></el-table-column>
        </el-table>
        <el-empty v-if="detail.funds.length === 0" description="尚未发生实际收付款" />
      </div>

      <div v-if="outgoing" class="panel">
        <div class="panel-header"><div><div class="panel-title">付款申请</div><div class="muted">老板可直接付款，也可以先申请再批准；申请不会改变资金。</div></div></div>
        <el-table :data="detail.paymentRequests" class="data-table" size="small" border show-overflow-tooltip>
          <el-table-column prop="requestNo" label="申请单号" min-width="190" /><el-table-column prop="requestDate" label="申请日期" width="112" /><el-table-column prop="expectedPaymentDate" label="预计付款" width="112" />
          <el-table-column label="申请金额" width="130" align="right"><template #default="{ row }">{{ money(row.requestedAmount) }}</template></el-table-column><el-table-column label="未支付" width="130" align="right"><template #default="{ row }">{{ money(row.unpaidAmount) }}</template></el-table-column>
          <el-table-column prop="purpose" label="用途" min-width="220" /><el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="requestStatusType(row.status)" size="small">{{ requestStatusName(row.status) }}</el-tag></template></el-table-column>
        </el-table>
      </div>

      <div class="panel"><div class="panel-header"><div><div class="panel-title">状态与审计轨迹</div><div class="muted">原单、资金和冲正事件只追加、不覆盖。</div></div></div><el-table :data="detail.events" class="data-table" size="small" border><el-table-column prop="createdAt" label="时间" width="176" :formatter="formatDateTimeColumn" /><el-table-column label="动作" width="150"><template #default="{ row }">{{ eventActionName(row.action) }}</template></el-table-column><el-table-column label="状态变化" width="200"><template #default="{ row }">{{ row.fromStatus ? statusName(row.fromStatus) : '建立' }} → {{ statusName(row.toStatus) }}</template></el-table-column><el-table-column prop="operatorName" label="操作人" width="140" /><el-table-column prop="reason" label="说明" min-width="260" /></el-table></div>
      <EvidenceAttachments document-type="TREASURY_DOCUMENT" :document-id="header.id" title="合同、账单、回单和费用依据" default-evidence-type="ORIGINAL_DOCUMENT" />
    </template>

    <el-dialog v-model="settlementVisible" :title="outgoing ? '登记实际付款' : '登记实际收款'" width="650px" destroy-on-close>
      <el-alert type="warning" :closable="false" show-icon :title="`${outgoing ? '付款' : '收款'}会立即形成真实资金、出纳日记账和草稿凭证；失败不会改变源单未结金额。`" />
      <el-form label-width="112px" class="dialog-form">
        <el-form-item v-if="outgoing && approvedRequests.length" label="付款申请"><el-select v-model="settlementForm.paymentRequestId" clearable placeholder="可选；直接付款无需先申请"><el-option v-for="item in approvedRequests" :key="item.id" :label="`${item.requestNo} · 未支付 ${money(item.unpaidAmount)}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="金额" required><el-input-number v-model="settlementForm.amount" :min="0" :max="settlementMax" :precision="2" :controls="false" /></el-form-item>
        <el-form-item label="收付日期" required><el-date-picker v-model="settlementForm.transactionDate" value-format="YYYY-MM-DD" :clearable="false" /></el-form-item>
        <el-form-item label="结算方式" required><el-segmented v-model="settlementForm.settlementMethod" :options="[{ label: '银行', value: 'BANK' }, { label: '现金', value: 'CASH' }]" /></el-form-item>
        <el-form-item v-if="settlementForm.settlementMethod === 'BANK'" label="银行账户" required><el-select v-model="settlementForm.businessBankAccountId" filterable><el-option v-for="item in bankAccounts" :key="item.id" :label="`${item.accountName} ${item.maskedAccountNo}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="现金流分类" required><el-select v-model="settlementForm.cashFlowCategory"><el-option v-for="item in cashFlowOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="收付用途" required><el-input v-model="settlementForm.purpose" type="textarea" :rows="3" maxlength="500" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="settlementVisible = false">取消</el-button><el-button type="primary" :loading="acting" @click="saveSettlement">确认登记</el-button></template>
    </el-dialog>

    <el-dialog v-model="requestVisible" title="新建付款申请" width="620px" destroy-on-close>
      <el-alert type="info" :closable="false" show-icon title="付款申请获批后仍不会自动支付；实际付款必须另行登记。" />
      <el-form label-width="116px" class="dialog-form"><el-form-item label="申请金额" required><el-input-number v-model="requestForm.requestedAmount" :min="0" :max="header?.outstandingAmount || 0" :precision="2" :controls="false" /></el-form-item><el-form-item label="申请日期" required><el-date-picker v-model="requestForm.requestDate" value-format="YYYY-MM-DD" :clearable="false" /></el-form-item><el-form-item label="预计付款日" required><el-date-picker v-model="requestForm.expectedPaymentDate" value-format="YYYY-MM-DD" :clearable="false" /></el-form-item><el-form-item label="付款用途" required><el-input v-model="requestForm.purpose" type="textarea" :rows="3" maxlength="500" /></el-form-item></el-form>
      <template #footer><el-button @click="requestVisible = false">取消</el-button><el-button type="primary" :loading="acting" @click="saveRequest">保存申请</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import { currentDateText, formatDateTime, formatDateTimeColumn, money } from '@/api/format'
import EvidenceAttachments from '@/components/EvidenceAttachments.vue'
import { useAppStore } from '@/stores/app'
import { resolveRiskException } from '@/utils/riskException'

const route = useRoute(); const router = useRouter(); const queryClient = useQueryClient(); const appStore = useAppStore(); const id = Number(route.params.id); const today = currentDateText()
const acting = ref(false); const settlementVisible = ref(false); const requestVisible = ref(false)
const query = useQuery({ queryKey: ['treasury-document', id], queryFn: () => api.treasuryDocument(id), enabled: id > 0 })
const banksQuery = useQuery({ queryKey: ['business-bank-accounts'], queryFn: () => api.businessBankAccounts(false) })
const detail = computed(() => query.data.value); const header = computed(() => detail.value!.header)
const outgoing = computed(() => ['CORPORATE_EXPENSE','OTHER_EXPENSE','OTHER_PAYABLE','PURCHASE_LANDED_COST','OPENING_PAYABLE'].includes(header.value.documentType))
const openingDocument = computed(() => header.value.documentType.startsWith('OPENING_'))
const canManage = computed(() => appStore.hasPermission('treasury.document.manage')); const canConfirm = computed(() => appStore.hasPermission('treasury.document.confirm')); const canRequest = computed(() => appStore.hasPermission('treasury.payment.request')); const canFund = computed(() => appStore.hasPermission('treasury.fund.manage')); const canReverse = computed(() => appStore.hasPermission('treasury.reverse')); const canLanded = computed(() => appStore.hasPermission('landed.cost.manage'))
const bankAccounts = computed(() => (banksQuery.data.value ?? []).filter((item) => item.status === 'ENABLED'))
const approvedRequests = computed(() => detail.value?.paymentRequests.filter((item) => ['APPROVED','PARTIALLY_PAID'].includes(item.status)) ?? [])
const settlementForm = reactive({ paymentRequestId: undefined as number | undefined, amount: 0, transactionDate: today, settlementMethod: 'BANK' as 'BANK'|'CASH', businessBankAccountId: undefined as number | undefined, cashFlowCategory: '', purpose: '' })
const requestForm = reactive({ requestedAmount: 0, requestDate: today, expectedPaymentDate: today, purpose: '' })
const selectedRequest = computed(() => approvedRequests.value.find((item) => item.id === settlementForm.paymentRequestId)); const settlementMax = computed(() => Math.min(header.value.outstandingAmount, selectedRequest.value?.unpaidAmount ?? header.value.outstandingAmount))
const cashFlowOptions = computed(() => outgoing.value ? [{ value:'PURCHASE_PAYMENT',label:'购买商品/服务支付'},{value:'EMPLOYEE_PAYMENT',label:'支付给职工'},{value:'TAX_PAYMENT',label:'支付税费'},{value:'BANK_FEE',label:'银行手续费'},{value:'OTHER_OPERATING_OUT',label:'其他经营支出'},{value:'INVESTING_OUT',label:'投资支出'},{value:'FINANCING_OUT',label:'筹资支出'}] : [{value:'SALES_COLLECTION',label:'销售收款'},{value:'OTHER_OPERATING_IN',label:'其他经营收入'},{value:'INVESTING_IN',label:'投资收入'},{value:'FINANCING_IN',label:'筹资收入'}])
async function refresh(){await Promise.all([query.refetch(),queryClient.invalidateQueries({queryKey:['treasury-documents']}),queryClient.invalidateQueries({queryKey:['treasury-dashboard']}),queryClient.invalidateQueries({queryKey:['treasury-payment-requests']}),queryClient.invalidateQueries({queryKey:['treasury-journal']})])}
async function run(task:()=>Promise<unknown>,success:string){acting.value=true;try{await task();ElMessage.success(success);await refresh()}catch(error){ElMessage.error(error instanceof Error?error.message:'操作失败')}finally{acting.value=false}}
async function confirmDocument(){const ok=await ElMessageBox.confirm('确认后将形成其他应收/应付和草稿凭证，价税快照冻结；不会自动收付款或开票。','确认业务事实',{type:'warning'}).then(()=>true).catch(()=>false);if(ok)await run(()=>api.confirmTreasuryDocument(id),'业务源单已确认')}
async function cancelDocument(){const r=await promptReason('作废源单草稿');if(r)await run(()=>api.cancelTreasuryDocument(id,r),'源单草稿已作废')}
async function reverseDocument(){const r=await promptReason('冲正业务源单');if(!r)return;const authorization=await resolveRiskException('TREASURY_DOCUMENT',id,'REVERSE','冲正业务源单',r);if(authorization.allowed)await run(()=>api.reverseTreasuryDocument(id,{reason:r,businessDate:today,approvalId:authorization.approvalId}),'业务源单已冲正')}
async function reverseFund(fundId:number){const r=await promptReason('冲正实际资金');if(!r)return;const authorization=await resolveRiskException('TREASURY_FUND',fundId,'REVERSE','冲正实际资金',r);if(authorization.allowed)await run(()=>api.reverseTreasuryFund(fundId,{reason:r,businessDate:today,approvalId:authorization.approvalId}),'资金及日记账已冲正')}
function openSettlement(){Object.assign(settlementForm,{paymentRequestId:undefined,amount:header.value.outstandingAmount,transactionDate:today,settlementMethod:'BANK',businessBankAccountId:undefined,cashFlowCategory:outgoing.value?'OTHER_OPERATING_OUT':'OTHER_OPERATING_IN',purpose:header.value.summary});settlementVisible.value=true}
async function saveSettlement(){if(settlementForm.amount<=0||settlementForm.amount>settlementMax.value||!settlementForm.cashFlowCategory||settlementForm.purpose.trim().length<4||(settlementForm.settlementMethod==='BANK'&&!settlementForm.businessBankAccountId)){ElMessage.warning('请完整填写有效金额、日期、账户、现金流分类和用途');return}await run(()=>api.settleTreasuryDocument(id,{...settlementForm,businessBankAccountId:settlementForm.settlementMethod==='BANK'?settlementForm.businessBankAccountId:undefined,purpose:settlementForm.purpose.trim()}),`实际${outgoing.value?'付款':'收款'}已登记`);settlementVisible.value=false}
function openRequest(){Object.assign(requestForm,{requestedAmount:header.value.outstandingAmount,requestDate:today,expectedPaymentDate:header.value.dueDate,purpose:header.value.summary});requestVisible.value=true}
async function saveRequest(){if(requestForm.requestedAmount<=0||requestForm.requestedAmount>header.value.outstandingAmount||requestForm.expectedPaymentDate<requestForm.requestDate||requestForm.purpose.trim().length<4){ElMessage.warning('请填写有效申请金额、日期和具体用途');return}await run(()=>api.createTreasuryPaymentRequest({documentId:id,...requestForm,purpose:requestForm.purpose.trim()}),'付款申请已保存，尚未发生资金支付');requestVisible.value=false}
async function promptReason(title:string){const r=await ElMessageBox.prompt('请输入具体原因，原记录将继续保留。',title,{type:'warning',inputValidator:(v)=>String(v||'').trim().length>=4||'原因至少4个字'}).catch(()=>null);return r?.value.trim()||''}
function documentTypeName(v:string){return({CORPORATE_EXPENSE:'对公费用',PURCHASE_LANDED_COST:'采购附加费用',OTHER_EXPENSE:'其他支出',OTHER_INCOME:'其他收入',OTHER_RECEIVABLE:'其他应收',OTHER_PAYABLE:'其他应付',OPENING_RECEIVABLE:'期初应收',OPENING_PAYABLE:'期初应付'}as Record<string,string>)[v]||v}function statusName(v:string){return({DRAFT:'草稿',CONFIRMED:'已确认',PARTIALLY_SETTLED:'部分结算',SETTLED:'已结清',CANCELLED:'已作废',REVERSED:'已冲正'}as Record<string,string>)[v]||v}function statusType(v:string){return v==='SETTLED'?'success':v==='PARTIALLY_SETTLED'?'warning':v==='DRAFT'?'info':['CANCELLED','REVERSED'].includes(v)?'info':'primary'}function voucherStatusName(v:string){return({DRAFT:'草稿',REVIEWED:'已审核',POSTED:'已记账',CANCELLED:'已作废',REVERSED:'已冲销'}as Record<string,string>)[v]||v}function requestStatusName(v:string){return({DRAFT:'待批准',APPROVED:'已批准',PARTIALLY_PAID:'部分支付',PAID:'已支付',REJECTED:'已退回',CANCELLED:'已作废'}as Record<string,string>)[v]||v}function requestStatusType(v:string){return v==='PAID'?'success':['APPROVED','PARTIALLY_PAID'].includes(v)?'warning':['REJECTED','CANCELLED'].includes(v)?'info':'primary'}
function eventActionName(v:string){return({MIGRATION_APPLY:'期初迁移应用',CONFIRM:'确认业务事实',CANCEL:'作废草稿',REVERSE:'冲正源单'}as Record<string,string>)[v]||v}
</script>

<style scoped>
.detail-header,.title-line,.header-actions{display:flex;align-items:center;gap:10px}.detail-header{width:100%;justify-content:space-between}.header-actions{flex-wrap:wrap;justify-content:flex-end}.detail-grid{display:grid;grid-template-columns:minmax(0,1.2fr) minmax(350px,.8fr);gap:14px;margin-top:14px}.section-title{margin-bottom:14px;font-size:16px;font-weight:700}.amount-summary{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-bottom:14px}.amount-summary div{display:grid;gap:5px;padding:12px;border-radius:9px;background:#f7f9fb}.amount-summary span{color:#72808b}.amount-summary strong{font-size:17px}.amount-summary .outstanding{background:#fff8f2}.amount-summary .outstanding strong{color:#c85d30}.boundary-note{margin:14px 18px 18px;padding:12px 14px;border-left:3px solid #d46a3a;background:#fff8f4;color:#5f6d78;line-height:1.65}.dialog-form{margin-top:18px}.dialog-form :deep(.el-select),.dialog-form :deep(.el-input-number),.dialog-form :deep(.el-date-editor),.dialog-form :deep(.el-segmented){width:100%}.amount-danger{color:#c5483c;font-weight:650}@media(max-width:1050px){.detail-grid{grid-template-columns:1fr}}@media(max-width:720px){.detail-header{align-items:flex-start;flex-direction:column}.header-actions{justify-content:flex-start}.amount-summary{grid-template-columns:1fr}}
</style>
