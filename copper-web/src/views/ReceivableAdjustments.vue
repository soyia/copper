<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">应收调整单</div>
          <div class="muted">尾款折让、坏账和小额豁免只减少应收，不计入客户实付款；已确认单据只能冲正。</div>
        </div>
        <el-button type="primary" @click="openCreate">新增应收调整</el-button>
      </div>
      <div class="list-tools">
        <PartnerRemoteSelect v-model="filters.customerId" type="CUSTOMER" clearable placeholder="客户" />
        <el-select v-model="filters.type" clearable placeholder="调整类型">
          <el-option label="尾款折让" value="COMMERCIAL_DISCOUNT" />
          <el-option label="坏账核销" value="BAD_DEBT" />
          <el-option label="小额豁免" value="SMALL_WAIVER" />
        </el-select>
        <el-select v-model="filters.status" clearable placeholder="单据状态">
          <el-option label="暂存" value="DRAFT" /><el-option label="已确认" value="CONFIRMED" />
          <el-option label="已作废" value="CANCELLED" /><el-option label="已冲正" value="REVERSED" />
        </el-select>
        <div class="list-tools-actions"><el-button @click="resetFilters">重置</el-button></div>
      </div>
      <el-table :data="filteredRows" class="data-table" size="small" border show-overflow-tooltip max-height="calc(100vh - 275px)">
        <el-table-column label="调整单号" width="200" fixed="left"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row)">{{ row.adjustmentNo }}</el-button></template></el-table-column>
        <el-table-column prop="salesOrderNo" label="销售订单" width="220"><template #default="{ row }"><router-link class="table-link" :to="relatedDetailRoute(route, `/sales-orders/${row.salesOrderId}`)">{{ row.salesOrderNo }}</router-link></template></el-table-column>
        <el-table-column prop="customerName" label="客户" min-width="170" />
        <el-table-column label="类型" width="110"><template #default="{ row }">{{ typeName(row.adjustmentType) }}</template></el-table-column>
        <el-table-column label="调整金额" width="130" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
        <el-table-column prop="occurredDate" label="业务日期" width="115" :formatter="formatDateColumn" />
        <el-table-column prop="reason" label="原因" min-width="220" />
        <el-table-column prop="evidenceRef" label="依据/凭证" min-width="160" />
        <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="statusType(row.status)" size="small">{{ statusName(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 'DRAFT'" text type="primary" @click="confirmRow(row)">确认</el-button>
            <el-button v-if="row.status === 'DRAFT'" text type="danger" @click="cancelRow(row)">作废</el-button>
            <el-button v-if="row.status === 'CONFIRMED'" text type="danger" @click="reverseRow(row)">冲正</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="createVisible" title="新增应收调整单" width="620px">
      <el-alert title="保存后为暂存状态；确认时才减少订单待收金额。" type="info" :closable="false" style="margin-bottom: 14px" />
      <el-form label-width="100px">
        <el-form-item label="销售订单" required>
          <el-select v-model="form.salesOrderId" filterable placeholder="请选择仍有待收金额的订单">
            <el-option v-for="row in adjustableOrders" :key="row.id" :label="`${row.orderNo} / ${row.customerName} / 待收 ${money(row.outstandingAmount)}`" :value="row.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="调整类型" required><el-select v-model="form.adjustmentType"><el-option label="尾款折让" value="COMMERCIAL_DISCOUNT" /><el-option label="坏账核销" value="BAD_DEBT" /><el-option label="小额豁免" value="SMALL_WAIVER" /></el-select></el-form-item>
        <el-form-item label="调整金额" required><el-input v-model="form.amount" inputmode="decimal" placeholder="不能超过订单待收金额" /></el-form-item>
        <el-form-item label="业务日期" required><el-date-picker v-model="form.occurredDate" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="调整原因" required><el-input v-model="form.reason" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="依据/凭证"><el-input v-model="form.evidenceRef" placeholder="协议编号、审批记录或附件位置" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="createVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveDraft">保存暂存</el-button></template>
    </el-dialog>

    <RelatedDetailDrawer
      v-model="detailVisible"
      :title="`应收调整 ${activeAdjustment?.adjustmentNo || ''}`"
      subtitle="只调整应收余额，不伪造客户实付款"
      :trail="[{ label: '应收调整单' }, { label: activeAdjustment?.adjustmentNo || '调整详情' }]"
      size="860px"
      @breadcrumb="detailVisible = false"
    >
      <el-descriptions v-if="activeAdjustment" class="fixed-detail-descriptions" :column="2" label-width="140px" border>
        <el-descriptions-item label="调整单号"><span class="related-record-no">{{ activeAdjustment.adjustmentNo }}</span></el-descriptions-item>
        <el-descriptions-item label="销售订单"><span class="related-record-no"><router-link class="table-link" :to="relatedDetailRoute(route, `/sales-orders/${activeAdjustment.salesOrderId}`)">{{ activeAdjustment.salesOrderNo }}</router-link></span></el-descriptions-item>
        <el-descriptions-item label="客户">{{ activeAdjustment.customerName }}</el-descriptions-item>
        <el-descriptions-item label="调整类型">{{ typeName(activeAdjustment.adjustmentType) }}</el-descriptions-item>
        <el-descriptions-item label="调整金额">{{ money(activeAdjustment.amount) }}</el-descriptions-item>
        <el-descriptions-item label="业务日期">{{ formatDate(activeAdjustment.occurredDate) }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusName(activeAdjustment.status) }}</el-descriptions-item>
        <el-descriptions-item label="调整原因" :span="2">{{ activeAdjustment.reason }}</el-descriptions-item>
        <el-descriptions-item label="依据/凭证" :span="2">{{ activeAdjustment.evidenceRef || '-' }}</el-descriptions-item>
      </el-descriptions>
      <template #footer><el-button @click="detailVisible = false">关闭</el-button></template>
    </RelatedDetailDrawer>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { api, type ReceivableAdjustmentRow } from '@/api/client'
import { currentDateText, formatDate, formatDateColumn, money } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import RelatedDetailDrawer from '@/components/RelatedDetailDrawer.vue'
import { relatedDetailRoute } from '@/utils/navigation'

const route = useRoute()
const queryClient = useQueryClient()
const filters = reactive({ customerId: undefined as number | undefined, type: '', status: '' })
const createVisible = ref(false)
const saving = ref(false)
const detailVisible = ref(false)
const activeAdjustment = ref<ReceivableAdjustmentRow>()
const form = reactive({ salesOrderId: undefined as number | undefined, adjustmentType: 'COMMERCIAL_DISCOUNT', amount: '', occurredDate: currentDateText(), reason: '', evidenceRef: '' })
const { data: rows } = useQuery({ queryKey: ['receivable-adjustments'], queryFn: () => api.receivableAdjustments() })
const { data: salesOrders } = useQuery({ queryKey: ['sales-orders'], queryFn: api.salesOrders })
const adjustableOrders = computed(() => (salesOrders.value ?? []).filter((row) => !['DRAFT', 'CANCELLED'].includes(row.status) && Number(row.outstandingAmount) > 0))
const filteredRows = computed(() => (rows.value ?? []).filter((row) => (!filters.customerId || row.customerId === filters.customerId) && (!filters.type || row.adjustmentType === filters.type) && (!filters.status || row.status === filters.status)))

function openCreate() {
  form.salesOrderId = Number(route.query.salesOrderId || 0) || undefined
  form.adjustmentType = 'COMMERCIAL_DISCOUNT'; form.amount = ''; form.occurredDate = currentDateText(); form.reason = ''; form.evidenceRef = ''
  createVisible.value = true
}
function resetFilters() { filters.customerId = undefined; filters.type = ''; filters.status = '' }
function openDetail(row: ReceivableAdjustmentRow) { activeAdjustment.value = row; detailVisible.value = true }
async function refresh() { await Promise.all([queryClient.invalidateQueries({ queryKey: ['receivable-adjustments'] }), queryClient.invalidateQueries({ queryKey: ['sales-orders'] }), queryClient.invalidateQueries({ queryKey: ['receivables'] }), queryClient.invalidateQueries({ queryKey: ['partner-summaries'] }), queryClient.invalidateQueries({ queryKey: ['dashboard'] })]) }
async function saveDraft() {
  const amount = Number(form.amount)
  if (!form.salesOrderId) { ElMessage.warning('请选择销售订单'); return }
  if (!Number.isFinite(amount) || amount <= 0) { ElMessage.warning('请填写大于0的调整金额'); return }
  if (!form.reason.trim()) { ElMessage.warning('请填写调整原因'); return }
  saving.value = true
  try { await api.createReceivableAdjustment({ ...form, amount }); ElMessage.success('应收调整单已暂存'); createVisible.value = false; await refresh() } finally { saving.value = false }
}
async function confirmRow(row: ReceivableAdjustmentRow) {
  const ok = await ElMessageBox.confirm(`确认后将减少订单待收金额 ${money(row.amount)}，但不会增加已收现金。`, '确认应收调整', { type: 'warning' }).then(() => true).catch(() => false)
  if (!ok) return; await api.confirmReceivableAdjustment(row.id); ElMessage.success('应收调整已确认'); await refresh()
}
async function actionReason(title: string) { return ElMessageBox.prompt('请输入原因，操作后将保留完整流水。', title, { inputValidator: (v) => String(v || '').trim() ? true : '请输入原因' }).then((r) => r.value.trim()).catch(() => '') }
async function cancelRow(row: ReceivableAdjustmentRow) { const reason = await actionReason('作废应收调整单'); if (!reason) return; await api.cancelReceivableAdjustment(row.id, { occurredDate: currentDateText(), reason }); ElMessage.success('暂存单已作废'); await refresh() }
async function reverseRow(row: ReceivableAdjustmentRow) { const reason = await actionReason('冲正应收调整单'); if (!reason) return; await api.reverseReceivableAdjustment(row.id, { occurredDate: currentDateText(), reason }); ElMessage.success('应收调整已冲正，应收已恢复'); await refresh() }
function typeName(v: string) { return v === 'BAD_DEBT' ? '坏账核销' : v === 'SMALL_WAIVER' ? '小额豁免' : '尾款折让' }
function statusName(v: string) { return ({ DRAFT: '暂存', CONFIRMED: '已确认', CANCELLED: '已作废', REVERSED: '已冲正' } as Record<string, string>)[v] ?? v }
function statusType(v: string) { return v === 'CONFIRMED' ? 'success' : v === 'DRAFT' ? 'warning' : 'info' }
</script>
