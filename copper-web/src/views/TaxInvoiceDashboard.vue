<template>
  <div class="page" v-loading="query.isLoading.value">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="发票与订单、出入库、应收应付、收付款和会计凭证分别保存；开票不等于收付款，收付款也不等于开票。"
    />

    <div class="tax-heading">
      <div>
        <div class="panel-title">发票税务工作台</div>
        <div class="muted">蓝红票、用途确认、票货款账勾稽均保留原记录和审计轨迹</div>
      </div>
      <el-button :loading="query.isFetching.value" @click="query.refetch()">刷新</el-button>
    </div>

    <div class="kpi-grid">
      <div v-for="item in kpis" :key="item.label" class="kpi">
        <div class="kpi-label">{{ item.label }}</div>
        <div class="kpi-value" :class="item.danger ? 'danger-text' : ''">{{ item.value }}</div>
        <div class="kpi-note">{{ item.note }}</div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-header"><div class="panel-title">工作入口</div></div>
      <div class="tax-actions">
        <button v-for="item in entries" :key="item.path" type="button" class="tax-entry" @click="$router.push(item.path)">
          <el-icon><component :is="item.icon" /></el-icon>
          <span><strong>{{ item.title }}</strong><small>{{ item.note }}</small></span>
          <el-icon><ArrowRight /></el-icon>
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ArrowRight, DataAnalysis, Download, Files, Upload } from '@element-plus/icons-vue'
import { api } from '@/api/client'
import { money } from '@/api/format'

const query = useQuery({ queryKey: ['tax-invoice-dashboard'], queryFn: api.taxInvoiceDashboard })
const dashboard = computed(() => query.data.value)
const kpis = computed(() => [
  { label: '进项价税合计', value: money(dashboard.value?.inputTotalAmount), note: `${dashboard.value?.inputConfirmedCount ?? 0} 张 · 税额 ${money(dashboard.value?.inputTaxAmount)}` },
  { label: '销项价税合计', value: money(dashboard.value?.outputTotalAmount), note: `${dashboard.value?.outputConfirmedCount ?? 0} 张 · 税额 ${money(dashboard.value?.outputTaxAmount)}` },
  { label: '勾稽异常', value: dashboard.value?.reconciliationExceptionCount ?? 0, note: '票货款账任一层未完成', danger: Boolean(dashboard.value?.reconciliationExceptionCount) },
  { label: '待查验', value: dashboard.value?.verificationPendingCount ?? 0, note: '尚未登记税务查验结果', danger: Boolean(dashboard.value?.verificationPendingCount) },
  { label: '待用途确认', value: dashboard.value?.deductionPendingCount ?? 0, note: '蓝字进项尚未选择抵扣或不抵扣', danger: Boolean(dashboard.value?.deductionPendingCount) },
  { label: '红字确认待办', value: dashboard.value?.redConfirmationPendingCount ?? 0, note: '确认单72小时窗口内待处理', danger: Boolean(dashboard.value?.redConfirmationPendingCount) },
])
const entries = [
  { path: '/tax-compliance', title: '电子凭证与票账税', note: '接收原件、验真验签证据、业务关联及增值税工作底稿', icon: Files },
  { path: '/tax-invoices/input', title: '进项发票', note: '收票、查验、入账、抵扣及进项转出', icon: Download },
  { path: '/tax-invoices/output', title: '销项发票', note: '开票登记、红冲及销项税额追溯', icon: Upload },
  { path: '/tax-invoices/reconciliation', title: '税票勾稽', note: '核对合同、货、往来、资金和会计凭证', icon: DataAnalysis },
]
</script>

<style scoped>
.tax-heading { display: flex; align-items: center; justify-content: space-between; margin: 16px 0; }
.tax-heading .muted { margin-top: 5px; }
.danger-text { color: var(--el-color-danger); }
.tax-actions { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; padding: 16px; }
.tax-entry { display: grid; grid-template-columns: 34px 1fr 20px; align-items: center; gap: 10px; padding: 16px; color: inherit; text-align: left; background: #fff; border: 1px solid #dbe4ec; border-radius: 8px; cursor: pointer; }
.tax-entry:hover { border-color: #c85d2a; box-shadow: 0 4px 14px rgb(20 42 58 / 8%); }
.tax-entry > .el-icon:first-child { color: #c85d2a; font-size: 24px; }
.tax-entry span { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
.tax-entry small { color: #73818c; }
@media (max-width: 1050px) { .tax-actions { grid-template-columns: 1fr; } }
</style>
