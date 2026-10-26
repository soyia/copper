<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">新建送样 / 领用 / 损耗单</div>
          <div class="muted form-subtitle">一张单据可添加多个商品；每条商品明细独立选择批次和来源包装。</div>
        </div>
        <el-button @click="router.push('/inventory/non-trade')">返回列表</el-button>
      </div>

      <el-alert
        title="先保存草稿，不立即扣库存。实物交付时在详情中确认发放并立即扣库存；实际领用人可随后签字，回传单据用于归档。"
        type="warning"
        :closable="false"
        show-icon
      />

      <el-form label-position="top" class="document-form">
        <div class="form-grid">
          <el-form-item label="业务类型" required>
            <el-select v-model="form.businessType" style="width: 100%">
              <el-option label="送样出库" value="SAMPLE_OUT" />
              <el-option label="内部领用" value="INTERNAL_USE" />
              <el-option label="合理损耗" value="NORMAL_LOSS" />
              <el-option label="其他出库" value="OTHER_OUT" />
            </el-select>
          </el-form-item>
          <el-form-item label="业务日期" required>
            <el-date-picker v-model="form.businessDate" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="实际领用人（签字人）" required>
            <el-input v-model="form.recipient" maxlength="128" placeholder="必须与回传单据上的领用人签字一致" />
          </el-form-item>
          <el-form-item label="经办人 / 发放人">
            <el-input v-model="form.handlerName" maxlength="128" placeholder="不填则记录为当前系统制单人" />
          </el-form-item>
          <el-form-item label="原因" required class="full-row">
            <el-input v-model="form.reason" maxlength="500" placeholder="说明本次送样、领用或损耗的真实原因" />
          </el-form-item>
          <el-form-item label="备注" class="full-row">
            <el-input v-model="form.memo" type="textarea" :rows="2" maxlength="500" />
          </el-form-item>
        </div>

        <div class="items-heading">
          <div>
            <div class="section-title">商品扣取明细</div>
            <div class="muted">不同商品、不同批次分别扣取；各行数量由本行来源包装自动合计。</div>
          </div>
          <el-button @click="addItem">增加商品</el-button>
        </div>

        <div v-for="(item, itemIndex) in form.items" :key="item.key" class="item-card">
          <div class="item-card-heading">
            <strong>商品明细 {{ itemIndex + 1 }}</strong>
            <el-button
              text
              type="danger"
              :disabled="form.items.length === 1"
              @click="removeItem(itemIndex)"
            >
              删除
            </el-button>
          </div>

          <div class="item-grid">
            <el-form-item label="库存批次" required>
              <el-select
                v-model="item.batchId"
                filterable
                clearable
                placeholder="选择有自由库存的商品批次"
                style="width: 100%"
                @change="loadPackages(item)"
              >
                <el-option
                  v-for="batch in availableBatchOptions(item)"
                  :key="batch.id"
                  :value="batch.id"
                  :label="batchLabel(batch)"
                />
              </el-select>
              <div v-if="selectedBatch(item)" class="muted">
                当前自由可用 {{ quantity(selectedBatch(item)!.quantityFree) }} {{ selectedBatch(item)!.baseUnit || '-' }}
              </div>
            </el-form-item>
            <el-form-item label="本商品合计扣取数量" required>
              <el-input :model-value="quantity(itemTotal(item))" readonly>
                <template #append>{{ selectedBatch(item)?.baseUnit || '-' }}</template>
              </el-input>
              <div class="muted">只读，由本行扣取明细自动合计</div>
            </el-form-item>
          </div>

          <template v-if="selectedBatch(item)">
            <div v-if="requiresPackageAllocation(item)" class="allocation-heading">
              <span class="muted">可从多盘、多卷或多箱分别扣取。</span>
              <el-button size="small" @click="addAllocation(item)">增加包装</el-button>
            </div>
            <el-table
              v-if="requiresPackageAllocation(item)"
              v-loading="item.loadingPackages"
              :data="item.packageAllocations"
              border
            >
              <el-table-column label="来源包装 *" min-width="300">
                <template #default="{ row, $index }">
                  <el-select v-model="row.packageInstanceId" filterable placeholder="选择实际扣取的包装" style="width: 100%">
                    <el-option
                      v-for="instance in availablePackageOptions(item, $index)"
                      :key="instance.id"
                      :value="instance.id"
                      :label="packageLabel(instance)"
                    />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="当前剩余" width="160" align="right">
                <template #default="{ row }">{{ packageRemainingText(item, row.packageInstanceId) }}</template>
              </el-table-column>
              <el-table-column label="本包装扣取 *" width="210">
                <template #default="{ row }">
                  <el-input-number
                    v-model="row.quantity"
                    :min="0.000001"
                    :max="packageRemaining(item, row.packageInstanceId) || undefined"
                    :precision="6"
                    :controls="false"
                    placeholder="输入实际扣取量"
                    style="width: 100%"
                  />
                </template>
              </el-table-column>
              <el-table-column label="扣后剩余" width="160" align="right">
                <template #default="{ row }">{{ packageAfterText(item, row) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="80" align="center">
                <template #default="{ $index }">
                  <el-button
                    text
                    type="danger"
                    :disabled="item.packageAllocations.length === 1"
                    @click="removeAllocation(item, $index)"
                  >
                    删除
                  </el-button>
                </template>
              </el-table-column>
            </el-table>

            <el-form-item v-else label="本批次扣取量" required class="quantity-deduction">
              <el-input-number
                v-model="item.sourceDeductionQuantity"
                :min="0.000001"
                :max="Number(selectedBatch(item)!.quantityFree)"
                :precision="6"
                :controls="false"
                style="width: 320px"
              />
              <span class="unit-suffix">{{ selectedBatch(item)!.baseUnit || '-' }}</span>
            </el-form-item>
          </template>
        </div>
      </el-form>

      <div class="document-summary">共 {{ form.items.length }} 项商品明细</div>
      <div class="form-actions">
        <el-button @click="router.push('/inventory/non-trade')">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveDraft">保存草稿</el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { roundQuantity, sumQuantities, subtractQuantities } from '@/utils/quantity'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useQuery } from '@tanstack/vue-query'
