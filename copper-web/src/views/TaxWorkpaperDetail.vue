<template>
  <div class="page" v-loading="loading">
    <template v-if="detail">
      <PageHeader :title="`增值税工作底稿 ${item.workpaperNo}`" back-to="/tax-compliance">
        <el-button :loading="acting" @click="exportCsv">导出复核底稿</el-button>
        <el-button v-if="item.status === 'DRAFT' && canReview" type="primary" :disabled="!canMarkReviewed" @click="review">标记已复核</el-button>
      </PageHeader>

      <el-alert v-if="item.status === 'SUPERSEDED'" type="info" :closable="false" show-icon title="该底稿已被同期间的新版本替代，仅供历史审计，不允许再次复核。" />
      <el-alert v-else-if="item.blockerCount" type="error" :closable="false" show-icon :title="`存在 ${item.blockerCount} 项阻断，必须回到来源单据处理并重新生成底稿。`" />
      <el-alert v-else-if="hasDifference" type="error" :closable="false" show-icon title="票账差异不为零，禁止标记已复核。" />
      <el-alert v-else-if="item.status === 'DRAFT'" type="warning" :closable="false" show-icon title="系统检查已通过，但仍须由企业会计或税务专业人员核对税法适用、申报口径和截止时点后再标记已复核。" />
      <el-alert v-else type="success" :closable="false" show-icon title="该版本已完成企业内部复核；这不代表已向税务机关申报，也不是申报成功回执。" />

      <div class="summary-grid">
        <div class="summary-card"><span>销项税额</span><strong>{{ money(item.outputTaxAmount) }}</strong><small>总账 {{ money(item.ledgerOutputTax) }}</small></div>
        <div class="summary-card"><span>确认抵扣进项税额</span><strong>{{ money(item.deductibleInputTax) }}</strong><small>进项转出 {{ money(item.transferredOutInputTax) }}</small></div>
        <div class="summary-card"><span>预计应纳增值税</span><strong class="primary-value">{{ money(item.estimatedVatPayable) }}</strong><small>仅为本底稿规则计算结果</small></div>
        <div class="summary-card"><span>销项票账差异</span><strong :class="Number(item.outputLedgerDifference) ? 'danger-text' : ''">{{ money(item.outputLedgerDifference) }}</strong><small>必须为 0</small></div>
        <div class="summary-card"><span>进项票账差异</span><strong :class="Number(item.inputLedgerDifference) ? 'danger-text' : ''">{{ money(item.inputLedgerDifference) }}</strong><small>必须为 0</small></div>
        <div class="summary-card"><span>检查结果</span><strong :class="item.blockerCount ? 'danger-text' : ''">{{ item.blockerCount }} 阻断 / {{ item.warningCount }} 提醒</strong><small>{{ workpaperStatusName(item.status) }}</small></div>
      </div>

      <div class="panel detail-panel">
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="税款所属期">{{ item.periodName }}</el-descriptions-item>
          <el-descriptions-item label="税种">增值税</el-descriptions-item>
          <el-descriptions-item label="版本">第 {{ item.versionNo }} 版</el-descriptions-item>
          <el-descriptions-item label="状态"><el-tag size="small" :type="statusTag(item.status)">{{ workpaperStatusName(item.status) }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="规则版本">{{ item.ruleCode }}/v{{ item.ruleVersion }}</el-descriptions-item>
          <el-descriptions-item label="数据截止">{{ dateTime(item.dataCutoffAt) }}</el-descriptions-item>
          <el-descriptions-item label="生成">{{ item.generatedByName }} · {{ dateTime(item.generatedAt) }}</el-descriptions-item>
          <el-descriptions-item label="复核">{{ item.reviewedByName ? `${item.reviewedByName} · ${dateTime(item.reviewedAt || '')}` : '尚未复核' }}</el-descriptions-item>
          <el-descriptions-item label="复核说明" :span="4">{{ item.reviewNote || '-' }}</el-descriptions-item>
          <el-descriptions-item label="内容 SHA-256" :span="4"><span class="hash">{{ item.contentSha256 }}</span></el-descriptions-item>
        </el-descriptions>
      </div>

      <div class="panel">
        <div class="panel-header"><div><div class="panel-title">计算项目与异常检查</div><div class="muted">金额、公式、来源数量和来源查询口径随版本冻结，便于复算与追责</div></div><el-radio-group v-model="lineFilter" size="small"><el-radio-button value="ALL">全部</el-radio-button><el-radio-button value="EXCEPTION">只看异常</el-radio-button></el-radio-group></div>
        <el-table :data="visibleLines" border size="small" class="data-table section-table" empty-text="暂无项目">
          <el-table-column prop="lineNo" label="行" width="55" />
          <el-table-column label="分区" width="115"><template #default="{ row }">{{ sectionName(row.sectionCode) }}</template></el-table-column>
          <el-table-column label="项目" min-width="210"><template #default="{ row }"><strong>{{ row.itemName }}</strong><div class="multiline-secondary">{{ row.itemCode }}</div></template></el-table-column>
          <el-table-column label="税率" width="80" align="right"><template #default="{ row }">{{ row.taxRate == null ? '-' : `${row.taxRate}%` }}</template></el-table-column>
          <el-table-column label="未税金额" width="125" align="right"><template #default="{ row }">{{ money(row.amountExcludingTax) }}</template></el-table-column>
          <el-table-column label="税额 / 差异" width="130" align="right"><template #default="{ row }"><span :class="row.severity === 'BLOCKER' ? 'danger-text' : ''">{{ money(row.taxAmount) }}</span></template></el-table-column>
          <el-table-column label="来源" width="80" align="center"><template #default="{ row }">{{ row.sourceCount }}</template></el-table-column>
          <el-table-column label="级别" width="95"><template #default="{ row }"><el-tag size="small" :type="severityTag(row.severity)">{{ severityName(row.severity) }}</el-tag></template></el-table-column>
          <el-table-column label="公式与来源口径" min-width="300"><template #default="{ row }"><div>{{ row.formula }}</div><div class="multiline-secondary source-snapshot">{{ row.sourceQuerySnapshot }}</div></template></el-table-column>
        </el-table>
      </div>

      <div class="panel">
        <div class="panel-title">版本与复核审计</div>
        <el-timeline class="event-timeline"><el-timeline-item v-for="event in detail.events" :key="event.id" :timestamp="`${event.operatorName} · ${dateTime(event.createdAt)}`" placement="top"><strong>{{ eventName(event.action) }}</strong><div class="muted">{{ statusSimple(event.fromStatus) }}<span v-if="event.fromStatus || event.toStatus"> → </span>{{ statusSimple(event.toStatus) }}</div><div>{{ event.reason }}</div></el-timeline-item></el-timeline>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute } from 'vue-router'
