<template>
  <div class="page" v-loading="query.isLoading.value">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="会计凭证与采购、销售、库存和资金业务分层保存；业务过账生成凭证草稿，审核后才能记入总账。"
    />

    <div class="accounting-heading">
      <div>
        <div class="panel-title">{{ dashboard?.entity.entityName || '本企业' }} · 会计核算</div>
        <div class="muted">本位币 {{ dashboard?.entity.baseCurrency || 'CNY' }} · 当前期间 {{ dashboard?.currentPeriod?.periodName || '未初始化' }}</div>
      </div>
      <el-tag :type="dashboard?.currentPeriod?.status === 'OPEN' ? 'success' : 'danger'">
        {{ dashboard?.currentPeriod?.status === 'OPEN' ? '期间开放' : '期间锁定或未初始化' }}
      </el-tag>
    </div>

    <div class="kpi-grid">
      <div v-for="item in kpis" :key="item.label" class="kpi">
        <div class="kpi-label">{{ item.label }}</div>
        <div class="kpi-value" :class="item.danger ? 'danger-text' : ''">{{ item.value }}</div>
        <div class="kpi-note">{{ item.note }}</div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-header"><div class="panel-title">会计工作入口</div><el-button :loading="query.isFetching.value" @click="query.refetch()">刷新</el-button></div>
      <div class="accounting-actions">
        <button v-for="item in entries" :key="item.path" type="button" class="accounting-entry" @click="$router.push(item.path)">
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
import { ArrowRight, DataAnalysis, Files, Setting, Tickets } from '@element-plus/icons-vue'
import { api } from '@/api/client'
import { money } from '@/api/format'

const query = useQuery({ queryKey: ['accounting-dashboard'], queryFn: api.accountingDashboard })
const dashboard = computed(() => query.data.value)
const kpis = computed(() => [
  { label: '待审核凭证', value: dashboard.value?.draftVoucherCount ?? 0, note: '业务自动制证和手工暂存' },
  { label: '待记账凭证', value: dashboard.value?.reviewedVoucherCount ?? 0, note: '已审核、尚未进入总账' },
  { label: '已记账凭证', value: dashboard.value?.postedVoucherCount ?? 0, note: '含已被冲销的原凭证' },
  { label: '制证异常', value: dashboard.value?.openIssueCount ?? 0, note: '需要修复规则或辅助核算后重试', danger: Boolean(dashboard.value?.openIssueCount) },
  { label: '未处理业务过账', value: dashboard.value?.ungeneratedPostingCount ?? 0, note: '锁定期间前必须归零', danger: Boolean(dashboard.value?.ungeneratedPostingCount) },
  { label: '试算差额', value: money(dashboard.value?.trialDifference), note: '借贷平衡时应为 0.00', danger: Number(dashboard.value?.trialDifference) !== 0 },
])
const entries = [
  { path: '/accounting/vouchers', title: '凭证中心', note: '生成、审核、记账、退回和冲销', icon: Tickets },
  { path: '/accounting/ledger', title: '总账与明细账', note: '试算平衡并追溯到凭证分录', icon: DataAnalysis },
  { path: '/accounting/master-data', title: '科目与期间', note: '维护科目、辅助核算和期间锁定', icon: Files },
  { path: '/accounting/rules', title: '自动制证规则', note: '按生效日期发布业务到会计映射', icon: Setting },
]
</script>

<style scoped>
.accounting-heading { display: flex; align-items: center; justify-content: space-between; margin: 16px 0; }
.accounting-heading .muted { margin-top: 5px; }
.danger-text { color: var(--el-color-danger); }
.accounting-actions { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; padding: 16px; }
.accounting-entry { display: grid; grid-template-columns: 34px 1fr 20px; align-items: center; gap: 10px; padding: 16px; color: inherit; text-align: left; background: #fff; border: 1px solid #dbe4ec; border-radius: 8px; cursor: pointer; }
.accounting-entry:hover { border-color: #c85d2a; box-shadow: 0 4px 14px rgb(20 42 58 / 8%); }
.accounting-entry > .el-icon:first-child { color: #c85d2a; font-size: 24px; }
.accounting-entry span { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
.accounting-entry small { color: #73818c; }
@media (max-width: 900px) { .accounting-actions { grid-template-columns: 1fr; } }
</style>
