<template>
  <div class="page">
    <div class="panel inventory-detail-panel" v-loading="isFetching">
      <PageHeader :title="pageTitle" :back-to="backTo">
        <template v-if="detail">
          <el-button plain :loading="printing" @click="printInventoryDocument">打印 / 导出 PDF</el-button>
          <RouterLink v-if="canCancel" :to="editRoute"><el-button>编辑草稿</el-button></RouterLink>
          <el-button v-if="canCancel" :loading="acting" @click="reasonAction('cancel')">作废草稿</el-button>
          <el-button v-if="canReverse" :loading="acting" type="danger" plain @click="reasonAction('reverse')">冲正</el-button>
          <el-button v-if="canPrimary" :loading="acting" type="primary" @click="primaryAction">{{ primaryText }}</el-button>
        </template>
      </PageHeader>
      <template v-if="detail">
        <el-alert v-if="status === 'CANCELLED' && detail.cancelReason" :title="`作废原因：${detail.cancelReason}`" type="info" :closable="false" class="detail-alert" />
        <el-alert v-if="status === 'REVERSED' && detail.reversalReason" :title="`冲正原因：${detail.reversalReason}`" type="warning" :closable="false" class="detail-alert" />
        <el-descriptions :column="4" border size="small" class="detail-descriptions">
          <el-descriptions-item label="单号">{{ documentNo }}</el-descriptions-item>
          <el-descriptions-item label="业务日期">{{ header.businessDate }}</el-descriptions-item>
          <el-descriptions-item label="状态"><el-tag :type="inventoryStatusTag(status)">{{ inventoryStatusText[status] || status }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatDateTime(header.createdAt) }}</el-descriptions-item>
          <template v-if="kind === 'transfer'">
            <el-descriptions-item label="调出仓">{{ header.fromWarehouseName }}</el-descriptions-item>
            <el-descriptions-item label="调入仓">{{ header.toWarehouseName }}</el-descriptions-item>
            <el-descriptions-item label="预计到达">{{ header.expectedArrivalDate || '-' }}</el-descriptions-item>
            <el-descriptions-item label="调拨数量">{{ quantity(header.totalQuantity) }}</el-descriptions-item>
            <el-descriptions-item v-if="appStore.hasPermission('inventory.cost.view')" label="调拨成本">{{ money(header.totalCost) }}</el-descriptions-item>
          </template>
          <template v-else-if="kind === 'stocktake'">
            <el-descriptions-item label="盘点仓库">{{ header.warehouseName }}</el-descriptions-item>
            <el-descriptions-item label="盘盈">{{ quantity(header.totalGainQuantity) }}</el-descriptions-item>
            <el-descriptions-item label="盘亏">{{ quantity(header.totalLossQuantity) }}</el-descriptions-item>
            <el-descriptions-item v-if="appStore.hasPermission('inventory.cost.view')" label="成本差额">{{ money(header.totalCostDifference) }}</el-descriptions-item>
          </template>
          <template v-else-if="kind === 'purchase-return'">
            <el-descriptions-item label="供应商">{{ header.supplierName }}</el-descriptions-item>
            <el-descriptions-item label="退货仓库">{{ header.warehouseName }}</el-descriptions-item>
            <el-descriptions-item label="原入库单"><RouterLink class="business-link" :to="sourceInboundRoute">{{ header.inboundNo }}</RouterLink></el-descriptions-item>
            <el-descriptions-item label="退货金额">{{ money(header.totalAmount) }}</el-descriptions-item>
            <el-descriptions-item label="价格口径">{{ header.priceIncludesTax ? '含税价' : '未税价' }} / {{ header.taxRateSummary }}</el-descriptions-item>
            <el-descriptions-item label="未税 / 税额">{{ money(header.amountExcludingTax) }} / {{ money(header.taxAmount) }}</el-descriptions-item>
            <el-descriptions-item label="应付冲减">{{ money(header.payableReductionAmount) }}</el-descriptions-item>
            <el-descriptions-item label="转供应商预付">{{ money(header.supplierCreditAmount) }}</el-descriptions-item>
            <el-descriptions-item label="退货原因" :span="2">{{ header.reason }}</el-descriptions-item>
          </template>
          <template v-else>
            <el-descriptions-item label="计价方法">批次实际成本法</el-descriptions-item>
            <el-descriptions-item label="成本差额">{{ money(header.totalDifferenceAmount) }}</el-descriptions-item>
            <el-descriptions-item label="调整原因" :span="2">{{ header.reason }}</el-descriptions-item>
          </template>
          <el-descriptions-item label="备注" :span="4">{{ header.memo || '-' }}</el-descriptions-item>
        </el-descriptions>

        <div class="detail-section-title">商品明细</div>
        <el-table :data="detail.items" border size="small">
          <el-table-column prop="sku" label="SKU" min-width="140" fixed="left" />
          <el-table-column prop="productName" label="商品" min-width="160" fixed="left" />
          <el-table-column prop="spec" label="规格摘要" min-width="220" />
          <template v-if="kind === 'transfer'">
            <el-table-column prop="sourceBatchNo" label="调出批次" min-width="150" />
            <el-table-column prop="targetBatchNo" label="调入批次" min-width="170"><template #default="{ row }">{{ row.targetBatchNo || '待收货' }}</template></el-table-column>
            <el-table-column label="数量" width="150" align="right"><template #default="{ row }">{{ quantity(row.quantity) }} {{ row.baseUnit }}</template></el-table-column>
            <el-table-column v-if="appStore.hasPermission('inventory.cost.view')" label="单位成本" width="125" align="right"><template #default="{ row }">{{ money(row.unitCost, 4) }}</template></el-table-column>
            <el-table-column v-if="appStore.hasPermission('inventory.cost.view')" label="成本金额" width="130" align="right"><template #default="{ row }">{{ money(row.amountCost) }}</template></el-table-column>
          </template>
          <template v-else-if="kind === 'stocktake'">
            <el-table-column prop="batchNo" label="批次" min-width="150" />
            <el-table-column label="账面" width="140" align="right"><template #default="{ row }">{{ quantity(row.systemQuantity) }} {{ row.baseUnit }}</template></el-table-column>
            <el-table-column label="实盘" width="140" align="right"><template #default="{ row }">{{ quantity(row.countedQuantity) }} {{ row.baseUnit }}</template></el-table-column>
            <el-table-column label="差异" width="130" align="right"><template #default="{ row }"><span :class="Number(row.differenceQuantity) < 0 ? 'amount-negative' : 'amount-positive'">{{ signed(row.differenceQuantity) }}</span></template></el-table-column>
            <el-table-column prop="differenceReason" label="差异原因" min-width="220" />
          </template>
          <template v-else-if="kind === 'purchase-return'">
            <el-table-column prop="batchNo" label="退货批次" min-width="150" />
            <el-table-column label="数量" width="150" align="right"><template #default="{ row }">{{ quantity(row.quantity) }} {{ row.baseUnit }}</template></el-table-column>
            <el-table-column label="原入库结算单价" width="145" align="right"><template #default="{ row }">{{ money(row.payableUnitCost, 4) }}</template></el-table-column>
            <el-table-column label="税率" width="90" align="right"><template #default="{ row }">{{ row.taxRate }}%</template></el-table-column>
            <el-table-column label="未税" width="120" align="right"><template #default="{ row }">{{ money(row.amountExcludingTax) }}</template></el-table-column>
            <el-table-column label="税额" width="110" align="right"><template #default="{ row }">{{ money(row.taxAmount) }}</template></el-table-column>
            <el-table-column label="应付冲减额" width="140" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
            <el-table-column v-if="appStore.hasPermission('inventory.cost.view')" label="库存成本金额" width="140" align="right"><template #default="{ row }">{{ money(row.inventoryAmountCost) }}</template></el-table-column>
          </template>
          <template v-else>
            <el-table-column prop="warehouseName" label="仓库" width="130" />
            <el-table-column prop="batchNo" label="批次" min-width="150" />
            <el-table-column label="结存数量" width="130" align="right"><template #default="{ row }">{{ quantity(row.quantitySnapshot) }}</template></el-table-column>
            <el-table-column label="原成本" width="125" align="right"><template #default="{ row }">{{ money(row.oldUnitCost, 4) }}</template></el-table-column>
            <el-table-column label="新成本" width="125" align="right"><template #default="{ row }">{{ money(row.newUnitCost, 4) }}</template></el-table-column>
            <el-table-column label="成本差额" width="135" align="right"><template #default="{ row }">{{ money(row.differenceAmount) }}</template></el-table-column>
          </template>
        </el-table>

        <div class="detail-section-title">操作记录</div>
        <el-timeline class="inventory-events">
          <el-timeline-item v-for="event in detail.events" :key="event.id" :timestamp="formatDateTime(event.createdAt)" placement="top">
            <div>{{ actionText(event.action) }} · {{ event.operatorName || '系统用户' }}</div>
            <div v-if="event.reason" class="muted">{{ event.reason }}</div>
          </el-timeline-item>
        </el-timeline>
      </template>
      <el-empty v-else-if="!isFetching" description="单据不存在或无权访问" />
    </div>
    <section v-if="detail" class="print-sheet a4-print-sheet">
      <div class="document-print-heading">
        <div class="document-print-company">{{ appStore.settings.companyName }}</div>
        <div class="document-print-title">{{ config.detailTitle }}{{ status === 'DRAFT' ? '（草稿）' : '' }}</div>
        <div class="document-print-subtitle">
          <span v-if="appStore.settings.companyPhone">电话：{{ appStore.settings.companyPhone }}</span>
          <span>打印时间：{{ printTime }}</span>
        </div>
      </div>
      <table class="document-print-meta"><tbody>
        <tr><td>单据编号：{{ documentNo }}</td><td>业务日期：{{ header.businessDate }}</td><td>状态：{{ inventoryStatusText[status] || status }}</td></tr>
        <tr v-if="kind === 'transfer'"><td>调出仓：{{ header.fromWarehouseName }}</td><td>调入仓：{{ header.toWarehouseName }}</td><td>调拨数量：{{ quantity(header.totalQuantity) }}</td></tr>
        <tr v-else-if="kind === 'stocktake'"><td>盘点仓库：{{ header.warehouseName }}</td><td>盘盈：{{ quantity(header.totalGainQuantity) }}</td><td>盘亏：{{ quantity(header.totalLossQuantity) }}</td></tr>
        <tr v-else-if="kind === 'purchase-return'"><td>供应商：{{ header.supplierName }}</td><td>退货仓库：{{ header.warehouseName }}</td><td>原入库单：{{ header.inboundNo }}</td></tr>
        <tr v-else><td>计价方法：批次实际成本法</td><td>成本差额：{{ money(header.totalDifferenceAmount) }}</td><td>调整原因：{{ header.reason }}</td></tr>
        <tr><td colspan="3">备注 / 原因：{{ header.reason || header.memo || '无' }}</td></tr>
      </tbody></table>
      <table class="document-print-table">
        <thead>
          <tr v-if="kind === 'transfer'"><th>#</th><th>商品 / SKU</th><th>批次</th><th>数量</th><template v-if="canViewCost"><th>单位成本</th><th>成本金额</th></template></tr>
          <tr v-else-if="kind === 'stocktake'"><th>#</th><th>商品 / SKU</th><th>批次</th><th>账面</th><th>实盘</th><th>差异 / 原因</th></tr>
          <tr v-else-if="kind === 'purchase-return'"><th>#</th><th>商品 / SKU</th><th>批次</th><th>数量</th><th>结算单价</th><th>应付冲减</th></tr>
          <tr v-else><th>#</th><th>商品 / SKU</th><th>仓库 / 批次</th><th>结存数量</th><th>原成本 → 新成本</th><th>成本差额</th></tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in detail.items" :key="row.id">
            <td class="center">{{ index + 1 }}</td>
            <td>{{ row.productName }} / {{ row.sku }}<br>{{ row.spec || '-' }}</td>
            <template v-if="kind === 'transfer'"><td>{{ row.sourceBatchNo }} → {{ row.targetBatchNo || '待收货' }}</td><td class="right">{{ quantity(row.quantity) }} {{ row.baseUnit }}</td><template v-if="canViewCost"><td class="right">{{ money(row.unitCost, 4) }}</td><td class="right">{{ money(row.amountCost) }}</td></template></template>
            <template v-else-if="kind === 'stocktake'"><td>{{ row.batchNo }}</td><td class="right">{{ quantity(row.systemQuantity) }} {{ row.baseUnit }}</td><td class="right">{{ quantity(row.countedQuantity) }} {{ row.baseUnit }}</td><td>{{ signed(row.differenceQuantity) }} · {{ row.differenceReason || '-' }}</td></template>
            <template v-else-if="kind === 'purchase-return'"><td>{{ row.batchNo }}</td><td class="right">{{ quantity(row.quantity) }} {{ row.baseUnit }}</td><td class="right">{{ money(row.payableUnitCost, 4) }}</td><td class="right">{{ money(row.amount) }}</td></template>
            <template v-else><td>{{ row.warehouseName }} / {{ row.batchNo }}</td><td class="right">{{ quantity(row.quantitySnapshot) }}</td><td class="right">{{ money(row.oldUnitCost, 4) }} → {{ money(row.newUnitCost, 4) }}</td><td class="right">{{ money(row.differenceAmount) }}</td></template>
          </tr>
        </tbody>
      </table>
      <table class="document-print-total"><tbody>
        <tr v-if="kind === 'purchase-return'"><td>退货金额：{{ money(header.totalAmount) }}</td><td>应付冲减：{{ money(header.payableReductionAmount) }}</td><td>转供应商预付：{{ money(header.supplierCreditAmount) }}</td></tr>
        <tr v-else-if="kind === 'cost-adjustment'"><td colspan="3">成本调整合计：{{ money(header.totalDifferenceAmount) }}</td></tr>
        <tr v-else><td colspan="3">制单时间：{{ formatDateTime(header.createdAt) }}</td></tr>
      </tbody></table>
      <div class="document-print-audit-title">审计轨迹</div>
      <table class="document-print-table document-print-audit"><thead><tr><th>#</th><th>动作</th><th>操作人</th><th>时间</th><th>原因 / 说明</th></tr></thead><tbody>
        <tr v-for="(event, index) in detail.events" :key="event.id"><td class="center">{{ index + 1 }}</td><td>{{ actionText(event.action) }}</td><td>{{ event.operatorName || '系统用户' }}</td><td>{{ formatDateTime(event.createdAt) }}</td><td>{{ event.reason || '-' }}</td></tr>
      </tbody></table>
      <div class="document-print-hash">打印数据摘要（SHA-256）：{{ snapshotHash }}</div>
      <div class="document-print-signatures"><span>制单：</span><span>{{ kind === 'stocktake' ? '盘点人：' : '经办人：' }}</span><span>{{ kind === 'purchase-return' ? '供应商签章：' : '仓库 / 财务复核：' }}</span><span>负责人审核：</span></div>
    </section>
    <EvidenceAttachments
      v-if="detail"
      :document-type="evidenceDocumentType"
      :document-id="id"
      :title="`${config.detailTitle}签字、审批与留存附件`"
      default-evidence-type="SIGNED_STAMPED_COPY"
      show-output-history
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type EvidenceDocumentType } from '@/api/client'
import { formatDateTime, money, quantity } from '@/api/format'
import PageHeader from '@/components/PageHeader.vue'
import EvidenceAttachments from '@/components/EvidenceAttachments.vue'
import { useAuditedDocumentPrint } from '@/composables/useAuditedDocumentPrint'
import { printFileName } from '@/composables/useDocumentPrint'
import { useAppStore } from '@/stores/app'
import { inventoryDocumentConfig, inventoryStatusTag, inventoryStatusText, type InventoryDocumentKind } from '@/utils/inventoryDocuments'
import { resolveRiskException } from '@/utils/riskException'

