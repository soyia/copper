<template>
  <div class="panel">
    <div class="panel-header">
      <div>
        <div class="panel-title">交付与签收</div>
        <div class="muted">记录货是否发出、签收或异常；签收不代表收款，也不会自动核销应收。</div>
      </div>
      <el-button v-if="outboundStatus === 'SHIPPED' && appStore.hasPermission('outbound.delivery.manage')" type="primary" plain @click="openEdit">{{ delivery ? '编辑交付信息' : '建立交付记录' }}</el-button>
    </div>
    <el-empty v-if="!delivery" description="尚未登记交付信息" :image-size="72" />
    <template v-else>
      <el-descriptions :column="4" border size="small">
        <el-descriptions-item label="状态"><el-tag :type="deliveryStatusType(delivery.status)">{{ deliveryStatus(delivery.status) }}</el-tag></el-descriptions-item>
        <el-descriptions-item label="方式">{{ deliveryMode(delivery.deliveryMode) }}</el-descriptions-item>
        <el-descriptions-item label="承运方">{{ delivery.carrierName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="运单号">{{ delivery.trackingNo || '-' }}</el-descriptions-item>
        <el-descriptions-item label="经办人">{{ delivery.handlerName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="发出时间">{{ formatDateTime(delivery.dispatchedAt) }}</el-descriptions-item>
        <el-descriptions-item label="签收时间">{{ formatDateTime(delivery.signedAt) }}</el-descriptions-item>
        <el-descriptions-item label="收货人">{{ delivery.recipientName || '-' }}</el-descriptions-item>
        <el-descriptions-item v-if="delivery.exceptionNote" label="异常说明" :span="4">{{ delivery.exceptionNote }}</el-descriptions-item>
      </el-descriptions>
      <div v-if="appStore.hasPermission('outbound.delivery.manage')" class="delivery-actions">
        <el-button v-if="['PENDING', 'EXCEPTION'].includes(delivery.status)" type="primary" @click="action('DISPATCH')">确认发出</el-button>
        <el-button v-if="delivery.status === 'DISPATCHED'" type="success" @click="sign">确认签收</el-button>
        <el-button v-if="['PENDING', 'DISPATCHED'].includes(delivery.status)" type="warning" plain @click="actionWithReason('EXCEPTION', '登记交付异常')">登记异常</el-button>
        <el-button v-if="['SIGNED', 'EXCEPTION'].includes(delivery.status)" plain @click="actionWithReason('REOPEN', '重开交付')">重开</el-button>
      </div>
      <el-timeline v-if="delivery.events.length" class="delivery-timeline">
        <el-timeline-item v-for="item in delivery.events" :key="item.id" :timestamp="formatDateTime(item.createdAt)" placement="top">
          <strong>{{ eventName(item.action) }}</strong> · {{ item.operatorName }}<div v-if="item.reason" class="muted">{{ item.reason }}</div>
        </el-timeline-item>
      </el-timeline>
    </template>

    <el-dialog v-model="dialogVisible" title="交付信息" width="560px">
      <el-form label-position="top">
        <el-form-item label="交付方式"><el-radio-group v-model="form.deliveryMode"><el-radio-button value="SELF_PICKUP">客户自提</el-radio-button><el-radio-button value="OWN_DELIVERY">自送</el-radio-button><el-radio-button value="LOGISTICS">物流</el-radio-button></el-radio-group></el-form-item>
        <el-form-item v-if="form.deliveryMode === 'LOGISTICS'" label="承运方"><el-input v-model="form.carrierName" maxlength="128" /></el-form-item>
        <el-form-item label="运单号"><el-input v-model="form.trackingNo" maxlength="128" /></el-form-item>
        <el-form-item label="经办人/司机"><el-input v-model="form.handlerName" maxlength="128" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.memo" maxlength="500" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialogVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import { formatDateTime } from '@/api/format'
import { useAppStore } from '@/stores/app'

const props = defineProps<{ outboundId: number; outboundStatus: string }>()
const appStore = useAppStore()
const query = useQuery({ queryKey: ['outbound-delivery', props.outboundId], queryFn: () => api.outboundDelivery(props.outboundId) })
const delivery = computed(() => query.data.value)
const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive({ deliveryMode: 'LOGISTICS', carrierName: '', trackingNo: '', handlerName: '', memo: '' })
function openEdit() {
  Object.assign(form, delivery.value ? {
    deliveryMode: delivery.value.deliveryMode,
    carrierName: delivery.value.carrierName,
    trackingNo: delivery.value.trackingNo,
    handlerName: delivery.value.handlerName,
    memo: delivery.value.memo,
  } : { deliveryMode: 'LOGISTICS', carrierName: '', trackingNo: '', handlerName: '', memo: '' })
  dialogVisible.value = true
}
async function save() {
  if (form.deliveryMode === 'LOGISTICS' && !form.carrierName.trim()) return ElMessage.warning('物流交付请填写承运方')
  saving.value = true
  try {
    await api.saveOutboundDelivery(props.outboundId, form)
    dialogVisible.value = false
    ElMessage.success('交付信息已保存')
    await query.refetch()
  } finally { saving.value = false }
}
async function action(actionCode: string, recipientName = '', reason = '') {
  await api.actOutboundDelivery(props.outboundId, { action: actionCode, recipientName, reason })
  ElMessage.success(actionCode === 'DISPATCH' ? '已确认发出' : '交付状态已更新')
  await query.refetch()
}
async function sign() {
  const { value } = await ElMessageBox.prompt('请填写实际收货人。签收不会自动登记收款。', '确认签收', { inputPattern: /.{1,}/, inputErrorMessage: '请填写收货人' })
  await action('SIGN', value)
}
async function actionWithReason(code: string, title: string) {
  const { value } = await ElMessageBox.prompt('请填写原因，系统会保留状态变化记录。', title, { inputPattern: /.{2,}/, inputErrorMessage: '至少填写2个字' })
  await action(code, '', value)
}
function deliveryMode(value: string) { return ({ SELF_PICKUP: '客户自提', OWN_DELIVERY: '自送', LOGISTICS: '物流' } as Record<string, string>)[value] || value }
function deliveryStatus(value: string) { return ({ PENDING: '待发出', DISPATCHED: '已发出', SIGNED: '已签收', EXCEPTION: '异常' } as Record<string, string>)[value] || value }
function deliveryStatusType(value: string) { return ({ PENDING: 'info', DISPATCHED: 'primary', SIGNED: 'success', EXCEPTION: 'warning' } as Record<string, string>)[value] || 'info' }
function eventName(value: string) { return ({ CREATE: '建立交付', UPDATE: '更新信息', DISPATCH: '确认发出', SIGN: '确认签收', EXCEPTION: '登记异常', REOPEN: '重开交付' } as Record<string, string>)[value] || value }
</script>

<style scoped>
.delivery-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.delivery-timeline { margin: 18px 0 -8px; }
</style>
