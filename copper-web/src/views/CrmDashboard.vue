<template>
  <div v-loading="loading" class="crm-page">
    <section class="panel crm-hero">
      <div>
        <div class="panel-title">客户经营助手</div>
        <div class="muted">先处理今天要联系的人、快到期报价、复购和欠款提醒。线索与商机是可选工具，不是销售下单的前置流程。</div>
      </div>
      <div class="hero-actions">
        <el-button @click="router.push('/crm/customers')">客户档案</el-button>
        <el-button v-if="canManageCrm" @click="directQuote()">直接报价</el-button>
        <el-button v-if="canCreateSales" type="primary" @click="directOrder()">直接销售下单</el-button>
      </div>
    </section>

    <section class="kpi-grid">
      <button class="kpi-card danger" @click="router.push('/crm/activities?scope=TODAY')">
        <span>今日跟进</span><strong>{{ dashboard?.dueTodayPlanCount || 0 }}</strong><small>另有逾期 {{ dashboard?.overduePlanCount || 0 }} 项</small>
      </button>
      <button class="kpi-card" @click="scrollTo('quote-panel')">
        <span>报价待处理</span><strong>{{ dashboard?.expiringQuoteCount || 0 }}</strong><small>7 天内到期或已经到期</small>
      </button>
      <button class="kpi-card" @click="scrollTo('repurchase-panel')">
        <span>复购提醒</span><strong>{{ dashboard?.repurchaseReminderCount || 0 }}</strong><small>按每位客户自己的周期建议</small>
      </button>
      <button class="kpi-card danger" @click="scrollTo('receivable-panel')">
        <span>欠款提醒</span><strong>{{ dashboard?.receivableReminderCount || 0 }}</strong><small>待收合计 ¥{{ moneyText(dashboard?.receivableReminderAmount) }}</small>
      </button>
    </section>

    <section class="dashboard-grid">
      <div class="panel">
        <div class="panel-header">
          <div><div class="panel-title">今天先联系谁</div><div class="muted">逾期优先，完成后保留沟通记录</div></div>
          <el-button link type="primary" @click="router.push('/crm/activities')">全部跟进</el-button>
        </div>
        <el-table :data="dashboard?.urgentPlans || []" empty-text="暂无待办">
          <el-table-column prop="partnerName" label="客户" min-width="145" />
          <el-table-column prop="subject" label="要做什么" min-width="170" />
          <el-table-column label="计划时间" width="160"><template #default="{ row }"><span :class="{ overdue: isPast(row.dueAt) }">{{ dateTimeText(row.dueAt) }}</span></template></el-table-column>
          <el-table-column prop="ownerName" label="负责人" width="100" />
        </el-table>
      </div>

      <div id="quote-panel" class="panel attention-panel">
        <div class="panel-header">
          <div><div class="panel-title">报价待办</div><div class="muted">报价可以直接创建，不要求先建线索或商机</div></div>
          <el-button link type="primary" @click="router.push('/crm/quotes')">全部报价</el-button>
        </div>
        <el-table :data="dashboard?.expiringQuotes || []" empty-text="暂无临期报价">
          <el-table-column label="报价" min-width="170"><template #default="{ row }"><el-button link type="primary" @click="router.push(`/crm/quotes/${row.id}`)">{{ row.quoteNo }}-V{{ row.revisionNo }}</el-button></template></el-table-column>
          <el-table-column prop="partnerName" label="客户" min-width="145" />
          <el-table-column label="金额" width="120" align="right"><template #default="{ row }">¥{{ moneyText(row.totalAmount) }}</template></el-table-column>
          <el-table-column label="有效期" width="120"><template #default="{ row }"><el-tag :type="isDateExpired(row.validUntil) ? 'danger' : 'warning'">{{ isDateExpired(row.validUntil) ? '已到期' : row.validUntil }}</el-tag></template></el-table-column>
        </el-table>
      </div>
    </section>

    <section class="dashboard-grid">
      <div id="repurchase-panel" class="panel attention-panel">
        <div class="panel-header">
          <div><div class="panel-title">可能该复购的客户</div><div class="muted">只做透明建议；有新销售订单时自动停止提醒，也可在客户档案关闭</div></div>
        </div>
        <el-table :data="dashboard?.repurchaseReminders || []" empty-text="暂无复购提醒">
          <el-table-column label="客户" min-width="145"><template #default="{ row }"><el-button link type="primary" @click="openCustomer(row.customerProfileId)">{{ row.partnerName }}</el-button></template></el-table-column>
          <el-table-column label="最近成交" width="110"><template #default="{ row }">{{ row.lastBusinessDate || '-' }}</template></el-table-column>
          <el-table-column prop="recentProducts" label="近期商品" min-width="150" show-overflow-tooltip />
          <el-table-column label="建议联系" width="115"><template #default="{ row }"><span class="overdue">{{ row.suggestedDate }}</span></template></el-table-column>
          <el-table-column v-if="canCreateSales || canManageCrm" label="快捷操作" width="140" fixed="right"><template #default="{ row }"><el-button v-if="canCreateSales" link type="primary" @click="directOrder(row.partnerId)">下单</el-button><el-button v-if="canManageCrm" link @click="directQuote(row.partnerId)">报价</el-button></template></el-table-column>
        </el-table>
      </div>

      <div id="receivable-panel" class="panel attention-panel">
        <div class="panel-header">
          <div><div class="panel-title">欠款提醒</div><div class="muted">只读引用财务应收；提醒客户不等于收款或核销</div></div>
          <el-button v-if="canViewFinance" link type="primary" @click="router.push('/partner-accounts')">查看往来账</el-button>
        </div>
        <el-table :data="dashboard?.receivableReminders || []" empty-text="暂无欠款客户">
          <el-table-column label="客户" min-width="145"><template #default="{ row }"><el-button link type="primary" @click="openCustomer(row.customerProfileId)">{{ row.partnerName }}</el-button></template></el-table-column>
          <el-table-column label="联系人" min-width="135"><template #default="{ row }"><div>{{ row.contactName || '-' }}</div><small class="muted">{{ row.contactPhone || '' }}</small></template></el-table-column>
          <el-table-column label="应收余额" width="125" align="right"><template #default="{ row }"><strong class="amount-danger">¥{{ moneyText(row.receivableBalance) }}</strong></template></el-table-column>
          <el-table-column label="其中逾期" width="120" align="right"><template #default="{ row }">¥{{ moneyText(row.overdueAmount) }}</template></el-table-column>
        </el-table>
      </div>
    </section>

    <section class="panel">
      <div class="panel-header">
        <div><div class="panel-title">客户经营分析</div><div class="muted">交易、成本、毛利和欠款来自销售、库存、财务只读事实，CRM 无权改账</div></div>
        <el-button link type="primary" @click="router.push('/crm/customers')">客户档案</el-button>
      </div>
      <el-table :data="dashboard?.customerRanking || []" empty-text="暂无客户经营数据">
        <el-table-column label="客户" min-width="170"><template #default="{ row }"><el-button link type="primary" @click="openCustomer(row.customerProfileId)">{{ row.partnerName }}</el-button></template></el-table-column>
        <el-table-column label="客户层级" width="100"><template #default="{ row }">{{ crmTierName(row.tierCode) }}</template></el-table-column>
        <el-table-column label="净销售额" align="right"><template #default="{ row }">¥{{ moneyText(row.netRevenue) }}</template></el-table-column>
        <el-table-column label="毛利" align="right"><template #default="{ row }">¥{{ moneyText(row.grossProfit) }}</template></el-table-column>
        <el-table-column label="应收余额" align="right"><template #default="{ row }">¥{{ moneyText(row.receivableBalance) }}</template></el-table-column>
        <el-table-column label="最近业务" width="120"><template #default="{ row }">{{ row.lastBusinessDate || '-' }}</template></el-table-column>
      </el-table>
    </section>

    <el-collapse class="optional-tools">
      <el-collapse-item name="pipeline">
        <template #title><strong>可选：线索与商机分析</strong><span class="muted collapse-hint">需要管理较长销售周期时再使用，不影响直接报价和下单</span></template>
        <div class="optional-summary">
          <button class="optional-card" @click="router.push('/crm/leads')"><span>待跟进线索</span><strong>{{ dashboard?.openLeadCount || 0 }}</strong></button>
          <button class="optional-card" @click="router.push('/crm/opportunities')"><span>未关闭商机</span><strong>{{ dashboard?.openOpportunityCount || 0 }}</strong><small>预计 ¥{{ moneyText(dashboard?.openPipelineAmount) }}</small></button>
          <div class="funnel-list">
            <button v-for="item in sortedStages" :key="item.stage" class="funnel-row" @click="router.push({ path: '/crm/opportunities', query: { stage: item.stage } })">
              <el-tag :type="crmStatusType(item.stage)">{{ crmStatusName(item.stage) }}</el-tag><strong>{{ item.count }} 个</strong><span>¥{{ moneyText(item.amount) }}</span>
            </button>
          </div>
        </div>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, type CrmDashboard } from '@/api/client'
