<template>
  <el-table :data="items" class="document-item-compact-table" size="small" border>
    <el-table-column type="expand" width="48">
      <template #default="{ row }">
        <div class="document-item-expand-body">
          <div class="document-item-expand-grid">
            <Field label="规格摘要" :value="row.spec" />
            <Field label="业务数量" :value="numberWithUnit(row.requestedQuantity ?? row.quantity, row.requestedUnit || row.unit)" />
            <Field label="计价数量及单位" :value="numberWithUnit(row.pricingQuantity ?? row.quantity, row.priceUnit)" />
            <Field :label="kind === 'INBOUND' ? '原始入库数量' : '主库存数量及单位'" :value="numberWithUnit(row.quantity, row.baseUnit || row.unit)" />
            <Field label="包装换算" :value="packagingRelation(row)" />
            <Field v-if="kind === 'PURCHASE'" label="已到货" :value="numberWithUnit(row.receivedQuantity, row.baseUnit || row.unit)" />
            <Field v-if="kind === 'INBOUND' || kind === 'OUTBOUND'" label="批次号" :value="row.batchNo" />
            <Field v-if="kind === 'INBOUND'" label="送样/领用/损耗已扣" :value="optionalNumberWithUnit(row.nonTradeIssuedQuantity, row.baseUnit || row.unit, true)" />
            <Field v-if="kind === 'INBOUND'" label="当前账面结存" :value="optionalNumberWithUnit(row.currentBookQuantity, row.baseUnit || row.unit)" />
            <Field v-if="kind === 'INBOUND'" label="草稿出库占用" :value="optionalNumberWithUnit(row.reservedQuantity, row.baseUnit || row.unit, true)" />
            <Field v-if="kind === 'INBOUND'" label="当前自由可用" :value="optionalNumberWithUnit(row.currentFreeQuantity, row.baseUnit || row.unit)" />
            <Field v-if="kind === 'INBOUND'" label="厂家序列" :value="row.serialNumber" />
            <Field v-if="kind === 'INBOUND'" label="生产日期" :value="formatDate(row.productionDate)" />
            <Field v-if="kind === 'INBOUND'" label="包装条码" :value="row.packageBarcode" />
            <Field v-if="kind === 'INBOUND'" label="保质期" :value="shelfLifeText(row)" />
            <Field v-if="kind === 'INBOUND'" label="到期日" :value="formatDate(row.expirationDate)" />
          </div>

          <div class="document-item-snapshot-audit">
            <div class="document-item-snapshot-title">历史包装快照</div>
            <template v-if="snapshotInfo(row).hasSnapshot">
              <div class="document-item-snapshot-grid">
                <Field label="包装组成" :value="snapshotInfo(row).components" />
                <Field label="规则来源" :value="snapshotInfo(row).source" />
                <Field label="有效版本" :value="snapshotInfo(row).version" />
                <Field label="数量口径" :value="snapshotInfo(row).quantityBasis" />
                <Field class="document-item-snapshot-hash" label="快照校验码" :value="snapshotInfo(row).hash" />
              </div>
            </template>
            <el-tag v-else type="info" size="small">历史数据·无完整快照</el-tag>
          </div>
        </div>
      </template>
    </el-table-column>

    <el-table-column label="商品" min-width="200">
      <template #default="{ row }">
        <div class="document-item-compact-product">{{ row.productName || '-' }}</div>
        <div class="document-item-compact-secondary">{{ row.sku || '-' }}</div>
      </template>
    </el-table-column>
    <el-table-column label="规格摘要" min-width="160">
      <template #default="{ row }">
        <div>{{ row.spec || '-' }}</div>
      </template>
    </el-table-column>
    <el-table-column label="数量 / 包装" min-width="170">
      <template #default="{ row }">
        <div class="document-item-compact-quantity">{{ numberWithUnit(row.requestedQuantity ?? row.quantity, row.requestedUnit || row.unit) }}</div>
        <div v-if="hasPackagingRelation(row)" class="document-item-compact-secondary">{{ packagingRelation(row) }}</div>
      </template>
    </el-table-column>
    <el-table-column v-if="kind === 'INBOUND'" label="当前库存" min-width="170">
      <template #default="{ row }">
        <div class="document-item-compact-quantity">
          账面 {{ optionalNumberWithUnit(row.currentBookQuantity, row.baseUnit || row.unit) }}
        </div>
        <div class="document-item-compact-secondary">
          可用 {{ optionalNumberWithUnit(row.currentFreeQuantity, row.baseUnit || row.unit) }}
          <span v-if="Number(row.reservedQuantity) > 0"> · 占用 {{ optionalNumberWithUnit(row.reservedQuantity, row.baseUnit || row.unit, true) }}</span>
        </div>
      </template>
    </el-table-column>
    <el-table-column v-if="kind === 'PURCHASE'" label="已到货" min-width="90" align="right">
      <template #default="{ row }">{{ numberWithUnit(row.receivedQuantity, row.baseUnit || row.unit) }}</template>
    </el-table-column>
    <el-table-column v-if="kind === 'SALES'" label="赠品" min-width="70" align="center">
      <template #default="{ row }"><el-tag :type="row.noCharge ? 'success' : 'info'" size="small">{{ row.noCharge ? '是' : '否' }}</el-tag></template>
    </el-table-column>
    <el-table-column :label="priceLabel" min-width="105" align="right">
      <template #default="{ row }">
        <div>{{ money(row.unitCost ?? row.unitPrice, 4) }}</div>
        <div class="document-item-compact-secondary">{{ row.priceUnit ? `/ ${row.priceUnit}` : '-' }}</div>
      </template>
    </el-table-column>
    <el-table-column label="金额" min-width="120" align="right">
      <template #default="{ row }"><strong>{{ money(row.amount) }}</strong></template>
    </el-table-column>
  </el-table>
