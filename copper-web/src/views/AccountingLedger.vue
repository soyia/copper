<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header"><div><div class="panel-title">总账、试算平衡与明细账</div><div class="muted ledger-subtitle">仅汇总已记账凭证；点击科目可查看带往来、商品和仓库辅助核算的明细。</div></div><el-button :loading="trialQuery.isFetching.value" @click="refresh">刷新</el-button></div>
      <div class="list-tools">
        <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" />
        <el-input v-model="keyword" clearable placeholder="科目编码 / 名称" />
        <el-tag :type="Number(trial?.difference) === 0 ? 'success' : 'danger'">试算差额 {{ money(trial?.difference) }}</el-tag>
      </div>
      <el-table v-loading="trialQuery.isLoading.value" :data="filteredRows" border size="small" class="data-table" max-height="410" @row-click="selectAccount">
        <el-table-column label="科目" min-width="210" fixed="left"><template #default="{ row }"><el-button link type="primary" @click.stop="selectAccount(row)">{{ row.accountCode }} {{ row.accountName }}</el-button></template></el-table-column>
        <el-table-column label="期初借方" width="125" align="right"><template #default="{ row }">{{ amountOrDash(row.openingDebit) }}</template></el-table-column>
        <el-table-column label="期初贷方" width="125" align="right"><template #default="{ row }">{{ amountOrDash(row.openingCredit) }}</template></el-table-column>
        <el-table-column label="本期借方" width="125" align="right"><template #default="{ row }">{{ amountOrDash(row.periodDebit) }}</template></el-table-column>
        <el-table-column label="本期贷方" width="125" align="right"><template #default="{ row }">{{ amountOrDash(row.periodCredit) }}</template></el-table-column>
        <el-table-column label="期末借方" width="125" align="right"><template #default="{ row }">{{ amountOrDash(row.closingDebit) }}</template></el-table-column>
        <el-table-column label="期末贷方" width="125" align="right"><template #default="{ row }">{{ amountOrDash(row.closingCredit) }}</template></el-table-column>
      </el-table>
      <div v-if="trial" class="trial-totals"><span>期初：借 {{ money(trial.openingDebitTotal) }} / 贷 {{ money(trial.openingCreditTotal) }}</span><span>本期：借 {{ money(trial.periodDebitTotal) }} / 贷 {{ money(trial.periodCreditTotal) }}</span><strong>期末：借 {{ money(trial.closingDebitTotal) }} / 贷 {{ money(trial.closingCreditTotal) }}</strong></div>
    </div>

    <div class="panel ledger-detail-panel">
      <div class="panel-header"><div><div class="panel-title">{{ selectedAccount ? `${selectedAccount.accountCode} ${selectedAccount.accountName} · 明细账` : '科目明细账' }}</div><div class="muted">余额采用借方为正、贷方为负的签名口径。</div></div></div>
      <div class="list-tools auxiliary-tools">
        <el-select v-model="accountId" filterable placeholder="选择科目"><el-option v-for="item in accounts" :key="item.id" :label="`${item.accountCode} ${item.accountName}`" :value="item.id" /></el-select>
        <el-select v-model="partnerId" clearable filterable placeholder="全部往来单位"><el-option v-for="item in partners" :key="item.id" :label="item.name" :value="item.id" /></el-select>
        <el-select v-model="productId" clearable filterable placeholder="全部商品"><el-option v-for="item in products" :key="item.id" :label="`${item.sku} ${item.name}`" :value="item.id" /></el-select>
        <el-select v-model="warehouseId" clearable placeholder="全部仓库"><el-option v-for="item in warehouses" :key="item.id" :label="item.name" :value="item.id" /></el-select>
      </div>
      <el-empty v-if="!accountId" description="请从试算平衡表或下拉框选择科目" :image-size="80" />
      <el-table v-else v-loading="ledgerQuery.isLoading.value" :data="ledgerEntries" border size="small" class="data-table" max-height="360">
        <el-table-column prop="voucherDate" label="日期" width="115" />
        <el-table-column label="凭证号" min-width="205"><template #default="{ row }"><RouterLink class="business-link" :to="`/accounting/vouchers/${row.voucherId}`">{{ row.voucherNo }}</RouterLink></template></el-table-column>
        <el-table-column prop="summary" label="摘要" min-width="210" />
        <el-table-column label="借方" width="125" align="right"><template #default="{ row }">{{ amountOrDash(row.debitAmount) }}</template></el-table-column>
        <el-table-column label="贷方" width="125" align="right"><template #default="{ row }">{{ amountOrDash(row.creditAmount) }}</template></el-table-column>
        <el-table-column label="余额(借正贷负)" width="150" align="right"><template #default="{ row }">{{ money(row.runningBalance) }}</template></el-table-column>
        <el-table-column label="辅助核算" min-width="220"><template #default="{ row }">{{ [row.partnerName, row.productName, row.warehouseName].filter(Boolean).join(' / ') || '-' }}</template></el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { api, type AccountingLedgerRow } from '@/api/client'
