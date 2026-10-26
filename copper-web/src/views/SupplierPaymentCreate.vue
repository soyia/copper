<template>
  <div class="page">
    <div class="panel">
      <PageHeader title="付款核销" :back-to="backTo" />
      <div class="panel-body">
        <SupplierPaymentForm :initial="initial" submit-label="保存付款" @success="router.push(backTo)" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import SupplierPaymentForm from '@/components/SupplierPaymentForm.vue'
import { returnPath } from '@/utils/navigation'

const route = useRoute()
const router = useRouter()
const backTo = computed(() => returnPath(route, '/supplier-payments'))
const initial = computed(() => {
  const sourceType = String(route.query.sourceType || '')
  const sourceId = Number(route.query.sourceId)
  if (!['PURCHASE_ORDER', 'INBOUND'].includes(sourceType) || !Number.isInteger(sourceId) || sourceId <= 0) return undefined
  return { sourceType: sourceType as 'PURCHASE_ORDER' | 'INBOUND', sourceId }
})
</script>
