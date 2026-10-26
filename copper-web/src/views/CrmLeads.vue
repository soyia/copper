<template>
  <div class="panel">
    <div class="panel-header">
      <div><div class="panel-title">线索</div><div class="muted">原始询盘先查重；确认主体后可选择共享往来单位主档，但不强制创建销售订单。</div></div>
      <el-button type="primary" @click="openCreate">新增线索</el-button>
    </div>
    <div class="filters">
      <el-input v-model="keyword" clearable placeholder="线索号 / 主题 / 公司 / 联系人 / 电话" @keyup.enter="load" />
      <el-select v-model="status" clearable placeholder="全部状态" @change="load"><el-option label="待跟进" value="OPEN" /><el-option label="已转潜在客户" value="QUALIFIED" /><el-option label="已判无效" value="DISQUALIFIED" /></el-select>
      <el-button @click="load">查询</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" @row-dblclick="openDetail">
      <el-table-column label="线索号" width="165" fixed="left"><template #default="{ row }"><el-button link type="primary" @click="openDetail(row)">{{ row.leadNo }}</el-button></template></el-table-column>
      <el-table-column prop="subject" label="主题" min-width="210" show-overflow-tooltip />
      <el-table-column prop="companyName" label="公司 / 主体" min-width="170" />
      <el-table-column prop="contactName" label="联系人" width="110" />
      <el-table-column prop="phone" label="电话" width="135" />
      <el-table-column label="来源" width="110"><template #default="{ row }">{{ crmSourceName(row.sourceCode) }}</template></el-table-column>
      <el-table-column label="预计金额" width="130" align="right"><template #default="{ row }">¥{{ moneyText(row.estimatedAmount) }}</template></el-table-column>
      <el-table-column label="状态" width="120"><template #default="{ row }"><el-tag :type="crmStatusType(row.status)">{{ crmStatusName(row.status) }}</el-tag></template></el-table-column>
      <el-table-column prop="ownerName" label="负责人" width="110" />
      <el-table-column label="更新时间" width="155"><template #default="{ row }">{{ dateTimeText(row.updatedAt) }}</template></el-table-column>
    </el-table>

    <el-dialog v-model="createVisible" title="新增线索" width="720px" destroy-on-close>
      <el-alert title="先查重再保存" description="同名、同电话或同邮箱不应重复建客户；如果这是已有客户的新询盘，请选择已有往来单位主档。" type="info" :closable="false" show-icon />
      <el-form label-width="100px" class="dialog-form">
        <el-form-item label="线索主题" required><el-input v-model="form.subject" maxlength="128" /></el-form-item>
        <el-form-item label="公司 / 主体"><el-input v-model="form.companyName" maxlength="128" @blur="checkDuplicates" /></el-form-item>
        <el-form-item label="联系人"><el-input v-model="form.contactName" maxlength="64" /></el-form-item>
        <el-form-item label="电话"><el-input v-model="form.phone" maxlength="32" @blur="checkDuplicates" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="form.email" maxlength="128" @blur="checkDuplicates" /></el-form-item>
        <el-form-item label="微信"><el-input v-model="form.wechat" maxlength="64" /></el-form-item>
        <el-form-item label="已有客户"><PartnerRemoteSelect v-model="form.partnerId" type="CUSTOMER" clearable placeholder="重复询盘可选择已有客户主档" /></el-form-item>
        <el-form-item label="线索来源"><el-select v-model="form.sourceCode"><el-option v-for="item in sourceOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        <el-form-item label="预计金额"><el-input-number v-model="form.estimatedAmount" :min="0" :precision="2" :controls="false" /></el-form-item>
        <el-form-item label="客户需求"><el-input v-model="form.demand" type="textarea" :rows="3" maxlength="1000" /></el-form-item>
      </el-form>
      <div v-if="duplicates.length" class="duplicate-box">
        <div class="duplicate-title">发现 {{ duplicates.length }} 条相似记录</div>
        <div v-for="item in duplicates" :key="`${item.matchType}:${item.sourceId}`" class="duplicate-row">{{ item.name }} · {{ item.phone || item.email || item.sourceNo }} · {{ item.status }}</div>
      </div>
      <template #footer><el-button @click="createVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存线索</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api, type CrmDuplicateCandidate, type CrmLead } from '@/api/client'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import { crmSourceName, crmStatusName, crmStatusType, dateTimeText, moneyText } from '@/utils/crm'

const router = useRouter()
const rows = ref<CrmLead[]>([])
const loading = ref(false)
const saving = ref(false)
const createVisible = ref(false)
const keyword = ref('')
const status = ref('OPEN')
const duplicates = ref<CrmDuplicateCandidate[]>([])
const sourceOptions = [{ label: '客户转介绍', value: 'REFERRAL' }, { label: '电话咨询', value: 'PHONE' }, { label: '微信咨询', value: 'WECHAT' }, { label: '门店来访', value: 'STORE' }, { label: '平台获客', value: 'PLATFORM' }, { label: '其他', value: 'OTHER' }]
const form = reactive({ subject: '', companyName: '', contactName: '', phone: '', email: '', wechat: '', partnerId: undefined as number | undefined, sourceCode: 'OTHER', estimatedAmount: 0, demand: '' })

onMounted(load)
async function load() { loading.value = true; try { rows.value = await api.crmLeads({ status: status.value || undefined, keyword: keyword.value.trim() || undefined }) } finally { loading.value = false } }
function openDetail(row: CrmLead) { router.push(`/crm/leads/${row.id}`) }
function openCreate() { Object.assign(form, { subject: '', companyName: '', contactName: '', phone: '', email: '', wechat: '', partnerId: undefined, sourceCode: 'OTHER', estimatedAmount: 0, demand: '' }); duplicates.value = []; createVisible.value = true }
async function checkDuplicates() { duplicates.value = await api.crmDuplicates({ companyName: form.companyName || undefined, phone: form.phone || undefined, email: form.email || undefined }) }
async function save() {
  if (!form.subject.trim()) { ElMessage.warning('请填写线索主题'); return }
  await checkDuplicates()
  if (duplicates.value.length && !form.partnerId) { ElMessage.warning('发现相似记录，请先选择已有客户主档或调整错误信息'); return }
  saving.value = true
  try { const id = await api.createCrmLead(form); ElMessage.success('线索已创建'); createVisible.value = false; await load(); router.push(`/crm/leads/${id}`) } finally { saving.value = false }
}
</script>

<style scoped>
.panel-header, .filters { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.filters { justify-content: flex-start; margin: 14px 0; }
.filters .el-input { width: 360px; } .filters .el-select { width: 160px; }
.dialog-form { margin-top: 16px; }
.duplicate-box { border: 1px solid var(--el-color-warning-light-5); background: var(--el-color-warning-light-9); border-radius: 8px; padding: 12px; }
.duplicate-title { font-weight: 600; margin-bottom: 6px; }.duplicate-row { color: var(--el-text-color-regular); line-height: 1.8; }
</style>