import { api, type InventoryBatch, type InventoryPackageInstance } from '@/api/client'
import { currentDateText, quantity } from '@/api/format'

type Allocation = { packageInstanceId?: number; quantity?: number }
type ItemForm = {
  key: number
  batchId?: number
  sourceDeductionQuantity?: number
  packageAllocations: Allocation[]
  packageInstances: InventoryPackageInstance[]
  loadingPackages: boolean
}

const route = useRoute()
const router = useRouter()
const saving = ref(false)
let itemKey = 0
const newItem = (): ItemForm => ({
  key: ++itemKey,
  batchId: undefined,
  sourceDeductionQuantity: undefined,
  packageAllocations: [{ packageInstanceId: undefined, quantity: undefined }],
  packageInstances: [],
  loadingPackages: false,
})
const form = reactive({
  businessType: 'SAMPLE_OUT',
  businessDate: currentDateText(),
  recipient: '',
  handlerName: '',
  reason: '',
  memo: '',
  items: [newItem()] as ItemForm[],
})

const { data: batches } = useQuery({
  queryKey: ['inventory-batches', 'non-trade-create'],
  queryFn: () => api.inventoryBatches({ availableOnly: true }),
})
const availableBatches = computed(() => (batches.value || [])
  .filter((item) => Number(item.quantityFree) > 0 && item.status !== 'OUT'))

onMounted(applyRouteBatch)
watch(availableBatches, applyRouteBatch)

function applyRouteBatch() {
  const batchId = Number(route.query.batchId || 0)
  const first = form.items[0]
  if (batchId > 0 && first && !first.batchId && availableBatches.value.some((item) => item.id === batchId)) {
    first.batchId = batchId
    loadPackages(first)
  }
}
function selectedBatch(item: ItemForm) {
  return availableBatches.value.find((entry) => entry.id === item.batchId)
}
function availableBatchOptions(current: ItemForm) {
  const used = new Set(form.items.filter((item) => item !== current).map((item) => item.batchId).filter(Boolean))
  return availableBatches.value.filter((item) => !used.has(item.id))
}
function requiresPackageAllocation(item: ItemForm) {
  return selectedBatch(item)?.packagingTrackingMode === 'ACTUAL_INSTANCE'
}
function validAllocations(item: ItemForm) {
  return item.packageAllocations.filter((allocation) => allocation.packageInstanceId && Number(allocation.quantity) > 0)
}
function itemTotal(item: ItemForm) {
  if (!requiresPackageAllocation(item)) return Number(item.sourceDeductionQuantity || 0)
  return sumQuantities(validAllocations(item).map((allocation) => allocation.quantity))
}
function addItem() {
  form.items.push(newItem())
}
function removeItem(index: number) {
  form.items.splice(index, 1)
}
async function loadPackages(item: ItemForm) {
  item.packageInstances = []
  item.sourceDeductionQuantity = undefined
  item.packageAllocations = [{ packageInstanceId: undefined, quantity: undefined }]
  if (!item.batchId || !requiresPackageAllocation(item)) return
  item.loadingPackages = true
  try {
    item.packageInstances = (await api.inventoryPackageInstances(item.batchId))
      .filter((instance) => Number(instance.remainingBaseQuantity ?? instance.actualBaseQuantity) > 0
        && instance.status !== 'CONSUMED')
  } finally {
    item.loadingPackages = false
  }
}
function addAllocation(item: ItemForm) {
  item.packageAllocations.push({ packageInstanceId: undefined, quantity: undefined })
}
function removeAllocation(item: ItemForm, index: number) {
  item.packageAllocations.splice(index, 1)
}
function availablePackageOptions(item: ItemForm, index: number) {
  const used = new Set(item.packageAllocations
    .filter((_, rowIndex) => rowIndex !== index)
    .map((allocation) => allocation.packageInstanceId)
    .filter(Boolean))
  return item.packageInstances.filter((instance) => !used.has(instance.id))
}
function packageRemaining(item: ItemForm, id?: number) {
  const instance = item.packageInstances.find((entry) => entry.id === id)
  return Number(instance?.remainingBaseQuantity ?? instance?.actualBaseQuantity ?? 0)
}
function packageRemainingText(item: ItemForm, id?: number) {
  return id ? `${quantity(packageRemaining(item, id))} ${selectedBatch(item)?.baseUnit || ''}` : '-'
}
function packageAfterText(item: ItemForm, allocation: Allocation) {
  if (!allocation.packageInstanceId || !allocation.quantity) return '-'
  return `${quantity(subtractQuantities(packageRemaining(item, allocation.packageInstanceId), allocation.quantity))} ${selectedBatch(item)?.baseUnit || ''}`
}
function packageLabel(instance: InventoryPackageInstance) {
  return `${instance.packageCode || `包装#${instance.id}`} · 剩余 ${quantity(instance.remainingBaseQuantity ?? instance.actualBaseQuantity)} ${instance.baseUnit}`
}
function batchLabel(item: InventoryBatch) {
  return `${item.productName} / ${item.spec || '无规格'} · ${item.warehouseName}/${item.batchNo} · 可用 ${quantity(item.quantityFree)} ${item.baseUnit || ''}`
}

