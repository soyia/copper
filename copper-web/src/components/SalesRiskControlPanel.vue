<template>
  <div class="panel sales-risk-panel">
    <div class="panel-header">
      <div>
        <div class="panel-title">客户信用与毛利控制</div>
        <div class="muted">信用敞口按应收、已确认待发、本单、预收和已核销承兑统一计算；毛利仅作销售放行判断，不替代库存成本账。</div>
      </div>
      <el-tag v-if="evaluation" :type="decisionType" effect="plain">{{ decisionName }}</el-tag>
    </div>

    <div v-if="evaluation" class="panel-body risk-body">
      <el-alert
        v-if="evaluation.blocked"
        :type="hasReleaseHistory ? 'warning' : 'error'"
        :title="evaluation.issues.map((item) => item.title).join('、')"
        :description="`${hasReleaseHistory ? '该单据已完成放行，以下为当前实时风险：' : ''}${evaluation.issues.map((item) => item.message).join('；')}`"
        :closable="false"
        show-icon
      />
      <el-alert
        v-else-if="!evaluation.policyEnabled"
        type="info"
        title="该客户尚未启用销售风控策略"
        description="当前仅展示实时经营风险，不阻断销售。老板可在往来单位详情中启用信用、逾期和最低毛利控制。"
        :closable="false"
        show-icon
      />
      <el-alert v-else type="success" title="当前风险校验通过" :closable="false" show-icon />

      <el-descriptions class="risk-metrics" :column="4" border size="small">
        <el-descriptions-item label="已形成应收">{{ money(evaluation.exposure.receivableAmount) }}</el-descriptions-item>
        <el-descriptions-item label="已确认待发">{{ money(evaluation.exposure.confirmedUnshippedAmount) }}</el-descriptions-item>
        <el-descriptions-item label="本单新增">{{ money(evaluation.exposure.currentDocumentAmount) }}</el-descriptions-item>
        <el-descriptions-item label="客户预收抵减">-{{ money(evaluation.exposure.advanceCreditAmount) }}</el-descriptions-item>
        <el-descriptions-item label="净信用敞口">
          <strong :class="evaluation.exposure.overLimitAmount > 0 ? 'text-danger' : ''">{{ money(evaluation.exposure.netExposureAmount) }}</strong>
        </el-descriptions-item>
        <el-descriptions-item label="授信额度">{{ money(evaluation.exposure.creditLimitAmount) }}</el-descriptions-item>
        <el-descriptions-item label="可用额度">{{ money(evaluation.exposure.availableCreditAmount) }}</el-descriptions-item>
        <el-descriptions-item label="超额金额">
          <span :class="evaluation.exposure.overLimitAmount > 0 ? 'text-danger' : ''">{{ money(evaluation.exposure.overLimitAmount) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="逾期金额">{{ money(evaluation.exposure.overdueAmount) }}</el-descriptions-item>
        <el-descriptions-item label="最长逾期">{{ evaluation.exposure.maximumOverdueDays }} 天</el-descriptions-item>
        <el-descriptions-item label="持有中承兑">{{ money(evaluation.exposure.acceptanceSecuredAmount) }}</el-descriptions-item>
        <el-descriptions-item label="计算时间">{{ formatDateTime(evaluation.evaluatedAt) }}</el-descriptions-item>
      </el-descriptions>
      <div class="risk-note">持有中承兑在收票时已核销对应应收，此处单列展示担保资产，不再次抵减敞口，避免重复计算。</div>

      <el-descriptions v-if="evaluation.marginVisible" :column="4" border size="small">
        <el-descriptions-item label="未税销售额">{{ nullableMoney(evaluation.margin.revenueExcludingTax) }}</el-descriptions-item>
        <el-descriptions-item label="预计成本">{{ nullableMoney(evaluation.margin.estimatedCostAmount) }}</el-descriptions-item>
        <el-descriptions-item label="预计毛利">{{ nullableMoney(evaluation.margin.estimatedGrossProfit) }}</el-descriptions-item>
        <el-descriptions-item label="预计毛利率">
          {{ evaluation.margin.costAvailable && evaluation.margin.estimatedMarginRate != null ? `${Number(evaluation.margin.estimatedMarginRate).toFixed(2)}%` : '无法计算' }}
        </el-descriptions-item>
      </el-descriptions>
      <el-alert v-else-if="evaluation.policyEnabled" type="info" title="毛利金额和比例仅对获授权岗位显示；风险不通过时仍会明确提示。" :closable="false" />
    </div>

    <div v-if="history?.length" class="risk-history">
      <div class="panel-header compact-header"><div class="panel-title">放行与授权记录</div></div>
      <el-table :data="history" border size="small" class="data-table" max-height="230">
        <el-table-column label="动作" width="100"><template #default="{ row }">{{ actionName(row.action) }}</template></el-table-column>
        <el-table-column label="版本" width="84"><template #default="{ row }">V{{ row.sourceRevisionNo }}</template></el-table-column>
        <el-table-column label="决定" width="110"><template #default="{ row }"><el-tag :type="row.decision === 'OVERRIDDEN' ? 'warning' : 'success'" size="small">{{ row.decision === 'OVERRIDDEN' ? '老板例外授权' : '校验通过' }}</el-tag></template></el-table-column>
        <el-table-column label="当时敞口" width="130" align="right"><template #default="{ row }">{{ money(row.exposure.netExposureAmount) }}</template></el-table-column>
        <el-table-column label="当时逾期" width="130" align="right"><template #default="{ row }">{{ money(row.exposure.overdueAmount) }}</template></el-table-column>
        <el-table-column prop="overrideReason" label="授权原因" min-width="240"><template #default="{ row }">{{ row.overrideReason || '-' }}</template></el-table-column>
        <el-table-column prop="operatorName" label="操作人" width="120" />
        <el-table-column label="时间" width="175"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { api } from '@/api/client'
import { formatDateTime, money } from '@/api/format'

const props = defineProps<{ documentType: 'SALES_ORDER' | 'OUTBOUND'; documentId: number }>()
const riskQueryKey = computed(() => ['sales-risk', props.documentType, props.documentId])
const historyQueryKey = computed(() => ['sales-risk-history', props.documentType, props.documentId])
const { data: evaluation } = useQuery({
  queryKey: riskQueryKey,
  queryFn: () => props.documentType === 'SALES_ORDER' ? api.salesOrderRisk(props.documentId) : api.outboundRisk(props.documentId),
})
const { data: history } = useQuery({
  queryKey: historyQueryKey,
  queryFn: () => props.documentType === 'SALES_ORDER' ? api.salesOrderRiskHistory(props.documentId) : api.outboundRiskHistory(props.documentId),
})

const hasReleaseHistory = computed(() => Boolean(history.value?.length))
const decisionType = computed(() => evaluation.value?.blocked ? hasReleaseHistory.value ? 'warning' : 'danger' : !evaluation.value?.policyEnabled ? 'info' : 'success')
const decisionName = computed(() => evaluation.value?.blocked ? hasReleaseHistory.value ? '已放行 · 当前仍有风险' : '需要调整或授权' : !evaluation.value?.policyEnabled ? '仅监测' : '校验通过')

function nullableMoney(value: number | null) { return value == null ? '-' : money(value) }
function actionName(value: string) { return ({ CONFIRM: '确认销售', AMEND: '变更订单', SHIP: '确认发货' } as Record<string, string>)[value] || value }
</script>

<style scoped>
.risk-body { display: grid; gap: 14px; }
.risk-metrics { margin-top: 2px; }
.risk-note { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6; }
.risk-history { border-top: 1px solid var(--el-border-color-lighter); }
.compact-header { min-height: 50px; }
.text-danger { color: var(--el-color-danger); font-weight: 700; }
</style>
