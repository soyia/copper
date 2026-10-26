<template>
  <router-view v-if="isLoginRoute" />
  <MobileShell v-else-if="isMobileRoute" />
  <el-container v-else class="app-shell" :class="appStore.fontSizeClass">
    <el-aside class="sidebar" width="188px">
      <div class="brand">
        <div class="brand-mark">铜</div>
        <div>
          <div class="brand-title">{{ appStore.settings.companyName }}</div>
          <div class="brand-subtitle">{{ appStore.settings.companyPhone || 'Copper V2' }}</div>
        </div>
      </div>

      <el-menu
        :default-active="activeMenuPath"
        :default-openeds="activeMenuOpeneds"
        router
        unique-opened
        class="nav"
        background-color="#082338"
        text-color="#d8e6ef"
        active-text-color="#ffffff"
      >
        <el-menu-item index="/dashboard">
          <el-icon><HomeFilled /></el-icon>
          <span>工作台</span>
        </el-menu-item>
        <el-sub-menu v-for="group in menuGroups" :key="group.index" :index="group.index">
          <template #title>
            <el-icon><component :is="group.icon" /></el-icon>
            <span>{{ group.label }}</span>
          </template>
          <template v-for="item in group.items" :key="item.path || item.index">
            <el-sub-menu v-if="item.children?.length" :index="item.index || item.label" class="nav-second-level-group">
              <template #title>
                <el-icon><component :is="item.icon" /></el-icon>
                <span>{{ item.label }}</span>
              </template>
              <el-menu-item v-for="child in item.children" :key="child.path" :index="child.path" class="nav-third-level-item">
                <el-icon><component :is="child.icon" /></el-icon>
                <span>{{ child.label }}</span>
              </el-menu-item>
            </el-sub-menu>
            <el-menu-item v-else-if="item.path" :index="item.path" class="nav-second-level-item">
              <el-icon><component :is="item.icon" /></el-icon>
              <span>{{ item.label }}</span>
            </el-menu-item>
          </template>
        </el-sub-menu>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="topbar">
        <div class="page-title">{{ $route.meta.title }}</div>
        <div class="topbar-actions">
          <el-button plain class="help-center-entry" @click="router.push('/help')">
            <el-icon><QuestionFilled /></el-icon>
            帮助
          </el-button>
          <el-button plain class="mobile-workbench-entry" @click="router.push('/mobile')">
            <el-icon><Iphone /></el-icon>
            手机办事
          </el-button>
          <ConnectivityIndicator />
          <span class="clock">{{ now }}</span>
          <el-dropdown trigger="click" @command="handleUserCommand">
            <button type="button" class="user-trigger">
              <span class="user-summary">
                <span class="user-name">{{ appStore.currentUserName }}</span>
                <span v-if="appStore.currentDepartmentName" class="user-department">{{ appStore.currentDepartmentName }}</span>
              </span>
              <el-avatar :size="32">{{ appStore.avatarText }}</el-avatar>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="settings">
                  <el-icon><Setting /></el-icon>
                  <span>设置</span>
                </el-dropdown-item>
                <el-dropdown-item command="logout" divided>
                  <el-icon><SwitchButton /></el-icon>
                  <span>退出</span>
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <div
        v-if="connectivity.bannerText"
        class="connectivity-banner"
        :class="`is-${connectivity.state.toLowerCase()}`"
        role="status"
        aria-live="polite"
      >
        <span>{{ connectivity.bannerText }}</span>
        <el-button text size="small" :loading="connectivity.probing" @click="connectivity.probe()">重新检测</el-button>
      </div>

      <WorkspaceTabs />

      <el-main class="content">
        <router-view v-slot="{ Component, route: viewRoute }">
          <keep-alive :max="20">
            <component :is="Component" :key="workspaceTabs.cacheKey(viewRoute)" />
          </keep-alive>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
  <ForcedPasswordChangeDialog
    :visible="forcePasswordChange"
    @success="handlePasswordChanged"
    @logout="handleForcedLogout"
  />
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, watch, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Box,
  DataAnalysis,
  Download,
  DocumentAdd,
  Files,
  Goods,
  HomeFilled,
  Iphone,
  Money,
  OfficeBuilding,
  PriceTag,
  QuestionFilled,
  ScaleToOriginal,
  Setting,
  SwitchButton,
  Tickets,
  Upload,
  UserFilled,
  Lock,
  View,
} from '@element-plus/icons-vue'
import { currentDateTimeText } from '@/api/format'
import ConnectivityIndicator from '@/components/ConnectivityIndicator.vue'
import ForcedPasswordChangeDialog from '@/components/ForcedPasswordChangeDialog.vue'
import MobileShell from '@/components/mobile/MobileShell.vue'
import WorkspaceTabs from '@/components/WorkspaceTabs.vue'
import { useAppStore } from '@/stores/app'
import { useConnectivityStore } from '@/stores/connectivity'
import { useWorkspaceTabsStore } from '@/stores/workspaceTabs'
import { clearMobileDrafts } from '@/utils/mobileDrafts'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const connectivity = useConnectivityStore()
const workspaceTabs = useWorkspaceTabsStore()
const isLoginRoute = computed(() => Boolean(route.meta.public))
const isMobileRoute = computed(() => Boolean(route.meta.mobile))
const forcePasswordChange = computed(() =>
  appStore.isAuthenticated
  && Boolean(appStore.session?.passwordChangeRequired)
  && !isLoginRoute.value,
)
type MenuLeaf = { path: string; label: string; icon: Component; permission?: string; permissionsAny?: string[]; index?: never; children?: never }
type MenuBranch = { index: string; label: string; icon: Component; children: MenuLeaf[]; path?: never }
type MenuItem = MenuLeaf | MenuBranch
type MenuGroup = { index: string; label: string; icon: Component; items: MenuItem[] }

