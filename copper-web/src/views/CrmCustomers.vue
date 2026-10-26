<template>
  <div class="panel">
    <div class="panel-header"><div><div class="panel-title">客户经营档案</div><div class="muted">CRM 只维护客户分层、负责人、潜力和下一步；名称、身份、联系人仍由共享往来单位主档负责。</div></div><el-button type="primary" @click="openCreate">建立经营档案</el-button></div>
    <div class="filters"><el-input v-model="keyword" clearable placeholder="客户编号 / 名称 / 联系人 / 电话" @keyup.enter="load" /><el-select v-model="lifecycleStage" clearable placeholder="全部生命周期" @change="load"><el-option label="潜在客户" value="PROSPECT" /><el-option label="活跃客户" value="ACTIVE" /><el-option label="沉睡客户" value="DORMANT" /><el-option label="已流失" value="LOST" /></el-select><el-select v-model="tierCode" clearable placeholder="全部分层" @change="load"><el-option v-for="item in tierOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select><el-button @click="load">查询</el-button></div>
    <el-table v-loading="loading" :data="rows" @row-dblclick="openDetail">
      <el-table-column prop="businessNo" label="客户编号" width="110" fixed="left" />
      <el-table-column label="客户" min-width="190" fixed="left"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row)">{{ row.partnerName }}</el-button></template></el-table-column>
      <el-table-column label="生命周期" width="110"><template #default="{ row }"><el-tag :type="crmStatusType(row.lifecycleStage)">{{ crmStatusName(row.lifecycleStage) }}</el-tag></template></el-table-column>
      <el-table-column label="客户分层" width="100"><template #default="{ row }">{{ crmTierName(row.tierCode) }}</template></el-table-column>
      <el-table-column prop="contactName" label="主要联系人" width="120" />
      <el-table-column prop="contactPhone" label="电话" width="135" />
      <el-table-column label="年度潜力" width="130" align="right"><template #default="{ row }">¥{{ moneyText(row.annualPotential) }}</template></el-table-column>
      <el-table-column label="商机金额" width="130" align="right"><template #default="{ row }">¥{{ moneyText(row.openPipelineAmount) }}</template></el-table-column>
      <el-table-column prop="nextAction" label="下一步" min-width="180" show-overflow-tooltip />
      <el-table-column prop="nextActionDate" label="计划日期" width="110" />
      <el-table-column prop="ownerName" label="负责人" width="110" />
      <el-table-column v-if="canCreateSales || canManageCrm" label="快捷操作" width="150" fixed="right"><template #default="{ row }"><el-button v-if="canCreateSales" link type="primary" @click.stop="directOrder(row.partnerId)">下单</el-button><el-button v-if="canManageCrm" link @click.stop="directQuote(row.partnerId)">报价</el-button></template></el-table-column>
    </el-table>

    <el-dialog v-model="formVisible" :title="editingId ? '编辑客户经营档案' : '建立客户经营档案'" width="720px" destroy-on-close>
      <el-alert title="不复制客户主档" description="这里通过 partnerId 引用往来单位，不在 CRM 再保存一套客户名称、身份或余额。" type="info" :closable="false" show-icon />
      <el-form label-width="110px" class="dialog-form">
        <el-form-item label="客户主档" required><PartnerRemoteSelect v-model="form.partnerId" type="CUSTOMER" :disabled="Boolean(editingId)" /></el-form-item>
        <el-form-item label="生命周期"><el-select v-model="form.lifecycleStage"><el-option label="潜在客户" value="PROSPECT" /><el-option label="活跃客户" value="ACTIVE" /><el-option label="沉睡客户" value="DORMANT" /><el-option label="已流失" value="LOST" /></el-select></el-form-item>
        <el-form-item label="客户分层"><el-select v-model="form.tierCode"><el-option v-for="item in tierOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="线索来源"><el-select v-model="form.sourceCode"><el-option v-for="item in sourceOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="标签"><el-input v-model="form.tags" placeholder="逗号分隔，例如：空调制冷、重点跟进" /></el-form-item>
        <el-form-item label="年度潜力"><el-input-number v-model="form.annualPotential" :min="0" :precision="2" :controls="false" /></el-form-item>
        <el-form-item label="偏好品类"><el-input v-model="form.preferredCategory" /></el-form-item>
        <el-form-item label="竞争对手"><el-input v-model="form.competitor" /></el-form-item>
        <el-form-item label="下一步"><el-input v-model="form.nextAction" maxlength="500" /></el-form-item>
        <el-form-item label="计划日期"><el-date-picker v-model="form.nextActionDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="复购提醒"><el-switch v-model="form.repurchaseReminderEnabled" active-text="开启" inactive-text="关闭" /></el-form-item>
        <el-form-item v-if="form.repurchaseReminderEnabled" label="建议周期"><el-input-number v-model="form.repurchaseCycleDays" :min="1" :max="3650" :controls="false" /><span class="muted cycle-hint">天；只提醒，不阻断业务</span></el-form-item>
        <el-form-item v-if="form.lifecycleStage === 'LOST'" label="流失原因" required><el-input v-model="form.lostReason" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="formVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, type CrmCustomer } from '@/api/client'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import { crmStatusName, crmStatusType, crmTierName, moneyText } from '@/utils/crm'
