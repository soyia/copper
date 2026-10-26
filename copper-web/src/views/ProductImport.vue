<template>
  <div class="page product-import-page">
    <PageHeader title="商品批量导入" back-to="/products" back-label="返回商品资料" />
    <el-alert
      v-if="!canManageComplex"
      type="info"
      :closable="false"
      title="当前账号只有复杂商品主数据查看权限，可查看历史导入审计，但不能上传、决策或提交。"
    />
    <el-alert
      v-else-if="!canWriteProducts"
      type="warning"
      :closable="false"
      title="当前账号可以上传、预检、记录逐行决定并完成“全部跳过”的审计任务；没有商品维护权限时，不能提交包含新增或安全补充的任务。"
    />

    <div class="panel">
      <div class="panel-body">
        <el-steps :active="activeStep" finish-status="success" align-center>
          <el-step title="选择文件" description="CSV / Excel，不直接入正式表" />
          <el-step title="字段映射" description="核对固定字段与动态规格字段" />
          <el-step title="规范化与预览" description="校验单位、枚举、签名和重复商品" />
          <el-step title="提交与审计" description="确认动作后一次提交并保留结果" />
        </el-steps>
      </div>
    </div>

    <div v-if="!preview" class="panel import-start-panel">
      <div class="panel-header"><div class="panel-title">建立导入暂存任务</div></div>
      <div class="panel-body import-upload-grid">
        <div class="series-template-card">
          <div>
            <strong>先选择产品系列，再下载专用模板</strong>
            <span>每个系列都提供 Excel 和 CSV；只保留日常建档字段及该系列经营规格，并附两行填写示例。</span>
          </div>
          <el-select v-model="templateSeriesId" filterable clearable placeholder="选择产品系列" style="width: 280px">
            <el-option
              v-for="series in enabledTemplateSeries"
              :key="series.id"
              :label="`${series.categoryName} / ${series.name}`"
              :value="series.id"
            />
          </el-select>
          <div class="series-template-actions">
            <el-button
              type="primary"
              :disabled="!templateSeriesId"
              :loading="templateDownloading === 'xlsx'"
              @click="downloadSeriesTemplate('xlsx')"
            >
              下载 Excel
            </el-button>
            <el-button
              :disabled="!templateSeriesId"
              :loading="templateDownloading === 'csv'"
              @click="downloadSeriesTemplate('csv')"
            >
              下载 CSV
            </el-button>
          </div>
        </div>
        <el-upload
          drag
          :auto-upload="false"
          :disabled="!canManageComplex"
          :limit="1"
          accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
          :on-change="selectFile"
          :on-remove="resetPreview"
        >
          <el-icon class="upload-icon"><UploadFilled /></el-icon>
          <div class="el-upload__text">拖入或点击选择 CSV / Excel</div>
          <template #tip>
            <div class="el-upload__tip">文件只进入暂存区。未知系列、字段、单位或枚举不会被静默创建。</div>
          </template>
        </el-upload>
        <div class="import-guardrails">
          <strong>导入安全规则</strong>
          <ul>
            <li>完全重复商品默认跳过，不会重复建档或产生两份库存。</li>
            <li>相同 SKU、不同规格签名一律作为冲突阻断。</li>
            <li>安全更新仅补充空白主数据，不覆盖已确认业务事实。</li>
            <li>无效行和未解决冲突不会进入正式商品表。</li>
            <li>只接受静态单元格；CSV/Excel 公式会被拒绝，负数请填写普通十进制数值。</li>
          </ul>
          <el-button type="primary" :loading="previewing" :disabled="!canManageComplex || !selectedFile" @click="runPreview()">
            上传并预检
          </el-button>
        </div>
      </div>
    </div>

    <template v-else>
      <div class="import-summary-grid">
        <button
          v-for="item in summaryCards"
          :key="item.key"
          type="button"
          class="import-summary-card"
          :class="{ active: resultFilter === item.key }"
          @click="resultFilter = resultFilter === item.key ? '' : item.key"
        >
          <span>{{ item.label }}</span>
          <strong>{{ item.count }}</strong>
          <small>{{ item.description }}</small>
        </button>
      </div>

      <div class="panel mapping-panel">
        <div class="panel-header">
          <div>
            <div class="panel-title">字段映射与任务信息</div>
            <div class="muted">{{ preview.job.sourceFileName }} · 模板 {{ preview.job.templateVersion || templateInfo?.templateVersion || '-' }} · 任务 {{ preview.job.jobNo }}</div>
          </div>
          <div class="product-import-actions">
            <el-button @click="resetPreview">{{ selectedFile ? '换一个文件' : '返回作业列表' }}</el-button>
            <el-button @click="downloadImportSource(preview.job.id, preview.job.sourceFileName)">下载原始文件</el-button>
            <el-button :loading="previewing" :disabled="!canManageComplex || !selectedFile" @click="runPreview(mapping)">应用映射并重新预检</el-button>
          </div>
        </div>
        <el-collapse>
          <el-collapse-item title="查看或调整字段映射" name="mapping">
            <div class="mapping-grid">
              <div v-for="field in mappableFields" :key="field.key" class="mapping-item">
                <div>
                  <span>{{ field.label }}</span>
                  <el-tag v-if="field.required" size="small" type="danger" effect="plain">必填</el-tag>
                </div>
                <el-select v-model="mapping[field.key]" :disabled="!canManageComplex" clearable filterable allow-create placeholder="自动识别或选择源列">
                  <el-option v-for="column in sourceColumns" :key="column" :label="column" :value="column" />
                </el-select>
                <small>{{ field.description || field.key }}</small>
              </div>
            </div>
          </el-collapse-item>
        </el-collapse>
      </div>

      <div class="panel">
        <div class="panel-header">
          <div>
            <div class="panel-title">逐行处理</div>
            <div class="muted">规范化结果、重复匹配和用户处理决定同时可见；修改决定后立即写入导入审计。</div>
          </div>
          <div class="product-import-actions">
            <el-select v-model="resultFilter" clearable placeholder="全部结果">
              <el-option v-for="item in summaryCards" :key="item.key" :label="item.label" :value="item.key" />
            </el-select>
            <el-button v-if="recoveringCommit" :loading="previewing" @click="reloadCurrentJob">刷新作业状态</el-button>
            <el-button type="primary" :loading="committing" :disabled="!canSubmitImport || !canCommit" @click="confirmCommit">
              {{ recoveringCommit ? (preview.job.recoverable ? '尝试恢复提交' : '提交执行中') : `完成处理 ${completableCount} 行（写入 ${committableCount}，跳过 ${skipCount}）` }}
            </el-button>
          </div>
        </div>
        <el-alert
          v-if="recoveringCommit"
          type="info"
          :closable="false"
          :title="preview.job.recoverable ? '上次提交租约已过期，可以尝试恢复' : '提交正在由另一执行者处理'"
          :description="preview.job.recoverable
            ? '点击“尝试恢复提交”后，后端将接管未完成行；已成功行和已跳过行不会重复写入。'
            : `当前租约仍有效${preview.job.leaseExpiresAt ? `，保护至 ${preview.job.leaseExpiresAt}` : ''}。请刷新状态；系统不会启动第二个提交执行者。`"
          class="commit-alert"
        />
        <el-alert
          v-else-if="!canCommit"
          type="warning"
          :closable="false"
          title="存在无效行或未解决冲突"
          description="请修改原文件后重新预检，或将不写入正式商品的无效/重复行设置为跳过并说明原因。系统不会带病提交。"
          class="commit-alert"
        />
        <el-table :data="pagedRows" border size="small" max-height="calc(100vh - 390px)" row-key="id">
          <el-table-column prop="sourceRowNo" label="源行" width="72" fixed="left" />
          <el-table-column label="结果" width="112">
            <template #default="{ row }"><el-tag :type="resultTagType(rowResult(row))">{{ resultLabel(rowResult(row)) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="源数据" min-width="260">
            <template #default="{ row }"><div class="data-summary">{{ recordSummary(row.rawData) }}</div></template>
          </el-table-column>
          <el-table-column label="规范化数据" min-width="320">
            <template #default="{ row }">
              <div class="normalized-cell">
                <strong>{{ normalizedTitle(row) }}</strong>
                <span>{{ recordSummary(row.normalizedData) }}</span>
                <small v-if="row.productSignature">签名：{{ row.productSignature }}</small>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="匹配商品" min-width="170">
            <template #default="{ row }">
              <el-link v-if="row.importedProductId" type="primary" underline="never" @click="router.push(`/products/${row.importedProductId}`)">
                {{ rowResult(row) === 'CREATE_PENDING' ? '补齐并发布待复核商品' : '查看已导入商品' }}
              </el-link>
              <span v-else-if="row.targetProductSku">{{ row.targetProductSku }}</span>
              <span v-else class="muted">未匹配</span>
            </template>
          </el-table-column>
          <el-table-column label="原因 / 校验信息" min-width="250">
            <template #default="{ row }">
              <div>{{ row.validationMessage || row.decisionReason || row.commitError || '-' }}</div>
              <ul v-if="rowConflicts(row).length" class="row-conflicts">
                <li v-for="item in rowConflicts(row)" :key="item.id">{{ item.message }}</li>
              </ul>
            </template>
          </el-table-column>
          <el-table-column label="处理决定" width="180" fixed="right">
            <template #default="{ row }">
              <el-select
                v-model="row.decidedAction"
                :disabled="!canManageComplex || savingRowId === row.id || ['COMPLETED', 'COMMITTING'].includes(preview.job.status)"
                @change="changeDecision(row)"
              >
                <el-option label="新增并发布" value="CREATE" :disabled="row.validationStatus === 'INVALID' || row.proposedAction !== 'CREATE'" />
                <el-option label="新增为待复核" value="CREATE_PENDING" :disabled="row.validationStatus === 'INVALID' || row.proposedAction !== 'CREATE_PENDING'" />
                <el-option label="安全补充" value="SAFE_UPDATE" :disabled="row.validationStatus === 'INVALID' || !row.targetProductId || row.proposedAction !== 'SAFE_UPDATE'" />
                <el-option label="跳过此行" value="SKIP" />
                <el-option label="保持冲突" value="CONFLICT" :disabled="row.validationStatus === 'INVALID' || row.proposedAction !== 'CONFLICT'" />
              </el-select>
            </template>
          </el-table-column>
        </el-table>
        <div class="import-preview-pagination">
          <span class="muted">
            当前筛选 {{ filteredRows.length }} 行；分页只影响预览显示，汇总、校验和提交仍按本任务全部 {{ preview.rows.length }} 行计算。
          </span>
          <el-pagination
            v-model:current-page="previewPage"
            v-model:page-size="previewPageSize"
            :page-sizes="PREVIEW_PAGE_SIZES"
            :total="filteredRows.length"
            layout="total, sizes, prev, pager, next, jumper"
            background
          />
        </div>
      </div>
    </template>

    <div class="panel">
      <div class="panel-header">
        <div>
          <div class="panel-title">历史导入审计</div>
          <div class="muted">预检、决定、提交和失败结果均保留，不能用再次上传覆盖原任务。</div>
        </div>
        <el-button @click="refreshHistory">刷新</el-button>
      </div>
      <el-table :data="importJobs ?? []" border size="small">
        <el-table-column prop="id" label="任务" width="80" />
        <el-table-column prop="sourceFileName" label="文件" min-width="220" />
        <el-table-column prop="status" label="状态" width="120" />
        <el-table-column label="新增/补充/跳过/冲突/无效" min-width="230">
          <template #default="{ row }">{{ row.createRows }}/{{ row.safeUpdateRows }}/{{ row.skipRows }}/{{ row.conflictRows }}/{{ row.invalidRows }}</template>
        </el-table-column>
        <el-table-column prop="committedRows" label="已提交" width="100" />
        <el-table-column prop="importedByName" label="操作人" width="120" />
        <el-table-column prop="createdAt" label="创建时间" min-width="170" />
        <el-table-column label="操作" width="170">
          <template #default="{ row }">
            <el-button text type="primary" @click="openImportJob(row.id)">查看</el-button>
            <el-button text @click="downloadImportSource(row.id, row.sourceFileName)">原文件</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { UploadFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type UploadFile } from 'element-plus'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import { useAppStore } from '@/stores/app'
