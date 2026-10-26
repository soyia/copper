<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">应收视图</div>
        <div class="muted">第一阶段先按出库单展示应收，后续增加完整账户流水和对账单。</div>
      </div>
      <div class="list-tools">
        <el-input v-model="filters.keyword" clearable placeholder="客户 / 出库单 / 仓库" />
        <PartnerRemoteSelect v-model="filters.customerName" type="CUSTOMER" value-field="name" clearable placeholder="客户" />
        <el-select v-model="filters.paymentStatus" clearable placeholder="回款状态">
          <el-option label="未回款" value="UNPAID" />
          <el-option label="部分回款" value="PARTIAL" />
          <el-option label="已回款" value="PAID" />
        </el-select>
        <el-date-picker v-model="filters.dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" />
        <div class="list-tools-actions">
          <el-button @click="resetFilters">重置</el-button>
        </div>
      </div>
      <el-table :data="rows" class="data-table" size="small" border show-overflow-tooltip max-height="calc(100vh - 286px)">
        <el-table-column prop="customerName" label="客户" width="170" fixed="left" />
        <el-table-column label="出库单" width="190" fixed="left"><template #default="{ row }"><router-link class="table-link" :to="relatedDetailRoute(route, `/outbounds/${row.id}`)">{{ row.orderNo }}</router-link></template></el-table-column>
        <el-table-column prop="businessDate" label="出库日期" width="110" :formatter="formatDateColumn" />
        <el-table-column prop="warehouseName" label="仓库" width="110" />
        <el-table-column label="应收金额" width="130" align="right"><template #default="{ row }">{{ money(row.totalAmount) }}</template></el-table-column>
        <el-table-column label="已回款" width="130" align="right"><template #default="{ row }">{{ money(row.paidAmount) }}</template></el-table-column>
        <el-table-column label="未回款" width="130" align="right"><template #default="{ row }">{{ money(row.totalAmount - row.paidAmount) }}</template></el-table-column>
        <el-table-column label="状态" width="110" fixed="right">
          <template #default="{ row }"><el-tag :type="paymentStatusType(row.paymentStatus)" size="small">{{ paymentStatus(row.paymentStatus) }}</el-tag></template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive } from 'vue'
import { useRoute } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { api } from '@/api/client'
import { formatDateColumn, money, paymentStatus, paymentStatusType } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import { matchesDateRange, matchesKeyword } from '@/utils/listFilters'
import { relatedDetailRoute } from '@/utils/navigation'

const route = useRoute()
const filters = reactive({ keyword: '', customerName: '', paymentStatus: '', dateRange: [] as string[] })
const { data: outbounds } = useQuery({ queryKey: ['outbounds'], queryFn: () => api.outbounds() })
const rows = computed(() =>
  [...(outbounds.value ?? [])]
    .filter(
      (item) =>
        (!filters.customerName || item.customerName === filters.customerName) &&
        (!filters.paymentStatus || item.paymentStatus === filters.paymentStatus) &&
        matchesDateRange(item.businessDate, filters.dateRange) &&
        matchesKeyword(item, filters.keyword, ['customerName', 'orderNo', 'warehouseName']),
    )
    .sort((a, b) => (a.paymentStatus > b.paymentStatus ? 1 : -1)),
)

function resetFilters() {
  filters.keyword = ''
  filters.customerName = ''
  filters.paymentStatus = ''
  filters.dateRange = []
}
</script>
