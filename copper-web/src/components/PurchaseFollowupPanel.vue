<template>
  <section class="panel followup-panel">
    <div class="panel-header responsive-header">
      <div>
        <div class="panel-title">采购跟催</div>
        <div class="muted">供应商发出只是履约信息，不代表已经入库、形成库存或完成付款。</div>
      </div>
      <el-dropdown v-if="canManage && summary && !['DRAFT','CANCELLED','COMPLETED'].includes(summary.orderStatus)" @command="openAction">
        <el-button type="primary">登记跟催<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="SUPPLIER_DISPATCHED">供应商已发</el-dropdown-item>
            <el-dropdown-item command="ETA_CHANGED">修改预计到货</el-dropdown-item>
            <el-dropdown-item command="DELAYED">登记延误</el-dropdown-item>
            <el-dropdown-item command="EXCEPTION">登记异常</el-dropdown-item>
            <el-dropdown-item v-if="summary.openException" command="RESOLVED">异常已解决</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
    <div v-if="summary" class="followup-summary">
      <div><span>当前状态</span><strong>{{ statusName(summary.fulfillmentStatus) }}</strong></div>
      <div><span>供应商已发</span><strong>{{ quantity(summary.dispatchedQuantity) }}</strong></div>
      <div><span>运输途中</span><strong>{{ quantity(summary.inTransitQuantity) }}</strong></div>
      <div><span>尚未发出</span><strong>{{ quantity(summary.remainingToDispatch) }}</strong></div>
      <div :class="{ danger: summary.delayDays > 0 }"><span>最新预计到货</span><strong>{{ summary.latestExpectedArrivalDate || '-' }}</strong><small v-if="summary.delayDays">已延误 {{ summary.delayDays }} 天</small></div>
    </div>
    <el-alert v-if="summary?.openException" type="error" show-icon :closable="false" :title="`未解决异常：${summary.latestException}`" />
    <el-table v-if="summary" :data="summary.items" border size="small" class="data-table">
      <el-table-column label="商品" min-width="180"><template #default="{ row }"><strong>{{ row.productName }}</strong><div class="muted">{{ row.productSku }}</div></template></el-table-column>
      <el-table-column label="采购" width="110" align="right"><template #default="{ row }">{{ quantity(row.orderedQuantity) }} {{ row.unit }}</template></el-table-column>
      <el-table-column label="已发" width="110" align="right"><template #default="{ row }">{{ quantity(row.dispatchedQuantity) }}</template></el-table-column>
      <el-table-column label="途中" width="110" align="right"><template #default="{ row }">{{ quantity(row.inTransitQuantity) }}</template></el-table-column>
      <el-table-column label="已入库" width="110" align="right"><template #default="{ row }">{{ quantity(row.receivedQuantity) }}</template></el-table-column>
      <el-table-column label="未发" width="110" align="right"><template #default="{ row }">{{ quantity(row.remainingToDispatch) }}</template></el-table-column>
    </el-table>
    <el-timeline v-if="summary?.events.length" class="followup-timeline">
      <el-timeline-item v-for="event in [...summary.events].reverse()" :key="event.id" :timestamp="`${event.occurredOn} · ${event.operatorName}`" placement="top">
        <strong>{{ eventName(event.eventType) }}</strong>
        <span v-if="event.expectedArrivalDate"> · 预计 {{ event.expectedArrivalDate }}</span>
        <div v-if="event.items.length" class="muted">{{ event.items.map(item => `${item.productName} ${quantity(item.dispatchedQuantity)}${item.unit}`).join('；') }}</div>
        <div v-if="event.carrierName || event.trackingNo" class="muted">{{ event.carrierName }} {{ event.trackingNo }}</div>
        <div v-if="event.reason">{{ event.reason }}</div>
      </el-timeline-item>
    </el-timeline>
    <el-empty v-else-if="!loading" description="尚未登记供应商履约信息" :image-size="55" />

    <el-dialog v-model="dialogVisible" :title="eventName(form.eventType)" width="720px" :close-on-click-modal="false">
      <el-form label-width="105px">
        <el-form-item label="发生日期" required><el-date-picker v-model="form.occurredOn" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item v-if="['SUPPLIER_DISPATCHED','ETA_CHANGED','DELAYED'].includes(form.eventType)" label="预计到货" required><el-date-picker v-model="form.expectedArrivalDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
        <template v-if="form.eventType === 'SUPPLIER_DISPATCHED'">
          <el-form-item label="物流/承运"><el-input v-model="form.carrierName" maxlength="128" /></el-form-item>
          <el-form-item label="物流单号"><el-input v-model="form.trackingNo" maxlength="128" /></el-form-item>
          <el-table :data="dispatchItems" border size="small">
            <el-table-column label="商品" min-width="180"><template #default="{ row }">{{ row.productName }}<div class="muted">{{ row.productSku }}</div></template></el-table-column>
            <el-table-column label="尚未发出" width="120" align="right"><template #default="{ row }">{{ quantity(row.remainingToDispatch) }}</template></el-table-column>
            <el-table-column label="本次发出" width="180"><template #default="{ row }"><el-input-number v-model="row.thisQuantity" :min="0" :max="row.remainingToDispatch" :precision="6" :controls="false" /></template></el-table-column>
          </el-table>
        </template>
        <el-form-item v-if="form.eventType === 'EXCEPTION'" label="异常类型"><el-select v-model="form.exceptionType"><el-option label="少发" value="SHORT_SHIPMENT" /><el-option label="破损" value="DAMAGED" /><el-option label="规格不符" value="WRONG_SPEC" /><el-option label="物流异常" value="LOGISTICS" /><el-option label="其他" value="OTHER" /></el-select></el-form-item>
        <el-form-item v-if="['DELAYED','EXCEPTION','RESOLVED'].includes(form.eventType)" :label="form.eventType === 'RESOLVED' ? '处理结果' : '原因'" required><el-input v-model="form.reason" type="textarea" :rows="3" maxlength="500" show-word-limit /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">确认登记</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ArrowDown } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api/client'
