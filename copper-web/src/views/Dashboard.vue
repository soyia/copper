<template>
  <div v-loading="dashboardQuery.isFetching.value" class="page owner-dashboard">
    <div class="dashboard-heading">
      <div>
        <div class="page-title">老板工作台</div>
        <div class="muted">先处理影响收钱、付款、发货、对账和关账的事项。</div>
      </div>
      <el-button :loading="dashboardQuery.isFetching.value" @click="dashboardQuery.refetch()">刷新</el-button>
    </div>

    <el-alert v-if="dashboardQuery.isError.value" type="error" :closable="false" show-icon title="经营待办读取失败，请刷新重试；本页不会用旧数字冒充当前结果。" />
    <el-alert v-else-if="dashboard && !dashboard.ownerAttention.completeScope" type="warning" :closable="false" show-icon title="当前账号只拥有部分业务数据范围，系统不展示可能失真的经营待办汇总。请由拥有全量数据范围的负责人查看。" />

    <section v-if="dashboard?.ownerAttention.completeScope" class="attention-section" aria-label="经营待办">
      <div class="section-heading">
        <div><div class="section-title">今天先看这些</div><div class="muted">数字来自完整业务范围；点击直接进入对应处理清单。</div></div>
        <el-tag :type="urgentTaskCount ? 'danger' : 'success'">{{ urgentTaskCount ? `${urgentTaskCount} 类高优先级` : '暂无硬性异常' }}</el-tag>
      </div>
      <div class="attention-grid">
        <button v-for="item in attentionCards" :key="item.key" type="button" class="attention-card" :class="item.level" @click="router.push(item.to)">
          <div class="attention-card-head"><span>{{ item.label }}</span><el-tag size="small" :type="item.tagType">{{ item.badge }}</el-tag></div>
          <strong class="attention-count">{{ item.countText }}</strong>
          <div v-if="item.amountText" class="attention-amount">{{ item.amountText }}</div>
          <div class="attention-note">{{ item.note }}</div>
          <div class="attention-action">{{ item.action }} <span>→</span></div>
        </button>
      </div>
    </section>

    <section class="overview-section">
      <div class="section-title">经营概览</div>
      <div class="kpi-grid overview-grid">
        <div v-for="item in kpis" :key="item.label" class="kpi">
          <div class="kpi-label">{{ item.label }}</div>
          <div class="kpi-value">{{ item.value }}</div>
          <div class="kpi-note">{{ item.note }}</div>
        </div>
      </div>
    </section>

    <div v-if="canViewFulfillmentReminders" class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">近期收发货安排</div>
          <div class="muted">仅显示今天至未来 7 日仍未完成的有效单据；直运采购不作为到货入库提醒。</div>
        </div>
      </div>
      <el-empty v-if="!fulfillmentReminders.length" description="未来 7 日暂无待到货或待出库安排" :image-size="56" />
      <el-table v-else :data="fulfillmentReminders" class="data-table" size="small" border show-overflow-tooltip max-height="360">
        <el-table-column label="提醒" width="125">
          <template #default="{ row }"><el-tag :type="reminderTagType(row.timingBucket)">{{ reminderLabel(row) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="plannedDate" label="计划日期" width="115" />
        <el-table-column label="单据号" min-width="175">
          <template #default="{ row }"><router-link class="table-link" :to="fulfillmentRoute(row)">{{ row.orderNo }}</router-link></template>
        </el-table-column>
        <el-table-column prop="partnerName" label="往来单位" min-width="140" />
        <el-table-column label="待处理" width="105" align="right"><template #default="{ row }">{{ row.remainingLineCount }} 项</template></el-table-column>
        <el-table-column label="计划仓库" min-width="120"><template #default="{ row }">{{ row.warehouseName || '未指定' }}</template></el-table-column>
      </el-table>
    </div>

    <div class="panel">
      <div class="panel-header"><div class="panel-title">常用开单</div></div>
      <div class="panel-body quick-actions">
        <el-button v-if="appStore.hasPermission('purchase.manage')" class="action-button" plain @click="router.push('/purchase-orders/new')">新建采购订单</el-button>
        <el-button v-if="appStore.hasPermission('sales.manage')" class="action-button" type="primary" plain @click="router.push('/sales-orders/new')">新建销售订单</el-button>
        <el-button v-if="appStore.hasPermission('inbound.manage')" class="action-button" type="success" plain @click="router.push('/inbounds/new')">新建入库</el-button>
        <el-button v-if="appStore.hasPermission('outbound.manage')" class="action-button" type="warning" plain @click="router.push('/outbounds/new')">新建出库</el-button>
      </div>
    </div>

    <div class="work-grid">
      <div v-if="appStore.hasPermission('inventory.view')" class="panel">
        <div class="panel-header"><div class="panel-title">可用量较低批次</div><el-button text @click="router.push('/inventory')">查看库存</el-button></div>
        <el-table :data="dashboard?.inventoryAlerts ?? []" class="data-table" size="small" border show-overflow-tooltip height="286">
          <el-table-column prop="productName" label="商品" min-width="120" fixed="left" />
          <el-table-column prop="warehouseName" label="仓库" width="90" />
          <el-table-column prop="batchNo" label="批次号" min-width="130" />
          <el-table-column label="可用量" width="110" align="right"><template #default="{ row }">{{ quantity(row.quantityAvailable) }}</template></el-table-column>
          <el-table-column v-if="appStore.hasPermission('inventory.cost.view')" label="成本单价" width="100" align="right"><template #default="{ row }">{{ money(row.unitCost, 4) }}</template></el-table-column>
        </el-table>
      </div>

      <div v-if="appStore.hasPermission('finance.view')" class="panel">
        <div class="panel-header"><div class="panel-title">近期未结应收</div><el-button text @click="router.push('/receivables')">查看应收</el-button></div>
        <el-table :data="dashboard?.recentReceivables ?? []" class="data-table" size="small" border show-overflow-tooltip height="286">
          <el-table-column prop="customerName" label="客户" min-width="120" fixed="left" />
          <el-table-column label="来源单据" min-width="160"><template #default="{ row }"><router-link class="table-link" :to="receivableDetailRoute(row)">{{ row.sourceNo }}</router-link></template></el-table-column>
          <el-table-column prop="dueDate" label="到期日" width="110" />
          <el-table-column label="待收金额" width="120" align="right"><template #default="{ row }"><span :class="{ 'text-danger': row.overdueDays > 0 }">{{ money(row.unpaidAmount) }}</span></template></el-table-column>
          <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="row.overdueDays > 0 ? 'danger' : paymentStatusType(row.paymentStatus)" size="small">{{ row.overdueDays > 0 ? `逾期${row.overdueDays}天` : paymentStatus(row.paymentStatus) }}</el-tag></template></el-table-column>
        </el-table>
      </div>
    </div>

    <div class="work-grid">
      <div v-if="appStore.hasPermission('inbound.view')" class="panel">
        <div class="panel-header"><div class="panel-title">近期入库单</div></div>
        <el-table :data="dashboard?.recentInbounds ?? []" class="data-table" size="small" border show-overflow-tooltip>
          <el-table-column label="单据号" min-width="160" fixed="left"><template #default="{ row }"><router-link class="table-link" :to="relatedDetailRoute(route, `/inbounds/${row.id}`)">{{ row.orderNo }}</router-link></template></el-table-column>
          <el-table-column prop="supplierName" label="供应商" min-width="120" />
          <el-table-column prop="warehouseName" label="仓库" width="90" />
          <el-table-column label="数量" width="110" align="right"><template #default="{ row }">{{ quantity(row.totalQuantity) }}</template></el-table-column>
          <el-table-column label="金额" width="120" align="right"><template #default="{ row }">{{ money(row.totalAmount) }}</template></el-table-column>
        </el-table>
      </div>

      <div v-if="appStore.hasPermission('finance.view')" class="panel">
        <div class="panel-header"><div class="panel-title">近期回款记录</div><el-button text @click="router.push('/payments')">查看回款</el-button></div>
        <el-table :data="dashboard?.recentPayments ?? []" class="data-table" size="small" border show-overflow-tooltip>
          <el-table-column label="回款单号" width="190" fixed="left"><template #default="{ row }"><el-button link type="primary" @click="openPayment(row)">{{ row.paymentNo }}</el-button></template></el-table-column>
          <el-table-column prop="receivedDate" label="日期" width="110" :formatter="formatDateColumn" />
          <el-table-column prop="customerName" label="客户" min-width="120" />
          <el-table-column label="核销单据" min-width="140"><template #default="{ row }">{{ row.salesOrderNo || row.outboundNo || '-' }}</template></el-table-column>
          <el-table-column label="金额" width="120" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
        </el-table>
      </div>
    </div>
    <PaymentRecordDrawer v-model="paymentDrawerVisible" kind="RECEIPT" :record="activePayment" origin-label="工作台" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter, type RouteLocationRaw } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { api, type FulfillmentReminder, type PaymentRow, type ReceivableRow } from '@/api/client'
