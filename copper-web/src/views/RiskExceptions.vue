<template>
  <div class="page">
    <div class="panel risk-header">
      <div class="panel-header">
        <div>
          <div class="panel-title">例外审批</div>
          <div class="muted risk-subtitle">只处理特价、超信用、大额付款、资金或库存调整及高风险冲正；普通业务不增加审批步骤。</div>
        </div>
        <div class="header-actions">
          <el-button v-if="canManagePolicy" @click="openPolicy">审批阈值</el-button>
          <el-button :loading="isFetching" @click="refreshAll">刷新</el-button>
        </div>
      </div>
      <div class="risk-metrics">
        <button class="risk-metric" type="button" @click="status = 'PENDING'">
          <span>待负责人处理</span><strong>{{ dashboard?.pendingCount ?? 0 }}</strong>
        </button>
        <button class="risk-metric" type="button" @click="status = 'APPROVED'">
          <span>已批准待执行</span><strong>{{ dashboard?.approvedWaitingExecutionCount ?? 0 }}</strong>
        </button>
        <button class="risk-metric" type="button" @click="status = ''">
          <span>24 小时内到期</span><strong>{{ dashboard?.expiringWithin24HoursCount ?? 0 }}</strong>
        </button>
        <button class="risk-metric" type="button" @click="mineOnly = true">
          <span>我发起的处理中</span><strong>{{ dashboard?.requestedByMeCount ?? 0 }}</strong>
        </button>
      </div>
    </div>

    <el-alert type="info" :closable="false" show-icon title="批准不等于执行业务">
      <template #default>批准只对当前单据版本、金额和动作生效。除大额付款意向可在审批详情执行外，其余事项仍须回到原单据操作；单据变化后原批准自动不匹配。</template>
    </el-alert>

    <div class="panel">
      <div class="list-tools risk-tools">
        <el-segmented v-model="status" :options="statusOptions" />
        <el-select v-model="exceptionType" clearable placeholder="全部例外类型" style="width: 210px">
          <el-option v-for="item in exceptionTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-checkbox v-model="mineOnly">只看与我有关</el-checkbox>
        <el-input v-model="keyword" clearable placeholder="申请号 / 原单号 / 申请人 / 事由" style="max-width: 310px" />
      </div>
      <el-table v-loading="requestsQuery.isLoading.value" :data="filteredRequests" border size="small" class="data-table" max-height="calc(100vh - 410px)" @row-dblclick="openDetail">
        <el-table-column label="申请号" min-width="205" fixed="left">
          <template #default="{ row }"><RouterLink class="business-link" :to="`/risk-exceptions/${row.id}`">{{ row.requestNo }}</RouterLink><div class="multiline-secondary">V{{ row.policyVersion }} 策略</div></template>
        </el-table-column>
        <el-table-column label="状态" width="118"><template #default="{ row }"><el-tag size="small" :type="statusType(row.status)">{{ statusName(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="例外事项" min-width="220"><template #default="{ row }"><div>{{ exceptionTypeName(row.exceptionType) }}</div><div class="multiline-secondary">{{ row.title }}</div></template></el-table-column>
        <el-table-column label="原业务" min-width="190"><template #default="{ row }"><div>{{ row.sourceNo || `${row.sourceType} #${row.sourceId}` }}</div><div class="multiline-secondary">{{ actionName(row.actionCode) }} · 版本 {{ row.sourceVersion }}</div></template></el-table-column>
        <el-table-column label="影响金额" width="140" align="right"><template #default="{ row }"><strong>{{ money(row.sourceAmount) }}</strong></template></el-table-column>
        <el-table-column label="申请人 / 时间" min-width="175"><template #default="{ row }"><div>{{ row.requestedByName }}</div><div class="multiline-secondary">{{ formatDateTime(row.requestedAt) }}</div></template></el-table-column>
        <el-table-column label="当前处理人" min-width="145"><template #default="{ row }">{{ row.assignedEmployeeName || (row.status === 'PENDING' ? '负责人' : '-') }}</template></el-table-column>
        <el-table-column label="有效期" min-width="175"><template #default="{ row }"><span :class="{ 'expiry-warning': isExpiring(row.expiresAt, row.status) }">{{ formatDateTime(row.expiresAt) }}</span></template></el-table-column>
        <el-table-column label="操作" width="94" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row)">处理</el-button></template></el-table-column>
      </el-table>
      <el-empty v-if="!requestsQuery.isLoading.value && !filteredRequests.length" description="当前筛选条件下没有例外审批" />
    </div>

    <el-drawer v-model="policyVisible" title="例外审批阈值" size="520px" destroy-on-close>
      <el-alert title="只控制少数高风险动作" description="阈值调整从新申请起生效，历史申请保留原策略版本；销售特价、超信用和盘亏按业务性质控制，不受金额阈值替代。" type="warning" :closable="false" show-icon />
      <el-form label-position="top" class="policy-form">
        <el-form-item label="大额付款阈值（元）"><el-input-number v-model="policyForm.largePaymentThreshold" :min="0.01" :precision="2" :step="10000" :controls="false" /></el-form-item>
        <el-form-item label="库存金额调整阈值（元）"><el-input-number v-model="policyForm.inventoryAdjustmentThreshold" :min="0.01" :precision="2" :step="1000" :controls="false" /></el-form-item>
        <el-form-item label="高风险冲正阈值（元）"><el-input-number v-model="policyForm.highRiskReversalThreshold" :min="0.01" :precision="2" :step="10000" :controls="false" /></el-form-item>
        <el-form-item label="批准有效期（小时）"><el-input-number v-model="policyForm.approvalValidHours" :min="1" :max="720" :controls="false" /></el-form-item>
        <el-form-item label="调整原因"><el-input v-model="policyForm.changeReason" type="textarea" :rows="4" maxlength="500" show-word-limit placeholder="说明经营政策或风险口径变化" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="policyVisible = false">取消</el-button><el-button type="primary" :loading="savingPolicy" @click="savePolicy">保存新版本</el-button></template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { api, type RiskExceptionRequest, type RiskExceptionStatus, type RiskExceptionType } from '@/api/client'
