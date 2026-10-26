<template>
  <RelatedDetailDrawer
    :model-value="modelValue"
    :title="drawerTitle"
    :subtitle="kind === 'RECEIPT' ? '客户资金到账、应收核销及完整分配历史' : '供应商付款、应付核销及完整分配历史'"
    :trail="[{ label: originLabel }, { label: recordNo || drawerTitle }]"
    size="1040px"
    @update:model-value="emit('update:modelValue', $event)"
    @breadcrumb="emit('update:modelValue', false)"
  >
    <el-skeleton v-if="detailQuery.isLoading.value" :rows="8" animated />
    <el-result v-else-if="detailQuery.isError.value" icon="error" title="付款详情加载失败" sub-title="请关闭后重试" />
    <template v-else-if="detail">
      <el-descriptions class="fixed-detail-descriptions" :column="2" label-width="140px" border>
        <el-descriptions-item :label="kind === 'RECEIPT' ? '回款单号' : '付款单号'"><span class="related-record-no">{{ detail.paymentNo }}</span></el-descriptions-item>
        <el-descriptions-item :label="kind === 'RECEIPT' ? '客户' : '供应商'">{{ detail.partnerName }}</el-descriptions-item>
        <el-descriptions-item label="本次金额">{{ money(detail.amount) }}</el-descriptions-item>
        <el-descriptions-item label="资金性质">{{ fundNatureName(detail.fundNature, detail.paymentMethod) }}</el-descriptions-item>
        <el-descriptions-item label="已分配金额"><span class="amount-success">{{ money(detail.allocatedAmount) }}</span></el-descriptions-item>
        <el-descriptions-item label="未分配金额"><span :class="{ 'amount-warning': detail.unallocatedAmount > 0 }">{{ money(detail.unallocatedAmount) }}</span></el-descriptions-item>
        <el-descriptions-item :label="kind === 'RECEIPT' ? '收款方式' : '付款方式'">{{ paymentMethodName(detail.paymentMethod) }}</el-descriptions-item>
        <el-descriptions-item :label="kind === 'RECEIPT' ? '收款账户' : '付款账户'">{{ settlementAccountName }}</el-descriptions-item>
        <el-descriptions-item :label="kind === 'RECEIPT' ? '到账日期' : '付款日期'">{{ formatDate(detail.businessDate) }}</el-descriptions-item>
        <el-descriptions-item label="核销状态"><el-tag :type="statusTagType(detail.verificationStatus)" effect="light">{{ verificationStatusName(detail.verificationStatus) }}</el-tag></el-descriptions-item>
      </el-descriptions>

      <section class="detail-section">
        <div class="detail-section__header">
          <div>
            <div class="detail-section__title">核销分配明细</div>
            <div class="detail-section__hint">有效、已释放和已冲正记录全部保留，可从资金追溯至原始业务单据。</div>
          </div>
          <el-button
            v-if="canAdjust"
            type="primary"
            plain
            @click="openAdjustment"
          >调整核销分配</el-button>
        </div>
        <el-table :data="detail.allocations" size="small" border empty-text="本笔资金尚未分配到业务单据">
          <el-table-column prop="allocationNo" label="分配编号" width="190" />
          <el-table-column label="目标单据" min-width="210">
            <template #default="{ row }">
              <el-button link type="primary" @click="openAllocationTarget(row)">{{ targetTypeName(row.targetType) }} {{ row.targetNo }}</el-button>
            </template>
          </el-table-column>
          <el-table-column label="分配金额" width="130" align="right"><template #default="{ row }">{{ money(row.allocatedAmount) }}</template></el-table-column>
          <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="allocationTagType(row.status)" effect="light">{{ allocationStatusName(row.status) }}</el-tag></template></el-table-column>
          <el-table-column label="形成方式" width="110"><template #default="{ row }">{{ captureSourceName(row.captureSource) }}</template></el-table-column>
          <el-table-column label="处理说明" min-width="180" show-overflow-tooltip><template #default="{ row }">{{ row.releaseReason || row.reversalReason || '-' }}</template></el-table-column>
        </el-table>
      </section>

      <section class="detail-section">
        <div class="detail-section__header">
          <div>
            <div class="detail-section__title">分配审计记录</div>
            <div class="detail-section__hint">每次分配、释放和整笔冲正均记录业务日期、原因和操作人。</div>
          </div>
        </div>
        <el-timeline v-if="detail.events.length" class="audit-timeline">
          <el-timeline-item v-for="event in [...detail.events].reverse()" :key="event.id" :timestamp="`${formatDate(event.businessDate)} · ${event.operatorName || '系统'}`" placement="top">
            <div class="audit-event"><b>{{ eventActionName(event.action) }}</b><span>{{ money(event.amount) }}</span><span>{{ event.reason || '-' }}</span></div>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else :image-size="64" description="暂无分配审计记录" />
      </section>

      <EvidenceAttachments
        :document-type="kind === 'RECEIPT' ? 'PAYMENT' : 'SUPPLIER_PAYMENT'"
        :document-id="detail.paymentId"
        :title="kind === 'RECEIPT' ? '银行回单、签收与收款凭据' : '银行回单、审批与付款凭据'"
        default-evidence-type="PAYMENT_PROOF"
      />
    </template>

    <template #footer><el-button @click="emit('update:modelValue', false)">关闭</el-button></template>
  </RelatedDetailDrawer>

  <el-dialog v-model="adjustmentVisible" title="调整核销分配" width="980px" destroy-on-close append-to-body>
    <el-alert type="info" :closable="false" title="调整不会重复收付款；已分配总额变化时会形成未分配资金与应收应付的重分类过账，等额换单不重复制证。已参与发票资金勾稽的记录需先解除勾稽。" />
    <div class="adjustment-block">
      <div class="adjustment-title">1. 选择需要释放的有效分配</div>
      <el-checkbox-group v-model="adjustment.releaseAllocationIds">
        <el-table :data="activeAllocations" size="small" border empty-text="没有可释放的有效分配">
          <el-table-column width="54" align="center"><template #default="{ row }"><el-checkbox :value="row.id" /></template></el-table-column>
          <el-table-column label="目标单据" min-width="220"><template #default="{ row }">{{ targetTypeName(row.targetType) }} {{ row.targetNo }}</template></el-table-column>
          <el-table-column label="当前分配" width="140" align="right"><template #default="{ row }">{{ money(row.allocatedAmount) }}</template></el-table-column>
        </el-table>
      </el-checkbox-group>
    </div>
    <div class="adjustment-block">
      <div class="adjustment-title">2. 使用未分配余额或已释放金额重新分配</div>
      <SettlementAllocationEditor
        v-model="adjustment.allocations"
        :candidates="adjustmentCandidates"
        :total-amount="adjustmentAvailable"
        :direction-label="kind === 'RECEIPT' ? '收款' : '付款'"
      />
    </div>
    <el-form label-width="92px">
      <el-form-item label="业务日期" required><el-date-picker v-model="adjustment.businessDate" value-format="YYYY-MM-DD" /></el-form-item>
      <el-form-item label="调整原因" required><el-input v-model="adjustment.reason" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="请说明释放或重分配原因，审计记录将永久保留" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="adjustmentVisible = false">取消</el-button>
      <el-button type="primary" :loading="changeMutation.isPending.value" @click="submitAdjustment">确认调整</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { api, type PaymentAllocationRow, type PaymentRow, type SupplierPaymentRow } from '@/api/client'
