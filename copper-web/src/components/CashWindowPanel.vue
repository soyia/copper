<template>
  <div class="cash-window">
    <el-alert type="info" :closable="false" show-icon :title="window?.formula || '现金窗口只读取当前资金、应收应付和已确认收支，不创建任何资金计划或付款。'" />
    <div v-if="window" class="cash-summary">
      <div><span>当前现金及银行</span><strong>{{ money(window.openingCash) }}</strong><small>总账1001/1002口径</small></div>
      <div><span>未来30天预计收款</span><strong>{{ money(window.receivable30Days) }}</strong><small>应收不是已经到账</small></div>
      <div><span>未来30天预计付款</span><strong>{{ money(window.payable30Days) }}</strong><small>应付及已确认支出</small></div>
      <div :class="{ danger: window.conservativeGap7Days > 0 }"><span>7天不等回款缺口</span><strong>{{ money(window.conservativeGap7Days) }}</strong><small>{{ window.firstConservativeGapDate || '暂无缺口' }}</small></div>
      <div :class="{ danger: window.scheduledGap30Days > 0 }"><span>30天按账期缺口</span><strong>{{ money(window.scheduledGap30Days) }}</strong><small>{{ window.firstScheduledGapDate || '暂无缺口' }}</small></div>
    </div>
    <el-segmented v-model="range" :options="[{ label: '未来7天', value: 7 }, { label: '未来30天', value: 30 }]" size="small" />
    <el-table :data="visibleDays" border size="small" class="data-table cash-days">
      <el-table-column prop="date" label="日期" width="115" />
      <el-table-column label="预计收款" width="125" align="right"><template #default="{ row }">{{ money(row.inflow) }}</template></el-table-column>
      <el-table-column label="预计付款" width="125" align="right"><template #default="{ row }">{{ money(row.outflow) }}</template></el-table-column>
      <el-table-column label="不等回款余额" min-width="145" align="right"><template #default="{ row }"><strong :class="{ dangerText: row.conservativeBalance < 0 }">{{ money(row.conservativeBalance) }}</strong></template></el-table-column>
      <el-table-column label="按账期余额" min-width="145" align="right"><template #default="{ row }"><strong :class="{ dangerText: row.scheduledBalance < 0 }">{{ money(row.scheduledBalance) }}</strong></template></el-table-column>
    </el-table>
    <div class="panel-title source-title">来源明细</div>
    <el-table :data="visibleSources" border size="small" class="data-table" show-overflow-tooltip>
      <el-table-column prop="dueDate" label="到期日" width="110" />
      <el-table-column label="方向" width="90"><template #default="{ row }"><el-tag :type="row.direction === 'INFLOW' ? 'success' : 'warning'">{{ row.direction === 'INFLOW' ? '预计收款' : '预计付款' }}</el-tag></template></el-table-column>
      <el-table-column label="来源单据" min-width="190"><template #default="{ row }"><router-link v-if="sourcePath(row)" class="table-link" :to="sourcePath(row)!">{{ row.sourceNo }}</router-link><span v-else>{{ row.sourceNo }}</span><div class="muted">{{ row.partnerName }}</div></template></el-table-column>
      <el-table-column prop="factLevel" label="事实层次" width="105" />
      <el-table-column label="金额" width="130" align="right"><template #default="{ row }"><strong>{{ money(row.amount) }}</strong></template></el-table-column>
      <el-table-column prop="explanation" label="计算说明" min-width="220" />
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { api, type CashWindowSource } from '@/api/client'
import { money } from '@/api/format'
const range = ref(7)
const query = useQuery({ queryKey: ['cash-window'], queryFn: api.cashWindow })
const window = computed(() => query.data.value)
const visibleDays = computed(() => (window.value?.days || []).slice(0, range.value))
const cutoff = computed(() => visibleDays.value[visibleDays.value.length - 1]?.date || '')
const visibleSources = computed(() => (window.value?.sources || []).filter(row => row.dueDate <= cutoff.value))
function sourcePath(row: CashWindowSource) {
  return ({ SALES_ORDER: `/sales-orders/${row.sourceId}`, OUTBOUND: `/outbounds/${row.sourceId}`, PURCHASE_ORDER: `/purchase-orders/${row.sourceId}`, INBOUND: `/inbounds/${row.sourceId}`, TREASURY_DOCUMENT: `/treasury/documents/${row.sourceId}` } as Record<string,string>)[row.sourceType]
}
</script>

<style scoped>
.cash-window{display:flex;flex-direction:column;gap:14px}.cash-summary{display:grid;grid-template-columns:repeat(5,minmax(135px,1fr));gap:10px}.cash-summary>div{padding:12px 14px;border:1px solid var(--el-border-color-light);border-radius:8px;background:var(--el-fill-color-lighter)}.cash-summary span,.cash-summary small{display:block;color:var(--el-text-color-secondary);font-size:12px}.cash-summary strong{display:block;margin:6px 0 4px;font-size:18px}.cash-summary .danger,.dangerText{color:var(--el-color-danger)}.source-title{margin-top:4px}@media(max-width:1050px){.cash-summary{grid-template-columns:repeat(2,1fr)}}@media(max-width:580px){.cash-summary{grid-template-columns:1fr}}
</style>