const props = defineProps<{ kind: InventoryDocumentKind }>()
const route = useRoute()
const appStore = useAppStore()
const queryClient = useQueryClient()
const acting = ref(false)
const id = computed(() => Number(route.params.id))
const kind = computed(() => props.kind)
const config = computed(() => inventoryDocumentConfig[props.kind])
const evidenceDocumentType = computed<EvidenceDocumentType>(() => ({
  transfer: 'INVENTORY_TRANSFER',
  stocktake: 'INVENTORY_STOCKTAKE',
  'purchase-return': 'PURCHASE_RETURN',
  'cost-adjustment': 'INVENTORY_COST_ADJUSTMENT',
})[props.kind] as EvidenceDocumentType)
const backTo = computed(() => typeof route.query.from === 'string' ? route.query.from : config.value.listPath)

const query = useQuery<any>({
  queryKey: computed(() => ['inventory-document-detail', props.kind, id.value]),
  queryFn: () => {
    if (props.kind === 'transfer') return api.inventoryTransfer(id.value)
    if (props.kind === 'stocktake') return api.inventoryStocktake(id.value)
    if (props.kind === 'purchase-return') return api.purchaseReturn(id.value)
    return api.inventoryCostAdjustment(id.value)
  },
})
const detail = computed<any>(() => query.data.value)
const isFetching = computed(() => query.isFetching.value)
const header = computed<any>(() => detail.value?.header || {})
const status = computed(() => String(header.value.status || ''))
const canViewCost = computed(() => appStore.hasPermission('inventory.cost.view'))
const documentNo = computed(() => String(header.value[config.value.numberKey] || `#${id.value}`))
const pageTitle = computed(() => `${config.value.detailTitle} ${documentNo.value}`)
const canCancel = computed(() => status.value === 'DRAFT' && appStore.hasPermission(config.value.permission))
const canReverse = computed(() => appStore.hasPermission(config.value.permission) && (props.kind === 'transfer'
  ? ['IN_TRANSIT', 'COMPLETED'].includes(status.value)
  : status.value === 'CONFIRMED'))
