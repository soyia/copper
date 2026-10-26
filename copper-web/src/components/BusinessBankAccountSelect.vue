<template>
  <el-select
    :model-value="modelValue"
    filterable
    :clearable="clearable"
    :disabled="disabled"
    :loading="query.isLoading.value"
    placeholder="请选择实际收付的企业银行账户"
    @update:model-value="emit('update:modelValue', $event || undefined)"
  >
    <el-option
      v-for="account in accounts"
      :key="account.id"
      :value="account.id"
      :label="`${account.accountName} / ${account.bankName} / ${account.maskedAccountNo}`"
    >
      <span>{{ account.accountName }} · {{ account.bankName }}</span>
      <span class="bank-account-no">{{ account.maskedAccountNo }} · {{ account.ledgerAccountCode }}</span>
    </el-option>
  </el-select>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue'
import { useQuery } from '@tanstack/vue-query'
import { api } from '@/api/client'

const props = withDefaults(defineProps<{
  modelValue?: number
  clearable?: boolean
  disabled?: boolean
}>(), { clearable: false, disabled: false })

const emit = defineEmits<{ (event: 'update:modelValue', value?: number): void }>()
const query = useQuery({
  queryKey: ['business-bank-accounts', 'enabled'],
  queryFn: () => api.businessBankAccounts(false),
})
const accounts = computed(() => (query.data.value ?? []).filter((item) => item.status === 'ENABLED'))

watch(accounts, (rows) => {
  if (!props.modelValue && rows.length === 1) emit('update:modelValue', rows[0].id)
}, { immediate: true })
</script>

<style scoped>
.bank-account-no { float: right; margin-left: 18px; color: var(--el-text-color-secondary); font-size: 12px; }
</style>
