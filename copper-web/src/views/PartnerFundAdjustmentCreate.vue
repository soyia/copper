<template>
  <div class="page adjustment-create-page">
    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">新建往来余额调整单</div>
          <div class="muted">先保存草稿，再由有权限的负责人确认。确认前余额、业务过账和会计凭证都不会变化。</div>
        </div>
        <el-button @click="router.push('/partner-fund-adjustments')">返回列表</el-button>
      </div>
    </div>

    <el-alert :title="guidance.title" :description="guidance.description" :type="guidance.type" :closable="false" show-icon />

    <div class="form-layout">
      <div class="panel">
        <div class="section-title">业务事实</div>
        <el-form ref="formRef" :model="form" :rules="rules" label-width="112px" class="adjustment-form">
          <el-form-item label="业务类型" prop="adjustmentType">
            <el-select v-model="form.adjustmentType" placeholder="请选择真实业务原因" @change="applyTypeDefaults">
              <el-option label="客户奖励确认" value="CUSTOMER_REWARD_GRANT" />
              <el-option label="供应商返利确认" value="SUPPLIER_REBATE_GRANT" />
              <el-option label="期初余额" value="OPENING_BALANCE" />
              <el-option label="余额差错调整" value="BALANCE_CORRECTION" />
            </el-select>
          </el-form-item>
          <el-form-item label="余额账户" prop="accountType">
            <el-select v-model="form.accountType" placeholder="请选择账户" :disabled="fixedAccount" @change="clearPartner">
              <el-option v-for="item in availableAccounts" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="往来单位" prop="partnerId">
            <PartnerRemoteSelect v-model="form.partnerId" :type="partnerRole" :disabled="!form.accountType" :placeholder="form.accountType ? `选择${partnerRole === 'CUSTOMER' ? '客户' : '供应商'}` : '请先选择余额账户'" />
          </el-form-item>
          <el-form-item label="调整方向" prop="direction">
            <el-segmented v-model="form.direction" :options="directionOptions" :disabled="fixedDirection" />
          </el-form-item>
          <el-form-item label="调整金额" prop="amount">
            <el-input v-model="form.amount" inputmode="decimal" placeholder="0.00"><template #prepend>¥</template></el-input>
          </el-form-item>
          <el-form-item label="业务日期" prop="businessDate">
            <el-date-picker v-model="form.businessDate" value-format="YYYY-MM-DD" :clearable="false" />
          </el-form-item>
        </el-form>
      </div>

      <div class="panel">
        <div class="section-title">依据与财税边界</div>
        <el-form :model="form" label-width="112px" class="adjustment-form">
          <el-form-item label="业务依据号" required>
            <el-input v-model="form.basisNo" maxlength="128" show-word-limit placeholder="合同、返利对账单、原错误单号或迁移批次号" />
          </el-form-item>
          <el-form-item label="税票处理" required>
            <el-select v-model="form.taxTreatment" :disabled="!rewardOrRebate">
              <el-option label="不涉及单独税票处理" value="NOT_APPLICABLE" />
              <el-option label="待会计 / 税务人员复核" value="PENDING_PROFESSIONAL_REVIEW" />
              <el-option label="发票已在税票模块单独处理" value="SEPARATE_INVOICE_HANDLING" />
            </el-select>
          </el-form-item>
          <el-form-item label="具体原因" required>
            <el-input v-model="form.memo" type="textarea" :rows="5" maxlength="500" show-word-limit placeholder="写清业务背景、计算口径、发现差错的原因和复核依据；不要只写“调账”" />
          </el-form-item>
        </el-form>
        <div class="boundary-box">
          <div><strong>确认后会发生</strong><span>{{ directionText }} {{ accountText }} {{ amountText }}，形成业务过账和草稿凭证。</span></div>
          <div><strong>确认后不会发生</strong><span>不会生成现金或银行流水，不会修改订单、应收应付或发票，不会自动完成税务处理。</span></div>
        </div>
      </div>
    </div>

    <div class="sticky-actions">
      <el-button @click="router.push('/partner-fund-adjustments')">取消</el-button>
      <el-button type="primary" :loading="saving" @click="saveDraft">保存草稿</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { api, type PartnerFundAccountType, type PartnerFundAdjustmentCreate, type PartnerFundAdjustmentType, type PartnerFundTaxTreatment } from '@/api/client'
import { currentDateText, money } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'

const router = useRouter()
const formRef = ref<FormInstance>()
const saving = ref(false)
const form = reactive({
  adjustmentType: '' as PartnerFundAdjustmentType | '',
  accountType: '' as PartnerFundAccountType | '',
  partnerId: undefined as number | undefined,
  direction: 'INCREASE' as 'INCREASE' | 'DECREASE',
  amount: '', businessDate: currentDateText(), basisNo: '',
  taxTreatment: 'NOT_APPLICABLE' as PartnerFundTaxTreatment,
  memo: '',
})
const rules: FormRules = {
  adjustmentType: [{ required: true, message: '请选择业务类型', trigger: 'change' }],
  accountType: [{ required: true, message: '请选择余额账户', trigger: 'change' }],
  partnerId: [{ required: true, message: '请选择往来单位', trigger: 'change' }],
  direction: [{ required: true, message: '请选择调整方向', trigger: 'change' }],
  amount: [{ validator: (_rule, value, callback) => Number.isFinite(Number(value)) && Number(value) > 0 ? callback() : callback(new Error('金额必须大于0')), trigger: 'blur' }],
  businessDate: [{ required: true, message: '请选择业务日期', trigger: 'change' }],
}
const allAccounts = [
  { label: '客户预收款', value: 'CUSTOMER_ADVANCE' as const }, { label: '客户奖励金', value: 'CUSTOMER_REWARD' as const },
  { label: '供应商预付款', value: 'SUPPLIER_PREPAY' as const }, { label: '供应商返利金', value: 'SUPPLIER_REWARD' as const },
]
const directionOptions = [{ label: '增加余额', value: 'INCREASE' }, { label: '减少余额', value: 'DECREASE' }]
const fixedAccount = computed(() => ['CUSTOMER_REWARD_GRANT', 'SUPPLIER_REBATE_GRANT'].includes(form.adjustmentType))
const fixedDirection = computed(() => form.adjustmentType !== 'BALANCE_CORRECTION')
const rewardOrRebate = computed(() => ['CUSTOMER_REWARD_GRANT', 'SUPPLIER_REBATE_GRANT'].includes(form.adjustmentType))
const availableAccounts = computed(() => form.adjustmentType === 'OPENING_BALANCE'
  ? allAccounts.filter((item) => ['CUSTOMER_ADVANCE', 'SUPPLIER_PREPAY'].includes(item.value)) : allAccounts)
