<template>
  <div class="page">
    <div class="panel hero-panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">往来余额调整单</div>
          <div class="muted">奖励、返利、期初和差错必须先形成源单，再由负责人确认过账；本页面不登记真实收付款，也不替代发票处理。</div>
        </div>
        <el-button v-if="canCreate" type="primary" @click="router.push('/partner-fund-adjustments/new')">新建调整单</el-button>
      </div>
      <div class="summary-grid">
        <div class="summary-card"><span>待确认</span><strong>{{ stats.draftCount }}</strong><small>{{ money(stats.draftAmount) }}</small></div>
        <div class="summary-card"><span>本页已确认</span><strong>{{ stats.confirmedCount }}</strong><small>{{ money(stats.confirmedAmount) }}</small></div>
        <div class="summary-card"><span>已冲正</span><strong>{{ stats.reversedCount }}</strong><small>原单始终保留</small></div>
        <div class="summary-card safe"><span>直接调账入口</span><strong>已退出</strong><small>余额只从业务源单产生</small></div>
      </div>
    </div>

    <div class="panel">
      <div class="list-tools filters">
        <el-input v-model="filters.keyword" clearable placeholder="单号 / 往来单位 / 依据号" />
        <PartnerRemoteSelect v-model="filters.partnerId" clearable placeholder="往来单位" />
        <el-select v-model="filters.status" clearable placeholder="单据状态">
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-select v-model="filters.accountType" clearable placeholder="余额账户">
          <el-option v-for="item in accountOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <div class="list-tools-actions"><el-button @click="resetFilters">重置</el-button></div>
      </div>
      <el-table v-loading="adjustmentsQuery.isLoading.value" :data="filteredRows" class="data-table" size="small" border show-overflow-tooltip>
        <el-table-column label="调整单号" min-width="190" fixed="left">
          <template #default="{ row }"><router-link class="table-link" :to="detailRoute(row.id)">{{ row.adjustmentNo }}</router-link></template>
        </el-table-column>
        <el-table-column prop="businessDate" label="业务日期" width="112" :formatter="formatDateColumn" />
        <el-table-column prop="partnerName" label="往来单位" min-width="170" />
        <el-table-column label="业务类型" width="140"><template #default="{ row }">{{ adjustmentTypeName(row.adjustmentType) }}</template></el-table-column>
        <el-table-column label="账户" width="140"><template #default="{ row }">{{ accountTypeName(row.accountType) }}</template></el-table-column>
        <el-table-column label="变动金额" width="142" align="right">
          <template #default="{ row }"><span :class="row.direction === 'INCREASE' ? 'amount-increase' : 'amount-decrease'">{{ row.direction === 'INCREASE' ? '+' : '-' }}{{ money(row.amount) }}</span></template>
        </el-table-column>
        <el-table-column label="状态" width="98"><template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ statusName(row.status) }}</el-tag></template></el-table-column>
        <el-table-column prop="basisNo" label="业务依据" min-width="160" />
        <el-table-column label="会计凭证" min-width="150">
          <template #default="{ row }">
            <router-link v-if="row.accountingVoucherId" class="table-link" :to="`/accounting/vouchers/${row.accountingVoucherId}`">{{ row.accountingVoucherNo }}</router-link>
            <span v-else class="muted">{{ row.status === 'CONFIRMED' ? '待自动制证' : '-' }}</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!adjustmentsQuery.isLoading.value && filteredRows.length === 0" description="没有符合条件的余额调整单" />
    </div>

    <div class="panel">
      <div class="panel-header">
        <div><div class="panel-title">当前账户余额</div><div class="muted">余额为全部合法业务源单累计结果；期初、差错、奖励与返利不得在此直接修改。</div></div>
      </div>
      <el-table :data="accountSummaries" class="data-table" size="small" border show-overflow-tooltip max-height="300">
        <el-table-column prop="partnerName" label="往来单位" min-width="180" fixed="left" />
        <el-table-column label="客户预收" width="140" align="right"><template #default="{ row }">{{ money(row.customerAdvance) }}</template></el-table-column>
        <el-table-column label="客户奖励" width="140" align="right"><template #default="{ row }">{{ money(row.customerReward) }}</template></el-table-column>
        <el-table-column label="供应商预付" width="140" align="right"><template #default="{ row }">{{ money(row.supplierPrepay) }}</template></el-table-column>
        <el-table-column label="供应商返利" width="140" align="right"><template #default="{ row }">{{ money(row.supplierReward) }}</template></el-table-column>
        <el-table-column label="合计" width="145" align="right"><template #default="{ row }"><strong>{{ money(row.total) }}</strong></template></el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { api, type PartnerFundAdjustmentRow } from '@/api/client'
import { accountTypeName, formatDateColumn, money } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import { useAppStore } from '@/stores/app'
import { relatedDetailRoute } from '@/utils/navigation'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const canCreate = computed(() => appStore.hasPermission('partner.fund.adjust.manage'))
const filters = reactive({ keyword: '', partnerId: undefined as number | undefined, status: '', accountType: '' })
const adjustmentsQuery = useQuery({ queryKey: ['partner-fund-adjustments'], queryFn: () => api.partnerFundAdjustments() })
const accountsQuery = useQuery({ queryKey: ['partner-accounts'], queryFn: () => api.partnerAccounts() })

