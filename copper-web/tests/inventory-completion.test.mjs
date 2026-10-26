import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

function source(path) {
  return readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')
}

const api = source('src/api/client.ts')
const router = source('src/router/index.ts')
const app = source('src/App.vue')
const inventory = source('src/views/Inventory.vue')
const create = source('src/views/InventoryDocumentCreate.vue')
const detail = source('src/views/InventoryDocumentDetail.vue')
const movements = source('src/views/InventoryMovements.vue')
const reconciliation = source('src/views/InventoryReconciliation.vue')
const mobileStocktake = source('src/views/mobile/MobileStocktake.vue')

test('inventory overview separates actual reserved free and in-transit quantities', () => {
  for (const field of ['quantityAvailable', 'quantityReserved', 'quantityFree', 'quantityInTransit']) {
    assert.match(api, new RegExp(`${field}: number`))
    assert.match(inventory, new RegExp(`row\.${field}`))
  }
  assert.match(inventory, /账面结存 − 出库草稿占用 = 自由可用/)
})

test('all four document families have list create edit detail and lifecycle APIs', () => {
  for (const path of ['transfers', 'stocktakes', 'purchase-returns', 'cost-adjustments']) {
    assert.match(router, new RegExp(`/inventory/${path}/new`))
    assert.match(router, new RegExp(`/inventory/${path}/:id/edit`))
    assert.match(router, new RegExp(`/inventory/${path}/:id`))
  }
  for (const name of ['createInventoryTransfer', 'updateInventoryTransfer', 'dispatchInventoryTransfer',
    'receiveInventoryTransfer', 'reverseInventoryTransfer', 'createInventoryStocktake', 'updateInventoryStocktake',
    'confirmInventoryStocktake', 'reverseInventoryStocktake', 'createPurchaseReturn', 'updatePurchaseReturn',
    'confirmPurchaseReturn', 'reversePurchaseReturn', 'createInventoryCostAdjustment',
    'updateInventoryCostAdjustment', 'confirmInventoryCostAdjustment', 'reverseInventoryCostAdjustment']) {
    assert.match(api, new RegExp(`${name}:`), name)
  }
})

test('inventory documents use standalone pages with recovery and audit context', () => {
  assert.match(create, /逐件实重批次必须选择具体包装实物/)
  assert.match(create, /按批次记录账面数、实盘数和差异原因/)
  assert.match(create, /按批次填写新成本单价/)
  assert.match(create, /保存草稿/)
  assert.match(detail, /编辑草稿/)
  assert.match(detail, /作废草稿/)
  assert.match(detail, /冲正/)
  assert.match(detail, /操作记录/)
  assert.match(detail, /调出仓并进入在途/)
  assert.match(create, /useSessionDraft/)
  assert.doesNotMatch(create, /RelatedDetailDrawer/)
  assert.doesNotMatch(detail, /RelatedDetailDrawer/)
})

test('desktop and mobile stocktake include zero-stock batches for inventory gains', () => {
  assert.match(create, /api\.inventoryBatches\(\{ availableOnly: false \}\)/)
  assert.match(create, /账面 \$\{quantity\(batch\.quantityAvailable\)\}/)
  assert.match(mobileStocktake, /api\.inventoryBatches\(\{ availableOnly: false \}\)/)
})

test('navigation, movement ledger and reconciliation expose complete inventory workbench', () => {
  for (const label of ['仓库库存', '库存调拨', '库存盘点', '采购退货', '成本调整', '库存流水', '库存勾稽']) {
    assert.match(app, new RegExp(label), label)
  }
  for (const type of ['TRANSFER_OUT', 'TRANSFER_IN', 'STOCKTAKE_GAIN', 'STOCKTAKE_LOSS',
    'PURCHASE_RETURN', 'COST_ADJUSTMENT']) assert.match(movements, new RegExp(type), type)
  assert.match(movements, /sourceDocumentNo/)
  assert.match(movements, /sourceRoute/)
  for (const field of ['batchQuantity', 'balanceQuantity', 'reservedQuantity', 'freeQuantity',
    'inTransitQuantity', 'trackedPackageQuantity', 'costDifference']) assert.match(reconciliation, new RegExp(field), field)
  assert.match(api, /inventoryReconciliation:/)
  assert.match(reconciliation, /上线期初成本对齐记录/)
  assert.match(reconciliation, /formatDateTimeColumn/)
  assert.match(api, /inventoryOpeningCostAlignments:/)
})