const canPrimary = computed(() => appStore.hasPermission(config.value.permission) && (status.value === 'DRAFT' || (props.kind === 'transfer' && status.value === 'IN_TRANSIT')))
const primaryText = computed(() => {
  if (props.kind === 'transfer') return status.value === 'DRAFT' ? '确认发出' : '确认收货'
  return props.kind === 'stocktake' ? '确认盘点' : props.kind === 'purchase-return' ? '确认退货' : '确认调整'
})
const sourceInboundRoute = computed(() => ({ path: `/inbounds/${header.value.inboundId}`, query: { from: route.fullPath } }))
const editRoute = computed(() => ({ path: `${config.value.listPath}/${id.value}/edit`, query: { from: route.fullPath } }))
const { printTime, snapshotHash, printing, printAndRecord } = useAuditedDocumentPrint(evidenceDocumentType.value, () => id.value)

async function printInventoryDocument() {
  if (!detail.value) return
  const owner = props.kind === 'purchase-return' ? header.value.supplierName
    : props.kind === 'transfer' ? `${header.value.fromWarehouseName}-${header.value.toWarehouseName}`
      : header.value.warehouseName || '库存'
  await printAndRecord(
    printFileName(owner || '库存', config.value.detailTitle, documentNo.value),
    detail.value,
    `copper-inventory-${props.kind}-print/v1`,
  )
}

