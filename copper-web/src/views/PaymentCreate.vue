<template>
  <div class="page">
    <div class="panel">
      <PageHeader title="回款核销" :back-to="backTo" />
      <div class="panel-body">
        <PaymentForm :initial="initial" submit-label="保存回款" @success="router.push(backTo)" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import PaymentForm from '@/components/PaymentForm.vue'
import { returnPath } from '@/utils/navigation'

const route = useRoute()
const router = useRouter()
const backTo = computed(() => returnPath(route, '/payments'))
const initial = computed(() => {
  const sourceType = String(route.query.sourceType || '')
  const sourceId = Number(route.query.sourceId)
  if (!['SALES_ORDER', 'OUTBOUND'].includes(sourceType) || !Number.isInteger(sourceId) || sourceId <= 0) return undefined
  return { sourceType: sourceType as 'SALES_ORDER' | 'OUTBOUND', sourceId }
})
</script>
