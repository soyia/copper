<template>
  <div class="panel">
    <div class="panel-header atp-panel-header">
      <div>
        <div class="panel-title">库存承诺与交期</div>
        <div class="muted">按履约仓库和计划交付日校验现存、占用、其他销售承诺以及届时采购/调拨到货；确认成功会固化计算快照。</div>
      </div>
      <div class="atp-panel-actions">
        <el-tag v-if="previewEnabled && evaluation" :type="evaluation.canPromise ? 'success' : 'danger'">{{ evaluation.canPromise ? '当前可承诺' : '当前不可承诺' }}</el-tag>
        <el-button v-if="previewEnabled" :loading="previewFetching" @click="refetchPreview">重新计算</el-button>
      </div>
    </div>

    <div v-if="previewEnabled" class="panel-body atp-body">
      <el-alert
        v-for="issue in evaluation?.issues || []"
        :key="`${issue.code}-${issue.message}`"
        :title="issue.message"
        :type="issue.code === 'ATP_SHORTAGE' || issue.code === 'UNALLOCATED_LEGACY_COMMITMENT' ? 'error' : 'warning'"
        :closable="false"
        show-icon
      />
      <el-descriptions v-if="evaluation" :column="3" border size="small">
        <el-descriptions-item label="履约仓库">{{ evaluation.warehouseName || '待指定' }}</el-descriptions-item>
        <el-descriptions-item label="计划交付">{{ formatDate(evaluation.promiseDate) }}</el-descriptions-item>
        <el-descriptions-item label="计算口径">{{ evaluation.calculationVersion }}</el-descriptions-item>
      </el-descriptions>
    </div>

    <el-table v-if="previewEnabled" v-loading="previewFetching" :data="evaluation?.items || []" border size="small" class="data-table" empty-text="补全履约仓库、交付日和商品后即可计算">
      <el-table-column label="商品" min-width="200" fixed="left"><template #default="{ row }"><div>{{ row.productName }}</div><div class="muted">{{ row.sku }} · {{ row.baseUnit }}</div></template></el-table-column>
      <el-table-column label="本单承诺" width="112" align="right"><template #default="{ row }">{{ quantity(row.commitmentQuantity) }}</template></el-table-column>
      <el-table-column label="现存" width="100" align="right"><template #default="{ row }">{{ quantity(row.onHandQuantity) }}</template></el-table-column>
      <el-table-column label="出库草稿" width="110" align="right"><template #default="{ row }">{{ quantity(row.outboundDraftQuantity) }}</template></el-table-column>
      <el-table-column label="其他销售承诺" width="125" align="right"><template #default="{ row }">{{ quantity(row.salesCommitmentQuantity) }}</template></el-table-column>
      <el-table-column label="采购/调拨将到" width="135" align="right"><template #default="{ row }">{{ quantity(Number(row.purchaseIncomingQuantity) + Number(row.transferIncomingQuantity)) }}</template></el-table-column>
      <el-table-column label="承诺前 ATP" width="115" align="right"><template #default="{ row }">{{ quantity(row.atpBeforeQuantity) }}</template></el-table-column>
      <el-table-column label="承诺后 ATP" width="115" align="right" fixed="right"><template #default="{ row }"><strong :class="Number(row.atpAfterQuantity) < 0 ? 'text-danger' : 'text-success'">{{ quantity(row.atpAfterQuantity) }}</strong></template></el-table-column>
    </el-table>

    <div class="panel-header history-header"><div><div class="panel-title">承诺审计快照</div><div class="muted">确认、变更和取消均保留当时数量口径，后续库存变化不会覆盖历史判断。</div></div></div>
    <el-table v-loading="historyFetching" :data="history || []" border size="small" class="data-table" empty-text="尚无承诺快照">
      <el-table-column label="动作" width="92"><template #default="{ row }">{{ actionText(row.action) }}</template></el-table-column>
      <el-table-column label="结论" width="95"><template #default="{ row }"><el-tag :type="row.decision === 'PROMISED' ? 'success' : 'info'" size="small">{{ row.decision === 'PROMISED' ? '已承诺' : '已释放' }}</el-tag></template></el-table-column>
      <el-table-column label="商品" min-width="180"><template #default="{ row }">{{ row.productName }}<div class="muted">{{ row.sku }}</div></template></el-table-column>
      <el-table-column label="承诺量" width="105" align="right"><template #default="{ row }">{{ quantity(row.commitmentQuantity) }}</template></el-table-column>
      <el-table-column label="ATP 前 / 后" width="150" align="right"><template #default="{ row }">{{ quantity(row.atpBeforeQuantity) }} / {{ quantity(row.atpAfterQuantity) }}</template></el-table-column>
      <el-table-column prop="operatorName" label="操作人" width="120" />
      <el-table-column label="时间" width="170"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { api } from '@/api/client'
import { formatDate, formatDateTime, quantity } from '@/api/format'

const props = defineProps<{ orderId: number; status: string }>()
const previewEnabled = computed(() => ['DRAFT', 'OPEN', 'PARTIAL_SHIPPED'].includes(props.status))
const previewQuery = useQuery({
  queryKey: computed(() => ['sales-order-atp', props.orderId]),
  queryFn: () => api.salesOrderAtp(props.orderId),
  enabled: computed(() => props.orderId > 0 && previewEnabled.value),
})
const evaluation = computed(() => previewQuery.data.value)
const previewFetching = computed(() => previewQuery.isFetching.value)
const refetchPreview = previewQuery.refetch
const historyQuery = useQuery({
  queryKey: computed(() => ['sales-order-atp-history', props.orderId]),
  queryFn: () => api.salesOrderAtpHistory(props.orderId),
  enabled: computed(() => props.orderId > 0),
})
const history = computed(() => historyQuery.data.value)
const historyFetching = computed(() => historyQuery.isFetching.value)

function actionText(action: string) {
  if (action === 'CONFIRM') return '确认'
  if (action === 'AMEND') return '变更'
  return '取消'
}
</script>

<style scoped>
.atp-panel-header { align-items: flex-start; }
.atp-panel-header .muted, .history-header .muted { margin-top: 4px; font-size: 12px; }
.atp-panel-actions { display: flex; align-items: center; gap: 10px; }
.atp-body { display: grid; gap: 10px; }
.history-header { margin-top: 12px; }
</style>