import { crmStatusName, crmStatusType, crmTierName, dateTimeText, moneyText } from '@/utils/crm'
import { useAppStore } from '@/stores/app'

const router = useRouter()
const appStore = useAppStore()
const dashboard = ref<CrmDashboard>()
const loading = ref(false)
const canCreateSales = computed(() => appStore.hasPermission('sales.view'))
const canManageCrm = computed(() => appStore.hasPermission('crm.record.manage'))
const canViewFinance = computed(() => appStore.hasPermission('finance.view'))
const stageOrder = ['QUALIFY', 'NEEDS', 'PROPOSAL', 'NEGOTIATION', 'WON', 'LOST']
const sortedStages = computed(() => [...(dashboard.value?.stages || [])].sort((a, b) => stageOrder.indexOf(a.stage) - stageOrder.indexOf(b.stage)))

onMounted(load)
async function load() { loading.value = true; try { dashboard.value = await api.crmDashboard() } finally { loading.value = false } }
function directOrder(partnerId?: number) { router.push({ path: '/sales-orders/new', query: partnerId ? { customerId: String(partnerId) } : {} }) }
function directQuote(partnerId?: number) { router.push({ path: '/crm/quotes/new', query: partnerId ? { partnerId: String(partnerId) } : {} }) }
function openCustomer(id: number) { router.push(`/crm/customers/${id}`) }
function scrollTo(id: string) { document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }
function isPast(value: string) { return new Date(value).getTime() < Date.now() }
function isDateExpired(value: string) { return value < new Date().toISOString().slice(0, 10) }
</script>

