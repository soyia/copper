<template>
  <div v-loading="loading">
    <PageHeader v-if="detail" :title="`${detail.opportunity.opportunityNo} · ${detail.opportunity.title}`" back-to="/crm/opportunities">
      <el-button v-if="!terminal" @click="openEdit">编辑</el-button>
      <el-button v-if="!terminal" @click="advance">推进阶段</el-button>
      <el-dropdown v-if="!terminal" @command="closeOpportunity"><el-button type="warning">关闭商机<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button><template #dropdown><el-dropdown-menu><el-dropdown-item command="WON">标记成交</el-dropdown-item><el-dropdown-item command="LOST">标记流失</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
      <el-button v-else type="warning" @click="reactivate">重新激活</el-button>
      <el-button v-if="!terminal" type="primary" @click="router.push({ path: '/crm/quotes/new', query: { opportunityId: detail.opportunity.id, partnerId: detail.opportunity.partnerId } })">新建报价</el-button>
    </PageHeader>
    <template v-if="detail">
      <section class="panel">
        <div class="stage-flow"><div v-for="item in openStages" :key="item.value" :class="['stage-step', { active: item.value === detail.opportunity.stage, passed: stageIndex(item.value) < stageIndex(detail.opportunity.stage) }]">{{ item.label }}</div><div v-if="terminal" :class="['stage-step','active',detail.opportunity.stage === 'LOST' ? 'lost' : 'won']">{{ crmStatusName(detail.opportunity.stage) }}</div></div>
        <el-descriptions :column="3" border>
          <el-descriptions-item label="客户"><el-button link type="primary" @click="openCustomer">{{ detail.opportunity.partnerName }}</el-button></el-descriptions-item><el-descriptions-item label="阶段"><el-tag :type="crmStatusType(detail.opportunity.stage)">{{ crmStatusName(detail.opportunity.stage) }}</el-tag></el-descriptions-item><el-descriptions-item label="负责人">{{ detail.opportunity.ownerName }}</el-descriptions-item><el-descriptions-item label="预计金额">¥{{ moneyText(detail.opportunity.estimatedAmount) }}</el-descriptions-item><el-descriptions-item label="成交概率">{{ detail.opportunity.probability }}%</el-descriptions-item><el-descriptions-item label="预计成交日">{{ detail.opportunity.expectedCloseDate || '-' }}</el-descriptions-item><el-descriptions-item label="联系人">{{ detail.opportunity.contactName || '-' }}</el-descriptions-item><el-descriptions-item label="竞争对手">{{ detail.opportunity.competitor || '-' }}</el-descriptions-item><el-descriptions-item label="来源线索"><el-button v-if="detail.opportunity.sourceLeadId" link type="primary" @click="router.push(`/crm/leads/${detail.opportunity.sourceLeadId}`)">查看线索</el-button><span v-else>-</span></el-descriptions-item><el-descriptions-item label="客户需求" :span="3">{{ detail.opportunity.demand || '-' }}</el-descriptions-item><el-descriptions-item v-if="terminal" :label="detail.opportunity.stage === 'WON' ? '成交原因' : '流失原因'" :span="3">{{ detail.opportunity.stage === 'WON' ? detail.opportunity.winReason : detail.opportunity.lossReason }}</el-descriptions-item>
        </el-descriptions>
      </section>
      <section class="panel quote-panel"><div class="panel-header"><div><div class="panel-title">关联报价</div><div class="muted">报价有独立版本和状态，不把商机金额直接当成订单金额。</div></div></div><el-table :data="detail.quotes" empty-text="暂无报价"><el-table-column label="报价号" width="185"><template #default="{ row }"><el-button link type="primary" @click="router.push(`/crm/quotes/${row.id}`)">{{ row.quoteNo }}-V{{ row.revisionNo }}</el-button></template></el-table-column><el-table-column prop="title" label="主题" /><el-table-column label="状态" width="110"><template #default="{ row }">{{ crmStatusName(row.status) }}</template></el-table-column><el-table-column label="价税合计" align="right"><template #default="{ row }">¥{{ moneyText(row.totalAmount) }}</template></el-table-column><el-table-column prop="validUntil" label="有效期至" width="115" /></el-table></section>
      <CrmActivityPlanPanel target-type="OPPORTUNITY" :target-id="detail.opportunity.id" :partner-id="detail.opportunity.partnerId" :activities="detail.activities" :plans="detail.plans" @refresh="load" />
      <section class="panel audit-panel"><div class="panel-title">商机审计历史</div><el-timeline><el-timeline-item v-for="item in detail.events" :key="item.id" :timestamp="dateTimeText(item.occurredAt)" placement="top"><strong>{{ crmEventName(item.eventType) }}</strong><div class="muted">{{ item.actorName }}</div></el-timeline-item></el-timeline></section>
    </template>

    <el-dialog v-model="editVisible" title="编辑商机" width="660px"><el-form v-if="detail" label-width="100px"><el-form-item label="商机名称" required><el-input v-model="form.title" /></el-form-item><el-form-item label="预计金额"><el-input-number v-model="form.estimatedAmount" :min="0" :precision="2" :controls="false" /></el-form-item><el-form-item label="成交概率"><el-input-number v-model="form.probability" :min="0" :max="100" :precision="2" :controls="false" /></el-form-item><el-form-item label="预计成交"><el-date-picker v-model="form.expectedCloseDate" type="date" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="客户需求"><el-input v-model="form.demand" type="textarea" :rows="3" /></el-form-item><el-form-item label="竞争对手"><el-input v-model="form.competitor" /></el-form-item></el-form><template #footer><el-button @click="editVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button></template></el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowDown } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type CrmOpportunityDetail } from '@/api/client'
