<template>
  <div v-loading="loading">
    <PageHeader v-if="detail" :title="`${detail.lead.leadNo} · ${detail.lead.subject}`" back-to="/crm/leads">
      <el-button v-if="detail.lead.status === 'OPEN'" @click="editVisible = true">编辑</el-button>
      <el-button v-if="detail.lead.status === 'OPEN'" type="danger" plain @click="disqualify">判定无效</el-button>
      <el-button v-if="detail.lead.status === 'OPEN'" type="primary" @click="openQualify">转潜在客户</el-button>
      <el-button v-if="detail.lead.status !== 'OPEN'" type="warning" @click="reactivate">重新激活</el-button>
    </PageHeader>
    <template v-if="detail">
      <section class="panel">
        <el-descriptions :column="3" border>
          <el-descriptions-item label="状态"><el-tag :type="crmStatusType(detail.lead.status)">{{ crmStatusName(detail.lead.status) }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="来源">{{ crmSourceName(detail.lead.sourceCode) }}</el-descriptions-item>
          <el-descriptions-item label="负责人">{{ detail.lead.ownerName }}</el-descriptions-item>
          <el-descriptions-item label="公司 / 主体">{{ detail.lead.companyName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="联系人">{{ detail.lead.contactName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="电话">{{ detail.lead.phone || '-' }}</el-descriptions-item>
          <el-descriptions-item label="邮箱">{{ detail.lead.email || '-' }}</el-descriptions-item>
          <el-descriptions-item label="微信">{{ detail.lead.wechat || '-' }}</el-descriptions-item>
          <el-descriptions-item label="预计金额">¥{{ moneyText(detail.lead.estimatedAmount) }}</el-descriptions-item>
          <el-descriptions-item label="客户需求" :span="3">{{ detail.lead.demand || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.lead.partnerId" label="共享客户主档" :span="3"><el-button link type="primary" @click="router.push(`/partners/${detail.lead.partnerId}`)">{{ detail.lead.partnerName }}</el-button></el-descriptions-item>
          <el-descriptions-item v-if="detail.lead.disqualifiedReason" label="无效原因" :span="3">{{ detail.lead.disqualifiedReason }}</el-descriptions-item>
        </el-descriptions>
      </section>
      <CrmActivityPlanPanel target-type="LEAD" :target-id="detail.lead.id" :partner-id="detail.lead.partnerId" :activities="detail.activities" :plans="detail.plans" @refresh="load" />
      <section class="panel audit-panel">
        <div class="panel-title">状态与审计历史</div>
        <el-timeline><el-timeline-item v-for="item in detail.events" :key="item.id" :timestamp="dateTimeText(item.occurredAt)" placement="top"><strong>{{ crmEventName(item.eventType) }}</strong><div class="muted">{{ item.actorName }}</div></el-timeline-item></el-timeline>
      </section>
    </template>

    <el-dialog v-model="qualifyVisible" title="转为潜在客户" width="660px" destroy-on-close>
      <el-alert title="线索转化不会自动创建客户主档" description="请选择已核实的共享往来单位主档，避免 CRM 产生重复客户；商机为可选。" type="info" :closable="false" show-icon />
      <el-form label-width="110px" class="dialog-form">
        <el-form-item label="客户主档" required><PartnerRemoteSelect v-model="qualifyForm.partnerId" type="CUSTOMER" @change="onPartnerChange" /></el-form-item>
        <el-form-item label="联系人"><el-select v-model="qualifyForm.contactId" clearable placeholder="可不选"><el-option v-for="item in contacts" :key="item.id" :label="`${item.name} ${item.phone}`" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="同时创建商机"><el-switch v-model="qualifyForm.createOpportunity" /></el-form-item>
        <template v-if="qualifyForm.createOpportunity">
          <el-form-item label="商机名称" required><el-input v-model="qualifyForm.opportunityTitle" /></el-form-item>
          <el-form-item label="预计成交日"><el-date-picker v-model="qualifyForm.expectedCloseDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        </template>
      </el-form>
      <template #footer><el-button @click="qualifyVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="qualify">确认转化</el-button></template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑待跟进线索" width="660px">
      <el-form v-if="detail" label-width="100px"><el-form-item label="主题" required><el-input v-model="editForm.subject" /></el-form-item><el-form-item label="公司"><el-input v-model="editForm.companyName" /></el-form-item><el-form-item label="联系人"><el-input v-model="editForm.contactName" /></el-form-item><el-form-item label="电话"><el-input v-model="editForm.phone" /></el-form-item><el-form-item label="邮箱"><el-input v-model="editForm.email" /></el-form-item><el-form-item label="微信"><el-input v-model="editForm.wechat" /></el-form-item><el-form-item label="预计金额"><el-input-number v-model="editForm.estimatedAmount" :min="0" :precision="2" :controls="false" /></el-form-item><el-form-item label="需求"><el-input v-model="editForm.demand" type="textarea" :rows="3" /></el-form-item></el-form>
      <template #footer><el-button @click="editVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type Contact, type CrmLeadDetail } from '@/api/client'
import PageHeader from '@/components/PageHeader.vue'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import CrmActivityPlanPanel from '@/components/CrmActivityPlanPanel.vue'
import { crmEventName, crmSourceName, crmStatusName, crmStatusType, dateTimeText, moneyText } from '@/utils/crm'

const route = useRoute(); const router = useRouter(); const detail = ref<CrmLeadDetail>(); const loading = ref(false); const saving = ref(false)
const qualifyVisible = ref(false); const editVisible = ref(false); const contacts = ref<Contact[]>([])
const qualifyForm = reactive({ partnerId: undefined as number | undefined, contactId: undefined as number | undefined, createOpportunity: true, opportunityTitle: '', expectedCloseDate: '' })
const editForm = reactive({ subject: '', companyName: '', contactName: '', phone: '', email: '', wechat: '', sourceCode: 'OTHER', demand: '', estimatedAmount: 0, partnerId: undefined as number | undefined, contactId: undefined as number | undefined })
const id = () => Number(route.params.id)
const isActiveRoute = () => route.path === `/crm/leads/${id()}`

onMounted(load); watch(() => route.params.id, load)
async function load() { if (!id() || !isActiveRoute()) return; loading.value = true; try { detail.value = await api.crmLead(id()); if (detail.value) Object.assign(editForm, { ...detail.value.lead }) } finally { loading.value = false } }
function openQualify() { if (!detail.value) return; Object.assign(qualifyForm, { partnerId: detail.value.lead.partnerId || undefined, contactId: detail.value.lead.contactId || undefined, createOpportunity: true, opportunityTitle: detail.value.lead.subject, expectedCloseDate: '' }); qualifyVisible.value = true; if (qualifyForm.partnerId) onPartnerChange(qualifyForm.partnerId) }
async function onPartnerChange(value?: number | string) { qualifyForm.contactId = undefined; contacts.value = typeof value === 'number' ? await api.contacts(value) : [] }
async function qualify() { if (!qualifyForm.partnerId || (qualifyForm.createOpportunity && !qualifyForm.opportunityTitle.trim())) { ElMessage.warning('请选择客户主档并填写商机名称'); return } saving.value = true; try { await api.qualifyCrmLead(id(), qualifyForm); ElMessage.success('线索已转为潜在客户'); qualifyVisible.value = false; await load() } finally { saving.value = false } }
async function disqualify() { const result = await ElMessageBox.prompt('无效线索不会删除，可填写原因后重新激活。', '判定线索无效', { inputType: 'textarea', inputValidator: (value) => Boolean(value.trim()) || '请填写原因' }).catch(() => null); if (!result) return; await api.disqualifyCrmLead(id(), { reasonCode: 'OTHER', reason: result.value }); ElMessage.success('线索已判定无效'); load() }
async function reactivate() { const result = await ElMessageBox.prompt('请填写重新激活原因，原状态历史会保留。', '重新激活线索', { inputType: 'textarea', inputValidator: (value) => Boolean(value.trim()) || '请填写原因' }).catch(() => null); if (!result) return; await api.reactivateCrmLead(id(), { reasonCode: 'OTHER', reason: result.value }); ElMessage.success('线索已重新激活'); load() }
async function saveEdit() { if (!editForm.subject.trim()) { ElMessage.warning('请填写主题'); return } saving.value = true; try { await api.updateCrmLead(id(), editForm); ElMessage.success('线索已更新'); editVisible.value = false; await load() } finally { saving.value = false } }
</script>

<style scoped>
.audit-panel { margin-top: 16px; }.dialog-form { margin-top: 16px; }
</style>
