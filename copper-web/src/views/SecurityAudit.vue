<template>
  <div class="page">
    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">安全审计</div>
          <div class="muted">独立记录登录、授权、数据范围、导出和高风险写入结果；业务单据流水仍按原业务账簿追溯。</div>
        </div>
        <el-button type="primary" :loading="loading" @click="load">查询</el-button>
      </div>
      <div class="list-tools audit-tools">
        <el-input v-model.trim="filters.action" clearable placeholder="动作编码，如 LOGIN_FAILED" @keyup.enter="load" />
        <el-select v-model="filters.result" clearable placeholder="执行结果">
          <el-option label="成功" value="SUCCESS" />
          <el-option label="已拒绝" value="DENIED" />
          <el-option label="失败" value="FAILED" />
        </el-select>
        <el-date-picker v-model="filters.range" type="datetimerange" value-format="YYYY-MM-DDTHH:mm:ss" range-separator="至" start-placeholder="开始时间" end-placeholder="结束时间" />
        <el-button @click="reset">重置</el-button>
        <div class="list-tools-actions"><span class="muted">最多显示最近 200 条</span></div>
      </div>
      <el-table v-loading="loading" :data="rows" border size="small" class="data-table" max-height="calc(100vh - 292px)">
        <el-table-column label="时间" width="180"><template #default="{ row }">{{ formatDateTime(row.occurredAt) }}</template></el-table-column>
        <el-table-column label="结果" width="76"><template #default="{ row }"><el-tag size="small" :type="resultType(row.resultCode)">{{ resultName(row.resultCode) }}</el-tag></template></el-table-column>
        <el-table-column label="操作人" min-width="110"><template #default="{ row }"><div>{{ row.operatorName || '系统' }}</div><div class="multiline-secondary">用户 #{{ row.operatorUserId }}</div></template></el-table-column>
        <el-table-column label="动作" min-width="168"><template #default="{ row }"><code class="audit-code">{{ row.actionCode }}</code><div v-if="row.failureCode" class="multiline-secondary">{{ row.failureCode }}</div></template></el-table-column>
        <el-table-column label="对象" min-width="105"><template #default="{ row }"><div>{{ row.targetType || '-' }}</div><div class="multiline-secondary">{{ targetKey(row) }}</div></template></el-table-column>
        <el-table-column label="说明 / 请求" min-width="260">
          <template #default="{ row }"><div>{{ row.summary }}</div><div class="multiline-secondary">{{ [row.requestMethod, row.requestPath].filter(Boolean).join(' ') || '后台任务' }} · IP {{ row.ipAddress || '-' }}</div></template>
        </el-table-column>
        <el-table-column label="追踪号" width="142"><template #default="{ row }"><el-button link type="primary" class="trace-button" :title="row.traceId" @click="copyTrace(row.traceId)">{{ shortTrace(row.traceId) }}</el-button></template></el-table-column>
        <template #empty><el-empty description="当前条件下没有安全审计记录" :image-size="80" /></template>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, type SecurityAuditRow } from '@/api/client'
import { formatDateTime } from '@/api/format'

const loading = ref(false)
const rows = ref<SecurityAuditRow[]>([])
const filters = reactive({ action: '', result: '' as '' | 'SUCCESS' | 'DENIED' | 'FAILED', range: [] as string[] })

onMounted(load)

async function load() {
  loading.value = true
  try {
    rows.value = await api.securityAudit({
      action: filters.action || undefined,
      result: filters.result || undefined,
      from: filters.range[0] || undefined,
      to: filters.range[1] || undefined,
      limit: 200,
    })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '安全审计加载失败')
  } finally { loading.value = false }
}

function reset() {
  filters.action = ''
  filters.result = ''
  filters.range = []
  load()
}

function resultName(result: SecurityAuditRow['resultCode']) {
  return result === 'SUCCESS' ? '成功' : result === 'DENIED' ? '已拒绝' : '失败'
}

function resultType(result: SecurityAuditRow['resultCode']) {
  return result === 'SUCCESS' ? 'success' : result === 'DENIED' ? 'warning' : 'danger'
}

function targetKey(row: SecurityAuditRow) {
  if (row.targetBusinessKey) return row.targetBusinessKey
  return row.targetId ? `#${row.targetId}` : '-'
}

function shortTrace(traceId: string) {
  return traceId.length > 12 ? `${traceId.slice(0, 12)}…` : traceId
}

async function copyTrace(traceId: string) {
  if (!traceId) return
  try {
    await navigator.clipboard.writeText(traceId)
    ElMessage.success('追踪号已复制')
  } catch { ElMessage.warning('浏览器不允许复制，请手动选择追踪号') }
}
</script>

<style scoped>
.audit-tools { grid-template-columns: minmax(210px, 1fr) 150px minmax(360px, 1.4fr) auto minmax(120px, 1fr); }
.audit-code { color: #234d67; font-size: 12px; }
.trace-button { max-width: 130px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; }
@media (max-width: 1100px) { .audit-tools { grid-template-columns: 1fr 150px; } }
</style>