import { formatDateTime, money } from '@/api/format'
import { useAppStore } from '@/stores/app'

const router = useRouter()
const appStore = useAppStore()
const queryClient = useQueryClient()
const status = ref<RiskExceptionStatus | ''>('PENDING')
const exceptionType = ref<RiskExceptionType | ''>('')
const mineOnly = ref(false)
const keyword = ref('')
const policyVisible = ref(false)
const savingPolicy = ref(false)
const canManagePolicy = computed(() => appStore.hasPermission('risk.exception.policy.manage'))
const statusOptions = [{ label: '待处理', value: 'PENDING' }, { label: '已批准待执行', value: 'APPROVED' }, { label: '全部', value: '' }]
const exceptionTypeOptions = [
  { value: 'SPECIAL_PRICE', label: '特价' }, { value: 'SALES_RISK', label: '销售信用 / 毛利' },
  { value: 'LARGE_PAYMENT', label: '大额付款' }, { value: 'FUND_ADJUSTMENT', label: '往来余额调整' },
  { value: 'INVENTORY_ADJUSTMENT', label: '库存调整' }, { value: 'HIGH_RISK_REVERSAL', label: '高风险冲正' },
] as { value: RiskExceptionType; label: string }[]
const dashboardQuery = useQuery({ queryKey: ['risk-exceptions-dashboard'], queryFn: api.riskExceptionDashboard })
const requestsQuery = useQuery({
  queryKey: computed(() => ['risk-exceptions', status.value, exceptionType.value, mineOnly.value]),
  queryFn: () => api.riskExceptionRequests({ status: status.value || undefined, exceptionType: exceptionType.value || undefined, mineOnly: mineOnly.value || undefined }),
})
const policyQuery = useQuery({ queryKey: ['risk-exception-policy'], queryFn: api.riskExceptionPolicy, enabled: canManagePolicy })
const dashboard = computed(() => dashboardQuery.data.value)
const filteredRequests = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  if (!key) return requestsQuery.data.value || []
  return (requestsQuery.data.value || []).filter((row) => [row.requestNo, row.sourceNo, row.requestedByName, row.requestReason, row.title].some((value) => String(value || '').toLowerCase().includes(key)))
})
const isFetching = computed(() => dashboardQuery.isFetching.value || requestsQuery.isFetching.value)
const policyForm = reactive({ largePaymentThreshold: 50000, inventoryAdjustmentThreshold: 5000, highRiskReversalThreshold: 50000, approvalValidHours: 72, changeReason: '' })