import PageHeader from '@/components/PageHeader.vue'
import CrmActivityPlanPanel from '@/components/CrmActivityPlanPanel.vue'
import { crmEventName, crmStatusName, crmStatusType, dateTimeText, moneyText } from '@/utils/crm'

const route = useRoute(); const router = useRouter(); const detail = ref<CrmOpportunityDetail>(); const loading = ref(false); const saving = ref(false); const editVisible = ref(false)
const openStages = [{ label: '资格确认', value: 'QUALIFY' }, { label: '需求分析', value: 'NEEDS' }, { label: '方案报价', value: 'PROPOSAL' }, { label: '商务谈判', value: 'NEGOTIATION' }]
const form = reactive({ partnerId: 0, contactId: undefined as number | undefined, sourceLeadId: undefined as number | undefined, title: '', probability: 10, estimatedAmount: 0, expectedCloseDate: '', demand: '', competitor: '' })
const id = () => Number(route.params.id); const terminal = computed(() => ['WON', 'LOST'].includes(detail.value?.opportunity.stage || '')); const stageIndex = (value: string) => openStages.findIndex((item) => item.value === value)
const isActiveRoute = () => route.path === `/crm/opportunities/${id()}`
onMounted(load); watch(() => route.params.id, load)
async function load() { if (!id() || !isActiveRoute()) return; loading.value = true; try { detail.value = await api.crmOpportunity(id()) } finally { loading.value = false } }
function openEdit() { if (!detail.value) return; Object.assign(form, { ...detail.value.opportunity, expectedCloseDate: detail.value.opportunity.expectedCloseDate || '' }); editVisible.value = true }
async function saveEdit() { if (!form.title.trim()) { ElMessage.warning('请填写商机名称'); return } saving.value = true; try { await api.updateCrmOpportunity(id(), form); ElMessage.success('商机已更新'); editVisible.value = false; load() } finally { saving.value = false } }
async function advance() { if (!detail.value) return; const index = stageIndex(detail.value.opportunity.stage); if (index < 0 || index >= openStages.length - 1) { ElMessage.info('当前已到商务谈判阶段，请选择成交或流失'); return } const next = openStages[index + 1]; await api.changeCrmOpportunityStage(id(), { stage: next.value }); ElMessage.success(`已推进到${next.label}`); load() }
async function closeOpportunity(command: string | number | object) { const stage = String(command); const result = await ElMessageBox.prompt(`请填写${stage === 'WON' ? '成交' : '流失'}原因，状态关闭后不能直接编辑。`, stage === 'WON' ? '标记成交' : '标记流失', { inputType: 'textarea', inputValidator: (value) => Boolean(value.trim()) || '请填写原因' }).catch(() => null); if (!result) return; await api.changeCrmOpportunityStage(id(), { stage, reasonCode: 'OTHER', reason: result.value }); ElMessage.success(stage === 'WON' ? '商机已成交' : '商机已流失'); load() }
async function reactivate() { const result = await ElMessageBox.prompt('重新激活会回到资格确认阶段，请填写原因。', '重新激活商机', { inputType: 'textarea', inputValidator: (value) => Boolean(value.trim()) || '请填写原因' }).catch(() => null); if (!result) return; await api.changeCrmOpportunityStage(id(), { stage: 'QUALIFY', reasonCode: 'OTHER', reason: result.value }); ElMessage.success('商机已重新激活'); load() }
async function openCustomer() { if (!detail.value) return; const rows = await api.crmCustomers({ keyword: detail.value.opportunity.partnerName }); const customer = rows.find((item) => item.partnerId === detail.value?.opportunity.partnerId); if (customer) router.push(`/crm/customers/${customer.id}`); else router.push(`/partners/${detail.value.opportunity.partnerId}`) }
</script>

<style scoped>
.stage-flow{display:flex;gap:8px;margin-bottom:16px}.stage-step{flex:1;text-align:center;padding:9px;border-radius:7px;background:var(--el-fill-color-light);color:var(--el-text-color-secondary)}.stage-step.passed{background:var(--el-color-success-light-9);color:var(--el-color-success)}.stage-step.active{background:var(--el-color-primary);color:white}.stage-step.lost{background:var(--el-color-danger)}.stage-step.won{background:var(--el-color-success)}.quote-panel,.audit-panel{margin-top:16px}.panel-header{display:flex;justify-content:space-between;align-items:center}
</style>
