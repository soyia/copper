<template>
  <div class="page settlement-report-page">
    <div class="panel">
      <div class="panel-header report-header">
        <div>
          <div class="title-line">
            <div class="panel-title">{{ copy.title }}</div>
            <el-tag v-if="report && !report.completeScope" type="warning" effect="plain">当前权限范围</el-tag>
          </div>
          <div class="muted subtitle">{{ copy.subtitle }}</div>
          <div v-if="report" class="cutoff-line">
            数据截止 {{ formatDateTime(report.snapshotAt) }} · 账龄基准日 {{ report.asOfDate }} · 汇总不受分页影响
          </div>
        </div>
        <div class="header-actions">
          <el-button v-if="props.direction === 'RECEIVABLE' && filters.partnerId && appStore.hasPermission('external.confirm.manage')" plain @click="showStatementConfirm = !showStatementConfirm">客户对账确认</el-button>
          <el-button v-if="appStore.hasPermission('finance.manage')" type="primary" @click="router.push(copy.createPath)">{{ copy.createLabel }}</el-button>
        </div>
      </div>

      <el-alert
        v-if="error"
        class="report-error"
        type="error"
        :closable="false"
        show-icon
        :title="errorMessage"
      />

      <div v-if="report" class="summary-grid">
        <div>
          <span>当前范围</span>
          <strong>{{ scopeName(filters.scope) }}</strong>
          <small>{{ report.summary.documentCount }} 张单据 · {{ report.summary.partnerCount }} 个{{ copy.partnerLabel }}</small>
        </div>
        <div>
          <span>{{ copy.netLabel }}</span>
          <strong>{{ money(report.summary.totalAmount) }}</strong>
          <small v-if="report.summary.reductionAmount > 0">冲减 {{ money(report.summary.reductionAmount) }}</small>
          <small v-else>按全部筛选结果汇总</small>
        </div>
        <div>
          <span>{{ copy.paidLabel }}</span>
          <strong>{{ money(report.summary.paidAmount) }}</strong>
          <small>资金核销口径</small>
        </div>
        <div :class="{ danger: filters.scope === 'overdue' }">
          <span>{{ copy.unpaidLabel }}</span>
          <strong>{{ money(report.summary.unpaidAmount) }}</strong>
          <small>其中逾期 {{ money(report.summary.overdueAmount) }}（{{ report.summary.overdueCount }} 张）</small>
        </div>
      </div>

      <div v-if="report && report.summary.unpaidAmount > 0" class="aging-grid" aria-label="账龄分布">
        <div v-for="bucket in report.aging" :key="bucket.code" :class="{ overdue: bucket.code.startsWith('OVERDUE') }">
          <span>{{ bucket.label }}</span>
          <strong>{{ money(bucket.outstandingAmount) }}</strong>
          <small>{{ bucket.documentCount }} 张</small>
        </div>
      </div>

      <div class="list-tools">
        <el-input
          v-model="keywordDraft"
          clearable
          :placeholder="`单据号 / ${copy.partnerLabel}`"
          @keyup.enter="search"
          @clear="search"
        />
        <PartnerRemoteSelect
          v-model="filters.partnerId"
          :type="copy.partnerType"
          clearable
          :placeholder="copy.partnerLabel"
          @change="filterChanged"
        />
        <el-select v-model="filters.sourceType" clearable placeholder="来源" @change="filterChanged">
          <el-option v-for="source in copy.sources" :key="source.value" :label="source.label" :value="source.value" />
        </el-select>
        <el-select v-model="filters.scope" placeholder="到期范围" @change="scopeChanged">
          <el-option :label="copy.openLabel" value="open" />
          <el-option label="今日到期" value="due-today" />
          <el-option label="已逾期" value="overdue" />
          <el-option label="已结清" value="settled" />
          <el-option label="全部状态" value="all" />
        </el-select>
        <el-date-picker
          v-model="filters.dateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="到期开始"
          end-placeholder="到期结束"
          @change="filterChanged"
        />
        <div class="list-tools-actions">
          <el-button type="primary" plain @click="search">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </div>
      </div>

      <el-table
        v-loading="isFetching"
        :data="report?.items ?? []"
        class="data-table"
        size="small"
        border
        show-overflow-tooltip
        max-height="calc(100vh - 425px)"
        empty-text="当前筛选范围暂无数据"
      >
        <el-table-column label="来源" width="108" fixed="left"><template #default="{ row }">{{ sourceTypeName(row.sourceType) }}</template></el-table-column>
        <el-table-column label="单据号" width="185" fixed="left"><template #default="{ row }"><router-link class="table-link" :to="detailRoute(row)">{{ row.sourceNo }}</router-link></template></el-table-column>
        <el-table-column prop="partnerName" :label="copy.partnerLabel" min-width="150" />
        <el-table-column prop="businessDate" label="业务日期" width="112" :formatter="formatDateColumn" />
        <el-table-column label="账期" width="82" align="right"><template #default="{ row }">{{ row.settlementDays }} 天</template></el-table-column>
        <el-table-column prop="dueDate" label="到期日" width="112" :formatter="formatDateColumn" />
        <el-table-column label="账龄" width="118"><template #default="{ row }"><el-tag v-if="row.unpaidAmount > 0" :type="row.overdueDays > 0 ? 'danger' : 'info'" size="small">{{ agingName(row) }}</el-tag><span v-else>-</span></template></el-table-column>
        <el-table-column :label="copy.grossLabel" width="125" align="right"><template #default="{ row }">{{ money(row.grossAmount) }}</template></el-table-column>
        <el-table-column label="调整/退货冲减" width="128" align="right"><template #default="{ row }">{{ row.reductionAmount ? money(row.reductionAmount) : '-' }}</template></el-table-column>
        <el-table-column :label="copy.netLabel" width="125" align="right"><template #default="{ row }">{{ money(row.totalAmount) }}</template></el-table-column>
        <el-table-column :label="copy.paidLabel" width="125" align="right"><template #default="{ row }">{{ money(row.paidAmount) }}</template></el-table-column>
        <el-table-column :label="copy.unpaidLabel" width="125" align="right"><template #default="{ row }"><strong :class="{ 'amount-danger': row.overdueDays > 0 && row.unpaidAmount > 0 }">{{ money(row.unpaidAmount) }}</strong></template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="paymentStatusType(row.paymentStatus)" size="small">{{ paymentStatus(row.paymentStatus) }}</el-tag></template></el-table-column>
        <el-table-column v-if="appStore.hasPermission('finance.manage')" label="处理" width="82" fixed="right"><template #default="{ row }"><router-link v-if="row.unpaidAmount > 0" class="table-link" :to="paymentRoute(row)">{{ copy.actionLabel }}</router-link><span v-else class="muted">完成</span></template></el-table-column>
      </el-table>

      <div v-if="report" class="pagination-row">
        <span class="muted">共 {{ report.totalRows }} 张，当前第 {{ report.pageNo }} / {{ report.totalPages || 1 }} 页</span>
        <el-pagination
          v-model:current-page="filters.pageNo"
          v-model:page-size="filters.pageSize"
          background
          layout="sizes, prev, pager, next"
          :page-sizes="[20, 50, 100, 200]"
          :total="report.totalRows"
          @size-change="pageSizeChanged"
        />
      </div>
    </div>
    <ExternalConfirmationPanel v-if="props.direction === 'RECEIVABLE' && filters.partnerId && showStatementConfirm" :key="filters.partnerId" packet-type="CUSTOMER_STATEMENT" :source-id="filters.partnerId" />
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { keepPreviousData, useQuery } from '@tanstack/vue-query'
import { api, type SettlementReportParams, type SettlementReportRow, type SettlementScope } from '@/api/client'
import { formatDateColumn, formatDateTime, money, paymentStatus, paymentStatusType } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import { relatedDetailRoute } from '@/utils/navigation'
import { useAppStore } from '@/stores/app'
import ExternalConfirmationPanel from '@/components/ExternalConfirmationPanel.vue'