const partnerRole = computed<'CUSTOMER' | 'SUPPLIER'>(() => form.accountType.startsWith('SUPPLIER') ? 'SUPPLIER' : 'CUSTOMER')
const accountText = computed(() => allAccounts.find((item) => item.value === form.accountType)?.label || '所选账户')
const directionText = computed(() => form.direction === 'INCREASE' ? '增加' : '减少')
const amountText = computed(() => Number(form.amount) > 0 ? money(Number(form.amount)) : '待填写金额')
const guidance = computed(() => {
  if (form.adjustmentType === 'OPENING_BALANCE') return { type: 'warning' as const, title: '期初只用于首次上线的空白账户', description: '账户已有余额或任何流水后，系统会拒绝确认期初。录错请冲正原期初，不要重复补录。' }
  if (form.adjustmentType === 'CUSTOMER_REWARD_GRANT') return { type: 'info' as const, title: '客户奖励是未来可抵扣销售款的义务', description: '必须有奖励协议或计算清单；涉及销售折让、红字发票或已申报税额时，仍须在税票模块单独处理并由专业人员复核。' }
  if (form.adjustmentType === 'SUPPLIER_REBATE_GRANT') return { type: 'info' as const, title: '供应商返利是可抵扣后续应付款的资产', description: '必须有供应商确认的返利对账依据；发票和进项税处理不由本调整单代替。' }
  return { type: 'warning' as const, title: '差错调整只修复余额事实', description: '先确认原业务单无法直接冲正或本次确属迁移差错；调整不会改写原单、银行流水、发票或已记账凭证。' }
})

function applyTypeDefaults() {
  form.partnerId = undefined
  form.direction = 'INCREASE'
  if (form.adjustmentType === 'CUSTOMER_REWARD_GRANT') form.accountType = 'CUSTOMER_REWARD'
  else if (form.adjustmentType === 'SUPPLIER_REBATE_GRANT') form.accountType = 'SUPPLIER_REWARD'
  else form.accountType = ''
  form.taxTreatment = rewardOrRebate.value ? 'PENDING_PROFESSIONAL_REVIEW' : 'NOT_APPLICABLE'
}
function clearPartner() { form.partnerId = undefined }
async function saveDraft() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  if (!form.basisNo.trim()) { ElMessage.warning('请填写合同、对账单、原单或迁移批次号'); return }
  if (form.memo.trim().length < 4) { ElMessage.warning('请具体说明调整原因，至少4个字'); return }
  if (rewardOrRebate.value && form.taxTreatment === 'NOT_APPLICABLE') { ElMessage.warning('奖励或返利必须选择待专业复核或发票单独处理'); return }
  saving.value = true
  try {
    const result = await api.createPartnerFundAdjustment({
      partnerId: form.partnerId!, accountType: form.accountType as PartnerFundAccountType,
      adjustmentType: form.adjustmentType as PartnerFundAdjustmentType, direction: form.direction,
      amount: Number(form.amount), businessDate: form.businessDate, basisNo: form.basisNo.trim(),
      taxTreatment: form.taxTreatment, memo: form.memo.trim(),
    } satisfies PartnerFundAdjustmentCreate)
    ElMessage.success('余额调整草稿已保存，尚未影响账户余额')
    await router.replace(`/partner-fund-adjustments/${result.id}`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '余额调整草稿保存失败')
  } finally { saving.value = false }
}
</script>

<style scoped>
.adjustment-create-page { padding-bottom: 72px; }
.form-layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 14px; margin-top: 14px; }
.section-title { margin-bottom: 18px; font-size: 16px; font-weight: 700; }
.adjustment-form :deep(.el-select), .adjustment-form :deep(.el-date-editor), .adjustment-form :deep(.el-segmented) { width: 100%; }
.boundary-box { display: grid; gap: 12px; margin: 8px 18px 18px; padding: 14px 16px; border: 1px solid #dfe6ec; border-radius: 9px; background: #f8fafb; }
.boundary-box div { display: grid; grid-template-columns: 118px 1fr; gap: 10px; line-height: 1.55; }
.boundary-box span { color: #64727d; }
.sticky-actions { position: sticky; z-index: 4; bottom: 0; display: flex; justify-content: flex-end; gap: 10px; margin-top: 14px; padding: 13px 18px; border: 1px solid #dfe5ea; border-radius: 10px; background: rgba(255,255,255,.96); box-shadow: 0 -5px 18px rgba(30,45,58,.07); backdrop-filter: blur(8px); }
@media (max-width: 980px) { .form-layout { grid-template-columns: 1fr; } }
</style>
