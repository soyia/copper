import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = (path) => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')

const app = source('src/App.vue')
const tabs = source('src/components/WorkspaceTabs.vue')
const store = source('src/stores/workspaceTabs.ts')
const styles = source('src/assets/main.css')

test('workspace tabs are an independent professional navigation surface', () => {
  assert.match(app, /<WorkspaceTabs\s*\/>/)
  assert.match(tabs, /role="tablist"/)
  assert.match(tabs, /role="tab"/)
  assert.match(tabs, /:aria-selected=/)
  assert.match(tabs, /@contextmenu\.prevent=/)
  assert.match(tabs, /role="menu"/)
  assert.match(tabs, /@auxclick\.prevent=/)
  assert.match(tabs, /handleTabKeydown/)
  assert.match(tabs, /'ArrowLeft', 'ArrowRight', 'Home', 'End'/)
  assert.match(tabs, /event\.shiftKey && event\.key === 'F10'/)
  assert.match(tabs, /ResizeObserver/)
  assert.match(tabs, /handleWheel/)
  assert.match(tabs, /has-leading-divider/)
  assert.match(styles, /\.workspace-tab-slot\.has-leading-divider::before/)
  assert.match(styles, /\.workspace-tab-slot\.is-active-slot\s*\{[^}]*align-self:\s*flex-end/s)
  assert.match(styles, /\.workspace-tab-slot:not\(\.is-active-slot\)\s*\{[^}]*translateY\(3px\)/s)
  assert.match(styles, /\.workspace-tab\.is-active::before/)
  assert.match(styles, /\.workspace-tabs::after\s*\{[^}]*background:\s*#dfe4ea/s)
  assert.match(styles, /border-bottom-color:\s*transparent/)
  assert.match(styles, /border-radius:\s*10px 10px 0 0/)
  assert.match(styles, /--workspace-content-surface:\s*#f4f7fa/)
  assert.match(styles, /--workspace-tab-strip-surface:\s*#eef2f6/)
  assert.match(styles, /\.workspace-tab\.is-active\s*\{[^}]*background:\s*var\(--workspace-content-surface\)/s)
  assert.match(styles, /\.content\s*\{[^}]*background:\s*var\(--workspace-content-surface\)/s)
  assert.match(styles, /box-shadow:\s*5px 5px 0 5px var\(--workspace-content-surface\)/)
  assert.match(styles, /\.workspace-tab-main:focus\s*\{[^}]*outline:\s*none/s)
  assert.match(styles, /\.workspace-tab\.is-active:has\(\.workspace-tab-main:focus-visible\)/)
  assert.doesNotMatch(styles, /inset 0 -2px 0 #c85d2a/)
  assert.doesNotMatch(styles, /\.workspace-tab\.is-active\s*\{[^}]*color:\s*#fff/s)
})

test('tab management supports refresh and all expected bulk-close actions', () => {
  for (const label of ['刷新此页面', '关闭当前页签', '关闭其他页签', '关闭左侧页签', '关闭右侧页签', '关闭全部页签']) {
    assert.match(tabs, new RegExp(label), label)
  }
  assert.match(tabs, /页签管理/)
  assert.match(tabs, /已打开页面 ·/)
  assert.match(tabs, /copper:workspace-tab-closed/)

  assert.match(store, /WorkspaceTabCloseMode = 'current' \| 'others' \| 'left' \| 'right' \| 'all'/)
  assert.match(store, /mode === 'others' && tab\.key !== anchorKey/)
  assert.match(store, /mode === 'left' && index < anchorIndex/)
  assert.match(store, /mode === 'right' && index > anchorIndex/)
  assert.match(store, /function refreshTab\(key: string\)/)
})

test('bulk close preserves the fixed workbench and invalidates every closed page cache', () => {
  assert.match(store, /tab\.closable &&/)
  assert.match(store, /for \(const key of closedKeys\) invalidateCache\(key\)/)
  assert.match(store, /tabs\.value = tabs\.value\.filter\(\(tab\) => !closed\.has\(tab\.key\)\)/)
  assert.match(store, /return \[homeTab\(\),/)
  assert.match(store, /closable: false/)
})
