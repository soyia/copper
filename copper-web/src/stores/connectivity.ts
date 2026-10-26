import { defineStore } from 'pinia'

export type ConnectivityState = 'CHECKING' | 'ONLINE' | 'BROWSER_OFFLINE' | 'HOST_UNREACHABLE' | 'RECOVERED'

const POLL_INTERVAL_MS = 15_000
const PROBE_TIMEOUT_MS = 5_000
const RECOVERED_VISIBLE_MS = 5_000

let pollTimer: number | undefined
let recoveredTimer: number | undefined
let listenersAttached = false

export const useConnectivityStore = defineStore('connectivity', {
  state: () => ({
    state: 'CHECKING' as ConnectivityState,
    lastCheckedAt: null as Date | null,
    lastOnlineAt: null as Date | null,
    running: false,
    probing: false,
  }),
  getters: {
    isAvailable: (state) => state.state === 'ONLINE' || state.state === 'RECOVERED',
    tagType: (state): 'success' | 'warning' | 'danger' | 'info' => {
      if (state.state === 'ONLINE') return 'success'
      if (state.state === 'RECOVERED') return 'success'
      if (state.state === 'CHECKING') return 'info'
      return state.state === 'BROWSER_OFFLINE' ? 'warning' : 'danger'
    },
    shortText: (state) => {
      if (state.state === 'ONLINE') return '局域网主机可用'
      if (state.state === 'RECOVERED') return '局域网连接已恢复'
      if (state.state === 'BROWSER_OFFLINE') return '当前设备网络已断开'
      if (state.state === 'HOST_UNREACHABLE') return 'Copper 主机不可达'
      return '正在检测局域网主机'
    },
    bannerText: (state) => {
      if (state.state === 'RECOVERED') return '与 Copper 局域网主机的连接已恢复，可以继续操作。请先核对刚才失败的写操作，再决定是否重试。'
      if (state.state === 'BROWSER_OFFLINE') return '当前设备网络已断开。Copper 不支持离线记账；未收到成功提示的写操作均未确认成功，请恢复网络后核对并重试。'
      if (state.state === 'HOST_UNREACHABLE') return '无法连接 Copper 局域网主机，主机可能关机、休眠，或局域网地址已经变化。未收到成功提示的写操作均未确认成功。'
      return ''
    },
  },
  actions: {
    start() {
      if (this.running) return
      this.running = true
      if (typeof window === 'undefined') return
      if (!listenersAttached) {
        window.addEventListener('online', handleBrowserOnline)
        window.addEventListener('offline', handleBrowserOffline)
        window.addEventListener('copper:request-failed', handleRequestFailed)
        window.addEventListener('copper:request-succeeded', handleRequestSucceeded)
        listenersAttached = true
      }
      void this.probe()
      pollTimer = window.setInterval(() => void this.probe(), POLL_INTERVAL_MS)
    },
    stop() {
      this.running = false
      if (typeof window !== 'undefined') {
        if (pollTimer !== undefined) window.clearInterval(pollTimer)
        if (recoveredTimer !== undefined) window.clearTimeout(recoveredTimer)
        if (listenersAttached) {
          window.removeEventListener('online', handleBrowserOnline)
          window.removeEventListener('offline', handleBrowserOffline)
          window.removeEventListener('copper:request-failed', handleRequestFailed)
          window.removeEventListener('copper:request-succeeded', handleRequestSucceeded)
          listenersAttached = false
        }
      }
      pollTimer = undefined
      recoveredTimer = undefined
    },
    markBrowserOffline() {
      this.state = 'BROWSER_OFFLINE'
      this.lastCheckedAt = new Date()
    },
    markHostUnreachable() {
      if (typeof navigator !== 'undefined' && !navigator.onLine) {
        this.markBrowserOffline()
        return
      }
      this.state = 'HOST_UNREACHABLE'
      this.lastCheckedAt = new Date()
    },
    markAvailable() {
      const recovered = this.state === 'BROWSER_OFFLINE' || this.state === 'HOST_UNREACHABLE'
      this.lastCheckedAt = new Date()
      this.lastOnlineAt = new Date()
      this.state = recovered ? 'RECOVERED' : 'ONLINE'
      if (!recovered || typeof window === 'undefined') return
      if (recoveredTimer !== undefined) window.clearTimeout(recoveredTimer)
      recoveredTimer = window.setTimeout(() => {
        if (this.state === 'RECOVERED') this.state = 'ONLINE'
      }, RECOVERED_VISIBLE_MS)
    },
    async probe() {
      if (!this.running || this.probing || typeof window === 'undefined') return
      if (!navigator.onLine) {
        this.markBrowserOffline()
        return
      }
      this.probing = true
      const controller = new AbortController()
      const timeout = window.setTimeout(() => controller.abort(), PROBE_TIMEOUT_MS)
      try {
        const response = await fetch('/api/actuator/health/readiness', {
          cache: 'no-store',
          credentials: 'include',
          headers: { Accept: 'application/json' },
          signal: controller.signal,
        })
        if (!response.ok) throw new Error(`readiness ${response.status}`)
        const body = await response.json() as { status?: string }
        if (body.status && body.status !== 'UP') throw new Error(`readiness ${body.status}`)
        this.markAvailable()
      } catch {
        this.markHostUnreachable()
      } finally {
        window.clearTimeout(timeout)
        this.probing = false
      }
    },
  },
})

function store() {
  return useConnectivityStore()
}

function handleBrowserOnline() {
  void store().probe()
}

function handleBrowserOffline() {
  store().markBrowserOffline()
}

function handleRequestFailed() {
  store().markHostUnreachable()
}

function handleRequestSucceeded() {
  store().markAvailable()
}
