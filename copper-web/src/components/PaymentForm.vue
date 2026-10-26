<template>
  <el-form ref="formRef" class="entry-form" :class="{ 'is-mobile-fast': mobile }" :model="form" :rules="rules" :label-width="mobile ? '78px' : '86px'">
    <el-row :gutter="14">
      <el-col :span="layout.partner">
        <el-form-item label="客户" prop="customerId">
          <PartnerRemoteSelect v-model="form.customerId" type="CUSTOMER" :disabled="lockSource" @change="handleCustomerChange" />
        </el-form-item>
      </el-col>
      <el-col :span="layout.amount">
        <el-form-item label="金额" prop="amount">
          <el-input v-model="form.amount" inputmode="decimal" placeholder="请输入收款金额">
          </el-input>
        </el-form-item>
      </el-col>
      <el-col :span="layout.date">
        <el-form-item label="日期" prop="receivedDate">
          <el-date-picker v-model="form.receivedDate" value-format="YYYY-MM-DD" />
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
    <el-form-item v-if="form.paymentMethod === 'BANK'" label="收款账户" prop="businessBankAccountId">
      <BusinessBankAccountSelect v-model="form.businessBankAccountId" />
      <div class="form-help">必须选择银行实际入账账户；该账户同时决定自动凭证的银行存款末级科目。</div>
    </el-form-item>
    <el-alert
      v-if="accountPaymentMethod"
      class="allocation-alert"
      type="warning"
      :closable="false"
      title="使用预收款或奖励金余额时，只能核销一张单据，且分配金额必须等于本次金额。"
    />
    <SettlementAllocationEditor
      v-model="allocations"
      :candidates="allocationCandidates"
      :total-amount="numericAmount"
      direction-label="收款"
      :mobile="mobile"
      :disabled="disabled"
      :empty-text="form.customerId ? '该客户当前没有可核销单据' : '请先选择客户，再进行核销分配'"
    />
    <el-form-item label="备注">
      <el-input v-model="form.memo" />
    </el-form-item>
    <div class="form-actions form-actions-right">
      <el-button v-if="showCancel" @click="emit('cancel')">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="disabled" @click="submit">{{ submitLabel }}</el-button>
    </div>
  </el-form>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { api, type ReceivableRow } from '@/api/client'
import { currentDateText } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import BusinessBankAccountSelect from '@/components/BusinessBankAccountSelect.vue'
import SettlementAllocationEditor, {
  type SettlementAllocationCandidate,
  type SettlementAllocationValue,
} from '@/components/SettlementAllocationEditor.vue'
import { settlementMethodOptions, type SettlementMethod } from '@/utils/settlementMethods'

type PaymentInitial = {
  customerId?: number
  sourceType?: ReceivableRow['sourceType']
  sourceId?: number
  amount?: number
  memo?: string
}

const props = withDefaults(
  defineProps<{
    initial?: PaymentInitial
    compact?: boolean
    mobile?: boolean
    disabled?: boolean
    lockSource?: boolean
    showCancel?: boolean
    submitLabel?: string
    balanceAmount?: number
    prefillSourceAmount?: boolean
  }>(),
  {
    compact: false,
    mobile: false,
    disabled: false,
    lockSource: false,
    showCancel: false,
    submitLabel: '保存回款',
    balanceAmount: undefined,
    prefillSourceAmount: true,
  },
)

const emit = defineEmits<{
  (e: 'success'): void
  (e: 'cancel'): void
}>()

const queryClient = useQueryClient()
const today = currentDateText()
const formRef = ref<FormInstance>()
const allocations = ref<SettlementAllocationValue[]>([])
const initializedSource = ref('')
const form = reactive({
  customerId: undefined as number | undefined,
  salesOrderId: undefined as number | undefined,
  outboundId: undefined as number | undefined,
  amount: '' as number | string,
  paymentMethod: '' as SettlementMethod,
  businessBankAccountId: undefined as number | undefined,
  receivedDate: today,
  memo: '',
})

const rules: FormRules = {
  customerId: [{ required: true, message: '请选择客户', trigger: 'change' }],
  amount: [{
    validator: (_rule, value, callback) => {
      const amount = Number(value)
      if (!Number.isFinite(amount) || amount <= 0) {
        callback(new Error('请填写大于0的回款金额'))
        return
      }
      if (allocatedAmount.value - amount > 0.000001) {
        callback(new Error('核销分配合计不能超过本次回款金额'))
        return
      }
      if (accountPaymentMethod.value && (allocations.value.length !== 1 || Math.abs(allocatedAmount.value - amount) > 0.000001)) {
        callback(new Error('使用预收款或奖励金余额时，必须选择一张单据并全额分配'))
        return
      }
      callback()
    },
    trigger: ['blur', 'change'],
  }],
  receivedDate: [{ required: true, message: '请选择回款日期', trigger: 'change' }],
  paymentMethod: [{ required: true, message: '请选择结算方式', trigger: 'change' }],
  businessBankAccountId: [{
    validator: (_rule, value, callback) => form.paymentMethod !== 'BANK' || value
      ? callback()
      : callback(new Error('请选择实际收款的企业银行账户')),
    trigger: 'change',
  }],
}

