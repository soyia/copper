<template>
  <div class="page" v-loading="query.isLoading.value">
    <template v-if="detail">
      <PageHeader :title="`业务过账 ${detail.batch.postingNo}`" back-to="/business-postings" />
      <el-alert type="info" :closable="false" show-icon class="detail-alert" title="业务过账固定经营事实，不等同于后续会计凭证；冲正会生成反向批次并保留原批次。" />
      <div class="panel">
        <div class="panel-title">批次信息</div>
        <el-descriptions :column="4" border size="small" class="detail-descriptions">
          <el-descriptions-item label="来源单据"><RouterLink v-if="sourceRoute" class="business-link" :to="sourceRoute">{{ detail.batch.sourceNo }}</RouterLink><span v-else>{{ detail.batch.sourceNo }}</span></el-descriptions-item>
          <el-descriptions-item label="动作">{{ actionName(detail.batch.postingAction) }}</el-descriptions-item>
          <el-descriptions-item label="状态"><el-tag size="small" :type="detail.batch.status === 'POSTED' ? 'success' : 'info'">{{ statusName(detail.batch.status) }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="业务日期">{{ detail.batch.businessDate }}</el-descriptions-item>
          <el-descriptions-item label="规则版本">{{ detail.batch.ruleCode }} / V{{ detail.batch.ruleVersionNo }}</el-descriptions-item>
          <el-descriptions-item label="订单版本">第 {{ detail.batch.sourceRevisionNo }} 版</el-descriptions-item>
          <el-descriptions-item label="往来单位">{{ detail.batch.partnerName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="执行人">{{ detail.batch.operatorName || '系统' }}</el-descriptions-item>
          <el-descriptions-item label="执行时间">{{ formatDateTime(detail.batch.postedAt || detail.batch.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="原因" :span="3">{{ detail.batch.reason || '-' }}</el-descriptions-item>
          <el-descriptions-item label="分录摘要" :span="4"><span class="hash-text">{{ detail.batch.entryHash }}</span></el-descriptions-item>
        </el-descriptions>
      </div>
      <div class="panel">
        <div class="panel-title">业务事实分录</div>
        <el-table :data="detail.entries" border size="small" class="data-table">
          <el-table-column prop="lineNo" label="行" width="60" />
          <el-table-column label="业务事实" min-width="170"><template #default="{ row }">{{ factName(row.factType) }}</template></el-table-column>
          <el-table-column label="方向" width="100"><template #default="{ row }"><el-tag size="small" :type="row.direction === 'INCREASE' ? 'success' : 'danger'">{{ row.direction === 'INCREASE' ? '增加' : '减少' }}</el-tag></template></el-table-column>
          <el-table-column prop="productName" label="商品" min-width="150"><template #default="{ row }">{{ row.productName || '-' }}</template></el-table-column>
          <el-table-column prop="warehouseName" label="仓库" min-width="120"><template #default="{ row }">{{ row.warehouseName || '-' }}</template></el-table-column>
          <el-table-column prop="inventoryBatchNo" label="批次" min-width="145"><template #default="{ row }">{{ row.inventoryBatchNo || '-' }}</template></el-table-column>
          <el-table-column label="数量" width="130" align="right"><template #default="{ row }">{{ Number(row.quantity) ? `${quantity(row.quantity)} ${row.quantityUnit}` : '-' }}</template></el-table-column>
          <el-table-column label="金额" width="130" align="right"><template #default="{ row }">{{ Number(row.amount) ? money(row.amount) : '-' }}</template></el-table-column>
          <el-table-column prop="memo" label="说明" min-width="220" />
        </el-table>
      </div>
      <div class="panel">
        <div class="panel-title">状态事件</div>
        <el-timeline class="posting-timeline">
          <el-timeline-item v-for="event in detail.events" :key="event.id" :timestamp="formatDateTime(event.createdAt)" placement="top">
            <div>{{ eventName(event.action) }} · {{ event.operatorName || '系统' }}</div>
            <div v-if="event.reason" class="muted">{{ event.reason }}</div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { api } from '@/api/client'
import { formatDateTime, money, quantity } from '@/api/format'
import PageHeader from '@/components/PageHeader.vue'
import { useAppStore } from '@/stores/app'

const route = useRoute()
const appStore = useAppStore()
const id = Number(route.params.id)
const query = useQuery({ queryKey: ['business-posting', id], queryFn: () => api.businessPostingDetail(id) })
const detail = computed(() => query.data.value)
const sourceRoute = computed(() => {
  if (!detail.value) return undefined
  const value = detail.value.batch
  const target = ({ PURCHASE_ORDER: [`/purchase-orders/${value.sourceId}`, 'purchase.view'], SALES_ORDER: [`/sales-orders/${value.sourceId}`, 'sales.view'], INBOUND: [`/inbounds/${value.sourceId}`, 'inbound.view'], OUTBOUND: [`/outbounds/${value.sourceId}`, 'outbound.view'], PURCHASE_RETURN: [`/inventory/purchase-returns/${value.sourceId}`, 'purchase.view'], INVENTORY_TRANSFER: [`/inventory/transfers/${value.sourceId}`, 'inventory.view'], INVENTORY_STOCKTAKE: [`/inventory/stocktakes/${value.sourceId}`, 'inventory.view'], INVENTORY_COST_ADJUSTMENT: [`/inventory/cost-adjustments/${value.sourceId}`, 'inventory.cost.view'], LANDED_COST_ALLOCATION: [`/inventory/landed-cost-allocations/${value.sourceId}`, 'landed.cost.view'], PAYMENT: ['/payments', 'finance.view'], SUPPLIER_PAYMENT: ['/supplier-payments', 'finance.view'], RECEIVABLE_ADJUSTMENT: ['/receivable-adjustments', 'finance.view'] } as Record<string, [string, string]>)[value.sourceType]
  return target && appStore.hasPermission(target[1]) ? { path: target[0], query: { from: route.fullPath } } : undefined
})
function actionName(value: string) { return ({ COMMITMENT_POST: '承诺过账', COMMITMENT_REVERSE: '承诺冲回', POST: '确认过账', SHIP: '销售出库', SETTLE: '定价结算', DISPATCH: '调拨发出', RECEIVE: '调拨收货', REVERSE: '冲正过账' } as Record<string, string>)[value] || value }
function statusName(value: string) { return ({ POSTED: '已过账', REVERSED: '已被冲正', POSTING: '过账中', FAILED: '失败' } as Record<string, string>)[value] || value }
function factName(value: string) { return ({ PURCHASE_COMMITMENT_QTY: '采购承诺数量', PURCHASE_COMMITMENT_AMOUNT: '采购承诺金额', SALES_COMMITMENT_QTY: '销售承诺数量', SALES_COMMITMENT_AMOUNT: '销售承诺金额', INVENTORY_ON_HAND: '实际结存', INVENTORY_IN_TRANSIT: '在途库存', INVENTORY_COST: '库存成本', SALES_FULFILLMENT: '销售履约', PAYABLE: '应付', RECEIVABLE: '应收', RECEIVABLE_SETTLEMENT: '应收核销', PAYABLE_SETTLEMENT: '应付核销', RECEIVABLE_ADJUSTMENT: '应收调整', CUSTOMER_ADVANCE: '客户预收', SUPPLIER_PREPAY: '供应商预付', CUSTOMER_ADVANCE_SETTLEMENT: '预收余额核销', CUSTOMER_REWARD_SETTLEMENT: '奖励余额核销', SUPPLIER_PREPAY_SETTLEMENT: '预付余额核销', SUPPLIER_REWARD_SETTLEMENT: '返利余额核销', CUSTOMER_ADVANCE_OPENING: '客户预收期初', SUPPLIER_PREPAY_OPENING: '供应商预付期初', CUSTOMER_REWARD_GRANT: '客户奖励确认', SUPPLIER_REBATE_GRANT: '供应商返利确认', CUSTOMER_ADVANCE_CORRECTION: '客户预收差错', CUSTOMER_REWARD_CORRECTION: '客户奖励差错', SUPPLIER_PREPAY_CORRECTION: '供应商预付差错', SUPPLIER_REWARD_CORRECTION: '供应商返利差错' } as Record<string, string>)[value] || value }
function eventName(value: string) { return ({ BEGIN: '开始过账', COMPLETE: '过账完成', REVERSE: '被反向批次冲正' } as Record<string, string>)[value] || value }
</script>

<style scoped>
.detail-alert { margin-bottom: 14px; }
.detail-descriptions { margin-top: 14px; }
.hash-text { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; overflow-wrap: anywhere; }
.posting-timeline { margin-top: 18px; }
</style>
