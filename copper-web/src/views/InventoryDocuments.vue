<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">{{ config.title }}</div>
          <div class="muted inventory-list-subtitle">{{ subtitle }}</div>
        </div>
        <div>
          <el-button @click="refetch">刷新</el-button>
          <el-button v-if="appStore.hasPermission(config.permission)" type="primary" @click="createDocument">新建{{ config.title }}</el-button>
        </div>
      </div>
      <div class="list-tools">
        <el-input v-model="keyword" clearable placeholder="单号 / 仓库 / 商品来源 / 供应商" />
        <el-select v-model="status" clearable placeholder="全部状态">
          <el-option v-for="item in statusOptions" :key="item" :label="inventoryStatusText[item]" :value="item" />
        </el-select>
      </div>
      <el-table v-loading="isFetching" :data="filteredRows" border size="small" class="data-table" max-height="calc(100vh - 260px)" @row-dblclick="openDetail">
        <el-table-column label="单号" min-width="190" fixed="left">
          <template #default="{ row }"><el-button link type="primary" @click="openDetail(row)">{{ documentNo(row) }}</el-button></template>
        </el-table-column>
        <template v-if="kind === 'transfer'">
          <el-table-column prop="fromWarehouseName" label="调出仓" min-width="130" />
          <el-table-column prop="toWarehouseName" label="调入仓" min-width="130" />
          <el-table-column prop="expectedArrivalDate" label="预计到达" width="120" />
          <el-table-column label="调拨数量" width="130" align="right"><template #default="{ row }">{{ quantity(row.totalQuantity) }}</template></el-table-column>
          <el-table-column v-if="appStore.hasPermission('inventory.cost.view')" label="在途成本" width="130" align="right"><template #default="{ row }">{{ money(row.totalCost) }}</template></el-table-column>
        </template>
        <template v-else-if="kind === 'stocktake'">
          <el-table-column prop="warehouseName" label="盘点仓库" min-width="140" />
          <el-table-column label="盘盈" width="120" align="right"><template #default="{ row }">{{ quantity(row.totalGainQuantity) }}</template></el-table-column>
          <el-table-column label="盘亏" width="120" align="right"><template #default="{ row }">{{ quantity(row.totalLossQuantity) }}</template></el-table-column>
          <el-table-column v-if="appStore.hasPermission('inventory.cost.view')" label="成本差额" width="130" align="right"><template #default="{ row }">{{ money(row.totalCostDifference) }}</template></el-table-column>
        </template>
        <template v-else-if="kind === 'purchase-return'">
          <el-table-column prop="supplierName" label="供应商" min-width="170" />
          <el-table-column prop="inboundNo" label="原入库单" min-width="180" />
          <el-table-column prop="warehouseName" label="退货仓库" min-width="130" />
          <el-table-column label="退货数量" width="120" align="right"><template #default="{ row }">{{ quantity(row.totalQuantity) }}</template></el-table-column>
          <el-table-column label="退货金额" width="130" align="right"><template #default="{ row }">{{ money(row.totalAmount) }}</template></el-table-column>
        </template>
        <template v-else>
          <el-table-column prop="reason" label="调整原因" min-width="240" />
          <el-table-column label="成本差额" width="150" align="right"><template #default="{ row }">{{ money(row.totalDifferenceAmount) }}</template></el-table-column>
        </template>
        <el-table-column prop="businessDate" label="业务日期" width="120" />
        <el-table-column label="状态" width="105">
          <template #default="{ row }"><el-tag :type="inventoryStatusTag(row.status)">{{ inventoryStatusText[row.status] || row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170" :formatter="formatDateTimeColumn" />
        <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row)">查看</el-button></template></el-table-column>
      </el-table>
      <el-empty v-if="!isFetching && !filteredRows.length" description="暂无符合条件的单据" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { api } from '@/api/client'
import { formatDateTimeColumn, money, quantity } from '@/api/format'
import { useAppStore } from '@/stores/app'
import { inventoryDocumentConfig, inventoryStatusTag, inventoryStatusText, type InventoryDocumentKind } from '@/utils/inventoryDocuments'

const props = defineProps<{ kind: InventoryDocumentKind }>()
const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const keyword = ref('')
const status = ref('')
const kind = computed(() => props.kind)
const config = computed(() => inventoryDocumentConfig[props.kind])
const subtitle = computed(() => ({
  transfer: '调出、在途、调入分段记录，库存位置变化可完整追溯',
  stocktake: '以建单时账面快照核对实盘数，差异确认后形成盘盈盘亏流水',
  'purchase-return': '关联原入库批次，退货库存、应付冲减和已付款权益同步过账',
  'cost-adjustment': '批次实际成本调整，不追溯重算已发生的历史出库',
}[props.kind]))
const statusOptions = computed(() => props.kind === 'transfer'
  ? ['DRAFT', 'IN_TRANSIT', 'COMPLETED', 'CANCELLED', 'REVERSED']
  : ['DRAFT', 'CONFIRMED', 'CANCELLED', 'REVERSED'])

const query = useQuery<any[]>({
  queryKey: computed(() => ['inventory-documents', props.kind, status.value]),
  queryFn: () => {
    if (props.kind === 'transfer') return api.inventoryTransfers(status.value)
    if (props.kind === 'stocktake') return api.inventoryStocktakes(status.value)
    if (props.kind === 'purchase-return') return api.purchaseReturns({ status: status.value })
    return api.inventoryCostAdjustments(status.value)
  },
})
const rows = computed<any[]>(() => query.data.value ?? [])
const isFetching = computed(() => query.isFetching.value)
const refetch = query.refetch
const filteredRows = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  if (!text) return rows.value
  return rows.value.filter((row) => Object.values(row).some((value) => String(value ?? '').toLowerCase().includes(text)))
})

function documentNo(row: Record<string, unknown>) {
  return String(row[config.value.numberKey] || `#${row.id}`)
}

function createDocument() {
  router.push({ path: `${config.value.listPath}/new`, query: { from: route.fullPath } })
}

function openDetail(row: { id: number }) {
  router.push({ path: `${config.value.listPath}/${row.id}`, query: { from: route.fullPath } })
}
</script>

<style scoped>
.inventory-list-subtitle { margin-top: 5px; font-size: 12px; }
</style>