import { quantity } from '@/api/format'
import { useAppStore } from '@/stores/app'

const props = defineProps<{ purchaseOrderId: number }>()
const appStore = useAppStore()
const canManage = computed(() => appStore.hasPermission('purchase.followup.manage'))
const query = useQuery({ queryKey: ['purchase-followup', props.purchaseOrderId], queryFn: () => api.purchaseFollowup(props.purchaseOrderId), retry: false })
const summary = computed(() => query.data.value)
const loading = computed(() => query.isFetching.value)
const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive({ eventType: '', occurredOn: today(), expectedArrivalDate: '', carrierName: '', trackingNo: '', exceptionType: 'OTHER', reason: '' })
const dispatchItems = ref<Array<any>>([])

function openAction(command: string) {
  Object.assign(form, { eventType: command, occurredOn: today(), expectedArrivalDate: summary.value?.latestExpectedArrivalDate || today(), carrierName: '', trackingNo: '', exceptionType: 'OTHER', reason: '' })
  dispatchItems.value = (summary.value?.items || []).filter(item => item.remainingToDispatch > 0).map(item => ({ ...item, thisQuantity: item.remainingToDispatch }))
  dialogVisible.value = true
}
async function save() {
  if (!form.occurredOn) return ElMessage.warning('请选择发生日期')
  if (['SUPPLIER_DISPATCHED','ETA_CHANGED','DELAYED'].includes(form.eventType) && !form.expectedArrivalDate) return ElMessage.warning('请选择预计到货日期')
  if (['DELAYED','EXCEPTION','RESOLVED'].includes(form.eventType) && form.reason.trim().length < 2) return ElMessage.warning('请填写真实原因或处理结果')
  const items = form.eventType === 'SUPPLIER_DISPATCHED' ? dispatchItems.value.filter(item => item.thisQuantity > 0).map(item => ({ purchaseOrderItemId: item.purchaseOrderItemId, dispatchedQuantity: item.thisQuantity })) : []
  if (form.eventType === 'SUPPLIER_DISPATCHED' && !items.length) return ElMessage.warning('至少填写一行本次发出数量')
  saving.value = true
  try {
    await api.addPurchaseFollowup(props.purchaseOrderId, { ...form, items })
    ElMessage.success('采购跟催已登记')
    dialogVisible.value = false
    await query.refetch()
  } finally { saving.value = false }
}
function today() { return new Date().toLocaleDateString('sv-SE') }
function eventName(value: string) { return ({ SUPPLIER_DISPATCHED: '供应商已发', ETA_CHANGED: '预计到货变更', DELAYED: '采购延误', EXCEPTION: '采购异常', RESOLVED: '异常已解决' } as Record<string, string>)[value] || value }
function statusName(value: string) { return ({ WAITING_DISPATCH: '待供应商发货', PARTIAL_DISPATCHED: '部分已发', IN_TRANSIT: '运输途中', DELAYED: '已延误', EXCEPTION: '有异常', RECEIVED: '已全部入库' } as Record<string, string>)[value] || value }
</script>

<style scoped>
.followup-panel{margin-top:16px}.followup-summary{display:grid;grid-template-columns:repeat(5,minmax(130px,1fr));gap:10px;margin-bottom:14px}.followup-summary>div{padding:12px;border:1px solid var(--el-border-color-light);border-radius:8px;background:var(--el-fill-color-lighter)}.followup-summary span,.followup-summary small{display:block;color:var(--el-text-color-secondary);font-size:12px}.followup-summary strong{display:block;margin-top:5px;font-size:17px}.followup-summary .danger strong{color:var(--el-color-danger)}.followup-timeline{margin-top:18px}@media(max-width:900px){.followup-summary{grid-template-columns:repeat(2,1fr)}}@media(max-width:560px){.followup-summary{grid-template-columns:1fr}}
</style>
