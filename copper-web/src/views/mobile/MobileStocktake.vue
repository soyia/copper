<template>
  <div class="mobile-page">
    <el-alert
      title="扫码只帮助找到批次"
      description="实盘数量必须人工确认。保存后只形成盘点草稿，不立即改变库存；正式确认仍会校验期间、批次快照和差异原因。"
      type="warning"
      :closable="false"
      show-icon
    />

    <section class="mobile-card">
      <el-form label-position="top">
        <el-form-item label="盘点仓库" required>
          <el-select v-model="form.warehouseId" filterable placeholder="先选择本次盘点仓库" @change="warehouseChanged">
            <el-option v-for="item in enabledWarehouses" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="业务日期" required><el-date-picker v-model="form.businessDate" value-format="YYYY-MM-DD" /></el-form-item>
      </el-form>
      <MobileScanInput :busy="scanBusy" :placeholder="form.warehouseId ? '扫描包装码、SKU 或批次号' : '请先选择盘点仓库'" @scan="handleScan" />
    </section>

    <section class="mobile-card">
      <div class="mobile-card-title">
        <div><strong>实盘明细</strong><span>{{ form.items.length }} 个批次</span></div>
        <el-button plain :disabled="!form.warehouseId" @click="batchPickerOpen = true">手工选择</el-button>
      </div>
      <div class="stocktake-lines">
        <article v-for="(line, index) in form.items" :key="line.batchId" class="stocktake-line">
          <div class="stocktake-line-title">
            <div><strong>{{ batchOf(line)?.productName }}</strong><span>{{ batchOf(line)?.sku }} · {{ batchOf(line)?.spec }}</span></div>
            <el-button link type="danger" @click="form.items.splice(index, 1)">移除</el-button>
          </div>
          <div class="batch-meta">
            <span>批次 {{ batchOf(line)?.batchNo }}</span>
            <span>账面 {{ quantity(batchOf(line)?.quantityAvailable || 0) }} {{ batchOf(line)?.baseUnit }}</span>
          </div>
          <el-form label-position="top">
            <el-form-item label="实盘数量" required>
              <el-input v-model="line.countedQuantity" inputmode="decimal">
                <template #append>{{ batchOf(line)?.baseUnit || '' }}</template>
              </el-input>
            </el-form-item>
            <el-form-item v-if="hasDifference(line)" label="差异原因" required>
              <el-input v-model="line.differenceReason" maxlength="500" placeholder="如：破损、少发、计量差异，不能只写“调整”" />
            </el-form-item>
          </el-form>
        </article>
        <el-empty v-if="!form.items.length" description="扫码或手工选择批次后，在这里录入实盘数" />
      </div>
      <el-form label-position="top">
        <el-form-item label="盘点备注（选填）"><el-input v-model="form.memo" maxlength="500" /></el-form-item>
      </el-form>
    </section>

    <section v-if="createdId" class="created-card">
      <el-icon><CircleCheckFilled /></el-icon>
      <div><strong>盘点草稿已保存</strong><span>尚未改变库存，请复核后进入详情执行确认。</span></div>
      <el-button plain @click="router.push(`/inventory/stocktakes/${createdId}`)">查看并复核</el-button>
    </section>

    <el-button class="save-button" type="primary" size="large" :loading="saving" :disabled="!connectivity.isAvailable" @click="save">
      保存盘点草稿
    </el-button>

    <el-drawer v-model="batchPickerOpen" direction="btt" size="78%" title="选择库存批次">
      <el-input v-model="batchKeyword" clearable placeholder="搜索 SKU、商品、规格或批次" />
      <div class="batch-picker-list">
        <button v-for="batch in selectableBatches" :key="batch.id" type="button" class="batch-picker-row" @click="addBatch(batch)">
          <span><strong>{{ batch.productName }}</strong><small>{{ batch.sku }} · {{ batch.spec }}</small></span>
          <span><strong>{{ batch.batchNo }}</strong><small>账面 {{ quantity(batch.quantityAvailable) }} {{ batch.baseUnit }}</small></span>
        </button>
      </div>
    </el-drawer>

    <el-dialog v-model="matchPickerOpen" title="一个条码匹配多个批次" width="min(92vw, 520px)">
      <div class="match-list">
        <button v-for="batch in scanMatches" :key="batch.id" type="button" class="batch-picker-row" @click="chooseScanMatch(batch)">
          <span><strong>{{ batch.productName }}</strong><small>{{ batch.sku }} · {{ batch.spec }}</small></span>
          <span><strong>{{ batch.batchNo }}</strong><small>账面 {{ quantity(batch.quantityAvailable) }} {{ batch.baseUnit }}</small></span>
        </button>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { CircleCheckFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { api, type InventoryBatch, type Warehouse } from '@/api/client'
import { currentDateText, quantity } from '@/api/format'
import MobileScanInput from '@/components/mobile/MobileScanInput.vue'
import { useSessionDraft } from '@/composables/useSessionDraft'
import { mobileDraftKey } from '@/utils/mobileDrafts'
import { useAppStore } from '@/stores/app'
import { useConnectivityStore } from '@/stores/connectivity'

type StocktakeLine = { batchId: number; countedQuantity: number | string; differenceReason: string }
type StocktakeDraft = { businessDate: string; warehouseId?: number; memo: string; items: StocktakeLine[] }

const router = useRouter()
const appStore = useAppStore()
const connectivity = useConnectivityStore()
const ready = ref(false)
const batches = ref<InventoryBatch[]>([])
const warehouses = ref<Warehouse[]>([])
const saving = ref(false)
const scanBusy = ref(false)
const batchPickerOpen = ref(false)
const matchPickerOpen = ref(false)
const batchKeyword = ref('')
const scanMatches = ref<InventoryBatch[]>([])
const createdId = ref<number>()
const form = reactive<StocktakeDraft>({
  businessDate: currentDateText(),
  warehouseId: undefined,
  memo: '',
  items: [],
})
const enabledWarehouses = computed(() => warehouses.value.filter((item) => item.status === 'ENABLED'))
const warehouseBatches = computed(() => batches.value.filter((item) => item.warehouseId === form.warehouseId))
const selectableBatches = computed(() => {
  const keyword = batchKeyword.value.trim().toLowerCase()
  const selected = new Set(form.items.map((item) => item.batchId))
  return warehouseBatches.value
    .filter((item) => !selected.has(item.id))
    .filter((item) => !keyword || [item.sku, item.productName, item.spec, item.batchNo].some((value) => String(value || '').toLowerCase().includes(keyword)))
})

const { clearDraft } = useSessionDraft<StocktakeDraft>({
  storageKey: () => mobileDraftKey(appStore.session?.userId, 'mobile-stocktake'),
  snapshot: () => JSON.parse(JSON.stringify(form)) as StocktakeDraft,
  restore: (draft) => {
    Object.assign(form, draft)
  },
  ready,
  label: '手机扫码盘点',
  confirmRestore: true,
})

onMounted(async () => {
  try {
    [batches.value, warehouses.value] = await Promise.all([
      api.inventoryBatches({ availableOnly: false }),
      api.warehouses(),
    ])
  } finally {
    ready.value = true
  }
})

function warehouseChanged() {
  if (form.items.length) {
    form.items = []
    ElMessage.info('已切换仓库，原盘点明细已清空，避免跨仓混盘')
  }
  createdId.value = undefined
}
function batchOf(line: StocktakeLine) { return batches.value.find((item) => item.id === line.batchId) }
function hasDifference(line: StocktakeLine) {
  if (line.countedQuantity === '') return false
  return Math.abs(Number(line.countedQuantity) - Number(batchOf(line)?.quantityAvailable || 0)) > 0.000001
}

async function handleScan(code: string) {
  if (!form.warehouseId) return ElMessage.warning('请先选择盘点仓库')
  scanBusy.value = true
  try {
    let matches = warehouseBatches.value.filter((item) => [item.packageBarcode, item.batchNo, item.sku].includes(code))
    if (!matches.length) {
      try {
        const product = await api.packageBarcode(code)
        matches = warehouseBatches.value.filter((item) => item.productId === product.productId)
      } catch {
        matches = warehouseBatches.value.filter((item) =>
          [item.sku, item.productName, item.spec, item.batchNo].some((value) => String(value || '').toLowerCase().includes(code.toLowerCase())))
      }
    }
    matches = matches.filter((item) => !form.items.some((line) => line.batchId === item.id))
    if (!matches.length) return ElMessage.warning('该仓库没有可加入的匹配批次，或该批次已经在盘点明细中')
    if (matches.length === 1) {
      addBatch(matches[0])
      return
    }
    scanMatches.value = matches
    matchPickerOpen.value = true
  } finally {
    scanBusy.value = false
  }
}

function addBatch(batch: InventoryBatch) {
  if (form.items.some((item) => item.batchId === batch.id)) return
  form.items.push({ batchId: batch.id, countedQuantity: '', differenceReason: '' })
  batchPickerOpen.value = false
  ElMessage.success(`已加入批次 ${batch.batchNo}，请填写实盘数量`)
}
function chooseScanMatch(batch: InventoryBatch) {
  matchPickerOpen.value = false
  addBatch(batch)
}

async function save() {
  if (!connectivity.isAvailable) return ElMessage.warning('当前无法连接 Copper 主机，不能保存盘点草稿')
  if (!form.warehouseId) return ElMessage.warning('请选择盘点仓库')
  if (!form.items.length) return ElMessage.warning('请至少加入一个盘点批次')
  if (form.items.some((item) => item.countedQuantity === '' || !Number.isFinite(Number(item.countedQuantity)) || Number(item.countedQuantity) < 0)) {
    return ElMessage.warning('每个批次都必须填写不小于0的实盘数量')
  }
  if (form.items.some((item) => hasDifference(item) && !item.differenceReason.trim())) {
    return ElMessage.warning('有盘盈盘亏的批次必须填写具体差异原因')
  }
  saving.value = true
  try {
    const result = await api.createInventoryStocktake({
      businessDate: form.businessDate,
      warehouseId: form.warehouseId,
      memo: form.memo,
      items: form.items.map((item) => ({
        batchId: item.batchId,
        countedQuantity: Number(item.countedQuantity),
        differenceReason: item.differenceReason.trim(),
      })),
    })
    createdId.value = result.id
    clearDraft()
    form.items = []
    form.memo = ''
    ElMessage.success('盘点草稿已保存，尚未改变库存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '盘点草稿保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.mobile-page { display: grid; gap: 12px; }
.mobile-card { padding: 15px; border: 1px solid #e2e7ea; border-radius: 17px; background: #fff; }
.mobile-card :deep(.el-select),
.mobile-card :deep(.el-date-editor) { width: 100%; }
.mobile-card-title { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 12px; }
.mobile-card-title > div { display: grid; gap: 3px; }
.mobile-card-title strong { font-size: 15px; }
.mobile-card-title span { color: #77858e; font-size: 11px; }
.stocktake-lines { display: grid; gap: 10px; }
.stocktake-line { padding: 12px; border: 1px solid #e4e9ec; border-radius: 14px; background: #fafcfd; }
.stocktake-line-title { display: flex; justify-content: space-between; gap: 10px; }
.stocktake-line-title div { display: grid; min-width: 0; gap: 3px; }
.stocktake-line-title span { overflow: hidden; color: #788690; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.batch-meta { display: flex; flex-wrap: wrap; gap: 8px 14px; margin: 9px 0 12px; color: #566873; font-size: 11px; }
.save-button { min-height: 47px; width: 100%; }
.created-card { display: grid; grid-template-columns: 28px 1fr auto; align-items: center; gap: 10px; padding: 14px; border: 1px solid #cce5d3; border-radius: 15px; color: #27613e; background: #f0f9f3; }
.created-card > .el-icon { font-size: 23px; }
.created-card div { display: grid; gap: 3px; }
.created-card span { color: #668273; font-size: 11px; }
.batch-picker-list,
.match-list { display: grid; gap: 8px; margin-top: 12px; }
.batch-picker-row { display: grid; width: 100%; grid-template-columns: minmax(0, 1fr) auto; gap: 10px; padding: 12px; border: 1px solid #e2e7ea; border-radius: 12px; color: inherit; background: #fff; text-align: left; }
.batch-picker-row > span { display: grid; min-width: 0; gap: 3px; }
.batch-picker-row > span:last-child { text-align: right; }
.batch-picker-row strong { overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.batch-picker-row small { color: #77858e; font-size: 10px; }
@media (max-width: 520px) {
  .created-card { grid-template-columns: 28px 1fr; }
  .created-card .el-button { grid-column: 1 / -1; }
}
</style>
