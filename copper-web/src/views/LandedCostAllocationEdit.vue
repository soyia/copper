<template>
  <div class="page">
    <div class="panel landed-form-panel">
      <PageHeader :title="isEdit ? '编辑采购费用分摊' : '新建采购费用分摊'" :back-to="backTo" />
      <el-alert class="form-alert" type="info" :closable="false" show-icon title="系统按费用源单冻结的进项税处理分摊未税净额或价税合计；实际付款、发票和供应商往来仍分别记录。" />
      <el-form label-position="top" class="landed-form">
        <div class="form-grid top-grid">
          <el-form-item label="采购附加费用源单" required class="source-field">
            <el-select v-model="form.treasuryDocumentId" filterable placeholder="选择已确认的运费、装卸或检验费用">
              <el-option v-for="item in sources" :key="item.id" :value="item.id" :disabled="item.allocated && item.id !== originalSourceId"
                :label="`${item.documentNo} · ${item.partnerName} · 计入成本 ${money(item.capitalizableAmount)} · ${item.summary}`" />
            </el-select>
          </el-form-item>
          <el-form-item label="业务日期" required><el-date-picker v-model="form.businessDate" value-format="YYYY-MM-DD" :clearable="false" /></el-form-item>
          <el-form-item label="分摊方式" required>
            <el-select v-model="form.allocationMethod">
              <el-option v-for="item in methods" :key="item.value" :value="item.value" :label="item.label" />
            </el-select>
          </el-form-item>
        </div>

        <div class="source-summary" v-if="source">
          <div><span>计入成本金额</span><strong>{{ money(source.capitalizableAmount) }}</strong></div>
          <div><span>{{ source.inputTaxTreatment === 'INCLUDED_IN_COST' ? '进项税额（计入成本）' : '进项税额（不分摊）' }}</span><strong>{{ money(source.taxAmount) }}</strong></div>
          <div><span>价税合计</span><strong>{{ money(source.totalAmount) }}</strong></div>
          <div><span>进项税处理</span><strong>{{ source.inputTaxTreatment === 'INCLUDED_IN_COST' ? '不抵扣，计入成本' : '可抵扣，不计成本' }}</strong></div>
        </div>

        <div class="line-header">
          <div><div class="panel-title">选择采购入库明细</div><div class="muted">支持跨入库单分摊；按重量时需核对公斤数，手工分摊时金额合计必须等于计入成本金额。</div></div>
          <el-input v-model="keyword" clearable placeholder="入库单 / 商品 / SKU / 批次 / 仓库" />
        </div>
        <el-table :data="filteredInbound" border size="small" class="candidate-table" max-height="440" show-overflow-tooltip>
          <el-table-column width="48" fixed="left"><template #default="{ row }"><el-checkbox :model-value="selectedIds.includes(row.inboundItemId)" @change="toggle(row)" /></template></el-table-column>
          <el-table-column label="入库单 / 供应商" min-width="210" fixed="left"><template #default="{ row }"><div>{{ row.inboundNo }}</div><div class="multiline-secondary">{{ row.supplierName }} · {{ row.inboundDate }}</div></template></el-table-column>
          <el-table-column label="商品 / 批次" min-width="250"><template #default="{ row }"><div>{{ row.productName }} · {{ row.sku }}</div><div class="multiline-secondary">{{ row.spec || '-' }} · {{ row.batchNo }}</div></template></el-table-column>
          <el-table-column prop="warehouseName" label="原入库仓库" width="125" />
          <el-table-column label="原入库 / 当前结存" width="170" align="right"><template #default="{ row }">{{ quantity(row.originalQuantity) }} / {{ quantity(row.currentQuantity) }} {{ row.baseUnit }}</template></el-table-column>
          <el-table-column label="入库未税金额" width="130" align="right"><template #default="{ row }">{{ money(row.amountExcludingTax) }}</template></el-table-column>
          <el-table-column v-if="form.allocationMethod === 'WEIGHT'" label="计费重量(kg)" width="175"><template #default="{ row }"><el-input-number v-if="selectedIds.includes(row.inboundItemId)" v-model="basisValues[row.inboundItemId]" :min="0" :precision="6" :controls="false" /></template></el-table-column>
          <el-table-column v-if="form.allocationMethod === 'MANUAL'" label="手工分摊金额" width="175"><template #default="{ row }"><el-input-number v-if="selectedIds.includes(row.inboundItemId)" v-model="manualAmounts[row.inboundItemId]" :min="0" :precision="2" :controls="false" /></template></el-table-column>
          <el-table-column label="预计分摊" width="125" align="right"><template #default="{ row }"><strong v-if="selectedIds.includes(row.inboundItemId)">{{ money(preview[row.inboundItemId] || 0) }}</strong><span v-else>-</span></template></el-table-column>
        </el-table>

        <div class="allocation-summary">
          <div><span>已选明细</span><strong>{{ selectedRows.length }} 条</strong></div>
          <div><span>分摊合计</span><strong :class="balanced ? 'ok' : 'warn'">{{ money(previewTotal) }}</strong></div>
          <div><span>预计计入当前库存</span><strong>{{ money(estimatedCapitalized) }}</strong></div>
          <div><span>预计已耗用成本</span><strong>{{ money(estimatedExpensed) }}</strong></div>
        </div>
        <el-alert v-if="selectedRows.some((row) => row.currentQuantity < row.originalQuantity)" type="warning" :closable="false" show-icon
          title="部分商品已经销售、退回或盘亏：该部分费用保留在期间成本，不会倒改历史销售成本。" />
        <el-form-item label="备注" class="memo"><el-input v-model="form.memo" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="写明运费单、装卸结算单、检验报告等业务背景" /></el-form-item>
        <div class="form-actions"><el-button @click="router.push(backTo)">取消</el-button><el-button type="primary" :loading="saving" @click="save">{{ isEdit ? '更新草稿' : '保存草稿' }}</el-button></div>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, type LandedCostAllocationCreate, type LandedCostAllocationMethod, type LandedCostInboundOption } from '@/api/client'
