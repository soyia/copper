<template>
  <div class="page">
    <div class="panel posting-panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">业务过账</div>
          <div class="muted posting-subtitle">把订单承诺、库存、在途、应收应付及核销固化为可冲正、可追溯的业务事实；这里不是会计凭证。</div>
        </div>
        <el-button :loading="isFetching" @click="refreshAll">刷新</el-button>
      </div>

      <el-tabs v-model="activeTab">
        <el-tab-pane label="过账批次" name="batches">
          <div class="list-tools">
            <el-input v-model="keyword" clearable placeholder="过账号 / 来源单号 / 往来单位 / 操作人" />
            <el-select v-model="sourceType" clearable placeholder="全部来源" style="width: 190px">
              <el-option v-for="item in sourceOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <el-select v-model="status" clearable placeholder="全部状态" style="width: 140px">
              <el-option label="已过账" value="POSTED" />
              <el-option label="已被冲正" value="REVERSED" />
              <el-option label="过账中" value="POSTING" />
              <el-option label="失败" value="FAILED" />
            </el-select>
          </div>
          <el-table v-loading="batchesQuery.isLoading.value" :data="filteredBatches" border size="small" class="data-table" max-height="calc(100vh - 330px)">
            <el-table-column label="过账号" min-width="210" fixed="left">
              <template #default="{ row }"><RouterLink class="business-link" :to="`/business-postings/${row.id}`">{{ row.postingNo }}</RouterLink></template>
            </el-table-column>
            <el-table-column label="来源单据" min-width="185">
              <template #default="{ row }">
                <RouterLink v-if="sourceRoute(row.sourceType, row.sourceId)" class="business-link" :to="sourceRoute(row.sourceType, row.sourceId)!">{{ row.sourceNo || `${sourceTypeName(row.sourceType)} #${row.sourceId}` }}</RouterLink>
                <span v-else>{{ row.sourceNo || `${sourceTypeName(row.sourceType)} #${row.sourceId}` }}</span>
                <div class="multiline-secondary">{{ sourceTypeName(row.sourceType) }} · 第 {{ row.sourceRevisionNo }} 版</div>
              </template>
            </el-table-column>
            <el-table-column label="动作" width="145"><template #default="{ row }">{{ actionName(row.postingAction) }}</template></el-table-column>
            <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag size="small" :type="statusType(row.status)">{{ statusName(row.status) }}</el-tag></template></el-table-column>
            <el-table-column prop="businessDate" label="业务日期" width="115" />
            <el-table-column label="规则版本" min-width="180"><template #default="{ row }">{{ row.ruleCode }} / V{{ row.ruleVersionNo }}</template></el-table-column>
            <el-table-column label="往来单位" min-width="140"><template #default="{ row }">{{ row.partnerName || '-' }}</template></el-table-column>
            <el-table-column label="执行信息" min-width="175"><template #default="{ row }"><div>{{ row.operatorName || '系统' }}</div><div class="multiline-secondary">{{ formatDateTime(row.postedAt || row.createdAt) }}</div></template></el-table-column>
            <el-table-column label="来源" width="105"><template #default="{ row }"><el-tag v-if="row.legacyImported" size="small" effect="plain">历史补录</el-tag><span v-else>实时</span></template></el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`勾稽差异 ${reconciliation.length ? `(${reconciliation.length})` : ''}`" name="reconciliation">
          <el-alert v-if="!reconciliation.length" type="success" :closable="false" show-icon title="当前业务单据与过账事实未发现缺失或半完成批次" />
          <el-table v-else v-loading="reconciliationQuery.isLoading.value" :data="reconciliation" border size="small" class="data-table" max-height="calc(100vh - 286px)">
            <el-table-column label="来源单据" min-width="210" fixed="left"><template #default="{ row }"><RouterLink v-if="sourceRoute(row.sourceType, row.sourceId)" class="business-link" :to="sourceRoute(row.sourceType, row.sourceId)!">{{ row.sourceNo }}</RouterLink><span v-else>{{ row.sourceNo }}</span><div class="multiline-secondary">{{ sourceTypeName(row.sourceType) }} · {{ row.documentStatus }}</div></template></el-table-column>
            <el-table-column label="应有动作" width="145"><template #default="{ row }">{{ actionName(row.expectedAction) }}</template></el-table-column>
            <el-table-column prop="issueMessage" label="差异说明" min-width="330" />
            <el-table-column prop="issueCode" label="差异代码" min-width="190" />
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="规则版本" name="rules">
          <el-table v-loading="rulesQuery.isLoading.value" :data="rules" border size="small" class="data-table" max-height="calc(100vh - 286px)">
            <el-table-column prop="ruleName" label="规则" min-width="190" fixed="left" />
            <el-table-column label="来源 / 动作" min-width="210"><template #default="{ row }"><div>{{ sourceTypeName(row.sourceType) }}</div><div class="multiline-secondary">{{ actionName(row.postingAction) }}</div></template></el-table-column>
            <el-table-column label="版本" width="90"><template #default="{ row }">V{{ row.versionNo }}</template></el-table-column>
            <el-table-column prop="effectiveFrom" label="生效日期" width="115" />
            <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">{{ row.status === 'ENABLED' ? '已启用' : row.status }}</el-tag></template></el-table-column>
            <el-table-column label="业务事实" min-width="300"><template #default="{ row }">{{ ruleFacts(row.ruleJson) }}</template></el-table-column>
            <el-table-column prop="ruleCode" label="规则编码" min-width="230" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { api, type BusinessPostingBatch } from '@/api/client'
