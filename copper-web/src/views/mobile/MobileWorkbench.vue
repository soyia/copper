<template>
  <div class="mobile-workbench">
    <section class="mobile-hero">
      <div>
        <span class="mobile-eyebrow">Mobile Fast Lite</span>
        <h1>{{ greeting }}，{{ appStore.currentUserName }}</h1>
        <p>只保留外出和现场最常用的五件事，数据、权限和业务规则与电脑版完全一致。</p>
      </div>
      <div class="mobile-connection" :class="{ online: connectivity.isAvailable }">
        <span />
        {{ connectivity.shortText }}
      </div>
    </section>

    <section class="mobile-section">
      <div class="mobile-section-heading">
        <div>
          <h2>快速办事</h2>
          <p>只显示当前账号有权使用的入口</p>
        </div>
      </div>
      <div class="task-grid">
        <router-link v-for="item in availableTasks" :key="item.path" :to="item.path" class="task-card">
          <span class="task-icon" :class="item.tone"><el-icon><component :is="item.icon" /></el-icon></span>
          <span class="task-copy">
            <strong>{{ item.title }}</strong>
            <small>{{ item.description }}</small>
          </span>
          <el-icon class="task-arrow"><ArrowRight /></el-icon>
        </router-link>
      </div>
      <el-empty v-if="!availableTasks.length" description="当前账号没有手机办事权限，请联系管理员按实际职责授权" />
    </section>

    <section class="mobile-safety-note">
      <el-icon><Lock /></el-icon>
      <div>
        <strong>手机端不是另一套账</strong>
        <span>断网时可以继续查看已经打开的页面，但不能离线收款、确认盘点或自动过账。没有成功提示的写操作都要先核对。</span>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, type Component } from 'vue'
import { ArrowRight, Camera, Goods, Lock, Money, PriceTag, ScaleToOriginal } from '@element-plus/icons-vue'
import { useAppStore } from '@/stores/app'
import { useConnectivityStore } from '@/stores/connectivity'

const appStore = useAppStore()
const connectivity = useConnectivityStore()
const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 11) return '早上好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

type Task = {
  path: string
  title: string
  description: string
  icon: Component
  tone: string
  permissionsAny: string[]
}
const tasks: Task[] = [
  { path: '/mobile/stock', title: '查货', description: '按商品、规格、仓库或条码查可用量', icon: Goods, tone: 'blue', permissionsAny: ['inventory.view'] },
  { path: '/mobile/quote-order', title: '报价 / 下单', description: '现场快速保存报价或销售订单草稿', icon: PriceTag, tone: 'copper', permissionsAny: ['crm.record.manage', 'sales.manage'] },
  { path: '/mobile/payment', title: '收款', description: '登记真实到账并按需分配核销', icon: Money, tone: 'green', permissionsAny: ['finance.view'] },
  { path: '/mobile/stocktake', title: '扫码盘点', description: '扫码找批次，录入实盘数并保存草稿', icon: ScaleToOriginal, tone: 'purple', permissionsAny: ['inventory.stocktake'] },
  { path: '/mobile/evidence', title: '拍照归单', description: '把磅单、签收单和回单归到原业务单', icon: Camera, tone: 'gold', permissionsAny: ['evidence.manage'] },
]
const availableTasks = computed(() => tasks.filter((item) => item.permissionsAny.some((permission) => appStore.hasPermission(permission))))
</script>

<style scoped>
.mobile-workbench { display: grid; gap: 14px; }
.mobile-hero {
  display: grid;
  gap: 18px;
  padding: 22px;
  border-radius: 20px;
  color: #fff;
  background: linear-gradient(138deg, #0b2a3f, #123f58);
  box-shadow: 0 14px 30px rgb(8 35 56 / 16%);
}
.mobile-eyebrow {
  display: inline-block;
  margin-bottom: 8px;
  color: #f0a47e;
  font-size: 11px;
  font-weight: 750;
  letter-spacing: .12em;
  text-transform: uppercase;
}
.mobile-hero h1 { margin: 0; font-size: 24px; line-height: 1.3; }
.mobile-hero p { max-width: 34rem; margin: 8px 0 0; color: rgb(255 255 255 / 70%); font-size: 13px; line-height: 1.65; }
.mobile-connection {
  display: flex;
  width: fit-content;
  align-items: center;
  gap: 7px;
  padding: 6px 10px;
  border: 1px solid rgb(255 255 255 / 14%);
  border-radius: 999px;
  color: rgb(255 255 255 / 72%);
  background: rgb(255 255 255 / 7%);
  font-size: 11px;
}
.mobile-connection span { width: 7px; height: 7px; border-radius: 50%; background: #e7b26e; }
.mobile-connection.online span { background: #63c87a; box-shadow: 0 0 0 3px rgb(99 200 122 / 16%); }
.mobile-section {
  padding: 18px;
  border: 1px solid #e5eaee;
  border-radius: 20px;
  background: #fff;
}
.mobile-section-heading { margin-bottom: 12px; }
.mobile-section-heading h2 { margin: 0; font-size: 17px; }
.mobile-section-heading p { margin: 4px 0 0; color: #788691; font-size: 12px; }
.task-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.task-card {
  display: grid;
  min-width: 0;
  grid-template-columns: 42px minmax(0, 1fr) 18px;
  align-items: center;
  gap: 10px;
  min-height: 82px;
  padding: 12px;
  border: 1px solid #e7ebee;
  border-radius: 15px;
  color: inherit;
  background: #fbfcfd;
  text-decoration: none;
  transition: transform .15s ease, border-color .15s ease, box-shadow .15s ease;
}
.task-card:active { transform: scale(.985); }
.task-card:hover { border-color: #cfd9df; box-shadow: 0 8px 20px rgb(29 53 69 / 8%); }
.task-icon {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 13px;
  font-size: 21px;
}
.task-icon.blue { color: #286c98; background: #e7f2f8; }
.task-icon.copper { color: #b74d20; background: #fff0e8; }
.task-icon.green { color: #26764a; background: #e8f6ee; }
.task-icon.purple { color: #684b9a; background: #f0ebf8; }
.task-icon.gold { color: #95631c; background: #fff5dc; }
.task-copy { display: grid; min-width: 0; gap: 4px; }
.task-copy strong { font-size: 15px; }
.task-copy small { color: #71808b; font-size: 11px; line-height: 1.45; }
.task-arrow { color: #a5b0b8; }
.mobile-safety-note {
  display: grid;
  grid-template-columns: 28px 1fr;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid #e2e7ea;
  border-radius: 15px;
  color: #4c5b65;
  background: #eef2f4;
}
.mobile-safety-note > .el-icon { margin-top: 2px; color: #31566d; font-size: 19px; }
.mobile-safety-note div { display: grid; gap: 4px; }
.mobile-safety-note strong { font-size: 13px; }
.mobile-safety-note span { color: #6e7c85; font-size: 11px; line-height: 1.55; }
@media (max-width: 520px) {
  .task-grid { grid-template-columns: 1fr; }
  .task-card { min-height: 76px; }
}
</style>
