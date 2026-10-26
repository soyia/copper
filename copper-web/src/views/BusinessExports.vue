<template>
  <div class="page export-page">
    <div class="export-intro panel">
      <div>
        <div class="panel-title">完整数据导出</div>
        <div class="muted export-subtitle">生成可由 Excel、LibreOffice 和通用程序长期读取的 copper-export/v1 文件包</div>
      </div>
      <el-button type="primary" :icon="Download" :loading="creating" :disabled="hasActiveTask" @click="createExport">
        {{ hasActiveTask ? '正在生成' : '生成完整导出' }}
      </el-button>
    </div>

    <el-alert type="warning" :closable="false" show-icon class="export-warning">
      <template #title>导出包包含完整经营数据、往来单位信息、资金记录和原始附件</template>
      <div>请只保存到受控设备并妥善销毁旧副本。业务导出用于查阅和迁出，不代替可恢复 Copper 的整站备份。</div>
    </el-alert>

    <div class="kpi-grid export-kpis">
      <div class="kpi">
        <div class="kpi-label">当前状态</div>
        <div class="kpi-value export-kpi-text" :class="latestStatusClass">{{ latestTask ? statusName(latestTask.status) : '尚未生成' }}</div>
        <div class="kpi-note">{{ latestTask ? phaseName(latestTask.currentPhase) : '首次生成前不会占用导出空间' }}</div>
      </div>
      <div class="kpi">
        <div class="kpi-label">业务记录</div>
        <div class="kpi-value">{{ numberText(latestCompleted?.exportedRecordCount) }}</div>
        <div class="kpi-note">{{ latestCompleted ? `${latestCompleted.exportedTableCount} 张业务表` : '完成后显示实际数量' }}</div>
      </div>
      <div class="kpi">
        <div class="kpi-label">原始附件</div>
        <div class="kpi-value">{{ numberText(latestCompleted?.exportedAttachmentCount) }}</div>
        <div class="kpi-note">逐个校验大小和 SHA-256</div>
      </div>
      <div class="kpi">
        <div class="kpi-label">最近文件</div>
        <div class="kpi-value export-kpi-text">{{ latestCompleted ? fileSize(latestCompleted.archiveBytes) : '-' }}</div>
        <div class="kpi-note">{{ latestCompleted?.completedAt ? formatDateTime(latestCompleted.completedAt) : '暂无成功导出' }}</div>
      </div>
    </div>

    <div class="panel export-list-panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">导出任务</div>
          <div class="muted">任务在服务器后台执行；关闭页面不会中断，失败记录和重新生成关系会长期保留</div>
        </div>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>

      <el-table v-loading="loading && !tasks.length" :data="tasks" border size="small" class="data-table" max-height="calc(100vh - 498px)" empty-text="暂无导出任务">
        <el-table-column label="申请时间 / 申请人" min-width="190" fixed="left">
          <template #default="{ row }">
            <div class="multiline-primary">{{ formatDateTime(row.requestedAt) }}</div>
            <div class="multiline-secondary">{{ row.requestedByName || `用户 #${row.requestedByUserId}` }}</div>
          </template>
        </el-table-column>
        <el-table-column label="范围" width="118">
          <template #default="{ row }"><el-tag size="small" effect="plain">{{ row.scopeType === 'FULL' ? '全部数据' : row.scopeType }}</el-tag></template>
        </el-table-column>
        <el-table-column label="状态与进度" min-width="210">
          <template #default="{ row }">
            <div class="status-line">
              <el-tag size="small" :type="statusType(row.status)">{{ statusName(row.status) }}</el-tag>
              <span class="muted">{{ phaseName(row.currentPhase) }}</span>
            </div>
            <el-progress v-if="isActive(row)" :percentage="row.progressPercent" :stroke-width="7" :show-text="false" />
          </template>
        </el-table-column>
        <el-table-column label="内容" min-width="190">
          <template #default="{ row }">
            <template v-if="row.status === 'COMPLETED'">
              <div>{{ numberText(row.exportedRecordCount) }} 条记录 · {{ row.exportedTableCount }} 张表</div>
              <div class="multiline-secondary">{{ row.exportedAttachmentCount }} 个附件 · {{ fileSize(row.archiveBytes) }}</div>
            </template>
            <span v-else class="muted">完成后显示记录与附件数量</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" min-width="390">
          <template #default="{ row }">
            <template v-if="row.status === 'FAILED'">
              <div class="danger-text export-error" :title="row.errorMessage">{{ row.errorMessage || '生成失败，请重新生成' }}</div>
              <div class="multiline-secondary">错误代码：{{ row.errorCode || '-' }}</div>
            </template>
            <template v-else-if="row.status === 'COMPLETED'">
              <div class="export-result-item">
                <div class="export-result-heading">
                  <span>文件名</span>
                  <el-button link type="primary" size="small" :icon="DocumentCopy" @click="copyResult(row.fileName, '文件名')">复制</el-button>
                </div>
                <div class="export-result-value export-file-name">{{ row.fileName }}</div>
              </div>
              <div class="export-result-item">
                <div class="export-result-heading">
                  <span>SHA-256</span>
                  <el-button link type="primary" size="small" :icon="DocumentCopy" @click="copyResult(row.archiveSha256, 'SHA-256')">复制</el-button>
                </div>
                <div class="export-result-value hash-text">{{ row.archiveSha256 }}</div>
              </div>
            </template>
            <span v-else class="muted">服务器正在生成一致性快照</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.downloadable" link type="primary" :loading="downloadingId === row.id" @click="download(row)">下载</el-button>
            <el-button v-if="row.status === 'FAILED'" link type="primary" :loading="retryingId === row.id" :disabled="hasActiveTask" @click="retry(row)">重新生成</el-button>
            <span v-if="!row.downloadable && row.status !== 'FAILED'" class="muted">等待完成</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref } from 'vue'
