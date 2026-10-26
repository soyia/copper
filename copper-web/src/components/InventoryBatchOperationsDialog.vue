<template>
  <el-dialog v-model="visible" :title="`批次处理 ${batch?.batchNo || ''}`" width="900px" destroy-on-close>
    <el-tabs v-if="batch" v-model="activeTab">
      <el-tab-pane v-if="batch && false" label="送样 / 领用 / 损耗" name="non-trade">
        <el-alert title="这是实际库存变化，保存即确认并生成库存流水与成本过账。填错后必须冲正，不能直接改库存。" type="warning" :closable="false" show-icon style="margin-bottom:14px" />
        <el-form v-if="appStore.hasPermission('inventory.nontrade.manage')" label-position="top">
          <div class="form-grid">
            <el-form-item label="业务类型">
              <el-select v-model="nonTrade.businessType" style="width:100%">
                <el-option label="送样出库" value="SAMPLE_OUT" />
                <el-option label="内部领用" value="INTERNAL_USE" />
                <el-option label="合理损耗" value="NORMAL_LOSS" />
                <el-option label="其他出库" value="OTHER_OUT" />
                <el-option label="其他入库" value="OTHER_IN" />
              </el-select>
            </el-form-item>
            <el-form-item label="业务日期"><el-date-picker v-model="nonTrade.businessDate" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
            <el-form-item label="数量">
              <el-input-number v-model="nonTrade.quantity" :min="0.000001" :max="nonTrade.businessType === 'OTHER_IN' ? undefined : Number(batch?.quantityFree || 0)" :precision="6" style="width:100%" :controls="false" />
              <div class="muted">当前自由可用 {{ quantity(batch?.quantityFree || 0) }} {{ batch?.baseUnit || '-' }}</div>
            </el-form-item>
            <el-form-item label="领取人 / 去向"><el-input v-model="nonTrade.recipient" maxlength="128" /></el-form-item>
            <el-form-item v-if="requiresSourcePackage" label="来源实重包装分配" required class="full-row">
              <el-table :data="nonTrade.packageAllocations" border size="small" class="allocation-table">
                <el-table-column label="来源包装 *" min-width="300">
                  <template #default="{ row, $index }">
                    <el-select
                      v-model="row.packageInstanceId"
                      filterable
                      clearable
                      placeholder="选择实际取样的盘"
                      style="width:100%"
                    >
                      <el-option
                        v-for="item in availablePackageOptions($index)"
                        :key="item.id"
                        :value="item.id"
                        :label="packageOptionLabel(item)"
                      />
                    </el-select>
                  </template>
                </el-table-column>
                <el-table-column label="当前剩余" width="125" align="right">
                  <template #default="{ row }">{{ packageRemainingText(row.packageInstanceId) }}</template>
                </el-table-column>
                <el-table-column label="本盘扣取 *" width="165">
                  <template #default="{ row }">
                    <el-input-number
                      v-model="row.quantity"
                      :min="0.000001"
                      :max="packageRemaining(row.packageInstanceId) || undefined"
                      :precision="6"
                      :controls="false"
                      placeholder="输入扣取量"
                      style="width:100%"
                    />
                  </template>
                </el-table-column>
                <el-table-column label="扣后剩余" width="125" align="right">
                  <template #default="{ row }">{{ packageAfterText(row) }}</template>
                </el-table-column>
                <el-table-column label="操作" width="70" align="center">
                  <template #default="{ $index }">
                    <el-button link type="danger" @click="removePackageAllocation($index)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <div class="allocation-footer">
                <span :class="allocationDifferenceUnits === 0n && allocatedTotalUnits > 0n ? 'allocation-ok' : 'muted'">
                  {{ allocationSummary }}
                </span>
                <el-button plain @click="addPackageAllocation">增加来源包装</el-button>
              </div>
              <div class="muted">可将一次送样、领用或损耗分摊到多个包装；每行填写该包装的实际扣取量。</div>
            </el-form-item>
          </div>
          <el-form-item label="原因（必填）"><el-input v-model="nonTrade.reason" maxlength="500" placeholder="例如：寄给客户试样、门店自用、运输合理损耗" /></el-form-item>
          <div class="dialog-action"><el-button type="primary" :loading="savingNonTrade" @click="confirmNonTrade">确认并记库存</el-button></div>
        </el-form>
        <el-table :data="nonTradeQuery.data.value ?? []" border size="small" style="margin-top:14px">
          <el-table-column prop="documentNo" label="单号" min-width="170" />
          <el-table-column label="类型" width="110"><template #default="{ row }">{{ nonTradeName(row.businessType) }}</template></el-table-column>
          <el-table-column prop="businessDate" label="日期" width="110" />
          <el-table-column label="数量" width="120" align="right"><template #default="{ row }">{{ quantity(row.quantity) }} {{ row.baseUnit }}</template></el-table-column>
          <el-table-column v-if="appStore.hasPermission('inventory.cost.view')" label="成本" width="120" align="right"><template #default="{ row }">{{ money(row.amountCost) }}</template></el-table-column>
          <el-table-column prop="reason" label="原因" min-width="170" />
          <el-table-column label="状态" width="90"><template #default="{ row }">{{ row.status === 'CONFIRMED' ? '已确认' : '已冲正' }}</template></el-table-column>
          <el-table-column v-if="appStore.hasPermission('inventory.nontrade.manage')" label="操作" width="90"><template #default="{ row }"><el-button v-if="row.status === 'CONFIRMED'" link type="danger" @click="reverseNonTrade(row.id)">冲正</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="质保书 / 批次质量" name="quality">
        <div class="quality-heading">
          <div class="muted">质保书按批次留存并复核；已复核记录不能覆盖修改，只能作废后新建。</div>
          <el-button v-if="appStore.hasPermission('inventory.quality.manage')" type="primary" plain @click="qualityDialogVisible=true">登记质保书</el-button>
        </div>
        <el-table :data="qualityQuery.data.value ?? []" border size="small" style="margin-top:12px">
          <el-table-column prop="certificateNo" label="质保书号" min-width="160" />
          <el-table-column prop="manufacturerName" label="生产厂家" min-width="140" />
          <el-table-column prop="manufacturerBatchNo" label="厂家批次" min-width="130" />
          <el-table-column prop="heatNo" label="炉号" width="110" />
          <el-table-column prop="issueDate" label="签发日期" width="110" />
          <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="qualityStatusType(row.verificationStatus)">{{ qualityStatus(row.verificationStatus) }}</el-tag></template></el-table-column>
          <el-table-column v-if="appStore.hasPermission('inventory.quality.manage')" label="操作" width="150"><template #default="{ row }"><el-button v-if="row.verificationStatus === 'PENDING'" link type="success" @click="review(row, 'VERIFIED')">通过</el-button><el-button v-if="row.status === 'ENABLED'" link type="danger" @click="review(row, row.verificationStatus === 'PENDING' ? 'REJECTED' : 'VOID')">{{ row.verificationStatus === 'PENDING' ? '驳回' : '作废' }}</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="qualityDialogVisible" title="登记批次质保书" width="620px" append-to-body>
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="质保书号"><el-input v-model="quality.certificateNo" maxlength="128" /></el-form-item>
          <el-form-item label="签发日期"><el-date-picker v-model="quality.issueDate" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
          <el-form-item label="生产厂家"><el-input v-model="quality.manufacturerName" /></el-form-item>
          <el-form-item label="厂家批次"><el-input v-model="quality.manufacturerBatchNo" /></el-form-item>
          <el-form-item label="炉号"><el-input v-model="quality.heatNo" /></el-form-item>
          <el-form-item label="卷号"><el-input v-model="quality.coilNo" /></el-form-item>
        </div>
      </el-form>
      <el-alert title="本次登记基础批次标识；详细检测指标可后续按实际质保书逐项补录，不能编造。" type="info" :closable="false" show-icon />
      <template #footer><el-button @click="qualityDialogVisible=false">取消</el-button><el-button type="primary" :loading="savingQuality" @click="saveQuality">保存待复核</el-button></template>
    </el-dialog>
  </el-dialog>