import { formatDateColumn, money, paymentStatus, paymentStatusType, quantity } from '@/api/format'
import PaymentRecordDrawer from '@/components/PaymentRecordDrawer.vue'
import { relatedDetailRoute } from '@/utils/navigation'
import { useAppStore } from '@/stores/app'

type AttentionCard = {
  key: string
  label: string
  badge: string
  countText: string
  amountText: string
  note: string
  action: string
  to: RouteLocationRaw
  level: 'normal' | 'warning' | 'danger'
  tagType: '' | 'success' | 'warning' | 'danger' | 'info'
  urgent: boolean
}

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const paymentDrawerVisible = ref(false)
const activePayment = ref<PaymentRow>()
const dashboardQuery = useQuery({ queryKey: ['dashboard'], queryFn: api.dashboard })
const dashboard = computed(() => dashboardQuery.data.value)
const canViewFulfillmentReminders = computed(() => appStore.hasPermission('purchase.view') || appStore.hasPermission('sales.view'))
const fulfillmentReminders = computed(() => (dashboard.value?.fulfillmentReminders || []).filter((item) =>
  appStore.hasPermission(item.sourceType === 'PURCHASE_ORDER' ? 'purchase.view' : 'sales.view')))

const attentionCards = computed<AttentionCard[]>(() => {
  const attention = dashboard.value?.ownerAttention
  if (!attention?.completeScope) return []
  const close = attention.close
  const fiscalYear = close.periodEndDate?.slice(0, 4)
  const closeRoute = close.periodId ? { path: '/accounting/period-close', query: { periodId: String(close.periodId), fiscalYear } } : '/accounting/master-data'
  return [
    card('today-receivable', '今日应收', attention.receivables.dueTodayCount, attention.receivables.dueTodayAmount, '按客户结算账期今天到期', '去收款', { path: '/receivables', query: { scope: 'due-today' } }, 'normal'),
    card('today-payable', '今日应付', attention.payables.dueTodayCount, attention.payables.dueTodayAmount, '按供应商结算账期今天到期', '去付款', { path: '/payables', query: { scope: 'due-today' } }, 'normal'),
    card('overdue-receivable', '逾期应收', attention.receivables.overdueCount, attention.receivables.overdueAmount, '已过到期日且仍未收回', '催收 / 核销', { path: '/receivables', query: { scope: 'overdue' } }, 'danger'),
    card('overdue-payable', '逾期应付', attention.payables.overdueCount, attention.payables.overdueAmount, '已过到期日且仍未付清', '安排付款', { path: '/payables', query: { scope: 'overdue' } }, 'danger'),
    countCard('inventory', '库存风险', attention.inventory.riskCount, `账批差异 ${attention.inventory.reconciliationMismatchCount} · 负数 ${attention.inventory.negativeBalanceCount} · 无自由库存 ${attention.inventory.unavailableBatchCount}`, '核对库存', '/inventory/reconciliation', 'danger'),
    card('bank', '未匹配银行流水', attention.bank.unmatchedStatementCount, attention.bank.unmatchedStatementAmount, `另有 ${attention.bank.openSessionCount} 张未确认对账单`, '处理对账', { path: '/accounting/bank-reconciliation', query: { scope: 'unmatched' } }, 'danger'),
    card('invoice', '未勾稽发票', attention.invoices.unreconciledCount, attention.invoices.unreconciledAmount, `另有 ${attention.invoices.verificationPendingCount} 张待查验`, '处理票货款账', '/tax-invoices/reconciliation', 'danger'),
    countCard('close', '待关账阻断', close.blockingCheckCount, `${close.periodName} · ${close.blockingSummary}`, '查看关账检查', closeRoute, 'danger'),
  ].filter((item) => {
    const permission = ({ inventory: 'inventory.view', bank: 'financial.bank.view', invoice: 'invoice.view', close: 'financial.report.view' } as Record<string, string>)[item.key] || 'finance.view'
    return appStore.hasPermission(permission)
  })
})

