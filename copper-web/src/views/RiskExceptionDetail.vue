<template>
  <div class="page" v-loading="query.isLoading.value">
    <template v-if="detail">
      <div class="panel">
        <div class="panel-header detail-header">
          <div>
            <div class="title-line"><div class="panel-title">{{ header.requestNo }}</div><el-tag :type="statusType(header.status)">{{ statusName(header.status) }}</el-tag></div>
            <div class="muted detail-subtitle">{{ exceptionTypeName(header.exceptionType) }} · {{ header.title }}</div>
          </div>
          <div class="header-actions">
            <el-button @click="router.push('/risk-exceptions')">返回列表</el-button>
            <el-button v-if="header.status === 'PENDING' && canApprove" :loading="acting" @click="transfer">转交</el-button>
            <el-button v-if="header.status === 'PENDING' && canApprove" type="danger" plain :loading="acting" @click="decide('reject')">退回</el-button>
            <el-button v-if="header.status === 'PENDING' && canApprove" type="primary" :loading="acting" @click="decide('approve')">批准</el-button>
            <el-button v-if="['PENDING', 'APPROVED'].includes(header.status) && canWithdraw" :loading="acting" @click="withdraw">撤回</el-button>
            <el-button v-if="header.status === 'APPROVED' && header.sourceType === 'SUPPLIER_PAYMENT_INTENT'" type="primary" :loading="acting" @click="executePayment">执行付款</el-button>
            <el-button v-else-if="header.status === 'APPROVED' && sourceRoute" type="primary" @click="router.push(sourceRoute)">回到原单执行</el-button>
          </div>
        </div>
      </div>

      <el-alert v-if="header.status === 'APPROVED'" type="warning" :closable="false" show-icon title="已批准，但业务尚未执行" description="批准仅锁定当前快照的单次授权；业务动作成功后状态才会变为已执行。" />
      <el-alert v-else-if="header.status === 'CONSUMED'" type="success" :closable="false" show-icon title="批准已被业务动作一次性消耗" :description="header.resultSourceNo ? `执行结果：${header.resultSourceNo}` : '原业务动作已完成，批准不能再次使用。'" />

      <div class="detail-grid">
        <div class="panel">
          <div class="section-title">申请与原业务</div>
          <el-descriptions :column="2" border class="fixed-detail-descriptions" label-width="132px">
            <el-descriptions-item label="例外类型">{{ exceptionTypeName(header.exceptionType) }}</el-descriptions-item>
            <el-descriptions-item label="风险等级">高风险</el-descriptions-item>
            <el-descriptions-item label="原业务单号">{{ header.sourceNo || '-' }}</el-descriptions-item>
            <el-descriptions-item label="待执行动作">{{ actionName(header.actionCode) }}</el-descriptions-item>
            <el-descriptions-item label="锁定版本">{{ header.sourceVersion }}</el-descriptions-item>
            <el-descriptions-item label="影响金额"><strong>{{ money(header.sourceAmount) }}</strong></el-descriptions-item>
            <el-descriptions-item label="申请人">{{ header.requestedByName }}</el-descriptions-item>
            <el-descriptions-item label="申请时间">{{ formatDateTime(header.requestedAt) }}</el-descriptions-item>
            <el-descriptions-item label="申请原因" :span="2">{{ header.requestReason }}</el-descriptions-item>
            <el-descriptions-item label="快照摘要" :span="2"><code>{{ header.snapshotHash }}</code></el-descriptions-item>
          </el-descriptions>
        </div>
        <div class="panel">
          <div class="section-title">处理与有效期</div>
          <el-descriptions :column="1" border class="fixed-detail-descriptions" label-width="130px">
            <el-descriptions-item label="当前处理人">{{ header.assignedEmployeeName || '负责人' }}</el-descriptions-item>
            <el-descriptions-item label="有效至">{{ formatDateTime(header.expiresAt) }}</el-descriptions-item>
            <el-descriptions-item label="批准人">{{ header.approvedByName || '-' }}<span v-if="header.approvedAt"> · {{ formatDateTime(header.approvedAt) }}</span></el-descriptions-item>
            <el-descriptions-item label="处理意见">{{ header.decisionReason || '-' }}</el-descriptions-item>
            <el-descriptions-item label="策略版本">V{{ header.policyVersion }}</el-descriptions-item>
            <el-descriptions-item label="执行结果">{{ header.resultSourceNo || '-' }}</el-descriptions-item>
          </el-descriptions>
        </div>
      </div>

      <div class="panel">
        <div class="panel-header"><div><div class="panel-title">冻结业务快照</div><div class="muted">执行时重新计算摘要；版本、金额或风险事实变化将拒绝使用原批准。</div></div></div>
        <pre class="snapshot">{{ prettySnapshot }}</pre>
      </div>
      <div class="panel">
        <div class="panel-header"><div><div class="panel-title">不可变审批轨迹</div><div class="muted">提交、转交、批准、退回、撤回、失效和执行只追加事件，不覆盖历史。</div></div></div>
        <el-table :data="detail.events" border size="small" class="data-table">
          <el-table-column label="时间" width="176"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
          <el-table-column label="动作" width="120"><template #default="{ row }">{{ eventName(row.action) }}</template></el-table-column>
          <el-table-column label="状态变化" width="205"><template #default="{ row }">{{ row.fromStatus ? statusName(row.fromStatus) : '建立' }} → {{ statusName(row.toStatus) }}</template></el-table-column>
          <el-table-column prop="operatorName" label="操作人" width="160" />
          <el-table-column prop="reason" label="原因 / 说明" min-width="300" />
        </el-table>
      </div>
    </template>
    <el-result v-else-if="query.isError.value" icon="error" title="例外审批加载失败" sub-title="对象可能不存在、已超出数据范围或当前账号无权查看"><template #extra><el-button @click="query.refetch()">重新加载</el-button></template></el-result>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import { formatDateTime, money } from '@/api/format'
