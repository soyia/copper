<template>
  <div class="page operations-assistant">
    <div class="assistant-heading">
      <div>
        <div class="page-title">经营助手</div>
        <div class="muted">只给老板需要的建议和数字；建议不会自动下单，导入也必须先预览确认。</div>
      </div>
      <el-button :loading="loading" @click="refreshAll">刷新</el-button>
    </div>

    <el-alert
      v-if="summaryQuery.isError.value || replenishmentQuery.isError.value || contributionQuery.isError.value"
      type="warning"
      :closable="false"
      show-icon
      title="部分经营数据读取失败；页面不会用旧汇总冒充当前结果，请刷新重试。"
    />

    <section class="summary-grid" v-loading="summaryQuery.isFetching.value">
      <button v-for="item in summaryCards" :key="item.label" type="button" class="summary-card" @click="item.to && router.push(item.to)">
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
        <small>{{ item.note }}</small>
      </button>
    </section>

    <el-tabs v-model="activeTab" class="assistant-tabs">
      <el-tab-pane label="补货与库龄" name="replenishment">
        <div class="panel">
          <div class="panel-header responsive-header">
            <div>
              <div class="panel-title">该补什么、哪些货压久了</div>
              <div class="muted">按近30天出库、供应交期、14天安全期、当前可用量和在途采购计算。系统只建议，不自动生成采购单。</div>
            </div>
            <el-segmented v-model="replenishmentScope" :options="replenishmentScopes" size="small" />
          </div>
          <el-table :data="visibleReplenishment" border size="small" show-overflow-tooltip v-loading="replenishmentQuery.isFetching.value">
            <el-table-column prop="productName" label="商品" min-width="160" fixed="left">
              <template #default="{ row }"><strong>{{ row.productName }}</strong><div class="muted">{{ row.sku }}</div></template>
            </el-table-column>
            <el-table-column prop="warehouseName" label="仓库" width="110" />
            <el-table-column label="可用" width="110" align="right"><template #default="{ row }">{{ quantity(row.freeQuantity) }} {{ row.baseUnit }}</template></el-table-column>
            <el-table-column label="在途采购" width="110" align="right"><template #default="{ row }">{{ quantity(row.incomingQuantity) }}</template></el-table-column>
            <el-table-column label="近30天出库" width="120" align="right"><template #default="{ row }">{{ quantity(row.sales30Days) }}</template></el-table-column>
            <el-table-column label="可覆盖" width="100" align="right"><template #default="{ row }">{{ row.coverageDays == null ? '无近期出库' : `${row.coverageDays}天` }}</template></el-table-column>
            <el-table-column label="建议采购" width="120" align="right"><template #default="{ row }"><strong :class="{ 'text-danger': row.suggestedQuantity > 0 }">{{ quantity(row.suggestedQuantity) }}</strong></template></el-table-column>
            <el-table-column label="最老库龄" width="110" align="right"><template #default="{ row }">{{ row.oldestBatchDate ? `${row.oldestBatchAgeDays}天` : '-' }}</template></el-table-column>
            <el-table-column label="原因" min-width="310"><template #default="{ row }">{{ row.explanation }}</template></el-table-column>
            <el-table-column label="操作" width="100" fixed="right"><template #default="{ row }"><el-button v-if="row.suggestedQuantity > 0" link type="primary" @click="openPurchase(row)">去采购</el-button><el-button v-else link @click="router.push({ path: '/inventory', query: { productId: row.productId, warehouseId: row.warehouseId } })">看库存</el-button></template></el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <el-tab-pane label="销售贡献" name="contribution">
        <div class="panel">
          <div class="panel-header responsive-header">
            <div>
              <div class="panel-title">卖一单真正留下多少</div>
              <div class="muted">销售额 - 已出库批次成本 - 已归集销售费用。费用必须来自已确认费用源单，不重复记钱、不改变会计凭证。</div>
            </div>
            <el-date-picker v-model="contributionRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" @change="reloadContributions" />
          </div>
          <el-table :data="contributionQuery.data.value ?? []" border size="small" show-overflow-tooltip v-loading="contributionQuery.isFetching.value">
            <el-table-column label="单据" min-width="180" fixed="left"><template #default="{ row }"><router-link class="table-link" :to="sourcePath(row)">{{ row.sourceNo }}</router-link><div class="muted">{{ row.partnerName }}</div></template></el-table-column>
            <el-table-column prop="businessDate" label="日期" width="110" />
            <el-table-column label="销售额" width="125" align="right"><template #default="{ row }">{{ money(row.revenue) }}</template></el-table-column>
            <el-table-column label="库存成本" width="125" align="right"><template #default="{ row }">{{ money(row.inventoryCost) }}</template></el-table-column>
            <el-table-column label="归集费用" width="120" align="right"><template #default="{ row }">{{ money(row.allocatedExpense) }}</template></el-table-column>
            <el-table-column label="毛利" width="125" align="right"><template #default="{ row }">{{ money(row.grossMargin) }}</template></el-table-column>
            <el-table-column label="贡献毛利" width="130" align="right"><template #default="{ row }"><strong :class="row.contributionMargin < 0 ? 'text-danger' : 'text-success'">{{ money(row.contributionMargin) }}</strong></template></el-table-column>
            <el-table-column label="还没收" width="125" align="right"><template #default="{ row }">{{ money(row.outstandingAmount) }}</template></el-table-column>
          </el-table>
        </div>
      </el-tab-pane>

      <el-tab-pane label="7/30天现金窗口" name="cash-window">
        <CashWindowPanel />
      </el-tab-pane>

      <el-tab-pane label="单据识别候选" name="document-intake">
        <DocumentIntakePanel />
      </el-tab-pane>

      <el-tab-pane label="导入与对外协同" name="integration">
        <div class="intake-grid">
          <button v-if="appStore.hasPermission('financial.bank.view')" type="button" class="intake-card" @click="router.push('/accounting/bank-reconciliation')">
            <strong>银行流水导入</strong><span>在银行对账单中上传 CSV/XLSX，先预览、去重和匹配，再确认导入。</span><em>进入银行对账 →</em>
          </button>
          <button v-if="appStore.hasPermission('tax.compliance.view')" type="button" class="intake-card" @click="router.push('/tax-compliance')">
            <strong>电子凭证归档</strong><span>上传真实电子凭证，保留原文件、校验结果和票账税关联。</span><em>进入电子凭证 →</em>
          </button>
          <button v-if="appStore.hasPermission('invoice.view')" type="button" class="intake-card" @click="router.push('/tax-invoices')">
            <strong>发票清单导入</strong><span>使用模板批量登记后再做票货款账勾稽；导入不等于收付款。</span><em>进入发票工作台 →</em>
          </button>
          <button v-if="appStore.hasPermission('data.migration.view')" type="button" class="intake-card" @click="router.push('/data-migrations')">
            <strong>期初数据导入</strong><span>仅用于上线迁移；预检、应用和切换全程保留可核对结果。</span><em>进入期初迁移 →</em>
          </button>
        </div>

        <el-alert
          title="当前没有配置银行或税务平台直连。页面只展示已经真实可用的文件导入，避免把未接通的能力包装成自动化。"
          type="info"
          :closable="false"
          show-icon
          style="margin: 14px 0"
        />

        <div v-if="appStore.hasPermission('integration.key.manage')" class="panel">
          <div class="panel-header">
            <div><div class="panel-title">往来单位只读订单接口</div><div class="muted">只允许读取指定往来单位的采购/销售订单；密钥仅创建时显示一次。</div></div>
            <el-button type="primary" @click="apiKeyDialogVisible = true">创建密钥</el-button>
          </div>
          <el-table :data="apiKeyQuery.data.value ?? []" border size="small" show-overflow-tooltip>
            <el-table-column prop="keyName" label="名称" min-width="150" />
            <el-table-column prop="partnerName" label="往来单位" min-width="150" />
            <el-table-column prop="scopes" label="范围" width="120" />
            <el-table-column prop="expiresAt" label="到期" width="170" />
            <el-table-column label="调用" width="90" align="right"><template #default="{ row }">{{ row.useCount }} 次</template></el-table-column>
            <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ keyStatus(row.status) }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="100"><template #default="{ row }"><el-button v-if="row.status === 'ACTIVE'" link type="danger" @click="revokeKey(row.id)">撤销</el-button></template></el-table-column>
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="apiKeyDialogVisible" title="创建只读订单密钥" width="540px">
      <el-form label-position="top">
        <el-form-item label="密钥名称"><el-input v-model="apiKeyForm.keyName" maxlength="128" placeholder="例如：某客户订单查询" /></el-form-item>
        <el-form-item label="限定往来单位"><PartnerRemoteSelect v-model="apiKeyForm.partnerId" clearable /></el-form-item>
        <el-form-item label="到期时间"><el-date-picker v-model="apiKeyForm.expiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="apiKeyDialogVisible=false">取消</el-button><el-button type="primary" :loading="savingKey" @click="createKey">创建并显示一次密钥</el-button></template>
    </el-dialog>

    <el-dialog v-model="tokenDialogVisible" title="请立即保存密钥" width="640px" :close-on-click-modal="false">
      <el-alert title="关闭后系统不会再次显示完整密钥。请通过安全渠道交给该往来单位，泄露后立即撤销。" type="warning" :closable="false" show-icon />
      <el-input :model-value="createdToken" readonly class="token-input" />
      <template #footer><el-button type="primary" @click="copyToken">复制密钥</el-button><el-button @click="tokenDialogVisible=false">我已保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { api, type ContributionRow, type ReplenishmentRow } from '@/api/client'
