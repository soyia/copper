<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">财务报表</div>
          <div class="muted report-subtitle">报表只读取已记账凭证；现金流量表还要求银行对账已确认，并单独显示与货币资金总账的勾稽差额。</div>
        </div>
        <el-button :loading="loading" @click="refresh">刷新</el-button>
      </div>

      <div class="list-tools report-tools">
        <el-date-picker v-if="activeTab === 'balance'" v-model="asOf" type="date" value-format="YYYY-MM-DD" placeholder="报表截止日" />
        <el-date-picker v-else v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" />
        <el-tag v-if="activeTab === 'balance'" :type="isZero(balance?.difference) ? 'success' : 'danger'">平衡差额 {{ money(balance?.difference) }}</el-tag>
        <el-tag v-if="activeTab === 'cash'" :type="isZero(cash?.closingDifference) ? 'success' : 'danger'">货币资金勾稽差额 {{ money(cash?.closingDifference) }}</el-tag>
      </div>

      <el-tabs v-model="activeTab">
        <el-tab-pane label="资产负债表" name="balance">
          <el-alert v-if="balance?.unmappedAccounts.length" type="error" show-icon :closable="false" :title="`存在未映射科目：${balance.unmappedAccounts.join('、')}`" />
          <el-table v-loading="balanceQuery.isLoading.value" :data="balance?.rows || []" border size="small" class="data-table report-table">
            <el-table-column prop="lineName" label="项目" min-width="240" fixed="left">
              <template #default="{ row }"><strong v-if="row.calculationType === 'FORMULA'">{{ row.lineName }}</strong><span v-else>{{ row.lineName }}</span><div v-if="row.accountCodes.length" class="account-codes">{{ row.accountCodes.join('、') }}</div></template>
            </el-table-column>
            <el-table-column :label="balance?.toDate || '本期'" width="170" align="right"><template #default="{ row }">{{ amountOrDash(row.currentAmount) }}</template></el-table-column>
            <el-table-column :label="`${priorYearLabel} 同期`" width="170" align="right"><template #default="{ row }">{{ amountOrDash(row.comparativeAmount) }}</template></el-table-column>
          </el-table>
          <div v-if="balance" class="report-summary"><span>资产合计 {{ money(balance.primaryTotal) }}</span><span>负债和所有者权益 {{ money(balance.secondaryTotal) }}</span><strong>差额 {{ money(balance.difference) }}</strong></div>
        </el-tab-pane>

        <el-tab-pane label="利润表" name="income">
          <el-alert v-if="income?.unmappedAccounts.length" type="error" show-icon :closable="false" :title="`存在未映射科目：${income.unmappedAccounts.join('、')}`" />
          <el-table v-loading="incomeQuery.isLoading.value" :data="income?.rows || []" border size="small" class="data-table report-table">
            <el-table-column prop="lineName" label="项目" min-width="240" fixed="left"><template #default="{ row }"><strong v-if="row.calculationType === 'FORMULA'">{{ row.lineName }}</strong><span v-else>{{ row.lineName }}</span><div v-if="row.accountCodes.length" class="account-codes">{{ row.accountCodes.join('、') }}</div></template></el-table-column>
            <el-table-column label="本期金额" width="170" align="right"><template #default="{ row }">{{ amountOrDash(row.currentAmount) }}</template></el-table-column>
            <el-table-column label="上年同期" width="170" align="right"><template #default="{ row }">{{ amountOrDash(row.comparativeAmount) }}</template></el-table-column>
          </el-table>
          <div v-if="income" class="report-summary"><span>营业收入 {{ money(income.primaryTotal) }}</span><span>成本费用 {{ money(income.secondaryTotal) }}</span><strong>净利润 {{ money(income.difference) }}</strong></div>
        </el-tab-pane>

        <el-tab-pane label="现金流量表" name="cash">
          <el-alert v-if="cash && !isZero(cash.closingDifference)" type="error" show-icon :closable="false" title="现金流量推算余额与总账货币资金不一致，期末结账将被阻止。请检查未确认银行对账、现金收付分类或错误会计科目。" />
          <el-table v-loading="cashQuery.isLoading.value" :data="cash?.rows || []" border size="small" class="data-table report-table">
            <el-table-column prop="categoryName" label="现金流量项目" min-width="280" fixed="left" />
            <el-table-column label="活动类别" width="120"><template #default="{ row }">{{ activityName(row.activityType) }}</template></el-table-column>
            <el-table-column label="方向" width="90"><template #default="{ row }">{{ row.direction === 'CREDIT' ? '流入' : '流出' }}</template></el-table-column>
            <el-table-column label="金额" width="170" align="right"><template #default="{ row }">{{ amountOrDash(row.amount) }}</template></el-table-column>
          </el-table>
          <div v-if="cash" class="cash-cards">
            <div><span>期初货币资金</span><strong>{{ money(cash.openingCash) }}</strong></div>
            <div><span>经营活动净额</span><strong>{{ money(cash.operatingNet) }}</strong></div>
            <div><span>投资活动净额</span><strong>{{ money(cash.investingNet) }}</strong></div>
            <div><span>筹资活动净额</span><strong>{{ money(cash.financingNet) }}</strong></div>
            <div><span>现金净增加额</span><strong>{{ money(cash.netIncrease) }}</strong></div>
            <div><span>推算期末余额</span><strong>{{ money(cash.closingCash) }}</strong></div>
            <div><span>总账期末余额</span><strong>{{ money(cash.ledgerClosingCash) }}</strong></div>
            <div :class="{ danger: !isZero(cash.closingDifference) }"><span>勾稽差额</span><strong>{{ money(cash.closingDifference) }}</strong></div>
          </div>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { api } from '@/api/client'