import { useAppStore } from '@/stores/app'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const queryClient = useQueryClient()
const id = Number(route.params.id)
const acting = ref(false)
const query = useQuery({ queryKey: ['risk-exception', id], queryFn: () => api.riskExceptionRequest(id), enabled: Number.isFinite(id) && id > 0 })
const detail = computed(() => query.data.value)
const header = computed(() => detail.value!.header)
const canApprove = computed(() => appStore.hasPermission('risk.exception.approve'))
const canWithdraw = computed(() => canApprove.value || header.value.requestedEmployeeId === appStore.session?.employeeId)
const approversQuery = useQuery({ queryKey: ['risk-exception-approvers'], queryFn: api.riskExceptionApprovers, enabled: canApprove })
const prettySnapshot = computed(() => { try { return JSON.stringify(JSON.parse(detail.value?.snapshotJson || '{}'), null, 2) } catch { return detail.value?.snapshotJson || '-' } })
const sourceRoute = computed(() => {
  const row = header.value
  const routeMap: Record<string, string> = {
    SALES_ORDER: `/sales-orders/${row.sourceId}`, OUTBOUND: `/outbounds/${row.sourceId}`,
    PARTNER_FUND_ADJUSTMENT: `/partner-fund-adjustments/${row.sourceId}`,
    INVENTORY_STOCKTAKE: `/inventory/stocktakes/${row.sourceId}`,
    INVENTORY_COST_ADJUSTMENT: `/inventory/cost-adjustments/${row.sourceId}`,
    TREASURY_PAYMENT_REQUEST: '/treasury', TREASURY_DOCUMENT: `/treasury/documents/${row.sourceId}`,
    TREASURY_FUND: '/treasury/journal', PAYMENT: '/payments', SUPPLIER_PAYMENT: '/supplier-payments',
    CUSTOMER_ADVANCE_REFUND: '/customer-advances',
  }
  return routeMap[row.sourceType]
})