import { accountTypeName, currentDateText, formatDate, money, paymentMethodName } from '@/api/format'
import RelatedDetailDrawer from '@/components/RelatedDetailDrawer.vue'
import EvidenceAttachments from '@/components/EvidenceAttachments.vue'
import SettlementAllocationEditor, { type SettlementAllocationCandidate, type SettlementAllocationValue } from '@/components/SettlementAllocationEditor.vue'
import { relatedDetailRoute } from '@/utils/navigation'
import { useAppStore } from '@/stores/app'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const queryClient = useQueryClient()

const props = withDefaults(defineProps<{
  modelValue: boolean
  kind: 'RECEIPT' | 'PAYMENT'
  record?: PaymentRow | SupplierPaymentRow
  originLabel?: string
}>(), { record: undefined, originLabel: '业务列表' })

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const recordNo = computed(() => props.record?.paymentNo || '')
const drawerTitle = computed(() => `${props.kind === 'RECEIPT' ? '回款' : '付款'}详情`)

const detailQuery = useQuery({
  queryKey: computed(() => ['payment-allocation-detail', props.kind, props.record?.id]),
  queryFn: () => props.kind === 'RECEIPT'
    ? api.paymentAllocationDetail(props.record!.id)
    : api.supplierPaymentAllocationDetail(props.record!.id),
  enabled: computed(() => props.modelValue && Boolean(props.record?.id)),
})
const detail = computed(() => detailQuery.data.value)
const settlementAccountName = computed(() => detail.value?.paymentMethod === 'BANK'
  ? detail.value.businessBankAccountName || '未指定企业银行账户'
  : accountTypeName(props.record?.accountType || '') || paymentMethodName(detail.value?.paymentMethod || ''))