</template>

<script setup lang="ts">
import { quantityUnits, quantityFromUnits, sameQuantity } from '@/utils/quantity'
import { computed, reactive, ref, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type InventoryBatch, type InventoryPackageInstance, type ProductQualityCertificate } from '@/api/client'
import { money, quantity } from '@/api/format'
import { useAppStore } from '@/stores/app'

const props = defineProps<{ modelValue: boolean; batch?: InventoryBatch }>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void; (e: 'changed'): void }>()
const appStore = useAppStore()
const visible = computed({ get: () => props.modelValue, set: (value) => emit('update:modelValue', value) })
const activeTab = ref('quality')
const nonTradeQuery = useQuery({
  queryKey: ['inventory-non-trade', computed(() => props.batch?.id)],
  queryFn: () => api.inventoryNonTrade({ batchId: props.batch!.id }),
  enabled: computed(() => Boolean(props.modelValue && props.batch?.id && appStore.hasPermission('inventory.nontrade.view'))),
})
const qualityQuery = useQuery({
  queryKey: ['inventory-batch-quality', computed(() => props.batch?.id)],
  queryFn: () => api.inventoryBatchQualityCertificates(props.batch!.id),
  enabled: computed(() => Boolean(props.modelValue && props.batch?.id && appStore.hasPermission('inventory.quality.view'))),
})
const packageInstancesQuery = useQuery({
  queryKey: ['inventory-package-instances', computed(() => props.batch?.id)],
  queryFn: () => api.inventoryPackageInstances(props.batch!.id),
  enabled: computed(() => Boolean(props.modelValue && props.batch?.id && props.batch.packagingTrackingMode === 'ACTUAL_INSTANCE')),
})
type PackageAllocationInput = { packageInstanceId?: number; quantity?: number }
const nonTrade = reactive({
  businessType: 'SAMPLE_OUT',
  businessDate: today(),
  quantity: 0,
  recipient: '',
  reason: '',
  packageAllocations: [emptyPackageAllocation()] as PackageAllocationInput[],
})
const savingNonTrade = ref(false)
const qualityDialogVisible = ref(false)
const savingQuality = ref(false)
const quality = reactive({ certificateNo: '', issueDate: today(), manufacturerName: '', manufacturerBatchNo: '', heatNo: '', coilNo: '' })
const requiresSourcePackage = computed(() =>
  props.batch?.packagingTrackingMode === 'ACTUAL_INSTANCE' && nonTrade.businessType !== 'OTHER_IN')
