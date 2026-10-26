<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">送样 / 领用 / 损耗单</div>
          <div class="muted list-subtitle">非购销库存事项独立留痕，不产生销售应收；实物发放时立即扣库存，签字单随后归档。</div>
        </div>
        <RouterLink v-if="appStore.hasPermission('inventory.nontrade.manage')" to="/inventory/non-trade/new">
          <el-button type="primary">新建单据</el-button>
        </RouterLink>
      </div>
      <div class="list-tools">
        <el-input v-model="filters.keyword" clearable placeholder="单号 / 商品 / SKU / 批次 / 领用人 / 经办人" />
        <el-select v-model="filters.businessType" clearable placeholder="业务类型">
          <el-option v-for="item in businessTypes" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-select v-model="filters.status" clearable placeholder="状态">
          <el-option label="草稿待发放" value="DRAFT" />
          <el-option label="已发放待签字" value="ISSUED_PENDING_SIGNATURE" />
          <el-option label="已签字归档" value="CONFIRMED" />
          <el-option label="已冲正" value="REVERSED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
        <el-button @click="reset">重置</el-button>
        <el-button @click="refetch">刷新</el-button>
      </div>
      <el-table v-loading="isLoading" :data="filteredRows" border class="data-table">
        <el-table-column label="单号" min-width="220" align="center">
          <template #default="{ row }">
            <RouterLink class="business-link" :to="`/inventory/non-trade/${row.id}`">{{ row.documentNo }}</RouterLink>
          </template>
        </el-table-column>
        <el-table-column label="业务类型" width="120">
          <template #default="{ row }">{{ businessTypeName(row.businessType) }}</template>
        </el-table-column>
        <el-table-column prop="businessDate" label="业务日期" width="120" align="center" />
        <el-table-column label="商品明细" min-width="320">
          <template #default="{ row }">
            <div>{{ row.productName }}<span v-if="row.itemCount > 1"> 等 {{ row.itemCount }} 项</span></div>
            <div class="multiline-secondary">{{ row.sku || '-' }} · {{ row.warehouseName }}/{{ row.batchNo }}</div>
          </template>
        </el-table-column>
        <el-table-column label="明细数" width="100" align="center">
          <template #default="{ row }">{{ row.itemCount || 1 }} 项</template>
        </el-table-column>
        <el-table-column prop="recipient" label="实际领用人" width="140" />
        <el-table-column prop="handlerName" label="经办人 / 发放人" width="150" />
        <el-table-column prop="reason" label="原因" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ statusName(row.status, row.direction) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <RouterLink :to="`/inventory/non-trade/${row.id}`"><el-button text type="primary">查看</el-button></RouterLink>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { api } from '@/api/client'
import { matchesKeyword } from '@/utils/listFilters'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()
const filters = reactive({ keyword: '', businessType: '', status: '' })
const businessTypes = [
  { value: 'SAMPLE_OUT', label: '送样' },
  { value: 'INTERNAL_USE', label: '内部领用' },
  { value: 'NORMAL_LOSS', label: '合理损耗' },
  { value: 'OTHER_OUT', label: '其他出库' },
]
const { data, isLoading, refetch } = useQuery({ queryKey: ['inventory-non-trade'], queryFn: () => api.inventoryNonTrade() })
const filteredRows = computed(() => (data.value || []).filter((row) =>
  (!filters.businessType || row.businessType === filters.businessType) &&
  (!filters.status || row.status === filters.status) &&
  matchesKeyword(row, filters.keyword, ['documentNo', 'productName', 'sku', 'spec', 'batchNo', 'warehouseName', 'recipient', 'handlerName', 'reason'])))

function reset() { filters.keyword = ''; filters.businessType = ''; filters.status = '' }
function businessTypeName(value: string) { return businessTypes.find((item) => item.value === value)?.label || value }
function statusName(value: string, direction?: string) {
  if (value === 'CONFIRMED') return direction === 'IN' ? '已确认入库' : '已签字归档'
  return ({ DRAFT: '草稿待发放', ISSUED_PENDING_SIGNATURE: '已发放待签字', CANCELLED: '已取消', REVERSED: '已冲正' } as Record<string, string>)[value] || value
}
function statusType(value: string) { return value === 'CONFIRMED' ? 'success' : ['DRAFT', 'ISSUED_PENDING_SIGNATURE'].includes(value) ? 'warning' : 'info' }
</script>

<style scoped>
.list-subtitle { margin-top: 5px; }
</style>
