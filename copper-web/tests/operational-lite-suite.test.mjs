import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')

test('purchase follow-up stays in the purchase order and never pretends dispatch is receipt', () => {
  const detail = read('src/views/PurchaseOrderDetail.vue')
  const panel = read('src/components/PurchaseFollowupPanel.vue')
  const api = read('src/api/client.ts')

  assert.match(detail, /PurchaseFollowupPanel/)
  assert.match(detail, /detail\.sourceType !== 'DIRECT_TRADE'/)
  for (const contract of [
    '供应商已发', '预计到货', '登记延误', '登记异常',
    '不代表已经入库、形成库存或完成付款',
  ]) assert.match(panel, new RegExp(contract))
  assert.match(api, /\/purchase-orders\/\$\{purchaseOrderId\}\/followup/)
})

test('cash window shows both conservative and scheduled gaps with drill-down sources', () => {
  const assistant = read('src/views/OperationsAssistant.vue')
  const panel = read('src/components/CashWindowPanel.vue')
  const api = read('src/api/client.ts')

  assert.match(assistant, /7\/30天现金窗口/)
  assert.match(panel, /7天不等回款缺口/)
  assert.match(panel, /30天按账期缺口/)
  assert.match(panel, /来源明细/)
  assert.match(panel, /应收不是已经到账/)
  assert.match(api, /\/operations\/cash-window/)
})

test('external confirmation supports quote statement and delivery without financial posting', () => {
  const quote = read('src/views/CrmQuoteDetail.vue')
  const statement = read('src/components/SettlementReportPage.vue')
  const sales = read('src/views/SalesOrderDetail.vue')
  const panel = read('src/components/ExternalConfirmationPanel.vue')
  const publicAccess = read('src/utils/publicAccess.ts')
  const format = read('src/api/format.ts')
  const publicView = read('src/views/PublicConfirmation.vue')
  const router = read('src/router/index.ts')

  assert.match(quote, /packet-type="CUSTOMER_QUOTE"/)
  assert.match(statement, /packet-type="CUSTOMER_STATEMENT"/)
  assert.match(sales, /packet-type="DELIVERY_SUMMARY"/)
  assert.match(panel, /不会创建收款、核销、库存或会计结果/)
  assert.match(panel, /不确认不影响交付/)
  assert.match(panel, /canManage && publicOrigin/)
  assert.match(panel, /当前未配置已确认可对外访问的 HTTPS 域名，外部确认链接未启用/)
  assert.doesNotMatch(panel, /window\.location\.origin/)
  assert.match(publicAccess, /VITE_EXTERNAL_PUBLIC_ORIGIN/)
  assert.match(publicAccess, /url\.protocol !== 'https:'/)
  assert.match(publicAccess, /isPrivateIpv4/)
  assert.match(panel, /formatExpiryDistance\(row\.expiresAt, row\.status\)/)
  assert.match(panel, /（\{\{ formatExpiryDistance\(row\.expiresAt, row\.status\) \}\}）/)
  assert.match(format, /status === 'REVOKED'\) return '已撤销'/)
  assert.match(format, /return '已过期'/)
  assert.match(format, /return `还有 \$\{parts\.join\(''\)\}`/)
  assert.doesNotMatch(panel, /setInterval/)
  assert.doesNotMatch(panel, /重置并复制/)
  assert.match(publicView, /不代表已经收款、付款、核销、开票或完成会计处理，也不等同于可靠电子签名/)
  assert.match(router, /path: '\/public\/confirmations\/:token'/)
  assert.match(router, /public: true/)
})

test('document intake requires human review and remains outside formal documents', () => {
  const assistant = read('src/views/OperationsAssistant.vue')
  const panel = read('src/components/DocumentIntakePanel.vue')
  const api = read('src/api/client.ts')

  assert.match(assistant, /单据识别候选/)
  for (const contract of [
    '供应商报价单', '磅单', '签收单', '核对识别候选',
    '下载核对', '尚未写入正式业务', '不会创建采购、入库、库存、签收或收付款事实',
  ]) assert.match(panel, new RegExp(contract))
  assert.match(api, /documentIntakeContent/)
  assert.match(api, /\/document-intakes/)
})

test('Hongtai package label OCR is reusable for inbound and outbound and excludes QR identity', () => {
  const dialog = read('src/components/PackageLabelRecognitionDialog.vue')
  const inbound = read('src/views/InboundCreate.vue')
  const outbound = read('src/views/OutboundCreate.vue')
  const api = read('src/api/client.ts')

  for (const contract of [
    '只识别标签可见文字，不识别二维码',
    '产品名称 \\+ 规格',
    '实际重量 kg',
    '生产日期',
    '包装卷号 / 序号',
    '不会自动新增商品、入库、出库或修改库存',
    'reviewDocumentIntake',
    '重复文件直接复用原识别记录',
    "batch.status === 'CONFIRMED'",
  ]) assert.match(dialog, new RegExp(contract))
  assert.match(dialog, /item\.confirmedValue\?\.trim\(\) \|\| item\.candidateValue/)
  assert.match(inbound, /PackageLabelRecognitionDialog/)
  assert.match(inbound, /applyRecognizedInboundLabels/)
  assert.match(outbound, /PackageLabelRecognitionDialog/)
  assert.match(outbound, /applyRecognizedOutboundLabels/)
  assert.match(outbound, /标签重量与库存记录不一致/)
  assert.match(api, /PACKAGE_LABEL/)
  assert.match(api, /expectedProductId/)
  assert.doesNotMatch(dialog, /qr_payload|matchedQr|productQr/i)
})
