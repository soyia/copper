<template>
  <main class="public-confirm-page">
    <section class="confirm-sheet" v-loading="query.isFetching.value">
      <div class="brand">Copper · 外部业务确认</div>
      <el-alert v-if="query.isError.value" type="error" :closable="false" show-icon title="确认链接不存在、已到期或已撤销" />
      <template v-else-if="packet">
        <header>
          <div><h1>{{ packet.snapshot.title }}</h1><p>{{ packet.sourceNo }} · 截止 {{ packet.asOfDate }}</p></div>
          <el-tag :type="statusType(packet.status)">{{ statusName(packet.status) }}</el-tag>
        </header>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="往来单位">{{ packet.partnerName }}</el-descriptions-item>
          <el-descriptions-item label="确认编号">{{ packet.packetNo }}</el-descriptions-item>
          <el-descriptions-item label="内容摘要" :span="2"><span class="hash">{{ packet.snapshotHash }}</span></el-descriptions-item>
        </el-descriptions>

        <template v-if="packet.packetType === 'CUSTOMER_QUOTE'">
          <div class="totals">报价金额 <strong>{{ money(packet.snapshot.totalAmount) }}</strong> · 有效期 {{ packet.snapshot.validUntil }}</div>
          <el-table :data="packet.snapshot.items || []" border size="small">
            <el-table-column prop="product_name" label="商品" min-width="170" />
            <el-table-column prop="product_spec" label="规格" min-width="160" />
            <el-table-column label="数量" width="130"><template #default="{ row }">{{ row.requested_quantity }} {{ row.requested_unit }}</template></el-table-column>
            <el-table-column label="单价" width="120" align="right"><template #default="{ row }">{{ money(row.unit_price) }}</template></el-table-column>
            <el-table-column label="金额" width="130" align="right"><template #default="{ row }">{{ money(row.total_amount) }}</template></el-table-column>
          </el-table>
        </template>

        <template v-else-if="packet.packetType === 'CUSTOMER_STATEMENT'">
          <div class="totals">累计应收 {{ money(packet.snapshot.totalAmount) }} · 已收/调整 {{ money(packet.snapshot.settledAmount) }} · <strong>未收 {{ money(packet.snapshot.outstandingAmount) }}</strong></div>
          <el-table :data="packet.snapshot.lines || []" border size="small">
            <el-table-column prop="source_no" label="单据号" min-width="170" />
            <el-table-column prop="business_date" label="业务日期" width="110" />
            <el-table-column prop="due_date" label="到期日" width="110" />
            <el-table-column label="应收" width="120" align="right"><template #default="{ row }">{{ money(row.total_amount) }}</template></el-table-column>
            <el-table-column label="已收/调整" width="120" align="right"><template #default="{ row }">{{ money(row.settled_amount) }}</template></el-table-column>
            <el-table-column label="未收" width="120" align="right"><template #default="{ row }"><strong>{{ money(row.outstanding_amount) }}</strong></template></el-table-column>
          </el-table>
        </template>

        <template v-else>
          <div class="totals">销售订单 {{ packet.snapshot.salesOrderNo }} · 金额 {{ money(packet.snapshot.totalAmount) }}</div>
          <el-table :data="packet.snapshot.deliveries || []" border size="small">
            <el-table-column prop="outbound_no" label="出库单" min-width="170" />
            <el-table-column prop="business_date" label="发货日期" width="110" />
            <el-table-column prop="total_quantity" label="数量" width="100" align="right" />
            <el-table-column prop="carrier_name" label="承运/配送" min-width="130" />
            <el-table-column prop="tracking_no" label="物流单号" min-width="140" />
            <el-table-column label="交付状态" width="110"><template #default="{ row }">{{ deliveryStatus(row.delivery_status) }}</template></el-table-column>
          </el-table>
        </template>

        <el-alert class="boundary" type="info" :closable="false" show-icon title="本页确认只表示对上述冻结内容的业务确认，不代表已经收款、付款、核销、开票或完成会计处理，也不等同于可靠电子签名。" />
        <section v-if="packet.confirmation" class="submitted">
          <h3>{{ packet.confirmation.decision === 'CONFIRMED' ? '已确认内容无误' : '已提出差异' }}</h3>
          <p>{{ packet.confirmation.confirmerName }} · {{ packet.confirmation.confirmedAt }}</p>
          <p v-if="packet.confirmation.comment">{{ packet.confirmation.comment }}</p>
        </section>
        <el-form v-else class="confirm-form" label-width="90px">
          <el-form-item label="确认人" required><el-input v-model="form.confirmerName" maxlength="128" placeholder="请输入真实姓名" /></el-form-item>
          <el-form-item label="手机尾号"><el-input v-model="form.contactTail" maxlength="4" placeholder="可选，用于内部核对" /></el-form-item>
          <el-form-item label="确认结果" required><el-radio-group v-model="form.decision"><el-radio-button value="CONFIRMED">内容无误</el-radio-button><el-radio-button value="DISPUTED">存在差异</el-radio-button></el-radio-group></el-form-item>
          <el-form-item label="说明" :required="form.decision === 'DISPUTED'"><el-input v-model="form.comment" type="textarea" :rows="3" maxlength="1000" show-word-limit :placeholder="form.decision === 'DISPUTED' ? '请说明具体单据、金额或交付差异' : '可选留言'" /></el-form-item>
          <el-form-item><el-button type="primary" :loading="saving" @click="submit">提交确认</el-button></el-form-item>
        </el-form>
      </template>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import { money } from '@/api/format'