const layout = computed(() =>
  props.mobile
    ? { partner: 24, amount: 24, date: 24, method: 24 }
    : props.compact
    ? { partner: 12, amount: 8, date: 8, method: 8 }
    : { partner: 7, amount: 5, date: 5, method: 7 },
)
const { data: receivables } = useQuery({
  queryKey: computed(() => ['receivables', form.customerId ?? 'all']),
  queryFn: () => api.receivables(form.customerId ? { customerId: form.customerId } : undefined),
})
const candidateReceivables = computed(() =>
  (receivables.value ?? []).filter((item) => {
    if (!form.customerId) return false
    const isCurrentTarget = item.sourceType === props.initial?.sourceType && item.sourceId === props.initial?.sourceId
    return (item.unpaidAmount > 0 || isCurrentTarget) && item.customerId === form.customerId
  }),
)
const allocationCandidates = computed<SettlementAllocationCandidate[]>(() => candidateReceivables.value
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
const paymentMethodOptions = computed(() => settlementMethodOptions(allocations.value.length > 0))

watch(
  () => props.initial,
  () => {
    applyInitial()
  },
  { deep: true, immediate: true },
)

watch(receivables, () => void applyInitialSource(), { immediate: true })
watch(allocations, (value) => {
  if (value.length !== 1 && accountPaymentMethod.value) form.paymentMethod = ''
}, { deep: true })
watch(() => form.paymentMethod, (value) => {
  if (value !== 'BANK') form.businessBankAccountId = undefined
})

const createMutation = useMutation({
  mutationFn: api.createPayment,
  onSuccess: async () => {
    ElMessage.success(allocatedAmount.value > 0 ? '回款已登记，核销分配已保存' : '回款已登记，余额暂未分配')
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['payments'] }),
      queryClient.invalidateQueries({ queryKey: ['receivables'] }),
      queryClient.invalidateQueries({ queryKey: ['sales-orders'] }),
      queryClient.invalidateQueries({ queryKey: ['outbounds'] }),
      queryClient.invalidateQueries({ queryKey: ['partner-accounts'] }),
      queryClient.invalidateQueries({ queryKey: ['partner-account-logs'] }),
      queryClient.invalidateQueries({ queryKey: ['dashboard'] }),
    ])
    emit('success')
    resetForm()
  },
  onError: (error) => ElMessage.error(error instanceof Error ? error.message : '回款保存失败，请稍后重试'),
})
const saving = computed(() => createMutation.isPending.value)

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  const amount = Number(form.amount)
  if (accountPaymentMethod.value) {
    const target = allocations.value[0]
    createMutation.mutate({
      ...form,
      amount,
      salesOrderId: target.targetType === 'SALES_ORDER' ? target.targetId : undefined,
      outboundId: target.targetType === 'OUTBOUND' ? target.targetId : undefined,
    })
    return
  }
  createMutation.mutate({ ...form, salesOrderId: undefined, outboundId: undefined, amount, allocations: allocations.value })
}

function applyInitial() {
  form.customerId = props.initial?.customerId
  form.salesOrderId = undefined
  form.outboundId = undefined
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
  const receivable = (receivables.value ?? []).find((item) => item.sourceType === sourceType && item.sourceId === id)
  if (!receivable) return
  if (props.prefillSourceAmount) form.amount = Number(receivable.unpaidAmount)
  form.customerId = receivable.customerId
  allocations.value = [{ targetType: receivable.sourceType, targetId: receivable.sourceId, amount: Number(receivable.unpaidAmount) }]
  initializedSource.value = key
}

function handleCustomerChange() {
  allocations.value = []
}

function resetForm() {
  form.customerId = undefined
  form.salesOrderId = undefined
  form.outboundId = undefined
  form.amount = ''
  form.paymentMethod = ''
  form.businessBankAccountId = undefined
  form.receivedDate = today
  form.memo = ''
  allocations.value = []
  initializedSource.value = ''
}
</script>

<style scoped>
.allocation-alert { margin-bottom: 12px; }
.is-mobile-fast :deep(.el-date-editor),
.is-mobile-fast :deep(.el-select),
.is-mobile-fast :deep(.business-bank-account-select) { width: 100%; }
.is-mobile-fast :deep(.el-form-item) { margin-bottom: 18px; }
.is-mobile-fast .form-actions .el-button { min-height: 44px; width: 100%; }
</style>
