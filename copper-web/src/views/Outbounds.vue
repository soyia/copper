<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">销售出库单</div>
        <el-button type="primary" @click="$router.push('/outbounds/new')">新建出库单</el-button>
      </div>
      <div class="list-tools outbound-list-tools">
        <el-input v-model="filters.keyword" clearable placeholder="出库单 / 销售订单 / 客户 / 仓库" />
        <PartnerRemoteSelect v-model="filters.customerName" type="CUSTOMER" value-field="name" clearable placeholder="客户" />
        <el-select v-model="filters.paymentStatus" clearable placeholder="回款状态">
          <el-option label="未回款" value="UNPAID" />
          <el-option label="部分回款" value="PARTIAL" />
          <el-option label="已回款" value="PAID" />
        </el-select>
        <el-select v-model="filters.status" clearable placeholder="物理状态"><el-option label="草稿" value="DRAFT" /><el-option label="已发货" value="SHIPPED" /><el-option label="已反向" value="REVERSED" /><el-option label="已作废" value="CANCELLED" /></el-select>
        <el-select v-model="filters.followup" clearable placeholder="待处理"><el-option label="单据信息待补录" value="TO_COMPLETE" /><el-option label="最终价格待确定" value="PENDING_PRICING" /></el-select>
        <el-date-picker class="outbound-date-filter" v-model="filters.dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" />
        <div class="list-tools-actions">
          <el-button @click="resetFilters">重置</el-button>
        </div>
      </div>
      <el-table :data="filteredOutbounds" class="data-table" size="small" border show-overflow-tooltip max-height="calc(100vh - 286px)">
        <el-table-column label="单据号" width="170" fixed="left">
          <template #default="{ row }"><router-link class="table-link" :to="`/outbounds/${row.id}`">{{ row.orderNo }}</router-link></template>
        </el-table-column>
        <el-table-column label="销售订单" min-width="170">
          <template #default="{ row }">
            <router-link v-if="row.salesOrderId" class="table-link" :to="relatedDetailRoute(route, `/sales-orders/${row.salesOrderId}`)">{{ row.salesOrderNo }}</router-link>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="businessDate" label="日期" width="110" :formatter="formatDateColumn" />
        <el-table-column prop="customerName" label="客户" min-width="140" />
        <el-table-column prop="warehouseName" label="仓库" width="100" />
        <el-table-column label="来源" width="110"><template #default="{ row }">{{ row.salesOrderId ? '销售订单' : '无订单直出' }}</template></el-table-column>
        <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="row.status === 'SHIPPED' ? 'success' : row.status === 'REVERSED' ? 'danger' : 'info'" size="small">{{ row.status === 'SHIPPED' ? '已发货' : row.status === 'REVERSED' ? '已反向' : row.status === 'CANCELLED' ? '已作废' : '草稿' }}</el-tag></template></el-table-column>
        <el-table-column label="待处理" width="140"><template #default="{ row }"><span v-if="row.settlementStatus === 'PENDING_PRICING'" class="text-danger">最终价格待确定</span><span v-else-if="row.completenessStatus === 'TO_COMPLETE'" class="text-warning">单据信息待补录</span><span v-else>-</span></template></el-table-column>
        <el-table-column label="数量(kg)" width="120" align="right"><template #default="{ row }">{{ quantity(row.totalQuantity) }}</template></el-table-column>
        <el-table-column label="包装数" width="110" align="right"><template #default="{ row }">{{ quantity(row.packageQuantity) }}</template></el-table-column>
        <el-table-column label="金额" width="130" align="right"><template #default="{ row }">{{ money(row.totalAmount) }}</template></el-table-column>
        <el-table-column label="已回款" width="130" align="right"><template #default="{ row }">{{ money(row.paidAmount) }}</template></el-table-column>
        <el-table-column label="回款状态" width="110" fixed="right">
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
import { formatDateColumn, money, paymentStatus, paymentStatusType, quantity } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import { matchesDateRange, matchesKeyword } from '@/utils/listFilters'
import { relatedDetailRoute } from '@/utils/navigation'

const route = useRoute()
const filters = reactive({ keyword: '', customerName: '', paymentStatus: '', status: '', followup: '', dateRange: [] as string[] })

const { data: outbounds } = useQuery({ queryKey: ['outbounds'], queryFn: () => api.outbounds() })
const filteredOutbounds = computed(() =>
  (outbounds.value ?? []).filter(
    (item) =>
      (!filters.customerName || item.customerName === filters.customerName) &&
      (!filters.paymentStatus || item.paymentStatus === filters.paymentStatus) &&
      (!filters.status || item.status === filters.status) &&
      (!filters.followup || item.completenessStatus === filters.followup || item.settlementStatus === filters.followup) &&
      matchesDateRange(item.businessDate, filters.dateRange) &&
      matchesKeyword(item, filters.keyword, ['orderNo', 'salesOrderNo', 'customerName', 'warehouseName']),
  ),
)

function resetFilters() {
  filters.keyword = ''
  filters.customerName = ''
  filters.paymentStatus = ''
  filters.status = ''
  filters.followup = ''
  filters.dateRange = []
}
</script>

<style scoped>
.outbound-list-tools {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.outbound-list-tools > *,
.outbound-list-tools :deep(.el-input),
.outbound-list-tools :deep(.el-select),
.outbound-list-tools :deep(.el-date-editor),
.outbound-list-tools :deep(.el-date-editor.el-input__wrapper) {
  width: 100%;
  min-width: 0;
}

@media (max-width: 1280px) {
  .outbound-list-tools {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .outbound-list-tools {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