<style scoped>
.crm-page { display: grid; gap: 16px; }
.crm-hero, .panel-header, .hero-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.kpi-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.kpi-card { border: 1px solid var(--el-border-color-light); border-radius: 10px; background: var(--el-bg-color); text-align: left; padding: 16px; color: inherit; cursor: pointer; }
.kpi-card strong { display: block; font-size: 28px; margin: 8px 0; }
.kpi-card small, .collapse-hint { color: var(--el-text-color-secondary); }
.kpi-card.danger strong, .amount-danger, .overdue { color: var(--el-color-danger); }
.dashboard-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.attention-panel { scroll-margin-top: 16px; }
.optional-tools { border: 1px solid var(--el-border-color-light); border-radius: 10px; padding: 0 16px; background: var(--el-bg-color); }
.collapse-hint { margin-left: 12px; font-weight: 400; }
.optional-summary { display: grid; grid-template-columns: 180px 220px minmax(360px, 1fr); gap: 12px; padding-bottom: 16px; }
.optional-card { border: 1px solid var(--el-border-color-light); border-radius: 8px; background: var(--el-fill-color-light); padding: 14px; text-align: left; color: inherit; cursor: pointer; }
.optional-card strong { display: block; font-size: 24px; margin-top: 8px; }
.optional-card small { display: block; margin-top: 5px; color: var(--el-text-color-secondary); }
.funnel-list { display: grid; gap: 8px; }
.funnel-row { display: grid; grid-template-columns: 1fr 80px 130px; align-items: center; gap: 12px; border: 0; border-radius: 8px; background: var(--el-fill-color-light); padding: 9px 12px; text-align: left; color: inherit; cursor: pointer; }
@media (max-width: 1100px) { .kpi-grid, .dashboard-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .optional-summary { grid-template-columns: 1fr; } }
@media (max-width: 760px) { .crm-hero, .panel-header { align-items: flex-start; flex-direction: column; } .hero-actions { width: 100%; flex-wrap: wrap; justify-content: flex-start; } .kpi-grid, .dashboard-grid { grid-template-columns: 1fr; } }
</style>
