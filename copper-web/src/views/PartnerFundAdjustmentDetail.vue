<template>
  <div class="page" v-loading="query.isLoading.value">
    <template v-if="detail">
      <div class="panel">
        <div class="panel-header detail-header">
          <div>
            <div class="title-line"><div class="panel-title">余额调整单 {{ header.adjustmentNo }}</div><el-tag :type="statusType(header.status)">{{ statusName(header.status) }}</el-tag></div>
            <div class="muted">{{ adjustmentTypeName(header.adjustmentType) }} · {{ accountTypeName(header.accountType) }} · {{ header.partnerName }}</div>
          </div>
          <div class="header-actions">
            <el-button @click="router.push('/partner-fund-adjustments')">返回列表</el-button>
            <el-button v-if="header.status === 'DRAFT' && canManage" :loading="acting" @click="cancelDocument">作废草稿</el-button>
            <el-button v-if="header.status === 'DRAFT' && canApprove" type="primary" :loading="acting" @click="confirmDocument">确认并过账</el-button>
            <el-button v-if="header.status === 'CONFIRMED' && canReverse" type="danger" plain :loading="acting" @click="reverseDocument">冲正</el-button>
          </div>
        </div>
      </div>

      <el-alert v-if="header.status === 'DRAFT'" title="草稿尚未影响余额" description="确认后才会更新往来余额、生成业务过账，并自动产生待财务审核的草稿凭证。" type="warning" :closable="false" show-icon />
      <el-alert v-else-if="header.status === 'CONFIRMED'" title="调整已确认，原业务事实已冻结" description="如确认错误，请冲正本单后重新建立正确单据；不得直接覆盖余额或原调整记录。" type="success" :closable="false" show-icon />
      <el-alert v-else-if="header.status === 'REVERSED'" :title="`原调整已由 ${header.reversalNo} 冲正`" :description="header.reversalReason" type="info" :closable="false" show-icon />

      <div class="detail-grid">
        <div class="panel">
          <div class="section-title">调整事实</div>
          <el-descriptions :column="2" border class="fixed-detail-descriptions" label-width="132px">
            <el-descriptions-item label="调整单号"><span class="related-record-no">{{ header.adjustmentNo }}</span></el-descriptions-item>
            <el-descriptions-item label="业务日期">{{ formatDate(header.businessDate) }}</el-descriptions-item>
            <el-descriptions-item label="往来单位">{{ header.partnerName }}</el-descriptions-item>
            <el-descriptions-item label="余额账户">{{ accountTypeName(header.accountType) }}</el-descriptions-item>
            <el-descriptions-item label="业务类型">{{ adjustmentTypeName(header.adjustmentType) }}</el-descriptions-item>
            <el-descriptions-item label="调整方向">{{ header.direction === 'INCREASE' ? '增加余额' : '减少余额' }}</el-descriptions-item>
            <el-descriptions-item label="调整金额"><strong :class="header.direction === 'INCREASE' ? 'amount-increase' : 'amount-decrease'">{{ header.direction === 'INCREASE' ? '+' : '-' }}{{ money(header.amount) }}</strong></el-descriptions-item>
            <el-descriptions-item label="业务依据号">{{ header.basisNo }}</el-descriptions-item>
            <el-descriptions-item label="税票处理" :span="2">{{ taxTreatmentName(header.taxTreatment) }}</el-descriptions-item>
            <el-descriptions-item label="具体原因" :span="2">{{ header.memo }}</el-descriptions-item>
          </el-descriptions>
        </div>

        <div class="panel">
          <div class="section-title">确认、过账与凭证</div>
          <el-descriptions :column="1" border class="fixed-detail-descriptions" label-width="140px">
            <el-descriptions-item label="编制人">{{ header.createdByName || '-' }} · {{ formatDateTime(header.createdAt) }}</el-descriptions-item>
            <el-descriptions-item label="确认负责人">{{ header.confirmedByName || '-' }}<span v-if="header.confirmedAt"> · {{ formatDateTime(header.confirmedAt) }}</span></el-descriptions-item>
            <el-descriptions-item label="业务过账">
              <router-link v-if="header.postingBatchId" class="table-link" :to="`/business-postings/${header.postingBatchId}`">{{ header.postingNo }} · {{ header.postingStatus }}</router-link><span v-else>-</span>
            </el-descriptions-item>
            <el-descriptions-item label="会计凭证">
              <router-link v-if="header.accountingVoucherId" class="table-link" :to="`/accounting/vouchers/${header.accountingVoucherId}`">{{ header.accountingVoucherNo }} · {{ voucherStatusName(header.accountingVoucherStatus) }}</router-link><span v-else>{{ header.status === 'CONFIRMED' ? '待自动制证' : '-' }}</span>
            </el-descriptions-item>
            <el-descriptions-item v-if="header.status === 'CANCELLED'" label="作废记录">{{ header.cancelledByName }} · {{ formatDateTime(header.cancelledAt) }}<br>{{ header.cancelReason }}</el-descriptions-item>
            <el-descriptions-item v-if="header.status === 'REVERSED'" label="冲正记录">{{ header.reversedByName }} · {{ formatDateTime(header.reversedAt) }}<br>{{ header.reversalNo }} · {{ header.reversalReason }}</el-descriptions-item>
          </el-descriptions>
          <div class="accounting-note">本单不形成现金或银行流水，也不改变订单、应收应付和发票。自动凭证保持草稿，财务应按企业执行的会计制度、税务处理和业务依据复核后再审核记账。</div>
        </div>
      </div>

      <div class="panel">
        <div class="panel-header"><div><div class="panel-title">审批与审计轨迹</div><div class="muted">事件只追加、不覆盖，确认和冲正均记录操作人、时间、前后状态与原因。</div></div></div>
        <el-table :data="detail.events" class="data-table" size="small" border>
          <el-table-column prop="createdAt" label="时间" width="176" :formatter="formatDateTimeColumn" />
          <el-table-column label="动作" width="110"><template #default="{ row }">{{ eventName(row.action) }}</template></el-table-column>
          <el-table-column label="状态变化" width="190"><template #default="{ row }">{{ row.fromStatus ? statusName(row.fromStatus) : '建立' }} → {{ statusName(row.toStatus) }}</template></el-table-column>
          <el-table-column prop="operatorName" label="操作人" width="150" />
          <el-table-column prop="reason" label="原因 / 说明" min-width="260" />
        </el-table>
      </div>

      <EvidenceAttachments document-type="PARTNER_FUND_ADJUSTMENT" :document-id="header.id" title="合同、返利对账单、迁移清单及差错依据" default-evidence-type="ORIGINAL_DOCUMENT" />
    </template>
    <el-result v-else-if="query.isError.value" icon="error" title="余额调整单加载失败" sub-title="请检查权限或稍后重试"><template #extra><el-button @click="query.refetch()">重新加载</el-button></template></el-result>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import { accountTypeName, currentDateText, formatDate, formatDateTime, formatDateTimeColumn, money } from '@/api/format'