import { currentDateText, money, quantity } from '@/api/format'
import PageHeader from '@/components/PageHeader.vue'

const route = useRoute()
const router = useRouter()
const editId = computed(() => Number(route.params.id || 0))
const isEdit = computed(() => editId.value > 0)
const backTo = computed(() => typeof route.query.from === 'string' ? route.query.from : '/inventory/landed-cost-allocations')
const saving = ref(false)
const keyword = ref('')
const selectedIds = ref<number[]>([])
const basisValues = reactive<Record<number, number>>({})
const manualAmounts = reactive<Record<number, number>>({})
const originalSourceId = ref(0)
const form = reactive({ treasuryDocumentId: Number(route.query.sourceId || 0) || undefined as number | undefined, businessDate: currentDateText(), allocationMethod: 'QUANTITY' as LandedCostAllocationMethod, memo: '' })
const methods = [{ value: 'QUANTITY', label: '按入库数量' }, { value: 'WEIGHT', label: '按计费重量' }, { value: 'AMOUNT', label: '按未税金额' }, { value: 'MANUAL', label: '手工指定金额' }] as const
const optionsQuery = useQuery({ queryKey: ['landed-cost-options'], queryFn: api.landedCostOptions })
const detailQuery = useQuery({ queryKey: computed(() => ['landed-cost-allocation', editId.value]), queryFn: () => api.landedCostAllocation(editId.value), enabled: isEdit })
const sources = computed(() => optionsQuery.data.value?.sources ?? [])
const inbound = computed(() => optionsQuery.data.value?.inboundItems ?? [])
const source = computed(() => sources.value.find((item) => item.id === form.treasuryDocumentId))
const selectedRows = computed(() => selectedIds.value.map((id) => inbound.value.find((row) => row.inboundItemId === id)).filter(Boolean) as LandedCostInboundOption[])
const filteredInbound = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  return text ? inbound.value.filter((row) => [row.inboundNo, row.supplierName, row.productName, row.sku, row.spec, row.batchNo, row.warehouseName].some((value) => String(value || '').toLowerCase().includes(text))) : inbound.value
})
const preview = computed<Record<number, number>>(() => {
  const result: Record<number, number> = {}
  const rows = selectedRows.value
  const fee = Number(source.value?.capitalizableAmount || 0)
  if (!rows.length || fee <= 0) return result
  if (form.allocationMethod === 'MANUAL') { rows.forEach((row) => { result[row.inboundItemId] = round2(manualAmounts[row.inboundItemId] || 0) }); return result }
  const bases = rows.map((row) => form.allocationMethod === 'QUANTITY' ? Number(row.originalQuantity) : form.allocationMethod === 'AMOUNT' ? Number(row.amountExcludingTax) : Number(basisValues[row.inboundItemId] || 0))
  const total = bases.reduce((sum, value) => sum + value, 0)
  if (total <= 0) return result
  let used = 0
  rows.forEach((row, index) => { const amount = index === rows.length - 1 ? round2(fee - used) : round2(fee * bases[index] / total); result[row.inboundItemId] = amount; used = round2(used + amount) })
  return result
})
const previewTotal = computed(() => round2(Object.values(preview.value).reduce((sum, value) => sum + value, 0)))
const balanced = computed(() => Math.abs(previewTotal.value - Number(source.value?.capitalizableAmount || 0)) < 0.005)
const estimatedCapitalized = computed(() => round2(selectedRows.value.reduce((sum, row) => sum + (preview.value[row.inboundItemId] || 0) * Math.min(Math.max(Number(row.currentQuantity), 0), Number(row.originalQuantity)) / Number(row.originalQuantity), 0)))
const estimatedExpensed = computed(() => round2(previewTotal.value - estimatedCapitalized.value))

