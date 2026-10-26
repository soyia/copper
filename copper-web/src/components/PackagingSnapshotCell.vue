<template>
  <el-popover placement="left" :width="520" trigger="click">
    <template #reference>
      <el-button v-if="hasSnapshot" text type="primary" class="snapshot-link">
        {{ summaryText }}
        <el-tag :type="quantityBasis === 'ESTIMATED' ? 'warning' : 'success'" size="small">{{ quantityBasis === 'ESTIMATED' ? '预计' : '实际' }}</el-tag>
      </el-button>
      <el-tag v-else type="info" size="small">历史数据·无完整快照</el-tag>
    </template>
    <template v-if="hasSnapshot">
      <div class="snapshot-popover-title">当时采用的计量包装快照</div>
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="业务数量">{{ displayNumber(requestedQuantity) }} {{ requestedUnit || '-' }}</el-descriptions-item>
        <el-descriptions-item label="库存数量">{{ displayNumber(baseQuantity) }} {{ baseUnit || '-' }}</el-descriptions-item>
        <el-descriptions-item label="计价数量">{{ displayNumber(pricingQuantity) }} {{ priceUnit || '-' }}</el-descriptions-item>
        <el-descriptions-item label="数量口径">{{ quantityBasis === 'ESTIMATED' ? '计划参考/预计' : '实际执行' }}</el-descriptions-item>
        <el-descriptions-item label="规则来源">{{ sourceText }}</el-descriptions-item>
        <el-descriptions-item label="有效版本">{{ effectiveRevisionId ? `#${effectiveRevisionId}` : `v${productVersionNo || 0}` }}</el-descriptions-item>
        <el-descriptions-item label="包装组成" :span="2">{{ componentText || '-' }}</el-descriptions-item>
        <el-descriptions-item label="校验码" :span="2"><span class="snapshot-hash">{{ contentHash || snapshotHash || '-' }}</span></el-descriptions-item>
      </el-descriptions>
      <el-alert v-if="snapshotStatus === 'LEGACY_BACKFILL'" title="该记录为历史回填快照，只能证明原数量和单位，不能反推当时完整包装规则。" type="warning" :closable="false" style="margin-top: 8px" />
    </template>
  </el-popover>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  snapshotJson?: string
  requestedQuantity?: number
  requestedUnit?: string
  quantity?: number
  baseUnit?: string
  pricingQuantity?: number
  priceUnit?: string
  snapshotHash?: string
}>()

const snapshot = computed<Record<string, any>>(() => {
  try {
    const parsed = JSON.parse(props.snapshotJson || '{}')
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch { return {} }
})
const profile = computed(() => snapshot.value.effectiveProfile || snapshot.value.profile || {})
const policy = computed(() => snapshot.value.policy || profile.value.policy || profile.value || {})
const sourceSnapshot = computed(() => snapshot.value.source || profile.value.source || {})
const calculation = computed(() => snapshot.value.calculation || snapshot.value)
const hasSnapshot = computed(() => Boolean(props.snapshotJson && props.snapshotJson !== '{}' && Object.keys(snapshot.value).length))
const requestedQuantity = computed(() => calculation.value.requestedQuantity ?? props.requestedQuantity)
const requestedUnit = computed(() => calculation.value.requestedUnit ?? props.requestedUnit)
const baseQuantity = computed(() => calculation.value.baseQuantity ?? props.quantity)
const baseUnit = computed(() => calculation.value.baseUnit ?? policy.value.baseUnit ?? props.baseUnit)
const pricingQuantity = computed(() => calculation.value.pricingQuantity ?? props.pricingQuantity)
const priceUnit = computed(() => calculation.value.priceUnit ?? props.priceUnit)
const quantityBasis = computed(() => calculation.value.quantityBasis || snapshot.value.quantityBasis || (calculation.value.estimated ? 'ESTIMATED' : 'ACTUAL'))
const effectiveRevisionId = computed(() => profile.value.effectiveRevisionId || snapshot.value.effectiveRevisionId)
const productVersionNo = computed(() => profile.value.versionNo || snapshot.value.versionNo)
const contentHash = computed(() => profile.value.contentHash || snapshot.value.contentHash)
const snapshotHash = computed(() => props.snapshotHash)
const snapshotStatus = computed(() => snapshot.value.snapshotStatus || '')
const sourceText = computed(() => {
  const source = sourceSnapshot.value.sourceLevel || profile.value.sourceLevel || snapshot.value.sourceLevel
  const label = ({ TEMPLATE: '系列包装模板', PRODUCT_OVERRIDE: '商品特例', SYSTEM_DEFAULT: '系统默认', LEGACY: '历史迁移' } as Record<string, string>)[source] || source || '历史快照'
  const template = sourceSnapshot.value.templateName || profile.value.templateName
  const revision = sourceSnapshot.value.templateRevisionNo || profile.value.templateRevisionNo
  return template ? `${label}：${template}${revision ? ` v${revision}` : ''}` : label
})
const components = computed(() => calculation.value.components || snapshot.value.components || [])
const componentText = computed(() => components.value.map((item: any) => `${displayNumber(item.quantity)}${item.unit}`).join(' + '))
const summaryText = computed(() => componentText.value || `${displayNumber(requestedQuantity.value)}${requestedUnit.value || ''}`)

function displayNumber(value: unknown) {
  const number = Number(value)
  if (!Number.isFinite(number)) return '-'
  return number.toLocaleString('zh-CN', { maximumFractionDigits: 6 })
}
</script>
