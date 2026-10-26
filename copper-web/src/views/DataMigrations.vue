<template>
  <div class="page migration-page">
    <div class="panel hero-panel">
      <div>
        <div class="panel-title">期初数据迁移</div>
        <div class="muted hero-subtitle">一次导入往来单位、仓库、商品、库存、往来余额、资金余额和总账期初，并在切换前完成勾稽。</div>
      </div>
      <div class="hero-actions">
        <el-button :icon="Download" :loading="downloading" @click="downloadTemplate">下载标准模板</el-button>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert type="warning" :closable="false" show-icon>
      <template #title>先冻结旧系统并完成预检，再应用；应用不会立即记账，只有“确认切换”才会记账期初凭证。</template>
      确认切换前，如尚未发生后续业务，可以整批撤销；确认切换后不能无痕回滚，只能通过调整或冲正保留历史。
    </el-alert>

    <div class="step-grid" aria-label="期初迁移流程">
      <div v-for="step in steps" :key="step.no" class="step-card">
        <span>{{ step.no }}</span>
        <div><strong>{{ step.title }}</strong><small>{{ step.note }}</small></div>
      </div>
    </div>

    <div class="work-grid migration-work-grid">
      <div class="panel upload-panel">
        <div class="panel-header">
          <div><div class="panel-title">新建预检批次</div><div class="muted">上传只生成逐行预检结果，不写入经营事实</div></div>
        </div>
        <div class="panel-body upload-body">
          <el-form label-position="top">
            <el-form-item label="期初日期" required>
              <el-date-picker v-model="openingDate" value-format="YYYY-MM-DD" :clearable="false" />
              <div class="field-help">必须位于开放会计期间；通常选择正式启用 Copper 的第一天。</div>
            </el-form-item>
            <el-form-item label="标准工作簿" required>
              <el-upload
                v-model:file-list="uploadFiles"
                drag
                :auto-upload="false"
                :limit="1"
                accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                :on-change="fileChanged"
                :on-remove="fileRemoved"
              >
                <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
                <div class="el-upload__text">拖入已填写的 .xlsx，或<em>选择文件</em></div>
                <template #tip><div class="el-upload__tip">只接受标准模板，最大 10 MB、总计 30,000 行；同一文件重复上传返回原批次。</div></template>
              </el-upload>
            </el-form-item>
          </el-form>
          <el-button
            v-if="canManage"
            type="primary"
            class="preflight-button"
            :loading="uploading"
            :disabled="!selectedFile || !openingDate"
            @click="preflight"
          >上传并逐行预检</el-button>
          <el-alert v-else type="info" :closable="false" title="当前角色只能查看迁移结果，不能上传新批次。" />
        </div>
      </div>

      <div class="panel batch-panel">
        <div class="panel-header"><div><div class="panel-title">迁移批次</div><div class="muted">最多显示最近 100 批，点击查看完整结果</div></div></div>
        <el-table
          v-loading="loading"
          :data="batches"
          row-key="id"
          highlight-current-row
          :current-row-key="detail?.header.id"
          class="data-table batch-table"
          size="small"
          empty-text="暂无迁移批次"
          @current-change="selectBatch"
        >
          <el-table-column label="批次 / 期初日" min-width="176">
            <template #default="{ row }"><strong>{{ row.batchNo }}</strong><div class="multiline-secondary">{{ row.openingDate }} · {{ row.sourceFileName }}</div></template>
          </el-table-column>
          <el-table-column label="预检" width="105" align="right">
            <template #default="{ row }"><span :class="row.errorRows ? 'amount-danger' : ''">{{ row.validRows }}/{{ row.totalRows }}</span><div class="multiline-secondary">错误 {{ row.errorRows }}</div></template>
          </el-table-column>
          <el-table-column label="状态" width="112"><template #default="{ row }"><el-tag size="small" :type="statusType(row.status)">{{ statusName(row.status) }}</el-tag></template></el-table-column>
        </el-table>
      </div>
    </div>

    <template v-if="detail">
      <div class="panel detail-head">
        <div class="panel-header detail-header">
          <div>
            <div class="detail-title-line"><div class="panel-title">{{ detail.header.batchNo }}</div><el-tag :type="statusType(detail.header.status)">{{ statusName(detail.header.status) }}</el-tag></div>
            <div class="muted">期初日 {{ detail.header.openingDate }} · {{ detail.header.sourceFileName }} · {{ fileSize(detail.header.sourceFileSize) }}</div>
          </div>
          <div class="detail-actions">
            <el-button v-if="canApply && detail.header.status === 'PREFLIGHT_PASSED'" type="primary" :loading="acting" @click="applyBatch">应用期初</el-button>
            <el-button v-if="canView && ['APPLIED','CUTOVER'].includes(detail.header.status)" :loading="acting" @click="runCheck">重新勾稽</el-button>
            <el-button v-if="canCutover && detail.header.status === 'APPLIED'" type="success" :loading="acting" @click="cutoverBatch">确认切换并记账</el-button>
            <el-button v-if="canRollback && ['PREFLIGHT_FAILED','PREFLIGHT_PASSED','APPLIED'].includes(detail.header.status)" type="danger" plain :loading="acting" @click="rollbackBatch">整批撤销</el-button>
          </div>
        </div>
        <div class="status-guidance">{{ statusGuidance(detail.header.status) }}</div>
      </div>

      <div class="kpi-grid migration-kpis">
        <div class="kpi"><div class="kpi-label">预检行数</div><div class="kpi-value">{{ detail.header.validRows }}/{{ detail.header.totalRows }}</div><div class="kpi-note">错误 {{ detail.header.errorRows }} 行</div></div>
        <div class="kpi"><div class="kpi-label">{{ appliedLabel(detail.header.status) }}</div><div class="kpi-value">{{ detail.header.appliedRows }}</div><div class="kpi-note">{{ appliedNote(detail.header.status) }}</div></div>
        <div class="kpi"><div class="kpi-label">期初凭证</div><div class="kpi-value kpi-text">{{ detail.header.openingVoucherNo || '-' }}</div><div class="kpi-note"><router-link v-if="detail.header.openingVoucherId" class="table-link" :to="`/accounting/vouchers/${detail.header.openingVoucherId}`">{{ voucherStatusName(detail.header.openingVoucherStatus) }} · 查看凭证</router-link><span v-else>应用后生成草稿</span></div></div>
        <div class="kpi"><div class="kpi-label">最近勾稽</div><div class="kpi-value kpi-text" :class="detail.latestCheck?.status === 'FAILED' ? 'amount-danger' : ''">{{ detail.latestCheck ? checkStatusName(detail.latestCheck.status) : '尚未执行' }}</div><div class="kpi-note">{{ detail.latestCheck ? `${detail.latestCheck.checkedCount} 项 · 差异 ${detail.latestCheck.issueCount}` : '应用后自动执行' }}</div></div>
      </div>

      <div class="panel result-panel">
        <el-tabs>
          <el-tab-pane label="七域汇总">
            <el-table :data="detail.domains" border size="small" class="data-table" empty-text="暂无数据">
              <el-table-column prop="domainName" label="数据域" min-width="150" fixed="left" />
              <el-table-column prop="totalRows" label="总行数" width="95" align="right" />
              <el-table-column label="预检通过" width="105" align="right"><template #default="{ row }"><strong>{{ row.validRows }}</strong></template></el-table-column>
              <el-table-column label="错误" width="90" align="right"><template #default="{ row }"><span :class="row.errorRows ? 'amount-danger' : ''">{{ row.errorRows }}</span></template></el-table-column>
              <el-table-column prop="appliedRows" :label="appliedLabel(detail.header.status)" width="95" align="right" />
              <el-table-column label="数量" width="135" align="right"><template #default="{ row }">{{ row.quantityTotal ? numberText(row.quantityTotal, 3) : '-' }}</template></el-table-column>
              <el-table-column label="金额" width="150" align="right"><template #default="{ row }">{{ row.amountTotal ? money(row.amountTotal) : '-' }}</template></el-table-column>
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`预检错误 ${detail.rowErrors.length}`">
            <el-alert v-if="detail.rowErrors.length" type="error" :closable="false" show-icon title="请在原工作簿修正后重新上传；系统不会在页面内偷偷修改源数据。" class="tab-alert" />
            <el-table :data="detail.rowErrors" border size="small" class="data-table" empty-text="全部行预检通过">
              <el-table-column label="工作表 / 行" width="150"><template #default="{ row }">{{ row.sourceSheet }} · {{ row.sourceRowNo }}</template></el-table-column>
              <el-table-column prop="sourceKey" label="来源键" min-width="140" />
              <el-table-column prop="errorCode" label="错误代码" min-width="180" />
              <el-table-column prop="errorMessage" label="修正说明" min-width="380" />
            </el-table>
          </el-tab-pane>
          <el-tab-pane :label="`一致性检查 ${detail.latestCheck?.issueCount ?? 0}`">
            <el-alert
              v-if="detail.latestCheck"
              :type="detail.latestCheck.status === 'PASSED' ? 'success' : 'error'"
              :closable="false"
              show-icon
              :title="`${checkStatusName(detail.latestCheck.status)}：检查 ${detail.latestCheck.checkedCount} 项，阻断差异 ${detail.latestCheck.issueCount} 项`"
              class="tab-alert"
            />
            <el-empty v-else description="应用期初后自动执行首轮一致性检查" />
            <el-table v-if="detail.latestCheck?.issues.length" :data="detail.latestCheck.issues" border size="small" class="data-table">
              <el-table-column prop="domainCode" label="数据域" width="135" />
              <el-table-column prop="checkCode" label="检查项" min-width="190" />
              <el-table-column prop="expectedValue" label="应为" width="140" />
              <el-table-column prop="actualValue" label="实际" width="140" />
              <el-table-column prop="message" label="处理说明" min-width="360" />
            </el-table>
          </el-tab-pane>
          <el-tab-pane label="操作审计">
            <el-timeline class="audit-timeline">
              <el-timeline-item v-for="event in [...detail.events].reverse()" :key="event.id" :timestamp="formatDateTime(event.createdAt)" placement="top">
                <div class="event-title">{{ actionName(event.action) }} · {{ event.operatorName }}</div>
                <div class="muted">{{ event.fromStatus ? `${statusName(event.fromStatus)} → ` : '' }}{{ statusName(event.toStatus) }}</div>
                <div class="event-reason">{{ event.reason }}</div>
              </el-timeline-item>
            </el-timeline>
          </el-tab-pane>
        </el-tabs>
      </div>

      <div class="panel safeguard-panel">
        <div class="panel-header"><div><div class="panel-title">安全边界</div><div class="muted">迁移工具负责可追溯和勾稽，不替代企业会计确认法定期初口径</div></div></div>
        <ul><li v-for="item in detail.safeguards" :key="item">{{ item }}</li></ul>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile, UploadFiles, UploadUserFile } from 'element-plus'
