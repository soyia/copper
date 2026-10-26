<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">仓库库存</div>
          <div class="muted inventory-overview-subtitle">账面结存 − 出库草稿占用 = 自由可用；调拨发出后单列在途，不计入任一仓实存</div>
        </div>
        <div class="inventory-overview-actions">
          <el-button @click="refetch">刷新</el-button>
          <RouterLink v-if="appStore.hasPermission('inventory.nontrade.view')" to="/inventory/non-trade"><el-button>送样/领用单</el-button></RouterLink>
          <RouterLink to="/inventory/movements"><el-button>库存流水</el-button></RouterLink>
          <RouterLink to="/inventory/reconciliation"><el-button>库存勾稽</el-button></RouterLink>
          <RouterLink v-if="appStore.hasPermission('inventory.transfer')" to="/inventory/transfers/new"><el-button type="primary">新建调拨</el-button></RouterLink>
          <RouterLink v-if="appStore.hasPermission('inventory.stocktake')" to="/inventory/stocktakes/new"><el-button>新建盘点</el-button></RouterLink>
        </div>
      </div>
      <div class="list-tools">
        <el-input v-model="filters.keyword" clearable placeholder="SKU / 商品 / 规格摘要 / 批次号 / 入库单号" />
        <el-select v-model="filters.warehouseId" clearable filterable placeholder="仓库">
          <el-option v-for="item in warehouses ?? []" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-select v-model="filters.status" clearable placeholder="库存状态">
          <el-option label="可用" value="AVAILABLE" />
          <el-option label="已出完" value="OUT" />
        </el-select>
        <el-date-picker v-model="filters.dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="入库开始" end-placeholder="入库结束" />
        <div class="list-tools-actions">
          <el-button @click="resetFilters">重置</el-button>
        </div>
      </div>
      <el-table :data="filteredBatches" class="data-table" size="small" border show-overflow-tooltip max-height="calc(100vh - 274px)">
        <el-table-column prop="id" label="编号" width="80" fixed="left" />
        <el-table-column prop="warehouseName" label="仓库" width="100" fixed="left" />
        <el-table-column prop="productName" label="商品" width="170" fixed="left" />
        <el-table-column prop="sku" label="SKU" min-width="150" />
        <el-table-column prop="spec" label="规格摘要" min-width="240" />
        <el-table-column prop="batchNo" label="批次号" min-width="150" />
        <el-table-column label="来源入库单" min-width="170">
          <template #default="{ row }">
            <RouterLink v-if="row.sourceInboundId" class="business-link" :to="inboundRoute(row)">{{ row.sourceInboundNo || `#${row.sourceInboundId}` }}</RouterLink>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="入库量" width="135" align="right">
          <template #default="{ row }">{{ quantity(row.quantityIn) }} {{ row.baseUnit || '-' }}</template>
        </el-table-column>
        <el-table-column label="账面结存" width="135" align="right">
          <template #default="{ row }">{{ quantity(row.quantityAvailable) }} {{ row.baseUnit || '-' }}</template>
        </el-table-column>
        <el-table-column label="已占用" width="125" align="right">
          <template #default="{ row }">{{ quantity(row.quantityReserved) }} {{ row.baseUnit || '-' }}</template>
        </el-table-column>
        <el-table-column label="自由可用" width="135" align="right">
          <template #default="{ row }"><strong>{{ quantity(row.quantityFree) }} {{ row.baseUnit || '-' }}</strong></template>
        </el-table-column>
        <el-table-column label="调拨在途" width="125" align="right">
          <template #default="{ row }">{{ quantity(row.quantityInTransit) }} {{ row.baseUnit || '-' }}</template>
        </el-table-column>
        <el-table-column v-if="appStore.hasPermission('inventory.cost.view')" label="成本单价" width="120" align="right">
          <template #default="{ row }">{{ money(row.unitCost, 4) }}</template>
        </el-table-column>
        <el-table-column v-if="appStore.hasPermission('inventory.cost.view')" label="成本金额" width="130" align="right">
          <template #default="{ row }">{{ money(row.amountCost) }}</template>
        </el-table-column>
        <el-table-column prop="receivedAt" label="入库日期" width="120" :formatter="formatDateColumn" />
        <el-table-column prop="productionDate" label="生产日期" width="120" :formatter="formatDateColumn" />
        <el-table-column prop="expirationDate" label="到期日" width="120" :formatter="formatDateColumn" />
        <el-table-column label="状态" width="80" align="center" fixed="right">
          <template #default="{ row }">
            <el-tag :type="row.status === 'OUT' ? 'info' : 'success'" size="small">{{ row.status === 'OUT' ? '已出完' : '可用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" @click="openPackaging(row)">包装</el-button>
            <el-button
              v-if="appStore.hasPermission('inventory.nontrade.manage') && row.status !== 'OUT' && Number(row.quantityFree) > 0"
              text
              type="primary"
              @click="openNonTrade(row)"
            >
              送样/领用
            </el-button>
            <el-button v-if="appStore.hasPermission('inventory.quality.view')" text type="primary" @click="openBatchOperations(row)">质保</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <RelatedDetailDrawer
      v-model="packagingVisible"
      :title="`批次包装实况 ${activeBatch?.batchNo || ''}`"
      subtitle="查看该库存批次入库时固化的计量包装快照"
      :trail="[{ label: '仓库库存' }, { label: `批次 ${activeBatch?.batchNo || ''}` }]"
      :loading="packagingLoading"
      size="980px"
      @breadcrumb="packagingVisible = false"
    >
      <div>
        <el-alert
          v-if="activeBatch?.packagingTrackingMode === 'QUANTITY_ONLY'"
          title="该批次是历史数量快照，只能证明当时的主库存数量和单位，不能反推原箱数或包装形态。"
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
        />
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="跟踪方式">{{ trackingModeName(activeBatch?.packagingTrackingMode) }}</el-descriptions-item>
          <el-descriptions-item label="有效版本">{{ activeBatch?.packagingEffectiveRevisionId ? `#${activeBatch.packagingEffectiveRevisionId}` : '历史数量口径' }}</el-descriptions-item>
          <el-descriptions-item label="账面结存">{{ quantity(activeBatch?.quantityAvailable || 0) }} {{ activeBatch?.baseUnit || packagingProfile?.baseUnit || '-' }}</el-descriptions-item>
          <el-descriptions-item label="自由可用">{{ quantity(activeBatch?.quantityFree || 0) }} {{ activeBatch?.baseUnit || packagingProfile?.baseUnit || '-' }}</el-descriptions-item>
          <el-descriptions-item label="规则来源" :span="2">{{ profileSourceText }}</el-descriptions-item>
          <el-descriptions-item label="商品规则版本">v{{ packagingProfile?.versionNo || 0 }}</el-descriptions-item>
          <el-descriptions-item label="包装条码" :span="2">{{ activeBatch?.packageBarcode || '-' }}</el-descriptions-item>
          <el-descriptions-item label="生产日期">{{ formatDate(activeBatch?.productionDate) }}</el-descriptions-item>
          <el-descriptions-item label="保质期">{{ shelfLifeText(activeBatch) }}</el-descriptions-item>
          <el-descriptions-item label="到期日">{{ formatDate(activeBatch?.expirationDate) }}</el-descriptions-item>
          <el-descriptions-item label="快照校验码" :span="3"><span class="snapshot-hash">{{ activeBatch?.packagingSnapshotHash || '-' }}</span></el-descriptions-item>
        </el-descriptions>

        <div class="panel-title inventory-packaging-title">当时的计量策略</div>
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="主库存单位">{{ packagingProfile?.baseUnit || activeBatch?.baseUnit || '-' }}</el-descriptions-item>
          <el-descriptions-item label="最小销售单位">{{ packagingProfile?.minimumSalesUnit || '-' }}</el-descriptions-item>
          <el-descriptions-item label="最小发货单位">{{ packagingProfile?.minimumOutboundUnit || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="packagingProfile?.rules || []" border size="small" class="inventory-packaging-table">
          <el-table-column prop="parentUnit" label="上级包装" width="120" />
          <el-table-column prop="childUnit" label="下级单位" width="120" />
          <el-table-column label="换算口径" min-width="180"><template #default="{ row }">{{ ruleText(row) }}</template></el-table-column>
          <el-table-column label="可拆" width="90"><template #default="{ row }">{{ row.breakable ? '是' : '否' }}</template></el-table-column>
        </el-table>

        <div class="panel-title inventory-packaging-title">
          <span>逐件实重</span>
          <span v-if="activeBatch?.productionDate" class="inventory-packaging-context">
            本批次生产日期：{{ formatDate(activeBatch.productionDate) }}
          </span>
        </div>
        <el-table :data="packageInstances" border size="small" max-height="240">
          <el-table-column label="实际重量" width="180" align="right"><template #default="{ row }">{{ quantity(row.actualBaseQuantity) }} {{ row.baseUnit }}</template></el-table-column>
          <el-table-column label="当前剩余" width="180" align="right"><template #default="{ row }">{{ quantity(row.remainingBaseQuantity ?? row.actualBaseQuantity) }} {{ row.baseUnit }}</template></el-table-column>
          <el-table-column prop="packageCode" label="箱码 / 包装码" min-width="180" />
          <el-table-column prop="unitName" label="包装单位" width="120" />
          <el-table-column prop="status" label="状态" width="120" />
        </el-table>
        <el-empty v-if="!packageInstances.length" description="该批次无逐件实重记录" :image-size="56" />
      </div>
      <template #footer><el-button @click="packagingVisible = false">关闭</el-button></template>
    </RelatedDetailDrawer>
    <InventoryBatchQualityDialog v-model="operationsVisible" :batch="activeOperationsBatch" />
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { api, type InventoryBatch, type InventoryPackageInstance, type PackagingProfile, type PackagingRule } from '@/api/client'
import { formatDate, formatDateColumn, money, quantity } from '@/api/format'
import { matchesDateRange, matchesKeyword } from '@/utils/listFilters'
import RelatedDetailDrawer from '@/components/RelatedDetailDrawer.vue'
import InventoryBatchQualityDialog from '@/components/InventoryBatchQualityDialog.vue'
import { useAppStore } from '@/stores/app'
import { relatedDetailRoute } from '@/utils/navigation'

const appStore = useAppStore()
const route = useRoute()
const router = useRouter()

const filters = reactive({ keyword: '', warehouseId: undefined as number | undefined, status: '', dateRange: [] as string[] })
const packagingVisible = ref(false)
const packagingLoading = ref(false)
const activeBatch = ref<InventoryBatch>()
const packagingProfile = ref<PackagingProfile>()
const packageInstances = ref<InventoryPackageInstance[]>([])
const operationsVisible = ref(false)
const activeOperationsBatch = ref<InventoryBatch>()
const profileSourceText = computed(() => {
  const profile = packagingProfile.value
  if (!profile) return '历史数量口径'
  const label = ({ TEMPLATE: '系列包装模板', PRODUCT_OVERRIDE: '商品特例', SYSTEM_DEFAULT: '系统默认', LEGACY: '历史迁移' } as Record<string, string>)[profile.sourceLevel || ''] || profile.sourceLevel || '未知'
  return profile.templateName ? `${label}：${profile.templateName} v${profile.templateRevisionNo || '-'}` : label
})

const { data: batches, refetch } = useQuery({
  queryKey: ['inventory-batches'],
  queryFn: () => api.inventoryBatches(),
})
const { data: warehouses } = useQuery({ queryKey: ['warehouses'], queryFn: api.warehouses })
const filteredBatches = computed(() =>
  (batches.value ?? []).filter(
    (item) =>
      (!filters.warehouseId || item.warehouseId === filters.warehouseId) &&
      (!filters.status || item.status === filters.status) &&
      matchesDateRange(item.receivedAt, filters.dateRange) &&
      matchesKeyword(item, filters.keyword, ['sku', 'warehouseName', 'productName', 'spec', 'batchNo', 'sourceInboundNo']),
  ),
)

function inboundRoute(row: InventoryBatch) {
  return relatedDetailRoute(route, `/inbounds/${row.sourceInboundId}`)
}

function resetFilters() {
  filters.keyword = ''
  filters.warehouseId = undefined
  filters.status = ''
  filters.dateRange = []
}

async function openPackaging(row: InventoryBatch) {
  activeBatch.value = row
  packagingProfile.value = undefined
  packageInstances.value = []
  packagingVisible.value = true
  packagingLoading.value = true
  try {
    const [profile, instances] = await Promise.all([
      api.batchPackagingProfile(row.id),
      api.inventoryPackageInstances(row.id),
    ])
    packagingProfile.value = profile
    packageInstances.value = instances
  } finally {
    packagingLoading.value = false
  }
}

function openBatchOperations(row: InventoryBatch) {
  activeOperationsBatch.value = row
  operationsVisible.value = true
}

function openNonTrade(row: InventoryBatch) {
  router.push({ path: '/inventory/non-trade/new', query: { batchId: String(row.id) } })
}

function trackingModeName(value?: string) {
  return ({ QUANTITY_ONLY: '历史数量口径', STRUCTURED: '结构化包装余额', ACTUAL_INSTANCE: '逐件实重跟踪' } as Record<string, string>)[value || ''] || value || '未标记'
}

function ruleText(rule: PackagingRule) {
  if (rule.conversionMode === 'FIXED') return `1${rule.parentUnit} = ${quantity(rule.fixedChildQuantity || 0)}${rule.childUnit}`
  if (rule.conversionMode === 'ACTUAL_WEIGHT') return `逐${rule.parentUnit}实称，参考 ${quantity(rule.referenceBaseQuantity || 0)}${rule.childUnit}`
  return `理论换算，参考 ${quantity(rule.referenceBaseQuantity || 0)}${rule.childUnit}`
}

function shelfLifeText(row?: InventoryBatch) {
  if (row?.shelfLifeMode !== 'PERIOD') return '无保质期'
  const unit = ({ DAY: '天', MONTH: '月', YEAR: '年' } as Record<string, string>)[row.shelfLifeUnit || ''] || ''
  return `${row.shelfLifeValue || '-'}${unit}`
}
</script>

<style scoped>
.inventory-overview-subtitle { margin-top: 5px; font-size: var(--app-small-font-size); }
.inventory-overview-actions { display: flex; gap: 8px; align-items: center; }
.inventory-packaging-context { color: var(--el-text-color-secondary); font-size: var(--app-small-font-size); font-weight: 400; }
</style>
