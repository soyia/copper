import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import type { RouteLocationNormalizedLoaded } from 'vue-router'

export type WorkspaceTab = {
  key: string
  fullPath: string
  title: string
  closable: boolean
}

export type WorkspaceTabCloseMode = 'current' | 'others' | 'left' | 'right' | 'all'

export type WorkspaceTabCloseResult = {
  closedKeys: string[]
  targetPath?: string
}

const HOME_PATH = '/dashboard'
const STORAGE_KEY = 'copper-v2-workspace-tabs'
const MAX_TABS = 20

function homeTab(): WorkspaceTab {
  return { key: HOME_PATH, fullPath: HOME_PATH, title: '工作台', closable: false }
}

export function workspaceRouteKey(route: RouteLocationNormalizedLoaded) {
  // Filters and return-source query parameters update the existing work tab.
  // A business primary key is already part of the route path, so query values
  // must never create duplicate tabs for the same task surface.
  return route.path
}

function routeTitle(route: RouteLocationNormalizedLoaded) {
  const base = String(route.meta.title || '未命名页面')
  const titleParam = typeof route.meta.titleParam === 'string' ? route.meta.titleParam : 'id'
  const id = route.params[titleParam]
  const text = Array.isArray(id) ? id[0] : id
  return text ? `${base} #${text}` : base
}

function loadTabs(): WorkspaceTab[] {
  if (typeof window === 'undefined') return [homeTab()]
  try {
    const stored = (JSON.parse(window.localStorage.getItem(STORAGE_KEY) || '[]') as WorkspaceTab[]).map((tab) => {
      if (tab?.key === '/product-series') {
        return { ...tab, key: '/product-catalog', fullPath: '/product-catalog', title: '分类与规格' }
      }
      if (['/product-dictionaries', '/product-standards', '/product-tempers', '/product-identity-schemas', '/product-specs'].includes(tab?.key)) {
        return { ...tab, key: '/product-catalog', fullPath: '/product-catalog', title: '分类与规格' }
      }
      return tab
    })
    const valid = stored.filter(
      (tab) =>
        tab &&
        typeof tab.key === 'string' &&
        typeof tab.fullPath === 'string' &&
        typeof tab.title === 'string' &&
        tab.title.trim() &&
        tab.key !== '/' &&
        tab.key !== '/login',
    )
    const unique = valid.filter((tab, index) => valid.findIndex((item) => item.key === tab.key) === index)
    const withoutHome = unique.filter((tab) => tab.key !== HOME_PATH).slice(-(MAX_TABS - 1))
    return [homeTab(), ...withoutHome.map((tab) => ({ ...tab, closable: true }))]
  } catch {
    return [homeTab()]
  }
}

export const useWorkspaceTabsStore = defineStore('workspace-tabs', () => {
  const tabs = ref<WorkspaceTab[]>(loadTabs())
  const activeKey = ref(HOME_PATH)
  const cacheVersions = ref<Record<string, number>>({})

  const activeTab = computed(() => tabs.value.find((tab) => tab.key === activeKey.value) ?? tabs.value[0])

  function registerRoute(route: RouteLocationNormalizedLoaded) {
    if (route.path === '/' || route.path === '/login' || route.meta.public) return
    const key = workspaceRouteKey(route)
    const current = tabs.value.find((tab) => tab.key === key)
    if (current) {
      current.fullPath = route.fullPath
      current.title = key === HOME_PATH ? '工作台' : routeTitle(route)
    } else {
      tabs.value.push({ key, fullPath: route.fullPath, title: routeTitle(route), closable: key !== HOME_PATH })
      trimTabs(key)
    }
    activeKey.value = key
  }

  function trimTabs(keepKey: string) {
    while (tabs.value.length > MAX_TABS) {
      const index = tabs.value.findIndex((tab) => tab.closable && tab.key !== keepKey)
      if (index < 0) return
      invalidateCache(tabs.value[index].key)
      tabs.value.splice(index, 1)
    }
  }

  function closeTab(key: string): WorkspaceTabCloseResult {
    const index = tabs.value.findIndex((tab) => tab.key === key)
    if (index < 0 || !tabs.value[index].closable) return { closedKeys: [] }
    const wasActive = activeKey.value === key
    invalidateCache(key)
    tabs.value.splice(index, 1)
    if (!wasActive) return { closedKeys: [key] }
    const target = tabs.value[Math.min(index, tabs.value.length - 1)] ?? tabs.value[0]
    activeKey.value = target.key
    return { closedKeys: [key], targetPath: target.fullPath }
  }

  function closeTabs(mode: WorkspaceTabCloseMode, anchorKey = activeKey.value): WorkspaceTabCloseResult {
    if (mode === 'current') return closeTab(anchorKey)

    const anchorIndex = tabs.value.findIndex((tab) => tab.key === anchorKey)
    if (anchorIndex < 0) return { closedKeys: [] }

    const closedKeys = tabs.value
      .filter((tab, index) => tab.closable && (
        mode === 'all'
        || (mode === 'others' && tab.key !== anchorKey)
        || (mode === 'left' && index < anchorIndex)
        || (mode === 'right' && index > anchorIndex)
      ))
      .map((tab) => tab.key)

    if (!closedKeys.length) return { closedKeys: [] }
    const closed = new Set(closedKeys)
    const activeWasClosed = closed.has(activeKey.value)
    for (const key of closedKeys) invalidateCache(key)
    tabs.value = tabs.value.filter((tab) => !closed.has(tab.key))

    if (!activeWasClosed) return { closedKeys }
    const target = tabs.value.find((tab) => tab.key === anchorKey)
      ?? tabs.value[Math.min(anchorIndex, tabs.value.length - 1)]
      ?? tabs.value[0]
    activeKey.value = target.key
    return { closedKeys, targetPath: target.fullPath }
  }

  function cacheKey(route: RouteLocationNormalizedLoaded) {
    const key = workspaceRouteKey(route)
    return `${key}::${cacheVersions.value[key] ?? 0}`
  }

  function invalidateCache(key: string) {
    cacheVersions.value[key] = (cacheVersions.value[key] ?? 0) + 1
  }

  function refreshTab(key: string) {
    if (!tabs.value.some((tab) => tab.key === key)) return false
    invalidateCache(key)
    return true
  }

  function setTitle(key: string, title: string) {
    const tab = tabs.value.find((item) => item.key === key)
    const normalized = title.trim()
    if (!tab || !normalized || tab.title === normalized) return false
    tab.title = normalized
    return true
  }

  function reset() {
    for (const tab of tabs.value) invalidateCache(tab.key)
    tabs.value = [homeTab()]
    activeKey.value = HOME_PATH
  }

  watch(
    tabs,
    (value) => {
      if (typeof window !== 'undefined') window.localStorage.setItem(STORAGE_KEY, JSON.stringify(value))
    },
    { deep: true, immediate: true },
  )

  return { tabs, activeKey, activeTab, registerRoute, closeTab, closeTabs, refreshTab, setTitle, cacheKey, reset }
})