import { api, type TaxWorkpaperDetail } from '@/api/client'
import { money } from '@/api/format'
import PageHeader from '@/components/PageHeader.vue'
import { useAppStore } from '@/stores/app'

const route = useRoute()
const appStore = useAppStore()
const id = Number(route.params.id)
const loading = ref(false)
const acting = ref(false)
const detail = ref<TaxWorkpaperDetail | null>(null)
const item = computed(() => detail.value!.workpaper)
const lineFilter = ref<'ALL' | 'EXCEPTION'>('ALL')
const canReview = computed(() => appStore.hasPermission('tax.compliance.review'))
const hasDifference = computed(() => Number(item.value.outputLedgerDifference) !== 0 || Number(item.value.inputLedgerDifference) !== 0)
const canMarkReviewed = computed(() => item.value.blockerCount === 0 && !hasDifference.value)
const visibleLines = computed(() => lineFilter.value === 'ALL' ? detail.value?.lines || [] : detail.value?.lines.filter(line => line.severity !== 'INFO') || [])

onMounted(load)
async function load() { loading.value = true; try { detail.value = await api.taxWorkpaper(id) } finally { loading.value = false } }
async function review() { const { value } = await ElMessageBox.prompt('请记录已核对的外部凭证、验真验签、用途确认、总账和申报口径。标记已复核不等于已申报。', '标记工作底稿已复核', { inputType: 'textarea', inputValidator: text => String(text).trim().length >= 10 || '复核说明至少 10 个字' }); acting.value = true; try { await api.reviewTaxWorkpaper(id, value); ElMessage.success('底稿已标记为企业内部复核完成'); await load() } finally { acting.value = false } }
async function exportCsv() { acting.value = true; try { const blob = await api.exportTaxWorkpaper(id); const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = `${item.value.workpaperNo}.csv`; link.click(); URL.revokeObjectURL(url); ElMessage.success('工作底稿已导出；该文件不是税务申报回执') } finally { acting.value = false } }
function workpaperStatusName(value: string) { return ({ DRAFT: '待复核', REVIEWED: '已复核', SUPERSEDED: '已被替代' } as Record<string, string>)[value] || value }
function statusTag(value: string) { return value === 'REVIEWED' ? 'success' : value === 'SUPERSEDED' ? 'info' : 'warning' }
function statusSimple(value: string) { return value ? workpaperStatusName(value) : '无' }
function sectionName(value: string) { return ({ OUTPUT: '销项', INPUT: '进项', TRANSFER_OUT: '进项转出', LEDGER: '总账', DIFFERENCE: '票账差异', EXCEPTION: '异常检查' } as Record<string, string>)[value] || value }
function severityName(value: string) { return ({ INFO: '正常', WARNING: '提醒', BLOCKER: '阻断' } as Record<string, string>)[value] || value }
function severityTag(value: string) { return value === 'BLOCKER' ? 'danger' : value === 'WARNING' ? 'warning' : 'success' }
function eventName(value: string) { return ({ GENERATE: '生成版本', REVIEW: '完成复核', SUPERSEDE: '版本替代' } as Record<string, string>)[value] || value }
function dateTime(value: string) { return value ? value.replace('T', ' ').slice(0, 19) : '-' }
</script>

<style scoped>
.summary-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 12px; margin: 14px 0; }
.summary-card { display: flex; min-width: 0; flex-direction: column; gap: 6px; padding: 14px; background: #fff; border: 1px solid #dfe6eb; border-radius: 8px; }
.summary-card span, .summary-card small { color: #73818c; }
.summary-card strong { font-size: 19px; }
.primary-value { color: #c85d2a; }
.danger-text { color: var(--el-color-danger); }
.detail-panel { padding: 16px; }
.section-table { margin-top: 14px; }
.source-snapshot { margin-top: 4px; }
.hash { overflow-wrap: anywhere; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
.event-timeline { margin-top: 20px; }
@media (max-width: 1280px) { .summary-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 760px) { .summary-grid { grid-template-columns: 1fr 1fr; } }
</style>
