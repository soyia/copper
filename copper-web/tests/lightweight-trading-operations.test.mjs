import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')

test('lightweight operations assistant exposes transparent suggestions and existing import paths', () => {
  const app = read('src/App.vue')
  const router = read('src/router/index.ts')
  const view = read('src/views/OperationsAssistant.vue')

  assert.match(app, /经营助手/)
  assert.match(router, /path: '\/operations-assistant'/)
  assert.match(router, /permission: 'operations\.insight\.view'/)
  for (const contract of [
    '补货与库龄', '销售贡献', '导入与对外协同', '不自动生成采购单',
    '银行流水导入', '电子凭证归档', '发票清单导入', '期初数据导入',
    '当前没有配置银行或税务平台直连',
  ]) assert.match(view, new RegExp(contract))
  for (const path of [
    '/operations/replenishment', '/operations/contributions',
    '/operations/owner-summary', '/integration-keys',
  ]) assert.match(read('src/api/client.ts'), new RegExp(path.replaceAll('/', '\\/')))
})

test('sample and internal issue deduct inventory at physical issue and archive signature afterward', () => {
  const inventory = read('src/views/Inventory.vue')
  const dialog = read('src/components/InventoryBatchQualityDialog.vue')
  const list = read('src/views/InventoryNonTradeDocuments.vue')
  const create = read('src/views/InventoryNonTradeCreate.vue')
  const detail = read('src/views/InventoryNonTradeDetail.vue')
  const inboundDetail = read('src/views/InboundDetail.vue')
  const compactItems = read('src/components/DocumentItemCompactTable.vue')
  const multilineItems = read('src/components/DocumentItemMultilineView.vue')
  const app = read('src/App.vue')
  const router = read('src/router/index.ts')
  const api = read('src/api/client.ts')

  assert.match(inventory, /送样\/领用/)
  assert.match(inventory, /path: '\/inventory\/non-trade\/new'/)
  assert.match(app, /送样 \/ 领用 \/ 损耗/)
  for (const path of ['/inventory/non-trade', '/inventory/non-trade/new', '/inventory/non-trade/:id']) {
    assert.match(router, new RegExp(path.replaceAll('/', '\\/')))
  }
  for (const contract of ['新建单据', '草稿待发放', '已发放待签字', '不产生销售应收']) assert.match(list, new RegExp(contract))
  for (const contract of [
    '一张单据可添加多个商品', '实际领用人（签字人）', '经办人 / 发放人',
    '增加商品', '本商品合计扣取数量', '只读，由本行扣取明细自动合计',
    '增加包装', '本包装扣取',
    '保存草稿',
  ]) assert.match(create, new RegExp(contract))
  assert.match(create, /items: payloadItems/)
  assert.doesNotMatch(create, /v-model="form\.quantity"/)
  for (const contract of [
    '打印签字单', '实际领用人签字单据', '系统制单人', '商品扣取明细',
    '确认发放并扣库存', '确认签字归档', '库存已在确认发放时扣减',
    'selectedSignedAttachmentId', 'EvidenceAttachments', 'INVENTORY_NON_TRADE',
    '扣前账面结存', '扣后账面结存', '扣前数量', '扣后数量',
  ]) assert.match(detail, new RegExp(contract))
  for (const contract of [
    '送样 / 领用 / 损耗记录', '当前账面结存', '当前自由可用',
    '已发放待签字和已归档记录计入当前账面结存', '/inventory/non-trade/',
  ]) assert.match(inboundDetail, new RegExp(contract))
  for (const view of [compactItems, multilineItems]) {
    for (const contract of ['原始入库数量', '送样/领用/损耗已扣', '当前账面结存', '当前自由可用']) {
      assert.match(view, new RegExp(contract))
    }
  }
  assert.match(dialog, /批次质保/)
  assert.match(api, /\/inventory\/non-trade\/\$\{id\}\/issue/)
  assert.match(api, /\/inventory\/non-trade\/\$\{id\}\/confirm/)
  assert.match(api, /\/inventory\/non-trade\/\$\{id\}\/reverse/)
  assert.match(api, /\/inventory\/batches\/\$\{batchId\}\/quality-certificates/)
})

test('order details reuse lightweight quote cost delivery and read-only sharing panels', () => {
  const purchase = read('src/views/PurchaseOrderDetail.vue')
  const sales = read('src/views/SalesOrderDetail.vue')
  const outbound = read('src/views/OutboundDetail.vue')
  const quote = read('src/components/PurchaseQuoteComparisonPanel.vue')
  const contribution = read('src/components/SalesContributionPanel.vue')
  const delivery = read('src/components/OutboundDeliveryPanel.vue')
  const share = read('src/components/DocumentSharePanel.vue')

  assert.match(purchase, /PurchaseQuoteComparisonPanel/)
  assert.match(purchase, /DocumentSharePanel/)
  assert.match(sales, /SalesContributionPanel/)
  assert.match(sales, /DocumentSharePanel/)
  assert.match(outbound, /OutboundDeliveryPanel/)
  assert.match(outbound, /SalesContributionPanel/)
  assert.match(quote, /不会静默改写订单/)
  assert.match(contribution, /不重复登记资金或凭证/)
  assert.match(delivery, /签收不代表收款/)
  assert.match(share, /对外只读分享/)
  assert.match(share, /formatDateTime\(row\.expiresAt\)/)
  assert.match(share, /formatExpiryDistance\(row\.expiresAt, row\.status\)/)
  assert.match(share, /（\{\{ formatExpiryDistance\(row\.expiresAt, row\.status\) \}\}）/)
  assert.match(share, /canManage && publicOrigin/)
  assert.match(share, /当前未配置已确认可对外访问的 HTTPS 域名，对外只读分享未启用/)
  assert.match(share, /externalPublicOrigin/)
  assert.doesNotMatch(share, /window\.location\.origin/)
  assert.doesNotMatch(share, /setInterval/)
})

test('barcode assistance and public order view do not bypass human confirmation or expose internals', () => {
  const outbound = read('src/views/OutboundCreate.vue')
  const publicView = read('src/views/PublicOrderShare.vue')
  const router = read('src/router/index.ts')

  assert.match(outbound, /BarcodeScannerInput/)
  assert.match(outbound, /自动选择批次.*包装记为.*核对后确认发货/)
  assert.match(outbound, /quantityFree/)
  assert.match(router, /path: '\/public\/order-shares\/:token'/)
  assert.match(router, /public: true/)
  assert.doesNotMatch(publicView, /库存成本|欠款|联系方式/)
})
