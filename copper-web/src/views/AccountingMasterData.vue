<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div><div class="panel-title">科目与会计期间</div><div class="muted master-subtitle">科目一旦被凭证使用，编码、类别和余额方向保持不变；期间关闭统一由“期末结账”执行完整业务、财务、税票和银行检查。</div></div>
        <el-button @click="refresh">刷新</el-button>
      </div>
      <el-tabs v-model="activeTab">
        <el-tab-pane label="会计科目" name="accounts">
          <div class="list-tools">
            <el-input v-model="accountKeyword" clearable placeholder="科目编码 / 名称" />
            <el-checkbox v-model="includeDisabled">显示已停用</el-checkbox>
            <span class="list-tools-actions"><el-button v-if="appStore.hasPermission('accounting.manage')" type="primary" @click="openAccount()">新增科目</el-button></span>
          </div>
          <el-table v-loading="accountQuery.isLoading.value" :data="filteredAccounts" border size="small" class="data-table" max-height="calc(100vh - 300px)">
            <el-table-column label="科目" min-width="230" fixed="left"><template #default="{ row }"><div :style="{ paddingLeft: `${(row.accountLevel - 1) * 18}px` }"><strong>{{ row.accountCode }}</strong> {{ row.accountName }}</div></template></el-table-column>
            <el-table-column label="类别" width="105"><template #default="{ row }">{{ categoryName(row.category) }}</template></el-table-column>
            <el-table-column label="余额方向" width="100"><template #default="{ row }">{{ row.normalDirection === 'DEBIT' ? '借' : '贷' }}</template></el-table-column>
            <el-table-column label="辅助核算" min-width="210"><template #default="{ row }">{{ auxiliaryText(row) }}</template></el-table-column>
            <el-table-column label="手工录入" width="100"><template #default="{ row }">{{ row.allowManual ? '允许' : '仅自动' }}</template></el-table-column>
            <el-table-column label="有效期" min-width="190"><template #default="{ row }">{{ row.effectiveFrom }} 至 {{ row.effectiveTo || '长期' }}</template></el-table-column>
            <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag size="small" :type="row.status === 'ENABLED' ? 'success' : 'info'">{{ row.status === 'ENABLED' ? '启用' : '停用' }}</el-tag><span v-if="row.used" class="used-mark">已使用</span></template></el-table-column>
            <el-table-column v-if="appStore.hasPermission('accounting.manage')" label="操作" width="80"><template #default="{ row }"><el-button link type="primary" @click="openAccount(row)">编辑</el-button></template></el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="会计期间" name="periods">
          <div class="list-tools">
            <el-input-number v-model="fiscalYear" :min="2000" :max="2200" :controls="false" />
            <el-button @click="periodQuery.refetch()">查询年度</el-button>
            <span class="list-tools-actions"><el-button v-if="appStore.hasPermission('accounting.period.manage')" type="primary" :loading="periodActing" @click="initializeYear">初始化 12 个期间</el-button></span>
          </div>
          <el-alert type="info" :closable="false" show-icon title="会计期间不再允许从主档直接锁定。请进入“期末结账”逐项完成库存、往来、税票、银行、凭证、报表和总账检查；反结账也必须从同一入口保留冲销和审计证据。" />
          <el-table v-loading="periodQuery.isLoading.value" :data="periods" border size="small" class="data-table period-table">
            <el-table-column prop="periodName" label="期间" min-width="140" fixed="left" />
            <el-table-column prop="startDate" label="开始日期" width="120" />
            <el-table-column prop="endDate" label="结束日期" width="120" />
            <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag size="small" :type="row.status === 'OPEN' ? 'success' : 'danger'">{{ row.status === 'OPEN' ? '开放' : '已结账' }}</el-tag></template></el-table-column>
            <el-table-column label="锁定信息" min-width="260"><template #default="{ row }"><span v-if="row.closedAt">{{ row.closedByName || '系统' }} · {{ formatDateTime(row.closedAt) }}</span><div v-if="row.closeReason" class="multiline-secondary">{{ row.closeReason }}</div><span v-if="!row.closedAt">-</span></template></el-table-column>
            <el-table-column v-if="appStore.hasPermission('financial.report.view')" label="操作" width="120"><template #default><RouterLink class="business-link" to="/accounting/period-close">进入期末结账</RouterLink></template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-dialog v-model="accountDialog" :title="accountForm.id ? '编辑会计科目' : '新增会计科目'" width="720px" destroy-on-close>
      <el-form label-position="top">
        <div class="form-grid form-grid-2">
          <el-form-item label="科目编码" required><el-input v-model="accountForm.accountCode" :disabled="Boolean(accountForm.used)" maxlength="32" /></el-form-item>
          <el-form-item label="科目名称" required><el-input v-model="accountForm.accountName" maxlength="128" /></el-form-item>
          <el-form-item label="科目类别" required><el-select v-model="accountForm.category" :disabled="Boolean(accountForm.used)"><el-option v-for="item in categories" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
          <el-form-item label="余额方向" required><el-select v-model="accountForm.normalDirection" :disabled="Boolean(accountForm.used)"><el-option label="借方" value="DEBIT" /><el-option label="贷方" value="CREDIT" /></el-select></el-form-item>
          <el-form-item label="上级科目"><el-select v-model="accountForm.parentId" clearable filterable><el-option v-for="item in parentAccounts" :key="item.id" :label="`${item.accountCode} ${item.accountName}`" :value="item.id" /></el-select></el-form-item>
          <el-form-item label="手工录入"><el-switch v-model="accountForm.allowManual" active-text="允许" inactive-text="仅自动规则" /></el-form-item>
          <el-form-item label="往来单位辅助"><el-select v-model="accountForm.partnerAuxiliary"><el-option v-for="item in auxiliaryOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
          <el-form-item label="商品辅助"><el-select v-model="accountForm.productAuxiliary"><el-option v-for="item in auxiliaryOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
          <el-form-item label="仓库辅助"><el-select v-model="accountForm.warehouseAuxiliary"><el-option v-for="item in auxiliaryOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
          <el-form-item label="状态"><el-select v-model="accountForm.status"><el-option label="启用" value="ENABLED" /><el-option label="停用" value="DISABLED" /></el-select></el-form-item>
          <el-form-item label="生效日期" required><el-date-picker v-model="accountForm.effectiveFrom" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item label="失效日期"><el-date-picker v-model="accountForm.effectiveTo" type="date" value-format="YYYY-MM-DD" clearable /></el-form-item>
        </div>
      </el-form>
      <template #footer><el-button @click="accountDialog = false">取消</el-button><el-button type="primary" :loading="accountSaving" @click="saveAccount">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { api, type AccountingAccount } from '@/api/client'
