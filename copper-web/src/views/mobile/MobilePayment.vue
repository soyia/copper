<template>
  <div class="mobile-page">
    <el-alert
      title="到账与核销分开记录"
      description="先登记真实到账，再按需要分配到应收单据；未分配金额保留为客户余额。手机端不会伪造回款，也不会在断网时排队补记。"
      type="warning"
      :closable="false"
      show-icon
    />
    <section v-if="saved" class="payment-success">
      <el-icon><CircleCheckFilled /></el-icon>
      <div><strong>回款已经服务器确认</strong><span>请以回款列表、资金流水和核销关系为准。</span></div>
      <el-button plain @click="saved = false">继续收款</el-button>
    </section>
    <section class="mobile-card">
      <PaymentForm
        mobile
        :disabled="!connectivity.isAvailable"
        submit-label="确认登记回款"
        @success="saved = true"
      />
      <div v-if="!connectivity.isAvailable" class="offline-block">
        当前无法连接 Copper 主机。已填写内容会留在当前页面，但不能提交；恢复后请先核对是否已有成功记录。
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { CircleCheckFilled } from '@element-plus/icons-vue'
import PaymentForm from '@/components/PaymentForm.vue'
import { useConnectivityStore } from '@/stores/connectivity'

const connectivity = useConnectivityStore()
const saved = ref(false)
</script>

<style scoped>
.mobile-page { display: grid; gap: 12px; }
.mobile-card { padding: 15px; border: 1px solid #e2e7ea; border-radius: 17px; background: #fff; }
.payment-success { display: grid; grid-template-columns: 28px 1fr auto; align-items: center; gap: 10px; padding: 14px; border: 1px solid #cce5d3; border-radius: 15px; color: #27613e; background: #f0f9f3; }
.payment-success > .el-icon { font-size: 23px; }
.payment-success div { display: grid; gap: 3px; }
.payment-success span { color: #668273; font-size: 11px; }
.offline-block { margin-top: 12px; padding: 10px 12px; border-radius: 10px; color: #8a4d1e; background: #fff3e7; font-size: 11px; line-height: 1.55; }
@media (max-width: 520px) {
  .payment-success { grid-template-columns: 28px 1fr; }
  .payment-success .el-button { grid-column: 1 / -1; }
}
</style>