const props = defineProps<{ direction: 'RECEIVABLE' | 'PAYABLE' }>()
const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const keywordDraft = ref('')
const showStatementConfirm = ref(false)
const filters = reactive({
  keyword: '',
  partnerId: undefined as number | undefined,
  sourceType: '',
  scope: 'open' as SettlementScope,
  dateRange: [] as string[],
  pageNo: 1,
  pageSize: 50,
})

const copy = computed(() => props.direction === 'RECEIVABLE' ? {
  title: '应收账款', subtitle: '客户欠款、到期与账龄按服务端完整数据口径计算；回款与核销单独留痕。',
  partnerLabel: '客户', partnerType: 'CUSTOMER' as const, createPath: '/payments/new', createLabel: '登记回款',
  openLabel: '待收全部', grossLabel: '原始应收', netLabel: '净应收', paidLabel: '已收金额', unpaidLabel: '待收金额', actionLabel: '收款',
  sources: [{ label: '销售订单', value: 'SALES_ORDER' }, { label: '无订单出库', value: 'OUTBOUND' }, { label: '期初应收', value: 'OPENING_RECEIVABLE' }],
} : {
  title: '应付账款', subtitle: '供应商欠款、到期与账龄按服务端完整数据口径计算；付款、退货与核销分别留痕。',
  partnerLabel: '供应商', partnerType: 'SUPPLIER' as const, createPath: '/supplier-payments/new', createLabel: '登记付款',
  openLabel: '待付全部', grossLabel: '原始应付', netLabel: '净应付', paidLabel: '已付金额', unpaidLabel: '待付金额', actionLabel: '付款',
  sources: [{ label: '采购订单', value: 'PURCHASE_ORDER' }, { label: '无订单入库', value: 'INBOUND' }, { label: '期初应付', value: 'OPENING_PAYABLE' }],
})