async function primaryAction() {
  const warning = props.kind === 'transfer'
    ? (status.value === 'DRAFT' ? '发出后库存将离开调出仓并进入在途。确认发出？' : '收货后库存将进入调入仓。确认收货？')
    : props.kind === 'stocktake' ? '确认后盘盈盘亏将立即改变库存数量和成本。'
      : props.kind === 'purchase-return' ? '确认后将扣减库存并冲减供应商应付。'
        : '确认后将改变批次结存成本；不会重算历史出库。'
  try {
    await ElMessageBox.confirm(warning, primaryText.value, { type: 'warning', confirmButtonText: primaryText.value })
    let authorization: { allowed: boolean; reason?: string; approvalId?: number } = { allowed: true }
    if (props.kind === 'stocktake' || props.kind === 'cost-adjustment') {
      authorization = await resolveRiskException(
        props.kind === 'stocktake' ? 'INVENTORY_STOCKTAKE' : 'INVENTORY_COST_ADJUSTMENT',
        id.value, 'CONFIRM', primaryText.value,
      )
      if (!authorization.allowed) return
    }
    acting.value = true
    if (props.kind === 'transfer') status.value === 'DRAFT' ? await api.dispatchInventoryTransfer(id.value) : await api.receiveInventoryTransfer(id.value)
    else if (props.kind === 'stocktake') await api.confirmInventoryStocktake(id.value, authorization)
    else if (props.kind === 'purchase-return') await api.confirmPurchaseReturn(id.value)
    else await api.confirmInventoryCostAdjustment(id.value, authorization)
    ElMessage.success('操作成功')
    await Promise.all([query.refetch(), queryClient.invalidateQueries({ queryKey: ['inventory-atp'] })])
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '操作失败')
  } finally { acting.value = false }
}