import { money, quantity } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import CashWindowPanel from '@/components/CashWindowPanel.vue'
import DocumentIntakePanel from '@/components/DocumentIntakePanel.vue'
import { useAppStore } from '@/stores/app'

const router = useRouter()
const appStore = useAppStore()
const activeTab = ref('replenishment')
const replenishmentScope = ref<'ATTENTION' | 'ALL'>('ATTENTION')
const replenishmentScopes = [{ label: '只看要处理', value: 'ATTENTION' }, { label: '全部商品', value: 'ALL' }]
const contributionRange = ref<[string, string]>([monthStart(), today()])
const summaryQuery = useQuery({ queryKey: ['operations-owner-summary'], queryFn: api.ownerOperationsSummary })
const replenishmentQuery = useQuery({ queryKey: ['operations-replenishment'], queryFn: api.replenishmentSuggestions })
const contributionQuery = useQuery({
  queryKey: ['operations-contributions', contributionRange],
  queryFn: () => api.contributionRows({ from: contributionRange.value[0], to: contributionRange.value[1] }),
})
const apiKeyQuery = useQuery({
  queryKey: ['integration-keys'],
  queryFn: api.integrationKeys,
  enabled: computed(() => appStore.hasPermission('integration.key.manage')),
})
const loading = computed(() => summaryQuery.isFetching.value || replenishmentQuery.isFetching.value || contributionQuery.isFetching.value)
const visibleReplenishment = computed(() => (replenishmentQuery.data.value ?? []).filter((row) => replenishmentScope.value === 'ALL' || row.attentionLevel !== 'NORMAL'))
const summaryCards = computed(() => {
  const summary = summaryQuery.data.value
  return [
    { label: '今日销售', value: money(summary?.todaySales), note: '已确认销售', to: '/sales-orders' },
    { label: '本月贡献毛利', value: money(summary?.monthContributionMargin), note: '扣库存成本和已归集费用', to: null },
    { label: '现金及银行账面余额', value: money(summary?.availableCashAndBank), note: '已记账总账口径', to: '/treasury/journal' },
    { label: '逾期应收', value: money(summary?.overdueReceivable), note: '应该优先收回', to: '/receivables?scope=overdue' },
    { label: '7天内应付', value: money(summary?.duePayable7Days), note: '提前安排资金', to: '/payables' },
    { label: '180天以上库存', value: money(summary?.agedStockAmount), note: '先消化再补货', to: '/inventory' },
  ]
})

