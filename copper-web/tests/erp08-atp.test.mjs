import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

function source(path) {
  return readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')
}

const api = source('src/api/client.ts')
const router = source('src/router/index.ts')
const app = source('src/App.vue')
const atp = source('src/views/InventoryAtp.vue')
const panel = source('src/components/AtpOrderPanel.vue')
const salesForm = source('src/views/SalesOrderCreate.vue')
const salesDetail = source('src/views/SalesOrderDetail.vue')
const purchaseForm = source('src/views/PurchaseOrderCreate.vue')
const transferForm = source('src/views/InventoryDocumentCreate.vue')
const sop = source('docs/OWNER_OPERATIONS_AND_FINANCE_SOP.md')

test('ERP-08 exposes one time-phased ATP workspace and auditable order APIs', () => {
  assert.match(router, /\/inventory\/atp/)
  assert.match(app, /库存可承诺量/)
  for (const method of ['inventoryAtp:', 'inventoryAtpTimeline:', 'salesOrderAtp:', 'salesOrderAtpHistory:']) {
    assert.match(api, new RegExp(method), method)
  }
  for (const concept of ['现存', '出库草稿', '销售承诺', '采购将到', '调拨将到', '可承诺量']) {
    assert.match(atp, new RegExp(concept), concept)
  }
  assert.match(atp, /历史销售承诺未分仓/)
  assert.match(atp, /采购在途未分仓或未排到货日期/)
})

test('sales purchase and transfer documents carry explicit promise coordinates', () => {
  assert.match(api, /fulfillmentWarehouseId/)
  assert.match(api, /expectedWarehouseId/)
  assert.match(api, /expectedArrivalDate/)
  assert.match(salesForm, /履约仓库/)
  assert.match(salesForm, /计划交付/)
  assert.match(purchaseForm, /入库仓库/)
  assert.match(purchaseForm, /预计到货/)
  assert.match(transferForm, /预计到达/)
})

test('sales confirmation preflights ATP and preserves immutable decision history', () => {
  assert.match(salesDetail, /api\.salesOrderAtp\(id\)/)
  assert.ok(salesDetail.indexOf('api.salesOrderAtp(id)') < salesDetail.indexOf('confirmMutation.mutate'))
  assert.match(salesDetail, /AtpOrderPanel/)
  assert.match(panel, /承诺审计快照/)
  assert.match(panel, /确认、变更和取消均保留/)
  assert.match(panel, /calculationVersion/)
})

test('owner SOP keeps expected supply separate from actual inventory and forbids silent rerouting', () => {
  assert.match(sop, /库存可承诺量（ATP）与交期承诺/)
  assert.match(sop, /预计供应只帮助判断交期，不增加实际库存/)
  assert.match(sop, /数据库内锁定“商品 \+ 履约仓库”/)
  assert.match(sop, /通过正式订单变更补齐/)
  assert.match(sop, /先按时间倒序撤销或冲正下游/)
})
