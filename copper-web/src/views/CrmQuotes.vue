<template>
  <div class="panel">
    <div class="panel-header">
      <div>
        <div class="panel-title">报价</div>
        <div class="muted">报价是 CRM 销售承诺版本，不等于销售订单、出库、应收或开票。</div>
      </div>
      <el-button type="primary" @click="router.push('/crm/quotes/new')">新建报价</el-button>
    </div>
    <div class="filters">
      <el-select v-model="status" clearable placeholder="全部状态" @change="load">
        <el-option v-for="item in statuses" :key="item.value" :label="item.label" :value="item.value"/>
      </el-select>
      <PartnerRemoteSelect v-model="partnerId" type="CUSTOMER" clearable placeholder="客户" @change="load"/>
      <el-button @click="load">查询</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" @row-dblclick="openDetail">
      <el-table-column label="报价号 / 版本" width="195" fixed="left">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">{{ row.quoteNo }}-V{{ row.revisionNo }}</el-button>
        </template>
      </el-table-column>
      <el-table-column prop="title" label="主题" min-width="190"/>
      <el-table-column prop="partnerName" label="客户" min-width="160"/>
      <el-table-column label="商机" width="170">
        <template #default="{ row }">
          <el-button v-if="row.opportunityId" link type="primary"
                     @click="router.push(`/crm/opportunities/${row.opportunityId}`)">{{ row.opportunityNo }}
          </el-button>
          <span v-else>-</span></template>
      </el-table-column>
      <el-table-column prop="quoteDate" label="报价日期" width="120"/>
      <el-table-column prop="validUntil" label="有效期至" width="120"/>
      <el-table-column label="价税合计" width="135" align="right">
        <template #default="{ row }">¥{{ moneyText(row.totalAmount) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="crmStatusType(row.status)">{{ crmStatusName(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="销售订单" width="150">
        <template #default="{ row }">
          <el-button v-if="row.salesOrderId" link type="primary"
                     @click="router.push(`/sales-orders/${row.salesOrderId}`)">{{ row.salesOrderNo }}
          </el-button>
          <span v-else>-</span></template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue'
import {useRouter} from 'vue-router'
import {api, type CrmQuote} from '@/api/client'
import PartnerRemoteSelect from '@/components/PartnerRemoteSelect.vue'
import {crmStatusName, crmStatusType, moneyText} from '@/utils/crm'

const router = useRouter();
const rows = ref<CrmQuote[]>([]);
const loading = ref(false);
const status = ref('');
const partnerId = ref<number>()
const statuses = [{label: '草稿', value: 'DRAFT'}, {label: '生效中', value: 'ACTIVE'}, {
  label: '客户接受',
  value: 'ACCEPTED'
}, {label: '客户拒绝', value: 'REJECTED'}, {label: '已修订', value: 'REVISED'}, {label: '已作废', value: 'CANCELLED'}]
onMounted(load)

async function load() {
  loading.value = true;
  try {
    rows.value = await api.crmQuotes({status: status.value || undefined, partnerId: partnerId.value})
  } finally {
    loading.value = false
  }
}

function openDetail(row: CrmQuote) {
  router.push(`/crm/quotes/${row.id}`)
}
</script>

<style scoped>.panel-header, .filters {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px
}

.filters {
  justify-content: flex-start;
  margin: 14px 0
}

.filters .el-select {
  width: 160px
}

.filters :deep(.partner-select-dropdown) {
  width: 300px
}

.filters > :nth-child(2) {
  width: 300px
}</style>
