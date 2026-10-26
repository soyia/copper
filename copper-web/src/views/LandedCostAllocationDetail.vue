<template>
  <div class="page">
    <div class="panel detail-panel">
      <PageHeader :title="`采购费用分摊 ${header?.allocationNo || `#${id}`}`" :back-to="backTo">
        <el-button v-if="header?.status === 'DRAFT' && canManage" @click="router.push(editRoute)">编辑</el-button>
        <el-button v-if="header?.status === 'DRAFT' && canManage" type="danger" plain :loading="acting" @click="cancel">作废</el-button>
        <el-button v-if="header?.status === 'DRAFT' && canConfirm" type="primary" :loading="acting" @click="confirm">确认分摊</el-button>
        <el-button v-if="header?.status === 'CONFIRMED' && canReverse" type="danger" plain :loading="acting" @click="reverse">冲正</el-button>
      </PageHeader>
      <template v-if="detail">
        <el-alert class="boundary" :type="header.status === 'DRAFT' ? 'warning' : 'info'" :closable="false" show-icon
          :title="header.status === 'DRAFT' ? '草稿尚未影响批次成本、主营成本和总账。' : '资本化只影响确认时仍在库的批次；已耗用部分和后续冲正均保留原业务时间顺序。'" />
        <div class="summary-grid">
          <div><span>计入成本金额</span><strong>{{ money(header.feeAmount) }}</strong></div>
          <div><span>已分摊</span><strong>{{ money(header.allocatedAmount) }}</strong></div>
          <div class="success"><span>计入当前库存</span><strong>{{ money(header.capitalizedAmount) }}</strong></div>
          <div class="warning"><span>已耗用留存成本</span><strong>{{ money(header.expensedAmount) }}</strong></div>
        </div>
        <el-descriptions :column="4" border class="descriptions">
          <el-descriptions-item label="状态"><el-tag :type="statusType(header.status)">{{ statusName(header.status) }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="业务日期">{{ header.businessDate }}</el-descriptions-item>
          <el-descriptions-item label="分摊方式">{{ methodName(header.allocationMethod) }}</el-descriptions-item>
          <el-descriptions-item label="费用供应商">{{ header.feePartnerName }}</el-descriptions-item>
          <el-descriptions-item label="费用源单"><router-link class="business-link" :to="`/treasury/documents/${header.treasuryDocumentId}`">{{ header.treasuryDocumentNo }}</router-link></el-descriptions-item>
          <el-descriptions-item label="业务过账"><router-link v-if="detail.postingBatchId" class="business-link" :to="`/business-postings/${detail.postingBatchId}`">查看过账 #{{ detail.postingBatchId }}</router-link><span v-else>-</span></el-descriptions-item>
          <el-descriptions-item label="会计凭证"><router-link v-if="detail.accountingVoucherId" class="business-link" :to="`/accounting/vouchers/${detail.accountingVoucherId}`">{{ detail.accountingVoucherNo || `#${detail.accountingVoucherId}` }}</router-link><span v-else>-</span></el-descriptions-item>
          <el-descriptions-item label="确认时间">{{ formatDateTime(header.confirmedAt) || '-' }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="4">{{ header.memo || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.cancelReason" label="作废原因" :span="4">{{ detail.cancelReason }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.reversalReason" label="冲正原因" :span="4">{{ detail.reversalReason }}</el-descriptions-item>
        </el-descriptions>

        <div class="section-title">入库费用分摊</div>
        <el-table :data="detail.items" border size="small" show-overflow-tooltip>
          <el-table-column label="原入库单" min-width="185" fixed="left"><template #default="{ row }"><router-link class="table-link" :to="`/inbounds/${row.inboundId}`">{{ row.inboundNo }}</router-link></template></el-table-column>
          <el-table-column label="商品 / 批次" min-width="250" fixed="left"><template #default="{ row }"><div>{{ row.productName }} · {{ row.sku }}</div><div class="multiline-secondary">{{ row.spec || '-' }} · {{ row.batchNo }}</div></template></el-table-column>
          <el-table-column prop="warehouseName" label="原仓库" width="120" />
          <el-table-column label="原数量 / 确认时结存" width="185" align="right"><template #default="{ row }">{{ quantity(row.originalQuantity) }} / {{ quantity(row.remainingQuantitySnapshot) }} {{ row.baseUnit }}</template></el-table-column>
          <el-table-column label="分摊依据" width="125" align="right"><template #default="{ row }">{{ basisText(row) }}</template></el-table-column>
          <el-table-column label="费用分摊" width="125" align="right"><template #default="{ row }"><strong>{{ money(row.allocatedAmount) }}</strong></template></el-table-column>
          <el-table-column label="单位附加费" width="130" align="right"><template #default="{ row }">{{ money(row.landedUnitAmount, 6) }}</template></el-table-column>
          <el-table-column label="资本化 / 已耗用" width="170" align="right"><template #default="{ row }">{{ money(row.capitalizedAmount) }} / {{ money(row.expensedAmount) }}</template></el-table-column>
        </el-table>

        <template v-if="detail.batches.length">
          <div class="section-title">批次成本变化</div>
          <el-table :data="detail.batches" border size="small">
            <el-table-column prop="batchNo" label="当前批次" min-width="180" />
            <el-table-column prop="warehouseName" label="仓库" width="125" />
            <el-table-column label="数量快照" width="125" align="right"><template #default="{ row }">{{ quantity(row.quantitySnapshot) }}</template></el-table-column>
            <el-table-column label="原单位成本" width="135" align="right"><template #default="{ row }">{{ money(row.oldUnitCost, 4) }}</template></el-table-column>
            <el-table-column label="分摊金额" width="125" align="right"><template #default="{ row }">{{ money(row.allocatedAmount) }}</template></el-table-column>
            <el-table-column label="新单位成本" width="135" align="right"><template #default="{ row }"><strong>{{ money(row.newUnitCost, 4) }}</strong></template></el-table-column>
            <el-table-column label="成本金额变化" min-width="190" align="right"><template #default="{ row }">{{ money(row.oldAmountCost) }} → {{ money(row.newAmountCost) }}</template></el-table-column>
          </el-table>
        </template>

        <div class="section-title">操作记录</div>
        <el-timeline class="events"><el-timeline-item v-for="event in detail.events" :key="event.id" :timestamp="formatDateTime(event.createdAt)" placement="top"><div>{{ actionName(event.action) }} · {{ event.operatorName || '系统用户' }}</div><div v-if="event.reason" class="muted">{{ event.reason }}</div></el-timeline-item></el-timeline>
      </template>
      <el-empty v-else-if="!query.isFetching.value" description="分摊单不存在或无权访问" />
    </div>
    <EvidenceAttachments v-if="detail" document-type="LANDED_COST_ALLOCATION" :document-id="id" title="运费单、装卸结算单、检验报告及审批依据" default-evidence-type="ORIGINAL_DOCUMENT" show-output-history />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type LandedCostAllocationItem, type LandedCostAllocationRow } from '@/api/client'
import { currentDateText, formatDateTime, money, quantity } from '@/api/format'
import EvidenceAttachments from '@/components/EvidenceAttachments.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useAppStore } from '@/stores/app'

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const appStore = useAppStore()
const acting = ref(false)
const id = Number(route.params.id)
const backTo = computed(() => typeof route.query.from === 'string' ? route.query.from : '/inventory/landed-cost-allocations')
const editRoute = computed(() => ({ path: `/inventory/landed-cost-allocations/${id}/edit`, query: { from: route.fullPath } }))
const canManage = computed(() => appStore.hasPermission('landed.cost.manage'))
const canConfirm = computed(() => appStore.hasPermission('landed.cost.confirm'))
const canReverse = computed(() => appStore.hasPermission('landed.cost.reverse'))
const query = useQuery({ queryKey: ['landed-cost-allocation', id], queryFn: () => api.landedCostAllocation(id) })
const detail = computed(() => query.data.value)
const emptyHeader: LandedCostAllocationRow = { id, allocationNo: '', treasuryDocumentId: 0, treasuryDocumentNo: '', feePartnerName: '', businessDate: '', allocationMethod: 'QUANTITY', status: 'DRAFT', feeAmount: 0, allocatedAmount: 0, capitalizedAmount: 0, expensedAmount: 0, memo: '', confirmedAt: null, createdAt: '' }
const header = computed(() => detail.value?.header ?? emptyHeader)

async function confirm() {
  const ok = await ElMessageBox.confirm('确认后，计入成本金额将按当前结存计入各批次成本并生成业务过账；已耗用部分不会追溯改写历史销售成本。', '确认采购费用分摊', { type: 'warning', confirmButtonText: '确认分摊' }).then(() => true).catch(() => false)
  if (!ok) return
  await run(() => api.confirmLandedCostAllocation(id), '费用已按当前结存计入批次成本')
}
async function cancel() { const reason = await promptReason('作废分摊草稿'); if (reason) await run(() => api.cancelLandedCostAllocation(id, reason), '分摊草稿已作废') }
async function reverse() {
  const result = await ElMessageBox.prompt('冲正只回退当前仍在库的费用组件；必须先冲正时间上更晚的采购退货、调拨或成本调整。请输入具体原因。', '冲正采购费用分摊', { type: 'warning', inputType: 'textarea', inputValidator: (value) => String(value || '').trim().length >= 4 || '原因至少4个字' }).catch(() => null)
  if (result) await run(() => api.reverseLandedCostAllocation(id, { reason: result.value.trim(), businessDate: currentDateText() }), '费用分摊已按当前结存冲正')
}
async function promptReason(title: string) { const result = await ElMessageBox.prompt('请输入具体原因，原单和操作记录将继续保留。', title, { type: 'warning', inputValidator: (value) => String(value || '').trim().length >= 4 || '原因至少4个字' }).catch(() => null); return result?.value.trim() || '' }
async function run(action: () => Promise<unknown>, message: string) { acting.value = true; try { await action(); ElMessage.success(message); await query.refetch(); await queryClient.invalidateQueries({ queryKey: ['landed-cost-allocations'] }); await queryClient.invalidateQueries({ queryKey: ['inventory'] }) } catch (error) { ElMessage.error(error instanceof Error ? error.message : '操作失败') } finally { acting.value = false } }
function basisText(row: LandedCostAllocationItem) { return header.value?.allocationMethod === 'WEIGHT' ? `${quantity(row.basisValue)} kg` : header.value?.allocationMethod === 'AMOUNT' ? money(row.basisValue) : header.value?.allocationMethod === 'MANUAL' ? money(row.allocatedAmount) : `${quantity(row.basisValue)} ${row.baseUnit}` }
function methodName(value: string) { return ({ QUANTITY: '按入库数量', WEIGHT: '按计费重量', AMOUNT: '按未税金额', MANUAL: '手工指定金额' } as Record<string, string>)[value] ?? value }
function statusName(value: string) { return ({ DRAFT: '草稿', CONFIRMED: '已确认', CANCELLED: '已作废', REVERSED: '已冲正' } as Record<string, string>)[value] ?? value }
function statusType(value: string) { return value === 'CONFIRMED' ? 'success' : 'info' }
function actionName(value: string) { return ({ CREATE: '创建草稿', UPDATE: '修改草稿', CONFIRM: '确认分摊', CANCEL: '作废草稿', REVERSE: '冲正' } as Record<string, string>)[value] ?? value }
</script>

<style scoped>
.detail-panel{max-width:1500px;margin:0 auto}.boundary{margin:0 18px 14px}.summary-grid{display:grid;grid-template-columns:repeat(4,minmax(150px,1fr));gap:10px;margin:0 18px 16px}.summary-grid div{display:grid;gap:5px;padding:13px 15px;border:1px solid #e1e7ec;border-radius:9px;background:#f8fafb}.summary-grid span{color:#71808b;font-size:12px}.summary-grid strong{font-size:19px}.summary-grid .success{background:#f3faf5}.summary-grid .warning{background:#fff8f1}.descriptions{margin:0 18px}.section-title{margin:22px 18px 10px;font-size:15px;font-weight:700}.detail-panel>:deep(.el-table){width:calc(100% - 36px);margin:0 18px}.events{margin:0 28px 12px}@media(max-width:800px){.summary-grid{grid-template-columns:1fr 1fr}}@media(max-width:520px){.summary-grid{grid-template-columns:1fr}}
</style>
