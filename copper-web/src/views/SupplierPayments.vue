<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div class="panel-title">供应商付款记录</div>
        <el-button type="primary" @click="$router.push('/supplier-payments/new')">新增付款核销</el-button>
      </div>
      <div class="list-tools">
        <el-input v-model="filters.keyword" clearable placeholder="付款单号 / 供应商 / 采购单 / 入库单" />
        <PartnerRemoteSelect v-model="filters.supplierName" type="SUPPLIER" value-field="name" clearable placeholder="供应商" />
        <el-select v-model="filters.paymentMethod" clearable placeholder="方式">
          <el-option label="银行" value="BANK" />
          <el-option label="现金" value="CASH" />
          <el-option label="抵消" value="OFFSET" />
          <el-option label="预付款余额" value="ADVANCE" />
          <el-option label="返利金" value="REWARD" />
        </el-select>
        <el-date-picker v-model="filters.dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" />
        <div class="list-tools-actions">
          <el-button @click="resetFilters">重置</el-button>
        </div>
      </div>
      <el-table :data="filteredPayments" class="data-table" size="small" border show-overflow-tooltip max-height="360">
        <el-table-column label="付款单号" width="190" fixed="left"><template #default="{ row }"><el-button link type="primary" @click="openPayment(row)">{{ row.paymentNo }}</el-button></template></el-table-column>
        <el-table-column prop="paidDate" label="日期" width="110" :formatter="formatDateColumn" />
        <el-table-column prop="supplierName" label="供应商" min-width="150" />
        <el-table-column label="本次金额" width="130" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
        <el-table-column label="已分配" width="130" align="right"><template #default="{ row }"><span class="amount-success">{{ money(row.allocatedAmount) }}</span></template></el-table-column>
        <el-table-column label="未分配" width="130" align="right"><template #default="{ row }"><span :class="{ 'amount-warning': row.unallocatedAmount > 0 }">{{ money(row.unallocatedAmount) }}</span></template></el-table-column>
        <el-table-column label="分配单据" width="105" align="center"><template #default="{ row }"><el-button v-if="row.allocationCount" link type="primary" @click="openPayment(row)">{{ row.allocationCount }} 张</el-button><span v-else>-</span></template></el-table-column>
        <el-table-column label="方式" width="130"><template #default="{ row }">{{ paymentMethodName(row.paymentMethod) }}</template></el-table-column>
        <el-table-column label="账户" min-width="150"><template #default="{ row }">{{ row.paymentMethod === 'BANK' ? row.businessBankAccountName || '未指定' : accountTypeName(row.accountType) || paymentMethodName(row.paymentMethod) }}</template></el-table-column>
        <el-table-column label="状态" width="105"><template #default="{ row }">{{ verificationStatusName(row.verificationStatus) }}</template></el-table-column>
        <el-table-column v-if="appStore.hasPermission('finance.approve')" label="操作" width="90" fixed="right"><template #default="{ row }"><el-button v-if="row.verificationStatus !== 'REVERSED'" link type="danger" :loading="reversingId === row.id" @click="reverse(row)">冲正</el-button><span v-else class="muted">已冲正</span></template></el-table-column>
      </el-table>
    </div>
    <PaymentRecordDrawer v-model="paymentDrawerVisible" kind="PAYMENT" :record="activePayment" origin-label="供应商付款" />
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type SupplierPaymentRow } from '@/api/client'
import { accountTypeName, currentDateText, formatDateColumn, money, paymentMethodName } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import { matchesDateRange, matchesKeyword } from '@/utils/listFilters'
import PaymentRecordDrawer from '@/components/PaymentRecordDrawer.vue'
import { useAppStore } from '@/stores/app'
import { resolveRiskException } from '@/utils/riskException'

const appStore = useAppStore()
const queryClient = useQueryClient()
const reversingId = ref(0)
const filters = reactive({ keyword: '', supplierName: '', paymentMethod: '', dateRange: [] as string[] })
const paymentDrawerVisible = ref(false)
const activePayment = ref<SupplierPaymentRow>()

const { data: payments } = useQuery({ queryKey: ['supplier-payments'], queryFn: () => api.supplierPayments() })

const filteredPayments = computed(() =>
  (payments.value ?? []).filter(
    (item) =>
      (!filters.supplierName || item.supplierName === filters.supplierName) &&
      (!filters.paymentMethod || item.paymentMethod === filters.paymentMethod) &&
      matchesDateRange(item.paidDate, filters.dateRange) &&
      matchesKeyword(item, filters.keyword, ['paymentNo', 'supplierName', 'purchaseOrderNo', 'inboundNo', 'paymentMethod', 'verificationStatus']),
  ),
)

function resetFilters() {
  filters.keyword = ''
  filters.supplierName = ''
  filters.paymentMethod = ''
  filters.dateRange = []
}

function openPayment(row: SupplierPaymentRow) {
  activePayment.value = row
  paymentDrawerVisible.value = true
}

async function reverse(row: SupplierPaymentRow) {
  const result = await ElMessageBox.prompt('冲正会撤销整笔资金、全部有效核销分配及对应过账；若资金已进入银行对账或发票勾稽，系统会拒绝。请填写原因。', `冲正付款 ${row.paymentNo}`, { type: 'warning', confirmButtonText: '确认冲正', cancelButtonText: '取消', inputPlaceholder: '请输入冲正原因', inputValidator: (value) => String(value || '').trim() || '请填写冲正原因' }).catch(() => null)
  if (!result) return
  const authorization = await resolveRiskException('SUPPLIER_PAYMENT', row.id, 'REVERSE', '冲正供应商付款', result.value.trim())
  if (!authorization.allowed) return
  reversingId.value = row.id
  try {
    await api.reverseSupplierPayment(row.id, { reason: result.value.trim(), businessDate: currentDateText(), approvalId: authorization.approvalId })
    ElMessage.success('供应商付款已冲正，原记录和反向过账均已保留')
    await Promise.all([queryClient.invalidateQueries({ queryKey: ['supplier-payments'] }), queryClient.invalidateQueries({ queryKey: ['purchase-orders'] }), queryClient.invalidateQueries({ queryKey: ['payables'] }), queryClient.invalidateQueries({ queryKey: ['business-postings'] })])
  } finally { reversingId.value = 0 }
}

function verificationStatusName(value: string) {
  if (value === 'VERIFIED') return '已核销'
  if (value === 'PARTIAL') return '部分分配'
  if (value === 'REVERSED') return '已冲正'
  if (value === 'UNAPPLIED') return '未分配'
  return value
}
</script>

<style scoped>
.amount-success { color: var(--el-color-success); font-weight: 600; }
.amount-warning { color: var(--el-color-warning); font-weight: 600; }
</style>