import { Download, Refresh, UploadFilled } from '@element-plus/icons-vue'
import { api, type DataMigrationBatchRow, type DataMigrationDetail, type DataMigrationStatus } from '@/api/client'
import { currentDateText, formatDateTime, money } from '@/api/format'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()
const loading = ref(false)
const uploading = ref(false)
const downloading = ref(false)
const acting = ref(false)
const batches = ref<DataMigrationBatchRow[]>([])
const detail = ref<DataMigrationDetail | null>(null)
const uploadFiles = ref<UploadUserFile[]>([])
const selectedFile = ref<File | null>(null)
const openingDate = ref(`${currentDateText().slice(0, 4)}-01-01`)
const canView = computed(() => appStore.hasPermission('data.migration.view'))
const canManage = computed(() => appStore.hasPermission('data.migration.manage'))
const canApply = computed(() => appStore.hasPermission('data.migration.apply'))
const canRollback = computed(() => appStore.hasPermission('data.migration.rollback'))
const canCutover = computed(() => appStore.hasPermission('data.migration.cutover'))
const steps = [
  { no: '1', title: '填写标准模板', note: '七张表使用稳定来源键关联' },
  { no: '2', title: '上传并预检', note: '逐行校验且不写业务事实' },
  { no: '3', title: '应用并勾稽', note: '生成期初事实和草稿凭证' },
  { no: '4', title: '确认切换', note: '复核通过后记账并锁定历史' },
]

