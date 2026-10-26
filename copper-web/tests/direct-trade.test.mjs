import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')

test('direct trade is a first-class permission-scoped workspace flow', () => {
  const app = read('src/App.vue')
  const router = read('src/router/index.ts')
  const api = read('src/api/client.ts')
  assert.match(app, /path: '\/direct-trades'.*direct\.trade\.view/)
  for (const path of ['/direct-trades', '/direct-trades/new', '/direct-trades/:id']) {
    assert.match(router, new RegExp(path.replaceAll('/', '\\/')))
  }
  for (const path of [
    '/direct-trades',
    '/direct-trades/${id}/confirm',
    '/direct-trades/${id}/deliveries',
    '/direct-trades/${id}/returns',
  ]) assert.ok(api.includes(path), path)
})

test('create form keeps one-entry small-business workflow and exposes margin only by permission', () => {
  const view = read('src/views/DirectTradeCreate.vue')
  for (const contract of [
    '一次录入客户、供应商和购销价格',
    '不生成入库、出库或库存流水',
    'purchaseUnitPrice',
    'saleUnitPrice',
    '预计毛利',
    'inventory.cost.view',
  ]) assert.match(view, new RegExp(contract))
  assert.doesNotMatch(view, /warehouseId|仓库/)
})

test('detail separates dispatch acceptance settlement and reversible returns', () => {
  const view = read('src/views/DirectTradeDetail.vue')
  for (const contract of [
    '登记供应商发出',
    '确认客户实收',
    '实收与发出不一致，请填写差异原因',
    '客户实收已确认，应收应付已同步形成',
    '登记客户收款',
    '登记供应商付款',
    '登记直运退货',
    '直运退货已冲正',
    '不经过本企业仓库',
    'ACCEPT_WITH_DIFFERENCE: \'客户差异实收\'',
    'confirmButtonText: \'确认冲正\'',
    'workspaceTabs.setTitle',
  ]) assert.match(view, new RegExp(contract))
  assert.match(view, /source-type="SALES_ORDER"/)
  assert.doesNotMatch(view, /source-type="PURCHASE_ORDER"[\s\S]*DocumentSharePanel/)
})
