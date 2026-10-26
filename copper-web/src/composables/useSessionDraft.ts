import { onBeforeUnmount, onMounted, ref, toValue, watch, type MaybeRefOrGetter, type WatchStopHandle } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

interface StoredSessionDraft<T> {
  version: 1
  savedAt: string
  data: T
}

interface SessionDraftOptions<T> {
  storageKey: () => string
  snapshot: () => T
  restore: (data: T) => void | Promise<void>
  ready?: MaybeRefOrGetter<boolean>
  label: string
  confirmRestore?: boolean
  additionalDirty?: () => boolean
  onRestored?: () => void
  workspaceKey?: string
}

export function useSessionDraft<T>(options: SessionDraftOptions<T>) {
  const isDirty = ref(false)
  const restoredAt = ref<string>()
  let baseline = ''
  let started = false
  let saveTimer: number | undefined
  let stopReady: WatchStopHandle | undefined
  let stopSnapshot: WatchStopHandle | undefined

  onMounted(() => {
    window.addEventListener('beforeunload', handleBeforeUnload)
    window.addEventListener('copper:workspace-tab-closed', handleWorkspaceTabClosed as EventListener)
    stopReady = watch(
      () => options.ready === undefined || toValue(options.ready),
      (ready) => {
        if (ready && !started) void start()
      },
      { immediate: true },
    )
  })

  onBeforeUnmount(() => {
    if (saveTimer !== undefined) window.clearTimeout(saveTimer)
    stopReady?.()
    stopSnapshot?.()
    window.removeEventListener('beforeunload', handleBeforeUnload)
    window.removeEventListener('copper:workspace-tab-closed', handleWorkspaceTabClosed as EventListener)
  })

  async function start() {
    if (started) return
    started = true
    baseline = serialize(options.snapshot())
    const stored = readStoredDraft()
    if (stored) {
      let restore = true
      if (options.confirmRestore) {
        restore = await ElMessageBox.confirm(
          `检测到 ${formatSavedAt(stored.savedAt)} 自动保存的${options.label}未提交内容，是否恢复？`,
          '恢复未提交内容',
          { confirmButtonText: '恢复内容', cancelButtonText: '放弃本地内容', type: 'warning' },
        ).then(() => true).catch(() => false)
      }
      if (restore) {
        await options.restore(stored.data)
        restoredAt.value = stored.savedAt
        isDirty.value = true
        options.onRestored?.()
        ElMessage.success(`已恢复${options.label}未提交内容`)
      } else {
        removeStoredDraft()
      }
    }

    stopSnapshot = watch(
      () => serialize(options.snapshot()),
      (value) => {
        isDirty.value = value !== baseline
        if (saveTimer !== undefined) window.clearTimeout(saveTimer)
        saveTimer = window.setTimeout(() => persist(value), 250)
      },
      { flush: 'post' },
    )
  }

  function persist(serialized: string) {
    if (!isDirty.value) {
      removeStoredDraft()
      return
    }
    try {
      const stored: StoredSessionDraft<T> = {
        version: 1,
        savedAt: new Date().toISOString(),
        data: JSON.parse(serialized) as T,
      }
      window.sessionStorage.setItem(options.storageKey(), JSON.stringify(stored))
    } catch {
      // 浏览器禁用或空间不足时仍保留 beforeunload 防误关提示。
    }
  }

  function clearDraft() {
    if (saveTimer !== undefined) window.clearTimeout(saveTimer)
    baseline = serialize(options.snapshot())
    isDirty.value = false
    removeStoredDraft()
  }

  function readStoredDraft() {
    try {
      const raw = window.sessionStorage.getItem(options.storageKey())
      if (!raw) return undefined
      const stored = JSON.parse(raw) as StoredSessionDraft<T>
      return stored.version === 1 && stored.data ? stored : undefined
    } catch {
      removeStoredDraft()
      return undefined
    }
  }

  function removeStoredDraft() {
    try { window.sessionStorage.removeItem(options.storageKey()) } catch { /* ignore */ }
  }

  function handleBeforeUnload(event: BeforeUnloadEvent) {
    if (!isDirty.value && !options.additionalDirty?.()) return
    event.preventDefault()
    event.returnValue = ''
  }

  function handleWorkspaceTabClosed(event: CustomEvent<{ key?: string }>) {
    if (options.workspaceKey && event.detail?.key === options.workspaceKey) clearDraft()
  }

  return { isDirty, restoredAt, clearDraft }
}

function serialize<T>(value: T) {
  return JSON.stringify(value)
}

function formatSavedAt(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '上次' : date.toLocaleString('zh-CN', { hour12: false })
}
