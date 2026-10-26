<template>
  <el-dialog
    :model-value="modelValue"
    title="拍照识别宏泰包装标签"
    width="1080px"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
  >
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="只识别标签可见文字，不识别二维码。结果是待核对候选，不会自动新增商品、入库、出库或修改库存。"
    />
    <div class="recognition-toolbar">
      <div>
        <strong>当前商品：</strong>{{ expectedProductLabel || `商品 #${expectedProductId}` }}
        <div class="muted">用“产品名称 + 规格”核对当前商品；不一致时不能套用。</div>
      </div>
      <div>
        <input
          ref="fileInput"
          class="native-file-input"
          type="file"
          accept="image/*"
          capture="environment"
          multiple
          @change="selectImages"
        >
        <el-button type="primary" plain :loading="recognizing" @click="fileInput?.click()">
          拍照 / 选择多个标签
        </el-button>
      </div>
    </div>

    <el-empty v-if="!rows.length" description="每个盘、卷或箱拍一张清晰正面标签照片" :image-size="84" />
    <el-table v-else :data="rows" border size="small" max-height="520" class="recognition-table">
      <el-table-column label="#" type="index" width="48" />
      <el-table-column label="照片" width="92">
        <template #default="{ row }">
          <el-image
            class="label-preview"
            :src="row.previewUrl"
            :preview-src-list="[row.previewUrl]"
            fit="cover"
            preview-teleported
          />
        </template>
      </el-table-column>
      <el-table-column label="商品核对" min-width="210">
        <template #default="{ row }">
          <el-tag :type="matchTagType(row.productMatchStatus)" size="small">
            {{ matchStatusText(row.productMatchStatus) }}
          </el-tag>
          <el-tag v-if="isConfirmedBatch(row)" type="info" size="small">复用已确认结果</el-tag>
          <div class="candidate-main">{{ row.labelProductName || '产品名未识别' }}</div>
          <div class="muted">{{ row.labelSpecification || '规格未识别' }}</div>
          <template v-if="!isProductMatched(row) && !isConfirmedBatch(row)">
            <div class="text-danger">{{ row.productMatchReason }}</div>
            <el-button link type="primary" @click="confirmCurrentProduct(row)">
              对照原图后确认是当前商品
            </el-button>
          </template>
        </template>
      </el-table-column>
      <el-table-column min-width="170">
        <template #header>包装卷号 / 序号 <span class="table-required-mark">*</span></template>
        <template #default="{ row }">
          <el-input v-model="row.packageCode" :disabled="isConfirmedBatch(row)" maxlength="128" placeholder="请按标签核对" />
        </template>
      </el-table-column>
      <el-table-column width="150">
        <template #header>实际重量 kg <span class="table-required-mark">*</span></template>
        <template #default="{ row }">
          <el-input-number
            v-model="row.actualWeightKg"
            :min="0.000001"
            :precision="6"
            :controls="false"
            :disabled="isConfirmedBatch(row)"
            style="width: 100%"
          />
        </template>
      </el-table-column>
      <el-table-column label="生产日期" width="155">
        <template #default="{ row }">
          <el-date-picker v-model="row.productionDate" :disabled="isConfirmedBatch(row)" value-format="YYYY-MM-DD" style="width: 100%" />
        </template>
      </el-table-column>
      <el-table-column label="置信度" width="88" align="center">
        <template #default="{ row }">
          <el-tag :type="row.confidence >= .8 ? 'success' : 'warning'" size="small">
            {{ Math.round(row.confidence * 100) }}%
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="70" align="center">
        <template #default="{ $index }">
          <el-button link type="danger" @click="removeRow($index)">移除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button
        type="primary"
        :loading="confirming"
        :disabled="recognizing || !rows.length"
        @click="confirmCandidates"
      >
        人工确认并套用 {{ rows.length }} 个标签
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { roundQuantity, sameQuantity, kilogramsToBaseQuantity } from '@/utils/quantity'
import { onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  api,
  type DocumentIntakeBatch,
  type DocumentIntakeCandidate,
} from '@/api/client'
import type {
  PackageLabelOcrResult,
  PackageLabelProductMatchStatus,
} from '@/types/packageLabelOcr'

type RecognitionRow = PackageLabelOcrResult & {
  batch: DocumentIntakeBatch
  previewUrl: string
}

const props = defineProps<{
  modelValue: boolean
  expectedProductId: number
  expectedProductLabel: string
  requireSameProductionDate?: boolean
  existingProductionDate?: string
  inventoryPackages?: Array<{
    packageCode: string
    actualBaseQuantity: number
    baseUnit: string
  }>
}>()

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: [rows: PackageLabelOcrResult[]]
}>()