import { useAppStore } from '@/stores/app'

const router = useRouter(); const appStore = useAppStore(); const rows = ref<CrmCustomer[]>([]); const loading = ref(false); const saving = ref(false); const formVisible = ref(false); const editingId = ref<number>()
const canCreateSales = computed(() => appStore.hasPermission('sales.view'))
const canManageCrm = computed(() => appStore.hasPermission('crm.record.manage'))
const keyword = ref(''); const lifecycleStage = ref(''); const tierCode = ref('')
const tierOptions = [{ label: '重点客户', value: 'KEY' }, { label: '成长客户', value: 'GROWTH' }, { label: '普通客户', value: 'NORMAL' }, { label: '观察客户', value: 'OBSERVE' }]
const sourceOptions = [{ label: '客户转介绍', value: 'REFERRAL' }, { label: '电话咨询', value: 'PHONE' }, { label: '微信咨询', value: 'WECHAT' }, { label: '门店来访', value: 'STORE' }, { label: '平台获客', value: 'PLATFORM' }, { label: '其他', value: 'OTHER' }]
const form = reactive({ partnerId: undefined as number | undefined, lifecycleStage: 'PROSPECT', tierCode: 'NORMAL', sourceCode: 'OTHER', tags: '', annualPotential: 0, preferredCategory: '', competitor: '', nextAction: '', nextActionDate: '', repurchaseReminderEnabled: true, repurchaseCycleDays: 30, wonReasonCode: '', lostReasonCode: 'OTHER', lostReason: '' })

onMounted(load)
async function load() { loading.value = true; try { rows.value = await api.crmCustomers({ lifecycleStage: lifecycleStage.value || undefined, tierCode: tierCode.value || undefined, keyword: keyword.value.trim() || undefined }) } finally { loading.value = false } }
function openDetail(row: CrmCustomer) { router.push(`/crm/customers/${row.id}`) }
function openCreate() { editingId.value = undefined; Object.assign(form, { partnerId: undefined, lifecycleStage: 'PROSPECT', tierCode: 'NORMAL', sourceCode: 'OTHER', tags: '', annualPotential: 0, preferredCategory: '', competitor: '', nextAction: '', nextActionDate: '', repurchaseReminderEnabled: true, repurchaseCycleDays: 30, wonReasonCode: '', lostReasonCode: 'OTHER', lostReason: '' }); formVisible.value = true }
function directOrder(partnerId: number) { router.push({ path: '/sales-orders/new', query: { customerId: partnerId } }) }
function directQuote(partnerId: number) { router.push({ path: '/crm/quotes/new', query: { partnerId } }) }
async function save() { if (!form.partnerId) { ElMessage.warning('请选择客户主档'); return } if (form.lifecycleStage === 'LOST' && !form.lostReason.trim()) { ElMessage.warning('流失客户必须填写原因'); return } saving.value = true; try { const id = editingId.value ? await api.updateCrmCustomer(editingId.value, form) : await api.createCrmCustomer(form); ElMessage.success('客户经营档案已保存'); formVisible.value = false; await load(); router.push(`/crm/customers/${id}`) } finally { saving.value = false } }
</script>

<style scoped>
.panel-header, .filters { display: flex; align-items: center; justify-content: space-between; gap: 12px; }.filters { justify-content: flex-start; margin: 14px 0; }.filters .el-input { width: 330px; }.filters .el-select { width: 150px; }.dialog-form { margin-top: 16px; }.cycle-hint{margin-left:10px}
</style>
