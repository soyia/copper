<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">销售订单</div>
        <el-button type="primary" @click="$router.push('/sales-orders/new')">新建销售订单</el-button>
      </div>
      <div class="list-tools sales-order-filters">
        <el-input v-model="filters.keyword" class="sales-order-filter" clearable placeholder="订单号 / 标题 / 客户 / 履约仓库" />
        <PartnerRemoteSelect v-model="filters.customerName" class="sales-order-filter" type="CUSTOMER" value-field="name" clearable placeholder="客户" />
        <el-select v-model="filters.status" class="sales-order-filter" clearable placeholder="订单状态">
          <el-option label="暂存" value="DRAFT" />
          <el-option label="已确认" value="OPEN" />
          <el-option label="部分出库" value="PARTIAL_SHIPPED" />
          <el-option label="已出库" value="SHIPPED" />
          <el-option label="已作废" value="CANCELLED" />
        </el-select>
        <el-select v-model="filters.paymentStatus" class="sales-order-filter" clearable placeholder="结清状态">
          <el-option label="未结清" value="UNPAID" />
          <el-option label="部分结清" value="PARTIAL" />
          <el-option label="已结清" value="PAID" />
        </el-select>
        <el-select v-model="filters.paymentTerm" class="sales-order-filter" clearable placeholder="收款条件">
          <el-option label="确认后收款" value="POSTPAID" />
          <el-option label="需预付款" value="PREPAYMENT_REQUIRED" />
          <el-option label="全额预付" value="FULL_PREPAYMENT" />
        </el-select>
        <el-date-picker v-model="filters.dateRange" class="sales-order-filter" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" />
        <div class="list-tools-actions sales-order-filter-actions">
          <el-button @click="resetFilters">重置</el-button>
        </div>
      </div>
      <el-table :data="filteredSalesOrders" class="data-table" size="small" border show-overflow-tooltip max-height="calc(100vh - 286px)">
        <el-table-column label="订单号" width="220" align="center" fixed="left">
          <template #default="{ row }"><router-link class="table-link" :to="`/sales-orders/${row.id}`">{{ row.orderNo }}</router-link></template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="160" />
        <el-table-column prop="businessDate" label="日期" width="110" :formatter="formatDateColumn" />
        <el-table-column prop="customerName" label="客户" min-width="160" />
        <el-table-column prop="deliveryPlanDate" label="计划交付" width="110" :formatter="formatDateColumn" />
        <el-table-column prop="fulfillmentWarehouseName" label="履约仓库" min-width="120" />
        <el-table-column label="合计金额" width="130" align="right"><template #default="{ row }">{{ money(row.originalAmount) }}</template></el-table-column>
        <el-table-column label="折扣金额" width="100" align="right"><template #default="{ row }">{{ money(row.discountAmount) }}</template></el-table-column>
        <el-table-column label="调整金额" width="100" align="right"><template #default="{ row }">{{ signedMoney(row.adjustmentAmount) }}</template></el-table-column>
        <el-table-column label="实际金额" width="130" align="right"><template #default="{ row }">{{ money(row.totalAmount) }}</template></el-table-column>
        <el-table-column label="回款/票据" width="120" align="right"><template #default="{ row }">{{ money(row.receivedAmount) }}</template></el-table-column>
        <el-table-column label="应收调整" width="110" align="right"><template #default="{ row }">{{ money(row.adjustedAmount) }}</template></el-table-column>
        <el-table-column label="实收资金" width="120" align="right"><template #default="{ row }">{{ money(row.cashReceivedAmount) }}</template></el-table-column>
        <el-table-column label="待收金额" width="120" align="right">
          <template #default="{ row }"><span v-if="!['DRAFT', 'CANCELLED'].includes(row.status)">{{ money(row.outstandingAmount) }}</span><span v-else>-</span></template>
        </el-table-column>
        <el-table-column label="收款条件" width="120"><template #default="{ row }">{{ paymentTermName(row.paymentTerm) }}</template></el-table-column>
        <el-table-column label="发货前到账" width="170"><template #default="{ row }">{{ shipmentPaymentPolicyName(row.shipmentPaymentPolicy) }}</template></el-table-column>
        <el-table-column label="放货资金" width="180" align="right">
          <template #default="{ row }">
            <span v-if="row.shipmentPaymentPolicy === 'NONE'">-</span>
            <span v-else :class="Number(row.shipmentPaymentShortfall) > 0 ? 'text-danger' : 'text-success'">{{ money(row.shipmentFundedAmount) }} / {{ money(row.shipmentRequiredAmount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="预付进度" width="180">
          <template #default="{ row }">
            <span v-if="row.paymentTerm === 'POSTPAID'">-</span>
            <span v-else :class="Number(row.prepaymentShortfall) > 0 ? 'text-danger' : 'text-success'">{{ money(row.reservedPrepaymentAmount) }} / {{ money(row.requiredDepositAmount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结清状态" width="110" fixed="right">
          <template #default="{ row }">
            <el-tag v-if="['DRAFT', 'CANCELLED'].includes(row.status)" type="info" size="small">不适用</el-tag>
            <el-tag v-else :type="paymentStatusType(row.paymentStatus)" size="small">{{ paymentStatus(row.paymentStatus) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" fixed="right">
          <template #default="{ row }"><el-tag :type="salesOrderStatusType(row.status)" size="small">{{ salesOrderStatus(row.status) }}</el-tag></template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { api } from '@/api/client'
import { formatDateColumn, money, paymentStatus, paymentStatusType, salesOrderStatus, salesOrderStatusType } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import { matchesDateRange, matchesKeyword } from '@/utils/listFilters'

const filters = reactive({ keyword: '', customerName: '', status: '', paymentStatus: '', paymentTerm: '', dateRange: [] as string[] })

const { data: salesOrders } = useQuery({ queryKey: ['sales-orders'], queryFn: api.salesOrders })
const filteredSalesOrders = computed(() =>
  (salesOrders.value ?? []).filter(
    (item) =>
      (!filters.customerName || item.customerName === filters.customerName) &&
      (!filters.status || item.status === filters.status) &&
      (!filters.paymentStatus || (!['DRAFT', 'CANCELLED'].includes(item.status) && item.paymentStatus === filters.paymentStatus)) &&
      (!filters.paymentTerm || item.paymentTerm === filters.paymentTerm) &&
      matchesDateRange(item.businessDate, filters.dateRange) &&
      matchesKeyword(item, filters.keyword, ['orderNo', 'title', 'customerName', 'fulfillmentWarehouseName']),
  ),
)

function resetFilters() {
  filters.keyword = ''
  filters.customerName = ''
  filters.status = ''
  filters.paymentStatus = ''
  filters.paymentTerm = ''
  filters.dateRange = []
}

function paymentTermName(value: string) {
  if (value === 'PREPAYMENT_REQUIRED') return '需预付款'
  if (value === 'FULL_PREPAYMENT') return '全额预付'
  return '确认后收款'
}

function shipmentPaymentPolicyName(value: string) {
  if (value === 'SHIPMENT_PAID') return '本次发货金额全额到账'
  if (value === 'ORDER_PAID') return '整单金额全额到账'
  return '不校验到账金额'
}

function signedMoney(value: number) {
  const amount = Number(value || 0)
  return `${amount >= 0 ? '+' : '-'}${money(Math.abs(amount))}`
}

</script>

<style scoped>
.sales-order-filters {
  grid-template-columns: repeat(6, minmax(0, 1fr));
}

.sales-order-filter,
.sales-order-filters :deep(.el-input),
.sales-order-filters :deep(.el-select),
.sales-order-filters :deep(.el-date-editor),
.sales-order-filters :deep(.el-date-editor.el-input__wrapper) {
  min-width: 0;
  width: 100% !important;
}

.sales-order-filter-actions {
  grid-column: 1 / -1;
}

@media (max-width: 1400px) {
  .sales-order-filters {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .sales-order-filters {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
