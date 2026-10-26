<template>
  <div v-loading="loading">
    <PageHeader v-if="detail" :title="`${detail.customer.businessNo} · ${detail.customer.partnerName}`" back-to="/crm/customers">
      <el-button @click="router.push(`/partners/${detail.customer.partnerId}?from=${encodeURIComponent(route.fullPath)}`)">共享主档</el-button>
      <el-button @click="openEdit">编辑经营档案</el-button>
      <el-button v-if="canManageCrm" @click="router.push({ path: '/crm/quotes/new', query: { partnerId: detail.customer.partnerId } })">直接报价</el-button>
      <el-button v-if="canCreateSales" type="primary" @click="router.push({ path: '/sales-orders/new', query: { customerId: detail.customer.partnerId } })">直接销售下单</el-button>
    </PageHeader>
    <template v-if="detail">
      <section class="summary-grid">
        <div class="panel profile-card">
          <div class="panel-title">客户经营概况</div>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="生命周期"><el-tag :type="crmStatusType(detail.customer.lifecycleStage)">{{ crmStatusName(detail.customer.lifecycleStage) }}</el-tag></el-descriptions-item>
            <el-descriptions-item label="客户分层">{{ crmTierName(detail.customer.tierCode) }}</el-descriptions-item>
            <el-descriptions-item label="主要联系人">{{ detail.customer.contactName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="电话">{{ detail.customer.contactPhone || '-' }}</el-descriptions-item>
            <el-descriptions-item label="负责人">{{ detail.customer.ownerName || '待分配' }}</el-descriptions-item>
            <el-descriptions-item label="年度潜力">¥{{ moneyText(detail.customer.annualPotential) }}</el-descriptions-item>
            <el-descriptions-item label="偏好品类">{{ detail.customer.preferredCategory || '-' }}</el-descriptions-item>
            <el-descriptions-item label="竞争对手">{{ detail.customer.competitor || '-' }}</el-descriptions-item>
            <el-descriptions-item label="下一步" :span="2">{{ detail.customer.nextAction || '-' }} {{ detail.customer.nextActionDate || '' }}</el-descriptions-item>
            <el-descriptions-item label="复购提醒" :span="2">{{ detail.customer.repurchaseReminderEnabled ? `成交后 ${detail.customer.repurchaseCycleDays} 天建议联系` : '已关闭' }}</el-descriptions-item>
          </el-descriptions>
        </div>
        <div class="panel analysis-card">
          <div class="panel-header"><div><div class="panel-title">只读经营分析</div><div class="muted">来源于销售、库存和财务事实，CRM 不可改写</div></div></div>
          <div class="metric-grid"><div><span>净销售额</span><strong>¥{{ moneyText(detail.analysis.netRevenue) }}</strong></div><div><span>毛利</span><strong>¥{{ moneyText(detail.analysis.grossProfit) }}</strong><small>{{ detail.analysis.grossMargin }}%</small></div><div><span>应收余额</span><strong :class="{ negative: detail.analysis.receivableBalance > 0 }">¥{{ moneyText(detail.analysis.receivableBalance) }}</strong></div><div><span>逾期估算</span><strong :class="{ negative: detail.analysis.overdueAmount > 0 }">¥{{ moneyText(detail.analysis.overdueAmount) }}</strong></div><div><span>未关闭商机</span><strong>¥{{ moneyText(detail.analysis.openPipelineAmount) }}</strong></div><div><span>最近业务</span><strong class="small-value">{{ detail.analysis.lastBusinessDate || '-' }}</strong></div></div>
          <div class="muted recent-products">近期商品：{{ detail.analysis.recentProducts || '暂无' }}</div>
        </div>
      </section>

      <section class="panel relation-panel">
        <el-tabs>
          <el-tab-pane :label="`联系人 (${detail.contacts.length})`"><el-table :data="detail.contacts"><el-table-column prop="name" label="姓名" /><el-table-column prop="jobTitle" label="职务" /><el-table-column prop="department" label="部门" /><el-table-column prop="phone" label="电话" /><el-table-column prop="wechat" label="微信" /><el-table-column prop="email" label="邮箱" min-width="180" /></el-table></el-tab-pane>
          <el-tab-pane :label="`商机 (${detail.opportunities.length})`"><el-table :data="detail.opportunities"><el-table-column label="商机号" width="160"><template #default="{ row }"><el-button link type="primary" @click="router.push(`/crm/opportunities/${row.id}`)">{{ row.opportunityNo }}</el-button></template></el-table-column><el-table-column prop="title" label="商机" min-width="180" /><el-table-column label="阶段" width="110"><template #default="{ row }">{{ crmStatusName(row.stage) }}</template></el-table-column><el-table-column label="预计金额" align="right"><template #default="{ row }">¥{{ moneyText(row.estimatedAmount) }}</template></el-table-column></el-table></el-tab-pane>
          <el-tab-pane :label="`报价 (${detail.quotes.length})`"><el-table :data="detail.quotes"><el-table-column label="报价号" width="180"><template #default="{ row }"><el-button link type="primary" @click="router.push(`/crm/quotes/${row.id}`)">{{ row.quoteNo }}-V{{ row.revisionNo }}</el-button></template></el-table-column><el-table-column prop="title" label="主题" /><el-table-column label="状态"><template #default="{ row }">{{ crmStatusName(row.status) }}</template></el-table-column><el-table-column label="价税合计" align="right"><template #default="{ row }">¥{{ moneyText(row.totalAmount) }}</template></el-table-column></el-table></el-tab-pane>
        </el-tabs>
      </section>
      <CrmActivityPlanPanel target-type="CUSTOMER" :target-id="detail.customer.partnerId" :partner-id="detail.customer.partnerId" :activities="detail.activities" :plans="detail.plans" @refresh="load" />
      <section class="panel audit-panel"><div class="panel-title">CRM 审计历史</div><el-timeline><el-timeline-item v-for="item in detail.events" :key="item.id" :timestamp="dateTimeText(item.occurredAt)" placement="top"><strong>{{ crmEventName(item.eventType) }}</strong><div class="muted">{{ item.actorName }}</div></el-timeline-item></el-timeline></section>
    </template>

    <el-dialog v-model="editVisible" title="编辑客户经营档案" width="680px">
      <el-form v-if="detail" label-width="110px"><el-form-item label="生命周期"><el-select v-model="form.lifecycleStage"><el-option label="潜在客户" value="PROSPECT" /><el-option label="活跃客户" value="ACTIVE" /><el-option label="沉睡客户" value="DORMANT" /><el-option label="已流失" value="LOST" /></el-select></el-form-item><el-form-item label="客户分层"><el-select v-model="form.tierCode"><el-option label="重点客户" value="KEY" /><el-option label="成长客户" value="GROWTH" /><el-option label="普通客户" value="NORMAL" /><el-option label="观察客户" value="OBSERVE" /></el-select></el-form-item><el-form-item label="标签"><el-input v-model="form.tags" /></el-form-item><el-form-item label="年度潜力"><el-input-number v-model="form.annualPotential" :min="0" :precision="2" :controls="false" /></el-form-item><el-form-item label="偏好品类"><el-input v-model="form.preferredCategory" /></el-form-item><el-form-item label="竞争对手"><el-input v-model="form.competitor" /></el-form-item><el-form-item label="下一步"><el-input v-model="form.nextAction" /></el-form-item><el-form-item label="计划日期"><el-date-picker v-model="form.nextActionDate" type="date" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="复购提醒"><el-switch v-model="form.repurchaseReminderEnabled" active-text="开启" inactive-text="关闭" /></el-form-item><el-form-item v-if="form.repurchaseReminderEnabled" label="建议周期"><el-input-number v-model="form.repurchaseCycleDays" :min="1" :max="3650" :controls="false" /><span class="muted cycle-hint">天；只是提醒，不阻断销售</span></el-form-item><el-form-item v-if="form.lifecycleStage === 'LOST'" label="流失原因" required><el-input v-model="form.lostReason" type="textarea" :rows="2" /></el-form-item></el-form>
      <template #footer><el-button @click="editVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, type CrmCustomerDetail } from '@/api/client'
import PageHeader from '@/components/PageHeader.vue'
import CrmActivityPlanPanel from '@/components/CrmActivityPlanPanel.vue'
import { crmEventName, crmStatusName, crmStatusType, crmTierName, dateTimeText, moneyText } from '@/utils/crm'
import { useAppStore } from '@/stores/app'

const route = useRoute(); const router = useRouter(); const appStore = useAppStore(); const detail = ref<CrmCustomerDetail>(); const loading = ref(false); const saving = ref(false); const editVisible = ref(false)
const canCreateSales = computed(() => appStore.hasPermission('sales.view'))
const canManageCrm = computed(() => appStore.hasPermission('crm.record.manage'))
const form = reactive({ partnerId: 0, lifecycleStage: 'PROSPECT', tierCode: 'NORMAL', sourceCode: 'OTHER', tags: '', annualPotential: 0, preferredCategory: '', competitor: '', nextAction: '', nextActionDate: '', repurchaseReminderEnabled: true, repurchaseCycleDays: 30, wonReasonCode: '', lostReasonCode: 'OTHER', lostReason: '' })
const id = () => Number(route.params.id)
const isActiveRoute = () => route.path === `/crm/customers/${id()}`
onMounted(load); watch(() => route.params.id, load)
async function load() { if (!id() || !isActiveRoute()) return; loading.value = true; try { detail.value = await api.crmCustomer(id()) } finally { loading.value = false } }
function openEdit() { if (!detail.value) return; Object.assign(form, { ...detail.value.customer, nextActionDate: detail.value.customer.nextActionDate || '' }); editVisible.value = true }
async function save() { if (form.lifecycleStage === 'LOST' && !form.lostReason.trim()) { ElMessage.warning('流失客户必须填写原因'); return } saving.value = true; try { await api.updateCrmCustomer(id(), form); ElMessage.success('客户经营档案已更新'); editVisible.value = false; await load() } finally { saving.value = false } }
</script>

<style scoped>
.summary-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }.profile-card .panel-title, .analysis-card .panel-title { margin-bottom: 12px; }.metric-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }.metric-grid div { background: var(--el-fill-color-light); border-radius: 8px; padding: 12px; }.metric-grid span, .metric-grid small { color: var(--el-text-color-secondary); display: block; }.metric-grid strong { display: block; font-size: 18px; margin-top: 6px; }.metric-grid .small-value { font-size: 15px; }.negative { color: var(--el-color-danger); }.recent-products { margin-top: 12px; }.relation-panel, .audit-panel { margin-top: 16px; }.cycle-hint{margin-left:10px}@media(max-width:1100px){.summary-grid{grid-template-columns:1fr}}
</style>
