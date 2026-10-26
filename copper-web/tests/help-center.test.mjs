import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')
const router = readFileSync(new URL('../src/router/index.ts', import.meta.url), 'utf8')
const helpCenter = readFileSync(new URL('../src/views/HelpCenter.vue', import.meta.url), 'utf8')
const manuals = readFileSync(new URL('../src/help/manuals.ts', import.meta.url), 'utf8')
const guide = readFileSync(new URL('../docs/help/BASE_DATA_USER_GUIDE.md', import.meta.url), 'utf8')

test('top bar opens the ERP help center as a workspace page', () => {
  assert.match(app, />\s*帮助\s*</)
  assert.match(app, /router\.push\('\/help'\)/)
  assert.match(router, /path: '\/help', component: HelpCenter, meta: \{ title: '帮助中心' \}/)
})

test('help center reads the versioned base-data guide from docs/help', () => {
  assert.match(manuals, /docs\/help\/BASE_DATA_USER_GUIDE\.md\?raw/)
  assert.match(manuals, /module: '基础资料'/)
  assert.match(helpCenter, /手册会随功能逐步补充/)
  assert.match(helpCenter, /relatedRoutes/)
})

test('base-data guide covers the real master-data entries and safety boundaries', () => {
  for (const section of ['计量单位', '仓库资料', '分类与规格', '计量与包装', '商品资料', '商品批量导入']) {
    assert.match(guide, new RegExp(section))
  }
  for (const rule of ['一物一档', '库存单位保持稳定', '历史业务保留原口径', '直运购销', '停用']) {
    assert.match(guide, new RegExp(rule))
  }
})
