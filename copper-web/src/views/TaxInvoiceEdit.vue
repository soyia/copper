<template>
  <div class="page" v-loading="detailQuery.isLoading.value">
    <PageHeader :title="pageTitle" :back-to="backTo" />
    <el-alert type="warning" :closable="false" show-icon title="这里只登记已经收到或开具的真实发票。订单、出入库、应收应付和收付款仍按各自单据确认，不随发票登记被改写。" />

    <div class="panel invoice-form-panel">
      <el-form label-position="top">
        <div class="form-grid form-grid-4">
          <el-form-item label="方向" required><el-select v-model="form.direction" :disabled="isEdit"><el-option label="进项发票" value="INPUT" /><el-option label="销项发票" value="OUTPUT" /></el-select></el-form-item>
          <el-form-item label="蓝红字" required><el-select v-model="form.invoiceColor" :disabled="isEdit"><el-option label="蓝字" value="BLUE" /><el-option label="红字" value="RED" /></el-select></el-form-item>
          <el-form-item label="发票类型" required><el-select v-model="form.invoiceType"><el-option label="数电专票" value="DIGITAL_SPECIAL" /><el-option label="数电普票" value="DIGITAL_NORMAL" /><el-option label="增值税专票" value="VAT_SPECIAL" /><el-option label="增值税普票" value="VAT_NORMAL" /><el-option label="其他" value="OTHER" /></el-select></el-form-item>
          <el-form-item label="开票日期" required><el-date-picker v-model="form.invoiceDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item v-if="form.invoiceColor === 'RED'" class="form-span-2" label="原蓝字发票" required><el-select v-model="form.blueInvoiceId" filterable placeholder="请选择可红冲的原蓝字发票"><el-option v-for="item in availableBlueInvoices" :key="item.id" :label="`${item.invoiceNumber} · ${item.partnerName} · 可红冲 ${money(item.remainingRedAmount)}`" :value="item.id" /></el-select></el-form-item>
          <el-form-item v-if="!form.invoiceType.startsWith('DIGITAL')" label="发票代码" :required="form.invoiceType.startsWith('VAT')"><el-input v-model="form.invoiceCode" maxlength="32" :placeholder="form.invoiceType.startsWith('VAT') ? '10位或12位数字' : '选填'" /></el-form-item>
          <el-form-item label="发票号码" required><el-input v-model="form.invoiceNumber" maxlength="32" :placeholder="form.invoiceType.startsWith('DIGITAL') ? '20位数电发票号码' : '请输入发票号码'" /></el-form-item>
          <el-form-item label="往来单位" required><PartnerRemoteSelect v-model="form.partnerId" :type="form.direction === 'INPUT' ? 'SUPPLIER' : 'CUSTOMER'" /></el-form-item>
          <el-form-item label="收到 / 开具日期" required><el-date-picker v-model="form.receivedOrIssuedDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item label="来源" required><el-select v-model="form.sourceChannel"><el-option label="税务数字账户" value="TAX_DIGITAL_ACCOUNT" /><el-option label="手工登记" value="MANUAL" /><el-option label="导入" value="IMPORT" /></el-select></el-form-item>
          <el-form-item label="币种"><el-input value="CNY" disabled /></el-form-item>
        </div>

        <div class="invoice-party-grid">
          <div class="invoice-party">
            <div class="panel-title">销售方</div>
            <el-form-item label="名称" required><el-input v-model="form.issuerName" maxlength="255" /></el-form-item>
            <el-form-item label="统一社会信用代码 / 纳税人识别号"><el-input v-model="form.issuerTaxNo" maxlength="64" /></el-form-item>
          </div>
          <div class="invoice-party">
            <div class="panel-title">购买方</div>
            <el-form-item label="名称" required><el-input v-model="form.recipientName" maxlength="255" /></el-form-item>
            <el-form-item label="统一社会信用代码 / 纳税人识别号"><el-input v-model="form.recipientTaxNo" maxlength="64" /></el-form-item>
          </div>
        </div>

        <div class="panel-header line-heading">
          <div><div class="panel-title">发票明细</div><div class="muted">按行计算并四舍五入到分；税率和法规依据在保存时形成历史快照。</div></div>
          <el-button @click="addLine">增加明细</el-button>
        </div>
        <el-table :data="form.lines" border size="small" class="data-table invoice-lines">
          <el-table-column label="商品 / 项目" min-width="220"><template #default="{ row }"><el-select v-model="row.productId" filterable clearable placeholder="选择商品或手工填写" style="width:100%" @change="selectProduct(row)"><el-option v-for="product in products" :key="product.id" :label="`${product.sku} ${product.name} ${product.spec || ''}`" :value="product.id" /></el-select><el-input v-model="row.productName" class="line-sub-input" placeholder="发票项目名称" maxlength="255" /></template></el-table-column>
          <el-table-column label="规格 / 单位" min-width="170"><template #default="{ row }"><el-input v-model="row.productSpec" placeholder="规格型号" /><el-input v-model="row.unit" class="line-sub-input" placeholder="单位" /></template></el-table-column>
          <el-table-column label="数量" width="130"><template #default="{ row }"><el-input-number v-model="row.quantity" :min="0" :precision="6" :controls="false" style="width:100%" @change="calculateLine(row, true)" /></template></el-table-column>
          <el-table-column label="未税单价" width="140"><template #default="{ row }"><el-input-number v-model="row.unitPriceExcludingTax" :min="0" :precision="6" :controls="false" style="width:100%" @change="calculateLine(row, true)" /></template></el-table-column>
          <el-table-column label="未税金额" width="140"><template #default="{ row }"><el-input-number v-model="row.amountExcludingTax" :min="0" :precision="2" :controls="false" style="width:100%" @change="calculateLine(row, false)" /></template></el-table-column>
          <el-table-column label="税率" min-width="190"><template #default="{ row }"><el-select v-model="row.taxRateRuleId" placeholder="选择税率" style="width:100%" @change="calculateLine(row, false)"><el-option v-for="rate in rates" :key="rate.id" :label="`${rate.ruleName} (${rate.taxRate}%)`" :value="rate.id" /></el-select></template></el-table-column>
          <el-table-column label="税额" width="125" align="right"><template #default="{ row }">{{ money(row.taxAmount) }}</template></el-table-column>
          <el-table-column label="价税合计" width="140" align="right"><template #default="{ row }"><strong>{{ money(row.totalAmount) }}</strong></template></el-table-column>
          <el-table-column label="操作" width="74" fixed="right"><template #default="{ $index }"><el-button link type="danger" :disabled="form.lines.length <= 1" @click="form.lines.splice($index, 1)">删除</el-button></template></el-table-column>
        </el-table>
        <div class="invoice-total"><span>未税金额 {{ money(netTotal) }}</span><span>税额 {{ money(taxTotal) }}</span><strong>价税合计 {{ money(grandTotal) }}</strong></div>

        <div class="form-grid form-grid-2 evidence-grid">
          <el-alert class="form-span-2" type="info" :closable="false" show-icon title="先保存草稿，再在发票详情上传 XML/OFD/PDF/图片原件和查验凭证；系统自动计算原文件 SHA-256，不能用手填文件名代替原件。" />
          <el-form-item class="form-span-2" label="备注"><el-input v-model="form.memo" type="textarea" :rows="2" maxlength="1000" show-word-limit /></el-form-item>
        </div>
        <div class="form-actions">
          <el-button @click="$router.push(backTo)">取消</el-button>
          <el-button type="primary" :loading="saving" @click="save">保存草稿</el-button>
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
import { api, type TaxInvoiceColor, type TaxInvoiceDirection, type TaxInvoiceLineSave, type TaxInvoiceSave } from '@/api/client'
import { money } from '@/api/format'
import PageHeader from '@/components/PageHeader.vue'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'