</template>

<script setup lang="ts">
import { computed, defineComponent, h } from 'vue'
import { formatDate, money, quantity } from '@/api/format'

type DocumentKind = 'PURCHASE' | 'SALES' | 'INBOUND' | 'OUTBOUND'

const props = defineProps<{
  items: any[]
  kind: DocumentKind
}>()

const priceLabel = computed(() => {
  if (props.kind === 'INBOUND') return '成本单价'
  if (props.kind === 'OUTBOUND') return '销售单价'
  return '单价'
})

const Field = defineComponent({
  inheritAttrs: false,
  props: { label: { type: String, required: true }, value: { type: [String, Number], default: '-' } },
  setup(fieldProps, { attrs }) {
    return () => h('div', { ...attrs, class: ['document-item-expand-field', attrs.class] }, [
      h('span', { class: 'document-item-expand-label' }, fieldProps.label),
      h('div', { class: 'document-item-expand-value' }, displayText(fieldProps.value)),
    ])
  },
})

function displayText(value: unknown) {
  if (value === null || value === undefined || value === '') return '-'
  return String(value)
}

function numberWithUnit(value: unknown, unit: unknown) {
  const number = Number(value)
  if (!Number.isFinite(number)) return '-'
  return `${quantity(number)}${unit ? ` ${unit}` : ''}`
}

function optionalNumberWithUnit(value: unknown, unit: unknown, zeroWhenMissing = false) {
  if ((value === null || value === undefined || value === '') && !zeroWhenMissing) return '-'
  return numberWithUnit(value ?? 0, unit)
}

function hasPackagingRelation(row: any) {
  const requestedUnit = row.requestedUnit || row.unit
  const baseUnit = row.baseUnit || row.unit
  return requestedUnit !== baseUnit || Number(row.requestedQuantity ?? row.quantity) !== Number(row.quantity) || Number(row.packageQuantity) > 0
}

function packagingRelation(row: any) {
  const requested = numberWithUnit(row.requestedQuantity ?? row.quantity, row.requestedUnit || row.unit)
  const base = numberWithUnit(row.quantity, row.baseUnit || row.unit)
  const requestedDiffers = (row.requestedUnit || row.unit) !== (row.baseUnit || row.unit)
    || Number(row.requestedQuantity ?? row.quantity) !== Number(row.quantity)
  if (requestedDiffers) return `${requested} × ${base}`
  if (Number(row.packageQuantity) > 0) return `${numberWithUnit(row.packageQuantity, row.packageUnit)} × ${base}`
  return base
}