const route = useRoute()
const token = String(route.params.token || '')
const query = useQuery({ queryKey: ['public-confirmation', token], queryFn: () => api.publicConfirmation(token), retry: false })
const packet = computed(() => query.data.value)
const saving = ref(false)
const form = reactive({ confirmerName: '', contactTail: '', decision: 'CONFIRMED' as 'CONFIRMED' | 'DISPUTED', comment: '' })
async function submit() {
  if (!form.confirmerName.trim()) return ElMessage.warning('请填写确认人姓名')
  if (form.decision === 'DISPUTED' && form.comment.trim().length < 2) return ElMessage.warning('请说明具体差异')
  const ok = await ElMessageBox.confirm('提交后不能覆盖原确认记录。本次确认不会执行收款、核销或会计处理。', '确认提交', { type: 'warning', confirmButtonText: '提交确认', cancelButtonText: '返回检查' }).then(() => true).catch(() => false)
  if (!ok) return
  saving.value = true
  try { await api.confirmPublicPacket(token, { ...form }); ElMessage.success('确认结果已提交'); await query.refetch() } finally { saving.value = false }
}
function statusName(value: string) { return ({ ACTIVE: '待确认', CONFIRMED: '内容无误', DISPUTED: '存在差异' } as Record<string,string>)[value] || value }
function statusType(value: string) { return value === 'CONFIRMED' ? 'success' : value === 'DISPUTED' ? 'danger' : 'warning' }
function deliveryStatus(value: string) { return ({ PENDING: '待发', DISPATCHED: '已发出', SIGNED: '已签收', EXCEPTION: '有异常' } as Record<string,string>)[value] || value }
</script>

<style scoped>
.public-confirm-page{min-height:100vh;padding:28px 16px;background:#f3f6f9}.confirm-sheet{max-width:1050px;min-height:420px;margin:auto;padding:24px;border:1px solid #dfe5ec;border-radius:12px;background:#fff;box-shadow:0 12px 36px rgb(31 45 61 / 8%)}.brand{margin-bottom:20px;color:#c85324;font-size:20px;font-weight:800}header{display:flex;justify-content:space-between;gap:16px;margin-bottom:18px}h1{margin:0;font-size:25px}header p{margin:6px 0 0;color:#6b7785}.hash{font-family:monospace;font-size:11px;word-break:break-all}.totals{margin:16px 0;padding:12px 14px;border-radius:8px;background:#f7f9fb}.totals strong{color:#c85324;font-size:18px}.boundary{margin-top:18px}.confirm-form,.submitted{margin-top:20px;padding:18px;border:1px solid #e3e8ee;border-radius:10px;background:#fafbfc}.submitted h3{margin:0;color:#31906a}@media(max-width:640px){.public-confirm-page{padding:0}.confirm-sheet{min-height:100vh;padding:16px;border-radius:0}header{align-items:flex-start;flex-direction:column}}
</style>