const packagesById = computed(() =>
  new Map((packageInstancesQuery.data.value ?? []).map((item) => [item.id, item])))
const allocatedTotalUnits = computed(() =>
  nonTrade.packageAllocations.reduce((sum, item) => sum + quantityUnits(item.quantity), 0n))
const allocationDifferenceUnits = computed(() => quantityUnits(nonTrade.quantity) - allocatedTotalUnits.value)
const allocationSummary = computed(() => {
  const unit = props.batch?.baseUnit || ''
  const allocated = displayQuantityUnits(allocatedTotalUnits.value)
  const target = displayQuantityUnits(quantityUnits(nonTrade.quantity))
  if (allocatedTotalUnits.value <= 0n) return `已分配 0.00 ${unit} / 本次数量 ${target} ${unit}`
  if (allocationDifferenceUnits.value > 0n) {
    return `已分配 ${allocated} ${unit} / 本次数量 ${target} ${unit}，还需分配 ${displayQuantityUnits(allocationDifferenceUnits.value)} ${unit}`
  }
  if (allocationDifferenceUnits.value < 0n) {
    return `已分配 ${allocated} ${unit} / 本次数量 ${target} ${unit}，已超出 ${displayQuantityUnits(-allocationDifferenceUnits.value)} ${unit}`
  }
  return `已分配 ${allocated} ${unit}，与本次数量一致`
})