onMounted(() => load())

async function load(preferredId?: number) {
  loading.value = true
  try {
    batches.value = await api.dataMigrations()
    const id = preferredId ?? detail.value?.header.id ?? batches.value[0]?.id
    detail.value = id ? await api.dataMigration(id) : null
  } catch (error) {
    ElMessage.error(errorMessage(error, '迁移批次加载失败'))
  } finally {
    loading.value = false
  }
}

async function selectBatch(row?: DataMigrationBatchRow) {
  if (!row || row.id === detail.value?.header.id) return
  loading.value = true
  try { detail.value = await api.dataMigration(row.id) }
  catch (error) { ElMessage.error(errorMessage(error, '迁移详情加载失败')) }
  finally { loading.value = false }
}

function fileChanged(file: UploadFile, files: UploadFiles) {
  uploadFiles.value = files.slice(-1)
  selectedFile.value = file.raw ?? null
}

function fileRemoved() { selectedFile.value = null }

async function downloadTemplate() {
  downloading.value = true
  try {
    const result = await api.downloadDataMigrationTemplate()
    const url = URL.createObjectURL(result.blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = result.fileName
    anchor.click()
    URL.revokeObjectURL(url)
    ElMessage.success('标准模板已下载，请保留工作表名称和表头')
  } catch (error) {
    ElMessage.error(errorMessage(error, '模板下载失败'))
  } finally {
    downloading.value = false
  }
}

async function preflight() {
  if (!selectedFile.value || !openingDate.value) return
  uploading.value = true
  try {
    const result = await api.uploadDataMigration(openingDate.value, selectedFile.value)
    detail.value = result
    uploadFiles.value = []
    selectedFile.value = null
    await load(result.header.id)
    if (result.header.status === 'PREFLIGHT_PASSED') ElMessage.success('预检通过，尚未形成任何经营事实')
    else ElMessage.warning(`预检发现 ${result.header.errorRows} 行错误，请修正原文件后重新上传`)
  } catch (error) {
    ElMessage.error(errorMessage(error, '工作簿预检失败'))
  } finally {
    uploading.value = false
  }
}

async function applyBatch() {
  await withReason('应用期初数据', '请填写旧系统冻结、清单复核或内部演练依据。应用后会形成库存、往来、资金事实和草稿凭证，但尚未确认切换。', async (reason) => {
    detail.value = await api.applyDataMigration(detail.value!.header.id, reason)
    ElMessage.success('期初数据已应用，自动勾稽通过；凭证仍为草稿')
  })
}

async function rollbackBatch() {
  await withReason('整批撤销期初迁移', '撤销会删除本批尚未被后续业务使用的期初事实并恢复被接管的银行账户。已有后续出入库、收付款或记账时系统会拒绝。', async (reason) => {
    detail.value = await api.rollbackDataMigration(detail.value!.header.id, reason)
    ElMessage.success('迁移批次已撤销，审计轨迹已保留')
  })
}

async function cutoverBatch() {
  const confirmed = await ElMessageBox.confirm(
    '确认切换会审核并记账期初凭证，此后不能整批无痕回滚。请确认旧系统已冻结、七域勾稽无差异，并已由负责人和企业会计复核。',
    '确认切换并记账',
    { type: 'warning', confirmButtonText: '继续填写确认依据', cancelButtonText: '暂不切换' },
  ).then(() => true).catch(() => false)
  if (!confirmed) return
  await withReason('确认切换依据', '写明负责人、会计复核或切换清单依据，至少 4 个字。', async (reason) => {
    detail.value = await api.cutoverDataMigration(detail.value!.header.id, reason)
    ElMessage.success('期初切换已确认，凭证已记账；后续修正必须留痕')
  })
}

async function runCheck() {
  if (!detail.value) return
  acting.value = true
  try {
    const result = await api.checkDataMigration(detail.value.header.id)
    detail.value = await api.dataMigration(detail.value.header.id)
    if (result.status === 'PASSED') ElMessage.success(`一致性检查通过，共 ${result.checkedCount} 项`)
    else ElMessage.error(`一致性检查发现 ${result.issueCount} 项阻断差异`)
  } catch (error) {
    ElMessage.error(errorMessage(error, '一致性检查失败'))
  } finally {
    acting.value = false
  }
}

async function withReason(title: string, message: string, task: (reason: string) => Promise<void>) {
  const result = await ElMessageBox.prompt(message, title, {
    inputType: 'textarea',
    inputPlaceholder: '请输入具体业务依据（至少 4 个字）',
    inputValidator: (value) => String(value || '').trim().length >= 4 || '业务依据至少 4 个字',
    confirmButtonText: '确认执行',
    cancelButtonText: '取消',
    type: 'warning',
  }).catch(() => null)
  if (!result) return
  acting.value = true
  try {
    await task(result.value.trim())
    await load(detail.value?.header.id)
  } catch (error) {
    ElMessage.error(errorMessage(error, `${title}失败`))
  } finally {
    acting.value = false
  }
}

function statusName(value: string) {
  return ({ PREFLIGHT_FAILED: '预检未通过', PREFLIGHT_PASSED: '预检通过', APPLIED: '已应用待切换', CUTOVER: '已确认切换', ROLLED_BACK: '已撤销' } as Record<string, string>)[value] ?? value
}
function statusType(value: DataMigrationStatus | string) { return value === 'CUTOVER' ? 'success' : value === 'APPLIED' ? 'warning' : value === 'PREFLIGHT_FAILED' ? 'danger' : value === 'PREFLIGHT_PASSED' ? 'primary' : 'info' }
function statusGuidance(value: DataMigrationStatus) {
  return ({
    PREFLIGHT_FAILED: '预检没有写入业务事实。请按逐行错误修正原工作簿，再上传形成新批次。',
    PREFLIGHT_PASSED: '七域预检与试算平衡已通过。应用前仍应冻结旧系统并人工核对原始清单。',
    APPLIED: '期初业务事实已形成且自动勾稽通过，凭证尚未记账。此时是最后的整批撤销窗口。',
    CUTOVER: '期初凭证已经记账并进入持续一致性检查。后续错误必须通过规范调整或冲正处理。',
    ROLLED_BACK: '本批已撤销且审计轨迹保留。如需重做，请修正原工作簿后重新上传。',
  } as Record<DataMigrationStatus, string>)[value]
}
function voucherStatusName(value: string) { return ({ DRAFT: '草稿', REVIEWED: '已审核', POSTED: '已记账', CANCELLED: '已作废', REVERSED: '已冲销' } as Record<string, string>)[value] ?? value }
function checkStatusName(value: string) { return value === 'PASSED' ? '勾稽通过' : '存在阻断差异' }
function actionName(value: string) { return ({ PREFLIGHT: '上传预检', APPLY: '应用期初', CHECK: '一致性检查', CUTOVER: '确认切换', ROLLBACK: '整批撤销' } as Record<string, string>)[value] ?? value }
function appliedLabel(value: DataMigrationStatus) { return value === 'ROLLED_BACK' ? '曾应用' : '已应用' }
function appliedNote(value: DataMigrationStatus) { return value === 'ROLLED_BACK' ? '事实已撤销，保留历史应用行数供审计' : '只有应用后才形成业务事实' }
function fileSize(value: number) { return value < 1024 * 1024 ? `${(value / 1024).toFixed(1)} KB` : `${(value / 1024 / 1024).toFixed(1)} MB` }
function numberText(value: number, digits = 0) { return Number(value).toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits }) }
function errorMessage(error: unknown, fallback: string) { return error instanceof Error ? error.message : fallback }
</script>

