<template>
  <el-form ref="formRef" class="entry-form" :model="form" :rules="rules" label-width="88px">
    <el-row :gutter="14">
      <el-col :span="layout.partner">
        <el-form-item label="供应商" prop="supplierId">
          <PartnerRemoteSelect v-model="form.supplierId" type="SUPPLIER" :disabled="lockSource" @change="handleSupplierChange" />
        </el-form-item>
      </el-col>
      <el-col :span="layout.amount">
        <el-form-item label="金额" prop="amount">
          <el-input v-model="form.amount" inputmode="decimal" placeholder="请输入付款金额" />
        </el-form-item>
      </el-col>
      <el-col :span="layout.date">
        <el-form-item label="付款日期" prop="paidDate">
          <el-date-picker v-model="form.paidDate" value-format="YYYY-MM-DD" />
        </el-form-item>
      </el-col>
      <el-col :span="layout.method">
        <el-form-item label="结算方式" prop="paymentMethod">
          <el-select v-model="form.paymentMethod" placeholder="请选择结算方式">
            <el-option v-for="item in paymentMethodOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
      </el-col>
    </el-row>
    <el-form-item v-if="form.paymentMethod === 'BANK'" label="付款账户" prop="businessBankAccountId">
      <BusinessBankAccountSelect v-model="form.businessBankAccountId" />
      <div class="form-help">必须选择银行实际扣款账户；该账户同时决定自动凭证的银行存款末级科目。</div>
    </el-form-item>
    <el-alert
      v-if="accountPaymentMethod"
      class="allocation-alert"
      type="warning"
      :closable="false"
      title="使用预付款或返利金余额时，只能核销一张单据，且分配金额必须等于本次金额。"
    />
    <SettlementAllocationEditor
      v-model="allocations"
      :candidates="allocationCandidates"
      :total-amount="numericAmount"
      direction-label="付款"
      :empty-text="form.supplierId ? '该供应商当前没有可核销单据' : '请先选择供应商，再进行核销分配'"
    />
    <el-form-item label="备注">
      <el-input v-model="form.memo" />
    </el-form-item>
    <div class="form-actions form-actions-right">
      <el-button v-if="showCancel" @click="emit('cancel')">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">{{ submitLabel }}</el-button>
    </div>
  </el-form>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { api, type PayableRow } from '@/api/client'
import { currentDateText } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import BusinessBankAccountSelect from '@/components/BusinessBankAccountSelect.vue'
import SettlementAllocationEditor, {
  type SettlementAllocationCandidate,
  type SettlementAllocationValue,
} from '@/components/SettlementAllocationEditor.vue'
import { settlementMethodOptions, type SettlementMethod } from '@/utils/settlementMethods'
import { submitSupplierPaymentException } from '@/utils/riskException'
import { useAppStore } from '@/stores/app'

type SupplierPaymentInitial = {
  supplierId?: number
  sourceType?: PayableRow['sourceType']
  sourceId?: number
  amount?: number
  memo?: string
}

const props = withDefaults(
  defineProps<{
    initial?: SupplierPaymentInitial
    compact?: boolean
    lockSource?: boolean
    showCancel?: boolean
    submitLabel?: string
  }>(),
  {
    compact: false,
    lockSource: false,
    showCancel: false,
    submitLabel: '保存付款',
  },
)

const emit = defineEmits<{
  (e: 'success'): void
  (e: 'cancel'): void
}>()

const queryClient = useQueryClient()
const appStore = useAppStore()
const today = currentDateText()
const formRef = ref<FormInstance>()
const allocations = ref<SettlementAllocationValue[]>([])
const initializedSource = ref('')
const form = reactive({
  supplierId: undefined as number | undefined,
  purchaseOrderId: undefined as number | undefined,
  inboundId: undefined as number | undefined,
  amount: '' as number | string,
  paymentMethod: '' as SettlementMethod,
  businessBankAccountId: undefined as number | undefined,
  paidDate: today,
  memo: '',
})

const rules: FormRules = {
  supplierId: [{ required: true, message: '请选择供应商', trigger: 'change' }],
  amount: [{
    validator: (_rule, value, callback) => {
      const amount = Number(value)
      if (!Number.isFinite(amount) || amount <= 0) return callback(new Error('请填写大于0的付款金额'))
      if (allocatedAmount.value - amount > 0.000001) return callback(new Error('核销分配合计不能超过本次付款金额'))
      if (accountPaymentMethod.value && (allocations.value.length !== 1 || Math.abs(allocatedAmount.value - amount) > 0.000001)) {
        return callback(new Error('使用预付款或返利金余额时，必须选择一张单据并全额分配'))
      }
      callback()
    },
    trigger: ['blur', 'change'],
  }],
  paidDate: [{ required: true, message: '请选择付款日期', trigger: 'change' }],
  paymentMethod: [{ required: true, message: '请选择结算方式', trigger: 'change' }],
  businessBankAccountId: [{
    validator: (_rule, value, callback) => form.paymentMethod !== 'BANK' || value
      ? callback()
      : callback(new Error('请选择实际付款的企业银行账户')),
    trigger: 'change',
  }],
}