import { formatDateTime } from '@/api/format'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()
const queryClient = useQueryClient()
const activeTab = ref('batches')
const keyword = ref('')
const sourceType = ref('')
const status = ref('')
const batchesQuery = useQuery({ queryKey: computed(() => ['business-postings', sourceType.value, status.value]), queryFn: () => api.businessPostingBatches({ sourceType: sourceType.value || undefined, status: status.value || undefined }) })
const rulesQuery = useQuery({ queryKey: ['business-posting-rules'], queryFn: api.businessPostingRules })
const reconciliationQuery = useQuery({ queryKey: ['business-posting-reconciliation'], queryFn: api.businessPostingReconciliation })
const batches = computed(() => batchesQuery.data.value || [])
const rules = computed(() => rulesQuery.data.value || [])
const reconciliation = computed(() => reconciliationQuery.data.value || [])
const isFetching = computed(() => batchesQuery.isFetching.value || rulesQuery.isFetching.value || reconciliationQuery.isFetching.value)
const filteredBatches = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  if (!text) return batches.value
  return batches.value.filter((row) => [row.postingNo, row.sourceNo, row.partnerName, row.operatorName, row.ruleCode].some((value) => String(value ?? '').toLowerCase().includes(text)))
})
const sourceOptions = [
  ['PURCHASE_ORDER', '采购订单'], ['SALES_ORDER', '销售订单'], ['INBOUND', '采购入库'], ['OUTBOUND', '销售出库'],
  ['SALES_RETURN', '销售退货'], ['PURCHASE_RETURN', '采购退货'], ['INVENTORY_TRANSFER', '库存调拨'],
  ['INVENTORY_STOCKTAKE', '库存盘点'], ['INVENTORY_COST_ADJUSTMENT', '成本调整'], ['LANDED_COST_ALLOCATION', '采购费用分摊'], ['PAYMENT', '客户收款'],
  ['SUPPLIER_PAYMENT', '供应商付款'], ['RECEIVABLE_ADJUSTMENT', '应收调整'],
].map(([value, label]) => ({ value, label }))

