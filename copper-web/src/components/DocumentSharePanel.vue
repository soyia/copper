<template>
  <div class="panel">
    <div class="panel-header">
      <div>
        <div class="panel-title">对外只读分享</div>
        <div class="muted">只展示本单的往来单位、日期、商品、数量和金额；不展示库存成本、内部余额、电话或税号。</div>
      </div>
      <el-button v-if="canManage && publicOrigin" type="primary" plain @click="create">生成链接</el-button>
    </div>
    <el-alert
      v-if="canManage && !publicOrigin"
      title="当前未配置已确认可对外访问的 HTTPS 域名，对外只读分享未启用。"
      type="info"
      :closable="false"
      show-icon
      class="external-share-disabled"
    />
    <el-table :data="query.data.value ?? []" border size="small" empty-text="尚未生成分享链接">
      <el-table-column prop="shareNo" label="分享编号" min-width="170" />
      <el-table-column label="到期时间" min-width="230">
        <template #default="{ row }">
          <span>{{ formatDateTime(row.expiresAt) }}</span>
          <span class="muted">（{{ formatExpiryDistance(row.expiresAt, row.status) }}）</span>
        </template>
      </el-table-column>
      <el-table-column label="查看" width="90" align="right"><template #default="{ row }">{{ row.viewCount }} 次</template></el-table-column>
      <el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ statusName(row.status) }}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="100"><template #default="{ row }"><el-button v-if="row.status === 'ACTIVE'" link type="danger" @click="revoke(row.id)">撤销</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="linkDialogVisible" title="分享链接仅显示一次" width="680px" :close-on-click-modal="false">
      <el-alert title="链接无需登录即可查看本单脱敏内容。请只发给对应往来单位；发送错误或不再需要时立即撤销。" type="warning" :closable="false" show-icon />
      <el-input :model-value="createdLink" readonly style="margin-top:14px" />
      <template #footer><el-button type="primary" @click="copy">复制链接</el-button><el-button @click="linkDialogVisible=false">我已保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '@/api/client'
import { formatDateTime, formatExpiryDistance } from '@/api/format'
import { useAppStore } from '@/stores/app'
import { externalPublicOrigin } from '@/utils/publicAccess'

const props = defineProps<{ sourceType: 'SALES_ORDER' | 'PURCHASE_ORDER'; sourceId: number }>()
const appStore = useAppStore()
const canManage = computed(() => appStore.hasPermission('external.share.manage'))
const publicOrigin = externalPublicOrigin()
const query = useQuery({ queryKey: ['external-shares', props.sourceType, props.sourceId], queryFn: () => api.externalShares({ sourceType: props.sourceType, sourceId: props.sourceId }) })
const linkDialogVisible = ref(false)
const createdLink = ref('')
async function create() {
  const { value } = await ElMessageBox.prompt('请输入链接有效天数，最长30天。', '生成只读分享', {
    inputValue: '7',
    inputPattern: /^(?:[1-9]|[12]\d|30)$/,
    inputErrorMessage: '请输入1到30天',
  })
  const expiresAt = new Date(Date.now() + Number(value) * 86400000)
  const result = await api.createExternalShare({
    sourceType: props.sourceType,
    sourceId: props.sourceId,
    expiresAt: `${expiresAt.toLocaleDateString('sv-SE')}T${expiresAt.toTimeString().slice(0, 8)}`,
  })
  createdLink.value = `${publicOrigin}/public/order-shares/${encodeURIComponent(result.token)}`
  linkDialogVisible.value = true
  await query.refetch()
}
async function revoke(id: number) {
  const { value } = await ElMessageBox.prompt('撤销后链接立即失效。请填写原因。', '撤销分享', { inputPattern: /.{2,}/, inputErrorMessage: '至少填写2个字' })
  await api.revokeExternalShare(id, value)
  ElMessage.success('分享链接已撤销')
  await query.refetch()
}
async function copy() {
  await navigator.clipboard.writeText(createdLink.value)
  ElMessage.success('分享链接已复制')
}
function statusName(value: string) { return ({ ACTIVE: '有效', REVOKED: '已撤销', EXPIRED: '已到期' } as Record<string, string>)[value] || value }
</script>

<style scoped>
.external-share-disabled{margin:0 0 12px}
</style>
