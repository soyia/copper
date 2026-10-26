<template>
  <div class="panel">
    <div class="panel-header">
      <div>
        <div class="panel-title">供应商报价比较</div>
        <div class="muted">只在采购草稿旁比较价格、运费和交期；选中报价不会静默改写订单。</div>
      </div>
      <el-button v-if="status === 'DRAFT' && appStore.hasPermission('purchase.quote.manage')" type="primary" plain @click="openCreate">添加报价</el-button>
    </div>
    <el-table :data="query.data.value ?? []" border size="small" show-overflow-tooltip>
      <el-table-column label="供应商" min-width="150" fixed="left"><template #default="{ row }"><strong>{{ row.supplierName }}</strong><div class="muted">{{ row.quoteDate }}</div></template></el-table-column>
      <el-table-column label="货款" width="120" align="right"><template #default="{ row }">{{ money(row.goodsAmount) }}</template></el-table-column>
      <el-table-column label="运费" width="110" align="right"><template #default="{ row }">{{ money(row.freightAmount) }}</template></el-table-column>
      <el-table-column label="合计" width="125" align="right"><template #default="{ row }"><strong>{{ money(row.totalAmount) }}</strong></template></el-table-column>
      <el-table-column label="交期" width="90" align="right"><template #default="{ row }">{{ row.leadTimeDays }}天</template></el-table-column>
      <el-table-column prop="settlementTerms" label="结算条件" min-width="150" />
      <el-table-column prop="sourceNote" label="报价来源" min-width="160" />
      <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.status === 'SELECTED' ? 'success' : row.status === 'VOID' ? 'info' : ''">{{ quoteStatus(row.status) }}</el-tag></template></el-table-column>
      <el-table-column v-if="status === 'DRAFT'" label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 'ACTIVE'" link type="primary" @click="selectQuote(row.id)">选用</el-button>
          <el-button v-if="row.status !== 'VOID'" link type="danger" @click="voidQuote(row.id)">作废</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="添加供应商报价" width="840px">
      <el-alert title="报价只用于比较。选用后仍需回到采购草稿核对供应商、单价、税率和数量，再确认订货。" type="info" :closable="false" show-icon style="margin-bottom:14px" />
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="供应商"><PartnerRemoteSelect v-model="form.supplierId" type="SUPPLIER" clearable /></el-form-item>
          <el-form-item label="报价日期"><el-date-picker v-model="form.quoteDate" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
          <el-form-item label="运费"><el-input-number v-model="form.freightAmount" :min="0" :precision="2" style="width:100%" :controls="false" /></el-form-item>
          <el-form-item label="交期（天）"><el-input-number v-model="form.leadTimeDays" :min="0" :max="3650" style="width:100%" :controls="false" /></el-form-item>
          <el-form-item label="价格口径"><el-radio-group v-model="form.priceIncludesTax"><el-radio-button :value="true">含税</el-radio-button><el-radio-button :value="false">未税</el-radio-button></el-radio-group></el-form-item>
          <el-form-item label="结算条件"><el-input v-model="form.settlementTerms" placeholder="例如：到货后30天" /></el-form-item>
        </div>
        <el-form-item label="报价来源/备注"><el-input v-model="form.sourceNote" maxlength="500" placeholder="微信、电话、报价单编号等" /></el-form-item>
      </el-form>
      <el-table :data="form.items" border size="small">
        <el-table-column prop="productName" label="商品" min-width="180" />
        <el-table-column label="报价数量" width="150"><template #default="{ row }">{{ quantity(row.requestedQuantity) }} {{ row.requestedUnit }}</template></el-table-column>
        <el-table-column label="报价单价" width="210"><template #default="{ row }"><el-input-number v-model="row.quotedUnitPrice" :min="0" :precision="4" style="width:100%" :controls="false" /></template></el-table-column>
        <el-table-column label="金额" width="130" align="right"><template #default="{ row }">{{ money(row.requestedQuantity * row.quotedUnitPrice) }}</template></el-table-column>
      </el-table>
      <template #footer><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveQuote">保存报价</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import { money, quantity } from '@/api/format'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import { useAppStore } from '@/stores/app'

const props = defineProps<{
  purchaseOrderId: number
  status: string
  items: Array<{ productId: number; productName: string; requestedQuantity?: number; quantity: number; requestedUnit?: string; baseUnit?: string; unit?: string; unitPrice: number }>
}>()
const appStore = useAppStore()
const query = useQuery({ queryKey: ['purchase-quotes', props.purchaseOrderId], queryFn: () => api.purchaseQuotes(props.purchaseOrderId) })
const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive({
  supplierId: undefined as number | undefined,
  quoteDate: today(),
  priceIncludesTax: true,
  freightAmount: 0,
  leadTimeDays: 7,
  settlementTerms: '',
  sourceNote: '',
  items: [] as Array<{ productId: number; productName: string; requestedQuantity: number; requestedUnit: string; quotedUnitPrice: number }>,
})

function openCreate() {
  form.supplierId = undefined
  form.quoteDate = today()
  form.priceIncludesTax = true
  form.freightAmount = 0
  form.leadTimeDays = 7
  form.settlementTerms = ''
  form.sourceNote = ''
  form.items = props.items.map((item) => ({
    productId: item.productId,
    productName: item.productName,
    requestedQuantity: Number(item.requestedQuantity ?? item.quantity),
    requestedUnit: item.requestedUnit || item.baseUnit || item.unit || '',
    quotedUnitPrice: Number(item.unitPrice || 0),
  }))
  dialogVisible.value = true
}
async function saveQuote() {
  if (!form.supplierId) return ElMessage.warning('请选择供应商')
  saving.value = true
  try {
    await api.createPurchaseQuote(props.purchaseOrderId, {
      supplierId: form.supplierId,
      quoteDate: form.quoteDate,
      priceIncludesTax: form.priceIncludesTax,
      freightAmount: form.freightAmount,
      leadTimeDays: form.leadTimeDays,
      settlementTerms: form.settlementTerms,
      sourceNote: form.sourceNote,
      items: form.items.map(({ productName: _productName, ...item }) => item),
    })
    dialogVisible.value = false
    ElMessage.success('报价已保存')
    await query.refetch()
  } finally { saving.value = false }
}
async function selectQuote(id: number) {
  await ElMessageBox.confirm('选中只记录采购决策，不会自动修改采购草稿。请选用后再核对订单内容。', '选用报价', { type: 'warning' })
  await api.selectPurchaseQuote(props.purchaseOrderId, id)
  ElMessage.success('已标记选用报价，请核对采购草稿')
  await query.refetch()
}
async function voidQuote(id: number) {
  const { value } = await ElMessageBox.prompt('请填写作废原因。', '作废报价', { inputPattern: /.{2,}/, inputErrorMessage: '至少填写2个字' })
  await api.voidPurchaseQuote(props.purchaseOrderId, id, value)
  ElMessage.success('报价已作废')
  await query.refetch()
}
function quoteStatus(value: string) { return ({ ACTIVE: '可选', SELECTED: '已选用', VOID: '已作废' } as Record<string, string>)[value] || value }
function today() { return new Date().toLocaleDateString('sv-SE') }
</script>

<style scoped>
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 14px; }
@media (max-width: 760px) { .form-grid { grid-template-columns: 1fr; } }
</style>