const urgentTaskCount = computed(() => attentionCards.value.filter((item) => item.urgent).length)
const kpis = computed(() => [
  { permission: 'inventory.view', label: '库存结存', value: quantity(dashboard.value?.stockQuantity), note: '主库存单位汇总' },
  { permission: 'inventory.cost.view', label: '库存成本', value: money(dashboard.value?.stockCostAmount), note: '按当前成本账汇总' },
  { permission: 'finance.view', label: '应收余额', value: money(dashboard.value?.receivableAmount), note: '全部未结应收' },
  { permission: 'finance.view', label: '累计实收资金', value: money(dashboard.value?.paidAmount), note: '现金、银行及承兑兑现' },
  { permission: 'inbound.view', label: '今日入库', value: quantity(dashboard.value?.todayInboundQuantity), note: '今日已确认入库' },
  { permission: 'outbound.view', label: '今日出库', value: quantity(dashboard.value?.todayOutboundQuantity), note: '今日已确认出库' },
].filter((item) => appStore.hasPermission(item.permission)))

function card(key: string, label: string, count: number, amount: number, note: string, action: string, to: RouteLocationRaw, risk: 'normal' | 'danger'): AttentionCard {
  const urgent = risk === 'danger' && count > 0
  return { key, label, badge: count ? `${count} 笔` : '已清', countText: `${count} 笔`, amountText: money(amount), note, action, to, level: urgent ? 'danger' : count ? 'warning' : 'normal', tagType: urgent ? 'danger' : count ? 'warning' : 'success', urgent }
}