import EvidenceAttachments from '@/components/EvidenceAttachments.vue'
import { useAppStore } from '@/stores/app'
import { resolveRiskException } from '@/utils/riskException'

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const appStore = useAppStore()
const id = Number(route.params.id)
const acting = ref(false)
const query = useQuery({ queryKey: ['partner-fund-adjustment', id], queryFn: () => api.partnerFundAdjustment(id), enabled: Number.isFinite(id) && id > 0 })
const detail = computed(() => query.data.value)
const header = computed(() => detail.value!.header)
const canManage = computed(() => appStore.hasPermission('partner.fund.adjust.manage'))
const canApprove = computed(() => appStore.hasPermission('partner.fund.adjust.approve'))
const canReverse = computed(() => appStore.hasPermission('partner.fund.adjust.reverse'))

async function refresh() {
  await Promise.all([
    query.refetch(), queryClient.invalidateQueries({ queryKey: ['partner-fund-adjustments'] }),
    queryClient.invalidateQueries({ queryKey: ['partner-accounts'] }), queryClient.invalidateQueries({ queryKey: ['partner-account-logs'] }),
  ])
}
async function confirmDocument() {
  const confirmed = await ElMessageBox.confirm('确认后将更新往来余额并形成业务过账和草稿凭证。请确认业务类型、金额、依据及税票处理均已复核。', `确认 ${header.value.adjustmentNo}`, { type: 'warning', confirmButtonText: '确认并过账' }).then(() => true).catch(() => false)
  if (!confirmed) return
  const authorization = await resolveRiskException('PARTNER_FUND_ADJUSTMENT', id, 'CONFIRM', '确认余额调整')
  if (!authorization.allowed) return
  acting.value = true
  try { await api.confirmPartnerFundAdjustment(id, authorization); ElMessage.success('余额调整已确认并过账'); await refresh() }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '余额调整确认失败') }
  finally { acting.value = false }
}
async function cancelDocument() {
  const result = await ElMessageBox.prompt('草稿不会影响余额。请输入不再继续的具体原因。', `作废草稿 ${header.value.adjustmentNo}`, { type: 'warning', inputValidator: (value) => String(value || '').trim().length >= 4 || '原因至少4个字' }).catch(() => null)
  if (!result) return
  acting.value = true
  try {
    await api.cancelPartnerFundAdjustment(id, { reason: result.value.trim() })
    ElMessage.success('余额调整草稿已作废')
    await refresh()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '余额调整草稿作废失败')
  } finally { acting.value = false }
}
async function reverseDocument() {
  const result = await ElMessageBox.prompt('冲正会生成反向余额流水、反向业务过账和冲销凭证；如果余额已被后续业务使用，系统将阻断。请输入具体冲正原因。', `冲正 ${header.value.adjustmentNo}`, { type: 'warning', confirmButtonText: '确认冲正', inputValidator: (value) => String(value || '').trim().length >= 4 || '冲正原因至少4个字' }).catch(() => null)
  if (!result) return
  const authorization = await resolveRiskException('PARTNER_FUND_ADJUSTMENT', id, 'REVERSE', '冲正余额调整', result.value.trim())
  if (!authorization.allowed) return
  acting.value = true
  try { await api.reversePartnerFundAdjustment(id, { reason: result.value.trim(), businessDate: currentDateText(), approvalId: authorization.approvalId }); ElMessage.success('原余额调整已完整冲正'); await refresh() }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : '余额调整冲正失败') }
  finally { acting.value = false }
}
function adjustmentTypeName(value: string) { return ({ OPENING_BALANCE: '期初余额', CUSTOMER_REWARD_GRANT: '客户奖励确认', SUPPLIER_REBATE_GRANT: '供应商返利确认', BALANCE_CORRECTION: '余额差错调整' } as Record<string, string>)[value] ?? value }
function taxTreatmentName(value: string) { return ({ NOT_APPLICABLE: '不涉及单独税票处理', PENDING_PROFESSIONAL_REVIEW: '待会计 / 税务人员复核', SEPARATE_INVOICE_HANDLING: '发票已在税票模块单独处理' } as Record<string, string>)[value] ?? value }
function statusName(value: string) { return ({ DRAFT: '草稿', CONFIRMED: '已确认', CANCELLED: '已作废', REVERSED: '已冲正' } as Record<string, string>)[value] ?? value }
function statusType(value: string) { return value === 'CONFIRMED' ? 'success' : value === 'DRAFT' ? 'warning' : 'info' }
function voucherStatusName(value: string) { return ({ DRAFT: '草稿', REVIEWED: '已审核', POSTED: '已记账', VOIDED: '已作废', REVERSED: '已冲销' } as Record<string, string>)[value] ?? value }
function eventName(value: string) { return ({ CREATE: '建立草稿', CONFIRM: '负责人确认', CANCEL: '作废草稿', REVERSE: '冲正' } as Record<string, string>)[value] ?? value }
</script>

<style scoped>
.detail-header, .title-line, .header-actions { display: flex; align-items: center; gap: 10px; }
.detail-header { width: 100%; justify-content: space-between; }
.header-actions { flex-wrap: wrap; justify-content: flex-end; }
.detail-grid { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(360px, .8fr); gap: 14px; margin-top: 14px; }
.section-title { margin-bottom: 14px; font-size: 16px; font-weight: 700; }
.accounting-note { margin: 14px 18px 18px; padding: 12px 14px; border-left: 3px solid #d46a3a; border-radius: 4px; color: #586874; background: #fff8f4; line-height: 1.65; }
.amount-increase { color: #268248; }
.amount-decrease { color: #c94f3d; }
@media (max-width: 1050px) { .detail-grid { grid-template-columns: 1fr; } }
@media (max-width: 720px) { .detail-header { align-items: flex-start; flex-direction: column; } .header-actions { justify-content: flex-start; } }
</style>
