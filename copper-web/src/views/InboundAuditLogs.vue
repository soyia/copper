<template>
  <div class="page" v-if="inbound">
    <div class="panel">
      <PageHeader :title="`审核记录 ${inbound.orderNo}`" :back-to="backPath" />
      <div class="panel-header audit-list-heading">
        <div>
          <div class="panel-title">审核记录</div>
          <div class="muted">按操作时间倒序展示。列表不展开快照，点击详情后查看该轮快照及变更对比。</div>
        </div>
      </div>
      <div class="panel-body audit-list-body">
        <el-table v-if="sortedAuditLogs.length" :data="sortedAuditLogs" border size="small" show-overflow-tooltip>
          <el-table-column label="记录号" width="100"><template #default="{ row }">#{{ row.id }}</template></el-table-column>
          <el-table-column label="操作时间" width="175"><template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template></el-table-column>
          <el-table-column prop="operatorName" label="操作人" width="130" />
          <el-table-column label="审核批次" width="125">
            <template #default="{ row }"><span v-if="row.reviewRound">第 {{ row.reviewRound }} 轮</span><el-tag v-else type="info" size="small">历史无快照</el-tag></template>
          </el-table-column>
          <el-table-column label="审核操作" width="165">
            <template #default="{ row }"><el-tag :type="auditActionType(row.action)" size="small" effect="plain">{{ auditActionName(row.action) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" min-width="260"><template #default="{ row }">{{ row.reason || '-' }}</template></el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }"><el-button link type="primary" @click="openAuditDetail(row)">详情</el-button></template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="暂无审核记录" :image-size="72" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { api, type InboundAuditLog } from '@/api/client'
import { formatDateTime } from '@/api/format'
import PageHeader from '@/components/PageHeader.vue'
import { relatedDetailRoute, returnPath } from '@/utils/navigation'

const route = useRoute()
const router = useRouter()
const inboundId = Number(route.params.id)
const backPath = computed(() => returnPath(route, `/inbounds/${inboundId}`))
const { data: inbound } = useQuery({ queryKey: ['inbound-detail', inboundId], queryFn: () => api.inboundDetail(inboundId) })
const { data: auditLogs } = useQuery({ queryKey: ['inbound-audit-logs', inboundId], queryFn: () => api.inboundAuditLogs(inboundId) })
const sortedAuditLogs = computed(() => [...(auditLogs.value ?? [])].sort((left, right) => {
  const timeDiff = Date.parse(right.createdAt) - Date.parse(left.createdAt)
  return timeDiff || right.id - left.id
}))

function openAuditDetail(item: InboundAuditLog) {
  router.push(relatedDetailRoute(route, `/inbounds/${inboundId}/audit-logs/${item.id}`))
}
function auditActionName(action: InboundAuditLog['action']) {
  return ({ SUBMIT: '提交审核', WITHDRAW: '撤回审核', APPROVE: '审核通过并入库', REJECT: '审核拒绝' } as const)[action] || action
}
function auditActionType(action: InboundAuditLog['action']) {
  if (action === 'APPROVE') return 'success'
  if (action === 'REJECT') return 'danger'
  if (action === 'WITHDRAW') return 'warning'
  return 'primary'
}
</script>

<style scoped>
.audit-list-heading { border-top: 1px solid var(--el-border-color-lighter); }
.audit-list-body { min-width: 0; }
</style>
