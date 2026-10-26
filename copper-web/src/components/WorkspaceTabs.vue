<template>
  <nav class="workspace-tabs" aria-label="工作区页签">
    <button
      type="button"
      class="workspace-tabs-nav"
      :disabled="!canScrollLeft"
      aria-label="向左滚动页签"
      title="向左滚动"
      @click="scrollTabs(-320)"
    >
      <el-icon><ArrowLeft /></el-icon>
    </button>

    <div
      ref="tabsTrack"
      class="workspace-tabs-track"
      role="tablist"
      aria-label="已打开页面"
      @scroll="updateScrollState"
      @wheel="handleWheel"
    >
      <div
        v-for="(tab, index) in workspaceTabs.tabs"
        :key="tab.key"
        class="workspace-tab-slot"
        :class="{
          'is-active-slot': workspaceTabs.activeKey === tab.key,
          'has-leading-divider':
            index > 0 &&
            workspaceTabs.activeKey !== tab.key &&
            workspaceTabs.tabs[index - 1]?.key !== workspaceTabs.activeKey,
        }"
      >
        <div
          class="workspace-tab"
          :class="{
            'is-active': workspaceTabs.activeKey === tab.key,
            'is-pinned': !tab.closable,
          }"
          :data-tab-key="tab.key"
          :title="tab.title"
          @auxclick.prevent="handleAuxClick($event, tab)"
          @contextmenu.prevent="openContextMenu($event, tab)"
        >
          <button
            type="button"
            role="tab"
            class="workspace-tab-main"
            :data-activate-tab="tab.key"
            :aria-label="`切换到${tab.title}`"
            :aria-selected="workspaceTabs.activeKey === tab.key"
            :tabindex="workspaceTabs.activeKey === tab.key ? 0 : -1"
            @click="activateTab(tab)"
            @keydown="handleTabKeydown($event, tab)"
          >
            <el-icon v-if="!tab.closable" class="workspace-tab-home"><HomeFilled /></el-icon>
            <span class="workspace-tab-title">{{ tab.title }}</span>
          </button>
          <button
            v-if="tab.closable"
            type="button"
            class="workspace-tab-close"
            :aria-label="`关闭${tab.title}`"
            :data-close-tab="tab.key"
            title="关闭页签"
            @click.stop="closeTabs('current', tab.key)"
          >
            <el-icon><Close /></el-icon>
          </button>
        </div>
      </div>
    </div>

    <button
      type="button"
      class="workspace-tabs-nav"
      :disabled="!canScrollRight"
      aria-label="向右滚动页签"
      title="向右滚动"
      @click="scrollTabs(320)"
    >
      <el-icon><ArrowRight /></el-icon>
    </button>

    <span class="workspace-tabs-divider" aria-hidden="true" />

    <el-dropdown
      trigger="click"
      placement="bottom-end"
      popper-class="workspace-tabs-manager-menu"
      @command="handleManagerCommand"
    >
      <button
        type="button"
        class="workspace-tabs-manager"
        aria-label="管理工作区页签"
        title="页签管理"
      >
        <el-icon><MoreFilled /></el-icon>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <div class="workspace-tabs-menu-heading">已打开页面 · {{ workspaceTabs.tabs.length }}</div>
          <el-dropdown-item
            v-for="tab in workspaceTabs.tabs"
            :key="tab.key"
            :command="{ action: 'activate', key: tab.key }"
            :class="{ 'is-current-tab': workspaceTabs.activeKey === tab.key }"
          >
            <el-icon class="workspace-tabs-menu-marker">
              <Check v-if="workspaceTabs.activeKey === tab.key" />
              <HomeFilled v-else-if="!tab.closable" />
            </el-icon>
            <span class="workspace-tabs-menu-title" :title="tab.title">{{ tab.title }}</span>
            <span v-if="!tab.closable" class="workspace-tabs-menu-fixed">固定</span>
          </el-dropdown-item>
          <el-dropdown-item
            :command="{ action: 'current' }"
            :disabled="!workspaceTabs.activeTab.closable"
            divided
          >
            关闭当前页签
          </el-dropdown-item>
          <el-dropdown-item :command="{ action: 'others' }" :disabled="!canCloseOthers(workspaceTabs.activeKey)">
            关闭其他页签
          </el-dropdown-item>
          <el-dropdown-item :command="{ action: 'right' }" :disabled="!canCloseRight(workspaceTabs.activeKey)">
            关闭右侧页签
          </el-dropdown-item>
          <el-dropdown-item :command="{ action: 'all' }" :disabled="!hasClosableTabs">
            关闭全部页签
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>

    <Teleport to="body">
      <div
        v-if="contextMenu && contextTab"
        class="workspace-tab-context-menu-panel"
        role="menu"
        :aria-label="`${contextTab.title}页签操作`"
        :style="{ left: `${contextMenu.x}px`, top: `${contextMenu.y}px` }"
        @pointerdown.stop
        @contextmenu.prevent
        @keydown.esc="closeContextMenu"
      >
        <button type="button" role="menuitem" @click="runContextAction('refresh')">
          <el-icon><RefreshRight /></el-icon>
          <span>刷新此页面</span>
        </button>
        <span class="workspace-tab-context-divider" aria-hidden="true" />
        <button type="button" role="menuitem" :disabled="!contextTab.closable" @click="runContextAction('current')">
          关闭当前页签
        </button>
        <button type="button" role="menuitem" :disabled="!canCloseOthers(contextTab.key)" @click="runContextAction('others')">
          关闭其他页签
        </button>
        <button type="button" role="menuitem" :disabled="!canCloseLeft(contextTab.key)" @click="runContextAction('left')">
          关闭左侧页签
        </button>
        <button type="button" role="menuitem" :disabled="!canCloseRight(contextTab.key)" @click="runContextAction('right')">
          关闭右侧页签
        </button>
        <span class="workspace-tab-context-divider" aria-hidden="true" />
        <button type="button" role="menuitem" :disabled="!hasClosableTabs" @click="runContextAction('all')">
          关闭全部页签
        </button>
      </div>
    </Teleport>
  </nav>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft,
  ArrowRight,
  Check,
  Close,
  HomeFilled,
  MoreFilled,
  RefreshRight,
} from '@element-plus/icons-vue'
import {
  useWorkspaceTabsStore,
  type WorkspaceTab,
  type WorkspaceTabCloseMode,
} from '@/stores/workspaceTabs'