import {
  api,
  type ProductImportAction,
  type ProductImportPreview,
  type ProductImportRow,
  type ProductImportRowResult,
} from '@/api/client'

const queryClient = useQueryClient()
const router = useRouter()
const appStore = useAppStore()
const canManageComplex = computed(() => appStore.hasPermission('product.master.complex.manage'))
const canWriteProducts = computed(() => appStore.hasPermission('product.manage'))
const PREVIEW_PAGE_SIZES = [50, 100, 200]
const activeStep = ref(0)
const selectedFile = ref<File>()
const previewing = ref(false)
const committing = ref(false)
const savingRowId = ref<number>()
const resultFilter = ref<ProductImportRowResult | ''>('')
const previewPage = ref(1)
const previewPageSize = ref(50)
const preview = ref<ProductImportPreview>()
const mapping = reactive<Record<string, string>>({})
const templateSeriesId = ref<number>()
const templateDownloading = ref<'xlsx' | 'csv'>()

const { data: templateInfo } = useQuery({ queryKey: ['product-import-template-info'], queryFn: api.productImportTemplateInfo })
const { data: importJobs } = useQuery({ queryKey: ['product-import-jobs'], queryFn: api.productImports })
const enabledTemplateSeries = computed(() => (templateInfo.value?.series ?? []).filter((item) => item.status === 'ENABLED'))