import { formatDateTime } from '@/api/format'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()
const queryClient = useQueryClient()
const activeTab = ref('accounts')
const accountKeyword = ref('')
const includeDisabled = ref(true)
const fiscalYear = ref(new Date().getFullYear())
const accountDialog = ref(false)
const accountSaving = ref(false)
const periodActing = ref(false)
const accountQuery = useQuery({ queryKey: ['accounting-accounts', 'all'], queryFn: () => api.accountingAccounts(true) })
const periodQuery = useQuery({ queryKey: computed(() => ['accounting-periods', fiscalYear.value]), queryFn: () => api.accountingPeriods(fiscalYear.value) })
const accounts = computed(() => accountQuery.data.value || [])
const periods = computed(() => periodQuery.data.value || [])
const filteredAccounts = computed(() => accounts.value.filter((row) => (includeDisabled.value || row.status === 'ENABLED') && (!accountKeyword.value.trim() || `${row.accountCode} ${row.accountName}`.toLowerCase().includes(accountKeyword.value.trim().toLowerCase()))))
const parentAccounts = computed(() => accounts.value.filter((row) => row.id !== accountForm.id && row.status === 'ENABLED'))
const categories = [{ label: '资产', value: 'ASSET' }, { label: '负债', value: 'LIABILITY' }, { label: '所有者权益', value: 'EQUITY' }, { label: '收入', value: 'REVENUE' }, { label: '费用', value: 'EXPENSE' }, { label: '成本', value: 'COST' }]
const auxiliaryOptions = [{ label: '不核算', value: 'NONE' }, { label: '选填', value: 'OPTIONAL' }, { label: '必填', value: 'REQUIRED' }]
const accountForm = reactive({ id: 0, used: false, accountCode: '', accountName: '', category: 'ASSET', normalDirection: 'DEBIT' as 'DEBIT' | 'CREDIT', parentId: 0 as number | undefined, allowManual: true, partnerAuxiliary: 'NONE', productAuxiliary: 'NONE', warehouseAuxiliary: 'NONE', effectiveFrom: `${new Date().getFullYear()}-01-01`, effectiveTo: null as string | null, status: 'ENABLED' })