const statusOptions = [
  { label: '草稿', value: 'DRAFT' }, { label: '已确认', value: 'CONFIRMED' },
  { label: '已作废', value: 'CANCELLED' }, { label: '已冲正', value: 'REVERSED' },
]
const accountOptions = [
  { label: '客户预收款', value: 'CUSTOMER_ADVANCE' }, { label: '客户奖励金', value: 'CUSTOMER_REWARD' },
  { label: '供应商预付款', value: 'SUPPLIER_PREPAY' }, { label: '供应商返利金', value: 'SUPPLIER_REWARD' },
]

const filteredRows = computed(() => (adjustmentsQuery.data.value ?? []).filter((row) => {
  const keyword = filters.keyword.trim().toLowerCase()
  return (!keyword || [row.adjustmentNo, row.partnerName, row.basisNo, row.memo].some((value) => String(value || '').toLowerCase().includes(keyword)))
    && (!filters.partnerId || row.partnerId === filters.partnerId)
    && (!filters.status || row.status === filters.status)
    && (!filters.accountType || row.accountType === filters.accountType)
}))

const stats = computed(() => {
  const rows = adjustmentsQuery.data.value ?? []
  const byStatus = (status: string) => rows.filter((row) => row.status === status)
  const sum = (items: PartnerFundAdjustmentRow[]) => items.reduce((total, row) => total + Number(row.amount), 0)
  return { draftCount: byStatus('DRAFT').length, draftAmount: sum(byStatus('DRAFT')), confirmedCount: byStatus('CONFIRMED').length, confirmedAmount: sum(byStatus('CONFIRMED')), reversedCount: byStatus('REVERSED').length }
})

const accountSummaries = computed(() => {
  const rows = new Map<number, { partnerId: number; partnerName: string; customerAdvance: number; customerReward: number; supplierPrepay: number; supplierReward: number; total: number }>()
  for (const account of accountsQuery.data.value ?? []) {
    const row = rows.get(account.partnerId) ?? { partnerId: account.partnerId, partnerName: account.partnerName, customerAdvance: 0, customerReward: 0, supplierPrepay: 0, supplierReward: 0, total: 0 }
    const value = Number(account.balance)
    if (account.accountType === 'CUSTOMER_ADVANCE') row.customerAdvance += value
    if (account.accountType === 'CUSTOMER_REWARD') row.customerReward += value
    if (account.accountType === 'SUPPLIER_PREPAY') row.supplierPrepay += value
    if (account.accountType === 'SUPPLIER_REWARD') row.supplierReward += value
    row.total += value
    rows.set(account.partnerId, row)
  }
  return [...rows.values()].filter((row) => !filters.partnerId || row.partnerId === filters.partnerId)
})

function resetFilters() { filters.keyword = ''; filters.partnerId = undefined; filters.status = ''; filters.accountType = '' }
function detailRoute(id: number) { return relatedDetailRoute(route, `/partner-fund-adjustments/${id}`) }
function adjustmentTypeName(value: string) { return ({ OPENING_BALANCE: '期初余额', CUSTOMER_REWARD_GRANT: '客户奖励确认', SUPPLIER_REBATE_GRANT: '供应商返利确认', BALANCE_CORRECTION: '余额差错调整' } as Record<string, string>)[value] ?? value }
function statusName(value: string) { return ({ DRAFT: '草稿', CONFIRMED: '已确认', CANCELLED: '已作废', REVERSED: '已冲正' } as Record<string, string>)[value] ?? value }
function statusType(value: string) { return value === 'CONFIRMED' ? 'success' : value === 'DRAFT' ? 'warning' : 'info' }
</script>

<style scoped>
.hero-panel { overflow: hidden; }
.summary-grid { display: grid; grid-template-columns: repeat(4, minmax(150px, 1fr)); gap: 12px; padding: 0 18px 18px; }
.summary-card { display: grid; gap: 4px; padding: 14px 16px; border: 1px solid #e2e8ee; border-radius: 10px; background: #fafcfd; }
.summary-card span, .summary-card small { color: #74818c; }
.summary-card strong { font-size: 22px; color: #18232d; }
.summary-card.safe { background: #f3faf5; border-color: #cfe7d5; }
.summary-card.safe strong { color: #27864a; font-size: 18px; }
.filters { grid-template-columns: minmax(220px, 1.3fr) minmax(190px, 1fr) 150px 170px auto; }
.amount-increase { color: #268248; font-weight: 650; }
.amount-decrease { color: #c94f3d; font-weight: 650; }
@media (max-width: 1100px) { .summary-grid { grid-template-columns: repeat(2, minmax(150px, 1fr)); } .filters { grid-template-columns: 1fr 1fr; } }
@media (max-width: 680px) { .summary-grid, .filters { grid-template-columns: 1fr; } }
</style>