const allMenuGroups: MenuGroup[] = [
  {
    index: 'crm',
    label: '客户经营',
    icon: UserFilled,
    items: [
      { path: '/partners', label: '往来单位', icon: OfficeBuilding, permission: 'partner.view' },
      { path: '/crm', label: '客户待办', icon: DataAnalysis, permission: 'crm.analysis.view' },
      { path: '/crm/customers', label: '客户经营档案', icon: OfficeBuilding, permission: 'crm.record.view' },
      { path: '/crm/quotes', label: '报价', icon: Tickets, permission: 'crm.record.view' },
      { path: '/crm/activities', label: '活动与计划', icon: Files, permission: 'crm.record.view' },
      { path: '/crm/leads', label: '线索（可选）', icon: UserFilled, permission: 'crm.record.view' },
      { path: '/crm/opportunities', label: '商机（可选）', icon: DataAnalysis, permission: 'crm.record.view' },
    ],
  },
  {
    index: 'sales',
    label: '销售管理',
    icon: Upload,
    items: [
      { path: '/sales-orders', label: '销售订单', icon: Tickets, permission: 'sales.view' },
      { path: '/direct-trades', label: '直运购销', icon: Files, permission: 'direct.trade.view' },
      { path: '/outbounds', label: '销售出库', icon: Upload, permission: 'outbound.view' },
    ],
  },
  {
    index: 'purchase',
    label: '采购管理',
    icon: DocumentAdd,
    items: [
      { path: '/purchase-orders', label: '采购订单', icon: Tickets, permission: 'purchase.view' },
      { path: '/inbounds', label: '进货入库', icon: DocumentAdd, permission: 'inbound.view' },
      { path: '/inventory/purchase-returns', label: '采购退货', icon: Tickets, permission: 'purchase.view' },
      { path: '/inventory/landed-cost-allocations', label: '采购费用分摊', icon: Money, permission: 'landed.cost.view' },
    ],
  },
  {
    index: 'inventory',
    label: '库存管理',
    icon: Box,
    items: [
      { path: '/inventory', label: '仓库库存', icon: Box, permission: 'inventory.view' },
      { path: '/inventory/non-trade', label: '送样 / 领用 / 损耗', icon: Tickets, permission: 'inventory.nontrade.view' },
      { path: '/inventory/atp', label: '库存可承诺量', icon: DataAnalysis, permission: 'inventory.view' },
      { path: '/inventory/transfers', label: '库存调拨', icon: Upload, permission: 'inventory.view' },
      { path: '/inventory/stocktakes', label: '库存盘点', icon: ScaleToOriginal, permission: 'inventory.view' },
      { path: '/inventory/movements', label: '库存流水', icon: DataAnalysis, permission: 'inventory.view' },
      { path: '/inventory/reconciliation', label: '库存勾稽', icon: DataAnalysis, permission: 'inventory.view' },
      { path: '/inventory/cost-adjustments', label: '成本调整', icon: Money, permission: 'inventory.cost.view' },
    ],
  },
  {
    index: 'finance',
    label: '往来结算',
    icon: Money,
    items: [
      {
        index: 'receivable-settlement',
        label: '应收与收款',
        icon: DataAnalysis,
        children: [
          { path: '/receivables', label: '应收账款', icon: DataAnalysis, permission: 'finance.view' },
          { path: '/payments', label: '回款核销', icon: Money, permission: 'finance.view' },
          { path: '/customer-advances', label: '客户预收款', icon: Money, permission: 'finance.view' },
          { path: '/receivable-adjustments', label: '应收调整单', icon: DataAnalysis, permission: 'finance.view' },
        ],
      },
      {
        index: 'payable-settlement',
        label: '应付与付款',
        icon: DataAnalysis,
        children: [
          { path: '/payables', label: '应付账款', icon: DataAnalysis, permission: 'finance.view' },
          { path: '/supplier-payments', label: '供应商付款', icon: Money, permission: 'finance.view' },
        ],
      },
      {
        index: 'cash-bills',
        label: '资金与票据',
        icon: Money,
        children: [
          { path: '/treasury', label: '费用与出纳', icon: Money, permission: 'treasury.view' },
          { path: '/treasury/journal', label: '出纳日记账', icon: DataAnalysis, permission: 'treasury.view' },
          { path: '/acceptance-bills', label: '承兑票据', icon: Tickets, permission: 'finance.view' },
          { path: '/partner-fund-adjustments', label: '余额调整单', icon: Money, permission: 'partner.fund.adjust.view' },
        ],
      },
    ],
  },
  {
    index: 'operations',
    label: '经营管理',
    icon: DataAnalysis,
    items: [
      { path: '/operations-assistant', label: '经营助手', icon: DataAnalysis, permission: 'operations.insight.view' },
      { path: '/price-center', label: '铜材价格中心', icon: PriceTag, permission: 'pricing.view' },
      { path: '/risk-exceptions', label: '例外审批', icon: Lock, permission: 'risk.exception.view' },
      { path: '/platform', label: '平台货源', icon: Files, permission: 'platform.view' },
    ],
  },
  {
    index: 'invoice',
    label: '发票税务',
    icon: Tickets,
    items: [
      { path: '/tax-invoices', label: '发票工作台', icon: DataAnalysis, permission: 'invoice.view' },
      { path: '/tax-invoices/output', label: '销项发票', icon: Upload, permission: 'invoice.view' },
      { path: '/tax-invoices/input', label: '进项发票', icon: Download, permission: 'invoice.view' },
      { path: '/tax-invoices/reconciliation', label: '税票勾稽', icon: DataAnalysis, permission: 'invoice.view' },
      { path: '/tax-compliance', label: '电子凭证与票账税', icon: Files, permission: 'tax.compliance.view' },
    ],
  },
  {
    index: 'accounting',
    label: '会计核算',
    icon: Tickets,
    items: [
      { path: '/accounting', label: '会计工作台', icon: DataAnalysis, permission: 'accounting.view' },
      { path: '/business-postings', label: '业务过账', icon: DataAnalysis, permission: 'posting.view' },
      { path: '/accounting/vouchers', label: '会计凭证', icon: Tickets, permission: 'accounting.view' },
      { path: '/accounting/ledger', label: '总账与明细账', icon: DataAnalysis, permission: 'accounting.view' },
      { path: '/accounting/reports', label: '财务报表', icon: DataAnalysis, permission: 'financial.report.view' },
      { path: '/accounting/bank-reconciliation', label: '银行对账', icon: Money, permission: 'financial.bank.view' },
      { path: '/accounting/period-close', label: '期末结账', icon: Lock, permission: 'financial.report.view' },
      { path: '/accounting/master-data', label: '科目与期间', icon: Files, permission: 'accounting.view' },
      { path: '/accounting/rules', label: '自动制证规则', icon: Setting, permission: 'accounting.view' },
    ],
  },
  {
    index: 'master',
    label: '基础资料',
    icon: OfficeBuilding,
    items: [
      {
        index: 'product-master',
        label: '商品主数据',
        icon: PriceTag,
        children: [
          { path: '/products', label: '商品资料', icon: Goods, permission: 'product.view' },
          { path: '/product-catalog', label: '分类与规格', icon: PriceTag, permission: 'product.view' },
          { path: '/product-packaging', label: '计量与包装', icon: ScaleToOriginal, permission: 'product.view' },
          { path: '/products/import', label: '批量导入（高级）', icon: Upload, permissionsAny: ['product.master.complex.view', 'product.master.complex.manage'] },
        ],
      },
      { path: '/warehouses', label: '仓库资料', icon: Box, permission: 'warehouse.view' },
      { path: '/units', label: '计量单位', icon: ScaleToOriginal, permission: 'product.view' },
    ],
  },
  {
    index: 'system',
    label: '系统管理',
    icon: Setting,
    items: [
      { path: '/settings', label: '系统设置', icon: Setting },
      { path: '/organization', label: '部门人员', icon: OfficeBuilding, permission: 'organization.view' },
      { path: '/system/users', label: '用户管理', icon: UserFilled, permission: 'user.view' },
      { path: '/system/roles', label: '角色权限', icon: Lock, permission: 'role.view' },
      { path: '/system/security-audit', label: '安全审计', icon: View, permission: 'security.audit.view' },
      { path: '/data-migrations', label: '期初数据迁移', icon: Upload, permission: 'data.migration.view' },
      { path: '/data-exports', label: '完整数据导出', icon: Download, permission: 'data.export' },
      { path: '/storage-capacity', label: '存储与容量', icon: Files, permission: 'storage.capacity.view' },
    ],
  },
]

