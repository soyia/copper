<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">采购费用分摊</div>
          <div class="muted subtitle">把运费、装卸、检验等采购附加费用按受控依据计入仍在库的真实批次成本。</div>
        </div>
        <div class="header-actions">
          <el-button @click="query.refetch()">刷新</el-button>
          <el-button v-if="canManage" @click="router.push({ path: '/treasury', query: { createType: 'PURCHASE_LANDED_COST' } })">登记采购费用</el-button>
          <el-button v-if="canManage" type="primary" @click="router.push({ path: '/inventory/landed-cost-allocations/new', query: { from: route.fullPath } })">新建分摊单</el-button>
        </div>
      </div>
      <el-alert class="boundary" type="info" :closable="false" show-icon title="费用源单、付款、发票和库存成本分层记录；只有确认分摊后，冻结的计入成本金额才进入批次成本。" />
      <div class="list-tools filters">
        <el-input v-model="keyword" clearable placeholder="分摊单号 / 费用源单 / 往来单位 / 备注" />
        <el-select v-model="status" clearable placeholder="全部状态">
          <el-option v-for="item in statuses" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </div>
      <el-table v-loading="query.isFetching.value" :data="filtered" border size="small" class="data-table" show-overflow-tooltip @row-dblclick="openDetail">
        <el-table-column label="分摊单号" min-width="205" fixed="left"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row)">{{ row.allocationNo }}</el-button></template></el-table-column>
        <el-table-column prop="businessDate" label="业务日期" width="112" />
        <el-table-column prop="feePartnerName" label="费用供应商" min-width="160" />
        <el-table-column label="费用源单" min-width="185"><template #default="{ row }"><router-link class="table-link" :to="`/treasury/documents/${row.treasuryDocumentId}`">{{ row.treasuryDocumentNo }}</router-link></template></el-table-column>
        <el-table-column label="分摊方式" width="105"><template #default="{ row }">{{ methodName(row.allocationMethod) }}</template></el-table-column>
        <el-table-column label="计入成本金额" width="135" align="right"><template #default="{ row }"><strong>{{ money(row.feeAmount) }}</strong></template></el-table-column>
        <el-table-column label="计入库存" width="125" align="right"><template #default="{ row }">{{ money(row.capitalizedAmount) }}</template></el-table-column>
        <el-table-column label="已耗用成本" width="125" align="right"><template #default="{ row }">{{ money(row.expensedAmount) }}</template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="statusType(row.status)">{{ statusName(row.status) }}</el-tag></template></el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170" :formatter="formatDateTimeColumn" />
      </el-table>
      <el-empty v-if="!query.isFetching.value && !filtered.length" description="暂无采购费用分摊单" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { api, type LandedCostAllocationRow } from '@/api/client'
import { formatDateTimeColumn, money } from '@/api/format'
import { useAppStore } from '@/stores/app'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const keyword = ref('')
const status = ref('')
const canManage = computed(() => appStore.hasPermission('landed.cost.manage'))
const statuses = [{ value: 'DRAFT', label: '草稿' }, { value: 'CONFIRMED', label: '已确认' }, { value: 'CANCELLED', label: '已作废' }, { value: 'REVERSED', label: '已冲正' }]
const query = useQuery({ queryKey: computed(() => ['landed-cost-allocations', status.value]), queryFn: () => api.landedCostAllocations(status.value) })
const filtered = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  const rows = query.data.value ?? []
  return text ? rows.filter((row) => [row.allocationNo, row.treasuryDocumentNo, row.feePartnerName, row.memo].some((value) => String(value || '').toLowerCase().includes(text))) : rows
})
function openDetail(row: LandedCostAllocationRow) { router.push({ path: `/inventory/landed-cost-allocations/${row.id}`, query: { from: route.fullPath } }) }
function methodName(value: string) { return ({ QUANTITY: '按数量', WEIGHT: '按重量', AMOUNT: '按金额', MANUAL: '手工分摊' } as Record<string, string>)[value] ?? value }
function statusName(value: string) { return ({ DRAFT: '草稿', CONFIRMED: '已确认', CANCELLED: '已作废', REVERSED: '已冲正' } as Record<string, string>)[value] ?? value }
function statusType(value: string) { return value === 'CONFIRMED' ? 'success' : value === 'DRAFT' ? 'info' : 'info' }
</script>

<style scoped>
.subtitle { margin-top: 5px; }.header-actions { display:flex;gap:10px;flex-wrap:wrap }.boundary { margin:0 18px 14px }.filters { grid-template-columns:minmax(260px,1fr) 160px }
</style>
