<template>
  <div class="page" v-loading="query.isLoading.value">
    <template v-if="detail">
      <PageHeader :title="`会计凭证 ${detail.voucher.voucherNo}`" back-to="/accounting/vouchers">
        <el-button v-if="detail.voucher.voucherType === 'MANUAL' && detail.voucher.status === 'DRAFT' && appStore.hasPermission('accounting.manage')" @click="$router.push(`/accounting/vouchers/${id}/edit`)">编辑</el-button>
        <el-button v-if="detail.voucher.status === 'DRAFT' && appStore.hasPermission('accounting.review')" type="success" :loading="acting" @click="review">审核</el-button>
        <el-button v-if="detail.voucher.status === 'REVIEWED' && appStore.hasPermission('accounting.review')" type="warning" :loading="acting" @click="reject">退回</el-button>
        <el-button v-if="detail.voucher.status === 'REVIEWED' && appStore.hasPermission('accounting.post')" type="primary" :loading="acting" @click="post">记账</el-button>
        <el-button v-if="detail.voucher.status === 'POSTED' && appStore.hasPermission('accounting.post')" type="danger" plain :loading="acting" @click="reverse">冲销</el-button>
      </PageHeader>

      <el-alert v-if="detail.voucher.status === 'POSTED'" type="success" :closable="false" show-icon title="该凭证已经记入总账，内容不可直接修改；如需更正，请生成冲销凭证。" />
      <el-alert v-else-if="detail.voucher.status === 'REVERSED'" type="warning" :closable="false" show-icon title="该原凭证已被冲销，总账保留原分录和反向分录。" />

      <div class="panel detail-panel">
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="凭证日期">{{ detail.voucher.voucherDate }}</el-descriptions-item>
          <el-descriptions-item label="凭证类型">{{ typeName(detail.voucher.voucherType) }}</el-descriptions-item>
          <el-descriptions-item label="状态"><el-tag size="small" :type="statusType(detail.voucher.status)">{{ statusName(detail.voucher.status) }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="会计期间">#{{ detail.voucher.periodId }}</el-descriptions-item>
          <el-descriptions-item label="摘要" :span="2">{{ detail.voucher.summary }}</el-descriptions-item>
          <el-descriptions-item label="来源单据" :span="2">
            <span>{{ detail.voucher.sourceNo || '手工录入' }}</span>
            <RouterLink v-if="detail.voucher.businessPostingBatchId" class="business-link source-posting-link" :to="`/business-postings/${detail.voucher.businessPostingBatchId}`">查看业务过账</RouterLink>
            <RouterLink v-else-if="detail.voucher.sourceType?.startsWith('TAX_INVOICE') && detail.voucher.sourceId" class="business-link source-posting-link" :to="`/tax-invoices/${detail.voucher.sourceId}`">查看来源发票</RouterLink>
          </el-descriptions-item>
          <el-descriptions-item label="制单">{{ detail.voucher.makerName || '系统' }} · {{ formatDateTime(detail.voucher.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="审核">{{ detail.voucher.reviewerName || '-' }}<span v-if="detail.voucher.reviewedAt"> · {{ formatDateTime(detail.voucher.reviewedAt) }}</span></el-descriptions-item>
          <el-descriptions-item label="记账">{{ detail.voucher.posterName || '-' }}<span v-if="detail.voucher.postedAt"> · {{ formatDateTime(detail.voucher.postedAt) }}</span></el-descriptions-item>
          <el-descriptions-item label="关联冲销">
            <RouterLink v-if="detail.voucher.originalVoucherId" class="business-link" :to="`/accounting/vouchers/${detail.voucher.originalVoucherId}`">原凭证 #{{ detail.voucher.originalVoucherId }}</RouterLink>
            <RouterLink v-else-if="detail.voucher.reversalVoucherId" class="business-link" :to="`/accounting/vouchers/${detail.voucher.reversalVoucherId}`">冲销凭证 #{{ detail.voucher.reversalVoucherId }}</RouterLink>
            <span v-else>-</span>
          </el-descriptions-item>
        </el-descriptions>
      </div>

      <div v-if="detail.linkedTaxInvoices?.length" class="panel">
        <div class="panel-title">关联发票</div>
        <el-alert type="info" :closable="false" show-icon title="同一会计凭证可能同时解释原蓝票及后续红票处理，以下关联均可双向追溯。" />
        <el-table :data="detail.linkedTaxInvoices || []" border size="small" class="data-table linked-invoices">
          <el-table-column label="发票" min-width="230"><template #default="{ row }"><RouterLink class="business-link" :to="`/tax-invoices/${row.id}`">{{ row.invoiceNumber }}</RouterLink><div class="multiline-secondary">{{ row.internalNo }}</div></template></el-table-column>
          <el-table-column label="类型" width="120"><template #default="{ row }">{{ row.direction === 'INPUT' ? '进项' : '销项' }}{{ row.invoiceColor === 'RED' ? '红字' : '蓝字' }}</template></el-table-column>
          <el-table-column label="税额" width="130" align="right"><template #default="{ row }">{{ money(row.taxAmount) }}</template></el-table-column>
          <el-table-column prop="matchingBasis" label="关联依据" min-width="320" />
        </el-table>
      </div>

      <div class="panel">
        <div class="panel-title">凭证分录</div>
        <el-table :data="detail.lines" border size="small" class="data-table detail-lines">
          <el-table-column prop="lineNo" label="行" width="58" />
          <el-table-column label="科目" min-width="190"><template #default="{ row }"><strong>{{ row.accountCode }}</strong> {{ row.accountName }}</template></el-table-column>
          <el-table-column prop="summary" label="摘要" min-width="210" />
          <el-table-column label="借方" width="140" align="right"><template #default="{ row }">{{ Number(row.debitAmount) ? money(row.debitAmount) : '-' }}</template></el-table-column>
          <el-table-column label="贷方" width="140" align="right"><template #default="{ row }">{{ Number(row.creditAmount) ? money(row.creditAmount) : '-' }}</template></el-table-column>
          <el-table-column label="辅助核算" min-width="220"><template #default="{ row }"><div v-if="row.partnerName">往来：{{ row.partnerName }}</div><div v-if="row.productName" class="multiline-secondary">商品：{{ row.productName }}</div><div v-if="row.warehouseName" class="multiline-secondary">仓库：{{ row.warehouseName }}</div><span v-if="!row.partnerName && !row.productName && !row.warehouseName">-</span></template></el-table-column>
        </el-table>
        <div class="voucher-sum"><span>借方合计 {{ money(detail.voucher.debitTotal) }}</span><span>贷方合计 {{ money(detail.voucher.creditTotal) }}</span></div>
      </div>

      <div class="panel">
        <div class="panel-title">凭证审计轨迹</div>
        <el-timeline class="voucher-timeline">
          <el-timeline-item v-for="event in detail.events" :key="event.id" :timestamp="formatDateTime(event.createdAt)" placement="top">
            <div>{{ eventName(event.action) }} · {{ event.operatorName || '系统' }} <span v-if="event.fromStatus || event.toStatus" class="muted">{{ statusName(event.fromStatus) }} → {{ statusName(event.toStatus) }}</span></div>
            <div v-if="event.reason" class="muted">{{ event.reason }}</div>
          </el-timeline-item>
        </el-timeline>
      </div>
      <EvidenceAttachments
        document-type="ACCOUNTING_VOUCHER"
        :document-id="id"
        title="会计凭证补充原始凭据"
        default-evidence-type="ORIGINAL_DOCUMENT"
      />
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import { formatDateTime, money } from '@/api/format'
import PageHeader from '@/components/PageHeader.vue'
import EvidenceAttachments from '@/components/EvidenceAttachments.vue'
import { useAppStore } from '@/stores/app'

const route = useRoute()
const id = Number(route.params.id)
const appStore = useAppStore()
const queryClient = useQueryClient()
const acting = ref(false)
const query = useQuery({ queryKey: ['accounting-voucher', id], queryFn: () => api.accountingVoucher(id) })
const detail = computed(() => query.data.value)
async function run(action: () => Promise<unknown>, message: string) { acting.value = true; try { await action(); ElMessage.success(message); await Promise.all([query.refetch(), queryClient.invalidateQueries({ queryKey: ['accounting-vouchers'] }), queryClient.invalidateQueries({ queryKey: ['accounting-dashboard'] })]) } finally { acting.value = false } }
async function review() { await ElMessageBox.confirm('审核后凭证内容将冻结。确认审核？', '审核凭证', { type: 'warning' }); await run(() => api.reviewAccountingVoucher(id), '凭证已审核') }
async function reject() { const { value } = await ElMessageBox.prompt('请填写退回原因', '退回凭证', { inputValidator: (text) => Boolean(String(text).trim()) || '必须填写原因' }); await run(() => api.rejectAccountingVoucher(id, value), '凭证已退回') }
async function post() { await ElMessageBox.confirm('记账后只能通过冲销更正。确认写入总账？', '凭证记账', { type: 'warning' }); await run(() => api.postAccountingVoucher(id), '凭证已记账') }
async function reverse() { const { value } = await ElMessageBox.prompt('请填写冲销原因', '生成冲销凭证', { inputValidator: (text) => Boolean(String(text).trim()) || '必须填写原因' }); await run(() => api.reverseAccountingVoucher(id, value, localDate()), '冲销凭证草稿已生成') }
function localDate() { const now = new Date(); return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}` }
function typeName(value: string) { return ({ AUTO: '业务自动凭证', MANUAL: '手工凭证', REVERSAL: '冲销凭证' } as Record<string, string>)[value] || value }
function statusName(value: string) { return ({ DRAFT: '暂存', REVIEWED: '已审核', POSTED: '已记账', REVERSED: '已被冲销', CANCELLED: '已作废', '': '' } as Record<string, string>)[value] ?? value }
function statusType(value: string) { return value === 'POSTED' ? 'success' : value === 'REVIEWED' ? 'warning' : value === 'REVERSED' ? 'danger' : value === 'CANCELLED' ? 'info' : 'primary' }
function eventName(value: string) { return ({ CREATE: '新增暂存', UPDATE: '修改暂存', AUTO_GENERATE: '业务自动制证', SOURCE_GENERATE: '来源业务自动制证', REVIEW: '审核', REJECT: '审核退回', POST: '记账', CANCEL: '作废', CREATE_REVERSAL: '生成冲销凭证', REVERSE: '被冲销', SOURCE_REVERSE_CANCEL: '来源业务冲回并作废凭证' } as Record<string, string>)[value] || value }
</script>

<style scoped>
.detail-panel { margin-top: 14px; padding: 16px; }
.source-posting-link { margin-left: 12px; }
.detail-lines { margin-top: 14px; }
.linked-invoices { margin-top: 14px; }
.voucher-sum { display: flex; justify-content: flex-end; gap: 36px; padding: 14px 10px 4px; font-weight: 700; }
.voucher-timeline { margin-top: 18px; }
</style>
