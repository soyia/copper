<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">采购订单</div>
        <el-button type="primary" @click="$router.push('/purchase-orders/new')">新建采购订单</el-button>
      </div>
      <div class="list-tools">
        <el-input v-model="filters.keyword" clearable placeholder="订单号 / 供应商 / 入库仓库 / 方式" />
        <PartnerRemoteSelect v-model="filters.supplierName" type="SUPPLIER" value-field="name" clearable placeholder="供应商" />
        <el-select v-model="filters.status" clearable placeholder="订单状态">
          <el-option label="暂存" value="DRAFT" />
          <el-option label="已订货" value="ORDERED" />
          <el-option label="部分到货" value="PARTIAL_RECEIVED" />
          <el-option label="已到齐" value="RECEIVED" />
          <el-option label="已作废" value="CANCELLED" />
        </el-select>
        <el-select v-model="filters.paymentStatus" clearable placeholder="付款状态">
          <el-option label="未结清" value="UNPAID" />
          <el-option label="部分" value="PARTIAL" />
          <el-option label="已结清" value="PAID" />
        </el-select>
        <el-date-picker v-model="filters.dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" />
        <div class="list-tools-actions">
          <el-button @click="resetFilters">重置</el-button>
        </div>
      </div>
      <el-table :data="filteredPurchaseOrders" class="data-table" size="small" border show-overflow-tooltip max-height="calc(100vh - 286px)">
        <el-table-column label="订单号" width="180" fixed="left">
          <template #default="{ row }"><router-link class="table-link" :to="`/purchase-orders/${row.id}`">{{ row.orderNo }}</router-link></template>
        </el-table-column>
        <el-table-column prop="supplierName" label="供应商" min-width="150" />
        <el-table-column prop="businessDate" label="采购日期" width="110" :formatter="formatDateColumn" />
        <el-table-column prop="expectedDate" label="预计到货" width="110" :formatter="formatDateColumn" />
        <el-table-column prop="expectedWarehouseName" label="入库仓库" min-width="120" />
        <el-table-column label="方式" width="100"><template #default="{ row }">{{ purchaseSource(row.sourceType) }}</template></el-table-column>
        <el-table-column label="采购量" width="110" align="right"><template #default="{ row }">{{ quantity(row.totalQuantity) }}</template></el-table-column>
        <el-table-column label="已到货" width="110" align="right"><template #default="{ row }">{{ quantity(row.receivedQuantity) }}</template></el-table-column>
        <el-table-column label="合计金额" width="130" align="right"><template #default="{ row }">{{ money(row.originalAmount) }}</template></el-table-column>
        <el-table-column label="折扣金额" width="130" align="right"><template #default="{ row }">{{ money(row.discountAmount) }}</template></el-table-column>
        <el-table-column label="调整金额" width="130" align="right"><template #default="{ row }">{{ signedMoney(row.adjustmentAmount) }}</template></el-table-column>
        <el-table-column label="实际金额" width="130" align="right"><template #default="{ row }">{{ money(row.totalAmount) }}</template></el-table-column>
        <el-table-column label="已付" width="130" align="right"><template #default="{ row }">{{ money(row.paidAmount) }}</template></el-table-column>
        <el-table-column label="付款" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'DRAFT'" type="info" size="small">未生效</el-tag>
            <el-tag v-else :type="paymentStatusType(row.paymentStatus)" size="small">{{ paymentStatus(row.paymentStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="订单状态" width="100" fixed="right">
          <template #default="{ row }"><el-tag :type="purchaseStatusType(row.status)" size="small">{{ purchaseStatus(row.status) }}</el-tag></template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { api } from '@/api/client'
import { formatDateColumn, money, paymentStatus, paymentStatusType, purchaseSource, purchaseStatus, purchaseStatusType, quantity } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import { matchesDateRange, matchesKeyword } from '@/utils/listFilters'

const filters = reactive({ keyword: '', supplierName: '', status: '', paymentStatus: '', dateRange: [] as string[] })

const { data: purchaseOrders } = useQuery({ queryKey: ['purchase-orders'], queryFn: api.purchaseOrders })

const filteredPurchaseOrders = computed(() =>
  (purchaseOrders.value ?? []).filter(
    (item) =>
      (!filters.supplierName || item.supplierName === filters.supplierName) &&
      (!filters.status || item.status === filters.status) &&
      (!filters.paymentStatus || (!['DRAFT', 'CANCELLED'].includes(item.status) && item.paymentStatus === filters.paymentStatus)) &&
      matchesDateRange(item.businessDate, filters.dateRange) &&
      matchesKeyword(item, filters.keyword, ['orderNo', 'supplierName', 'expectedWarehouseName', 'sourceType']),
  ),
)

function resetFilters() {
  filters.keyword = ''
  filters.supplierName = ''
  filters.status = ''
  filters.paymentStatus = ''
  filters.dateRange = []
}

function signedMoney(value: number) {
  const amount = Number(value || 0)
  return `${amount >= 0 ? '+' : '-'}${money(Math.abs(amount))}`
}
</script>
