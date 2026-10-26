<template>
  <div v-loading="loading">
    <PageHeader v-if="detail" :title="`${detail.quote.quoteNo}-V${detail.quote.revisionNo} · ${detail.quote.title || detail.quote.partnerName}`" back-to="/crm/quotes">
      <el-button v-if="detail.quote.status === 'DRAFT'" @click="router.push(`/crm/quotes/${detail.quote.id}/edit`)">编辑草稿</el-button>
      <el-button v-if="detail.quote.status === 'DRAFT'" type="primary" @click="activate">报价生效</el-button>
      <el-button v-if="detail.quote.status === 'ACTIVE'" @click="revise">创建修订版</el-button>
      <el-button v-if="detail.quote.status === 'ACTIVE'" type="danger" plain @click="decide('REJECTED')">客户拒绝</el-button>
      <el-button v-if="detail.quote.status === 'ACTIVE'" type="success" @click="decide('ACCEPTED')">客户接受</el-button>
      <el-button v-if="['DRAFT','ACTIVE','ACCEPTED'].includes(detail.quote.status) && !detail.quote.salesOrderId" type="warning" plain @click="cancel">作废</el-button>
      <el-button v-if="detail.quote.status === 'ACCEPTED' && !detail.quote.salesOrderId" type="primary" @click="convert">转销售订单草稿</el-button>
      <el-button v-if="detail.quote.salesOrderId" type="primary" @click="router.push(`/sales-orders/${detail.quote.salesOrderId}`)">查看销售订单 {{ detail.quote.salesOrderNo }}</el-button>
    </PageHeader>
    <template v-if="detail">
      <section class="panel">
        <el-alert v-if="detail.quote.status === 'ACCEPTED' && !detail.quote.salesOrderId" title="客户已接受报价，但尚未创建销售订单" description="CRM 不会自动占库存或产生应收。确认需要履约时，再由有销售权限的用户转换成销售订单草稿。" type="success" :closable="false" show-icon />
        <el-descriptions :column="3" border class="quote-descriptions">
          <el-descriptions-item label="状态"><el-tag :type="crmStatusType(detail.quote.status)">{{ crmStatusName(detail.quote.status) }}</el-tag></el-descriptions-item><el-descriptions-item label="客户">{{ detail.quote.partnerName }}</el-descriptions-item><el-descriptions-item label="商机"><el-button v-if="detail.quote.opportunityId" link type="primary" @click="router.push(`/crm/opportunities/${detail.quote.opportunityId}`)">{{ detail.quote.opportunityNo }}</el-button><span v-else>-</span></el-descriptions-item><el-descriptions-item label="报价日期">{{ detail.quote.quoteDate }}</el-descriptions-item><el-descriptions-item label="有效期至">{{ detail.quote.validUntil }}</el-descriptions-item><el-descriptions-item label="预计交付">{{ detail.quote.deliveryPlanDate || '-' }}</el-descriptions-item><el-descriptions-item label="价格口径">{{ detail.quote.priceIncludesTax ? '含税价' : '未税价' }}</el-descriptions-item><el-descriptions-item label="税率">{{ detail.quote.taxRate }}%</el-descriptions-item><el-descriptions-item label="付款条件">{{ paymentTermName(detail.quote.paymentTerm) }}</el-descriptions-item><el-descriptions-item label="税务快照" :span="3">{{ detail.quote.taxRuleSnapshot || '-' }}</el-descriptions-item><el-descriptions-item v-if="detail.quote.decisionReason" label="决策原因" :span="3">{{ detail.quote.decisionReason }}</el-descriptions-item><el-descriptions-item label="备注" :span="3">{{ detail.quote.memo || '-' }}</el-descriptions-item>
        </el-descriptions>
      </section>
      <section class="panel items-panel"><div class="panel-title">报价商品与冻结快照</div><el-table :data="detail.items"><el-table-column prop="productSkuSnapshot" label="SKU" width="150" /><el-table-column prop="productNameSnapshot" label="商品" min-width="160" /><el-table-column prop="productSpecSnapshot" label="规格" min-width="220" show-overflow-tooltip /><el-table-column label="报价数量" width="135"><template #default="{ row }">{{ row.requestedQuantity }} {{ row.requestedUnit }}</template></el-table-column><el-table-column label="基础数量" width="135"><template #default="{ row }">{{ row.baseQuantity }} {{ row.baseUnit }}</template></el-table-column><el-table-column label="计价数量" width="135"><template #default="{ row }">{{ row.pricingQuantity }} {{ row.priceUnit }}</template></el-table-column><el-table-column label="单价 / 来源" width="170" align="right"><template #default="{ row }"><div class="price-audit-cell"><span>{{ row.unitPrice }}</span><span class="price-audit-source" :title="row.priceSnapshotJson || row.priceSourceName">{{ row.priceSourceName || '手工录入' }}</span></div></template></el-table-column><el-table-column label="价税合计" width="130" align="right"><template #default="{ row }">¥{{ moneyText(row.totalAmount) }}</template></el-table-column></el-table><div class="totals"><span>原价 ¥{{ moneyText(detail.quote.originalAmount) }}</span><span>折扣 -¥{{ moneyText(detail.quote.discountAmount) }}</span><span>未税 ¥{{ moneyText(detail.quote.amountExcludingTax) }}</span><span>税额 ¥{{ moneyText(detail.quote.taxAmount) }}</span><strong>价税合计 ¥{{ moneyText(detail.quote.totalAmount) }}</strong></div></section>
      <ExternalConfirmationPanel v-if="['ACTIVE','ACCEPTED'].includes(detail.quote.status)" packet-type="CUSTOMER_QUOTE" :source-id="detail.quote.id" />
      <CrmActivityPlanPanel target-type="QUOTE" :target-id="detail.quote.id" :partner-id="detail.quote.partnerId" :activities="detail.activities" :plans="detail.plans" @refresh="load" />
      <section class="panel audit-panel"><div class="panel-title">报价版本与审计历史</div><div v-if="detail.quote.previousQuoteId" class="version-link">来源版本：<el-button link type="primary" @click="router.push(`/crm/quotes/${detail.quote.previousQuoteId}`)">查看上一版</el-button></div><el-timeline><el-timeline-item v-for="item in detail.events" :key="item.id" :timestamp="dateTimeText(item.occurredAt)" placement="top"><strong>{{ crmEventName(item.eventType) }}</strong><div class="muted">{{ item.actorName }}</div></el-timeline-item></el-timeline></section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type CrmQuoteDetail } from '@/api/client'