function shelfLifeText(row: any) {
  if (row.shelfLifeMode !== 'PERIOD') return '无保质期'
  const unit = ({ DAY: '天', MONTH: '月', YEAR: '年' } as Record<string, string>)[row.shelfLifeUnit || ''] || ''
  return `${row.shelfLifeValue || '-'}${unit}`
}

function snapshotInfo(row: any) {
  let snapshot: Record<string, any> = {}
  try {
    const parsed = JSON.parse(row.packagingPlanJson || '{}')
    if (parsed && typeof parsed === 'object') snapshot = parsed
  } catch { snapshot = {} }
  const profile = snapshot.effectiveProfile || snapshot.profile || {}
  const source = snapshot.source || profile.source || {}
  const calculation = snapshot.calculation || snapshot
  const components = calculation.components || snapshot.components || []
  const sourceLevel = source.sourceLevel || profile.sourceLevel || snapshot.sourceLevel
  const sourceLabel = ({ TEMPLATE: '系列包装模板', PRODUCT_OVERRIDE: '商品特例', SYSTEM_DEFAULT: '系统默认', LEGACY: '历史迁移' } as Record<string, string>)[sourceLevel] || sourceLevel || '历史快照'
  const templateName = source.templateName || profile.templateName
  const revision = source.templateRevisionNo || profile.templateRevisionNo
  const effectiveRevisionId = profile.effectiveRevisionId || snapshot.effectiveRevisionId || row.packagingRevisionId
  const versionNo = profile.versionNo || snapshot.versionNo
  const basis = calculation.quantityBasis || snapshot.quantityBasis || (calculation.estimated ? 'ESTIMATED' : 'ACTUAL')
  return {
    hasSnapshot: Boolean(row.packagingPlanJson && row.packagingPlanJson !== '{}' && Object.keys(snapshot).length),
    components: components.map((item: any) => `${quantity(Number(item.quantity))}${item.unit || ''}`).join(' + ') || packagingRelation(row),
    source: templateName ? `${sourceLabel}：${templateName}${revision ? ` v${revision}` : ''}` : sourceLabel,
    version: effectiveRevisionId ? `#${effectiveRevisionId}` : `v${versionNo || 0}`,
    quantityBasis: basis === 'ESTIMATED' ? '计划参考/预计' : '实际执行',
    hash: profile.contentHash || snapshot.contentHash || row.packagingSnapshotHash || '-',
  }
}
</script>

<style scoped>
.document-item-compact-table {
  width: 100%;
}

.document-item-compact-product,
.document-item-compact-quantity {
  color: var(--el-text-color-primary);
  font-weight: 600;
  line-height: 1.5;
}

.document-item-compact-secondary {
  margin-top: 3px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.document-item-expand-body {
  padding: 14px 18px 18px 66px;
  background: var(--el-fill-color-extra-light);
}

.document-item-expand-grid,
.document-item-snapshot-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 1px;
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-border-color-lighter);
}

.document-item-expand-field {
  min-width: 0;
  padding: 9px 11px;
  background: var(--el-bg-color);
}

.document-item-expand-label {
  display: block;
  margin-bottom: 3px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.document-item-expand-value {
  color: var(--el-text-color-primary);
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.document-item-snapshot-audit {
  margin-top: 12px;
}

.document-item-snapshot-title {
  margin-bottom: 8px;
  color: var(--el-text-color-primary);
  font-weight: 600;
}

.document-item-snapshot-hash {
  grid-column: span 2;
}

@media (max-width: 720px) {
  .document-item-expand-body {
    padding: 12px;
  }

  .document-item-expand-grid,
  .document-item-snapshot-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 480px) {
  .document-item-expand-grid,
  .document-item-snapshot-grid {
    grid-template-columns: 1fr;
  }

  .document-item-snapshot-hash {
    grid-column: auto;
  }
}
</style>
