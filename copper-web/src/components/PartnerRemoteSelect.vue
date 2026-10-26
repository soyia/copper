<template>
  <el-select
    v-model="selectValue"
    filterable
    remote
    reserve-keyword
    :clearable="clearable"
    :disabled="disabled"
    :loading="loading"
    :placeholder="placeholderText"
    popper-class="partner-select-dropdown"
    :remote-method="queueSearch"
    no-data-text="输入关键字搜索"
    no-match-text="未找到匹配单位"
    @change="handleChange"
    @visible-change="handleVisibleChange"
  >
    <el-option
      v-for="item in displayOptions"
      :key="`${item.id}:${item.name}`"
      :label="item.name"
      :value="optionValue(item)"
    >
      <div class="partner-option">
        <span class="partner-option-name">{{ item.name }}</span>
        <span v-if="optionMeta(item)" class="partner-option-meta">{{ optionMeta(item) }}</span>
      </div>
    </el-option>
  </el-select>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, type Partner, type PartnerType } from '@/api/client'
import { formatPhone } from '@/api/format'

type PartnerSelectValue = number | string | undefined

const props = withDefaults(
  defineProps<{
    modelValue?: PartnerSelectValue
    type?: PartnerType
    valueField?: 'id' | 'name'
    placeholder?: string
    clearable?: boolean
    disabled?: boolean
    limit?: number
    extraOptions?: Partner[]
  }>(),
  {
    valueField: 'id',
    placeholder: '',
    clearable: false,
    disabled: false,
    limit: 20,
    extraOptions: () => [],
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: PartnerSelectValue): void
  (e: 'change', value: PartnerSelectValue, partner?: Partner): void
}>()

const options = ref<Partner[]>([])
const loading = ref(false)
let searchTimer: ReturnType<typeof setTimeout> | undefined
let requestSeq = 0

const selectValue = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', normalizeValue(value)),
})

const placeholderText = computed(() => props.placeholder || (props.type === 'SUPPLIER' ? '输入供应商名称/电话/联系人' : props.type === 'CUSTOMER' ? '输入客户名称/电话/联系人' : '输入往来单位名称/电话/联系人'))

const displayOptions = computed(() => {
  const rows = [...props.extraOptions, ...options.value]
  const seen = new Set<string>()
  const merged: Partner[] = []
  for (const row of rows) {
    const key = `${row.id}:${row.name}`
    if (seen.has(key)) continue
    seen.add(key)
    merged.push(row)
  }
  if (props.valueField === 'name' && typeof props.modelValue === 'string' && props.modelValue && !merged.some((item) => item.name === props.modelValue)) {
    merged.unshift(makeNameOption(props.modelValue))
  }
  return merged
})

onMounted(() => {
  searchPartners('')
  ensureSelectedOption()
})

onBeforeUnmount(() => {
  if (searchTimer) clearTimeout(searchTimer)
})

watch(
  () => props.type,
  () => searchPartners(''),
)

watch(
  () => props.modelValue,
  () => ensureSelectedOption(),
)

function queueSearch(keyword: string) {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => searchPartners(keyword), 250)
}

function handleVisibleChange(visible: boolean) {
  if (visible && options.value.length === 0) searchPartners('')
}

async function searchPartners(keyword: string) {
  const seq = ++requestSeq
  loading.value = true
  try {
    const rows = await api.partners({
      type: props.type,
      keyword: keyword.trim() || undefined,
      status: 'ENABLED',
      limit: props.limit,
    })
    if (seq === requestSeq) options.value = rows
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

async function ensureSelectedOption() {
  if (props.valueField !== 'id' || typeof props.modelValue !== 'number') return
  if (displayOptions.value.some((item) => item.id === props.modelValue)) return

  const seq = ++requestSeq
  loading.value = true
  try {
    const rows = await api.partners({ type: props.type, id: props.modelValue, limit: 1 })
    if (seq === requestSeq && rows.length) options.value = mergeOptions(rows, options.value)
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

function handleChange(value: PartnerSelectValue) {
  const normalized = normalizeValue(value)
  emit('change', normalized, findPartner(normalized))
}

function optionValue(item: Partner) {
  return props.valueField === 'name' ? item.name : item.id
}

function normalizeValue(value: unknown): PartnerSelectValue {
  return value === '' || value === null ? undefined : (value as PartnerSelectValue)
}

function findPartner(value: PartnerSelectValue) {
  if (value === undefined) return undefined
  return displayOptions.value.find((item) => optionValue(item) === value)
}

function optionMeta(item: Partner) {
  return [item.businessNo, item.aliasName, item.contactPerson, item.phone ? formatPhone(item.phone) : '', item.creditCode].filter(Boolean).join(' / ')
}

function mergeOptions(incoming: Partner[], current: Partner[]) {
  const ids = new Set(incoming.map((item) => item.id))
  return [...incoming, ...current.filter((item) => !ids.has(item.id))]
}

function makeNameOption(name: string): Partner {
  return {
    id: -Math.abs(hashText(name)),
    businessNo: '',
    type: props.type ?? 'CUSTOMER',
    typeMask: props.type === 'SUPPLIER' ? 2 : 1,
    entityType: 'COMPANY',
    name,
    aliasName: '',
    creditCode: '',
    contactPerson: '',
    primaryContactRole: '',
    phone: '',
    wechat: '',
    email: '',
    address: '',
    creditLimit: 0,
    topFixed: false,
    cooperationStartDate: null,
    establishmentDate: null,
    source: '',
    tags: '',
    status: 'ENABLED',
    disabledReason: '',
    disabledAt: null,
    memo: '',
    createdAt: '',
    customerStatus: props.type === 'SUPPLIER' ? 'DISABLED' : 'ENABLED',
    customerDisabledReason: '',
    customerDisabledAt: null,
    customerLevel: 'NORMAL',
    customerSettlementMethod: 'PER_ORDER',
    customerSettlementDays: 0,
    customerTaxIncluded: true,
    customerInvoiceType: 'NONE',
    customerTaxRate: 0,
    preferredProducts: '',
    supplierStatus: props.type === 'SUPPLIER' ? 'ENABLED' : 'DISABLED',
    supplierDisabledReason: '',
    supplierDisabledAt: null,
    supplierSettlementMethod: 'PER_ORDER',
    supplierSettlementDays: 0,
    leadTimeDays: 0,
    minOrderAmount: 0,
    supplierTaxIncluded: true,
    supplierInvoiceType: 'NONE',
    supplierTaxRate: 0,
    qualificationMemo: '',
  }
}

function hashText(value: string) {
  let hash = 0
  for (const char of value) hash = (hash * 31 + char.charCodeAt(0)) | 0
  return hash || 1
}
</script>