import PageHeader from '@/components/PageHeader.vue'
import CrmActivityPlanPanel from '@/components/CrmActivityPlanPanel.vue'
import ExternalConfirmationPanel from '@/components/ExternalConfirmationPanel.vue'
import { crmEventName, crmStatusName, crmStatusType, dateTimeText, moneyText } from '@/utils/crm'

const route = useRoute(); const router = useRouter(); const detail = ref<CrmQuoteDetail>(); const loading = ref(false); const id = () => Number(route.params.id)
const isActiveRoute = () => route.path === `/crm/quotes/${id()}`
onMounted(load); watch(() => route.params.id, load)
async function load() { if (!id() || !isActiveRoute()) return; loading.value = true; try { detail.value = await api.crmQuote(id()) } finally { loading.value = false } }
async function activate() { await api.activateCrmQuote(id()); ElMessage.success('报价已生效，可提交客户确认'); load() }
async function reasonPrompt(title: string, message: string) { return ElMessageBox.prompt(message, title, { inputType: 'textarea', inputValidator: (value) => Boolean(value.trim()) || '请填写原因' }).then((result) => result.value).catch(() => '') }
async function revise() { const reason = await reasonPrompt('创建报价修订版', '原报价会保留为“已修订”，新版本从草稿开始，请填写修订原因。'); if (!reason) return; const newId = await api.reviseCrmQuote(id(), { reasonCode: 'OTHER', reason }); ElMessage.success('修订版草稿已创建'); router.push(`/crm/quotes/${newId}/edit`) }
async function decide(status: 'ACCEPTED' | 'REJECTED') { const reason = await reasonPrompt(status === 'ACCEPTED' ? '客户接受报价' : '客户拒绝报价', `请填写${status === 'ACCEPTED' ? '成交' : '拒绝'}原因，便于复盘。`); if (!reason) return; if (status === 'ACCEPTED') await api.acceptCrmQuote(id(), { reasonCode: 'OTHER', reason }); else await api.rejectCrmQuote(id(), { reasonCode: 'OTHER', reason }); ElMessage.success(status === 'ACCEPTED' ? '已记录客户接受' : '已记录客户拒绝'); load() }
async function cancel() { const reason = await reasonPrompt('作废报价', '作废不会删除报价；客户已接受但尚未转订单时也可按原因终止。'); if (!reason) return; await api.cancelCrmQuote(id(), { reasonCode: 'OTHER', reason }); ElMessage.success('报价已作废，历史已保留'); load() }
async function convert() { const confirmed = await ElMessageBox.confirm('将按报价冻结的商品、包装、金额和付款条件创建销售订单草稿；不会自动确认销售、占库存或产生应收。', '转销售订单草稿', { type: 'warning' }).then(() => true).catch(() => false); if (!confirmed) return; const result = await api.convertCrmQuote(id()); ElMessage.success(`已创建销售订单草稿 ${result.salesOrderNo}`); router.push(`/sales-orders/${result.salesOrderId}`) }
function paymentTermName(value: string) { return ({ POSTPAID: '账期 / 后付', PREPAYMENT_REQUIRED: '要求预付款', FULL_PREPAYMENT: '全额预付' } as Record<string, string>)[value] || value }
</script>

<style scoped>
.quote-descriptions{margin-top:12px}.items-panel,.audit-panel{margin-top:16px}.items-panel .panel-title{margin-bottom:12px}.totals{display:flex;justify-content:flex-end;gap:20px;align-items:center;margin-top:14px}.totals strong{font-size:18px;color:var(--el-color-primary)}.version-link{margin:10px 0}.price-audit-cell{display:flex;flex-direction:column;min-width:0;line-height:1.35}.price-audit-source{overflow:hidden;color:var(--el-text-color-secondary);font-size:12px;text-overflow:ellipsis;white-space:nowrap}
</style>