type EditableLine = TaxInvoiceLineSave & { productSku?: string; productSpec?: string; unit?: string }
const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const id = Number(route.params.id || 0)
const isEdit = id > 0
const saving = ref(false)
const initialDirection = (route.query.direction === 'OUTPUT' ? 'OUTPUT' : 'INPUT') as TaxInvoiceDirection
const initialColor = (route.query.color === 'RED' ? 'RED' : 'BLUE') as TaxInvoiceColor
const form = reactive({
  direction: initialDirection,
  invoiceColor: initialColor,
  invoiceType: 'DIGITAL_SPECIAL', invoiceCode: '', invoiceNumber: '', blueInvoiceId: 0,
  partnerId: 0, issuerName: '', issuerTaxNo: '', recipientName: '', recipientTaxNo: '',
  invoiceDate: localDate(), receivedOrIssuedDate: localDate(), sourceChannel: 'TAX_DIGITAL_ACCOUNT',
  sourceFileName: '', sourceFileSha256: '', memo: '', expectedVersionNo: undefined as number | undefined,
  lines: [emptyLine()] as EditableLine[],
})
const detailQuery = useQuery({ queryKey: ['tax-invoice', id], queryFn: () => api.taxInvoice(id), enabled: computed(() => isEdit) })
const ratesQuery = useQuery({ queryKey: computed(() => ['tax-rates', form.direction, form.invoiceDate]), queryFn: () => api.taxRates({ direction: form.direction, date: form.invoiceDate }) })
const partnersQuery = useQuery({ queryKey: computed(() => ['partners', 'invoice', form.direction]), queryFn: () => api.partners(form.direction === 'INPUT' ? 'SUPPLIER' : 'CUSTOMER') })
const productsQuery = useQuery({ queryKey: ['products'], queryFn: api.products })
const blueQuery = useQuery({ queryKey: computed(() => ['tax-blue-invoices', form.direction]), queryFn: () => api.taxInvoices({ direction: form.direction, status: 'CONFIRMED' }) })
const entityQuery = useQuery({ queryKey: ['accounting-dashboard'], queryFn: api.accountingDashboard })
const rates = computed(() => ratesQuery.data.value || [])
const products = computed(() => productsQuery.data.value || [])
const partners = computed(() => partnersQuery.data.value || [])
const availableBlueInvoices = computed(() => (blueQuery.data.value || []).filter((item) => item.invoiceColor === 'BLUE' && item.remainingRedAmount > 0 && (!form.partnerId || item.partnerId === form.partnerId)))
const netTotal = computed(() => round2(form.lines.reduce((sum, line) => sum + Number(line.amountExcludingTax || 0), 0)))
const taxTotal = computed(() => round2(form.lines.reduce((sum, line) => sum + Number(line.taxAmount || 0), 0)))
const grandTotal = computed(() => round2(form.lines.reduce((sum, line) => sum + Number(line.totalAmount || 0), 0)))
const backTo = computed(() => isEdit ? `/tax-invoices/${id}` : form.direction === 'INPUT' ? '/tax-invoices/input' : '/tax-invoices/output')
const pageTitle = computed(() => `${isEdit ? '编辑' : '登记'}${form.invoiceColor === 'RED' ? '红字' : '蓝字'}${form.direction === 'INPUT' ? '进项' : '销项'}发票`)
let hydrated = false
watch(() => detailQuery.data.value, (detail) => {
  if (!detail || hydrated) return
  if (detail.invoice.status !== 'DRAFT') { ElMessage.error('只有发票草稿可以编辑'); router.replace(`/tax-invoices/${id}`); return }
  Object.assign(form, {
    direction: detail.invoice.direction, invoiceColor: detail.invoice.invoiceColor,
    invoiceType: detail.invoice.invoiceType, invoiceCode: detail.invoice.invoiceCode,
    invoiceNumber: detail.invoice.invoiceNumber, blueInvoiceId: detail.invoice.blueInvoiceId,
    partnerId: detail.invoice.partnerId, issuerName: detail.issuerName, issuerTaxNo: detail.issuerTaxNo,
    recipientName: detail.recipientName, recipientTaxNo: detail.recipientTaxNo,
    invoiceDate: detail.invoice.invoiceDate, receivedOrIssuedDate: detail.invoice.receivedOrIssuedDate,
    sourceChannel: detail.sourceChannel, sourceFileName: detail.sourceFileName,
    sourceFileSha256: detail.sourceFileSha256, memo: detail.memo, expectedVersionNo: detail.invoice.versionNo,
    lines: detail.lines.map((line) => ({ productId: line.productId || undefined, productName: line.productName,
      productSku: line.productSku, productSpec: line.productSpec, unit: line.unit, quantity: Number(line.quantity),
      unitPriceExcludingTax: Number(line.unitPriceExcludingTax), amountExcludingTax: Number(line.amountExcludingTax),
      taxRateRuleId: line.taxRateRuleId, taxClassificationCode: line.taxClassificationCode,
      taxAmount: Number(line.taxAmount), totalAmount: Number(line.totalAmount) })),
  })
  hydrated = true
}, { immediate: true })
watch(() => form.partnerId, () => {
  if (hydrated && isEdit) return
  const partner = partners.value.find((item) => item.id === form.partnerId)
  if (!partner) return
  const company = entityQuery.data.value?.entity.entityName || '本企业'
  if (form.direction === 'INPUT') {
    form.issuerName = partner.name; form.issuerTaxNo = partner.creditCode || ''; form.recipientName ||= company
  } else {
    form.issuerName ||= company; form.recipientName = partner.name; form.recipientTaxNo = partner.creditCode || ''
  }
})

