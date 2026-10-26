<template>
  <div class="page capacity-page">
    <div class="panel capacity-intro">
      <div>
        <div class="panel-title">存储与容量</div>
        <div class="muted capacity-subtitle">容量来自客户自己的服务器、NAS 或对象存储，不设 Copper 人为套餐空间</div>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="load">刷新状态</el-button>
    </div>

    <el-alert
      v-if="capacity"
      :type="alertType"
      :closable="false"
      show-icon
      class="capacity-alert"
    >
      <template #title>{{ riskTitle }}</template>
      <div>{{ riskMessage }}</div>
    </el-alert>

    <div v-loading="loading && !capacity" class="kpi-grid capacity-kpis">
      <div class="kpi">
        <div class="kpi-label">数据盘可用</div>
        <div class="kpi-value" :class="riskClass">{{ fileSize(capacity?.dataFilesystem.availableBytes) }}</div>
        <div class="kpi-note">总容量 {{ fileSize(capacity?.dataFilesystem.capacityBytes) }}</div>
      </div>
      <div class="kpi">
        <div class="kpi-label">数据盘已用</div>
        <div class="kpi-value">{{ fileSize(physicalUsedBytes) }}</div>
        <div class="kpi-note">物理占用 {{ percentText(usedPercent) }}</div>
      </div>
      <div class="kpi">
        <div class="kpi-label">业务数据占用</div>
        <div class="kpi-value">{{ fileSize(knownUsageBytes) }}</div>
        <div class="kpi-note">后端已核对的数据目录</div>
      </div>
      <div class="kpi">
        <div class="kpi-label">其他占用</div>
        <div class="kpi-value">{{ fileSize(otherUsageBytes) }}</div>
        <div class="kpi-note">程序包、容器、系统或其他目录</div>
      </div>
    </div>

    <div v-if="capacity" class="panel disk-panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">数据盘物理占用</div>
          <div class="muted">进度条按整块数据盘容量计算，风险判断以可用空间为准</div>
        </div>
        <el-tag :type="riskTagType(capacity.runtimeRiskLevel)" effect="dark">{{ riskName(capacity.runtimeRiskLevel) }}</el-tag>
      </div>
      <div class="disk-summary">
        <div class="disk-summary-item">
          <span>已用</span>
          <strong>{{ fileSize(physicalUsedBytes) }}</strong>
          <small>{{ percentText(usedPercent) }}</small>
        </div>
        <div class="disk-summary-item">
          <span>可用</span>
          <strong :class="riskClass">{{ fileSize(capacity.dataFilesystem.availableBytes) }}</strong>
          <small>{{ percentText(availablePercent) }}</small>
        </div>
        <div class="disk-summary-item">
          <span>总容量</span>
          <strong>{{ fileSize(capacity.dataFilesystem.capacityBytes) }}</strong>
          <small>客户自有存储</small>
        </div>
      </div>
      <div class="capacity-bar" role="img" :aria-label="capacityBarLabel">
        <div class="capacity-bar-track">
          <div
            class="capacity-bar-segment is-copper"
            :style="{ left: percentStyle(displayedOtherUsagePercent), width: percentStyle(displayedKnownUsagePercent) }"
            :title="`Copper 业务数据 ${fileSize(knownUsageBytes)}，真实占比 ${percentText(knownUsagePercent)}；蓝色段设最短显示宽度`"
          />
          <div
            class="capacity-bar-segment is-other"
            :style="{ width: percentStyle(displayedOtherUsagePercent) }"
            :title="`其他物理占用 ${fileSize(otherUsageBytes)}，${percentText(otherUsagePercent)}`"
          />
        </div>
        <div class="capacity-bar-legend">
          <div><span class="capacity-bar-dot is-other" />其他占用 {{ fileSize(otherUsageBytes) }} · {{ percentText(otherUsagePercent) }}</div>
          <div><span class="capacity-bar-dot is-copper" />业务数据 {{ fileSize(knownUsageBytes) }} · {{ percentText(knownUsagePercent) }}</div>
          <div><span class="capacity-bar-dot is-available" />可用 {{ fileSize(capacity.dataFilesystem.availableBytes) }} · {{ percentText(availablePercent) }}</div>
        </div>
      </div>
      <div class="disk-footnote">
        蓝色为 Copper 业务数据，灰色为其他物理占用，浅绿色为可用空间；蓝色段设最短显示宽度，真实占比以图例和明细为准。
      </div>
      <div class="known-usage-section">
        <div class="known-usage-header">
          <div>
            <div class="known-usage-title">Copper 业务数据明细</div>
            <div class="muted">只列后端当前能核对的数据库、附件、导出、日志和备份；不包含程序包/JAR、JRE、容器镜像等部署占用</div>
          </div>
          <strong>{{ fileSize(knownUsageBytes) }}</strong>
        </div>
        <div class="usage-breakdown">
          <div><span class="usage-dot is-database" />数据库 {{ fileSize(capacity.usage.databaseBytes) }}</div>
          <div><span class="usage-dot is-attachment" />附件 {{ fileSize(capacity.usage.attachmentBytes) }}</div>
          <div><span class="usage-dot is-export" />业务导出 {{ fileSize(capacity.usage.exportBytes) }}</div>
          <div><span class="usage-dot is-log" />日志 {{ fileSize(capacity.usage.logBytes) }}</div>
          <div><span class="usage-dot is-backup" />本地备份 {{ fileSize(capacity.usage.backupBytes) }}</div>
        </div>
        <div class="other-usage-row">
          <div><span class="usage-dot is-other" />其他物理占用 {{ fileSize(otherUsageBytes) }}</div>
          <span>可能包含程序包/JAR、运行环境、容器镜像、系统或未核对目录</span>
        </div>
      </div>
    </div>

    <div class="panel target-panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">备份存储目标</div>
          <div class="muted">本地保留恢复主副本；NAS 和 S3 是客户自有的可选异地副本，三处备份字节保持完全一致</div>
        </div>
      </div>
      <el-table :data="capacity?.targets ?? []" border size="small" class="data-table" empty-text="尚未刷新存储目标状态">
        <el-table-column label="目标" min-width="150" fixed="left">
          <template #default="{ row }">
            <div class="target-name">
              <el-tag size="small" :type="row.available ? 'success' : 'danger'" effect="plain">{{ row.available ? '可用' : '不可用' }}</el-tag>
              <strong>{{ targetName(row.target) }}</strong>
            </div>
            <div class="multiline-secondary">{{ targetType(row.type) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="位置" min-width="260">
          <template #default="{ row }">
            <div class="target-location" :title="row.location">{{ row.location || '-' }}</div>
            <div v-if="!row.available" class="danger-text">连接、挂载、权限或凭据异常（{{ row.errorCode || 'UNAVAILABLE' }}）</div>
          </template>
        </el-table-column>
        <el-table-column label="成功备份" width="132" align="right">
          <template #default="{ row }">{{ row.backupCount.toLocaleString('zh-CN') }} 个</template>
        </el-table-column>
        <el-table-column label="备份占用" width="132" align="right">
          <template #default="{ row }">{{ fileSize(row.backupBytes) }}</template>
        </el-table-column>
        <el-table-column label="剩余容量" min-width="175">
          <template #default="{ row }">
            <template v-if="row.quotaKnown">
              <div>{{ fileSize(row.availableBytes) }}</div>
              <div class="multiline-secondary">共 {{ fileSize(row.capacityBytes) }}</div>
            </template>
            <template v-else>
              <div>{{ row.quotaMessage || '由存储供应商配额决定' }}</div>
              <div class="multiline-secondary">Copper 不把未知显示成无限</div>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="风险" width="105" fixed="right">
          <template #default="{ row }">
            <el-tag size="small" :type="riskTagType(row.riskLevel)">{{ riskName(row.riskLevel) }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="capacity" class="capacity-footer-grid">
      <div class="panel info-panel">
        <div class="panel-title">最近成功整站备份</div>
        <template v-if="capacity.lastSuccessfulBackup">
          <div class="info-value">{{ formatDateTime(capacity.lastSuccessfulBackup.completedAt) }}</div>
          <div class="multiline-secondary archive-name" :title="capacity.lastSuccessfulBackup.archive">{{ capacity.lastSuccessfulBackup.archive }}</div>
        </template>
        <div v-else class="muted info-value">暂无成功备份记录</div>
      </div>
      <div class="panel info-panel">
        <div class="panel-title">状态时点</div>
        <div class="info-value">{{ capacity.observedAt ? formatDateTime(capacity.observedAt) : '部署层快照未生成' }}</div>
        <div class="multiline-secondary">{{ capacity.statusMessage }}</div>
      </div>
      <div class="panel info-panel policy-panel">
        <div class="panel-title">保护规则</div>
        <div class="policy-line"><el-tag size="small" type="warning">预警</el-tag>{{ capacity.policy.warning }}</div>
        <div class="policy-line"><el-tag size="small" type="danger">严重</el-tag>{{ capacity.policy.critical }}</div>
        <div class="policy-line"><el-tag size="small" type="danger" effect="dark">危险</el-tag>{{ capacity.policy.danger }}</div>
      </div>
    </div>

    <el-alert type="info" :closable="false" show-icon>
      <template #title>运维边界</template>
      <div>NAS 由客户操作系统负责稳定挂载；对象存储账号、配额、流量费和网络质量由客户与供应商负责。服务器管理员可运行 <code>sudo copperctl storage status</code> 立即刷新状态。</div>
    </el-alert>
  </div>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { api, type StorageCapacityStatus, type StorageRiskLevel } from '@/api/client'
import { formatDateTime } from '@/api/format'

const capacity = ref<StorageCapacityStatus>()
const loading = ref(false)
const minKnownUsageBarPercent = 1.5

const risk = computed(() => capacity.value?.runtimeRiskLevel ?? 'UNKNOWN')
const riskClass = computed(() => `risk-${risk.value.toLowerCase()}`)
const alertType = computed<'success' | 'warning' | 'error' | 'info'>(() => {
  if (risk.value === 'NORMAL') return 'success'
  if (risk.value === 'WARNING') return 'warning'
  if (risk.value === 'CRITICAL' || risk.value === 'DANGER') return 'error'
  return 'info'
})
const riskTitle = computed(() => {
  if (risk.value === 'NORMAL') return '当前容量充足，业务写入和备份可正常进行'
  if (risk.value === 'WARNING') return '容量进入预警，请安排清理或扩容'
  if (risk.value === 'CRITICAL') return '容量进入严重级别，已阻止备份、完整导出和批量上传'
  if (risk.value === 'DANGER') return '容量进入危险级别，业务写入已受控停止'
  return '暂时无法确认当前容量风险'
})
const riskMessage = computed(() => {
  if (capacity.value?.snapshotStale) return `${capacity.value.statusMessage}；数据盘实时风险仍由后端直接核对。`
  if (risk.value === 'CRITICAL' || risk.value === 'DANGER') return '请先扩容，或仅清理已确认可删除的旧备份和导出文件；不要手工删除数据库或附件目录。'
  return 'Copper 只展示客户实际存储容量，不设置软件套餐空间，也不会把对象存储未知配额显示成无限。'
})
const physicalUsedBytes = computed(() => {
  const total = capacity.value?.dataFilesystem.capacityBytes ?? 0
  const available = capacity.value?.dataFilesystem.availableBytes ?? 0
  return Math.max(0, total - available)
})
const knownUsageBytes = computed(() => {
  const usage = capacity.value?.usage
  if (!usage) return 0
  return usage.databaseBytes + usage.attachmentBytes + usage.exportBytes + usage.logBytes + usage.backupBytes
})
const otherUsageBytes = computed(() => Math.max(0, physicalUsedBytes.value - knownUsageBytes.value))
const usedPercent = computed(() => {
  const total = capacity.value?.dataFilesystem.capacityBytes ?? 0
  return percentOf(physicalUsedBytes.value, total)
})
const knownUsagePercent = computed(() => {
  const total = capacity.value?.dataFilesystem.capacityBytes ?? 0
  return percentOf(knownUsageBytes.value, total)
})
const otherUsagePercent = computed(() => {
  const total = capacity.value?.dataFilesystem.capacityBytes ?? 0
  return percentOf(otherUsageBytes.value, total)
})
const displayedKnownUsagePercent = computed(() => {
  if (knownUsagePercent.value <= 0) return 0
  return Math.min(usedPercent.value, Math.max(knownUsagePercent.value, minKnownUsageBarPercent))
})
const displayedOtherUsagePercent = computed(() => Math.max(0, usedPercent.value - displayedKnownUsagePercent.value))
const availablePercent = computed(() => {
  const total = capacity.value?.dataFilesystem.capacityBytes ?? 0
  return percentOf(capacity.value?.dataFilesystem.availableBytes ?? 0, total)
})
const capacityBarLabel = computed(() => [
  `其他物理占用 ${fileSize(otherUsageBytes.value)}，${percentText(otherUsagePercent.value)}`,
  `Copper 业务数据 ${fileSize(knownUsageBytes.value)}，${percentText(knownUsagePercent.value)}`,
  `可用 ${fileSize(capacity.value?.dataFilesystem.availableBytes)}，${percentText(availablePercent.value)}`,
].join('；'))
onMounted(load)
onActivated(load)

async function load() {
  loading.value = true
  try {
    capacity.value = await api.storageCapacity()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '容量状态加载失败')
  } finally {
    loading.value = false
  }
}

function fileSize(bytes: number | null | undefined) {
  if (bytes == null) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 ** 2) return `${(bytes / 1024).toFixed(1)} KiB`
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MiB`
  if (bytes < 1024 ** 4) return `${(bytes / 1024 ** 3).toFixed(2)} GiB`
  return `${(bytes / 1024 ** 4).toFixed(2)} TiB`
}

function percentOf(value: number, total: number) {
  if (!total) return 0
  return Math.max(0, Math.min(100, Number((value * 100 / total).toFixed(3))))
}

function percentText(value: number) {
  if (value > 0 && value < 0.1) return '<0.1%'
  const text = value >= 10 ? value.toFixed(1) : value.toFixed(1)
  return `${text.replace(/\.0$/, '')}%`
}

function percentStyle(value: number) {
  return `${Math.max(0, Math.min(100, value))}%`
}

function riskName(value: StorageRiskLevel) {
  return ({ NORMAL: '正常', WARNING: '预警', CRITICAL: '严重', DANGER: '危险', UNKNOWN: '未知' } as const)[value] ?? value
}

function riskTagType(value: StorageRiskLevel): 'success' | 'warning' | 'danger' | 'info' {
  return ({ NORMAL: 'success', WARNING: 'warning', CRITICAL: 'danger', DANGER: 'danger', UNKNOWN: 'info' } as const)[value] ?? 'info'
}

function targetName(value: string) {
  return ({ local: '服务器本地', nas: 'NAS 挂载目录', s3: 'S3 兼容对象存储' } as Record<string, string>)[value] ?? value
}

function targetType(value: string) {
  return value === 'S3_COMPATIBLE' ? '对象存储' : '文件系统'
}
</script>

<style scoped>
.capacity-page { display: grid; gap: 14px; }
.capacity-intro { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 18px 20px; }
.capacity-subtitle { margin-top: 5px; }
.capacity-alert { align-items: flex-start; }
.capacity-kpis { margin: 0; min-height: 108px; }
.risk-warning { color: #b45309; }
.risk-critical, .risk-danger { color: #c53030; }
.risk-unknown { color: #6b7280; }
.disk-panel, .target-panel { padding: 18px 20px; }
.disk-panel .panel-header { margin: -18px -20px 16px; padding-right: 20px; padding-left: 20px; }
.disk-summary { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; margin-bottom: 14px; }
.disk-summary-item { min-width: 0; padding-bottom: 10px; border-bottom: 1px solid #edf2f7; }
.disk-summary-item span { display: block; color: #7a8a97; font-size: 12px; }
.disk-summary-item strong { display: block; overflow: hidden; margin-top: 5px; color: #253443; font-size: 20px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.disk-summary-item strong.risk-warning { color: #b45309; }
.disk-summary-item strong.risk-critical, .disk-summary-item strong.risk-danger { color: #c53030; }
.disk-summary-item strong.risk-unknown { color: #6b7280; }
.disk-summary-item small { display: block; margin-top: 4px; color: #82909c; font-size: 12px; }
.capacity-bar { display: grid; gap: 9px; }
.capacity-bar-track { position: relative; overflow: hidden; height: 16px; border-radius: 999px; background: #e9f7e5; }
.capacity-bar-segment { position: absolute; top: 0; bottom: 0; }
.capacity-bar-segment.is-copper { background: #1677ff; }
.capacity-bar-segment.is-other { background: #64748b; }
.capacity-bar-legend { display: flex; flex-wrap: wrap; gap: 8px 18px; color: #5f6b76; font-size: 12px; line-height: 1.4; }
.capacity-bar-dot { display: inline-block; width: 8px; height: 8px; margin-right: 7px; border-radius: 50%; }
.capacity-bar-dot.is-copper { background: #1677ff; }
.capacity-bar-dot.is-other { background: #64748b; }
.capacity-bar-dot.is-available { background: #67c23a; }
.disk-footnote { margin-top: 10px; color: #7a8a97; font-size: 12px; line-height: 1.5; }
.known-usage-section { margin-top: 16px; padding-top: 14px; border-top: 1px solid #edf2f7; }
.known-usage-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.known-usage-title { color: #253443; font-size: 14px; font-weight: 650; }
.known-usage-header strong { flex: none; color: #253443; font-size: 16px; }
.usage-breakdown { display: flex; flex-wrap: wrap; gap: 12px 22px; margin-top: 14px; color: #5f6b76; font-size: 13px; }
.other-usage-row { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-top: 12px; padding-top: 12px; border-top: 1px dashed #dbe4ee; color: #5f6b76; font-size: 13px; }
.other-usage-row > span { color: #82909c; font-size: 12px; }
.usage-dot { display: inline-block; width: 8px; height: 8px; margin-right: 7px; border-radius: 50%; background: #78909c; }
.usage-dot.is-database { background: #1677ff; }
.usage-dot.is-attachment { background: #16a34a; }
.usage-dot.is-export { background: #d97706; }
.usage-dot.is-log { background: #64748b; }
.usage-dot.is-backup { background: #7c3aed; }
.usage-dot.is-other { background: #9ca3af; }
.target-name { display: flex; align-items: center; gap: 8px; }
.target-location { overflow: hidden; color: #344054; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.capacity-footer-grid { display: grid; grid-template-columns: 1fr 1fr 1.35fr; gap: 14px; }
.info-panel { min-width: 0; padding: 18px 20px; }
.info-value { margin-top: 14px; color: #1f2937; font-size: 18px; font-weight: 650; }
.archive-name { overflow: hidden; margin-top: 7px; text-overflow: ellipsis; white-space: nowrap; }
.policy-panel { display: grid; gap: 9px; }
.policy-line { display: grid; grid-template-columns: 52px 1fr; align-items: center; gap: 7px; color: #5f6b76; font-size: 12px; }
code { padding: 1px 5px; border-radius: 4px; background: #eef2f6; color: #344054; }

@media (max-width: 1100px) {
  .capacity-footer-grid { grid-template-columns: 1fr 1fr; }
  .policy-panel { grid-column: 1 / -1; }
  .disk-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 760px) {
  .capacity-intro { align-items: flex-start; flex-direction: column; }
  .capacity-footer-grid { grid-template-columns: 1fr; }
  .policy-panel { grid-column: auto; }
  .disk-summary { grid-template-columns: 1fr; }
  .known-usage-header { display: grid; grid-template-columns: 1fr; gap: 8px; }
  .other-usage-row { display: grid; grid-template-columns: 1fr; gap: 6px; }
}
</style>
