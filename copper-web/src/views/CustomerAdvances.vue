<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">客户预收款</div>
          <div class="muted">未核销资金先进入客户预收账户；订单占用后不可重复使用，确认销售时自动转为回款核销。</div>
        </div>
        <el-button type="primary" @click="openReceive()">登记预收款</el-button>
      </div>
      <div class="list-tools">
        <el-input v-model="keyword" clearable placeholder="客户名称" />
        <PartnerRemoteSelect v-model="selectedCustomerId" type="CUSTOMER" clearable placeholder="客户" />
        <div class="list-tools-actions"><el-button @click="resetFilters">重置</el-button></div>
      </div>
      <el-table :data="filteredRows" class="data-table" size="small" border show-overflow-tooltip max-height="360">
        <el-table-column prop="customerName" label="客户" min-width="190" fixed="left" />
        <el-table-column label="预收余额" width="150" align="right"><template #default="{ row }">{{ money(row.balance) }}</template></el-table-column>
        <el-table-column label="订单已占用" width="150" align="right"><template #default="{ row }">{{ money(row.reservedAmount) }}</template></el-table-column>
        <el-table-column label="可用余额" width="150" align="right"><template #default="{ row }"><strong>{{ money(row.availableAmount) }}</strong></template></el-table-column>
        <el-table-column prop="updatedAt" label="最近变动" width="170" :formatter="formatDateTimeColumn" />
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" @click="openReceive(row)">继续收款</el-button>
            <el-button text type="danger" :disabled="Number(row.availableAmount) <= 0" @click="openRefund(row)">退款</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-header"><div class="panel-title">预收款流水</div></div>
      <el-table :data="advanceLogs" class="data-table" size="small" border show-overflow-tooltip max-height="320">
        <el-table-column prop="createdAt" label="时间" width="170" :formatter="formatDateTimeColumn" />
        <el-table-column prop="partnerName" label="客户" min-width="160" />
        <el-table-column label="来源单据" min-width="200"><template #default="{ row }"><router-link v-if="businessRoute(row)" class="table-link" :to="businessRoute(row)!">{{ row.businessNo }}</router-link><span v-else>{{ row.businessNo || '-' }}</span></template></el-table-column>
        <el-table-column prop="businessSummary" label="说明" min-width="220" />
        <el-table-column label="变动" width="130" align="right"><template #default="{ row }">{{ signedMoney(row.amount) }}</template></el-table-column>
        <el-table-column label="变动前" width="130" align="right"><template #default="{ row }">{{ money(row.amountBefore) }}</template></el-table-column>
        <el-table-column label="变动后" width="130" align="right"><template #default="{ row }">{{ money(row.amountAfter) }}</template></el-table-column>
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-header"><div class="panel-title">退款记录</div></div>
      <el-table :data="filteredRefunds" class="data-table" size="small" border show-overflow-tooltip max-height="260">
        <el-table-column label="退款单号" width="190"><template #default="{ row }"><el-button link type="primary" @click="openRefundDetail(row)">{{ row.refundNo }}</el-button></template></el-table-column>
        <el-table-column prop="refundedDate" label="日期" width="110" :formatter="formatDateColumn" />
        <el-table-column prop="customerName" label="客户" min-width="160" />
        <el-table-column label="金额" width="130" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
        <el-table-column label="方式" width="110"><template #default="{ row }">{{ row.refundMethod === 'CASH' ? '现金' : '银行转账' }}</template></el-table-column>
        <el-table-column prop="businessBankAccountName" label="企业银行账户" min-width="150"><template #default="{ row }">{{ row.businessBankAccountName || '-' }}</template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">{{ row.status === 'ACTIVE' ? '有效' : '已冲正' }}</el-tag></template></el-table-column>
        <el-table-column prop="memo" label="备注" min-width="200" />
        <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button v-if="row.status === 'ACTIVE'" link type="danger" @click="reverseRefund(row)">冲正</el-button></template></el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="receiveVisible" title="登记客户预收款" width="520px">
      <el-form label-width="92px">
        <el-form-item label="客户" required><PartnerRemoteSelect v-model="receiveForm.customerId" type="CUSTOMER" /></el-form-item>
        <el-form-item label="到账金额" required><el-input v-model="receiveForm.amount" inputmode="decimal" placeholder="请输入到账金额" /></el-form-item>
        <el-form-item label="结算方式" required><el-select v-model="receiveForm.paymentMethod" placeholder="请选择结算方式"><el-option v-for="item in cashSettlementMethodOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item v-if="receiveForm.paymentMethod === 'BANK'" label="收款账户" required><BusinessBankAccountSelect v-model="receiveForm.businessBankAccountId" /></el-form-item>
        <el-form-item label="到账日期" required><el-date-picker v-model="receiveForm.receivedDate" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="receiveForm.memo" placeholder="合同、客户汇款说明等" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="receiveVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveReceive">保存预收款</el-button></template>
    </el-dialog>

    <el-dialog v-model="refundVisible" title="客户预收款退款" width="520px">
      <el-alert :title="`可退款余额 ${money(refundTarget?.availableAmount || 0)}`" type="warning" :closable="false" style="margin-bottom: 14px" />
      <el-form label-width="92px">
        <el-form-item label="客户">{{ refundTarget?.customerName }}</el-form-item>
        <el-form-item label="退款金额"><el-input v-model="refundForm.amount" inputmode="decimal"><template #append><el-button @click="fillRefundBalance">全部余额</el-button></template></el-input></el-form-item>
        <el-form-item label="退款方式" required><el-select v-model="refundForm.refundMethod" placeholder="请选择退款方式"><el-option v-for="item in cashSettlementMethodOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item v-if="refundForm.refundMethod === 'BANK'" label="退款账户" required><BusinessBankAccountSelect v-model="refundForm.businessBankAccountId" /></el-form-item>
        <el-form-item label="退款日期"><el-date-picker v-model="refundForm.refundedDate" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="refundForm.memo" placeholder="退款原因、凭证号等" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="refundVisible = false">取消</el-button><el-button type="danger" :loading="refunding" @click="saveRefund">确认退款</el-button></template>
    </el-dialog>

    <RelatedDetailDrawer
      v-model="refundDetailVisible"
      :title="`预收款退款 ${activeRefund?.refundNo || ''}`"
      subtitle="客户预收账户退款记录"
      :trail="[{ label: '客户预收款' }, { label: activeRefund?.refundNo || '退款详情' }]"
      size="680px"
      @breadcrumb="refundDetailVisible = false"
    >
      <el-descriptions v-if="activeRefund" class="fixed-detail-descriptions" :column="2" label-width="140px" border>
        <el-descriptions-item label="退款单号"><span class="related-record-no">{{ activeRefund.refundNo }}</span></el-descriptions-item>
        <el-descriptions-item label="客户">{{ activeRefund.customerName }}</el-descriptions-item>
        <el-descriptions-item label="退款金额">{{ money(activeRefund.amount) }}</el-descriptions-item>
        <el-descriptions-item label="退款方式">{{ activeRefund.refundMethod === 'CASH' ? '现金' : '银行转账' }}</el-descriptions-item>
        <el-descriptions-item label="企业银行账户">{{ activeRefund.businessBankAccountName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ activeRefund.status === 'ACTIVE' ? '有效' : '已冲正' }}</el-descriptions-item>
        <el-descriptions-item label="退款日期">{{ formatDate(activeRefund.refundedDate) }}</el-descriptions-item>
        <el-descriptions-item label="登记时间" :span="2">{{ formatDateTime(activeRefund.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="备注" :span="2">{{ activeRefund.memo || '-' }}</el-descriptions-item>
        <el-descriptions-item v-if="activeRefund.status === 'REVERSED'" label="冲正单号">{{ activeRefund.reversalNo }}</el-descriptions-item>
        <el-descriptions-item v-if="activeRefund.status === 'REVERSED'" label="冲正时间">{{ formatDateTime(activeRefund.reversedAt) }}</el-descriptions-item>
        <el-descriptions-item v-if="activeRefund.status === 'REVERSED'" label="冲正原因" :span="2">{{ activeRefund.reversalReason }}</el-descriptions-item>
      </el-descriptions>
      <template #footer><el-button @click="refundDetailVisible = false">关闭</el-button></template>
    </RelatedDetailDrawer>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { api, type CustomerAdvanceRefundRow, type CustomerAdvanceRow, type PartnerAccountLogRow } from '@/api/client'
import { currentDateText, formatDate, formatDateColumn, formatDateTime, formatDateTimeColumn, money } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import BusinessBankAccountSelect from '@/components/BusinessBankAccountSelect.vue'
import RelatedDetailDrawer from '@/components/RelatedDetailDrawer.vue'
import { relatedDetailRoute } from '@/utils/navigation'
import { cashSettlementMethodOptions, type SettlementMethod } from '@/utils/settlementMethods'
import { resolveRiskException } from '@/utils/riskException'

const route = useRoute()
const queryClient = useQueryClient()
const keyword = ref('')
const selectedCustomerId = ref<number>()
const receiveVisible = ref(false)
const refundVisible = ref(false)
const saving = ref(false)
const refunding = ref(false)
const refundTarget = ref<CustomerAdvanceRow>()
const refundDetailVisible = ref(false)
const activeRefund = ref<CustomerAdvanceRefundRow>()
const receiveForm = reactive({ customerId: undefined as number | undefined, amount: '', paymentMethod: '' as SettlementMethod, businessBankAccountId: undefined as number | undefined, receivedDate: currentDateText(), memo: '' })
const refundForm = reactive({ amount: '', refundMethod: '' as SettlementMethod, businessBankAccountId: undefined as number | undefined, refundedDate: currentDateText(), memo: '' })

const { data: advances } = useQuery({ queryKey: ['customer-advances'], queryFn: api.customerAdvances })
const { data: logs } = useQuery({ queryKey: ['partner-account-logs'], queryFn: () => api.partnerAccountLogs() })
const { data: refunds } = useQuery({ queryKey: ['customer-advance-refunds'], queryFn: () => api.customerAdvanceRefunds() })
watch(() => receiveForm.paymentMethod, (value) => { if (value !== 'BANK') receiveForm.businessBankAccountId = undefined })
watch(() => refundForm.refundMethod, (value) => { if (value !== 'BANK') refundForm.businessBankAccountId = undefined })

const filteredRows = computed(() => (advances.value ?? []).filter((row) =>
  (!selectedCustomerId.value || row.customerId === selectedCustomerId.value)
  && (!keyword.value.trim() || row.customerName.toLowerCase().includes(keyword.value.trim().toLowerCase())),
))
const advanceLogs = computed(() => (logs.value ?? []).filter((row) => row.accountType === 'CUSTOMER_ADVANCE' && (!selectedCustomerId.value || row.partnerId === selectedCustomerId.value)))
const filteredRefunds = computed(() => (refunds.value ?? []).filter((row) => !selectedCustomerId.value || row.customerId === selectedCustomerId.value))

function businessRoute(row: PartnerAccountLogRow) {
  let path = ''
  if (row.businessType === 'SALES_ORDER') path = `/sales-orders/${row.businessId}`
  if (row.businessType === 'OUTBOUND') path = `/outbounds/${row.businessId}`
  if (row.businessType === 'PURCHASE_ORDER') path = `/purchase-orders/${row.businessId}`
  if (row.businessType === 'INBOUND') path = `/inbounds/${row.businessId}`
  if (row.businessType === 'PARTNER_FUND_ADJUSTMENT') path = `/partner-fund-adjustments/${row.businessId}`
  return path ? relatedDetailRoute(route, path) : undefined
}

function openRefundDetail(row: CustomerAdvanceRefundRow) {
  activeRefund.value = row
  refundDetailVisible.value = true
}

function resetFilters() { keyword.value = ''; selectedCustomerId.value = undefined }
function openReceive(row?: CustomerAdvanceRow) {
  receiveForm.customerId = row?.customerId
  receiveForm.amount = ''
  receiveForm.paymentMethod = ''
  receiveForm.businessBankAccountId = undefined
  receiveForm.receivedDate = currentDateText()
  receiveForm.memo = ''
  receiveVisible.value = true
}
function openRefund(row: CustomerAdvanceRow) {
  refundTarget.value = row
  refundForm.amount = ''
  refundForm.refundMethod = ''
  refundForm.businessBankAccountId = undefined
  refundForm.refundedDate = currentDateText()
  refundForm.memo = ''
  refundVisible.value = true
}
function fillRefundBalance() { refundForm.amount = Number(refundTarget.value?.availableAmount || 0).toFixed(2) }

async function refreshAll() {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['customer-advances'] }),
    queryClient.invalidateQueries({ queryKey: ['customer-advance-refunds'] }),
    queryClient.invalidateQueries({ queryKey: ['partner-accounts'] }),
    queryClient.invalidateQueries({ queryKey: ['partner-account-logs'] }),
    queryClient.invalidateQueries({ queryKey: ['payments'] }),
  ])
}
async function saveReceive() {
  const amount = Number(receiveForm.amount)
  if (!receiveForm.customerId) { ElMessage.warning('请选择客户'); return }
  if (!Number.isFinite(amount) || amount <= 0) { ElMessage.warning('请填写大于0的到账金额'); return }
  if (!receiveForm.paymentMethod) { ElMessage.warning('请选择结算方式'); return }
  if (receiveForm.paymentMethod === 'BANK' && !receiveForm.businessBankAccountId) { ElMessage.warning('请选择实际收款的企业银行账户'); return }
  saving.value = true
  try {
    await api.createPayment({ ...receiveForm, amount })
    ElMessage.success('客户预收款已登记')
    receiveVisible.value = false
    await refreshAll()
  } finally { saving.value = false }
}
async function saveRefund() {
  if (!refundTarget.value) return
  const amount = Number(refundForm.amount)
  if (!Number.isFinite(amount) || amount <= 0 || amount > Number(refundTarget.value.availableAmount)) { ElMessage.warning('退款金额必须大于0且不能超过可用余额'); return }
  if (!refundForm.refundMethod) { ElMessage.warning('请选择退款方式'); return }
  if (refundForm.refundMethod === 'BANK' && !refundForm.businessBankAccountId) { ElMessage.warning('请选择实际退款的企业银行账户'); return }
  const confirmed = await ElMessageBox.confirm('退款会实际扣减客户预收账户，订单已占用金额不会被退款。', '确认预收款退款', { type: 'warning' }).then(() => true).catch(() => false)
  if (!confirmed) return
  refunding.value = true
  try {
    await api.refundCustomerAdvance({ customerId: refundTarget.value.customerId, ...refundForm, amount })
    ElMessage.success('客户预收款退款已登记')
    refundVisible.value = false
    await refreshAll()
  } finally { refunding.value = false }
}
async function reverseRefund(row: CustomerAdvanceRefundRow) {
  const result = await ElMessageBox.prompt('请输入冲正原因。若该退款已进入银行对账，需先重新开放对账单并撤销匹配。', `冲正退款 ${row.refundNo}`, {
    inputValidator: (value) => value.trim().length >= 4 || '冲正原因至少4个字',
    confirmButtonText: '确认冲正',
    cancelButtonText: '取消',
    type: 'warning',
  }).catch(() => null)
  if (!result) return
  const authorization = await resolveRiskException('CUSTOMER_ADVANCE_REFUND', row.id, 'REVERSE', '冲正客户退款', result.value.trim())
  if (!authorization.allowed) return
  await api.reverseCustomerAdvanceRefund(row.id, { reason: result.value.trim(), businessDate: currentDateText(), approvalId: authorization.approvalId })
  ElMessage.success('退款已冲正，客户预收余额已经恢复')
  await refreshAll()
}
function signedMoney(value: number) { const amount = Number(value || 0); return `${amount >= 0 ? '+' : '-'}${money(Math.abs(amount))}` }
</script>