function emptyLine(): EditableLine { return { productId: undefined, productName: '', productSku: '', productSpec: '', unit: '', quantity: 0, unitPriceExcludingTax: 0, amountExcludingTax: 0, taxRateRuleId: 0, taxClassificationCode: '', taxAmount: 0, totalAmount: 0 } }
function addLine() { form.lines.push(emptyLine()) }
function selectProduct(line: EditableLine) { const product = products.value.find((item) => item.id === line.productId); if (!product) return; line.productName = product.name; line.productSku = product.sku; line.productSpec = product.spec; line.unit = product.unit }
function calculateLine(line: EditableLine, deriveAmount: boolean) {
  if (deriveAmount && Number(line.quantity) > 0) line.amountExcludingTax = round2(Number(line.quantity) * Number(line.unitPriceExcludingTax))
  const rate = rates.value.find((item) => item.id === line.taxRateRuleId)?.taxRate || 0
  line.taxAmount = round2(Number(line.amountExcludingTax) * Number(rate) / 100)
  line.totalAmount = round2(Number(line.amountExcludingTax) + Number(line.taxAmount))
}
async function save() {
  if (!form.partnerId || !form.invoiceNumber.trim() || !form.issuerName.trim() || !form.recipientName.trim() || !form.invoiceDate || !form.receivedOrIssuedDate) { ElMessage.warning('请完整填写发票抬头、号码、日期和往来单位'); return }
  if (form.invoiceColor === 'RED' && !form.blueInvoiceId) { ElMessage.warning('红字发票必须选择原蓝字发票'); return }
  if (form.invoiceType.startsWith('DIGITAL') && (!/^\d{20}$/.test(form.invoiceNumber) || form.invoiceCode.trim())) { ElMessage.warning('数电发票号码必须为20位数字，且不填写传统发票代码'); return }
  if (form.invoiceType.startsWith('VAT') && (!/^\d{10}(\d{2})?$/.test(form.invoiceCode) || !/^\d{8}$/.test(form.invoiceNumber))) { ElMessage.warning('传统增值税发票代码须为10位或12位数字，发票号码须为8位数字'); return }
  if (form.invoiceType === 'OTHER' && (!/^[A-Za-z0-9-]{6,32}$/.test(form.invoiceNumber) || (form.invoiceCode && !/^[A-Za-z0-9-]{6,32}$/.test(form.invoiceCode)))) { ElMessage.warning('其他发票号码/代码仅支持6至32位字母、数字或连字符'); return }
  if (form.lines.some((line) => !line.productName.trim() || !line.taxRateRuleId || Number(line.totalAmount) <= 0)) { ElMessage.warning('请完整填写每条发票明细、税率和金额'); return }
  const payload: TaxInvoiceSave = { ...form, currency: 'CNY', blueInvoiceId: form.invoiceColor === 'RED' ? form.blueInvoiceId : undefined, lines: form.lines }
  saving.value = true
  try {
    const invoiceId = isEdit ? await api.updateTaxInvoice(id, payload) : await api.createTaxInvoice(payload)
    ElMessage.success('发票草稿已保存')
    await Promise.all([queryClient.invalidateQueries({ queryKey: ['tax-invoices'] }), queryClient.invalidateQueries({ queryKey: ['tax-invoice-dashboard'] })])
    router.replace(`/tax-invoices/${invoiceId}`)
  } finally { saving.value = false }
}
function round2(value: number) { return Math.round((value + Number.EPSILON) * 100) / 100 }
function localDate() { const now = new Date(); return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}` }
</script>

<style scoped>
.invoice-form-panel { margin-top: 14px; padding: 18px; }
.invoice-party-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin: 8px 0 18px; }
.invoice-party { padding: 16px; background: #f7f9fb; border: 1px solid #e1e7ec; border-radius: 8px; }
.invoice-party .panel-title { margin-bottom: 12px; }
.line-heading { margin: 6px 0 12px; }
.line-sub-input { margin-top: 6px; }
.invoice-lines :deep(.el-input-number .el-input__inner) { text-align: right; }
.invoice-total { display: flex; justify-content: flex-end; gap: 28px; padding: 16px 8px; }
.evidence-grid { margin-top: 8px; }
@media (max-width: 900px) { .invoice-party-grid { grid-template-columns: 1fr; } }
</style>
