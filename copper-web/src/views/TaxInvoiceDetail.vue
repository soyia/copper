<template>
  <div class="page" v-loading="query.isLoading.value">
    <template v-if="detail">
      <PageHeader :title="`${directionName(detail.invoice.direction)}发票 ${detail.invoice.invoiceNumber}`" :back-to="backTo">
        <el-button plain :loading="printing" @click="printReconciliationSheet">打印 / 导出税票勾稽表</el-button>
        <el-button v-if="detail.invoice.status === 'DRAFT' && appStore.hasPermission('invoice.manage')" @click="$router.push(`/tax-invoices/${id}/edit`)">编辑</el-button>
        <el-button v-if="detail.invoice.invoiceColor === 'RED' && detail.invoice.status === 'DRAFT' && appStore.hasPermission('invoice.red.manage')" @click="openWorkflow('RED_CONFIRMATION')">红字确认单</el-button>
        <el-button v-if="detail.invoice.status === 'DRAFT' && appStore.hasPermission('invoice.confirm')" type="primary" :loading="acting" @click="confirmInvoice">确认发票</el-button>
        <el-button v-if="detail.invoice.status === 'CONFIRMED' && appStore.hasPermission('invoice.verify')" @click="openWorkflow('VERIFY')">登记查验</el-button>
        <el-button v-if="detail.invoice.status === 'CONFIRMED' && detail.invoice.bookingStatus === 'UNCONFIRMED' && appStore.hasPermission('invoice.verify')" @click="openWorkflow('BOOKING')">确认入账标识</el-button>
        <el-button v-if="canConfirmUsage && appStore.hasPermission('invoice.verify')" type="success" @click="openWorkflow('USAGE')">确认进项用途</el-button>
        <el-button v-if="canTransferOut && appStore.hasPermission('invoice.verify')" type="warning" @click="openWorkflow('TRANSFER_OUT')">进项转出</el-button>
        <el-button v-if="detail.invoice.status === 'CONFIRMED' && appStore.hasPermission('invoice.void')" type="danger" plain :loading="acting" @click="voidInvoice">作废</el-button>
      </PageHeader>

      <el-alert v-if="detail.invoice.status === 'DRAFT'" type="warning" :closable="false" show-icon title="草稿尚未形成正式发票事实；确认后票面内容冻结，更正必须作废或开具红字票。" />
      <el-alert v-else-if="detail.invoice.status === 'VOIDED'" type="info" :closable="false" show-icon :title="`该发票已作废：${detail.voidReason}`" />
      <el-alert v-else-if="detail.invoice.reconciliationStatus !== 'COMPLETE'" type="warning" :closable="false" show-icon title="该发票的票货款账勾稽尚未完成；这不会改写原业务单据，但必须在结账前处理差异。" />
      <div class="muted tax-boundary-note">数电发票查验、税务数字账户入账标识和进项用途分别登记，任何一步都不代替原业务或资金确认。</div>

      <div class="panel detail-panel">
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="内部单号">{{ detail.invoice.internalNo }}</el-descriptions-item>
          <el-descriptions-item label="蓝红字"><el-tag size="small" :type="detail.invoice.invoiceColor === 'RED' ? 'danger' : 'primary'">{{ detail.invoice.invoiceColor === 'RED' ? '红字' : '蓝字' }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="发票类型">{{ typeName(detail.invoice.invoiceType) }}</el-descriptions-item>
          <el-descriptions-item label="状态"><el-tag size="small" :type="statusType(detail.invoice.status)">{{ statusName(detail.invoice.status) }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="开票日期">{{ detail.invoice.invoiceDate }}</el-descriptions-item>
          <el-descriptions-item label="收到 / 开具日期">{{ detail.invoice.receivedOrIssuedDate }}</el-descriptions-item>
          <el-descriptions-item label="往来单位"><RouterLink class="business-link" :to="`/partners/${detail.invoice.partnerId}`">{{ detail.invoice.partnerName }}</RouterLink></el-descriptions-item>
          <el-descriptions-item label="原蓝字发票"><RouterLink v-if="detail.invoice.blueInvoiceId" class="business-link" :to="`/tax-invoices/${detail.invoice.blueInvoiceId}`">#{{ detail.invoice.blueInvoiceId }}</RouterLink><span v-else>-</span></el-descriptions-item>
          <el-descriptions-item label="销售方" :span="2">{{ detail.issuerName }}<div class="multiline-secondary">{{ detail.issuerTaxNo || '未记录纳税人识别号' }}</div></el-descriptions-item>
          <el-descriptions-item label="购买方" :span="2">{{ detail.recipientName }}<div class="multiline-secondary">{{ detail.recipientTaxNo || '未记录纳税人识别号' }}</div></el-descriptions-item>
          <el-descriptions-item label="查验">{{ verifyName(detail.invoice.verificationStatus) }}</el-descriptions-item>
          <el-descriptions-item label="入账标识">{{ bookingName(detail.invoice.bookingStatus) }}</el-descriptions-item>
          <el-descriptions-item label="进项用途">{{ usageName(detail.invoice.usageStatus) }}<span v-if="detail.invoice.usagePeriod"> · {{ detail.invoice.usagePeriod }}</span></el-descriptions-item>
          <el-descriptions-item label="红字确认单">{{ redConfirmationName(detail.invoice.redConfirmationStatus) }}<div v-if="detail.redConfirmationNo" class="multiline-secondary">{{ detail.redConfirmationNo }}</div></el-descriptions-item>
          <el-descriptions-item label="会计凭证" :span="2"><RouterLink v-if="detail.invoice.accountingVoucherId" class="business-link" :to="`/accounting/vouchers/${detail.invoice.accountingVoucherId}`">税额重分类凭证 #{{ detail.invoice.accountingVoucherId }}</RouterLink><span v-else-if="detail.invoice.invoiceColor === 'RED' && detail.invoice.direction === 'INPUT' && detail.invoice.accountingMatchedTax >= detail.invoice.taxAmount">本票无需新制凭证，见下方票账勾稽</span><span v-else>无需生成或尚未生成</span></el-descriptions-item>
          <el-descriptions-item label="用途 / 转出凭证" :span="2"><RouterLink v-if="detail.invoice.usageVoucherId" class="business-link" :to="`/accounting/vouchers/${detail.invoice.usageVoucherId}`">用途凭证 #{{ detail.invoice.usageVoucherId }}</RouterLink><span v-if="detail.invoice.usageVoucherId && detail.invoice.transferOutVoucherId"> · </span><RouterLink v-if="detail.invoice.transferOutVoucherId" class="business-link" :to="`/accounting/vouchers/${detail.invoice.transferOutVoucherId}`">转出凭证 #{{ detail.invoice.transferOutVoucherId }}</RouterLink><span v-if="!detail.invoice.usageVoucherId && !detail.invoice.transferOutVoucherId">-</span></el-descriptions-item>
          <el-descriptions-item v-if="detail.memo" label="备注" :span="4">{{ detail.memo }}</el-descriptions-item>
        </el-descriptions>
      </div>

      <div class="panel">
        <div class="panel-title">票面明细</div>
        <el-table :data="detail.lines" border size="small" class="data-table section-table">
          <el-table-column prop="lineNo" label="行" width="55" />
          <el-table-column label="商品 / 项目" min-width="220"><template #default="{ row }"><strong>{{ row.productName }}</strong><div class="multiline-secondary">{{ [row.productSku, row.productSpec].filter(Boolean).join(' · ') || '-' }}</div></template></el-table-column>
          <el-table-column label="数量" width="140" align="right"><template #default="{ row }">{{ row.quantity }} {{ row.unit }}</template></el-table-column>
          <el-table-column label="未税单价" width="130" align="right"><template #default="{ row }">{{ money(row.unitPriceExcludingTax) }}</template></el-table-column>
          <el-table-column label="未税金额" width="130" align="right"><template #default="{ row }">{{ money(row.amountExcludingTax) }}</template></el-table-column>
          <el-table-column label="税率快照" min-width="180"><template #default="{ row }">{{ row.taxRuleName }} · {{ row.taxRate }}%<div class="multiline-secondary" :title="row.legalBasis">{{ row.taxRuleCode }}</div></template></el-table-column>
          <el-table-column label="税额" width="120" align="right"><template #default="{ row }">{{ money(row.taxAmount) }}</template></el-table-column>
          <el-table-column label="价税合计" width="135" align="right"><template #default="{ row }"><strong>{{ money(row.totalAmount) }}</strong></template></el-table-column>
        </el-table>
        <div class="invoice-total"><span>未税金额 {{ money(detail.invoice.amountExcludingTax) }}</span><span>税额 {{ money(detail.invoice.taxAmount) }}</span><strong>价税合计 {{ money(detail.invoice.totalAmount) }}</strong></div>
      </div>

      <div class="panel">
        <div class="panel-header"><div><div class="panel-title">票货款账勾稽</div><div class="muted">同一发票可关联多张单据，同一单据也可分多张发票；每个层次独立核对。</div></div><el-button v-if="detail.invoice.status !== 'VOIDED' && appStore.hasPermission('invoice.manage')" type="primary" plain @click="openRelation">新增勾稽</el-button></div>
        <div class="reconciliation-grid">
          <div v-for="item in reconciliationCards" :key="item.label" class="reconciliation-card" :class="item.complete ? 'is-complete' : 'is-pending'">
            <span>{{ item.label }}</span><strong>{{ money(item.matched) }} / {{ money(item.target) }}</strong><small>{{ item.note }}</small>
          </div>
        </div>
        <el-table :data="detail.relations" border size="small" class="data-table section-table">
          <el-table-column label="层次" width="100"><template #default="{ row }">{{ layerName(row.relationLayer) }}</template></el-table-column>
          <el-table-column label="来源单据" min-width="190"><template #default="{ row }"><RouterLink v-if="sourcePath(row)" class="business-link" :to="sourcePath(row)!">{{ row.sourceNo }}</RouterLink><span v-else>{{ row.sourceNo }}</span><div class="multiline-secondary">{{ sourceTypeName(row.sourceType) }} · {{ row.sourceBusinessDate || '-' }}</div></template></el-table-column>
          <el-table-column label="未税 / 税额" width="180" align="right"><template #default="{ row }">{{ money(row.amountExcludingTax) }} / {{ money(row.taxAmount) }}</template></el-table-column>
          <el-table-column label="价税合计" width="130" align="right"><template #default="{ row }">{{ money(row.totalAmount) }}</template></el-table-column>
          <el-table-column prop="matchingBasis" label="勾稽依据" min-width="220" show-overflow-tooltip />
          <el-table-column label="状态" width="92"><template #default="{ row }"><el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status === 'ACTIVE' ? '有效' : '已解除' }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="80" fixed="right"><template #default="{ row }"><el-button v-if="row.status === 'ACTIVE' && row.relationLayer !== 'ACCOUNTING' && appStore.hasPermission('invoice.manage')" link type="danger" @click="reverseRelation(row.id)">解除</el-button></template></el-table-column>
        </el-table>
      </div>

      <div class="panel">
        <div class="panel-title">税务与审计轨迹</div>
        <el-timeline class="invoice-timeline">
          <el-timeline-item v-for="event in detail.events" :key="event.id" :timestamp="formatDateTime(event.createdAt)" placement="top">
            <div>{{ eventName(event.action) }} · {{ event.operatorName || '系统' }} <span class="muted">{{ event.fromStatus }}<template v-if="event.fromStatus || event.toStatus"> → </template>{{ event.toStatus }}</span></div>
            <div v-if="event.reason" class="muted">{{ event.reason }}</div>
          </el-timeline-item>
        </el-timeline>
      </div>

      <div class="panel">
        <div class="panel-title">发票查验记录</div>
        <el-table :data="detail.verificationRecords" border size="small" class="data-table section-table" empty-text="尚未登记附带来源凭证的查验记录">
          <el-table-column label="查验时间" width="170"><template #default="{ row }">{{ formatDateTime(row.checkedAt) }}</template></el-table-column>
          <el-table-column label="来源" min-width="160"><template #default="{ row }">{{ verificationSourceName(row.verificationSource) }}</template></el-table-column>
          <el-table-column label="结果" width="105"><template #default="{ row }"><el-tag size="small" :type="row.verificationResult === 'VERIFIED' ? 'success' : 'danger'">{{ verifyName(row.verificationResult) }}</el-tag></template></el-table-column>
          <el-table-column prop="verificationReference" label="平台流水 / 查验参考" min-width="200" />
          <el-table-column label="查验凭证" min-width="190"><template #default="{ row }"><a class="business-link" :href="api.evidenceAttachmentUrl('TAX_INVOICE', id, row.proofAttachmentId)" target="_blank" rel="noopener">下载凭证</a><div class="multiline-secondary hash" :title="row.proofSha256">{{ shortHash(row.proofSha256) }}</div></template></el-table-column>
          <el-table-column prop="operatorName" label="登记人" width="120" />
        </el-table>
      </div>

      <EvidenceAttachments
        document-type="TAX_INVOICE"
        :document-id="id"
        title="发票原件、查验凭证与勾稽表签字盖章归档"
        default-evidence-type="SIGNED_STAMPED_COPY"
        default-source-channel="SCAN"
        show-output-history
      />

      <section class="print-sheet a4-print-sheet">
        <div class="document-print-heading">
          <div class="document-print-company">{{ appStore.settings.companyName }}</div>
          <div class="document-print-title">税票勾稽表</div>
          <div class="document-print-subtitle"><span>{{ directionName(detail.invoice.direction) }} · {{ detail.invoice.invoiceColor === 'RED' ? '红字' : '蓝字' }}</span><span>打印时间：{{ printTime }}</span></div>
        </div>
        <table class="document-print-meta"><tbody>
          <tr><td>内部单号：{{ detail.invoice.internalNo }}</td><td>发票号码：{{ detail.invoice.invoiceNumber }}</td><td>开票日期：{{ detail.invoice.invoiceDate }}</td></tr>
          <tr><td>往来单位：{{ detail.invoice.partnerName }}</td><td>发票状态：{{ statusName(detail.invoice.status) }}</td><td>勾稽状态：{{ detail.invoice.reconciliationStatus }}</td></tr>
          <tr><td>未税金额：{{ money(detail.invoice.amountExcludingTax) }}</td><td>税额：{{ money(detail.invoice.taxAmount) }}</td><td>价税合计：{{ money(detail.invoice.totalAmount) }}</td></tr>
          <tr><td>查验：{{ verifyName(detail.invoice.verificationStatus) }}</td><td>入账标识：{{ bookingName(detail.invoice.bookingStatus) }}</td><td>进项用途：{{ usageName(detail.invoice.usageStatus) }}</td></tr>
        </tbody></table>
        <table class="document-print-table"><thead><tr><th>#</th><th>勾稽层次</th><th>来源单据</th><th>未税 / 税额</th><th>价税合计</th><th>依据 / 状态</th></tr></thead><tbody>
          <tr v-for="(row, index) in detail.relations" :key="row.id"><td class="center">{{ index + 1 }}</td><td>{{ layerName(row.relationLayer) }}</td><td>{{ sourceTypeName(row.sourceType) }}<br>{{ row.sourceNo }}</td><td class="right">{{ money(row.amountExcludingTax) }} / {{ money(row.taxAmount) }}</td><td class="right">{{ money(row.totalAmount) }}</td><td>{{ row.matchingBasis }} · {{ row.status === 'ACTIVE' ? '有效' : `已解除：${row.reversedReason || '-'}` }}</td></tr>
          <tr v-if="!detail.relations.length"><td class="center">-</td><td colspan="5">尚未建立分层勾稽关系</td></tr>
        </tbody></table>
        <table class="document-print-total"><tbody><tr v-for="item in reconciliationCards" :key="item.label"><td>{{ item.label }}</td><td>{{ money(item.matched) }} / {{ money(item.target) }}</td><td>{{ item.complete ? '已勾稽' : '待处理' }}</td><td>{{ item.note }}</td></tr></tbody></table>
        <div class="document-print-audit-title">查验与审计轨迹</div>
        <table class="document-print-table document-print-audit"><thead><tr><th>#</th><th>动作</th><th>状态变化</th><th>操作人 / 时间</th><th>原因</th></tr></thead><tbody>
          <tr v-for="(event, index) in detail.events" :key="event.id"><td class="center">{{ index + 1 }}</td><td>{{ eventName(event.action) }}</td><td>{{ event.fromStatus || '-' }} → {{ event.toStatus || '-' }}</td><td>{{ event.operatorName || '系统' }}<br>{{ formatDateTime(event.createdAt) }}</td><td>{{ event.reason || '-' }}</td></tr>
        </tbody></table>
        <div class="document-print-hash">打印数据摘要（SHA-256）：{{ snapshotHash }}</div>
        <div class="document-print-signatures"><span>制表：</span><span>业务核对：</span><span>会计 / 税务复核：</span><span>负责人：</span></div>
      </section>

      <el-dialog v-model="workflow.visible" :title="workflowTitle" width="520px" destroy-on-close>
        <el-form label-position="top">
          <template v-if="workflow.kind === 'VERIFY'">
            <el-alert class="workflow-alert" type="info" :closable="false" title="先在本页附件区上传“发票查验凭证”，查验登记必须引用该原始文件及其 SHA-256。" />
            <el-form-item label="查验结果"><el-select v-model="workflow.status"><el-option label="查验通过" value="VERIFIED" /><el-option label="查验异常" value="FAILED" /></el-select></el-form-item>
            <el-form-item label="查验来源" required><el-select v-model="workflow.verificationSource" style="width:100%"><el-option label="税务数字账户" value="TAX_DIGITAL_ACCOUNT" /><el-option label="全国发票查验平台" value="NATIONAL_PLATFORM" /><el-option label="主管税务机关" value="LOCAL_TAX_AUTHORITY" /><el-option label="人工复核" value="MANUAL_REVIEW" /></el-select></el-form-item>
            <el-form-item label="查验凭证附件" required><el-select v-model="workflow.proofAttachmentId" style="width:100%" placeholder="请选择有效的查验凭证"><el-option v-for="item in verificationProofs" :key="item.id" :label="`${item.originalFileName} · ${shortHash(item.sha256)}`" :value="item.id" /></el-select><div v-if="!verificationProofs.length" class="muted proof-empty">尚无有效查验凭证，请关闭弹窗后在附件区上传。</div></el-form-item>
            <el-form-item label="实际查验时间" required><el-date-picker v-model="workflow.checkedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" /></el-form-item>
            <el-form-item label="查验结果摘要"><el-input v-model="workflow.querySnapshot" type="textarea" :rows="2" maxlength="1000" placeholder="选填：票面状态、税务机关返回摘要或异常信息" /></el-form-item>
          </template>
          <template v-if="workflow.kind === 'USAGE'"><el-form-item label="进项用途"><el-select v-model="workflow.status"><el-option label="申报抵扣" value="DEDUCTION" /><el-option label="不抵扣，转回成本" value="NON_DEDUCTION" /></el-select></el-form-item><el-form-item label="税款所属期"><el-date-picker v-model="workflow.period" type="month" value-format="YYYY-MM" /></el-form-item></template>
          <template v-if="workflow.kind === 'TRANSFER_OUT'"><el-form-item label="转出所属期"><el-date-picker v-model="workflow.period" type="month" value-format="YYYY-MM" /></el-form-item></template>
          <template v-if="workflow.kind === 'RED_CONFIRMATION'"><el-alert class="workflow-alert" type="info" :closable="false" title="原蓝字票已用途确认或入账确认时须经双方确认；确认单72小时窗口届满未确认会自动失效。" /><el-form-item label="确认单状态"><el-select v-model="workflow.status"><el-option label="待对方确认" value="PENDING" /><el-option label="双方已确认" value="CONFIRMED" /><el-option label="已超72小时失效" value="EXPIRED" /></el-select></el-form-item><el-form-item label="发起方"><el-select v-model="workflow.initiator"><el-option label="开票方" value="ISSUER" /><el-option label="受票方" value="RECIPIENT" /></el-select></el-form-item><el-form-item v-if="workflow.status === 'CONFIRMED'" label="红字确认单编号" required><el-input v-model="workflow.reference" /></el-form-item></template>
          <el-form-item v-if="workflow.kind !== 'RED_CONFIRMATION'" label="税务平台凭据 / 查验参考" required><el-input v-model="workflow.reference" maxlength="128" /></el-form-item>
          <el-form-item label="说明"><el-input v-model="workflow.reason" type="textarea" :rows="3" maxlength="500" /></el-form-item>
        </el-form>
        <template #footer><el-button @click="workflow.visible = false">取消</el-button><el-button type="primary" :loading="acting" @click="submitWorkflow">确认</el-button></template>
      </el-dialog>

      <el-dialog v-model="relationVisible" title="新增分层勾稽" width="620px" destroy-on-close>
        <el-form label-position="top">
          <el-form-item label="来源单据" required><el-select v-model="relationForm.key" filterable placeholder="请选择仍有可勾稽金额的来源单据" style="width:100%" @change="selectCandidate"><el-option v-for="item in candidates" :key="candidateKey(item)" :label="`${layerName(item.relationLayer)} · ${sourceTypeName(item.sourceType)} ${item.sourceNo} · 可用 ${money(item.availableAmount)}`" :value="candidateKey(item)" /></el-select></el-form-item>
          <el-alert v-if="selectedCandidate?.taxSnapshotAvailable" type="info" :closable="false" show-icon :title="`来源冻结快照：未税 ${money(selectedCandidate.availableAmountExcludingTax)} + 税额 ${money(selectedCandidate.availableTaxAmount)} = ${money(selectedCandidate.availableAmount)}；尾差 ${money(selectedCandidate.taxRoundingDifference)}`" />
          <el-alert v-else-if="selectedCandidate" type="info" :closable="false" show-icon title="该往来或资金层没有独立税额事实，本次默认按发票价税比例分配，不会反改来源资金记录。" />
          <el-alert v-if="relationDifference" type="warning" :closable="false" show-icon :title="`票货价税差异：未税 ${signedMoney(relationDifference.net)}，税额 ${signedMoney(relationDifference.tax)}；系统会把差异写入勾稽依据，请由财务复核。`" />
          <div class="form-grid form-grid-3"><el-form-item label="未税金额" required><el-input-number v-model="relationForm.net" :min="0" :precision="2" :controls="false" /></el-form-item><el-form-item label="税额" required><el-input-number v-model="relationForm.tax" :min="0" :precision="2" :controls="false" /></el-form-item><el-form-item label="价税合计" required><el-input-number v-model="relationForm.total" :min="0.01" :precision="2" :controls="false" /></el-form-item></div>
          <el-form-item label="勾稽依据" required><el-input v-model="relationForm.basis" maxlength="500" placeholder="如：按入库单价税合计分配；经财务核对" /></el-form-item>
        </el-form>
        <template #footer><el-button @click="relationVisible = false">取消</el-button><el-button type="primary" :loading="acting" @click="addRelation">保存勾稽</el-button></template>
      </el-dialog>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type TaxInvoiceRelation, type TaxInvoiceSourceCandidate, type TaxInvoiceVerificationRecord } from '@/api/client'
import { formatDateTime, money } from '@/api/format'
import PageHeader from '@/components/PageHeader.vue'
import EvidenceAttachments from '@/components/EvidenceAttachments.vue'
import { useAuditedDocumentPrint } from '@/composables/useAuditedDocumentPrint'
import { printFileName } from '@/composables/useDocumentPrint'
import { useAppStore } from '@/stores/app'

type WorkflowKind = 'VERIFY' | 'BOOKING' | 'USAGE' | 'TRANSFER_OUT' | 'RED_CONFIRMATION'
const route = useRoute()
const id = Number(route.params.id)
const appStore = useAppStore()
const queryClient = useQueryClient()
const acting = ref(false)
const relationVisible = ref(false)
const query = useQuery({ queryKey: ['tax-invoice', id], queryFn: () => api.taxInvoice(id) })
const detail = computed(() => query.data.value)
const evidenceQuery = useQuery({ queryKey: ['evidence-attachments', 'TAX_INVOICE', id], queryFn: () => api.evidenceAttachments('TAX_INVOICE', id) })
const verificationProofs = computed(() => (evidenceQuery.data.value || []).filter((item) => item.status === 'ACTIVE' && item.evidenceType === 'VERIFICATION_PROOF'))
const candidatesQuery = useQuery({ queryKey: computed(() => ['tax-invoice-candidates', detail.value?.invoice.direction, detail.value?.invoice.partnerId, detail.value?.invoice.invoiceColor]), queryFn: () => api.taxInvoiceSourceCandidates({ direction: detail.value!.invoice.direction, partnerId: detail.value!.invoice.partnerId, invoiceColor: detail.value!.invoice.invoiceColor }), enabled: computed(() => relationVisible.value && Boolean(detail.value)) })
const candidates = computed(() => candidatesQuery.data.value || [])
const workflow = reactive({ visible: false, kind: 'VERIFY' as WorkflowKind, status: 'VERIFIED', period: currentPeriod(), reference: '', reason: '', initiator: 'ISSUER' as 'ISSUER' | 'RECIPIENT', verificationSource: 'TAX_DIGITAL_ACCOUNT' as TaxInvoiceVerificationRecord['verificationSource'], proofAttachmentId: 0, checkedAt: localDateTime(), querySnapshot: '' })
const relationForm = reactive({ key: '', net: 0, tax: 0, total: 0, basis: '' })
const selectedCandidate = computed(() => candidates.value.find((candidate) => candidateKey(candidate) === relationForm.key))
const relationDifference = computed(() => {
  const item = selectedCandidate.value
  if (!item?.taxSnapshotAvailable || !item.availableAmount || !relationForm.total) return null
  const expectedNet = round2(item.availableAmountExcludingTax * relationForm.total / item.availableAmount)
  const net = round2(relationForm.net - expectedNet)
  const tax = round2(relationForm.tax - round2(relationForm.total - expectedNet))
  return Math.abs(net) >= 0.01 || Math.abs(tax) >= 0.01 ? { net, tax } : null
})
const backTo = computed(() => detail.value?.invoice.direction === 'INPUT' ? '/tax-invoices/input' : '/tax-invoices/output')
const canConfirmUsage = computed(() => detail.value?.invoice.direction === 'INPUT' && detail.value?.invoice.invoiceColor === 'BLUE' && detail.value?.invoice.status === 'CONFIRMED' && detail.value?.invoice.usageStatus === 'UNCONFIRMED')
const canTransferOut = computed(() => detail.value?.invoice.direction === 'INPUT' && detail.value?.invoice.status === 'CONFIRMED' && detail.value?.invoice.usageStatus === 'DEDUCTION')
const reconciliationCards = computed(() => {
  const invoice = detail.value?.invoice
  if (!invoice) return []
  return [
    { label: '票货', matched: invoice.goodsMatchedAmount, target: invoice.totalAmount, complete: invoice.goodsMatchedAmount >= invoice.totalAmount, note: '入库 / 出库 / 退货' },
    { label: '票款', matched: invoice.settlementMatchedAmount, target: invoice.totalAmount, complete: invoice.settlementMatchedAmount >= invoice.totalAmount, note: '应收 / 应付' },
    { label: '资金参考', matched: invoice.fundsMatchedAmount, target: invoice.totalAmount, complete: invoice.fundsMatchedAmount >= invoice.totalAmount, note: '收付款不是开票前提' },
    { label: '票账', matched: invoice.accountingMatchedTax, target: invoice.taxAmount, complete: invoice.taxAmount === 0 || invoice.accountingMatchedTax >= invoice.taxAmount, note: '税额重分类凭证' },
  ]
})
const { printTime, snapshotHash, printing, printAndRecord } = useAuditedDocumentPrint('TAX_INVOICE', () => id)
const workflowTitle = computed(() => ({ VERIFY: '登记发票查验', BOOKING: '确认税务入账标识', USAGE: '确认进项用途', TRANSFER_OUT: '进项税额转出', RED_CONFIRMATION: '维护红字确认单' } as Record<WorkflowKind, string>)[workflow.kind])

async function printReconciliationSheet() {
  if (!detail.value) return
  await printAndRecord(
    printFileName(detail.value.invoice.partnerName, '税票勾稽表', detail.value.invoice.internalNo),
    detail.value,
    'copper-tax-invoice-reconciliation-print/v1',
  )
}

async function refresh() { await Promise.all([query.refetch(), queryClient.invalidateQueries({ queryKey: ['tax-invoices'] }), queryClient.invalidateQueries({ queryKey: ['tax-invoice-dashboard'] }), queryClient.invalidateQueries({ queryKey: ['accounting-vouchers'] })]) }
async function run(action: () => Promise<unknown>, message: string) { acting.value = true; try { await action(); ElMessage.success(message); await refresh() } finally { acting.value = false } }
async function confirmInvoice() { await ElMessageBox.confirm('确认后票面内容冻结，并按税额生成会计凭证草稿。确认继续？', '确认发票', { type: 'warning' }); await run(() => api.confirmTaxInvoice(id), '发票已确认') }
async function voidInvoice() { const { value } = await ElMessageBox.prompt('已用途确认或入账确认的进项票不能直接作废。请填写作废原因', '作废发票', { inputValidator: (text) => Boolean(String(text).trim()) || '必须填写原因' }); await run(() => api.voidTaxInvoice(id, value), '发票已作废') }
function openWorkflow(kind: WorkflowKind) { workflow.kind = kind; workflow.visible = true; workflow.reference = ''; workflow.reason = ''; workflow.period = currentPeriod(); workflow.initiator = 'ISSUER'; workflow.verificationSource = 'TAX_DIGITAL_ACCOUNT'; workflow.proofAttachmentId = verificationProofs.value[0]?.id || 0; workflow.checkedAt = localDateTime(); workflow.querySnapshot = ''; workflow.status = kind === 'USAGE' ? 'DEDUCTION' : kind === 'RED_CONFIRMATION' ? 'PENDING' : 'VERIFIED' }
async function submitWorkflow() {
  if (workflow.kind !== 'RED_CONFIRMATION' && !workflow.reference.trim()) { ElMessage.warning('请填写税务平台凭据或查验参考'); return }
  if (['USAGE', 'TRANSFER_OUT'].includes(workflow.kind) && !workflow.period) { ElMessage.warning('请选择税款所属期'); return }
  if (workflow.kind === 'TRANSFER_OUT' && !workflow.reason.trim()) { ElMessage.warning('进项转出必须填写原因'); return }
  if (workflow.kind === 'RED_CONFIRMATION' && workflow.status === 'CONFIRMED' && !workflow.reference.trim()) { ElMessage.warning('请填写红字确认单编号'); return }
  if (workflow.kind === 'VERIFY' && (!workflow.verificationSource || !workflow.proofAttachmentId || !workflow.checkedAt)) { ElMessage.warning('请选择查验来源、查验凭证附件和实际查验时间'); return }
  const actions: Record<WorkflowKind, () => Promise<unknown>> = {
    VERIFY: () => api.verifyTaxInvoice(id, { verificationStatus: workflow.status as 'VERIFIED' | 'FAILED', verificationReference: workflow.reference, verificationSource: workflow.verificationSource, proofAttachmentId: workflow.proofAttachmentId, checkedAt: workflow.checkedAt, querySnapshot: workflow.querySnapshot, reason: workflow.reason }),
    BOOKING: () => api.confirmTaxInvoiceBooking(id, { evidenceReference: workflow.reference, reason: workflow.reason }),
    USAGE: () => api.confirmTaxInvoiceUsage(id, { usageStatus: workflow.status as 'DEDUCTION' | 'NON_DEDUCTION', taxPeriod: workflow.period, evidenceReference: workflow.reference, reason: workflow.reason }),
    TRANSFER_OUT: () => api.transferOutTaxInvoice(id, { taxPeriod: workflow.period, evidenceReference: workflow.reference, reason: workflow.reason }),
    RED_CONFIRMATION: () => api.updateRedTaxInvoiceConfirmation(id, { confirmationStatus: workflow.status as 'PENDING' | 'CONFIRMED' | 'EXPIRED', confirmationNo: workflow.reference, initiator: workflow.initiator, reason: workflow.reason }),
  }
  await run(actions[workflow.kind], '税务状态已登记'); workflow.visible = false
}
function openRelation() { relationVisible.value = true; Object.assign(relationForm, { key: '', net: 0, tax: 0, total: 0, basis: '' }) }
function candidateKey(item: TaxInvoiceSourceCandidate) { return `${item.sourceType}:${item.sourceId}` }
function selectCandidate() { const item = selectedCandidate.value; if (!item || !detail.value) return; const total = Math.min(item.availableAmount, detail.value.invoice.totalAmount); const invoiceRatio = detail.value.invoice.totalAmount ? detail.value.invoice.taxAmount / detail.value.invoice.totalAmount : 0; const net = item.taxSnapshotAvailable && item.availableAmount ? round2(item.availableAmountExcludingTax * total / item.availableAmount) : round2(total * (1 - invoiceRatio)); relationForm.net = net; relationForm.tax = round2(total - net); relationForm.total = round2(total); relationForm.basis = item.taxSnapshotAvailable ? `按${sourceTypeName(item.sourceType)} ${item.sourceNo} 冻结价税快照分配；票货差异经财务复核` : `按本发票价税比例分配至${sourceTypeName(item.sourceType)} ${item.sourceNo}；该层不承载独立税额事实` }
async function addRelation() { const candidate = candidates.value.find((item) => candidateKey(item) === relationForm.key); if (!candidate || !relationForm.basis.trim()) { ElMessage.warning('请选择来源单据并填写勾稽依据'); return } if (round2(relationForm.net + relationForm.tax) !== round2(relationForm.total)) { ElMessage.warning('未税金额加税额必须等于价税合计'); return } await run(() => api.addTaxInvoiceRelation(id, { sourceType: candidate.sourceType, sourceId: candidate.sourceId, amountExcludingTax: relationForm.net, taxAmount: relationForm.tax, totalAmount: relationForm.total, matchingBasis: relationForm.basis }), '勾稽关系已保存'); relationVisible.value = false }
async function reverseRelation(relationId: number) { const { value } = await ElMessageBox.prompt('解除只会失效该勾稽关系，不会删除历史。请填写原因', '解除勾稽', { inputValidator: (text) => Boolean(String(text).trim()) || '必须填写原因' }); await run(() => api.reverseTaxInvoiceRelation(id, relationId, value), '勾稽关系已解除') }
function sourcePath(row: TaxInvoiceRelation) { return ({ PURCHASE_ORDER: `/purchase-orders/${row.sourceId}`, INBOUND: `/inbounds/${row.sourceId}`, PURCHASE_RETURN: `/inventory/purchase-returns/${row.sourceId}`, SALES_ORDER: `/sales-orders/${row.sourceId}`, OUTBOUND: `/outbounds/${row.sourceId}`, SALES_RETURN: `/outbounds`, PAYMENT: '/payments', SUPPLIER_PAYMENT: '/supplier-payments', RECEIVABLE: '/partner-accounts', PAYABLE: '/payables', TREASURY_DOCUMENT: `/treasury/documents/${row.sourceId}`, TREASURY_ACCRUAL: `/treasury/documents/${row.sourceId}`, TREASURY_JOURNAL: '/treasury/journal', ACCOUNTING_VOUCHER: `/accounting/vouchers/${row.sourceId}` } as Record<string, string>)[row.sourceType] }
function directionName(value: string) { return value === 'INPUT' ? '进项' : '销项' }
function typeName(value: string) { return ({ DIGITAL_SPECIAL: '数电专票', DIGITAL_NORMAL: '数电普票', VAT_SPECIAL: '增值税专票', VAT_NORMAL: '增值税普票', OTHER: '其他' } as Record<string, string>)[value] || value }
function statusName(value: string) { return ({ DRAFT: '草稿', CONFIRMED: '已确认', VOIDED: '已作废' } as Record<string, string>)[value] || value }
function statusType(value: string) { return value === 'CONFIRMED' ? 'success' : value === 'VOIDED' ? 'info' : 'primary' }
function verifyName(value: string) { return ({ UNVERIFIED: '待查验', VERIFIED: '查验通过', FAILED: '查验异常' } as Record<string, string>)[value] || value }
function bookingName(value: string) { return value === 'CONFIRMED' ? '已确认' : '待确认' }
function usageName(value: string) { return ({ UNCONFIRMED: '待确认', DEDUCTION: '申报抵扣', NON_DEDUCTION: '不抵扣', TRANSFERRED_OUT: '已转出', NOT_APPLICABLE: '不适用' } as Record<string, string>)[value] || value }
function redConfirmationName(value: string) { return ({ NOT_REQUIRED: '无需确认', PENDING: '待对方确认', CONFIRMED: '双方已确认', EXPIRED: '已超时失效' } as Record<string, string>)[value] || value }
function layerName(value: string) { return ({ CONTRACT: '票合同', GOODS: '票货', SETTLEMENT: '票款', FUNDS: '资金参考', ACCOUNTING: '票账' } as Record<string, string>)[value] || value }
function sourceTypeName(value: string) { return ({ PURCHASE_ORDER: '采购订单', INBOUND: '采购入库', PURCHASE_RETURN: '采购退货', PAYABLE: '应付流水', SUPPLIER_PAYMENT: '供应商付款', SALES_ORDER: '销售订单', OUTBOUND: '销售出库', SALES_RETURN: '销售退货', RECEIVABLE: '应收流水', PAYMENT: '客户回款', TREASURY_DOCUMENT: '费用/其他收支业务事实', TREASURY_ACCRUAL: '其他应收/应付', TREASURY_JOURNAL: '费用出纳资金', ACCOUNTING_VOUCHER: '会计凭证' } as Record<string, string>)[value] || value }
function eventName(value: string) { return ({ CREATE: '新增草稿', UPDATE: '修改草稿', CONFIRM: '确认发票', VOID: '作废发票', VERIFY: '登记查验', BOOKING_CONFIRM: '确认入账标识', USAGE_CONFIRM: '确认进项用途', TRANSFER_OUT: '进项税额转出', RED_CONFIRMATION: '红字确认单', RELATION_ADD: '新增勾稽', RELATION_REVERSE: '解除勾稽' } as Record<string, string>)[value] || value }
function currentPeriod() { const now = new Date(); return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}` }
function localDateTime() { const now = new Date(); const pad = (value: number) => String(value).padStart(2, '0'); return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}T${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}` }
function verificationSourceName(value: string) { return ({ TAX_DIGITAL_ACCOUNT: '税务数字账户', NATIONAL_PLATFORM: '全国发票查验平台', LOCAL_TAX_AUTHORITY: '主管税务机关', MANUAL_REVIEW: '人工复核' } as Record<string, string>)[value] || value }
function shortHash(value: string) { return value ? `${value.slice(0, 12)}…${value.slice(-8)}` : '-' }
function round2(value: number) { return Math.round((value + Number.EPSILON) * 100) / 100 }
function signedMoney(value: number) { return `${value > 0 ? '+' : ''}${money(value)}` }
</script>

<style scoped>
.detail-panel { margin-top: 14px; padding: 16px; }
.tax-boundary-note { margin: 10px 2px 0; }
.workflow-alert { margin-bottom: 14px; }
.section-table { margin-top: 14px; }
.invoice-total { display: flex; justify-content: flex-end; gap: 28px; padding: 14px 8px 4px; }
.reconciliation-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-top: 14px; }
.reconciliation-card { display: flex; flex-direction: column; gap: 5px; padding: 12px; border: 1px solid #dfe6eb; border-radius: 8px; }
.reconciliation-card.is-complete { background: #f1faf4; border-color: #badfc5; }
.reconciliation-card.is-pending { background: #fff8ec; border-color: #eed29d; }
.reconciliation-card small { color: #73818c; }
.invoice-timeline { margin-top: 18px; }
.proof-empty { margin-top: 6px; font-size: 12px; }
.hash { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
@media (max-width: 1050px) { .reconciliation-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
