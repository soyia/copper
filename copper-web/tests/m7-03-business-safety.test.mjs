import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')

test('user authorization makes the initial password visibly and functionally required', () => {
  const source = read('src/views/SystemUsers.vue')
  assert.match(source, /label="初始密码" prop="initialPassword" required/)
  assert.match(source, /请填写初始密码/)
  assert.match(source, /密码至少 8 位，且包含字母和数字/)
})

test('warehouse writes are permission-aware, single-submit, and show backend errors', () => {
  const source = read('src/views/Warehouses.vue')
  assert.match(source, /hasPermission\('warehouse\.manage'\)/)
  assert.match(source, /:loading="saving" :disabled="saving"/)
  assert.match(source, /onError: \(error\) => ElMessage\.error/)
})

test('inbound editing shows calculated stock quantity and persisted attachments', () => {
  const source = read('src/views/InboundCreate.vue')
  assert.match(source, /row\.packagingPlan\?\.baseQuantity/)
  assert.match(source, /已保存图片/)
  assert.match(source, /api\.inboundAttachments\(id\)/)
  assert.match(source, /api\.inboundAttachmentUrl/)
})

test('business edit views scope route-param reactions by stable named routes', () => {
  const router = read('src/router/index.ts')
  const purchase = read('src/views/PurchaseOrderCreate.vue')
  const inbound = read('src/views/InboundCreate.vue')
  const sales = read('src/views/SalesOrderCreate.vue')
  for (const name of ['purchase-order-edit', 'inbound-edit', 'sales-order-edit', 'outbound-edit']) {
    assert.match(router, new RegExp(`name: '${name}'`))
  }
  for (const source of [purchase, inbound, sales]) {
    assert.match(source, /\[route\.name, route\.params\.id\]/)
  }
})

test('sales and outbound documents only offer enabled receiving addresses', () => {
  const helper = read('src/utils/partnerAddresses.ts')
  const sales = read('src/views/SalesOrderCreate.vue')
  const outbound = read('src/views/OutboundCreate.vue')
  assert.match(helper, /addressType === 'RECEIVING'/)
  assert.match(helper, /status === 'ENABLED'/)
  assert.match(sales, /receivingAddressOptions/)
  assert.match(outbound, /receivingAddressOptions/)
})

test('inventory exposes searchable SKU and a real source inbound relation', () => {
  const source = read('src/views/Inventory.vue')
  assert.match(source, /prop="sku" label="SKU"/)
  assert.match(source, /sourceInboundNo/)
  assert.match(source, /relatedDetailRoute\(route, `\/inbounds\//)
})

test('sales order detail exposes fulfillment totals, item progress, and linked outbounds', () => {
  const source = read('src/views/SalesOrderDetail.vue')
  assert.match(source, /发货进度/)
  assert.match(source, /shippedQuantity/)
  assert.match(source, /待发数量/)
  assert.match(source, /api\.outbounds\(\{ salesOrderId: id \}\)/)
  assert.match(source, /关联出库单/)
})

test('outbound submission keeps one idempotency key and blocks repeated clicks', () => {
  const create = read('src/views/OutboundCreate.vue')
  const detail = read('src/views/OutboundDetail.vue')
  assert.match(create, /idempotencyKey: crypto\.randomUUID\(\)/)
  assert.match(create, /idempotencyKey,\n/)
  assert.doesNotMatch(create, /idempotencyKey: crypto\.randomUUID\(\),\n\s*items:/)
  assert.match(create, /:loading="saving" :disabled="saving"/)
  assert.match(detail, /作废出库草稿/)
  assert.match(detail, /api\.cancelOutbound/)
})

test('sales-order outbound supports partial multi-batch allocation and preserves confirmed price', () => {
  const create = read('src/views/OutboundCreate.vue')
  assert.match(create, /splitBatchAllocation/)
  assert.match(create, />增加批次</)
  assert.match(create, /可减少本次数量形成分批发货/)
  assert.match(create, /salesOrderItemId: row\.salesOrderItemId/)
  assert.match(create, /quantity: 0/)
  assert.match(create, /row\.packagingPlan\?\.baseQuantity/)
  assert.match(create, /关联销售订单按已确认价格执行；变价请先走销售订单变更/)
  assert.match(create, /:disabled="!form\.priceAgreed \|\| !!salesOrderId"/)
})

test('receipt and payment forms start blank and share required settlement methods', () => {
  const methods = read('src/utils/settlementMethods.ts')
  const receipt = read('src/components/PaymentForm.vue')
  const payment = read('src/components/SupplierPaymentForm.vue')
  assert.match(methods, /银行转账/)
  assert.match(methods, /现金/)
  assert.match(methods, /预收\/预付余额/)
  for (const source of [receipt, payment]) {
    assert.match(source, /paymentMethod: '' as SettlementMethod/)
    assert.match(source, /label="结算方式" prop="paymentMethod"/)
    assert.match(source, /请选择结算方式/)
  }
})

test('ERP-01 ordinary funds support multi-document allocation without disguising unallocated cash as advance', () => {
  const api = read('src/api/client.ts')
  const editor = read('src/components/SettlementAllocationEditor.vue')
  const receipt = read('src/components/PaymentForm.vue')
  const payment = read('src/components/SupplierPaymentForm.vue')
  const detail = read('src/components/PaymentRecordDrawer.vue')
  for (const source of [receipt, payment]) {
    assert.match(source, /SettlementAllocationEditor/)
    assert.match(source, /allocations: allocations\.value/)
    assert.match(source, /核销分配合计不能超过本次/)
  }
  assert.match(editor, /不会转成预收预付/)
  assert.match(editor, /已分配/)
  assert.match(editor, /未分配/)
  assert.match(api, /paymentAllocationDetail/)
  assert.match(api, /changePaymentAllocation/)
  assert.match(api, /supplierPaymentAllocationDetail/)
  assert.match(api, /changeSupplierPaymentAllocation/)
  assert.match(api, /supplierId: number/)
  assert.match(api, /customerId: number/)
  assert.match(receipt, /item\.customerId === form\.customerId/)
  assert.match(payment, /item\.supplierId === form\.supplierId/)
  assert.match(detail, /customerId: detail\.value!\.partnerId/)
  assert.match(detail, /supplierId: detail\.value!\.partnerId/)
  assert.match(detail, /调整不会重复收付款/)
  assert.match(detail, /重分类过账/)
  assert.match(detail, /HEAD_REVERSED/)
  assert.match(detail, /releaseAllocationIds/)
})

test('dashboard receivables use settled business sources and never treat draft outbounds as debt', () => {
  const dashboard = read('src/views/Dashboard.vue')
  const api = read('src/api/client.ts')
  assert.match(api, /recentReceivables: ReceivableRow\[\]/)
  assert.match(dashboard, /dashboard\?\.recentReceivables/)
  assert.match(dashboard, /row\.unpaidAmount/)
  assert.match(dashboard, /row\.sourceType === 'SALES_ORDER'/)
  assert.doesNotMatch(dashboard, /dashboard\?\.recentOutbounds/)
  assert.doesNotMatch(dashboard, /row\.totalAmount - row\.paidAmount/)
})