const apiKeyDialogVisible = ref(false)
const tokenDialogVisible = ref(false)
const createdToken = ref('')
const savingKey = ref(false)
const apiKeyForm = reactive({ keyName: '', partnerId: undefined as number | undefined, expiresAt: futureLocal(90) })

async function refreshAll() {
  await Promise.all([summaryQuery.refetch(), replenishmentQuery.refetch(), contributionQuery.refetch(), apiKeyQuery.refetch()])
}
function reloadContributions() { contributionQuery.refetch() }
function openPurchase(row: ReplenishmentRow) {
  router.push({ path: '/purchase-orders/new', query: { productId: row.productId, warehouseId: row.warehouseId, suggestedQuantity: row.suggestedQuantity } })
}
function sourcePath(row: ContributionRow) { return row.sourceType === 'SALES_ORDER' ? `/sales-orders/${row.sourceId}` : `/outbounds/${row.sourceId}` }
function keyStatus(status: string) { return ({ ACTIVE: '有效', REVOKED: '已撤销', EXPIRED: '已到期' } as Record<string, string>)[status] || status }
async function createKey() {
  if (!apiKeyForm.keyName.trim() || !apiKeyForm.partnerId || !apiKeyForm.expiresAt) return ElMessage.warning('请填写名称、往来单位和到期时间')
  savingKey.value = true
  try {
    const result = await api.createIntegrationKey({ keyName: apiKeyForm.keyName.trim(), partnerId: apiKeyForm.partnerId, expiresAt: apiKeyForm.expiresAt })
    createdToken.value = result.token
    apiKeyDialogVisible.value = false
    tokenDialogVisible.value = true
    apiKeyForm.keyName = ''
    apiKeyForm.partnerId = undefined
    apiKeyForm.expiresAt = futureLocal(90)
    await apiKeyQuery.refetch()
  } finally { savingKey.value = false }
}
async function revokeKey(id: number) {
  const { value } = await ElMessageBox.prompt('撤销后调用方会立即失去访问能力。请填写原因。', '撤销密钥', { inputPattern: /.{2,}/, inputErrorMessage: '至少填写2个字' })
  await api.revokeIntegrationKey(id, value)
  ElMessage.success('密钥已撤销')
  await apiKeyQuery.refetch()
}
async function copyToken() {
  await navigator.clipboard.writeText(createdToken.value)
  ElMessage.success('密钥已复制')
}
function today() { return new Date().toLocaleDateString('sv-SE') }
function monthStart() { const value = new Date(); value.setDate(1); return value.toLocaleDateString('sv-SE') }
function futureLocal(days: number) { const value = new Date(Date.now() + days * 86400000); return `${value.toLocaleDateString('sv-SE')}T${value.toTimeString().slice(0, 8)}` }
</script>

