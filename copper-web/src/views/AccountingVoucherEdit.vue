<template>
  <div class="page" v-loading="detailQuery.isLoading.value">
    <PageHeader :title="isEdit ? '编辑手工凭证' : '新增手工凭证'" back-to="/accounting/vouchers" />
    <el-alert type="warning" :closable="false" show-icon title="手工凭证只用于非业务自动过账事项；已确认采购、销售、库存和收付款不得用手工凭证替代业务处理。" />

    <div class="panel voucher-form-panel">
      <el-form label-position="top">
        <div class="form-grid form-grid-3">
          <el-form-item label="凭证日期" required><el-date-picker v-model="form.voucherDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item class="form-span-2" label="凭证摘要" required><el-input v-model="form.summary" maxlength="500" show-word-limit /></el-form-item>
        </div>

        <div class="panel-header line-heading">
          <div><div class="panel-title">凭证分录</div><div class="muted">每行只能填写借方或贷方；辅助核算按科目要求填写。</div></div>
          <el-button @click="addLine">增加分录</el-button>
        </div>
        <el-table :data="form.lines" border size="small" class="data-table voucher-lines">
          <el-table-column min-width="210"><template #header>科目 <span class="table-required-mark">*</span></template><template #default="{ row }"><el-select v-model="row.accountId" filterable placeholder="选择末级科目" style="width:100%"><el-option v-for="account in manualAccounts" :key="account.id" :label="`${account.accountCode} ${account.accountName}`" :value="account.id" /></el-select></template></el-table-column>
          <el-table-column min-width="190"><template #header>摘要 <span class="table-required-mark">*</span></template><template #default="{ row }"><el-input v-model="row.summary" maxlength="500" /></template></el-table-column>
          <el-table-column width="175"><template #header>借方（本行二选一）<span class="table-required-mark">*</span></template><template #default="{ row }"><el-input-number v-model="row.debitAmount" :min="0" :precision="2" :controls="false" style="width:100%" @change="row.debitAmount > 0 && (row.creditAmount = 0)" /></template></el-table-column>
          <el-table-column width="175"><template #header>贷方（本行二选一）<span class="table-required-mark">*</span></template><template #default="{ row }"><el-input-number v-model="row.creditAmount" :min="0" :precision="2" :controls="false" style="width:100%" @change="row.creditAmount > 0 && (row.debitAmount = 0)" /></template></el-table-column>
          <el-table-column label="往来单位" min-width="150"><template #default="{ row }"><el-select v-model="row.partnerId" clearable filterable placeholder="按科目要求"><el-option v-for="item in partners" :key="item.id" :label="item.name" :value="item.id" /></el-select></template></el-table-column>
          <el-table-column label="商品" min-width="150"><template #default="{ row }"><el-select v-model="row.productId" clearable filterable placeholder="可选"><el-option v-for="item in products" :key="item.id" :label="`${item.sku} ${item.name}`" :value="item.id" /></el-select></template></el-table-column>
          <el-table-column label="仓库" min-width="120"><template #default="{ row }"><el-select v-model="row.warehouseId" clearable placeholder="可选"><el-option v-for="item in warehouses" :key="item.id" :label="item.name" :value="item.id" /></el-select></template></el-table-column>
          <el-table-column label="操作" width="74" fixed="right"><template #default="{ $index }"><el-button link type="danger" :disabled="form.lines.length <= 2" @click="form.lines.splice($index, 1)">删除</el-button></template></el-table-column>
        </el-table>

        <div class="voucher-total" :class="{ 'is-unbalanced': !balanced }">
          <span>借方合计：{{ money(debitTotal) }}</span><span>贷方合计：{{ money(creditTotal) }}</span>
          <strong>{{ balanced ? '借贷平衡' : `差额 ${money(Math.abs(debitTotal - creditTotal))}` }}</strong>
        </div>
        <div class="form-actions">
          <el-button @click="$router.push('/accounting/vouchers')">取消</el-button>
          <el-button type="primary" :loading="saving" @click="save">保存暂存</el-button>
        </div>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage } from 'element-plus'