<style scoped>
.hero-panel,.hero-actions,.detail-title-line,.detail-actions{display:flex;align-items:center}.hero-panel{justify-content:space-between;gap:16px;padding:18px 20px}.hero-subtitle{margin-top:5px;font-size:13px}.hero-actions,.detail-actions{flex-wrap:wrap;gap:10px}.step-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}.step-card{display:flex;align-items:center;gap:12px;min-width:0;padding:14px 16px;border:1px solid #dde5ec;border-radius:9px;background:#fff}.step-card>span{display:grid;flex:0 0 30px;width:30px;height:30px;place-items:center;border-radius:50%;background:#eaf1f7;color:#315269;font-weight:700}.step-card strong,.step-card small{display:block}.step-card small{margin-top:3px;color:#7a8a97;font-size:12px}.migration-work-grid{grid-template-columns:minmax(330px,.82fr) minmax(520px,1.4fr);align-items:start}.panel-body{padding:16px}.upload-body :deep(.el-date-editor){width:100%}.upload-body :deep(.el-upload){width:100%}.upload-body :deep(.el-upload-dragger){width:100%;padding:24px 12px}.field-help{width:100%;margin-top:6px;color:#7a8a97;font-size:12px;line-height:1.5}.preflight-button{width:100%}.batch-table{max-height:338px}.detail-header{align-items:flex-start}.detail-title-line{gap:9px}.status-guidance{padding:11px 14px;border-top:1px solid #edf0f3;background:#f8fafc;color:#526473;font-size:13px;line-height:1.6}.migration-kpis{grid-template-columns:repeat(4,minmax(0,1fr))}.kpi-text{overflow:hidden;font-size:18px;text-overflow:ellipsis;white-space:nowrap}.result-panel{padding:0 14px 14px}.tab-alert{margin-bottom:12px}.audit-timeline{max-width:920px;padding:12px 8px 0}.event-title{font-weight:650}.event-reason{margin-top:5px;line-height:1.6}.safeguard-panel ul{display:grid;gap:8px;margin:0;padding:14px 38px 18px;color:#526473;line-height:1.65}.amount-danger{color:var(--el-color-danger);font-weight:650}.multiline-secondary{overflow:hidden;margin-top:3px;color:#7a8a97;font-size:12px;text-overflow:ellipsis;white-space:nowrap}.batch-panel :deep(.el-table__row){cursor:pointer}
@media(max-width:1100px){.step-grid,.migration-kpis{grid-template-columns:repeat(2,minmax(0,1fr))}.migration-work-grid{grid-template-columns:1fr}}
@media(max-width:700px){.hero-panel,.detail-header{align-items:flex-start;flex-direction:column}.hero-actions,.detail-actions{width:100%}.step-grid,.migration-kpis{grid-template-columns:1fr}.hero-actions .el-button,.detail-actions .el-button{margin-left:0}.result-panel{padding-right:8px;padding-left:8px}}
</style>