import { DocumentCopy, Download, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, type BusinessExportStatus, type BusinessExportTask } from '@/api/client'
import { formatDateTime } from '@/api/format'

const tasks = ref<BusinessExportTask[]>([])
const hasActiveTask = ref(false)
const loading = ref(false)
const creating = ref(false)
const retryingId = ref('')
const downloadingId = ref('')
let pollTimer: number | undefined

const latestTask = computed(() => tasks.value[0])
const latestCompleted = computed(() => tasks.value.find((item) => item.status === 'COMPLETED'))
const latestStatusClass = computed(() => latestTask.value ? `is-${latestTask.value.status.toLowerCase()}` : '')

onMounted(load)
onActivated(startPolling)
onDeactivated(stopPolling)
onBeforeUnmount(stopPolling)

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const result = await api.businessExports()
    tasks.value = result.items
    hasActiveTask.value = result.hasActiveTask
    if (result.hasActiveTask) startPolling()
    else stopPolling()
  } catch (error) {
    if (!silent) ElMessage.error(error instanceof Error ? error.message : '导出任务加载失败')
  } finally {
    if (!silent) loading.value = false
  }
}

function startPolling() {
  if (pollTimer || !hasActiveTask.value) return
  pollTimer = window.setInterval(() => load(true), 2000)
}

function stopPolling() {
  if (pollTimer) window.clearInterval(pollTimer)
  pollTimer = undefined
}

async function createExport() {
  try {
    await ElMessageBox.confirm(
      '将导出全部主数据、进销存、应收应付、收付款、审计关系和原始附件。文件生成后，下载和保管责任由当前用户承担。',
      '确认生成完整数据导出？',
      { type: 'warning', confirmButtonText: '确认生成', cancelButtonText: '取消', distinguishCancelAndClose: true },
    )
    creating.value = true
    await api.createBusinessExport()
    ElMessage.success('导出任务已提交，可离开页面后稍后回来下载')
    await load(true)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '导出任务创建失败')
  } finally {
    creating.value = false
  }
}

