<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">库存勾稽</div>
          <div class="muted reconciliation-subtitle">核对批次账、库存汇总账、出库占用、调拨在途与逐件包装实物；异常必须回到来源单据处理，不能直接改余额。</div>
        </div>
        <el-button @click="refetch">刷新核对</el-button>
      </div>
      <div class="list-tools">
        <el-input v-model="keyword" clearable placeholder="商品编码 / 商品 / 仓库" />
        <el-select v-model="warehouseId" clearable filterable placeholder="全部仓库">
          <el-option v-for="item in warehouses || []" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-checkbox v-model="mismatchesOnly">只看异常</el-checkbox>
      </div>
      <el-alert v-if="issueCount" :title="issueTitle" :type="mismatchCount ? 'error' : 'warning'" :closable="false" show-icon class="reconciliation-alert" />
      <el-alert v-else-if="!isFetching" title="当前筛选范围内各库存口径一致" type="success" :closable="false" show-icon class="reconciliation-alert" />
      <el-table v-loading="isFetching" :data="filteredRows" border size="small" class="data-table" max-height="calc(100vh - 300px)" :row-key="rowKey">
        <el-table-column label="状态" width="90" fixed="left">
          <template #default="{ row }"><el-tag :type="statusTagType(row.status)">{{ statusText(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="风险说明" min-width="210">
          <template #default="{ row }">{{ riskDescription(row) }}</template>
        </el-table-column>
        <el-table-column prop="sku" label="商品编码" min-width="130" />
        <el-table-column prop="productName" label="商品" min-width="170" />
        <el-table-column prop="warehouseName" label="仓库" width="120" />
        <el-table-column label="批次账结存" width="130" align="right"><template #default="{ row }">{{ quantity(row.batchQuantity) }}</template></el-table-column>
        <el-table-column label="汇总账结存" width="130" align="right"><template #default="{ row }">{{ quantity(row.balanceQuantity) }}</template></el-table-column>
        <el-table-column label="数量差异" width="120" align="right"><template #default="{ row }"><span :class="differenceClass(row.quantityDifference)">{{ signed(row.quantityDifference) }}</span></template></el-table-column>
        <el-table-column label="出库草稿占用" width="130" align="right"><template #default="{ row }">{{ quantity(row.reservedQuantity) }}</template></el-table-column>
        <el-table-column label="自由可用" width="120" align="right"><template #default="{ row }"><span :class="differenceClass(Math.min(row.freeQuantity, 0))">{{ quantity(row.freeQuantity) }}</span></template></el-table-column>
        <el-table-column label="调拨在途" width="120" align="right"><template #default="{ row }">{{ quantity(row.inTransitQuantity) }}</template></el-table-column>
        <el-table-column label="逐件批次结存" width="135" align="right"><template #default="{ row }">{{ quantity(row.trackedBatchQuantity) }}</template></el-table-column>
        <el-table-column label="逐件实物合计" width="135" align="right"><template #default="{ row }">{{ quantity(row.trackedPackageQuantity) }}</template></el-table-column>
        <el-table-column label="实物差异" width="120" align="right"><template #default="{ row }"><span :class="differenceClass(row.packageDifference)">{{ signed(row.packageDifference) }}</span></template></el-table-column>
        <template v-if="appStore.hasPermission('inventory.cost.view')">
          <el-table-column label="批次账成本" width="135" align="right"><template #default="{ row }">{{ money(row.batchAmountCost) }}</template></el-table-column>
          <el-table-column label="汇总账成本" width="135" align="right"><template #default="{ row }">{{ money(row.balanceAmountCost) }}</template></el-table-column>
          <el-table-column label="成本差异" width="125" align="right"><template #default="{ row }"><span :class="differenceClass(row.costDifference)">{{ signedMoney(row.costDifference) }}</span></template></el-table-column>
        </template>
      </el-table>
      <div v-if="appStore.hasPermission('inventory.cost.view') && openingAlignments?.length" class="opening-alignment-section">
        <div class="panel-title">上线期初成本对齐记录</div>
        <div class="muted opening-alignment-note">仅校正成本模块启用前的当前批次金额，不重写历史出入库流水；调整前后快照永久保留。</div>
        <el-table :data="openingAlignments" border size="small" class="data-table">
          <el-table-column prop="batchNo" label="批次" min-width="165" />
          <el-table-column label="商品" min-width="210">
            <template #default="{ row }">
              <div>{{ row.productName }}</div>
              <div class="muted">{{ row.sku }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="warehouseName" label="仓库" width="100" />
          <el-table-column label="数量快照" width="100" align="right"><template #default="{ row }">{{ quantity(row.quantitySnapshot) }}</template></el-table-column>
          <el-table-column label="成本单价" width="110" align="right"><template #default="{ row }">{{ money(row.unitCostSnapshot, 4) }}</template></el-table-column>
          <el-table-column label="调整前" width="110" align="right"><template #default="{ row }">{{ money(row.batchAmountBefore) }}</template></el-table-column>
          <el-table-column label="调整后" width="110" align="right"><template #default="{ row }">{{ money(row.batchAmountAfter) }}</template></el-table-column>
          <el-table-column label="调整金额" width="110" align="right"><template #default="{ row }"><span :class="differenceClass(row.differenceAmount)">{{ signedMoney(row.differenceAmount) }}</span></template></el-table-column>
          <el-table-column prop="alignedAt" label="对齐时间" width="160" :formatter="formatDateTimeColumn" />
        </el-table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { api } from '@/api/client'
import { formatDateTimeColumn, money, quantity } from '@/api/format'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()
const keyword = ref('')
const warehouseId = ref<number>()
const mismatchesOnly = ref(true)
const { data: warehouses } = useQuery({ queryKey: ['warehouses'], queryFn: api.warehouses })
const { data: openingAlignments } = useQuery({
  queryKey: ['inventory-opening-cost-alignments'],
  queryFn: api.inventoryOpeningCostAlignments,
  enabled: computed(() => appStore.hasPermission('inventory.cost.view')),
})
const query = useQuery({
  queryKey: computed(() => ['inventory-reconciliation', warehouseId.value, mismatchesOnly.value]),
  queryFn: () => api.inventoryReconciliation({ warehouseId: warehouseId.value, mismatchesOnly: mismatchesOnly.value }),
})
const rows = computed(() => query.data.value || [])
const isFetching = computed(() => query.isFetching.value)
const refetch = query.refetch
const filteredRows = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  if (!text) return rows.value
  return rows.value.filter((row) => [row.sku, row.productName, row.warehouseName].some((value) => value.toLowerCase().includes(text)))
})
const mismatchCount = computed(() => rows.value.filter((row) => row.status === 'MISMATCH').length)
const riskCount = computed(() => rows.value.filter((row) => row.status === 'RISK').length)
const issueCount = computed(() => mismatchCount.value + riskCount.value)
const issueTitle = computed(() => {
  const parts = []
  if (mismatchCount.value) parts.push(`勾稽异常 ${mismatchCount.value} 项`)
  if (riskCount.value) parts.push(`经营风险 ${riskCount.value} 项`)
  return `发现${parts.join('、')}；请按风险说明回到来源单据处理，不能直接改余额。`
})
function rowKey(row: { productId: number; warehouseId: number }) { return `${row.productId}-${row.warehouseId}` }
function statusTagType(status: 'MATCH' | 'RISK' | 'MISMATCH') { return status === 'MATCH' ? 'success' : status === 'RISK' ? 'warning' : 'danger' }
function statusText(status: 'MATCH' | 'RISK' | 'MISMATCH') { return status === 'MATCH' ? '一致' : status === 'RISK' ? '风险' : '异常' }
function riskDescription(row: { status: string; negativeSourceCount: number; unavailableBatchCount: number }) {
  const reasons = []
  if (row.status === 'MISMATCH') reasons.push('账批 / 占用 / 包装 / 成本口径不一致')
  if (row.negativeSourceCount) reasons.push(`负数来源 ${row.negativeSourceCount} 个`)
  if (row.unavailableBatchCount) reasons.push(`无自由库存批次 ${row.unavailableBatchCount} 个`)
  return reasons.join('；') || '各口径一致'
}
function differenceClass(value: number | null) { return Number(value || 0) === 0 ? '' : 'amount-negative' }
function signed(value: number | null) { return `${Number(value || 0) > 0 ? '+' : ''}${quantity(value || 0)}` }
function signedMoney(value: number | null) { return `${Number(value || 0) > 0 ? '+' : ''}${money(value || 0)}` }
</script>

<style scoped>
.reconciliation-subtitle { margin-top: 5px; font-size: 12px; }
.reconciliation-alert { margin: 0 16px 14px; }
.amount-negative { color: var(--el-color-danger); font-weight: 600; }
.opening-alignment-section { margin-top: 22px; }
.opening-alignment-note { margin: 5px 0 10px; font-size: 12px; }
</style>