type TabMenuCommand = {
  action: WorkspaceTabCloseMode | 'activate' | 'refresh'
  key?: string
}

const router = useRouter()
const workspaceTabs = useWorkspaceTabsStore()
const tabsTrack = ref<HTMLElement>()
const canScrollLeft = ref(false)
const canScrollRight = ref(false)
const contextMenu = ref<{ x: number; y: number; tabKey: string }>()
let resizeObserver: ResizeObserver | undefined

const hasClosableTabs = computed(() => workspaceTabs.tabs.some((tab) => tab.closable))
const contextTab = computed(() => contextMenu.value
  ? workspaceTabs.tabs.find((tab) => tab.key === contextMenu.value?.tabKey)
  : undefined)

onMounted(() => {
  if (tabsTrack.value) {
    resizeObserver = new ResizeObserver(updateScrollState)
    resizeObserver.observe(tabsTrack.value)
  }
  window.addEventListener('pointerdown', closeContextMenu)
  window.addEventListener('blur', closeContextMenu)
  nextTick(() => {
    updateScrollState()
    scrollActiveTabIntoView(false)
  })
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  window.removeEventListener('pointerdown', closeContextMenu)
  window.removeEventListener('blur', closeContextMenu)
})

watch(
  () => [workspaceTabs.tabs.map((tab) => `${tab.key}:${tab.title}`).join('|'), workspaceTabs.activeKey],
  () => nextTick(() => {
    updateScrollState()
    scrollActiveTabIntoView()
  }),
)

function activateTab(tab: WorkspaceTab) {
  if (tab.key !== workspaceTabs.activeKey) router.push(tab.fullPath)
}

function handleAuxClick(event: MouseEvent, tab: WorkspaceTab) {
  if (event.button === 1 && tab.closable) closeTabs('current', tab.key)
}