watch(() => props.modelValue, (value) => {
  if (!value) return
  resetNonTrade()
  nonTradeQuery.refetch()
  if (props.batch?.packagingTrackingMode === 'ACTUAL_INSTANCE') packageInstancesQuery.refetch()
  qualityQuery.refetch()
})
watch(() => nonTrade.businessType, () => {
  nonTrade.packageAllocations = requiresSourcePackage.value ? [emptyPackageAllocation()] : []
})
async function confirmNonTrade() {
  if (!props.batch || nonTrade.quantity <= 0 || !nonTrade.reason.trim()) return ElMessage.warning('请填写数量和原因')
  const packageAllocations = nonTrade.packageAllocations.map((item) => ({
    packageInstanceId: Number(item.packageInstanceId || 0),
    quantity: Number(item.quantity || 0),
  }))
  if (requiresSourcePackage.value) {
    const allocationError = validatePackageAllocations(packageAllocations)
    if (allocationError) return ElMessage.warning(allocationError)
  }
  const submittedAllocations = requiresSourcePackage.value ? packageAllocations : []
  savingNonTrade.value = true
  try {
    await api.confirmInventoryNonTrade({
      batchId: props.batch.id,
      businessType: nonTrade.businessType,
      businessDate: nonTrade.businessDate,
      quantity: nonTrade.quantity,
      recipient: nonTrade.recipient,
      reason: nonTrade.reason,
      memo: '',
      packageInstanceIds: submittedAllocations.map((item) => item.packageInstanceId),
      packageAllocations: submittedAllocations,
    })
    ElMessage.success('库存事实已确认并过账')
    resetNonTrade()
    await nonTradeQuery.refetch()
    await packageInstancesQuery.refetch()
    emit('changed')
  } finally { savingNonTrade.value = false }
}
async function reverseNonTrade(id: number) {
  const { value } = await ElMessageBox.prompt('冲正会反向恢复库存并保留原记录。请填写原因。', '冲正其他出入库', { inputPattern: /.{2,}/, inputErrorMessage: '至少填写2个字' })
  await api.reverseInventoryNonTrade(id, value)
  ElMessage.success('已冲正')
  await nonTradeQuery.refetch()
  await packageInstancesQuery.refetch()
  emit('changed')
}
async function saveQuality() {
  if (!props.batch || !quality.certificateNo.trim()) return ElMessage.warning('请填写质保书号')
  savingQuality.value = true
  try {
    await api.createInventoryBatchQualityCertificate(props.batch.id, { ...quality, certificateType: 'COA', status: 'ENABLED', results: [] })
    qualityDialogVisible.value = false
    Object.assign(quality, { certificateNo: '', issueDate: today(), manufacturerName: '', manufacturerBatchNo: '', heatNo: '', coilNo: '' })
    ElMessage.success('质保书已登记，等待复核')
    await qualityQuery.refetch()
  } finally { savingQuality.value = false }
}
async function review(row: ProductQualityCertificate, status: 'VERIFIED' | 'REJECTED' | 'VOID') {
  const message = status === 'VERIFIED' ? '确认质保书批次标识与原件一致？' : '请填写原因。'
  let reason = '与原件核对一致'
  if (status === 'VERIFIED') await ElMessageBox.confirm(message, '通过质保书复核', { type: 'warning' })
  else reason = (await ElMessageBox.prompt(message, status === 'VOID' ? '作废质保书' : '驳回质保书', { inputPattern: /.{2,}/, inputErrorMessage: '至少填写2个字' })).value
  await api.reviewInventoryBatchQualityCertificate(props.batch!.id, row.id, { reviewStatus: status, reviewComment: reason, expectedLockVersion: row.lockVersion })
  ElMessage.success('质保书状态已更新')
  await qualityQuery.refetch()
}
function nonTradeName(value: string) { return ({ SAMPLE_OUT: '送样', INTERNAL_USE: '领用', NORMAL_LOSS: '合理损耗', OTHER_OUT: '其他出库', OTHER_IN: '其他入库' } as Record<string, string>)[value] || value }
function qualityStatus(value: string) { return ({ PENDING: '待复核', VERIFIED: '已通过', REJECTED: '已驳回', VOID: '已作废' } as Record<string, string>)[value] || value }
function qualityStatusType(value: string) { return ({ PENDING: 'warning', VERIFIED: 'success', REJECTED: 'danger', VOID: 'info' } as Record<string, string>)[value] || 'info' }
function packageOptionLabel(item: InventoryPackageInstance) {
  const remaining = Number(item.remainingBaseQuantity ?? item.actualBaseQuantity)
  const original = Number(item.actualBaseQuantity)
  const remainder = sameQuantity(remaining, original) ? '' : ` / 原始 ${quantity(original)}${item.baseUnit}`
  return `${item.packageCode} · 剩余 ${quantity(remaining)}${item.baseUnit}${remainder}`
}
function emptyPackageAllocation(): PackageAllocationInput {
  return { packageInstanceId: undefined, quantity: undefined }
}
function resetNonTrade() {
  Object.assign(nonTrade, {
    businessType: 'SAMPLE_OUT',
    businessDate: today(),
    quantity: 0,
    recipient: '',
    reason: '',
    packageAllocations: [emptyPackageAllocation()],
  })
}
function availablePackageOptions(rowIndex: number) {
  const currentId = nonTrade.packageAllocations[rowIndex]?.packageInstanceId
  const selectedElsewhere = new Set(nonTrade.packageAllocations
    .filter((_, index) => index !== rowIndex)
    .map((item) => item.packageInstanceId)
    .filter(Boolean))
  return (packageInstancesQuery.data.value ?? []).filter((item) =>
    item.status === 'AVAILABLE'
    && Number(item.remainingBaseQuantity ?? item.actualBaseQuantity) > 0
    && (item.id === currentId || !selectedElsewhere.has(item.id)))
}
function addPackageAllocation() {
  nonTrade.packageAllocations.push(emptyPackageAllocation())
}
function removePackageAllocation(index: number) {
  if (nonTrade.packageAllocations.length === 1) {
    nonTrade.packageAllocations.splice(0, 1, emptyPackageAllocation())
    return
  }
  nonTrade.packageAllocations.splice(index, 1)
}
function packageRemaining(packageInstanceId?: number) {
  const item = packageInstanceId ? packagesById.value.get(packageInstanceId) : undefined
  return item ? Number(item.remainingBaseQuantity ?? item.actualBaseQuantity) : 0
}
function packageRemainingText(packageInstanceId?: number) {
  if (!packageInstanceId) return '—'
  return `${displayQuantityUnits(quantityUnits(packageRemaining(packageInstanceId)))} ${props.batch?.baseUnit || ''}`
}
function packageAfterText(row: PackageAllocationInput) {
  if (!row.packageInstanceId || !row.quantity) return '—'
  return `${displayQuantityUnits(quantityUnits(packageRemaining(row.packageInstanceId)) - quantityUnits(row.quantity))} ${props.batch?.baseUnit || ''}`
}
function validatePackageAllocations(allocations: Array<{ packageInstanceId: number; quantity: number }>) {
  if (allocations.length === 0) return '请至少增加一个来源实重包装'
  if (allocations.some((item) => !item.packageInstanceId)) return '请为每一行选择来源实重包装'
  if (allocations.some((item) => quantityUnits(item.quantity) <= 0n)) return '请填写每个来源包装的扣取量'
  if (new Set(allocations.map((item) => item.packageInstanceId)).size !== allocations.length) {
    return '同一个来源实重包装不能重复选择'
  }
  for (const item of allocations) {
    if (quantityUnits(item.quantity) > quantityUnits(packageRemaining(item.packageInstanceId))) {
      return '本盘扣取量不能超过该包装当前剩余量'
    }
  }
  if (allocationDifferenceUnits.value !== 0n) return '各来源包装扣取量合计必须与本次数量完全一致'
  return ''
}
function displayQuantityUnits(value: bigint) { return quantity(quantityFromUnits(value)) }
function today() { return new Date().toLocaleDateString('sv-SE') }
</script>

<style scoped>
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 14px; }
.full-row { grid-column: 1 / -1; }
.allocation-table { width: 100%; }
.allocation-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 10px; }
.allocation-ok { color: var(--el-color-success); }
.dialog-action, .quality-heading { display: flex; justify-content: flex-end; }
.quality-heading { align-items: center; justify-content: space-between; gap: 16px; }
@media (max-width: 700px) { .form-grid { grid-template-columns: 1fr; } }
</style>