<style scoped>
.operations-assistant { display: grid; grid-template-columns: minmax(0, 1fr); min-width: 0; gap: 14px; }
.assistant-heading, .responsive-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.page-title { font-size: 24px; font-weight: 700; }
.summary-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 10px; }
.summary-card { display: grid; gap: 8px; min-height: 122px; padding: 15px; border: 1px solid var(--el-border-color-light); border-radius: 10px; background: var(--el-bg-color); color: inherit; text-align: left; cursor: pointer; }
.summary-card strong { font-size: 20px; font-variant-numeric: tabular-nums; }
.summary-card small, .intake-card span { color: var(--el-text-color-secondary); line-height: 1.5; }
.assistant-tabs { padding: 0 14px 14px; border: 1px solid var(--el-border-color-light); border-radius: 10px; background: var(--el-bg-color); }
.assistant-tabs .panel { box-shadow: none; }
.intake-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.intake-card { display: grid; gap: 9px; min-height: 146px; padding: 16px; border: 1px solid var(--el-border-color-light); border-radius: 10px; background: var(--el-bg-color); color: inherit; text-align: left; cursor: pointer; }
.intake-card:hover, .summary-card:hover { border-color: var(--el-color-primary-light-5); box-shadow: 0 5px 16px rgb(31 45 61 / 7%); }
.intake-card em { color: var(--el-color-primary); font-style: normal; font-weight: 600; }
.token-input { margin-top: 16px; }
@media (max-width: 1280px) { .summary-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } .intake-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 760px) {
  .assistant-heading, .responsive-header { flex-direction: column; }
  .summary-grid, .intake-grid { grid-template-columns: 1fr; }
  .summary-card { min-height: 96px; }
  :deep(.el-date-editor--daterange) { width: 100%; }
}
</style>