watch(detailQuery.data, (detail) => {
  if (!detail) return
  originalSourceId.value = detail.header.treasuryDocumentId
  Object.assign(form, { treasuryDocumentId: detail.header.treasuryDocumentId, businessDate: detail.header.businessDate, allocationMethod: detail.header.allocationMethod, memo: detail.header.memo })
  selectedIds.value = detail.items.map((item) => item.inboundItemId)
  detail.items.forEach((item) => { basisValues[item.inboundItemId] = Number(item.basisValue); manualAmounts[item.inboundItemId] = Number(item.allocatedAmount) })
}, { immediate: true })

function toggle(row: LandedCostInboundOption) {
  const exists = selectedIds.value.includes(row.inboundItemId)
  selectedIds.value = exists ? selectedIds.value.filter((id) => id !== row.inboundItemId) : [...selectedIds.value, row.inboundItemId]
  if (!exists) { basisValues[row.inboundItemId] ||= Number(row.suggestedWeight || 0); manualAmounts[row.inboundItemId] ||= 0 }
}
async function save() {
  if (!form.treasuryDocumentId || !source.value) return ElMessage.warning('请选择采购附加费用源单')
  if (!selectedRows.value.length) return ElMessage.warning('请至少选择一条采购入库明细')
  if (form.allocationMethod === 'WEIGHT' && selectedRows.value.some((row) => Number(basisValues[row.inboundItemId] || 0) <= 0)) return ElMessage.warning('请为每条已选明细填写大于0的计费重量')
  if (!balanced.value) return ElMessage.warning(`分摊合计必须等于计入成本金额 ${money(source.value.capitalizableAmount)}`)
  const payload: LandedCostAllocationCreate = { treasuryDocumentId: form.treasuryDocumentId, businessDate: form.businessDate, allocationMethod: form.allocationMethod, memo: form.memo.trim(), items: selectedRows.value.map((row) => ({ inboundItemId: row.inboundItemId, basisValue: form.allocationMethod === 'WEIGHT' ? Number(basisValues[row.inboundItemId] || 0) : 0, allocatedAmount: form.allocationMethod === 'MANUAL' ? Number(manualAmounts[row.inboundItemId] || 0) : 0 })) }
  saving.value = true
  try { const result = isEdit.value ? await api.updateLandedCostAllocation(editId.value, payload) : await api.createLandedCostAllocation(payload); ElMessage.success('采购费用分摊草稿已保存，尚未改变库存成本'); await router.push({ path: `/inventory/landed-cost-allocations/${result.id}`, query: { from: backTo.value } }) }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '分摊单保存失败') } finally { saving.value = false }
}
function round2(value: number) { return Math.round((value + Number.EPSILON) * 100) / 100 }
</script>

<style scoped>
.landed-form-panel{max-width:1500px;margin:0 auto}.form-alert{margin:0 18px 16px}.landed-form{padding:0 18px 20px}.top-grid{grid-template-columns:minmax(360px,2fr) 180px 180px}.source-field{min-width:0}.source-summary,.allocation-summary{display:grid;grid-template-columns:repeat(4,minmax(150px,1fr));gap:10px;margin:4px 0 20px}.source-summary div,.allocation-summary div{display:grid;gap:5px;padding:12px 14px;border:1px solid #e1e7ec;border-radius:9px;background:#f8fafb}.source-summary span,.allocation-summary span{color:#71808b;font-size:12px}.source-summary strong,.allocation-summary strong{font-size:16px}.line-header{display:flex;align-items:flex-end;justify-content:space-between;gap:18px;margin:18px 0 10px}.line-header .el-input{width:350px}.candidate-table :deep(.el-input-number){width:100%}.allocation-summary{margin-top:16px}.ok{color:var(--el-color-success)}.warn{color:var(--el-color-danger)}.memo{margin-top:18px}.form-actions{display:flex;justify-content:flex-end;gap:10px}@media(max-width:900px){.top-grid,.source-summary,.allocation-summary{grid-template-columns:1fr 1fr}.line-header{align-items:stretch;flex-direction:column}.line-header .el-input{width:100%}}@media(max-width:600px){.top-grid,.source-summary,.allocation-summary{grid-template-columns:1fr}}
</style>
