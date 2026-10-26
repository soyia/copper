<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">{{ pageTitle }}</div>
          <div class="muted tax-subtitle">{{ pageNote }}</div>
        </div>
        <div v-if="appStore.hasPermission('invoice.manage')" class="header-actions">
          <el-button @click="downloadTemplate">下载导入模板</el-button>
          <el-button :loading="importing" @click="importInput?.click()">导入电子发票台账</el-button>
          <template v-if="direction"><el-button @click="create('RED')">登记红字票</el-button><el-button type="primary" @click="create('BLUE')">登记蓝字票</el-button></template>
          <input ref="importInput" class="hidden-input" type="file" accept=".csv,text/csv" @change="importCsv" />
        </div>
      </div>

      <el-alert class="import-note" type="info" :closable="false" show-icon title="标准模板导入只生成发票草稿；原始 CSV、文件哈希和导入批次会留存。确认、查验和票货款账勾稽仍需逐步完成。" />

      <div class="list-tools">
        <el-input v-model="keyword" clearable placeholder="内部单号 / 发票号码 / 往来单位" />
        <el-select v-model="status" clearable placeholder="全部状态" style="width: 140px">
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已确认" value="CONFIRMED" />
          <el-option label="已作废" value="VOIDED" />
        </el-select>
        <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="发票开始日" end-placeholder="发票结束日" />
        <el-button :loading="query.isFetching.value" @click="query.refetch()">刷新</el-button>
      </div>

      <el-table v-loading="query.isLoading.value" :data="filteredRows" border size="small" class="data-table" max-height="calc(100vh - 286px)">
        <el-table-column label="发票" min-width="220" fixed="left">
          <template #default="{ row }">
            <RouterLink class="business-link" :to="`/tax-invoices/${row.id}`">{{ row.invoiceNumber }}</RouterLink>
            <div class="multiline-secondary">{{ row.internalNo }} · {{ colorName(row.invoiceColor) }} · {{ typeName(row.invoiceType) }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="invoiceDate" label="开票日期" width="112" />
        <el-table-column prop="partnerName" label="往来单位" min-width="170" show-overflow-tooltip />
        <el-table-column label="金额 / 税额" width="165" align="right">
          <template #default="{ row }"><div>{{ money(row.totalAmount) }}</div><div class="multiline-secondary">未税 {{ money(row.amountExcludingTax) }} · 税 {{ money(row.taxAmount) }}</div></template>
        </el-table-column>
        <el-table-column label="发票状态" width="102"><template #default="{ row }"><el-tag size="small" :type="statusType(row.status)">{{ statusName(row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="税务状态" min-width="170"><template #default="{ row }"><div>查验：{{ verifyName(row.verificationStatus) }}</div><div class="multiline-secondary">入账：{{ bookingName(row.bookingStatus) }}<template v-if="row.direction === 'INPUT'"> · 用途：{{ usageName(row.usageStatus) }}</template></div></template></el-table-column>
        <el-table-column label="勾稽" width="110"><template #default="{ row }"><el-tag size="small" :type="reconciliationType(row.reconciliationStatus)">{{ reconciliationName(row.reconciliationStatus) }}</el-tag></template></el-table-column>
        <el-table-column label="票货 / 票款" width="185" align="right"><template #default="{ row }"><div>{{ money(row.goodsMatchedAmount) }} / {{ money(row.totalAmount) }}</div><div class="multiline-secondary">{{ money(row.settlementMatchedAmount) }} / {{ money(row.totalAmount) }}</div></template></el-table-column>
        <el-table-column label="操作" width="120" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="$router.push(`/tax-invoices/${row.id}`)">详情</el-button><el-button v-if="row.status === 'DRAFT' && appStore.hasPermission('invoice.manage')" link @click="$router.push(`/tax-invoices/${row.id}/edit`)">编辑</el-button></template></el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { api, type TaxInvoiceColor, type TaxInvoiceDirection } from '@/api/client'
import { money } from '@/api/format'
import { useAppStore } from '@/stores/app'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const queryClient = useQueryClient()
const importInput = ref<HTMLInputElement>()
const importing = ref(false)
const direction = computed(() => (route.meta.direction as TaxInvoiceDirection | undefined))
const exceptionOnly = computed(() => Boolean(route.meta.exceptionOnly))
const status = ref('')
const keyword = ref('')
const dateRange = ref<[string, string] | null>(null)
const params = computed(() => ({ direction: direction.value, status: status.value || undefined, from: dateRange.value?.[0], to: dateRange.value?.[1], exceptionOnly: exceptionOnly.value }))
const query = useQuery({ queryKey: computed(() => ['tax-invoices', params.value]), queryFn: () => api.taxInvoices(params.value) })
const filteredRows = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  const rows = query.data.value || []
  return text ? rows.filter((row) => [row.internalNo, row.invoiceCode, row.invoiceNumber, row.partnerName].some((value) => String(value || '').toLowerCase().includes(text))) : rows
})
const pageTitle = computed(() => exceptionOnly.value ? '税票勾稽与异常' : direction.value === 'INPUT' ? '进项发票' : '销项发票')
const pageNote = computed(() => exceptionOnly.value ? '逐层核对合同、货物、往来结算、资金和会计凭证；差异不通过覆盖原票处理。' : direction.value === 'INPUT' ? '收到发票后分别登记查验、入账、抵扣或不抵扣，不以收票替代付款。' : '登记实际开具的发票及红冲结果，不以开票替代出库、应收或回款。')
function create(color: TaxInvoiceColor) { router.push({ path: '/tax-invoices/new', query: { direction: direction.value, color } }) }
async function downloadTemplate() {
  const result = await api.downloadTaxInvoiceImportTemplate()
  downloadBlob(result.blob, result.fileName)
}
async function importCsv(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  importing.value = true
  try {
    const result = await api.importTaxInvoices(file)
    ElMessage.success(`已导入 ${result.importedInvoiceCount} 张发票草稿、${result.importedLineCount} 行明细；原始 CSV 已留存`)
    await queryClient.invalidateQueries({ queryKey: ['tax-invoices'] })
    if (result.invoiceIds.length === 1) await router.push(`/tax-invoices/${result.invoiceIds[0]}`)
  } finally { importing.value = false; input.value = '' }
}
function downloadBlob(blob: Blob, fileName: string) { const url = URL.createObjectURL(blob); const anchor = document.createElement('a'); anchor.href = url; anchor.download = fileName; anchor.click(); URL.revokeObjectURL(url) }
function colorName(value: string) { return value === 'RED' ? '红字' : '蓝字' }
function typeName(value: string) { return ({ DIGITAL_SPECIAL: '数电专票', DIGITAL_NORMAL: '数电普票', VAT_SPECIAL: '增值税专票', VAT_NORMAL: '增值税普票', OTHER: '其他' } as Record<string, string>)[value] || value }
function statusName(value: string) { return ({ DRAFT: '草稿', CONFIRMED: '已确认', VOIDED: '已作废' } as Record<string, string>)[value] || value }
function statusType(value: string) { return value === 'CONFIRMED' ? 'success' : value === 'VOIDED' ? 'info' : 'primary' }
function verifyName(value: string) { return ({ UNVERIFIED: '待查验', VERIFIED: '已通过', FAILED: '查验异常' } as Record<string, string>)[value] || value }
function bookingName(value: string) { return value === 'CONFIRMED' ? '已确认' : '待确认' }
function usageName(value: string) { return ({ UNCONFIRMED: '待确认', DEDUCTION: '抵扣', NON_DEDUCTION: '不抵扣', TRANSFERRED_OUT: '已转出', NOT_APPLICABLE: '-' } as Record<string, string>)[value] || value }
function reconciliationName(value: string) { return ({ COMPLETE: '已完成', PARTIAL: '部分勾稽', UNMATCHED: '未勾稽' } as Record<string, string>)[value] || value }
function reconciliationType(value: string) { return value === 'COMPLETE' ? 'success' : value === 'PARTIAL' ? 'warning' : 'danger' }
</script>

<style scoped>
.tax-subtitle { margin-top: 5px; font-size: 12px; }
.header-actions { display: flex; gap: 8px; }
.import-note { margin: 12px 0; }
.hidden-input { display: none; }
</style>
