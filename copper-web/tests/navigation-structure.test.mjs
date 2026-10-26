import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const app = readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8')

function section(start, end) {
  const startIndex = app.indexOf(start)
  const endIndex = app.indexOf(end, startIndex + start.length)
  assert.notEqual(startIndex, -1, `missing ${start}`)
  assert.notEqual(endIndex, -1, `missing ${end}`)
  return app.slice(startIndex, endIndex)
}

test('navigation follows the small-trader operating sequence', () => {
  const groupMarkers = [
    "index: 'crm'",
    "index: 'sales'",
    "index: 'purchase'",
    "index: 'inventory'",
    "index: 'finance'",
    "index: 'operations'",
    "index: 'invoice'",
    "index: 'accounting'",
    "index: 'master'",
    "index: 'system'",
  ]
  const positions = groupMarkers.map((marker) => app.indexOf(marker))
  assert.ok(positions.every((position) => position >= 0), 'all business groups must exist')
  assert.deepEqual([...positions].sort((left, right) => left - right), positions)

  for (const retiredLabel of ['采购库存', '销售平台', '财务往来', '经营控制']) {
    assert.doesNotMatch(app, new RegExp(retiredLabel), retiredLabel)
  }
})

test('shared partner master belongs to customer operations instead of base data', () => {
  const customerOperations = section("index: 'crm'", "index: 'sales'")
  const baseData = section("index: 'master'", "index: 'system'")
  assert.match(customerOperations, /path: '\/partners', label: '往来单位'/)
  assert.ok(customerOperations.indexOf("path: '/partners'") < customerOperations.indexOf("path: '/crm'"))
  assert.doesNotMatch(baseData, /path: '\/partners'/)
})

test('settlement, accounting, operations and configuration entries have stable ownership', () => {
  const settlement = section("index: 'finance'", "index: 'operations'")
  const operations = section("index: 'operations'", "index: 'invoice'")
  const accounting = section("index: 'accounting'", "index: 'master'")
  const system = section("index: 'system'", '\n]\n')

  for (const branch of ['应收与收款', '应付与付款', '资金与票据']) assert.match(settlement, new RegExp(branch))
  assert.doesNotMatch(settlement, /path: '\/business-postings'/)
  assert.match(accounting, /path: '\/business-postings', label: '业务过账'/)
  for (const path of ['/price-center', '/risk-exceptions', '/platform']) {
    assert.match(operations, new RegExp(`path: '${path.replaceAll('/', '\\/')}'`))
  }
  assert.match(system, /path: '\/settings', label: '系统设置'/)
})
