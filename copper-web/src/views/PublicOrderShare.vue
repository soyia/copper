<template>
  <main class="share-page">
    <section class="share-sheet" v-loading="query.isFetching.value">
      <div class="share-brand">Copper</div>
      <el-alert v-if="query.isError.value" title="链接不存在、已到期或已撤销" type="error" :closable="false" show-icon />
      <template v-else-if="order">
        <header>
          <div><h1>{{ order.sourceType === 'SALES_ORDER' ? '销售订单' : '采购订单' }}</h1><p>{{ order.sourceNo }}</p></div>
          <el-tag>{{ order.status }}</el-tag>
        </header>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="往来单位">{{ order.partnerName }}</el-descriptions-item>
          <el-descriptions-item label="业务日期">{{ order.businessDate }}</el-descriptions-item>
          <el-descriptions-item label="价格口径">{{ order.priceIncludesTax ? '含税' : '未税' }}</el-descriptions-item>
          <el-descriptions-item label="订单金额">{{ money(order.totalAmount) }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="order.items" border size="small" style="margin-top:16px">
          <el-table-column label="商品" min-width="170"><template #default="{ row }"><strong>{{ row.productName }}</strong><div class="muted">{{ row.productSku }}</div></template></el-table-column>
          <el-table-column prop="productSpec" label="规格" min-width="150" />
          <el-table-column label="数量" width="130" align="right"><template #default="{ row }">{{ quantity(row.quantity) }} {{ row.unit }}</template></el-table-column>
          <el-table-column label="单价" width="120" align="right"><template #default="{ row }">{{ money(row.unitPrice, 4) }}</template></el-table-column>
          <el-table-column label="金额" width="130" align="right"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
        </el-table>
        <p v-if="order.memo" class="memo">备注：{{ order.memo }}</p>
        <footer>此页面为只读订单信息，不代表收付款、签收或发票状态。</footer>
      </template>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useQuery } from '@tanstack/vue-query'
import { api } from '@/api/client'
import { money, quantity } from '@/api/format'
const route = useRoute()
const token = String(route.params.token || '')
const query = useQuery({ queryKey: ['public-order-share', token], queryFn: () => api.publicOrderShare(token), retry: false })
const order = computed(() => query.data.value)
</script>

<style scoped>
.share-page { min-height: 100vh; padding: 30px 16px; background: #f3f6f9; }
.share-sheet { max-width: 980px; min-height: 360px; margin: 0 auto; padding: 24px; border: 1px solid #dfe5ec; border-radius: 12px; background: #fff; box-shadow: 0 12px 36px rgb(31 45 61 / 8%); }
.share-brand { margin-bottom: 20px; color: #c85324; font-size: 20px; font-weight: 800; }
header { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 20px; }
h1 { margin: 0; font-size: 26px; } header p { margin: 6px 0 0; color: #6b7785; }
.memo { padding: 12px; background: #f7f9fb; }
footer { margin-top: 20px; color: #7d8792; font-size: 12px; text-align: center; }
@media (max-width: 640px) { .share-page { padding: 0; } .share-sheet { min-height: 100vh; padding: 16px; border-radius: 0; } }
</style>