import { api, type AccountingVoucherLineSave } from '@/api/client'
import { money } from '@/api/format'
import PageHeader from '@/components/PageHeader.vue'

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const id = Number(route.params.id || 0)
const isEdit = id > 0
const saving = ref(false)
const accountsQuery = useQuery({ queryKey: ['accounting-accounts'], queryFn: () => api.accountingAccounts(false) })
const partnersQuery = useQuery({ queryKey: ['partners', 'accounting-select'], queryFn: () => api.partners() })
const productsQuery = useQuery({ queryKey: ['products'], queryFn: api.products })
const warehousesQuery = useQuery({ queryKey: ['warehouses'], queryFn: api.warehouses })
const detailQuery = useQuery({ queryKey: ['accounting-voucher', id], queryFn: () => api.accountingVoucher(id), enabled: computed(() => isEdit) })
const manualAccounts = computed(() => (accountsQuery.data.value || []).filter((item) => item.status === 'ENABLED' && item.leaf && item.allowManual))
const partners = computed(() => partnersQuery.data.value || [])
const products = computed(() => productsQuery.data.value || [])
const warehouses = computed(() => warehousesQuery.data.value || [])
const form = reactive({ voucherDate: localDate(), summary: '', lines: [emptyLine(), emptyLine()] as AccountingVoucherLineSave[] })
const debitTotal = computed(() => form.lines.reduce((sum, line) => sum + Number(line.debitAmount || 0), 0))
const creditTotal = computed(() => form.lines.reduce((sum, line) => sum + Number(line.creditAmount || 0), 0))
const balanced = computed(() => debitTotal.value > 0 && Math.abs(debitTotal.value - creditTotal.value) < 0.005)
let hydrated = false
watch(() => detailQuery.data.value, (detail) => {
  if (!detail || hydrated) return
  if (detail.voucher.voucherType !== 'MANUAL' || detail.voucher.status !== 'DRAFT') { ElMessage.error('只有暂存的手工凭证可以编辑'); router.replace(`/accounting/vouchers/${id}`); return }
  form.voucherDate = detail.voucher.voucherDate
  form.summary = detail.voucher.summary
  form.lines = detail.lines.map((line) => ({ accountId: line.accountId, summary: line.summary, debitAmount: Number(line.debitAmount), creditAmount: Number(line.creditAmount), partnerId: line.partnerId || undefined, productId: line.productId || undefined, warehouseId: line.warehouseId || undefined }))
  hydrated = true
}, { immediate: true })

function emptyLine(): AccountingVoucherLineSave { return { accountId: 0, summary: '', debitAmount: 0, creditAmount: 0 } }
function addLine() { form.lines.push(emptyLine()) }
async function save() {
  if (!form.voucherDate || !form.summary.trim()) { ElMessage.warning('请填写凭证日期和摘要'); return }
  if (form.lines.some((line) => !line.accountId || !line.summary.trim() || ((Number(line.debitAmount) > 0) === (Number(line.creditAmount) > 0)))) { ElMessage.warning('请完整填写科目、摘要，并确保每行只填借方或贷方'); return }
  if (!balanced.value) { ElMessage.warning('借贷合计必须相等且大于 0'); return }
  saving.value = true
  try {
    const payload = { voucherDate: form.voucherDate, summary: form.summary.trim(), lines: form.lines }
    const voucherId = isEdit ? await api.updateAccountingVoucher(id, payload) : await api.createAccountingVoucher(payload)
    ElMessage.success('凭证已暂存')
    await queryClient.invalidateQueries({ queryKey: ['accounting-vouchers'] })
    router.replace(`/accounting/vouchers/${voucherId}`)
  } finally { saving.value = false }
}
function localDate() { const now = new Date(); return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}` }
</script>

<style scoped>
.voucher-form-panel { margin-top: 14px; padding: 18px; }
.line-heading { margin: 6px 0 12px; }
.voucher-lines :deep(.el-input-number .el-input__inner) { text-align: right; }
.voucher-total { display: flex; justify-content: flex-end; gap: 28px; padding: 14px 8px; color: #24603b; }
.voucher-total.is-unbalanced { color: var(--el-color-danger); }
</style>
