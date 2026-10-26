<template>
  <div class="mobile-page">
    <section class="mobile-card mobile-search-card">
      <MobileScanInput :busy="loading" placeholder="输入 SKU、商品、规格、批次或扫码" @scan="handleScan" />
      <el-input v-model="keyword" size="large" clearable placeholder="也可直接输入关键词查货">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-select v-model="warehouseId" size="large" clearable placeholder="全部仓库">
        <el-option v-for="item in enabledWarehouses" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
    </section>

    <div class="mobile-result-summary">
      <span>共 {{ filtered.length }} 个库存批次</span>
      <el-button text :loading="loading" @click="load">刷新</el-button>
    </div>

    <section v-loading="loading" class="stock-list">
      <article v-for="row in filtered" :key="row.id" class="stock-card">
        <div class="stock-card-title">
          <div>
            <strong>{{ row.productName }}</strong>
            <span>{{ row.sku }} · {{ row.spec || '未填规格' }}</span>
          </div>
          <el-tag :type="Number(row.quantityFree) > 0 ? 'success' : 'warning'" effect="plain">
            可用 {{ quantity(row.quantityFree) }} {{ row.baseUnit || '' }}
          </el-tag>
        </div>
        <div class="stock-facts">
          <div><span>仓库</span><strong>{{ row.warehouseName }}</strong></div>
          <div><span>批次</span><strong>{{ row.batchNo }}</strong></div>
          <div><span>实存</span><strong>{{ quantity(row.quantityAvailable) }}</strong></div>
          <div><span>已预留</span><strong>{{ quantity(row.quantityReserved) }}</strong></div>
          <div><span>在途</span><strong>{{ quantity(row.quantityInTransit) }}</strong></div>
          <div><span>包装码</span><strong>{{ row.packageBarcode || '-' }}</strong></div>
        </div>
      </article>
      <el-empty v-if="!loading && !filtered.length" description="没有找到匹配库存；可换 SKU、规格、批次或仓库再试" />
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { api, type InventoryBatch, type Warehouse } from '@/api/client'
import { quantity } from '@/api/format'
import MobileScanInput from '@/components/mobile/MobileScanInput.vue'

const loading = ref(false)
const keyword = ref('')
const warehouseId = ref<number>()
const batches = ref<InventoryBatch[]>([])
const warehouses = ref<Warehouse[]>([])
const enabledWarehouses = computed(() => warehouses.value.filter((item) => item.status === 'ENABLED'))
const filtered = computed(() => {
  const search = keyword.value.trim().toLowerCase()
  return batches.value
    .filter((row) => !warehouseId.value || row.warehouseId === warehouseId.value)
    .filter((row) => !search || [row.sku, row.productName, row.grade, row.spec, row.batchNo, row.packageBarcode, row.warehouseName]
      .some((value) => String(value || '').toLowerCase().includes(search)))
    .sort((left, right) => Number(right.quantityFree) - Number(left.quantityFree))
})

onMounted(load)

async function load() {
  loading.value = true
  try {
    [batches.value, warehouses.value] = await Promise.all([api.inventoryBatches(), api.warehouses()])
  } finally {
    loading.value = false
  }
}

async function handleScan(code: string) {
  keyword.value = code
  const localMatch = batches.value.some((row) => [row.packageBarcode, row.batchNo, row.sku].includes(code))
  if (localMatch) return
  loading.value = true
  try {
    const match = await api.packageBarcode(code)
    keyword.value = match.sku
    ElMessage.success(`已识别 ${match.productName} · ${match.packageUnit}`)
  } catch {
    ElMessage.warning('未识别到该条码，已按关键词继续查询')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.mobile-page { display: grid; gap: 12px; }
.mobile-card { padding: 14px; border: 1px solid #e2e7ea; border-radius: 17px; background: #fff; }
.mobile-search-card { display: grid; gap: 10px; }
.mobile-result-summary { display: flex; align-items: center; justify-content: space-between; padding: 0 4px; color: #657580; font-size: 12px; }
.stock-list { display: grid; gap: 10px; min-height: 180px; }
.stock-card { display: grid; gap: 14px; padding: 15px; border: 1px solid #e3e8eb; border-radius: 16px; background: #fff; box-shadow: 0 6px 20px rgb(25 49 65 / 4%); }
.stock-card-title { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.stock-card-title > div { display: grid; min-width: 0; gap: 4px; }
.stock-card-title strong { font-size: 15px; }
.stock-card-title span { overflow: hidden; color: #76858f; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.stock-facts { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px 10px; }
.stock-facts div { display: grid; min-width: 0; gap: 3px; }
.stock-facts span { color: #8a969e; font-size: 10px; }
.stock-facts strong { overflow: hidden; font-size: 12px; font-weight: 620; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 520px) {
  .stock-card-title { display: grid; }
  .stock-card-title .el-tag { width: fit-content; }
  .stock-facts { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