const mappableFields = computed(() => {
  const templateColumns = (templateInfo.value?.columns ?? []).filter((field) => field.key !== 'field:<seriesFieldId>')
  const dynamicFields = (templateInfo.value?.series ?? []).flatMap((item) => item.fields).map((field) => ({
    key: field.key,
    label: `${field.displayName}（${field.name}）`,
    required: field.required,
    description: `${field.fieldType}${field.unitName ? ` / ${field.unitName}` : ''}`,
  }))
  return [...new Map([...templateColumns, ...dynamicFields].map((field) => [field.key, field])).values()]
})
const sourceColumns = computed(() => Object.keys(preview.value?.rows[0]?.rawData ?? {}))
const summaryCards = computed(() => {
  const rows = preview.value?.rows ?? []
  const count = (result: ProductImportRowResult) => rows.filter((row) => rowResult(row) === result).length
  return [
    { key: 'CREATE' as const, label: '新增并可经营', count: count('CREATE'), description: '必填规格完整的新 SKU' },
    { key: 'CREATE_PENDING' as const, label: '新增待复核', count: count('CREATE_PENDING'), description: '补结构后再发布' },
    { key: 'SAFE_UPDATE' as const, label: '安全补充', count: count('SAFE_UPDATE'), description: '只补空白资料' },
    { key: 'SKIP' as const, label: '跳过', count: count('SKIP'), description: '保留审计不写入' },
    { key: 'CONFLICT' as const, label: '冲突', count: count('CONFLICT'), description: '需要人工决定' },
    { key: 'INVALID' as const, label: '无效', count: count('INVALID'), description: '源数据必须修正' },
  ]
})
const filteredRows = computed(() => {
  const rows = preview.value?.rows ?? []
  return resultFilter.value ? rows.filter((row) => rowResult(row) === resultFilter.value) : rows
})
const pagedRows = computed(() => {
  const start = (previewPage.value - 1) * previewPageSize.value
  return filteredRows.value.slice(start, start + previewPageSize.value)
})
const committableCount = computed(() => (preview.value?.rows ?? []).filter((row) =>
  ['CREATE', 'CREATE_PENDING', 'SAFE_UPDATE'].includes(row.decidedAction ?? row.proposedAction),
).length)
const skipCount = computed(() => (preview.value?.rows ?? []).filter((row) =>
  (row.decidedAction ?? row.proposedAction) === 'SKIP',
).length)
const completableCount = computed(() => committableCount.value + skipCount.value)
const canSubmitImport = computed(() =>
  canManageComplex.value && (!committableCount.value || canWriteProducts.value),
)
const recoveringCommit = computed(() => preview.value?.job.status === 'COMMITTING')
const canCommit = computed(() => {
  if (!preview.value) return false
  if (preview.value.job.status === 'COMMITTING') return Boolean(preview.value.job.recoverable)
  if (preview.value.job.status === 'COMPLETED' || !completableCount.value) return false
  return !preview.value.rows.some((row) =>
    rowResult(row) === 'INVALID'
    || rowResult(row) === 'CONFLICT'
    || (rowResult(row) === 'SKIP'
      && (row.decisionReason || row.validationMessage).trim().length < 4),
  )
})