const layout = computed(() =>
  props.compact
    ? { partner: 12, amount: 8, date: 8, method: 8 }
    : { partner: 7, amount: 5, date: 5, method: 7 },
)
const { data: payables } = useQuery({
  queryKey: computed(() => ['payables', form.supplierId ?? 'all']),
  queryFn: () => api.payables(form.supplierId ? { supplierId: form.supplierId } : undefined),
})
const { data: riskPolicy } = useQuery({ queryKey: ['risk-exception-policy'], queryFn: api.riskExceptionPolicy })
const paymentMethodOptions = computed(() => settlementMethodOptions(allocations.value.length > 0))
const candidatePayables = computed(() =>
  (payables.value ?? []).filter((item) => {
    if (!form.supplierId) return false
    const isCurrentTarget = item.sourceType === props.initial?.sourceType && item.sourceId === props.initial?.sourceId
    return (item.unpaidAmount > 0 || isCurrentTarget) && item.supplierId === form.supplierId
  }),
)
const allocationCandidates = computed<SettlementAllocationCandidate[]>(() => candidatePayables.value
  .filter((item) => !props.lockSource || (item.sourceType === props.initial?.sourceType && item.sourceId === props.initial?.sourceId))
  .map((item) => ({
    targetType: item.sourceType,
    targetId: item.sourceId,
    targetNo: item.sourceNo,
    businessDate: item.businessDate,
    unpaidAmount: Number(item.unpaidAmount),
  })))
const numericAmount = computed(() => Number(form.amount || 0))
const allocatedAmount = computed(() => allocations.value.reduce((sum, item) => sum + Number(item.amount || 0), 0))
const accountPaymentMethod = computed(() => ['ADVANCE', 'REWARD'].includes(form.paymentMethod))

const createMutation = useMutation({
  mutationFn: api.createSupplierPayment,
  onSuccess: async () => {
    ElMessage.success(allocatedAmount.value > 0 ? '供应商付款已登记，核销分配已保存' : '供应商付款已登记，余额暂未分配')
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['supplier-payments'] }),
      queryClient.invalidateQueries({ queryKey: ['payables'] }),
      queryClient.invalidateQueries({ queryKey: ['purchase-orders'] }),
      queryClient.invalidateQueries({ queryKey: ['inbounds'] }),
      queryClient.invalidateQueries({ queryKey: ['partner-accounts'] }),
      queryClient.invalidateQueries({ queryKey: ['partner-account-logs'] }),
      queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
    ])
    emit('success')
    resetForm()
  },
  onError: (error) => ElMessage.error(error instanceof Error ? error.message : '付款保存失败，请稍后重试'),
})
const saving = computed(() => createMutation.isPending.value)

watch(
  () => props.initial,
  () => {
    applyInitial()
  },
  { deep: true, immediate: true },
)

watch(payables, () => void applyInitialSource(), { immediate: true })
watch(allocations, (value) => {
  if (value.length !== 1 && accountPaymentMethod.value) form.paymentMethod = ''
}, { deep: true })
watch(() => form.paymentMethod, (value) => {
  if (value !== 'BANK') form.businessBankAccountId = undefined
})

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  const amount = Number(form.amount)
  const payload = accountPaymentMethod.value ? (() => {
    const target = allocations.value[0]
    return {
      ...form,
      amount,
      purchaseOrderId: target.targetType === 'PURCHASE_ORDER' ? target.targetId : undefined,
      inboundId: target.targetType === 'INBOUND' ? target.targetId : undefined,
    }
  })() : { ...form, purchaseOrderId: undefined, inboundId: undefined, amount, allocations: allocations.value }
  if (amount >= Number(riskPolicy.value?.largePaymentThreshold || Number.POSITIVE_INFINITY)) {
    if (!appStore.hasPermission('risk.exception.approve')) {
      await submitSupplierPaymentException(payload, amount)
      return
    }
    const authorization = await ElMessageBox.prompt(
      `本次付款达到大额付款阈值 ${Number(riskPolicy.value?.largePaymentThreshold || 0).toFixed(2)} 元。授权只对当前供应商、金额、账户、日期和核销分配有效。`,
      '负责人确认大额付款',
      { type: 'warning', inputType: 'textarea', confirmButtonText: '授权并付款', inputValidator: (value) => String(value || '').trim().length >= 8 || '授权原因至少8个字' },
    ).catch(() => null)
    if (!authorization) return
    createMutation.mutate({ ...payload, exceptionReason: authorization.value.trim() })
    return
  }
  createMutation.mutate(payload)
}

function applyInitial() {
  form.supplierId = props.initial?.supplierId
  form.purchaseOrderId = undefined
  form.inboundId = undefined
  form.amount = props.initial?.amount ?? ''
  form.memo = props.initial?.memo ?? ''
  allocations.value = []
  initializedSource.value = ''
  void applyInitialSource()
}

function applyInitialSource() {
  const sourceType = props.initial?.sourceType
  const id = props.initial?.sourceId
  const key = sourceType && id ? `${sourceType}:${id}` : ''
  if (!key || initializedSource.value === key) return
  const payable = (payables.value ?? []).find((item) => item.sourceType === sourceType && item.sourceId === id)
  if (!payable) return
  form.amount = Number(payable.unpaidAmount)
  form.supplierId = payable.supplierId
  allocations.value = [{ targetType: payable.sourceType, targetId: payable.sourceId, amount: Number(payable.unpaidAmount) }]
  initializedSource.value = key
}

function handleSupplierChange() {
  allocations.value = []
}

function resetForm() {
  form.supplierId = undefined
  form.purchaseOrderId = undefined
  form.inboundId = undefined
  form.amount = ''
  form.paymentMethod = ''
  form.businessBankAccountId = undefined
  form.paidDate = today
  form.memo = ''
  allocations.value = []
  initializedSource.value = ''
}
</script>

<style scoped>
.allocation-alert { margin-bottom: 12px; }
</style>
