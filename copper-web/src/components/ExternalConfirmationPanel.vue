<template>
  <section class="panel external-confirm-panel">
    <div class="panel-header responsive-header">
      <div>
        <div class="panel-title">{{ title }}</div>
        <div class="muted">这是可选的客户留痕，不确认不影响交付。确认只形成外部记录，不会创建收款、核销、库存或会计结果。</div>
      </div>
      <el-button v-if="canManage && publicOrigin" type="primary" plain @click="create">生成确认链接</el-button>
    </div>
    <el-alert
      v-if="canManage && !publicOrigin"
      title="当前未配置已确认可对外访问的 HTTPS 域名，外部确认链接未启用。"
      type="info"
      :closable="false"
      show-icon
      class="external-confirm-disabled"
    />
    <el-table :data="query.data.value || []" border size="small" empty-text="尚未生成外部确认">
      <el-table-column prop="packetNo" label="确认编号" min-width="180" />
      <el-table-column prop="asOfDate" label="冻结日期" width="115" />
      <el-table-column label="到期时间" min-width="230">
        <template #default="{ row }">
          <span>{{ formatDateTime(row.expiresAt) }}</span>
          <span class="muted">（{{ formatExpiryDistance(row.expiresAt, row.status) }}）</span>
        </template>
      </el-table-column>
      <el-table-column label="查看" width="75" align="right"><template #default="{ row }">{{ row.viewCount }}次</template></el-table-column>
      <el-table-column label="状态" width="105"><template #default="{ row }"><el-tag :type="statusType(row.status)">{{ statusName(row.status) }}</el-tag></template></el-table-column>
      <el-table-column label="确认人" min-width="130"><template #default="{ row }">{{ row.confirmerName || '-' }}<div v-if="row.comment" class="muted">{{ row.comment }}</div></template></el-table-column>
      <el-table-column label="操作" width="90"><template #default="{ row }"><el-button v-if="row.status === 'ACTIVE'" link type="danger" @click="revoke(row.id)">撤销</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="linkVisible" title="外部确认链接仅显示一次" width="680px" :close-on-click-modal="false">
      <el-alert type="warning" :closable="false" show-icon title="链接持有人可以对冻结内容确认或提出异议。请只发给对应客户；确认不是收款、核销或可靠电子签名。" />
      <el-input :model-value="createdLink" readonly style="margin-top:14px" />
      <template #footer><el-button type="primary" @click="copy">复制链接</el-button><el-button @click="linkVisible=false">我已保存</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type ConfirmationPacketType } from '@/api/client'
import { formatDateTime, formatExpiryDistance } from '@/api/format'
import { useAppStore } from '@/stores/app'
import { externalPublicOrigin } from '@/utils/publicAccess'

const props = defineProps<{ packetType: ConfirmationPacketType; sourceId: number }>()
const appStore = useAppStore()
const canManage = computed(() => appStore.hasPermission('external.confirm.manage'))
const query = useQuery({ queryKey: ['external-confirmations', props.packetType, props.sourceId], queryFn: () => api.confirmationPackets(props.packetType, props.sourceId), enabled: canManage })
const linkVisible = ref(false)
const createdLink = ref('')
const publicOrigin = externalPublicOrigin()
const title = computed(() => ({ CUSTOMER_QUOTE: '报价对外确认', CUSTOMER_STATEMENT: '客户对账确认', DELIVERY_SUMMARY: '交付摘要确认' }[props.packetType]))
async function create() {
  const { value } = await ElMessageBox.prompt('请输入链接有效天数，建议3天，最长30天。', `生成${title.value}`, { inputValue: '3', inputPattern: /^(?:[1-9]|[12]\d|30)$/, inputErrorMessage: '请输入1到30天' })
  const expires = new Date(Date.now() + Number(value) * 86400000)
  const result = await api.createConfirmationPacket({ packetType: props.packetType, sourceId: props.sourceId, asOfDate: today(), expiresAt: localDateTime(expires) })
  createdLink.value = `${publicOrigin}/public/confirmations/${encodeURIComponent(result.token)}`
  linkVisible.value = true
  await query.refetch()
}
async function revoke(id: number) {
  const { value } = await ElMessageBox.prompt('撤销后链接立即失效，请填写原因。', '撤销外部确认', { inputPattern: /.{2,}/, inputErrorMessage: '至少填写2个字' })
  await api.revokeConfirmationPacket(id, value)
  ElMessage.success('确认链接已撤销')
  await query.refetch()
}
async function copy() { await navigator.clipboard.writeText(createdLink.value); ElMessage.success('链接已复制') }
function today() { return new Date().toLocaleDateString('sv-SE') }
function localDateTime(value: Date) { return `${value.toLocaleDateString('sv-SE')}T${value.toTimeString().slice(0,8)}` }
function statusName(value: string) { return ({ ACTIVE: '待确认', CONFIRMED: '内容无误', DISPUTED: '存在差异', REVOKED: '已撤销', EXPIRED: '已到期' } as Record<string,string>)[value] || value }
function statusType(value: string) { return ({ ACTIVE: 'warning', CONFIRMED: 'success', DISPUTED: 'danger', REVOKED: 'info', EXPIRED: 'info' } as Record<string,any>)[value] || 'info' }
</script>

<style scoped>
.external-confirm-panel{margin-top:16px}
.external-confirm-disabled{margin:0 0 12px}
</style>