const queryParams = computed<SettlementReportParams>(() => ({
  partnerId: filters.partnerId,
  keyword: filters.keyword || undefined,
  sourceType: filters.sourceType || undefined,
  scope: filters.scope,
  dueDateFrom: filters.dateRange[0],
  dueDateTo: filters.dateRange[1],
  pageNo: filters.pageNo,
  pageSize: filters.pageSize,
}))

const { data: report, error, isFetching } = useQuery({
  queryKey: computed(() => ['settlement-report', props.direction, queryParams.value]),
  queryFn: () => props.direction === 'RECEIVABLE' ? api.receivableReport(queryParams.value) : api.payableReport(queryParams.value),
  placeholderData: keepPreviousData,
})

const errorMessage = computed(() => (error.value as { message?: string } | null)?.message || '应收应付报表加载失败，请稍后重试')

watch(() => route.query.scope, (value) => {
  const scope = String(value ?? 'open')
  filters.scope = ['all', 'open', 'due-today', 'overdue', 'settled'].includes(scope) ? scope as SettlementScope : 'open'
  filters.pageNo = 1
}, { immediate: true })

function search() {
  filters.keyword = keywordDraft.value.trim()
  filters.pageNo = 1
}

function filterChanged() { filters.pageNo = 1 }

function scopeChanged() {
  filters.pageNo = 1
  router.replace({ path: route.path, query: filters.scope === 'open' ? {} : { scope: filters.scope } })
}

function pageSizeChanged() { filters.pageNo = 1 }

function resetFilters() {
  keywordDraft.value = ''
  Object.assign(filters, { keyword: '', partnerId: undefined, sourceType: '', scope: 'open', dateRange: [], pageNo: 1, pageSize: 50 })
  router.replace({ path: route.path, query: {} })
}

