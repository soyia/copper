import { defineStore } from 'pinia'
import { api, type AuthSession } from '@/api/client'

export type FontSizeLevel = 'large' | 'medium' | 'small' | 'tiny'

export interface UserSettings {
  companyName: string
  companyPhone: string
  fontSize: FontSizeLevel
}

const SETTINGS_KEY = 'copper-v2-user-settings'
const defaultSettings: UserSettings = {
  companyName: '铜材进销存',
  companyPhone: '',
  fontSize: 'medium',
}

function readSettings(): UserSettings {
  if (typeof window === 'undefined') return { ...defaultSettings }
  try {
    const text = window.localStorage.getItem(SETTINGS_KEY)
    if (!text) return { ...defaultSettings }
    return normalizeSettings(JSON.parse(text) as Partial<UserSettings>)
  } catch {
    return { ...defaultSettings }
  }
}

function normalizeSettings(value: Partial<UserSettings>): UserSettings {
  const fontSize = value.fontSize === 'large' || value.fontSize === 'small' || value.fontSize === 'tiny'
    ? value.fontSize
    : 'medium'
  return {
    companyName: value.companyName?.trim() || defaultSettings.companyName,
    companyPhone: value.companyPhone?.trim() || defaultSettings.companyPhone,
    fontSize,
  }
}

function writeSettings(settings: UserSettings) {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings))
}

export const useAppStore = defineStore('app', {
  state: () => ({
    collapsed: false,
    settings: readSettings(),
    session: null as AuthSession | null,
    sessionChecked: false,
  }),
  getters: {
    isAuthenticated: (state) => Boolean(state.session),
    currentUserName: (state) => state.session?.employeeName || '未登录',
    currentDepartmentName: (state) => state.session?.departmentName || '',
    avatarText: (state) => state.session?.employeeName.slice(0, 1) || '用',
    fontSizeClass: (state) => `font-size-${state.settings.fontSize}`,
    permissionCodes: (state) => new Set(state.session?.permissionCodes ?? []),
  },
  actions: {
    toggleSidebar() {
      this.collapsed = !this.collapsed
    },
    updateSettings(settings: Partial<UserSettings>) {
      this.settings = normalizeSettings({ ...this.settings, ...settings })
      writeSettings(this.settings)
    },
    hasPermission(code?: string) {
      if (!code || this.permissionCodes.has(code)) return true
      const implications: Record<string, string[]> = {
        'product.view': ['product.manage'],
        'product.master.complex.view': ['product.master.complex.manage'],
        'product.cost.view': ['product.supplier.offer.manage'],
      }
      return (implications[code] ?? []).some((permission) => this.permissionCodes.has(permission))
    },
    async restoreSession(force = false) {
      if (this.sessionChecked && !force) return this.session
      try {
        this.session = await api.currentSession()
      } catch {
        this.session = null
      } finally {
        this.sessionChecked = true
      }
      return this.session
    },
    async login(username: string, password: string) {
      this.session = await api.login({ username, password })
      this.sessionChecked = true
      return this.session
    },
    async logout() {
      try {
        await api.logout()
      } finally {
        this.session = null
        this.sessionChecked = true
      }
    },
  },
})
