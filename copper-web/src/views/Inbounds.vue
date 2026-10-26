<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">入库单</div>
        <el-button type="primary" @click="$router.push('/inbounds/new')">新建入库单</el-button>
      </div>
      <div class="list-tools">
        <el-input v-model="filters.keyword" clearable placeholder="单据号 / 发货单号 / 供应商 / 仓库" />
        <PartnerRemoteSelect v-model="filters.supplierName" type="SUPPLIER" value-field="name" clearable placeholder="供应商" />
        <el-select v-model="filters.status" clearable placeholder="状态">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="待审核" value="PENDING" />
          <el-option label="已拒绝" value="REJECTED" />
          <el-option label="已入库" value="STOCKED" />
          <el-option label="已取消" value="CANCELLED" />
        </el-select>
        <el-date-picker v-model="filters.dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" />
        <div class="list-tools-actions">
          <el-button @click="resetFilters">重置</el-button>
        </div>
      </div>
      <el-table :data="filteredInbounds" class="data-table" size="small" border show-overflow-tooltip max-height="calc(100vh - 286px)">
        <el-table-column label="单据号" width="220" align="center" fixed="left">
          <template #default="{ row }"><router-link class="table-link" :to="`/inbounds/${row.id}`">{{ row.orderNo }}</router-link></template>
        </el-table-column>
        <el-table-column prop="businessDate" label="日期" width="110" :formatter="formatDateColumn" />
        <el-table-column prop="supplierName" label="供应商" min-width="140" />
        <el-table-column prop="warehouseName" label="仓库" width="100" />
        <el-table-column prop="quantitySummary" label="业务数量" min-width="150" />
        <el-table-column label="金额" width="130" align="right"><template #default="{ row }">{{ money(row.totalAmount) }}</template></el-table-column>
        <el-table-column label="已付" width="120" align="right"><template #default="{ row }">{{ money(row.paidAmount) }}</template></el-table-column>
        <el-table-column label="付款" width="100">
          <template #default="{ row }"><el-tag :type="paymentStatusType(row.paymentStatus)" size="small">{{ paymentStatus(row.paymentStatus) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="采购订单" width="220" align="center">
          <template #default="{ row }">
            <router-link v-if="row.purchaseOrderId" class="table-link" :to="relatedDetailRoute(route, `/purchase-orders/${row.purchaseOrderId}`)">{{ row.purchaseOrderNo }}</router-link>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="sendNumber" label="发货单号" min-width="220" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="inboundStatusType(row.status)" size="small">
              {{ inboundStatus(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button v-if="['DRAFT', 'REJECTED'].includes(row.status)" text type="primary" @click="$router.push(`/inbounds/${row.id}/edit`)">继续编辑</el-button>
            <el-button v-if="row.status === 'PENDING'" text @click="withdraw(row.id)">撤回</el-button>
            <el-button v-if="row.status === 'PENDING'" text type="primary" @click="$router.push(relatedDetailRoute(route, `/inbounds/${row.id}/review`))">审核入库</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { api } from '@/api/client'
import { formatDateColumn, money, paymentStatus, paymentStatusType } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import { matchesDateRange, matchesKeyword } from '@/utils/listFilters'
import { relatedDetailRoute } from '@/utils/navigation'
import { useAppStore } from '@/stores/app'

const route = useRoute()
const queryClient = useQueryClient()
const appStore = useAppStore()
const filters = reactive({ keyword: '', supplierName: '', status: '', dateRange: [] as string[] })

const { data: inbounds } = useQuery({ queryKey: ['inbounds'], queryFn: () => api.inbounds() })
const filteredInbounds = computed(() =>
  (inbounds.value ?? []).filter(
    (item) =>
      (!filters.supplierName || item.supplierName === filters.supplierName) &&
      (!filters.status || item.status === filters.status) &&
      matchesDateRange(item.businessDate, filters.dateRange) &&
      matchesKeyword(item, filters.keyword, ['orderNo', 'purchaseOrderNo', 'sendNumber', 'supplierName', 'warehouseName']),
  ),
)

const withdrawMutation = useMutation({
  mutationFn: (id: number) => api.withdrawInbound(id, appStore.currentUserName),
  onSuccess: async () => {
    ElMessage.success('已撤回为草稿，可以继续修改')
    await queryClient.invalidateQueries({ queryKey: ['inbounds'] })
  },
  onError: (error) => ElMessage.error(error instanceof Error ? error.message : '撤回失败'),
})
async function withdraw(id: number) {
  try {
    await ElMessageBox.confirm('撤回后单据恢复为草稿，可以修改并重新提交。', '撤回审核', {
      confirmButtonText: '确认撤回', cancelButtonText: '取消', type: 'warning',
    })
    withdrawMutation.mutate(id)
  } catch { /* 取消撤回 */ }
}

function inboundStatus(status: string) {
  if (status === 'DRAFT') return '草稿'
  if (status === 'REJECTED') return '已拒绝'
  if (status === 'STOCKED') return '已入库'
  if (status === 'CANCELLED') return '已取消'
  return '待审核'
}

function inboundStatusType(status: string) {
  if (status === 'STOCKED') return 'success'
  if (status === 'PENDING') return 'warning'
  if (status === 'REJECTED') return 'danger'
  return 'info'
}

function resetFilters() {
  filters.keyword = ''
  filters.supplierName = ''
  filters.status = ''
  filters.dateRange = []
}
</script>
