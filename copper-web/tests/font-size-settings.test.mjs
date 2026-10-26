import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

function source(path) {
  return readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')
}

const app = source('src/App.vue')
const css = source('src/assets/main.css')
const inventory = source('src/views/Inventory.vue')
const relatedDetailDrawer = source('src/components/RelatedDetailDrawer.vue')

test('font size setting reaches Element Plus overlays appended outside the app shell', () => {
  assert.match(relatedDetailDrawer, /append-to-body/)
  assert.match(app, /document\.body\.dataset\.copperFontSize = fontSize/)
  assert.match(app, /delete document\.body\.dataset\.copperFontSize/)
  for (const level of ['small', 'tiny', 'large']) {
    assert.ok(css.includes(`body[data-copper-font-size="${level}"]`), level)
  }
  assert.ok(css.includes('body[data-copper-font-size] {'))
})

test('inventory packaging drawer typography uses shared scalable tokens', () => {
  assert.match(css, /\.related-detail-trail[\s\S]*font-size:\s*var\(--app-extra-small-font-size\)/)
  assert.match(css, /\.related-detail-title[\s\S]*font-size:\s*var\(--app-page-title-font-size\)/)
  assert.match(css, /\.related-detail-subtitle[\s\S]*font-size:\s*var\(--app-small-font-size\)/)
  assert.match(css, /\.snapshot-hash[\s\S]*font-size:\s*var\(--app-extra-small-font-size\)/)
  assert.match(css, /body\[data-copper-font-size\] \.el-descriptions--small[\s\S]*font-size:\s*var\(--app-extra-small-font-size\)/)
  assert.match(inventory, /\.inventory-overview-subtitle[^}]*font-size:\s*var\(--app-small-font-size\)/)
})
