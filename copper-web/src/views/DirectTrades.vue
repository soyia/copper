<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">直运购销</div>
          <div class="muted">供应商直接发给客户；客户确认实收后形成应收、应付，不经过仓库库存。</div>
        </div>
        <el-button v-if="appStore.hasPermission('direct.trade.manage')" type="primary" @click="$router.push('/direct-trades/new')">
          新建直运购销
        </el-button>
      </div>
      <div class="list-tools">
        <el-input v-model="keyword" clearable placeholder="单号 / 客户 / 供应商" style="width: 300px" />
        <el-select v-model="status" clearable placeholder="状态" style="width: 160px">
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </div>
      <el-table :data="filteredRows" class="data-table" size="small" border show-overflow-tooltip max-height="calc(100vh - 270px)">
        <el-table-column label="直运单号" width="190" fixed="left">
          <template #default="{ row }"><router-link class="table-link" :to="`/direct-trades/${row.id}`">{{ row.tradeNo }}</router-link></template>
        </el-table-column>
        <el-table-column prop="businessDate" label="业务日期" width="112" />
        <el-table-column prop="customerName" label="客户" min-width="150" />
        <el-table-column prop="supplierName" label="供应商" min-width="150" />
        <el-table-column prop="baseUnitSummary" label="商品数量" min-width="150" />
        <el-table-column label="销售金额" width="125" align="right"><template #default="{ row }">{{ money(row.saleOrderAmount) }}</template></el-table-column>
        <el-table-column v-if="canViewCost" label="采购金额" width="125" align="right"><template #default="{ row }">{{ money(row.purchaseOrderAmount) }}</template></el-table-column>
        <el-table-column v-if="canViewCost" label="预计毛利" width="125" align="right">
          <template #default="{ row }"><span :class="Number(row.grossProfitAmount) < 0 ? 'text-danger' : 'text-success'">{{ money(row.grossProfitAmount) }}</span></template>
        </el-table-column>
        <el-table-column label="已形成应收" width="125" align="right"><template #default="{ row }">{{ money(row.saleReceivableAmount) }}</template></el-table-column>
        <el-table-column label="已形成应付" width="125" align="right"><template #default="{ row }">{{ money(row.purchasePayableAmount) }}</template></el-table-column>
        <el-table-column label="状态" width="112" fixed="right">
          <template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ statusName(row.status) }}</el-tag></template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { api } from '@/api/client'
import { money } from '@/api/format'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()
const keyword = ref('')
const status = ref('')
const canViewCost = computed(() => appStore.hasPermission('inventory.cost.view') || appStore.hasPermission('purchase.view'))
const { data: rows } = useQuery({ queryKey: ['direct-trades'], queryFn: api.directTrades })
const statusOptions = [
  { value: 'DRAFT', label: '暂存' },
  { value: 'CONFIRMED', label: '待交付' },
  { value: 'PARTIAL_DELIVERED', label: '部分交付' },
  { value: 'DELIVERED', label: '已交付' },
  { value: 'CANCELLED', label: '已作废' },
]
const filteredRows = computed(() => (rows.value ?? []).filter((row) => {
  const word = keyword.value.trim().toLowerCase()
  return (!status.value || row.status === status.value)
    && (!word || [row.tradeNo, row.customerName, row.supplierName].some((value) => String(value || '').toLowerCase().includes(word)))
}))
function statusName(value: string) {
  return statusOptions.find((item) => item.value === value)?.label || value
}
function statusType(value: string) {
  if (value === 'DELIVERED') return 'success'
  if (value === 'CANCELLED') return 'info'
  if (value === 'PARTIAL_DELIVERED') return 'warning'
  return value === 'DRAFT' ? 'info' : 'primary'
}
</script>