const canAdjust = computed(() => detail.value?.fundNature === 'SETTLEMENT'
  && detail.value.verificationStatus !== 'REVERSED' && appStore.hasPermission('finance.manage'))
const activeAllocations = computed(() => detail.value?.allocations.filter((item) => item.status === 'ACTIVE') ?? [])

const adjustmentVisible = ref(false)
const adjustment = reactive({
  releaseAllocationIds: [] as number[],
  allocations: [] as SettlementAllocationValue[],
  businessDate: currentDateText(),
  reason: '',
})
const { data: receivables } = useQuery({
  queryKey: computed(() => ['receivables', detail.value?.partnerId]),
  queryFn: () => api.receivables({ customerId: detail.value!.partnerId }),
  enabled: computed(() => adjustmentVisible.value && props.kind === 'RECEIPT' && Boolean(detail.value?.partnerId)),
})
const { data: payables } = useQuery({
  queryKey: computed(() => ['payables', detail.value?.partnerId]),
  queryFn: () => api.payables({ supplierId: detail.value!.partnerId }),
  enabled: computed(() => adjustmentVisible.value && props.kind === 'PAYMENT' && Boolean(detail.value?.partnerId)),
})

const releasedAmount = computed(() => activeAllocations.value
  .filter((item) => adjustment.releaseAllocationIds.includes(item.id))
  .reduce((sum, item) => sum + Number(item.allocatedAmount), 0))
const adjustmentAvailable = computed(() => Number(detail.value?.unallocatedAmount || 0) + releasedAmount.value)
const remainingTargets = computed(() => new Set(activeAllocations.value
  .filter((item) => !adjustment.releaseAllocationIds.includes(item.id))
  .map((item) => `${item.targetType}:${item.targetId}`)))
const releasedByTarget = computed(() => new Map(activeAllocations.value
  .filter((item) => adjustment.releaseAllocationIds.includes(item.id))
  .map((item) => [`${item.targetType}:${item.targetId}`, Number(item.allocatedAmount)])))
const adjustmentCandidates = computed<SettlementAllocationCandidate[]>(() => {
  const source = props.kind === 'RECEIPT' ? (receivables.value ?? []) : (payables.value ?? [])
  return source
    .filter((item) => !remainingTargets.value.has(`${item.sourceType}:${item.sourceId}`))
    .map((item) => ({
      targetType: item.sourceType,
      targetId: item.sourceId,
      targetNo: item.sourceNo,
      businessDate: item.businessDate,
      unpaidAmount: Number(item.unpaidAmount) + (releasedByTarget.value.get(`${item.sourceType}:${item.sourceId}`) || 0),
    })) as SettlementAllocationCandidate[]
})

watch(() => adjustment.releaseAllocationIds, () => { adjustment.allocations = [] }, { deep: true })

const changeMutation = useMutation({
  mutationFn: () => {
    const payload = {
      reason: adjustment.reason.trim(),
      businessDate: adjustment.businessDate,
      releaseAllocationIds: adjustment.releaseAllocationIds,
      allocations: adjustment.allocations,
    }
    return props.kind === 'RECEIPT'
      ? api.changePaymentAllocation(props.record!.id, payload)
      : api.changeSupplierPaymentAllocation(props.record!.id, payload)
  },
  onSuccess: async () => {
    ElMessage.success('核销分配已调整，原分配与审计记录均已保留')
    adjustmentVisible.value = false
    await Promise.all([
      detailQuery.refetch(),
      queryClient.invalidateQueries({ queryKey: [props.kind === 'RECEIPT' ? 'payments' : 'supplier-payments'] }),
      queryClient.invalidateQueries({ queryKey: [props.kind === 'RECEIPT' ? 'receivables' : 'payables'] }),
      queryClient.invalidateQueries({ queryKey: [props.kind === 'RECEIPT' ? 'sales-orders' : 'purchase-orders'] }),
      queryClient.invalidateQueries({ queryKey: [props.kind === 'RECEIPT' ? 'outbounds' : 'inbounds'] }),
    ])
  },
})

