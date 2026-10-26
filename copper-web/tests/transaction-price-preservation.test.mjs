import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const salesOrderCreate = readFileSync(new URL('../src/views/SalesOrderCreate.vue', import.meta.url), 'utf8')
const purchaseOrderCreate = readFileSync(new URL('../src/views/PurchaseOrderCreate.vue', import.meta.url), 'utf8')
const crmQuoteEdit = readFileSync(new URL('../src/views/CrmQuoteEdit.vue', import.meta.url), 'utf8')

test('changing sales quantity refreshes packaging and suggestions without replacing the current price', () => {
  assert.match(
    salesOrderCreate,
    /async function handleQuantityChange\(row: SalesItemForm\) \{[\s\S]*refreshPackagingPlan\(row\)[\s\S]*applyProductUnitOptions\(row, row\.priceUnit, true\)/,
  )
  assert.match(
    salesOrderCreate,
    /const currentPrice = preserveCurrentPrice[\s\S]*unitPrice: row\.unitPrice[\s\S]*priceSource: row\.priceSource[\s\S]*priceExplanation: row\.priceExplanation/,
  )
  assert.match(
    salesOrderCreate,
    /if \(currentPrice\) \{[\s\S]*row\.unitPrice = currentPrice\.unitPrice[\s\S]*row\.priceSource = currentPrice\.priceSource[\s\S]*row\.priceExplanation = currentPrice\.priceExplanation/,
  )
})

test('product and price-unit changes still allow the pricing engine to apply a fresh suggestion', () => {
  assert.match(salesOrderCreate, /await applyProductUnitOptions\(row\)\s+await loadPackagingProfile\(row\)/)
  assert.match(salesOrderCreate, /await applyProductUnitOptions\(row, row\.priceUnit\)\s*\}/)
})

test('purchase quantity and actual-base-quantity changes preserve the current price', () => {
  assert.match(purchaseOrderCreate, /refreshPurchaseOptions\(row, row\.priceUnit, true, true\)/)
  assert.match(purchaseOrderCreate, /refreshPurchaseOptions\(row, row\.priceUnit, false, true\)/)
  assert.match(
    purchaseOrderCreate,
    /if \(currentPrice && row\.productId === productId\) \{[\s\S]*row\.unitPrice = currentPrice\.unitPrice[\s\S]*row\.priceSource = currentPrice\.priceSource[\s\S]*row\.priceExplanation = currentPrice\.priceExplanation/,
  )
})

test('CRM quote quantity changes preserve the current price while price-unit changes can reprice', () => {
  assert.match(crmQuoteEdit, /row\.quantity[\s\S]*refreshPriceAndPlan\(row, true\)/)
  assert.match(crmQuoteEdit, /row\.quantityUnit[\s\S]*refreshPriceAndPlan\(row, true\)/)
  assert.match(crmQuoteEdit, /row\.priceUnit[\s\S]*refreshPriceAndPlan\(row\)/)
  assert.match(
    crmQuoteEdit,
    /if \(currentPrice && row\.productId === productId\) \{[\s\S]*row\.unitPrice = currentPrice\.unitPrice[\s\S]*row\.priceSource = currentPrice\.priceSource[\s\S]*row\.priceExplanation = currentPrice\.priceExplanation/,
  )
})