function detailRoute(row: SettlementReportRow) {
  const paths: Record<string, string> = {
    SALES_ORDER: `/sales-orders/${row.sourceId}`,
    OUTBOUND: `/outbounds/${row.sourceId}`,
    PURCHASE_ORDER: `/purchase-orders/${row.sourceId}`,
    INBOUND: `/inbounds/${row.sourceId}`,
    OPENING_RECEIVABLE: `/treasury/documents/${row.sourceId}`,
    OPENING_PAYABLE: `/treasury/documents/${row.sourceId}`,
  }
  return relatedDetailRoute(route, paths[row.sourceType])
}

function paymentRoute(row: SettlementReportRow) {
  if (row.sourceType.startsWith('OPENING_')) return relatedDetailRoute(route, `/treasury/documents/${row.sourceId}`)
  return {
    path: props.direction === 'RECEIVABLE' ? '/payments/new' : '/supplier-payments/new',
    query: { sourceType: row.sourceType, sourceId: String(row.sourceId), from: route.fullPath },
  }
}

function sourceTypeName(value: SettlementReportRow['sourceType']) {
  return ({ SALES_ORDER: '销售订单', OUTBOUND: '无订单出库', PURCHASE_ORDER: '采购订单', INBOUND: '无订单入库', OPENING_RECEIVABLE: '期初应收', OPENING_PAYABLE: '期初应付' } as Record<string, string>)[value] ?? value
}

function agingName(row: SettlementReportRow) {
  if (row.overdueDays > 0) return `逾期 ${row.overdueDays} 天`
  if (row.agingBucket === 'DUE_TODAY') return '今日到期'
  return '未到期'
}

function scopeName(value: SettlementScope) {
  return ({ all: '全部状态', open: copy.value.openLabel, 'due-today': '今日到期', overdue: '已逾期', settled: '已结清' } as Record<SettlementScope, string>)[value]
}
</script>

<style scoped>
.report-header { align-items: flex-start; }
.title-line { display: flex; align-items: center; gap: 8px; }
.subtitle { margin-top: 5px; font-size: 12px; }
.cutoff-line { margin-top: 7px; color: var(--el-text-color-secondary); font-size: 12px; font-variant-numeric: tabular-nums; }
.report-error { margin: 14px 0; }
.summary-grid { display: grid; grid-template-columns: repeat(4, minmax(150px, 1fr)); gap: 10px; margin: 14px 0 10px; }
.summary-grid > div { min-height: 82px; padding: 12px 14px; border: 1px solid var(--el-border-color-light); border-radius: 8px; background: var(--el-fill-color-lighter); }
.summary-grid span, .summary-grid small, .aging-grid span, .aging-grid small { display: block; color: var(--el-text-color-secondary); font-size: 12px; }
.summary-grid strong { display: block; margin: 6px 0 4px; font-size: 18px; font-variant-numeric: tabular-nums; }
.summary-grid .danger strong, .amount-danger { color: var(--el-color-danger); }
.aging-grid { display: grid; grid-template-columns: repeat(6, minmax(112px, 1fr)); gap: 8px; margin: 0 0 14px; }
.aging-grid > div { padding: 9px 11px; border-left: 3px solid var(--el-color-info-light-5); border-radius: 5px; background: var(--el-fill-color-light); }
.aging-grid > div.overdue { border-left-color: var(--el-color-danger-light-3); }
.aging-grid strong { display: block; margin: 4px 0 2px; font-size: 14px; font-variant-numeric: tabular-nums; }
.pagination-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding-top: 14px; }
@media (max-width: 1100px) { .summary-grid { grid-template-columns: repeat(2, 1fr); } .aging-grid { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 760px) { .summary-grid, .aging-grid { grid-template-columns: 1fr; } .pagination-row { align-items: flex-start; flex-direction: column; } }
</style>