function openAdjustment() {
  adjustment.releaseAllocationIds = []
  adjustment.allocations = []
  adjustment.businessDate = currentDateText()
  adjustment.reason = ''
  adjustmentVisible.value = true
}

function submitAdjustment() {
  if (!adjustment.releaseAllocationIds.length && !adjustment.allocations.length) return ElMessage.warning('请选择要释放的分配，或填写新的分配金额')
  if (!adjustment.businessDate) return ElMessage.warning('请选择业务日期')
  if (!adjustment.reason.trim()) return ElMessage.warning('请填写调整原因')
  const added = adjustment.allocations.reduce((sum, item) => sum + Number(item.amount), 0)
  if (added - adjustmentAvailable.value > 0.000001) return ElMessage.warning('新增分配金额超过可分配余额')
  if (['ADVANCE', 'REWARD'].includes(detail.value?.paymentMethod || '')) {
    const nextAllocated = Number(detail.value?.allocatedAmount || 0) - releasedAmount.value + added
    const nextActiveCount = remainingTargets.value.size + adjustment.allocations.length
    if (nextActiveCount !== 1 || Math.abs(nextAllocated - Number(detail.value?.amount || 0)) > 0.000001) {
      return ElMessage.warning(kindLabel('预收款/奖励金余额核销', '预付款/返利金余额核销') + '必须始终保持一张单据全额分配')
    }
  }
  changeMutation.mutate()
}

function verificationStatusName(value: string) {
  if (value === 'VERIFIED') return '已全部分配'
  if (value === 'PARTIAL') return '部分分配'
  if (value === 'REVERSED') return '已冲正'
  if (value === 'UNAPPLIED') return '未分配'
  return value || '-'
}
function fundNatureName(fundNature: string, paymentMethod: string) {
  if (paymentMethod === 'ADVANCE') return kindLabel('客户预收款余额核销', '供应商预付款余额核销')
  if (paymentMethod === 'REWARD') return kindLabel('客户奖励金余额核销', '供应商返利金余额核销')
  if (fundNature === 'ADVANCE') return kindLabel('客户无单预收款', '供应商无单预付款')
  return '普通结算资金'
}
function kindLabel(receipt: string, payment: string) { return props.kind === 'RECEIPT' ? receipt : payment }
function statusTagType(value: string) { return value === 'VERIFIED' ? 'success' : value === 'REVERSED' ? 'info' : value === 'UNAPPLIED' ? 'warning' : 'primary' }
function allocationStatusName(value: string) {
  return ({ ACTIVE: '有效', RELEASED: '已释放', REVERSED: '已冲正' } as Record<string, string>)[value] || value
}
function allocationTagType(value: string) { return value === 'ACTIVE' ? 'success' : 'info' }
function captureSourceName(value: string) { return value === 'REALLOCATION' ? '重分配' : value === 'LEGACY_BACKFILL' ? '历史回填' : '收付款时分配' }
function eventActionName(value: string) {
  return ({ APPLIED: '新增分配', RELEASED: '释放分配', HEAD_REVERSED: '整笔资金冲正' } as Record<string, string>)[value] || value
}
function targetTypeName(value: PaymentAllocationRow['targetType']) { return ({ SALES_ORDER: '销售订单', OUTBOUND: '出库单', PURCHASE_ORDER: '采购订单', INBOUND: '入库单' })[value] }

function openAllocationTarget(row: PaymentAllocationRow) {
  const base = ({ SALES_ORDER: '/sales-orders', OUTBOUND: '/outbounds', PURCHASE_ORDER: '/purchase-orders', INBOUND: '/inbounds' })[row.targetType]
  emit('update:modelValue', false)
  router.push(relatedDetailRoute(route, `${base}/${row.targetId}`))
}
</script>

<style scoped>
.amount-success { color: var(--el-color-success); font-weight: 600; }
.amount-warning { color: var(--el-color-warning); font-weight: 600; }
.detail-section { margin-top: 20px; }
.detail-section__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 10px; }
.detail-section__title, .adjustment-title { color: var(--el-text-color-primary); font-size: 14px; font-weight: 600; }
.detail-section__hint { margin-top: 3px; color: var(--el-text-color-secondary); font-size: 12px; }
.audit-timeline { padding: 8px 8px 0 4px; }
.audit-event { display: flex; flex-wrap: wrap; gap: 8px 18px; color: var(--el-text-color-regular); }
.audit-event b { color: var(--el-text-color-primary); }
.adjustment-block { margin-top: 18px; }
.adjustment-title { margin-bottom: 10px; }
</style>