function countCard(key: string, label: string, count: number, note: string, action: string, to: RouteLocationRaw, risk: 'danger'): AttentionCard {
  const urgent = risk === 'danger' && count > 0
  return { key, label, badge: count ? `${count} 项` : '已清', countText: `${count} 项`, amountText: '', note, action, to, level: urgent ? 'danger' : 'normal', tagType: urgent ? 'danger' : 'success', urgent }
}

function openPayment(payment: PaymentRow) { activePayment.value = payment; paymentDrawerVisible.value = true }
function reminderLabel(row: FulfillmentReminder) {
  const timing = ({ TODAY: '今日', TOMORROW: '明日', UPCOMING: '近期' } as const)[row.timingBucket]
  return `${timing}${row.sourceType === 'PURCHASE_ORDER' ? '到货' : '出库'}`
}
function reminderTagType(value: FulfillmentReminder['timingBucket']) { return value === 'TODAY' ? 'danger' : value === 'TOMORROW' ? 'warning' : 'info' }
function fulfillmentRoute(row: FulfillmentReminder) {
  return relatedDetailRoute(route, row.sourceType === 'PURCHASE_ORDER' ? `/purchase-orders/${row.sourceId}` : `/sales-orders/${row.sourceId}`)
}
function receivableDetailRoute(row: ReceivableRow) { return relatedDetailRoute(route, row.sourceType === 'SALES_ORDER' ? `/sales-orders/${row.sourceId}` : `/outbounds/${row.sourceId}`) }
</script>

<style scoped>
.owner-dashboard { display: grid; gap: 14px; }
.dashboard-heading, .section-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.page-title { color: var(--el-text-color-primary); font-size: 24px; font-weight: 700; }
.section-title { color: var(--el-text-color-primary); font-size: 16px; font-weight: 700; }
.attention-section, .overview-section { display: grid; gap: 12px; }
.attention-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.attention-card { min-height: 176px; padding: 15px 16px; overflow: hidden; border: 1px solid var(--el-border-color-light); border-radius: 10px; background: var(--el-bg-color); color: inherit; text-align: left; cursor: pointer; transition: border-color .16s ease, box-shadow .16s ease, transform .16s ease; }
.attention-card:hover { border-color: var(--el-color-primary-light-5); box-shadow: 0 6px 18px rgb(31 45 61 / 8%); transform: translateY(-1px); }
.attention-card.warning { border-color: var(--el-color-warning-light-5); background: var(--el-color-warning-light-9); }
.attention-card.danger { border-color: var(--el-color-danger-light-5); background: var(--el-color-danger-light-9); }
.attention-card-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: var(--el-text-color-regular); font-size: 13px; }
.attention-count { display: block; margin-top: 14px; font-size: 27px; line-height: 1; font-variant-numeric: tabular-nums; }
.attention-card.danger .attention-count, .attention-card.danger .attention-amount { color: var(--el-color-danger); }
.attention-amount { margin-top: 8px; font-size: 18px; font-weight: 650; font-variant-numeric: tabular-nums; }
.attention-note { min-height: 34px; margin-top: 9px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.45; }
.attention-action { margin-top: 8px; color: var(--el-color-primary); font-size: 12px; font-weight: 600; }
.overview-grid { grid-template-columns: repeat(6, minmax(0, 1fr)); }
@media (max-width: 1280px) { .attention-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .overview-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 760px) { .dashboard-heading, .section-heading { align-items: flex-start; flex-direction: column; } .attention-grid, .overview-grid { grid-template-columns: 1fr; } }
</style>