async function refreshAll() { await Promise.all([dashboardQuery.refetch(), requestsQuery.refetch()]) }
function openDetail(row: RiskExceptionRequest) { router.push(`/risk-exceptions/${row.id}`) }
async function openPolicy() {
  const value = await policyQuery.refetch().then((result) => result.data)
  if (value) Object.assign(policyForm, { ...value, changeReason: '' })
  policyVisible.value = true
}
async function savePolicy() {
  if (policyForm.changeReason.trim().length < 4) { ElMessage.warning('阈值调整原因至少填写4个字'); return }
  savingPolicy.value = true
  try {
    await api.updateRiskExceptionPolicy({ ...policyForm, changeReason: policyForm.changeReason.trim() })
    ElMessage.success('例外审批阈值已保存为新版本')
    policyVisible.value = false
    await queryClient.invalidateQueries({ queryKey: ['risk-exception-policy'] })
  } finally { savingPolicy.value = false }
}
function statusName(value: string) { return ({ PENDING: '待审批', APPROVED: '已批准待执行', REJECTED: '已退回', WITHDRAWN: '已撤回', EXPIRED: '已失效', CONSUMED: '已执行', INVALIDATED: '已失效' } as Record<string, string>)[value] || value }
function statusType(value: string) { return value === 'PENDING' ? 'warning' : value === 'APPROVED' ? 'primary' : value === 'CONSUMED' ? 'success' : value === 'REJECTED' ? 'danger' : 'info' }
function exceptionTypeName(value: string) { return Object.fromEntries(exceptionTypeOptions.map((item) => [item.value, item.label]))[value] || value }
function actionName(value: string) { return ({ CONFIRM: '确认并过账', APPROVE: '批准付款申请', CREATE: '建立付款', SHIP: '确认发货', REVERSE: '冲正' } as Record<string, string>)[value] || value }
function isExpiring(value: string, requestStatus: string) { return ['PENDING', 'APPROVED'].includes(requestStatus) && new Date(value).getTime() - Date.now() < 24 * 3600 * 1000 }
</script>

<style scoped>
.risk-subtitle { margin-top: 6px; line-height: 1.55; }
.header-actions, .risk-tools { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.risk-metrics { display: grid; grid-template-columns: repeat(4, minmax(150px, 1fr)); gap: 12px; margin-top: 18px; }
.risk-metric { display: flex; align-items: center; justify-content: space-between; padding: 15px 17px; border: 1px solid #dfe6ec; border-radius: 9px; background: #f8fafc; color: #536371; cursor: pointer; }
.risk-metric:hover { border-color: #d46a3a; background: #fff9f5; }
.risk-metric strong { color: #1f2d3d; font-size: 24px; }
.expiry-warning { color: #c4562f; font-weight: 700; }
.policy-form { margin-top: 20px; }
.policy-form :deep(.el-input-number) { width: 100%; }
@media (max-width: 900px) { .risk-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 560px) { .risk-metrics { grid-template-columns: 1fr; } }
</style>
