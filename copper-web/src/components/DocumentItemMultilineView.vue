<template>
  <div class="document-item-multiline-list">
    <article v-for="(row, index) in items" :key="row.id ?? index" class="document-item-multiline-row">
      <header class="document-item-multiline-header">
        <div class="document-item-multiline-identity">
          <div class="document-item-multiline-name">{{ row.productName || '-' }}</div>
          <div class="document-item-multiline-sku">SKU：{{ row.sku || '-' }}</div>
        </div>
        <div class="document-item-multiline-total">
          <span>金额</span>
          <strong>{{ money(row.amount) }}</strong>
        </div>
      </header>

      <div class="document-item-multiline-grid">
        <Field label="规格摘要" :value="row.spec" />
        <Field label="业务数量" :value="numberWithUnit(row.requestedQuantity ?? row.quantity, row.requestedUnit || row.unit)" />
        <Field :label="kind === 'INBOUND' ? '原始入库数量' : '库存数量'" :value="numberWithUnit(row.quantity, row.baseUnit || row.unit)" />
        <Field label="计价数量" :value="numberWithUnit(row.pricingQuantity ?? row.quantity, row.priceUnit)" />
        <Field v-if="kind === 'PURCHASE'" label="已到货" :value="numberWithUnit(row.receivedQuantity, row.baseUnit || row.unit)" />
        <Field v-if="kind === 'INBOUND' || kind === 'OUTBOUND'" label="批次号" :value="row.batchNo" />
        <Field v-if="kind === 'INBOUND'" label="送样/领用/损耗已扣" :value="optionalNumberWithUnit(row.nonTradeIssuedQuantity, row.baseUnit || row.unit, true)" />
        <Field v-if="kind === 'INBOUND'" label="当前账面结存" :value="optionalNumberWithUnit(row.currentBookQuantity, row.baseUnit || row.unit)" />
        <Field v-if="kind === 'INBOUND'" label="草稿出库占用" :value="optionalNumberWithUnit(row.reservedQuantity, row.baseUnit || row.unit, true)" />
        <Field v-if="kind === 'INBOUND'" label="当前自由可用" :value="optionalNumberWithUnit(row.currentFreeQuantity, row.baseUnit || row.unit)" />
        <Field v-if="kind === 'INBOUND'" label="厂家序列" :value="row.serialNumber" />
        <Field v-if="Number(row.packageQuantity) > 0" label="包装数量" :value="numberWithUnit(row.packageQuantity, row.packageUnit)" />
        <Field label="计价单位" :value="row.priceUnit" />
        <Field label="包装单位" :value="row.packageUnit" />
        <Field v-if="kind === 'SALES'" label="赠品" :value="row.noCharge ? '是' : '否'" />
        <Field :label="priceLabel" :value="money(row.unitCost ?? row.unitPrice, 4)" />
        <Field v-if="kind === 'INBOUND'" label="生产日期" :value="formatDate(row.productionDate)" />
        <Field v-if="kind === 'INBOUND'" label="包装条码" :value="row.packageBarcode" />
        <Field v-if="kind === 'INBOUND'" label="保质期" :value="shelfLifeText(row)" />
        <Field v-if="kind === 'INBOUND'" label="到期日" :value="formatDate(row.expirationDate)" />
        <div class="document-item-multiline-field document-item-multiline-snapshot">
          <span class="document-item-multiline-label">计量包装快照</span>
          <div class="document-item-multiline-value">
            <PackagingSnapshotCell
              :snapshot-json="row.packagingPlanJson"
              :requested-quantity="row.requestedQuantity"
              :requested-unit="row.requestedUnit"
              :quantity="row.quantity"
              :base-unit="row.baseUnit || row.unit"
              :pricing-quantity="row.pricingQuantity"
              :price-unit="row.priceUnit"
              :snapshot-hash="row.packagingSnapshotHash"
            />
          </div>
        </div>
      </div>
    </article>
    <el-empty v-if="!items.length" description="暂无商品明细" :image-size="72" />
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h } from 'vue'
import { formatDate, money, quantity } from '@/api/format'
import PackagingSnapshotCell from '@/components/PackagingSnapshotCell.vue'

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
  props: { label: { type: String, required: true }, value: { type: [String, Number], default: '-' } },
  setup(fieldProps) {
    return () => h('div', { class: 'document-item-multiline-field' }, [
      h('span', { class: 'document-item-multiline-label' }, fieldProps.label),
      h('div', { class: 'document-item-multiline-value' }, displayText(fieldProps.value)),
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

function shelfLifeText(row: any) {
  if (row.shelfLifeMode !== 'PERIOD') return '无保质期'
  const unit = ({ DAY: '天', MONTH: '月', YEAR: '年' } as Record<string, string>)[row.shelfLifeUnit || ''] || ''
  return `${row.shelfLifeValue || '-'}${unit}`
}
</script>

<style scoped>
.document-item-multiline-list {
  padding: 14px 16px 16px;
}

.document-item-multiline-row {
  overflow: hidden;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.document-item-multiline-row + .document-item-multiline-row {
  margin-top: 12px;
}

.document-item-multiline-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 12px 14px;
  background: var(--el-fill-color-light);
}

.document-item-multiline-identity {
  min-width: 0;
}

.document-item-multiline-name {
  color: var(--el-text-color-primary);
  font-size: 15px;
  font-weight: 600;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.document-item-multiline-sku {
  margin-top: 3px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.document-item-multiline-total {
  display: flex;
  flex: none;
  align-items: baseline;
  gap: 8px;
  white-space: nowrap;
}

.document-item-multiline-total span,
.document-item-multiline-label {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.document-item-multiline-total strong {
  color: var(--el-text-color-primary);
  font-size: 16px;
}

.document-item-multiline-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 1px;
  background: var(--el-border-color-lighter);
}

.document-item-multiline-field {
  min-width: 0;
  padding: 10px 12px;
  background: var(--el-bg-color);
}

.document-item-multiline-label {
  display: block;
  margin-bottom: 4px;
}

.document-item-multiline-value {
  min-height: 22px;
  color: var(--el-text-color-primary);
  font-size: 14px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.document-item-multiline-snapshot {
  grid-column: span 2;
}

@media (max-width: 720px) {
  .document-item-multiline-list {
    padding: 10px;
  }

  .document-item-multiline-header {
    display: block;
  }

  .document-item-multiline-total {
    margin-top: 8px;
  }

  .document-item-multiline-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 480px) {
  .document-item-multiline-grid {
    grid-template-columns: 1fr;
  }

  .document-item-multiline-snapshot {
    grid-column: auto;
  }
}
</style>
