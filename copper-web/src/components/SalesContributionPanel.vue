<template>
  <div class="panel">
    <div class="panel-header">
      <div>
        <div class="panel-title">本单贡献毛利</div>
        <div class="muted">销售额减已出库批次成本，再减已归集销售费用。这里只做经营归集，不重复登记资金或凭证。</div>
      </div>
      <el-button v-if="appStore.hasPermission('sales.cost.manage')" type="primary" plain @click="allocationDialogVisible = true">归集已有费用</el-button>
    </div>
    <div v-if="contribution" class="contribution-grid">
      <div><span>销售额</span><strong>{{ money(contribution.revenue) }}</strong></div>
      <div><span>库存成本</span><strong>{{ money(contribution.inventoryCost) }}</strong></div>
      <div><span>毛利</span><strong>{{ money(contribution.grossMargin) }}</strong></div>
      <div><span>归集费用</span><strong>{{ money(contribution.allocatedExpense) }}</strong></div>
      <div class="highlight"><span>贡献毛利</span><strong :class="contribution.contributionMargin < 0 ? 'text-danger' : 'text-success'">{{ money(contribution.contributionMargin) }}</strong></div>
      <div><span>尚未收回</span><strong>{{ money(contribution.outstandingAmount) }}</strong></div>
    </div>
    <el-empty v-else description="当前单据尚未形成可计算的销售与出库成本" :image-size="72" />
    <el-table v-if="allocations.length" :data="allocations" border size="small" show-overflow-tooltip>
      <el-table-column label="费用源单" min-width="180"><template #default="{ row }"><router-link class="table-link" :to="`/treasury/documents/${row.treasuryDocumentId}`">{{ row.treasuryDocumentNo }}</router-link><div class="muted">{{ row.summary }}</div></template></el-table-column>
      <el-table-column label="分配金额" width="120" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
      <el-table-column prop="reason" label="归集依据" min-width="180" />
      <el-table-column label="状态" width="90"><template #default="{ row }">{{ row.status === 'ACTIVE' ? '有效' : '已撤销' }}</template></el-table-column>
      <el-table-column v-if="appStore.hasPermission('sales.cost.manage')" label="操作" width="90"><template #default="{ row }"><el-button v-if="row.status === 'ACTIVE'" link type="danger" @click="reverse(row.id)">撤销</el-button></template></el-table-column>
    </el-table>

    <el-dialog v-model="allocationDialogVisible" title="归集已有销售费用" width="620px">
      <el-alert title="请先在“费用与出纳”确认真实费用源单。这里仅分配其经营归属，不产生第二笔费用或付款。" type="warning" :closable="false" show-icon style="margin-bottom:14px" />
      <el-form label-position="top">
        <el-form-item label="费用源单">
          <el-select v-model="form.treasuryDocumentId" filterable style="width:100%">
            <el-option v-for="item in availableExpenses" :key="item.id" :label="`${item.documentNo}｜${item.summary}｜可分配 ${money(item.availableAmount)}`" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="本单分配金额"><el-input-number v-model="form.amount" :min="0.01" :precision="2" style="width:100%" :controls="false" /></el-form-item>
        <el-form-item label="归集依据"><el-input v-model="form.reason" maxlength="500" placeholder="例如：本单送货运费、业务佣金" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="allocationDialogVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="allocate">确认归集</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import { money } from '@/api/format'
import { useAppStore } from '@/stores/app'

const props = defineProps<{ sourceType: 'SALES_ORDER' | 'OUTBOUND'; sourceId: number }>()
const appStore = useAppStore()
const contributionQuery = useQuery({
  queryKey: ['sales-contribution', props.sourceType, props.sourceId],
  queryFn: () => api.contributionRows({ sourceType: props.sourceType, sourceId: props.sourceId }),
})
const allocationQuery = useQuery({
  queryKey: ['sales-cost-allocations', props.sourceType, props.sourceId],
  queryFn: () => api.salesCostAllocations(props.sourceType, props.sourceId),
})
const expenseQuery = useQuery({
  queryKey: ['sales-expense-sources'],
  queryFn: api.expenseSources,
  enabled: computed(() => appStore.hasPermission('sales.cost.manage')),
})
const contribution = computed(() => contributionQuery.data.value?.[0])
const allocations = computed(() => allocationQuery.data.value ?? [])
const availableExpenses = computed(() => (expenseQuery.data.value ?? []).filter((item) => item.availableAmount > 0))
const allocationDialogVisible = ref(false)
const saving = ref(false)
const form = reactive({ treasuryDocumentId: undefined as number | undefined, amount: 0, reason: '' })

async function allocate() {
  if (!form.treasuryDocumentId || form.amount <= 0 || form.reason.trim().length < 2) return ElMessage.warning('请选择费用并填写金额和归集依据')
  saving.value = true
  try {
    await api.allocateSalesCost(props.sourceType, props.sourceId, { treasuryDocumentId: form.treasuryDocumentId, amount: form.amount, reason: form.reason.trim() })
    allocationDialogVisible.value = false
    Object.assign(form, { treasuryDocumentId: undefined, amount: 0, reason: '' })
    ElMessage.success('费用已归集到本单；原费用和会计记录未改变')
    await Promise.all([allocationQuery.refetch(), contributionQuery.refetch(), expenseQuery.refetch()])
  } finally { saving.value = false }
}
async function reverse(id: number) {
  const { value } = await ElMessageBox.prompt('只撤销本单经营归集，不撤销原费用或付款。请填写原因。', '撤销费用归集', { inputPattern: /.{2,}/, inputErrorMessage: '至少填写2个字' })
  await api.reverseSalesCost(props.sourceType, props.sourceId, id, value)
  ElMessage.success('费用归集已撤销')
  await Promise.all([allocationQuery.refetch(), contributionQuery.refetch(), expenseQuery.refetch()])
}
</script>

<style scoped>
.contribution-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 1px; margin-bottom: 12px; overflow: hidden; border: 1px solid var(--el-border-color-light); border-radius: 8px; background: var(--el-border-color-light); }
.contribution-grid > div { display: grid; gap: 8px; padding: 14px; background: var(--el-bg-color); }
.contribution-grid span { color: var(--el-text-color-secondary); font-size: 12px; }
.contribution-grid strong { font-size: 18px; font-variant-numeric: tabular-nums; }
.contribution-grid .highlight { background: var(--el-color-primary-light-9); }
@media (max-width: 900px) { .contribution-grid { grid-template-columns: repeat(2, 1fr); } }
</style>
