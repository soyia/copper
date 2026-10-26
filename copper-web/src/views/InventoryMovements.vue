<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">库存流水</div>
          <div class="muted movement-subtitle">每次入库、出库、调拨、盘盈亏、退货、成本调整和冲正的不可覆盖记录</div>
        </div>
        <el-button @click="refetch">刷新</el-button>
      </div>
      <div class="list-tools">
        <el-input v-model="keyword" clearable placeholder="流水号 / 商品 / 仓库 / 批次 / 来源" />
        <el-select v-model="warehouseId" clearable filterable placeholder="全部仓库">
          <el-option v-for="item in warehouses || []" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
      </div>
      <el-table v-loading="isFetching" :data="filteredRows" border size="small" class="data-table" max-height="calc(100vh - 260px)">
        <el-table-column prop="movementNo" label="流水号" min-width="190" fixed="left" />
        <el-table-column label="类型" width="145"><template #default="{ row }">{{ movementType(row.movementType) }}</template></el-table-column>
        <el-table-column prop="warehouseName" label="仓库" width="120" />
        <el-table-column prop="productName" label="商品" min-width="170" />
        <el-table-column prop="batchNo" label="批次" min-width="150" />
        <el-table-column label="数量变化" width="140" align="right"><template #default="{ row }"><span :class="Number(row.quantity) < 0 ? 'amount-negative' : 'amount-positive'">{{ signed(row.quantity) }}</span></template></el-table-column>
        <el-table-column v-if="appStore.hasPermission('inventory.cost.view')" label="成本单价" width="125" align="right"><template #default="{ row }">{{ money(row.unitCost, 4) }}</template></el-table-column>
        <el-table-column v-if="appStore.hasPermission('inventory.cost.view')" label="金额变化" width="135" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
        <el-table-column label="来源单据" min-width="200">
          <template #default="{ row }">
            <RouterLink v-if="sourceRoute(row)" class="business-link" :to="sourceRoute(row)!">{{ row.sourceDocumentNo }}</RouterLink>
            <span v-else>{{ row.sourceDocumentNo || `${row.refType} #${row.refId}` }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="memo" label="说明" min-width="220" />
        <el-table-column prop="occurredAt" label="发生时间" width="175" :formatter="formatDateTimeColumn" />
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { api, type InventoryMovement } from '@/api/client'
import { formatDateTimeColumn, money, quantity } from '@/api/format'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()
const keyword = ref('')
const warehouseId = ref<number>()
const { data: warehouses } = useQuery({ queryKey: ['warehouses'], queryFn: api.warehouses })
const query = useQuery({ queryKey: computed(() => ['inventory-movements', warehouseId.value]), queryFn: () => api.inventoryMovements({ warehouseId: warehouseId.value }) })
const rows = computed(() => query.data.value || [])
const isFetching = computed(() => query.isFetching.value)
const refetch = query.refetch
const filteredRows = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  if (!text) return rows.value
  return rows.value.filter((row) => [row.movementNo, row.movementType, row.productName, row.warehouseName, row.batchNo, row.refType, row.refId, row.memo].some((value) => String(value ?? '').toLowerCase().includes(text)))
})
function signed(value: number) { return `${Number(value) > 0 ? '+' : ''}${quantity(value)}` }
function sourceRoute(row: InventoryMovement) {
  const target = ({
    INBOUND: { path: `/inbounds/${row.sourceDocumentId}`, permission: 'inbound.view' },
    OUTBOUND: { path: `/outbounds/${row.sourceDocumentId}`, permission: 'outbound.view' },
    INVENTORY_TRANSFER: { path: `/inventory/transfers/${row.sourceDocumentId}`, permission: 'inventory.view' },
    INVENTORY_STOCKTAKE: { path: `/inventory/stocktakes/${row.sourceDocumentId}`, permission: 'inventory.view' },
    PURCHASE_RETURN: { path: `/inventory/purchase-returns/${row.sourceDocumentId}`, permission: 'purchase.view' },
    INVENTORY_COST_ADJUSTMENT: { path: `/inventory/cost-adjustments/${row.sourceDocumentId}`, permission: 'inventory.cost.view' },
    LANDED_COST_ALLOCATION: { path: `/inventory/landed-cost-allocations/${row.sourceDocumentId}`, permission: 'landed.cost.view' },
  } as Record<string, { path: string; permission: string }>)[row.sourceDocumentType]
  return target && appStore.hasPermission(target.permission) ? { path: target.path, query: { from: '/inventory/movements' } } : undefined
}
function movementType(value: string) { return ({ INBOUND: '采购入库', OUTBOUND: '销售出库', RETURN: '销售退货', RETURN_REVERSAL: '销售退货冲正', TRANSFER_OUT: '调拨发出', TRANSFER_IN: '调拨入库', TRANSFER_REVERSE_OUT: '调拨冲正出', TRANSFER_REVERSE_IN: '调拨冲正入', STOCKTAKE_GAIN: '盘盈', STOCKTAKE_LOSS: '盘亏', STOCKTAKE_REVERSAL: '盘点冲正', PURCHASE_RETURN: '采购退货', PURCHASE_RETURN_REVERSAL: '采购退货冲正', COST_ADJUSTMENT: '成本调整', COST_ADJUSTMENT_REVERSAL: '成本调整冲正', LAND_COST: '采购费用资本化', LAND_COST_ROLLBACK: '采购费用冲正', CANCEL: '反向出库' } as Record<string, string>)[value] || value }
</script>

<style scoped>
.movement-subtitle { margin-top: 5px; font-size: 12px; }
.amount-negative { color: var(--el-color-danger); }
.amount-positive { color: var(--el-color-success); }
</style>