import { money } from '@/api/format'

const queryClient = useQueryClient()
const now = new Date()
const dateRange = ref<[string, string]>([`${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}-01`, localDate()])
const keyword = ref('')
const accountId = ref(0)
const partnerId = ref<number>()
const productId = ref<number>()
const warehouseId = ref<number>()
const periodParams = computed(() => ({ from: dateRange.value?.[0], to: dateRange.value?.[1] }))
const ledgerParams = computed(() => ({ accountId: accountId.value, ...periodParams.value, partnerId: partnerId.value, productId: productId.value, warehouseId: warehouseId.value }))
const trialQuery = useQuery({ queryKey: computed(() => ['accounting-trial-balance', periodParams.value]), queryFn: () => api.accountingTrialBalance(periodParams.value) })
const accountQuery = useQuery({ queryKey: ['accounting-accounts'], queryFn: () => api.accountingAccounts(false) })
const partnerQuery = useQuery({ queryKey: ['partners', 'accounting-ledger'], queryFn: () => api.partners() })
const productQuery = useQuery({ queryKey: ['products'], queryFn: api.products })
const warehouseQuery = useQuery({ queryKey: ['warehouses'], queryFn: api.warehouses })
const ledgerQuery = useQuery({ queryKey: computed(() => ['accounting-ledger', ledgerParams.value]), queryFn: () => api.accountingLedger(ledgerParams.value), enabled: computed(() => accountId.value > 0) })
const trial = computed(() => trialQuery.data.value)
const accounts = computed(() => accountQuery.data.value || [])
const partners = computed(() => partnerQuery.data.value || [])
const products = computed(() => productQuery.data.value || [])
const warehouses = computed(() => warehouseQuery.data.value || [])
const ledgerEntries = computed(() => ledgerQuery.data.value || [])
const selectedAccount = computed(() => accounts.value.find((item) => item.id === accountId.value))
const filteredRows = computed(() => (trial.value?.rows || []).filter((row) => !keyword.value.trim() || `${row.accountCode} ${row.accountName}`.toLowerCase().includes(keyword.value.trim().toLowerCase())))
function selectAccount(row: AccountingLedgerRow) { accountId.value = row.accountId }
async function refresh() { await Promise.all([queryClient.invalidateQueries({ queryKey: ['accounting-trial-balance'] }), queryClient.invalidateQueries({ queryKey: ['accounting-ledger'] })]) }
function amountOrDash(value: number) { return Number(value) ? money(value) : '-' }
function localDate() { const date = new Date(); return `${date.getFullYear()}-${String(date.getMonth()+1).padStart(2,'0')}-${String(date.getDate()).padStart(2,'0')}` }
</script>

<style scoped>
.ledger-subtitle { margin-top: 5px; font-size: 12px; }
.trial-totals { display: flex; justify-content: flex-end; gap: 28px; padding: 14px 6px 2px; }
.ledger-detail-panel { margin-top: 14px; }
.auxiliary-tools .el-select { min-width: 180px; }
</style>
