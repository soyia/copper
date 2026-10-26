<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header atp-header">
        <div>
          <div class="panel-title">库存可承诺量（ATP）</div>
          <div class="muted atp-subtitle">回答“指定交期还能接多少”：现存 − 出库草稿占用 − 未被草稿覆盖的销售承诺 ＋ 届时采购到货 ＋ 届时调拨到货。</div>
        </div>
        <div class="header-actions">
          <el-tag effect="plain">按仓库 / 商品 / 日期</el-tag>
          <el-button :loading="isFetching" @click="refetch">刷新计算</el-button>
        </div>
      </div>

      <div class="list-tools atp-tools">
        <el-input v-model="keyword" clearable placeholder="商品编码 / 商品 / 仓库" />
        <el-select v-model="warehouseId" clearable filterable placeholder="全部仓库">
          <el-option v-for="item in enabledWarehouses" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-date-picker v-model="asOfDate" value-format="YYYY-MM-DD" :clearable="false" placeholder="承诺截止日期" />
        <el-checkbox v-model="issuesOnly">只看缺口</el-checkbox>
      </div>

      <div class="atp-kpis">
        <div class="atp-kpi"><span>商品仓库组合</span><strong>{{ filteredRows.length }}</strong></div>
        <div class="atp-kpi"><span>可承诺量合计</span><strong>{{ quantity(totalAtp) }}</strong></div>
        <div class="atp-kpi risk"><span>短缺 / 待补旧承诺</span><strong>{{ riskCount }}</strong></div>
        <div class="atp-kpi warning"><span>未排期采购</span><strong>{{ unscheduledCount }}</strong></div>
      </div>

      <el-alert
        v-if="riskCount"
        :title="`发现 ${riskCount} 项无法安全承诺；先补全旧订单履约仓库或调整交期、采购/调拨计划，不能直接跳过。`"
        type="warning"
        :closable="false"
        show-icon
        class="atp-alert"
      />

      <el-table
        v-loading="isFetching"
        :data="filteredRows"
        border
        size="small"
        class="data-table atp-table"
        max-height="calc(100vh - 365px)"
        row-key="rowKey"
        highlight-current-row
        @row-click="openTimeline"
      >
        <el-table-column label="判断" width="104" fixed="left">
          <template #default="{ row }"><el-tag :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="商品" width="190" fixed="left">
          <template #default="{ row }"><div class="product-cell"><strong>{{ row.productName }}</strong><span>{{ row.sku }} · {{ row.baseUnit }}</span></div></template>
        </el-table-column>
        <el-table-column prop="warehouseName" label="履约仓库" width="110" />
        <el-table-column label="现存" width="88" align="right"><template #default="{ row }">{{ quantity(row.onHandQuantity) }}</template></el-table-column>
        <el-table-column label="出库草稿" width="88" align="right"><template #default="{ row }">{{ quantity(row.outboundDraftQuantity) }}</template></el-table-column>
        <el-table-column label="销售承诺" width="88" align="right"><template #default="{ row }">{{ quantity(row.salesCommitmentQuantity) }}</template></el-table-column>
        <el-table-column label="草稿已覆盖" width="88" align="right"><template #default="{ row }">{{ quantity(row.linkedDraftCoveredQuantity) }}</template></el-table-column>
        <el-table-column label="采购将到" width="88" align="right"><template #default="{ row }">{{ quantity(row.purchaseIncomingQuantity) }}</template></el-table-column>
        <el-table-column label="调拨将到" width="88" align="right"><template #default="{ row }">{{ quantity(row.transferIncomingQuantity) }}</template></el-table-column>
        <el-table-column label="可承诺量" width="105" align="right" fixed="right">
          <template #default="{ row }"><strong :class="Number(row.atpQuantity) < 0 ? 'amount-negative' : 'amount-positive'">{{ quantity(row.atpQuantity) }}</strong></template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!isFetching && !filteredRows.length" description="当前筛选范围暂无库存或承诺" :image-size="60" />
    </div>

    <el-drawer v-model="drawerVisible" size="720px" append-to-body>
      <template #header>
        <div v-if="selected" class="drawer-title">
          <strong>{{ selected.productName }} · {{ selected.warehouseName }}</strong>
          <span>截至 {{ formatDate(selected.asOfDate) }} 的供需时间轴</span>
        </div>
      </template>
      <template v-if="selected">
        <el-descriptions :column="3" border size="small" class="timeline-summary">
          <el-descriptions-item label="现存">{{ quantity(selected.onHandQuantity) }}</el-descriptions-item>
          <el-descriptions-item label="在途供应">{{ quantity(Number(selected.purchaseIncomingQuantity) + Number(selected.transferIncomingQuantity)) }}</el-descriptions-item>
          <el-descriptions-item label="可承诺"><strong>{{ quantity(selected.atpQuantity) }}</strong></el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="selected.unallocatedSalesCommitmentQuantity > 0" :title="`另有 ${quantity(selected.unallocatedSalesCommitmentQuantity)} ${selected.baseUnit} 历史销售承诺未分仓，当前商品暂停新增承诺。`" type="error" :closable="false" show-icon />
        <el-alert v-if="selected.unallocatedPurchaseIncomingQuantity > 0" :title="`另有 ${quantity(selected.unallocatedPurchaseIncomingQuantity)} ${selected.baseUnit} 采购在途未分仓或未排到货日期，未计入 ATP。`" type="warning" :closable="false" show-icon />
        <el-table v-loading="timelineFetching" :data="timelineRows" border size="small" class="timeline-table" empty-text="所选日期范围暂无供需事件">
          <el-table-column prop="eventDate" label="日期" width="112" :formatter="formatDateColumn" />
          <el-table-column label="方向" width="82"><template #default="{ row }"><el-tag :type="row.direction === 'SUPPLY' ? 'success' : 'warning'" size="small">{{ row.direction === 'SUPPLY' ? '供应' : '需求' }}</el-tag></template></el-table-column>
          <el-table-column label="业务来源" min-width="190"><template #default="{ row }"><RouterLink class="business-link" :to="sourcePath(row)">{{ eventTypeText(row.eventType) }} · {{ row.sourceNo }}</RouterLink></template></el-table-column>
          <el-table-column label="原数量" width="105" align="right"><template #default="{ row }">{{ quantity(row.quantity) }}</template></el-table-column>
          <el-table-column label="净影响" width="105" align="right"><template #default="{ row }"><span :class="row.direction === 'SUPPLY' ? 'amount-positive' : 'amount-negative'">{{ row.direction === 'SUPPLY' ? '+' : '-' }}{{ quantity(row.netQuantity) }}</span></template></el-table-column>
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { api, type AtpStatus, type AtpSummary, type AtpTimelineRow } from '@/api/client'
import { currentDateText, formatDate, formatDateColumn, quantity } from '@/api/format'

