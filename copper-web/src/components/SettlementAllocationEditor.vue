<template>
  <section class="allocation-editor">
    <div class="allocation-editor__header">
      <div>
        <div class="allocation-editor__title">核销分配</div>
        <div class="allocation-editor__hint">
          普通{{ directionLabel }}可分配到多张单据，暂未分配余额不会转成预收预付；余额类核销必须始终全额分配。
        </div>
      </div>
      <div class="allocation-editor__summary">
        <span>本次金额 <b>{{ money(totalAmount) }}</b></span>
        <span>已分配 <b class="is-success">{{ money(allocatedAmount) }}</b></span>
        <span>未分配 <b :class="{ 'is-warning': unallocatedAmount > 0 }">{{ money(unallocatedAmount) }}</b></span>
      </div>
    </div>

    <div v-if="mobile" class="allocation-mobile-list">
      <article v-for="row in candidates" :key="`${row.targetType}:${row.targetId}`" class="allocation-mobile-card">
        <div class="allocation-mobile-card__head">
          <span><strong>{{ targetTypeName(row.targetType) }}</strong><small>{{ row.targetNo }}</small></span>
          <span><strong>{{ money(row.unpaidAmount) }}</strong><small>待收 / 待付 · {{ row.businessDate }}</small></span>
        </div>
        <div class="allocation-mobile-card__action">
          <el-input
            :model-value="amountFor(row) > 0 ? String(amountFor(row)) : ''"
            inputmode="decimal"
            placeholder="本次分配"
            :disabled="disabled"
            @update:model-value="updateAmount(row, Number($event || 0))"
          ><template #append>元</template></el-input>
          <el-button plain type="primary" :disabled="disabled || remainingAmount <= 0" @click="fillRow(row)">分配</el-button>
        </div>
      </article>
      <el-empty v-if="!candidates.length" :description="emptyText" :image-size="64" />
    </div>
    <el-table v-else :data="candidates" size="small" border max-height="320" :empty-text="emptyText">
      <el-table-column label="单据类型" width="110">
        <template #default="{ row }">{{ targetTypeName(row.targetType) }}</template>
      </el-table-column>
      <el-table-column prop="targetNo" label="单据编号" min-width="180" show-overflow-tooltip />
      <el-table-column prop="businessDate" label="业务日期" width="112" />
      <el-table-column label="待收/待付" width="130" align="right">
        <template #default="{ row }">{{ money(row.unpaidAmount) }}</template>
      </el-table-column>
      <el-table-column label="本次分配" width="190" align="right">
        <template #default="{ row }">
          <el-input
            :model-value="amountFor(row) > 0 ? String(amountFor(row)) : ''"
            inputmode="decimal"
            placeholder="0.00"
            :disabled="disabled"
            @update:model-value="updateAmount(row, Number($event || 0))"
          ><template #append>元</template></el-input>
        </template>
      </el-table-column>
      <el-table-column label="快捷" width="82" align="center">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="disabled || remainingAmount <= 0" @click="fillRow(row)">分配</el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { money } from '@/api/format'

export type SettlementAllocationCandidate = {
  targetType: 'SALES_ORDER' | 'OUTBOUND' | 'PURCHASE_ORDER' | 'INBOUND'
  targetId: number
  targetNo: string
  businessDate: string
  unpaidAmount: number
}

export type SettlementAllocationValue = {
  targetType: SettlementAllocationCandidate['targetType']
  targetId: number
  amount: number
}

const props = withDefaults(defineProps<{
  modelValue: SettlementAllocationValue[]
  candidates: SettlementAllocationCandidate[]
  totalAmount: number
  directionLabel: '收款' | '付款'
  disabled?: boolean
  mobile?: boolean
  emptyText?: string
}>(), { disabled: false, mobile: false, emptyText: '当前往来单位没有可核销单据' })

const emit = defineEmits<{ 'update:modelValue': [value: SettlementAllocationValue[]] }>()

const allocatedAmount = computed(() => roundMoney(props.modelValue.reduce((sum, item) => sum + Number(item.amount || 0), 0)))
const unallocatedAmount = computed(() => Math.max(0, roundMoney(Number(props.totalAmount || 0) - allocatedAmount.value)))
const remainingAmount = computed(() => unallocatedAmount.value)

function amountFor(row: SettlementAllocationCandidate) {
  return props.modelValue.find((item) => item.targetType === row.targetType && item.targetId === row.targetId)?.amount ?? 0
}

function updateAmount(row: SettlementAllocationCandidate, value: number) {
  const amount = Math.max(0, Math.min(Number(row.unpaidAmount), roundMoney(value)))
  const next = props.modelValue.filter((item) => !(item.targetType === row.targetType && item.targetId === row.targetId))
  if (amount > 0) next.push({ targetType: row.targetType, targetId: row.targetId, amount })
  emit('update:modelValue', next)
}

function fillRow(row: SettlementAllocationCandidate) {
  const current = amountFor(row)
  const available = Math.max(0, roundMoney(Number(props.totalAmount || 0) - allocatedAmount.value + current))
  updateAmount(row, Math.min(Number(row.unpaidAmount), available))
}

function targetTypeName(value: SettlementAllocationCandidate['targetType']) {
  return ({ SALES_ORDER: '销售订单', OUTBOUND: '出库单', PURCHASE_ORDER: '采购订单', INBOUND: '入库单' })[value]
}

function roundMoney(value: number) {
  return Math.round((Number(value) + Number.EPSILON) * 100) / 100
}
</script>

<style scoped>
.allocation-editor { margin: 4px 0 18px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; overflow: hidden; background: var(--el-bg-color); }
.allocation-editor__header { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; padding: 14px 16px; background: var(--el-fill-color-extra-light); }
.allocation-editor__title { color: var(--el-text-color-primary); font-size: 14px; font-weight: 600; }
.allocation-editor__hint { margin-top: 4px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.5; }
.allocation-editor__summary { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px 18px; color: var(--el-text-color-regular); font-size: 13px; white-space: nowrap; }
.allocation-editor__summary b { margin-left: 4px; color: var(--el-text-color-primary); font-variant-numeric: tabular-nums; }
.allocation-editor__summary .is-success { color: var(--el-color-success); }
.allocation-editor__summary .is-warning { color: var(--el-color-warning); }
:deep(.el-input) { width: 156px; }
.allocation-mobile-list { display: grid; gap: 8px; padding: 10px; }
.allocation-mobile-card { display: grid; gap: 10px; padding: 11px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; background: var(--el-fill-color-blank); }
.allocation-mobile-card__head { display: flex; justify-content: space-between; gap: 10px; }
.allocation-mobile-card__head > span { display: grid; min-width: 0; gap: 2px; }
.allocation-mobile-card__head > span:last-child { text-align: right; }
.allocation-mobile-card__head strong { overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.allocation-mobile-card__head small { color: var(--el-text-color-secondary); font-size: 10px; }
.allocation-mobile-card__action { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; }
.allocation-mobile-card__action :deep(.el-input) { width: 100%; }
@media (max-width: 520px) {
  .allocation-editor__header { display: grid; gap: 10px; padding: 12px; }
  .allocation-editor__summary { justify-content: flex-start; white-space: normal; }
}
</style>
