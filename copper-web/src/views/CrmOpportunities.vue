<template>
  <div class="panel">
    <div class="panel-header"><div><div class="panel-title">商机</div><div class="muted">阶段只向前推进；成交、流失和重新激活必须保留原因，不允许拖动后静默改状态。</div></div><div><el-radio-group v-model="viewMode"><el-radio-button value="kanban">看板</el-radio-button><el-radio-button value="list">列表</el-radio-button></el-radio-group><el-button type="primary" class="create-button" @click="openCreate">新建商机</el-button></div></div>
    <div class="filters"><el-input v-model="keyword" clearable placeholder="商机号 / 名称 / 客户" @keyup.enter="load" /><el-select v-model="stage" clearable placeholder="全部阶段" @change="load"><el-option v-for="item in stageOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select><el-button @click="load">查询</el-button></div>
    <div v-if="viewMode === 'kanban'" v-loading="loading" class="kanban">
      <section v-for="column in openStages" :key="column.value" class="kanban-column"><header><span>{{ column.label }}</span><el-tag size="small">{{ grouped[column.value]?.length || 0 }}</el-tag></header><button v-for="item in grouped[column.value] || []" :key="item.id" class="opportunity-card" @click="router.push(`/crm/opportunities/${item.id}`)"><strong>{{ item.title }}</strong><span>{{ item.partnerName }}</span><span>¥{{ moneyText(item.estimatedAmount) }} · {{ item.probability }}%</span><small>预计 {{ item.expectedCloseDate || '未定' }}</small></button><el-empty v-if="!grouped[column.value]?.length" description="暂无" :image-size="50" /></section>
    </div>
    <el-table v-else v-loading="loading" :data="rows" @row-dblclick="openOpportunity">
      <el-table-column label="商机号" width="165"><template #default="{ row }"><el-button link type="primary" @click="router.push(`/crm/opportunities/${row.id}`)">{{ row.opportunityNo }}</el-button></template></el-table-column><el-table-column prop="title" label="商机" min-width="210" /><el-table-column prop="partnerName" label="客户" min-width="160" /><el-table-column label="阶段" width="110"><template #default="{ row }"><el-tag :type="crmStatusType(row.stage)">{{ crmStatusName(row.stage) }}</el-tag></template></el-table-column><el-table-column label="预计金额" align="right"><template #default="{ row }">¥{{ moneyText(row.estimatedAmount) }}</template></el-table-column><el-table-column prop="probability" label="概率%" width="90" /><el-table-column prop="expectedCloseDate" label="预计成交" width="115" /><el-table-column prop="ownerName" label="负责人" width="110" />
    </el-table>

    <el-dialog v-model="createVisible" title="新建商机" width="680px" destroy-on-close>
      <el-form label-width="105px"><el-form-item label="客户主档" required><PartnerRemoteSelect v-model="form.partnerId" type="CUSTOMER" @change="onPartnerChange" /></el-form-item><el-form-item label="联系人"><el-select v-model="form.contactId" clearable><el-option v-for="item in contacts" :key="item.id" :label="`${item.name} ${item.phone}`" :value="item.id" /></el-select></el-form-item><el-form-item label="商机名称" required><el-input v-model="form.title" /></el-form-item><el-form-item label="预计金额"><el-input-number v-model="form.estimatedAmount" :min="0" :precision="2" :controls="false" /></el-form-item><el-form-item label="初始概率"><el-input-number v-model="form.probability" :min="0" :max="100" :precision="2" :controls="false" /></el-form-item><el-form-item label="预计成交日"><el-date-picker v-model="form.expectedCloseDate" type="date" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="客户需求"><el-input v-model="form.demand" type="textarea" :rows="3" /></el-form-item><el-form-item label="竞争对手"><el-input v-model="form.competitor" /></el-form-item></el-form>
      <template #footer><el-button @click="createVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">创建商机</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, type Contact, type CrmOpportunity } from '@/api/client'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import { crmStatusName, crmStatusType, moneyText } from '@/utils/crm'

const route = useRoute(); const router = useRouter(); const rows = ref<CrmOpportunity[]>([]); const loading = ref(false); const saving = ref(false); const createVisible = ref(false); const contacts = ref<Contact[]>([])
const viewMode = ref<'kanban' | 'list'>('kanban'); const keyword = ref(''); const stage = ref(String(route.query.stage || ''))
const stageOptions = [{ label: '资格确认', value: 'QUALIFY' }, { label: '需求分析', value: 'NEEDS' }, { label: '方案报价', value: 'PROPOSAL' }, { label: '商务谈判', value: 'NEGOTIATION' }, { label: '已成交', value: 'WON' }, { label: '已流失', value: 'LOST' }]; const openStages = stageOptions.slice(0, 4)
const form = reactive({ partnerId: undefined as number | undefined, contactId: undefined as number | undefined, title: '', probability: 10, estimatedAmount: 0, expectedCloseDate: '', demand: '', competitor: '' })
const grouped = computed(() => rows.value.reduce<Record<string, CrmOpportunity[]>>((map, item) => { (map[item.stage] ||= []).push(item); return map }, {}))
onMounted(async () => { await load(); if (route.query.create === '1') { openCreate(); const partnerId = Number(route.query.partnerId); if (partnerId) { form.partnerId = partnerId; await onPartnerChange(partnerId) } } })
async function load() { loading.value = true; try { rows.value = await api.crmOpportunities({ stage: stage.value || undefined, keyword: keyword.value.trim() || undefined }) } finally { loading.value = false } }
function openOpportunity(row: CrmOpportunity) { router.push(`/crm/opportunities/${row.id}`) }
function openCreate() { Object.assign(form, { partnerId: undefined, contactId: undefined, title: '', probability: 10, estimatedAmount: 0, expectedCloseDate: '', demand: '', competitor: '' }); contacts.value = []; createVisible.value = true }
async function onPartnerChange(value?: number | string) { form.contactId = undefined; contacts.value = typeof value === 'number' ? await api.contacts(value) : [] }
async function save() { if (!form.partnerId || !form.title.trim()) { ElMessage.warning('请选择客户并填写商机名称'); return } saving.value = true; try { const id = await api.createCrmOpportunity(form); ElMessage.success('商机已创建'); createVisible.value = false; await load(); router.push(`/crm/opportunities/${id}`) } finally { saving.value = false } }
</script>

<style scoped>
.panel-header, .filters { display:flex;align-items:center;justify-content:space-between;gap:12px}.filters{justify-content:flex-start;margin:14px 0}.filters .el-input{width:340px}.filters .el-select{width:150px}.create-button{margin-left:10px}.kanban{display:grid;grid-template-columns:repeat(4,minmax(230px,1fr));gap:12px;overflow-x:auto}.kanban-column{background:var(--el-fill-color-light);border-radius:10px;padding:10px;min-height:420px}.kanban-column header{display:flex;justify-content:space-between;align-items:center;font-weight:600;margin-bottom:10px}.opportunity-card{display:grid;gap:6px;width:100%;border:1px solid var(--el-border-color-light);border-radius:8px;background:var(--el-bg-color);padding:12px;margin-bottom:9px;text-align:left;color:inherit;cursor:pointer}.opportunity-card span,.opportunity-card small{color:var(--el-text-color-secondary)}
</style>