const keyword = ref('')
const warehouseId = ref<number>()
const asOfDate = ref(currentDateText())
const issuesOnly = ref(false)
const selected = ref<AtpSummary>()
const drawerVisible = ref(false)

const { data: warehouses } = useQuery({ queryKey: ['warehouses'], queryFn: api.warehouses })
const enabledWarehouses = computed(() => (warehouses.value || []).filter((item) => item.status === 'ENABLED'))
const query = useQuery({
  queryKey: computed(() => ['inventory-atp', warehouseId.value, asOfDate.value]),
  queryFn: () => api.inventoryAtp({ warehouseId: warehouseId.value, asOfDate: asOfDate.value }),
})
const rows = computed(() => query.data.value || [])
const isFetching = computed(() => query.isFetching.value)
const refetch = query.refetch
const filteredRows = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  return rows.value.filter((row) => {
    if (issuesOnly.value && row.status === 'AVAILABLE') return false
    return !text || [row.sku, row.productName, row.warehouseName].some((value) => value.toLowerCase().includes(text))
  })
})
const totalAtp = computed(() => filteredRows.value.reduce((sum, row) => sum + Math.max(0, Number(row.atpQuantity)), 0))
const riskCount = computed(() => filteredRows.value.filter((row) => ['SHORTAGE', 'UNALLOCATED_DEMAND'].includes(row.status)).length)
const unscheduledCount = computed(() => filteredRows.value.filter((row) => row.status === 'UNSCHEDULED_SUPPLY').length)