async function reasonAction(action: 'cancel' | 'reverse') {
  try {
    const result = await ElMessageBox.prompt(action === 'cancel' ? '请填写作废原因' : '冲正会生成反向库存和往来流水，请填写原因',
      action === 'cancel' ? '作废草稿' : '冲正单据', { inputValidator: (value) => Boolean(value?.trim()) || '原因不能为空', inputType: 'textarea', type: 'warning' })
    acting.value = true
    const reason = result.value.trim()
    let authorization: { allowed: boolean; reason?: string; approvalId?: number } = { allowed: true }
    if (action === 'reverse' && (props.kind === 'stocktake' || props.kind === 'cost-adjustment')) {
      authorization = await resolveRiskException(
        props.kind === 'stocktake' ? 'INVENTORY_STOCKTAKE' : 'INVENTORY_COST_ADJUSTMENT',
        id.value, 'REVERSE', '冲正', reason,
      )
      if (!authorization.allowed) return
    }
    if (props.kind === 'transfer') action === 'cancel' ? await api.cancelInventoryTransfer(id.value, reason) : await api.reverseInventoryTransfer(id.value, reason)
    else if (props.kind === 'stocktake') action === 'cancel' ? await api.cancelInventoryStocktake(id.value, reason) : await api.reverseInventoryStocktake(id.value, reason, authorization.approvalId)
    else if (props.kind === 'purchase-return') action === 'cancel' ? await api.cancelPurchaseReturn(id.value, reason) : await api.reversePurchaseReturn(id.value, reason)
    else action === 'cancel' ? await api.cancelInventoryCostAdjustment(id.value, reason) : await api.reverseInventoryCostAdjustment(id.value, reason, authorization.approvalId)
    ElMessage.success(action === 'cancel' ? '草稿已作废' : '单据已冲正')
    await Promise.all([query.refetch(), queryClient.invalidateQueries({ queryKey: ['inventory-atp'] })])
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '操作失败')
  } finally { acting.value = false }
}

function signed(value: number) { return `${Number(value) > 0 ? '+' : ''}${quantity(value)}` }
function actionText(value: string) { return ({ CREATE: '创建草稿', UPDATE: '修改草稿', DISPATCH: '确认发出', RECEIVE: '确认收货', CONFIRM: '确认过账', CANCEL: '作废草稿', REVERSE: '冲正' } as Record<string, string>)[value] || value }
</script>

<style scoped>
.inventory-detail-panel { max-width: 1480px; margin: 0 auto; }
.detail-alert { margin: 0 18px 14px; }
.detail-descriptions { margin: 0 18px; }
.detail-section-title { margin: 22px 18px 10px; font-size: 15px; font-weight: 650; }
.inventory-detail-panel > :deep(.el-table) { width: calc(100% - 36px); margin: 0 18px; }
.inventory-events { margin: 0 28px 10px; }
.amount-negative { color: var(--el-color-danger); }
.amount-positive { color: var(--el-color-success); }
</style>