function openAccount(row?: AccountingAccount) {
  Object.assign(accountForm, row ? { ...row, effectiveTo: row.effectiveTo || null } : { id: 0, used: false, accountCode: '', accountName: '', category: 'ASSET', normalDirection: 'DEBIT', parentId: 0, allowManual: true, partnerAuxiliary: 'NONE', productAuxiliary: 'NONE', warehouseAuxiliary: 'NONE', effectiveFrom: `${fiscalYear.value}-01-01`, effectiveTo: null, status: 'ENABLED' })
  accountDialog.value = true
}
async function saveAccount() {
  if (!accountForm.accountCode.trim() || !accountForm.accountName.trim() || !accountForm.effectiveFrom) { ElMessage.warning('请完整填写科目编码、名称和生效日期'); return }
  accountSaving.value = true
  try {
    const payload = { accountCode: accountForm.accountCode.trim(), accountName: accountForm.accountName.trim(), category: accountForm.category, normalDirection: accountForm.normalDirection, parentId: accountForm.parentId || 0, allowManual: accountForm.allowManual, partnerAuxiliary: accountForm.partnerAuxiliary, productAuxiliary: accountForm.productAuxiliary, warehouseAuxiliary: accountForm.warehouseAuxiliary, effectiveFrom: accountForm.effectiveFrom, effectiveTo: accountForm.effectiveTo || null, status: accountForm.status }
    if (accountForm.id) await api.updateAccountingAccount(accountForm.id, payload)
    else await api.createAccountingAccount(payload)
    ElMessage.success('会计科目已保存'); accountDialog.value = false; await queryClient.invalidateQueries({ queryKey: ['accounting-accounts'] })
  } finally { accountSaving.value = false }
}
async function initializeYear() { periodActing.value = true; try { const count = await api.initializeAccountingYear(fiscalYear.value); ElMessage.success(count ? `已新增 ${count} 个期间` : '该年度期间已经存在'); await periodQuery.refetch() } finally { periodActing.value = false } }
async function refresh() { await Promise.all([accountQuery.refetch(), periodQuery.refetch()]) }
function categoryName(value: string) { return Object.fromEntries(categories.map((item) => [item.value, item.label]))[value] || value }
function auxiliaryText(row: AccountingAccount) { const items = [['往来', row.partnerAuxiliary], ['商品', row.productAuxiliary], ['仓库', row.warehouseAuxiliary]].filter(([, value]) => value !== 'NONE').map(([name, value]) => `${name}${value === 'REQUIRED' ? '必填' : '选填'}`); return items.join('、') || '无' }
</script>

<style scoped>
.master-subtitle { margin-top: 5px; font-size: 12px; }
.used-mark { display: block; margin-top: 3px; color: #788792; font-size: 11px; }
.period-table { margin-top: 14px; }
</style>