watch(resultFilter, () => {
  previewPage.value = 1
})
watch(() => preview.value?.job.id, () => {
  previewPage.value = 1
})
watch(previewPageSize, () => {
  previewPage.value = 1
})
watch(() => filteredRows.value.length, (total) => {
  const lastPage = Math.max(1, Math.ceil(total / previewPageSize.value))
  if (previewPage.value > lastPage) previewPage.value = lastPage
})

function selectFile(uploadFile: UploadFile) {
  selectedFile.value = uploadFile.raw
  activeStep.value = selectedFile.value ? 1 : 0
}

function resetPreview() {
  selectedFile.value = undefined
  preview.value = undefined
  activeStep.value = 0
  resultFilter.value = ''
  Object.keys(mapping).forEach((key) => delete mapping[key])
}

async function runPreview(fieldMapping?: Record<string, string>) {
  if (!canManageComplex.value || !selectedFile.value) return
  previewing.value = true
  try {
    const result = await api.previewProductImport(selectedFile.value, fieldMapping)
    preview.value = result
    Object.assign(mapping, result.job.mapping ?? {})
    activeStep.value = 2
    ElMessage.success(`预检完成：读取 ${result.rows.length} 行，未写入正式商品`)
    await refreshHistory()
  } catch (error) {
    ElMessage.error(errorMessage(error, '导入预检失败'))
  } finally {
    previewing.value = false
  }
}