async function saveDraft() {
  if (!form.businessDate) return ElMessage.warning('请选择业务日期')
  if (!form.recipient.trim()) return ElMessage.warning('请填写实际领用人')
  if (!form.reason.trim()) return ElMessage.warning('请填写原因')
  if (!form.items.length) return ElMessage.warning('至少添加一个商品明细')
  const payloadItems = []
  for (let index = 0; index < form.items.length; index += 1) {
    const item = form.items[index]
    const batch = selectedBatch(item)
    if (!batch) return ElMessage.warning(`请选择第 ${index + 1} 项商品的库存批次`)
    const total = itemTotal(item)
    if (total <= 0) return ElMessage.warning(`请填写第 ${index + 1} 项商品的来源扣取明细`)
    if (total > Number(batch.quantityFree)) return ElMessage.warning(`第 ${index + 1} 项商品扣取量超过当前自由库存`)
    let allocations: Array<{ packageInstanceId: number; quantity: number }> = []
    if (requiresPackageAllocation(item)) {
      const valid = validAllocations(item)
      if (valid.length !== item.packageAllocations.length) {
        return ElMessage.warning(`请完整填写第 ${index + 1} 项商品的来源包装和扣取量`)
      }
      if (valid.some((allocation) => Number(allocation.quantity) > packageRemaining(item, allocation.packageInstanceId))) {
        return ElMessage.warning(`第 ${index + 1} 项商品存在超过包装剩余量的扣取`)
      }
      allocations = valid.map((allocation) => ({
        packageInstanceId: Number(allocation.packageInstanceId),
        quantity: Number(allocation.quantity),
      }))
    }
    payloadItems.push({
      batchId: batch.id,
      quantity: roundQuantity(total),
      packageInstanceIds: allocations.map((allocation) => allocation.packageInstanceId),
      packageAllocations: allocations,
    })
  }
  saving.value = true
  try {
    const result = await api.createInventoryNonTradeDraft({
      businessType: form.businessType,
      businessDate: form.businessDate,
      recipient: form.recipient.trim(),
      handlerName: form.handlerName.trim(),
      reason: form.reason.trim(),
      memo: form.memo.trim(),
      items: payloadItems,
    })
    ElMessage.success('草稿已保存；实际交付时请在详情中确认发放并扣库存')
    await router.push(`/inventory/non-trade/${result.id}`)
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.form-subtitle { margin-top: 5px; }
.document-form { margin-top: 18px; }
.form-grid, .item-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 24px; }
.full-row { grid-column: 1 / -1; }
.items-heading { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; margin: 8px 0 12px; }
.section-title { font-weight: 650; font-size: var(--app-title-font-size); }
.item-card { margin-bottom: 14px; padding: 16px; border: 1px solid var(--el-border-color); border-radius: 8px; }
.item-card-heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.allocation-heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.quantity-deduction { margin: 4px 0 0; }
.unit-suffix { margin-left: 10px; }
.document-summary { text-align: right; color: var(--el-text-color-secondary); }
.form-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 18px; }
@media (max-width: 900px) {
  .form-grid, .item-grid { grid-template-columns: 1fr; }
  .full-row { grid-column: auto; }
}
</style>