const menuGroups = computed<MenuGroup[]>(() => allMenuGroups
  .map((group) => ({
    ...group,
    items: group.items
      .map((item) => item.children
        ? { ...item, children: item.children.filter(canAccessMenuLeaf) }
        : item)
      .filter((item) => item.children ? item.children.length > 0 : canAccessMenuLeaf(item)),
  }))
  .filter((group) => group.items.length > 0))

function canAccessMenuLeaf(item: MenuLeaf) {
  if (item.permissionsAny?.length) return item.permissionsAny.some((permission) => appStore.hasPermission(permission))
  return appStore.hasPermission(item.permission)
}

const now = computed(() => currentDateTimeText(new Date(), ' '))
const flatItems = computed(() => menuGroups.value.flatMap((group) =>
  group.items.flatMap((item) => item.children ?? [item]),
))
const activeMenuPath = computed(() => {
  const current = route.path
  if (current === '/settings') return '/settings'
  return [...flatItems.value]
    .sort((left, right) => right.path.length - left.path.length)
    .find((item) => current === item.path || current.startsWith(`${item.path}/`))?.path ?? current
})
const activeMenuGroup = computed(() => menuGroups.value.find((group) => group.items.some((item) =>
  activeMenuPath.value === item.path || item.children?.some((child) => child.path === activeMenuPath.value),
))?.index ?? '')
const activeMenuBranch = computed(() => menuGroups.value
  .flatMap((group) => group.items)
  .find((item): item is MenuBranch => Boolean(item.children?.some((child) => child.path === activeMenuPath.value)))
  ?.index ?? '')