async function saveDecision(row: ProductImportRow) {
  if (!canManageComplex.value || !preview.value) return
  savingRowId.value = row.id
  try {
    preview.value = await api.decideProductImportRow(preview.value.job.id, row.id, {
      action: row.decidedAction ?? row.proposedAction,
      targetProductId: row.targetProductId,
      reason: row.decisionReason,
      expectedRowVersion: row.rowVersion,
    })
  } catch (error) {
    ElMessage.error(errorMessage(error, '处理决定保存失败'))
    await reloadCurrentJob()
  } finally {
    savingRowId.value = undefined
  }
}

async function changeDecision(row: ProductImportRow) {
  if (row.decidedAction === 'SKIP') {
    try {
      const { value } = await ElMessageBox.prompt(
        row.validationStatus === 'INVALID'
          ? '该无效行不会写入正式商品。请记录跳过原因，便于后续审计和重新导入。'
          : '该行不会写入正式商品。请记录业务原因，避免正常商品被无痕遗漏。',
        `跳过源行 ${row.sourceRowNo}`,
        {
          inputPlaceholder: '例如：供应商文件中的表头说明行，不是商品数据',
          inputValidator: (text) => text.trim().length >= 4 || '请填写至少4个字的跳过原因',
          confirmButtonText: '确认跳过',
          cancelButtonText: '返回',
        },
      )
      row.decisionReason = value.trim()
    } catch {
      row.decidedAction = row.proposedAction
      return
    }
  }
  await saveDecision(row)
}