const fileInput = ref<HTMLInputElement>()
const recognizing = ref(false)
const confirming = ref(false)
const rows = ref<RecognitionRow[]>([])

async function selectImages(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = ''
  if (!files.length) return
  recognizing.value = true
  let recognized = 0
  for (const file of files) {
    try {
      const batch = await api.createDocumentIntake(
        'PACKAGE_LABEL',
        file,
        undefined,
        props.expectedProductId,
      )
      if (rows.value.some((row) => row.batch.id === batch.id)) {
        ElMessage.info(`${file.name} 已在当前待核对列表中`)
        continue
      }
      if (batch.status === 'REJECTED' || batch.status === 'FAILED') {
        ElMessage.error(
          `${file.name} 的原识别记录${batch.status === 'REJECTED' ? '已驳回' : '失败'}，`
          + '为保留审计记录，请重新拍摄一张清晰标签照片',
        )
        continue
      }
      const recognitionRow = toRecognitionRow(batch, file)
      if (batch.status === 'CONFIRMED'
        && recognitionRow.matchedProductId !== props.expectedProductId) {
        ElMessage.error(`${file.name} 的原识别结果已确认给其他商品，不能用于当前商品`)
        URL.revokeObjectURL(recognitionRow.previewUrl)
        continue
      }
      rows.value.push(recognitionRow)
      recognized += 1
    } catch (error) {
      ElMessage.error(`${file.name}：${requestErrorMessage(error)}`)
    }
  }
  recognizing.value = false
  if (recognized) ElMessage.success(`已载入 ${recognized} 个标签候选；重复文件直接复用原识别记录`)
}

function toRecognitionRow(batch: DocumentIntakeBatch, file: File): RecognitionRow {
  const values = new Map(batch.candidates.map((item) => [
    item.fieldCode,
    item.confirmedValue?.trim() || item.candidateValue,
  ]))
  const confidenceCandidates = batch.candidates.filter((item) =>
    ['product_name', 'specification', 'actual_weight_kg', 'production_date', 'package_code']
      .includes(item.fieldCode) && item.confidence > 0)
  const confidence = confidenceCandidates.length
    ? confidenceCandidates.reduce((sum, item) => sum + Number(item.confidence), 0) / confidenceCandidates.length
    : 0
  return {
    intakeId: batch.id,
    originalFileName: batch.originalFileName,
    packageCode: values.get('package_code') || '',
    actualWeightKg: roundQuantity(positiveNumber(values.get('actual_weight_kg')) || 0),
    productionDate: values.get('production_date') || '',
    labelProductName: values.get('product_name') || '',
    labelSpecification: values.get('specification') || '',
    matchedProductId: positiveNumber(values.get('matched_product_id')),
    matchedProductSku: values.get('matched_product_sku') || '',
    productMatchStatus: (values.get('product_match_status') || 'INCOMPLETE') as PackageLabelProductMatchStatus,
    productMatchReason: values.get('product_match_reason') || '请人工核对商品',
    confidence,
    batch,
    previewUrl: URL.createObjectURL(file),
  }
}

async function confirmCandidates() {
  const invalidMatch = rows.value.find((row) => !isProductMatched(row)
    || row.matchedProductId !== props.expectedProductId)
  if (invalidMatch) {
    ElMessage.warning(`${invalidMatch.originalFileName} 与当前商品不一致，请移除或重拍`)
    return
  }
  const invalidCode = rows.value.find((row) => !row.packageCode.trim())
  if (invalidCode) {
    ElMessage.warning(`${invalidCode.originalFileName} 未填写包装卷号/序号`)
    return
  }
  const codes = rows.value.map((row) => row.packageCode.trim())
  if (new Set(codes).size !== codes.length) {
    ElMessage.warning('本次识别的包装卷号/序号不能重复')
    return
  }
  const invalidWeight = rows.value.find((row) =>
    !Number.isFinite(Number(row.actualWeightKg)) || Number(row.actualWeightKg) <= 0)
  if (invalidWeight) {
    ElMessage.warning(`${invalidWeight.originalFileName} 的实际重量不正确`)
    return
  }
  if (props.requireSameProductionDate) {
    const recognizedDates = [...new Set(rows.value.map((row) => row.productionDate).filter(Boolean))]
    if (recognizedDates.length > 1) {
      ElMessage.warning('这些标签的生产日期不同，请拆成多条入库明细后分别识别')
      return
    }
    if (props.existingProductionDate && recognizedDates.length
      && recognizedDates[0] !== props.existingProductionDate) {
      ElMessage.warning(`标签生产日期 ${recognizedDates[0]} 与当前明细 ${props.existingProductionDate} 不一致`)
      return
    }
  }
  if (props.inventoryPackages?.length) {
    for (const row of rows.value) {
      const inventory = props.inventoryPackages.find((item) =>
        normalizedPackageCode(item.packageCode) === normalizedPackageCode(row.packageCode))
      if (!inventory) {
        ElMessage.warning(`包装编号 ${row.packageCode} 不在当前批次的可用库存中`)
        return
      }
      let recognizedBaseQuantity: number
      try {
        recognizedBaseQuantity = kilogramsToBaseQuantity(row.actualWeightKg, inventory.baseUnit)
      } catch (error) {
        ElMessage.warning(error instanceof Error ? error.message : '标签重量单位无法换算')
        return
      }
      if (!sameQuantity(inventory.actualBaseQuantity, recognizedBaseQuantity)) {
        ElMessage.warning(
          `${row.packageCode} 标签重量 ${String(roundQuantity(row.actualWeightKg))}kg`
          + ` 与库存记录 ${String(roundQuantity(inventory.actualBaseQuantity))}${inventory.baseUnit} 不一致`,
        )
        return
      }
    }
  }
  confirming.value = true
  try {
    for (const row of rows.value) {
      if (row.batch.status === 'CONFIRMED') continue
      await api.reviewDocumentIntake(row.batch.id, {
        decision: 'CONFIRMED',
        note: '在收发货标签识别页对照原图人工确认；尚未形成业务或库存事实',
        candidates: row.batch.candidates.map((candidate) => ({
          fieldCode: candidate.fieldCode,
          confirmedValue: confirmedCandidateValue(row, candidate),
        })),
      })
    }
    emit('confirm', rows.value.map(({ batch: _batch, previewUrl: _previewUrl, ...row }) => ({ ...row })))
    releasePreviewUrls()
    rows.value = []
    emit('update:modelValue', false)
  } finally {
    confirming.value = false
  }
}