async function refresh() {
  await Promise.all([query.refetch(), queryClient.invalidateQueries({ queryKey: ['risk-exceptions'] }), queryClient.invalidateQueries({ queryKey: ['risk-exceptions-dashboard'] })])
}
async function prompt(title: string, message: string) {
  const result = await ElMessageBox.prompt(message, title, { type: 'warning', inputType: 'textarea', inputValidator: (value) => String(value || '').trim().length >= 4 || '处理意见至少4个字' }).catch(() => null)
  return result?.value.trim()
}
async function decide(action: 'approve' | 'reject') {
  const reason = await prompt(action === 'approve' ? '批准例外' : '退回申请', action === 'approve' ? '请写明已核实的业务依据。批准不会自动执行原业务。' : '请写明退回原因和申请人应补充或调整的事项。')
  if (!reason) return
  acting.value = true
  try { if (action === 'approve') await api.approveRiskException(id, reason); else await api.rejectRiskException(id, reason); ElMessage.success(action === 'approve' ? '已批准，等待原业务执行' : '申请已退回'); await refresh() } finally { acting.value = false }
}
async function withdraw() {
  const reason = await prompt('撤回例外申请', '撤回后原批准（如有）立即失效，后续需要重新提交。')
  if (!reason) return
  acting.value = true
  try { await api.withdrawRiskException(id, reason); ElMessage.success('申请已撤回'); await refresh() } finally { acting.value = false }
}
async function transfer() {
  const options = (approversQuery.data.value || []).filter((item) => item.employeeId !== header.value.assignedEmployeeId)
  if (!options.length) { ElMessage.warning('没有其他可转交的负责人'); return }
  const names = options.map((item) => `${item.employeeId}：${item.employeeName}（${item.departmentName}）`).join('\n')
  const selected = await ElMessageBox.prompt(`可转交负责人：\n${names}\n\n请输入负责人编号。`, '转交例外申请', { inputValidator: (value) => options.some((item) => item.employeeId === Number(value)) || '请输入列表中的负责人编号' }).catch(() => null)
  if (!selected) return
  const reason = await prompt('转交原因', '说明为何转交给该负责人处理。')
  if (!reason) return
  acting.value = true
  try { await api.transferRiskException(id, { targetEmployeeId: Number(selected.value), reason }); ElMessage.success('申请已转交'); await refresh() } finally { acting.value = false }
}
async function executePayment() {
  const confirmed = await ElMessageBox.confirm('将按审批中冻结的供应商、金额、账户、日期和核销分配真正建立付款。系统会再次校验原付款权限和数据一致性。', '执行已批准的大额付款', { type: 'warning', confirmButtonText: '执行付款' }).then(() => true).catch(() => false)
  if (!confirmed) return
  acting.value = true
  try { await api.executeRiskException(id); ElMessage.success('供应商付款已建立，批准已一次性消耗'); await refresh(); await queryClient.invalidateQueries({ queryKey: ['supplier-payments'] }) } finally { acting.value = false }
}
function statusName(value: string) { return ({ PENDING: '待审批', APPROVED: '已批准待执行', REJECTED: '已退回', WITHDRAWN: '已撤回', EXPIRED: '已失效', CONSUMED: '已执行', INVALIDATED: '已失效', '': '建立' } as Record<string, string>)[value] || value }
function statusType(value: string) { return value === 'PENDING' ? 'warning' : value === 'APPROVED' ? 'primary' : value === 'CONSUMED' ? 'success' : value === 'REJECTED' ? 'danger' : 'info' }
function exceptionTypeName(value: string) { return ({ SPECIAL_PRICE: '特价', SALES_RISK: '销售信用 / 毛利', LARGE_PAYMENT: '大额付款', FUND_ADJUSTMENT: '往来余额调整', INVENTORY_ADJUSTMENT: '库存调整', HIGH_RISK_REVERSAL: '高风险冲正' } as Record<string, string>)[value] || value }
function actionName(value: string) { return ({ CONFIRM: '确认并过账', APPROVE: '批准付款申请', CREATE: '建立付款', SHIP: '确认发货', REVERSE: '冲正' } as Record<string, string>)[value] || value }
function eventName(value: string) { return ({ SUBMIT: '提交', APPROVE: '批准', REJECT: '退回', WITHDRAW: '撤回', TRANSFER: '转交', EXPIRE: '到期失效', CONSUME: '执行业务', DIRECT_APPROVE: '负责人直接授权' } as Record<string, string>)[value] || value }
</script>

<style scoped>
.detail-header, .title-line, .header-actions { display: flex; align-items: center; gap: 10px; }
.detail-header { width: 100%; justify-content: space-between; }
.header-actions { justify-content: flex-end; flex-wrap: wrap; }
.detail-subtitle { margin-top: 6px; }
.detail-grid { display: grid; grid-template-columns: minmax(0, 1.25fr) minmax(350px, .75fr); gap: 14px; }
.section-title { margin-bottom: 14px; font-size: 16px; font-weight: 700; }
.snapshot { max-height: 340px; margin: 0; padding: 16px; overflow: auto; border: 1px solid #e2e8ee; border-radius: 7px; background: #f7f9fb; color: #334554; font: 12px/1.65 ui-monospace, SFMono-Regular, Menlo, monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
code { font-size: 11px; overflow-wrap: anywhere; }
@media (max-width: 980px) { .detail-grid { grid-template-columns: 1fr; } }
@media (max-width: 720px) { .detail-header { align-items: flex-start; flex-direction: column; } .header-actions { justify-content: flex-start; } }
</style>