async function confirmCommit() {
  if (!canSubmitImport.value || !preview.value || !canCommit.value) return
  const recovery = recoveringCommit.value
  await ElMessageBox.confirm(
    recovery
      ? '系统将检查上次提交租约。仍在正常执行时不会重复启动；只有租约已过期才接管未完成行，已成功行保持不变。是否尝试恢复？'
      : `本次共处理 ${completableCount.value} 行：写入可经营商品 ${committableCount.value} 行，保留审计并跳过 ${skipCount.value} 行。必填规格缺项只会保存为待补资料，不能直接用于业务，是否继续？`,
    recovery ? '恢复中断的商品导入' : (committableCount.value ? '确认写入并完成导入' : '确认完成全跳过任务'),
    { type: 'warning', confirmButtonText: recovery ? '尝试恢复' : '确认完成', cancelButtonText: '返回检查' },
  )
  committing.value = true
  try {
    const result = await api.commitProductImport(preview.value.job.id, {
      expectedLockVersion: preview.value.job.lockVersion,
      allowSafeUpdates: true,
    })
    preview.value = await api.productImport(result.jobId)
    const pendingCreated = preview.value.rows.filter((row) =>
      rowResult(row) === 'CREATE_PENDING' && row.commitStatus === 'SUCCEEDED',
    ).length
    activeStep.value = 3
    const summary = `新增 ${result.createdRows} 行（其中待复核 ${pendingCreated} 行），安全补充 ${result.updatedRows} 行，跳过 ${result.skippedRows} 行`
    if (result.failedRows) {
      ElMessage.warning(`导入已部分完成：${summary}，另有 ${result.failedRows} 行失败；成功行已保留，请按逐行错误重试失败行`)
    } else {
      ElMessage.success(`导入提交完成：${summary}`)
    }
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: ['products'] }),
      refreshHistory(),
    ])
  } catch (error) {
    const message = errorMessage(error, '导入提交发生异常；已成功行会保留，失败行会记录原因并可按原任务重试，请查看逐行结果')
    if (recovery && /正在|处理中|租约|执行者/.test(message)) {
      ElMessage.info(`${message}；稍后可再次打开任务检查状态`)
    } else {
      ElMessage.error(message)
    }
    await reloadCurrentJob()
  } finally {
    committing.value = false
  }
}

async function reloadCurrentJob() {
  if (!preview.value) return
  preview.value = await api.productImport(preview.value.job.id)
}

async function openImportJob(id: number) {
  try {
    preview.value = await api.productImport(id)
    selectedFile.value = undefined
    activeStep.value = preview.value.job.status.startsWith('COMPLETED') ? 3 : 2
    window.scrollTo({ top: 0, behavior: 'smooth' })
  } catch (error) {
    ElMessage.error(errorMessage(error, '导入审计读取失败'))
  }
}

async function refreshHistory() {
  await queryClient.invalidateQueries({ queryKey: ['product-import-jobs'] })
}

async function downloadImportSource(jobId: number, fileName: string) {
  try {
    const blob = await api.productImportSourceFile(jobId)
    triggerDownload(blob, fileName || `商品导入原文件-${jobId}`)
  } catch (error) {
    ElMessage.error(errorMessage(error, '原始导入文件下载失败'))
  }
}

async function downloadSeriesTemplate(format: 'xlsx' | 'csv') {
  const selected = enabledTemplateSeries.value.find((item) => item.id === templateSeriesId.value)
  if (!selected) {
    ElMessage.warning('请先选择产品系列')
    return
  }
  templateDownloading.value = format
  try {
    const blob = await api.productImportTemplate(format, selected.id)
    triggerDownload(
      blob,
      `商品导入模板-${safeFilePart(selected.categoryName)}-${safeFilePart(selected.name)}.${format}`,
    )
  } catch (error) {
    ElMessage.error(errorMessage(error, `${format.toUpperCase()} 模板下载失败`))
  } finally {
    templateDownloading.value = undefined
  }
}

