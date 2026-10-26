<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div><div class="panel-title">统一出纳日记账</div><div class="muted">销售回款、供应商付款、退款、承兑、费用收付和内部转账按实际资金逐笔汇总；这里不代替银行对账。</div></div>
        <div class="header-actions"><el-button @click="router.push('/treasury')">费用与出纳</el-button><el-button @click="router.push('/accounting/bank-reconciliation')">银行对账</el-button></div>
      </div>
      <div class="summary-grid">
        <div class="summary-card income"><span>本期收入</span><strong>{{ money(summary.credit) }}</strong><small>{{ summary.creditCount }} 笔</small></div>
        <div class="summary-card expense"><span>本期支出</span><strong>{{ money(summary.debit) }}</strong><small>{{ summary.debitCount }} 笔</small></div>
        <div class="summary-card"><span>净流入</span><strong>{{ money(summary.credit - summary.debit) }}</strong><small>内部转账净额为 0</small></div>
        <div class="summary-card warning"><span>银行待对账</span><strong>{{ money(summary.unmatched) }}</strong><small>{{ summary.unmatchedCount }} 笔</small></div>
      </div>
    </div>

    <div class="panel">
      <div class="list-tools filters">
        <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" :clearable="false" @change="refetch" />
        <el-select v-model="filters.bankAccountId" clearable placeholder="账户" @change="refetch"><el-option label="库存现金" :value="-1" /><el-option v-for="item in bankAccounts" :key="item.id" :label="`${item.accountName} ${item.maskedAccountNo}`" :value="item.id" /></el-select>
        <el-select v-model="filters.direction" clearable placeholder="收支方向" @change="refetch"><el-option label="收入" value="CREDIT" /><el-option label="支出" value="DEBIT" /></el-select>
        <el-input v-model="filters.keyword" clearable placeholder="单号 / 往来单位 / 用途" />
        <div class="list-tools-actions"><el-button @click="reset">重置</el-button></div>
      </div>
      <el-alert class="journal-alert" type="info" :closable="false" show-icon title="银行资金只有在银行流水匹配或登记银行未达项后才算完成对账；现金资金不进入银行对账。" />
      <el-table v-loading="journalQuery.isLoading.value" :data="filteredRows" class="data-table" size="small" border show-overflow-tooltip>
        <el-table-column prop="transactionDate" label="日期" width="112" fixed="left" />
        <el-table-column label="来源单号" min-width="190" fixed="left"><template #default="{ row }"><router-link v-if="sourcePath(row)" class="table-link" :to="sourcePath(row)!">{{ row.sourceNo }}</router-link><span v-else>{{ row.sourceNo }}</span><div class="multiline-secondary">{{ sourceTypeName(row.sourceType) }}</div></template></el-table-column>
        <el-table-column label="账户" min-width="170"><template #default="{ row }">{{ row.settlementMethod === 'CASH' ? '库存现金' : row.bankAccountName || '未指定银行账户' }}</template></el-table-column>
        <el-table-column label="收入" width="135" align="right"><template #default="{ row }"><strong v-if="row.direction === 'CREDIT'" class="amount-in">{{ money(row.amount) }}</strong></template></el-table-column>
        <el-table-column label="支出" width="135" align="right"><template #default="{ row }"><strong v-if="row.direction === 'DEBIT'" class="amount-out">{{ money(row.amount) }}</strong></template></el-table-column>
        <el-table-column label="现金流分类" min-width="155"><template #default="{ row }">{{ cashFlowName(row.cashFlowCategory) }}</template></el-table-column>
        <el-table-column prop="partnerName" label="往来单位" min-width="155" />
        <el-table-column prop="purpose" label="用途/摘要" min-width="230" />
        <el-table-column label="对账状态" width="126"><template #default="{ row }"><el-tag v-if="row.settlementMethod === 'CASH'" type="info" size="small">现金无需银行对账</el-tag><el-tag v-else-if="row.unmatchedAmount <= 0" type="success" size="small">已处理</el-tag><el-tag v-else type="warning" size="small">待对账 {{ money(row.unmatchedAmount) }}</el-tag></template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">{{ row.status === 'ACTIVE' ? '有效' : '已冲正' }}</el-tag></template></el-table-column>
      </el-table>
      <el-empty v-if="!journalQuery.isLoading.value && filteredRows.length === 0" description="当前范围没有资金流水" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { api, type TreasuryJournalRow } from '@/api/client'
import { currentDateText, money } from '@/api/format'

