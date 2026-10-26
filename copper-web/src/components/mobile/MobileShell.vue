<template>
  <div class="mobile-shell" :class="appStore.fontSizeClass">
    <header class="mobile-shell-header">
      <button v-if="route.path !== '/mobile'" type="button" class="mobile-icon-button" aria-label="返回" @click="goBack">
        <el-icon><ArrowLeft /></el-icon>
      </button>
      <div v-else class="mobile-brand-mark">铜</div>
      <div class="mobile-shell-title">
        <strong>{{ route.meta.title }}</strong>
        <span>{{ appStore.settings.companyName }}</span>
      </div>
      <el-dropdown trigger="click" @command="handleCommand">
        <button type="button" class="mobile-user-button" aria-label="用户菜单">
          <el-avatar :size="34">{{ appStore.avatarText }}</el-avatar>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="mobile">手机办事首页</el-dropdown-item>
            <el-dropdown-item command="desktop">电脑版工作台</el-dropdown-item>
            <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </header>

    <div v-if="connectivity.bannerText" class="mobile-connectivity-banner" role="status" aria-live="polite">
      <span>{{ connectivity.bannerText }}</span>
      <el-button text size="small" :loading="connectivity.probing" @click="connectivity.probe()">重试</el-button>
    </div>

    <main class="mobile-shell-content">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import { ArrowLeft } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { useConnectivityStore } from '@/stores/connectivity'
import { useWorkspaceTabsStore } from '@/stores/workspaceTabs'
import { clearMobileDrafts } from '@/utils/mobileDrafts'

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const connectivity = useConnectivityStore()
const workspaceTabs = useWorkspaceTabsStore()

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/mobile')
}

async function handleCommand(command: string | number | object) {
  if (command === 'mobile') await router.push('/mobile')
  if (command === 'desktop') await router.push('/dashboard')
  if (command === 'logout') {
    clearMobileDrafts(appStore.session?.userId)
    workspaceTabs.reset()
    await appStore.logout()
    await router.replace('/login')
  }
}
</script>

<style scoped>
.mobile-shell {
  min-height: 100dvh;
  color: #18232d;
  background:
    radial-gradient(circle at 90% 0, rgb(201 92 42 / 9%), transparent 28rem),
    #f3f6f8;
}
.mobile-shell-header {
  position: sticky;
  z-index: 40;
  top: 0;
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) 42px;
  align-items: center;
  min-height: 58px;
  padding: max(6px, env(safe-area-inset-top)) 14px 6px;
  color: #fff;
  background: #0b2a3f;
  box-shadow: 0 1px 0 rgb(255 255 255 / 8%), 0 8px 24px rgb(8 35 56 / 12%);
}
.mobile-brand-mark {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 10px;
  color: #fff;
  background: #c95c2a;
  font-weight: 750;
}
.mobile-shell-title {
  display: grid;
  min-width: 0;
  gap: 1px;
  padding: 0 8px;
}
.mobile-shell-title strong {
  overflow: hidden;
  font-size: 17px;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mobile-shell-title span {
  overflow: hidden;
  color: rgb(255 255 255 / 67%);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mobile-icon-button,
.mobile-user-button {
  display: grid;
  width: 42px;
  height: 42px;
  padding: 0;
  place-items: center;
  border: 0;
  color: inherit;
  background: transparent;
  cursor: pointer;
}
.mobile-icon-button { font-size: 22px; }
.mobile-connectivity-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 9px 14px;
  border-bottom: 1px solid #ecd5bc;
  color: #6e3f13;
  background: #fff6e9;
  font-size: 12px;
  line-height: 1.5;
}
.mobile-shell-content {
  width: min(100%, 760px);
  min-height: calc(100dvh - 58px);
  margin: 0 auto;
  padding: 14px 14px calc(24px + env(safe-area-inset-bottom));
}
</style>