function safeFilePart(value: string) {
  return value.replace(/[\\/:*?"<>|]/g, '-').trim() || '未命名系列'
}

function triggerDownload(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.click()
  URL.revokeObjectURL(url)
}

function rowResult(row: ProductImportRow): ProductImportRowResult {
  if (row.decidedAction === 'SKIP') return 'SKIP'
  if (row.validationStatus === 'INVALID') return 'INVALID'
  return row.decidedAction ?? row.proposedAction
}

function rowConflicts(row: ProductImportRow) {
  return (preview.value?.conflicts ?? []).filter((item) => item.rowId === row.id)
}

function resultLabel(result: ProductImportRowResult) {
  return { CREATE: '新增并发布', CREATE_PENDING: '新增待复核', SAFE_UPDATE: '安全补充', SKIP: '跳过', CONFLICT: '冲突', INVALID: '无效' }[result]
}

function resultTagType(result: ProductImportRowResult) {
  return { CREATE: 'success', CREATE_PENDING: 'warning', SAFE_UPDATE: 'primary', SKIP: 'info', CONFLICT: 'warning', INVALID: 'danger' }[result] as 'success' | 'primary' | 'info' | 'warning' | 'danger'
}

function normalizedTitle(row: ProductImportRow) {
  const value = row.normalizedData
  return String(value.name || value.productName || value.sku || `第 ${row.sourceRowNo} 行`)
}

function recordSummary(record: Record<string, unknown>) {
  const entries = Object.entries(record).filter(([, value]) => value !== '' && value !== null && value !== undefined)
  return entries.slice(0, 8).map(([key, value]) => `${key}: ${String(value)}`).join(' · ') || '-'
}

function errorMessage(error: unknown, fallback: string) {
  return (error as { response?: { data?: { message?: string } } })?.response?.data?.message
    || (error instanceof Error ? error.message : fallback)
}
</script>

<style scoped>
.product-import-page {
  display: grid;
  gap: 14px;
}

.import-start-panel {
  min-height: 330px;
}

.import-upload-grid {
  display: grid;
  grid-template-columns: minmax(360px, 1.25fr) minmax(300px, 1fr);
  gap: 28px;
  align-items: center;
  padding: 34px;
}

.series-template-card {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: minmax(320px, 1fr) auto auto;
  gap: 14px;
  align-items: center;
  padding: 14px 16px;
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 8px;
}

.series-template-card > div:first-child {
  display: grid;
  gap: 4px;
}

.series-template-card span {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.series-template-actions {
  display: flex;
  gap: 8px;
}

.series-template-actions .el-button + .el-button {
  margin-left: 0;
}

.upload-icon {
  margin-bottom: 10px;
  color: var(--el-color-primary);
  font-size: 48px;
}

.import-guardrails {
  display: grid;
  gap: 14px;
  color: var(--el-text-color-regular);
}

.import-guardrails ul {
  display: grid;
  gap: 8px;
  padding-left: 20px;
  margin: 0;
}

.import-summary-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 10px;
}

.import-summary-card {
  display: grid;
  gap: 5px;
  padding: 14px;
  color: inherit;
  text-align: left;
  background: #fff;
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  cursor: pointer;
}

.import-summary-card:hover,
.import-summary-card.active {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 1px var(--el-color-primary-light-7);
}

.import-summary-card strong {
  font-size: 24px;
}

.import-summary-card small {
  color: var(--el-text-color-secondary);
}

.mapping-panel :deep(.el-collapse) {
  border: 0;
}

.mapping-panel :deep(.el-collapse-item__header),
.mapping-panel :deep(.el-collapse-item__wrap) {
  padding: 0 14px;
}

.mapping-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  padding: 6px 0 18px;
}

.mapping-item {
  display: grid;
  gap: 6px;
}

.mapping-item .el-tag {
  margin-left: 6px;
}

.mapping-item small {
  color: var(--el-text-color-secondary);
}

.product-import-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

.product-import-actions .el-select {
  width: 150px;
}

.commit-alert {
  margin: 12px 14px;
}

.import-preview-pagination {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
  padding: 12px 14px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.data-summary,
.normalized-cell span {
  display: -webkit-box;
  overflow: hidden;
  line-height: 1.45;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
}

.normalized-cell {
  display: grid;
  gap: 4px;
}

.normalized-cell small {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-conflicts {
  padding-left: 18px;
  margin: 5px 0 0;
  color: var(--el-color-danger);
}

@media (max-width: 1000px) {
  .import-upload-grid,
  .mapping-grid,
  .series-template-card {
    grid-template-columns: 1fr;
  }

  .series-template-card .el-select {
    width: 100% !important;
  }

  .import-summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .import-preview-pagination {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
