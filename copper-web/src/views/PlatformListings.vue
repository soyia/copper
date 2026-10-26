<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">平台货源</div>
          <div class="muted" style="margin-top: 4px">这里预留全国铜供销平台联动。第一阶段只从库存批次选择可发布货源。</div>
        </div>
        <el-button type="primary">发布货源</el-button>
      </div>
      <div class="list-tools">
        <el-input v-model="filters.keyword" clearable placeholder="商品 / 规格摘要 / 批次号" />
        <el-select v-model="filters.warehouseId" clearable filterable placeholder="仓库">
          <el-option v-for="item in warehouses ?? []" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <div class="list-tools-actions">
          <el-button @click="resetFilters">重置</el-button>
        </div>
      </div>
      <el-table :data="filteredBatches" class="data-table" size="small" border show-overflow-tooltip max-height="calc(100vh - 300px)">
        <el-table-column prop="warehouseName" label="仓库" width="100" fixed="left" />
        <el-table-column prop="productName" label="商品" width="170" fixed="left" />
        <el-table-column prop="spec" label="规格摘要" min-width="240" />
        <el-table-column prop="batchNo" label="批次号" min-width="150" />
        <el-table-column label="可发布数量(kg)" width="150" align="right"><template #default="{ row }">{{ quantity(row.quantityAvailable) }}</template></el-table-column>
        <el-table-column label="参考价" width="120" align="right"><template #default="{ row }">{{ money(row.unitCost, 4) }}</template></el-table-column>
        <el-table-column label="平台状态" width="120" fixed="right"><template #default><el-tag size="small" type="info">未发布</el-tag></template></el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { api } from '@/api/client'
import { money, quantity } from '@/api/format'
import { matchesKeyword } from '@/utils/listFilters'

const filters = reactive({ keyword: '', warehouseId: undefined as number | undefined })
const { data: batches } = useQuery({
  queryKey: ['inventory-batches', 'platform'],
  queryFn: () => api.inventoryBatches({ availableOnly: true }),
})
const { data: warehouses } = useQuery({ queryKey: ['warehouses'], queryFn: api.warehouses })
const filteredBatches = computed(() =>
  (batches.value ?? []).filter(
    (item) =>
      (!filters.warehouseId || item.warehouseId === filters.warehouseId) &&
      matchesKeyword(item, filters.keyword, ['warehouseName', 'productName', 'spec', 'batchNo']),
  ),
)

function resetFilters() {
  filters.keyword = ''
  filters.warehouseId = undefined
}
</script>