import { money } from '@/api/format'

const today = localDate(new Date())
const monthStart = `${today.slice(0, 7)}-01`
const activeTab = ref<'balance' | 'income' | 'cash'>('balance')
const asOf = ref(today)
const dateRange = ref<[string, string]>([monthStart, today])
const balanceQuery = useQuery({ queryKey: computed(() => ['balance-sheet', asOf.value]), queryFn: () => api.balanceSheet(asOf.value) })
const incomeQuery = useQuery({ queryKey: computed(() => ['income-statement', dateRange.value]), queryFn: () => api.incomeStatement({ from: dateRange.value[0], to: dateRange.value[1] }) })
const cashQuery = useQuery({ queryKey: computed(() => ['cash-flow-statement', dateRange.value]), queryFn: () => api.cashFlowStatement({ from: dateRange.value[0], to: dateRange.value[1] }) })
const balance = computed(() => balanceQuery.data.value)
const income = computed(() => incomeQuery.data.value)
const cash = computed(() => cashQuery.data.value)
const loading = computed(() => balanceQuery.isFetching.value || incomeQuery.isFetching.value || cashQuery.isFetching.value)
const priorYearLabel = computed(() => String(Number(asOf.value.slice(0, 4)) - 1))

async function refresh() { await Promise.all([balanceQuery.refetch(), incomeQuery.refetch(), cashQuery.refetch()]) }
function isZero(value?: number) { return Math.abs(Number(value || 0)) < 0.01 }
function amountOrDash(value: number) { return Number(value) ? money(value) : '-' }
function activityName(value: string) { return ({ OPERATING: '经营活动', INVESTING: '投资活动', FINANCING: '筹资活动' } as Record<string, string>)[value] || value }
function localDate(date: Date) { return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}` }
</script>

<style scoped>
.report-subtitle { margin-top: 5px; font-size: 12px; }
.report-tools { margin-bottom: 8px; }
.report-table { max-height: calc(100vh - 315px); }
.account-codes { margin-top: 3px; color: #8a98a2; font-size: 11px; }
.report-summary { display: flex; justify-content: flex-end; gap: 28px; padding: 14px 6px 2px; }
.cash-cards { display: grid; grid-template-columns: repeat(4, minmax(170px, 1fr)); gap: 10px; margin-top: 14px; }
.cash-cards > div { display: flex; flex-direction: column; gap: 6px; padding: 12px 14px; border: 1px solid #dfe6ea; border-radius: 8px; background: #fafcfd; }
.cash-cards span { color: #73828c; font-size: 12px; }
.cash-cards strong { font-size: 17px; }
.cash-cards .danger { border-color: #efb7b7; background: #fff6f6; color: #c33434; }
@media (max-width: 1100px) { .cash-cards { grid-template-columns: repeat(2, minmax(170px, 1fr)); } }
</style>