const timelineQuery = useQuery({
  queryKey: computed(() => ['inventory-atp-timeline', selected.value?.productId, selected.value?.warehouseId, asOfDate.value]),
  queryFn: () => api.inventoryAtpTimeline({
    productId: selected.value!.productId,
    warehouseId: selected.value!.warehouseId,
    fromDate: currentDateText(),
    toDate: asOfDate.value,
  }),
  enabled: computed(() => Boolean(selected.value && drawerVisible.value)),
})
const timelineRows = computed(() => timelineQuery.data.value || [])
const timelineFetching = computed(() => timelineQuery.isFetching.value)

function rowKey(row: AtpSummary) { return `${row.productId}-${row.warehouseId}` }
function openTimeline(row: AtpSummary) { selected.value = row; drawerVisible.value = true }
function statusText(status: AtpStatus) { return ({ AVAILABLE: '可承诺', SHORTAGE: '预计短缺', UNALLOCATED_DEMAND: '旧承诺待分仓', UNSCHEDULED_SUPPLY: '采购待排期' })[status] }
function statusType(status: AtpStatus) { return status === 'AVAILABLE' ? 'success' : status === 'UNSCHEDULED_SUPPLY' ? 'warning' : 'danger' }
function eventTypeText(type: AtpTimelineRow['eventType']) { return ({ SALES_COMMITMENT: '销售承诺', PURCHASE_INCOMING: '采购到货', TRANSFER_INCOMING: '调拨到货', OUTBOUND_DRAFT: '出库草稿' })[type] }
function sourcePath(row: AtpTimelineRow) {
  if (row.sourceType === 'SALES_ORDER') return `/sales-orders/${row.sourceId}`
  if (row.sourceType === 'PURCHASE_ORDER') return `/purchase-orders/${row.sourceId}`
  if (row.sourceType === 'INVENTORY_TRANSFER') return `/inventory/transfers/${row.sourceId}`
  return `/outbounds/${row.sourceId}`
}
</script>

<style scoped>
.atp-header { align-items: flex-start; }
.atp-subtitle { margin-top: 5px; font-size: 12px; line-height: 1.6; }
.header-actions { display: flex; gap: 10px; align-items: center; }
.atp-tools { grid-template-columns: minmax(240px, 1fr) 190px 190px auto; }
.atp-kpis { display: grid; grid-template-columns: repeat(4, minmax(150px, 1fr)); gap: 12px; padding: 0 16px 14px; }
.atp-kpi { padding: 13px 16px; border: 1px solid var(--el-border-color-light); border-radius: 8px; background: var(--el-fill-color-blank); }
.atp-kpi span { display: block; color: var(--el-text-color-secondary); font-size: 12px; }
.atp-kpi strong { display: block; margin-top: 5px; font-size: 22px; }
.atp-kpi.risk strong { color: var(--el-color-danger); }
.atp-kpi.warning strong { color: var(--el-color-warning); }
.atp-alert { margin: 0 16px 14px; }
.atp-table :deep(.el-table__row) { cursor: pointer; }
.product-cell { display: flex; flex-direction: column; gap: 3px; }
.product-cell span, .drawer-title span { color: var(--el-text-color-secondary); font-size: 12px; }
.amount-positive { color: var(--el-color-success); font-weight: 700; }
.amount-negative { color: var(--el-color-danger); font-weight: 700; }
.drawer-title { display: flex; flex-direction: column; gap: 4px; }
.timeline-summary { margin-bottom: 14px; }
.timeline-table { margin-top: 14px; }
@media (max-width: 1100px) {
  .atp-kpis { grid-template-columns: repeat(2, 1fr); }
  .atp-tools { grid-template-columns: 1fr 1fr; }
}
</style>