const router = useRouter(); const today = currentDateText(); const monthStart = `${today.slice(0,7)}-01`
const dateRange = ref<[string,string]>([monthStart,today]); const filters = reactive({ bankAccountId: undefined as number|undefined, direction: '' as ''|'CREDIT'|'DEBIT', keyword: '' })
const banksQuery = useQuery({queryKey:['business-bank-accounts'],queryFn:()=>api.businessBankAccounts(true)})
const journalQuery = useQuery({queryKey:['treasury-journal',dateRange],queryFn:()=>api.treasuryJournal({from:dateRange.value[0],to:dateRange.value[1]})})
const bankAccounts = computed(()=>banksQuery.data.value??[])
const filteredRows = computed(()=>(journalQuery.data.value??[]).filter((row)=>{
  const keyword=filters.keyword.trim().toLowerCase(); const bankMatch=!filters.bankAccountId||(filters.bankAccountId===-1?row.settlementMethod==='CASH':row.businessBankAccountId===filters.bankAccountId)
  return bankMatch&&(!filters.direction||row.direction===filters.direction)&&(!keyword||[row.sourceNo,row.partnerName,row.purpose].some((value)=>value.toLowerCase().includes(keyword)))
}))
const operatingRows = computed(()=>filteredRows.value.filter((row)=>row.status==='ACTIVE'&&row.cashFlowCategory!=='INTERNAL_TRANSFER'))
const summary = computed(()=>({credit:sum(operatingRows.value.filter((r)=>r.direction==='CREDIT')),debit:sum(operatingRows.value.filter((r)=>r.direction==='DEBIT')),creditCount:operatingRows.value.filter((r)=>r.direction==='CREDIT').length,debitCount:operatingRows.value.filter((r)=>r.direction==='DEBIT').length,unmatched:sum(filteredRows.value.filter((r)=>r.status==='ACTIVE'&&r.settlementMethod==='BANK'&&r.unmatchedAmount>0),'unmatchedAmount'),unmatchedCount:filteredRows.value.filter((r)=>r.status==='ACTIVE'&&r.settlementMethod==='BANK'&&r.unmatchedAmount>0).length}))
function sum(rows:TreasuryJournalRow[],field:'amount'|'unmatchedAmount'='amount'){return rows.reduce((total,row)=>total+Number(row[field]||0),0)}
function refetch(){journalQuery.refetch()}function reset(){dateRange.value=[monthStart,today];filters.bankAccountId=undefined;filters.direction='';filters.keyword='';journalQuery.refetch()}
function sourcePath(row:TreasuryJournalRow){if(row.sourceType==='PAYMENT')return'/payments';if(row.sourceType==='SUPPLIER_PAYMENT')return'/supplier-payments';if(row.sourceType==='CUSTOMER_ADVANCE_REFUND')return'/customer-advances';if(row.sourceType==='ACCEPTANCE_EVENT')return'/acceptance-bills';if(row.sourceType==='TREASURY_JOURNAL'&&row.fundTransactionId)return'/treasury';return''}
function sourceTypeName(v:string){return({PAYMENT:'客户回款',SUPPLIER_PAYMENT:'供应商付款',CUSTOMER_ADVANCE_REFUND:'客户预收退款',ACCEPTANCE_EVENT:'承兑资金事件',TREASURY_JOURNAL:'费用/其他往来/内部转账'}as Record<string,string>)[v]||v}
function cashFlowName(v:string){return({SALES_COLLECTION:'销售收款',OTHER_OPERATING_IN:'其他经营收入',PURCHASE_PAYMENT:'购买商品/服务支付',EMPLOYEE_PAYMENT:'支付给职工',TAX_PAYMENT:'支付税费',BANK_FEE:'银行手续费',OTHER_OPERATING_OUT:'其他经营支出',INVESTING_IN:'投资收入',INVESTING_OUT:'投资支出',FINANCING_IN:'筹资收入',FINANCING_OUT:'筹资支出',INTERNAL_TRANSFER:'内部账户转账'}as Record<string,string>)[v]||v}
</script>

<style scoped>
.header-actions{display:flex;gap:10px}.summary-grid{display:grid;grid-template-columns:repeat(4,minmax(150px,1fr));gap:12px;padding:0 18px 18px}.summary-card{display:grid;gap:4px;padding:14px 16px;border:1px solid #dfe6ec;border-radius:10px;background:#fafcfd}.summary-card span,.summary-card small{color:#74818c}.summary-card strong{font-size:21px}.summary-card.income{background:#f3faf5}.summary-card.expense{background:#fff8f4}.summary-card.warning{background:#fffaf2}.filters{grid-template-columns:minmax(250px,1.35fr) minmax(180px,1fr) 130px minmax(210px,1fr) auto}.journal-alert{margin-bottom:14px}.amount-in{color:#268248}.amount-out{color:#c94f3d}@media(max-width:1000px){.summary-grid{grid-template-columns:repeat(2,1fr)}.filters{grid-template-columns:1fr 1fr}}@media(max-width:650px){.summary-grid,.filters{grid-template-columns:1fr}}
</style>