async function retry(row: BusinessExportTask) {
  try {
    await ElMessageBox.confirm(
      `将保留失败任务 ${row.id.slice(0, 8)} 的记录，并创建一个新的完整导出任务。`,
      '重新生成失败任务？',
      { type: 'warning', confirmButtonText: '重新生成', cancelButtonText: '取消' },
    )
    retryingId.value = row.id
    await api.retryBusinessExport(row.id)
    ElMessage.success('已创建新的导出任务')
    await load(true)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error instanceof Error ? error.message : '重新生成失败')
  } finally {
    retryingId.value = ''
  }
}

async function download(row: BusinessExportTask) {
  downloadingId.value = row.id
  try {
    const file = await api.downloadBusinessExport(row.id)
    const url = URL.createObjectURL(file.blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = file.fileName
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    ElMessage.success(file.sha256 ? `下载已开始；SHA-256 ${shortHash(file.sha256)}` : '下载已开始')
    await load(true)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '导出文件下载失败')
  } finally {
    downloadingId.value = ''
  }
}

function isActive(row: BusinessExportTask) {
  return row.status === 'PENDING' || row.status === 'RUNNING'
}

function statusName(status: BusinessExportStatus) {
  return ({ PENDING: '等待执行', RUNNING: '正在生成', COMPLETED: '生成成功', FAILED: '生成失败' } as const)[status] ?? status
}

function statusType(status: BusinessExportStatus): 'info' | 'warning' | 'success' | 'danger' {
  return ({ PENDING: 'info', RUNNING: 'warning', COMPLETED: 'success', FAILED: 'danger' } as const)[status]
}

function phaseName(phase: string) {
  const names: Record<string, string> = {
    WAITING: '等待后台任务',
    CONSISTENT_SNAPSHOT: '读取一致性业务快照',
    SCHEMA_DICTIONARY: '生成字段字典',
    MASTER_DATA: '导出主数据',
    PURCHASING: '导出采购与入库',
    INVENTORY: '导出库存数据',
    SALES: '导出销售与出库',
    RECEIVABLES_PAYABLES: '导出应收应付',
    CASH_SETTLEMENT: '导出资金与核销',
    AUDIT_DATA: '导出权限与审计',
    ENUM_DICTIONARY: '生成枚举说明',
    BUSINESS_TOTALS: '核对关键余额',
    ATTACHMENTS: '导出并校验原始附件',
    BUILDING_MANIFEST: '生成导出清单',
    PACKAGING_ARCHIVE: '打包导出文件',
    VERIFYING_ARCHIVE: '校验文件包',
    COMPLETED: '文件包已校验',
    FAILED: '已安全停止',
    INTERRUPTED: '应用重启导致中断',
  }
  return names[phase] ?? phase
}

function fileSize(bytes: number) {
  if (!bytes) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MiB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GiB`
}

function numberText(value: number | undefined) {
  return value == null ? '-' : value.toLocaleString('zh-CN')
}

function shortHash(value: string) {
  return value ? `${value.slice(0, 12)}…${value.slice(-8)}` : '-'
}

async function copyResult(value: string, label: string) {
  if (!value) return
  try {
    await navigator.clipboard.writeText(value)
    ElMessage.success(`${label}已复制`)
  } catch {
    ElMessage.warning(`浏览器不允许自动复制，请直接选择完整${label}`)
  }
}
</script>

<style scoped>
.export-page {
  display: grid;
  gap: 14px;
}

.export-intro {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 18px 20px;
}

.export-subtitle {
  margin-top: 5px;
}

.export-warning {
  align-items: flex-start;
}

.export-kpis {
  margin: 0;
}

.export-kpi-text {
  overflow: hidden;
  font-size: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.export-kpi-text.is-completed { color: #2f855a; }
.export-kpi-text.is-failed { color: #c53030; }
.export-kpi-text.is-running { color: #b45309; }

.export-list-panel {
  min-height: 280px;
}

.status-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 7px;
}

.export-error {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.export-result-item + .export-result-item {
  margin-top: 8px;
}

.export-result-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 24px;
  color: #64748b;
  font-size: 12px;
}

.export-result-value {
  overflow-wrap: anywhere;
  color: #1f2937;
  line-height: 1.45;
  user-select: text;
  white-space: normal;
}

.hash-text {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

@media (max-width: 980px) {
  .export-intro {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