function confirmedCandidateValue(row: RecognitionRow, candidate: DocumentIntakeCandidate) {
  const override: Record<string, string> = {
    package_code: row.packageCode.trim(),
    actual_weight_kg: String(roundQuantity(row.actualWeightKg)),
    production_date: row.productionDate || '',
    product_name: row.labelProductName.trim(),
    specification: row.labelSpecification.trim(),
    product_match_status: row.productMatchStatus,
    product_match_reason: row.productMatchReason,
    matched_product_id: row.matchedProductId ? String(row.matchedProductId) : '',
  }
  return Object.prototype.hasOwnProperty.call(override, candidate.fieldCode)
    ? override[candidate.fieldCode]
    : candidate.candidateValue
}

function removeRow(index: number) {
  const [removed] = rows.value.splice(index, 1)
  if (removed) URL.revokeObjectURL(removed.previewUrl)
}

function releasePreviewUrls() {
  rows.value.forEach((row) => URL.revokeObjectURL(row.previewUrl))
}

function positiveNumber(value?: string) {
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined
}

function normalizedPackageCode(value: string) {
  return value.trim().toUpperCase()
}

function confirmCurrentProduct(row: RecognitionRow) {
  row.productMatchStatus = 'MANUALLY_MATCHED'
  row.productMatchReason = '操作人已对照原图和当前商品名称、规格后人工确认'
  row.matchedProductId = props.expectedProductId
}

function isProductMatched(row: RecognitionRow) {
  return ['MATCHED', 'MANUALLY_MATCHED'].includes(row.productMatchStatus)
}

function isConfirmedBatch(row: RecognitionRow) {
  return row.batch.status === 'CONFIRMED'
}

function matchStatusText(value: PackageLabelProductMatchStatus) {
  return ({
    MATCHED: '与当前商品一致',
    MANUALLY_MATCHED: '已人工核对商品',
    MISMATCH: '商品不一致',
    INCOMPLETE: '标签字段不完整',
    AMBIGUOUS: '匹配到多个商品',
    NOT_FOUND: '未匹配商品',
  } as Record<string, string>)[value] || value
}

function matchTagType(value: PackageLabelProductMatchStatus) {
  return ['MATCHED', 'MANUALLY_MATCHED'].includes(value)
    ? 'success'
    : value === 'INCOMPLETE' ? 'warning' : 'danger'
}

function requestErrorMessage(error: unknown) {
  const message = (error as { response?: { data?: { message?: string } } })?.response?.data?.message
  return message || (error instanceof Error ? error.message : '标签识别失败')
}

onBeforeUnmount(releasePreviewUrls)
</script>

<style scoped>
.recognition-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin: 14px 0;
}

.native-file-input {
  display: none;
}

.label-preview {
  width: 62px;
  height: 62px;
  border-radius: 4px;
  cursor: zoom-in;
}

.candidate-main {
  margin-top: 6px;
  font-weight: 600;
}

.recognition-table :deep(.el-input-number) {
  width: 100%;
}

@media (max-width: 800px) {
  .recognition-toolbar {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