const activeMenuOpeneds = computed(() => [activeMenuGroup.value, activeMenuBranch.value].filter(Boolean))

onMounted(() => {
  window.addEventListener('copper:unauthorized', handleUnauthorized)
  window.addEventListener('copper:password-changed', handlePasswordChanged)
})
onBeforeUnmount(() => {
  window.removeEventListener('copper:unauthorized', handleUnauthorized)
  window.removeEventListener('copper:password-changed', handlePasswordChanged)
  connectivity.stop()
  if (typeof document !== 'undefined') delete document.body.dataset.copperFontSize
})

watch(
  () => appStore.settings.fontSize,
  (fontSize) => {
    if (typeof document !== 'undefined') document.body.dataset.copperFontSize = fontSize
  },
  { immediate: true },
)

watch(
  () => appStore.isAuthenticated,
  (authenticated) => {
    if (authenticated) connectivity.start()
    else connectivity.stop()
  },
  { immediate: true },
)

watch(
  () => route.fullPath,
  () => {
    if (isLoginRoute.value || isMobileRoute.value) return
    workspaceTabs.registerRoute(route)
  },
  { immediate: true },
)

function handleUnauthorized() {
  if (route.path === '/login') return
  clearMobileDrafts(appStore.session?.userId)
  discardWorkspaceDrafts()
  workspaceTabs.reset()
  appStore.session = null
  appStore.sessionChecked = true
  ElMessage.warning('登录状态已失效，请重新登录')
  router.replace({ path: '/login', query: { redirect: route.fullPath } })
}

function handlePasswordChanged() {
  clearCurrentWorkspace()
  appStore.session = null
  appStore.sessionChecked = true
  ElMessage.success('密码已修改，请使用新密码重新登录')
  router.replace('/login')
}

async function handleForcedLogout() {
  clearCurrentWorkspace()
  await appStore.logout()
  ElMessage.success('已退出登录')
  router.replace('/login')
}

async function handleUserCommand(command: string | number | object) {
  if (command === 'settings') {
    router.push('/settings')
    return
  }
  if (command === 'logout') {
    clearCurrentWorkspace()
    await appStore.logout()
    ElMessage.success('已退出登录')
    router.replace('/login')
  }
}

function clearCurrentWorkspace() {
  clearMobileDrafts(appStore.session?.userId)
  discardWorkspaceDrafts()
  workspaceTabs.reset()
}

function discardWorkspaceDrafts() {
  for (const tab of workspaceTabs.tabs) {
    window.dispatchEvent(new CustomEvent('copper:workspace-tab-closed', { detail: { key: tab.key } }))
  }
}
</script>