function handleTabKeydown(event: KeyboardEvent, tab: WorkspaceTab) {
  if (event.key === 'ContextMenu' || (event.shiftKey && event.key === 'F10')) {
    event.preventDefault()
    const element = event.currentTarget as HTMLElement
    const rect = element.getBoundingClientRect()
    showContextMenu(rect.left + 16, rect.bottom + 4, tab)
    return
  }
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const currentIndex = workspaceTabs.tabs.findIndex((item) => item.key === tab.key)
  const targetIndex = event.key === 'Home'
    ? 0
    : event.key === 'End'
      ? workspaceTabs.tabs.length - 1
      : currentIndex + (event.key === 'ArrowLeft' ? -1 : 1)
  const target = workspaceTabs.tabs[Math.max(0, Math.min(targetIndex, workspaceTabs.tabs.length - 1))]
  if (!target || target.key === tab.key) return
  activateTab(target)
  nextTick(() => tabsTrack.value
    ?.querySelector<HTMLElement>(`[data-tab-key="${CSS.escape(target.key)}"] .workspace-tab-main`)
    ?.focus())
}

function handleManagerCommand(value: unknown) {
  const command = value as TabMenuCommand
  if (command.action === 'activate' && command.key) {
    const tab = workspaceTabs.tabs.find((item) => item.key === command.key)
    if (tab) activateTab(tab)
    return
  }
  if (command.action === 'refresh') return
  closeTabs(command.action as WorkspaceTabCloseMode, workspaceTabs.activeKey)
}

function openContextMenu(event: MouseEvent, tab: WorkspaceTab) {
  showContextMenu(event.clientX, event.clientY, tab)
}

function showContextMenu(clientX: number, clientY: number, tab: WorkspaceTab) {
  const width = 188
  const height = 266
  contextMenu.value = {
    x: Math.max(8, Math.min(clientX, window.innerWidth - width - 8)),
    y: Math.max(8, Math.min(clientY, window.innerHeight - height - 8)),
    tabKey: tab.key,
  }
}

function closeContextMenu() {
  contextMenu.value = undefined
}

function runContextAction(action: WorkspaceTabCloseMode | 'refresh') {
  const tab = contextTab.value
  if (!tab) return
  closeContextMenu()
  if (action === 'refresh') {
    refreshTab(tab)
    return
  }
  closeTabs(action, tab.key)
}

function closeTabs(mode: WorkspaceTabCloseMode, anchorKey: string) {
  const result = workspaceTabs.closeTabs(mode, anchorKey)
  for (const key of result.closedKeys) {
    window.dispatchEvent(new CustomEvent('copper:workspace-tab-closed', { detail: { key } }))
  }
  if (result.targetPath) router.push(result.targetPath)
}

function refreshTab(tab: WorkspaceTab) {
  workspaceTabs.refreshTab(tab.key)
  if (tab.key !== workspaceTabs.activeKey) router.push(tab.fullPath)
  ElMessage.success(`已刷新“${tab.title}”`)
}

function canCloseOthers(anchorKey: string) {
  return workspaceTabs.tabs.some((tab) => tab.closable && tab.key !== anchorKey)
}

function canCloseLeft(anchorKey: string) {
  const index = workspaceTabs.tabs.findIndex((tab) => tab.key === anchorKey)
  return index > 0 && workspaceTabs.tabs.slice(0, index).some((tab) => tab.closable)
}

function canCloseRight(anchorKey: string) {
  const index = workspaceTabs.tabs.findIndex((tab) => tab.key === anchorKey)
  return index >= 0 && workspaceTabs.tabs.slice(index + 1).some((tab) => tab.closable)
}

function scrollTabs(distance: number) {
  tabsTrack.value?.scrollBy({ left: distance, behavior: 'smooth' })
}

function scrollActiveTabIntoView(smooth = true) {
  tabsTrack.value?.querySelector<HTMLElement>('.workspace-tab.is-active')?.scrollIntoView({
    behavior: smooth ? 'smooth' : 'auto',
    block: 'nearest',
    inline: 'nearest',
  })
}

function updateScrollState() {
  const track = tabsTrack.value
  if (!track) return
  const maxScroll = Math.max(0, track.scrollWidth - track.clientWidth)
  canScrollLeft.value = track.scrollLeft > 1
  canScrollRight.value = track.scrollLeft < maxScroll - 1
}

function handleWheel(event: WheelEvent) {
  const track = tabsTrack.value
  if (!track || track.scrollWidth <= track.clientWidth) return
  const delta = Math.abs(event.deltaX) > Math.abs(event.deltaY) ? event.deltaX : event.deltaY
  if (!delta) return
  event.preventDefault()
  track.scrollLeft += delta
}
</script>