async function refreshAll() {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: ['business-postings'] }),
    queryClient.invalidateQueries({ queryKey: ['business-posting-rules'] }),
    queryClient.invalidateQueries({ queryKey: ['business-posting-reconciliation'] }),
  ])
}
function sourceTypeName(value: string) { return Object.fromEntries(sourceOptions.map((item) => [item.value, item.label]))[value] || value }
function actionName(value: string) { return ({ COMMITMENT_POST: '承诺过账', COMMITMENT_REVERSE: '承诺冲回', POST: '确认过账', SHIP: '出库过账', SETTLE: '定价结算', DISPATCH: '调拨发出', RECEIVE: '调拨收货', ROLLBACK: '按当前结存回退', REVERSE: '冲正过账' } as Record<string, string>)[value] || value }
function statusName(value: string) { return ({ POSTED: '已过账', REVERSED: '已被冲正', POSTING: '过账中', FAILED: '失败' } as Record<string, string>)[value] || value }
function statusType(value: string) { return value === 'POSTED' ? 'success' : value === 'FAILED' ? 'danger' : value === 'POSTING' ? 'warning' : 'info' }
function ruleFacts(json: string) { try { const value = JSON.parse(json); return Array.isArray(value.facts) ? value.facts.map(factName).join('、') : value.reverseOf ? `冲回 ${Array.isArray(value.reverseOf) ? value.reverseOf.join('、') : value.reverseOf}` : '-' } catch { return '-' } }
function factName(value: string) { return ({ PURCHASE_COMMITMENT_QTY: '采购承诺数量', PURCHASE_COMMITMENT_AMOUNT: '采购承诺金额', SALES_COMMITMENT_QTY: '销售承诺数量', SALES_COMMITMENT_AMOUNT: '销售承诺金额', INVENTORY_ON_HAND: '实际结存', INVENTORY_IN_TRANSIT: '在途库存', INVENTORY_COST: '库存成本', LANDED_COST_CAPITALIZED: '采购附加费用资本化', LANDED_COST_RETURN_EXPENSE: '退货附加费用转成本', PAYABLE: '应付', RECEIVABLE: '应收', RECEIVABLE_SETTLEMENT: '应收核销', PAYABLE_SETTLEMENT: '应付核销', SALES_FULFILLMENT: '销售履约' } as Record<string, string>)[value] || value }
function sourceRoute(type: string, id: number) {
  const target = ({
    PURCHASE_ORDER: [`/purchase-orders/${id}`, 'purchase.view'], SALES_ORDER: [`/sales-orders/${id}`, 'sales.view'],
    INBOUND: [`/inbounds/${id}`, 'inbound.view'], OUTBOUND: [`/outbounds/${id}`, 'outbound.view'],
    SALES_RETURN: ['/outbounds', 'outbound.view'], PURCHASE_RETURN: [`/inventory/purchase-returns/${id}`, 'purchase.view'],
    INVENTORY_TRANSFER: [`/inventory/transfers/${id}`, 'inventory.view'], INVENTORY_STOCKTAKE: [`/inventory/stocktakes/${id}`, 'inventory.view'],
    INVENTORY_COST_ADJUSTMENT: [`/inventory/cost-adjustments/${id}`, 'inventory.cost.view'], LANDED_COST_ALLOCATION: [`/inventory/landed-cost-allocations/${id}`, 'landed.cost.view'], PAYMENT: ['/payments', 'finance.view'],
    SUPPLIER_PAYMENT: ['/supplier-payments', 'finance.view'], RECEIVABLE_ADJUSTMENT: ['/receivable-adjustments', 'finance.view'],
  } as Record<string, [string, string]>)[type]
  return target && appStore.hasPermission(target[1]) ? { path: target[0], query: { from: '/business-postings' } } : undefined
}
</script>

<style scoped>
.posting-subtitle { margin-top: 5px; font-size: 12px; }
.posting-panel :deep(.el-tabs__header) { margin-bottom: 16px; }
</style>
