import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

function source(path) {
  return readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')
}

const app = source('src/App.vue')
const apiClient = source('src/api/client.ts')
const connectivity = source('src/stores/connectivity.ts')
const connectivityIndicator = source('src/components/ConnectivityIndicator.vue')
const scanner = source('src/components/BarcodeScannerInput.vue')
const inboundCreate = source('src/views/InboundCreate.vue')
const inboundDetail = source('src/views/InboundDetail.vue')
const printComposable = source('src/composables/useDocumentPrint.ts')
const printCss = source('src/assets/main.css')
const numberFormat = source('src/api/format.ts')
const sessionDraft = source('src/composables/useSessionDraft.ts')
const acceptance = source('docs/self-hosting/M5_06_LAN_EXPERIENCE_ACCEPTANCE.md')

test('LAN status distinguishes browser offline, host unreachable, recovery and external features', () => {
  assert.match(app, /<ConnectivityIndicator\s*\/>/)
  assert.doesNotMatch(app, /local-database\s*\/\s*copper_v2/)
  assert.match(connectivity, /\/api\/actuator\/health\/readiness/)
  for (const state of ['BROWSER_OFFLINE', 'HOST_UNREACHABLE', 'RECOVERED', 'ONLINE']) assert.match(connectivity, new RegExp(state))
  assert.match(connectivity, /Copper 不支持离线记账/)
  assert.match(connectivity, /未收到成功提示的写操作均未确认成功/)
  assert.match(connectivityIndicator, /第三方功能会在对应页面单独提示/)
  assert.match(apiClient, /copper:request-failed/)
  assert.match(apiClient, /copper:request-succeeded/)
  assert.match(apiClient, /\[502, 503, 504\]/)
  assert.match(connectivity, /removeEventListener\('copper:request-failed'/)
})

test('four business documents use browser print with stable A4 or dot-matrix pagination', () => {
  const documents = [
    ['src/views/PurchaseOrderDetail.vue', '打印采购订单', 'a4-print-sheet'],
    ['src/views/SalesOrderDetail.vue', '打印销售订单', 'dot-print-sheet'],
    ['src/views/InboundDetail.vue', '打印入库单', 'a4-print-sheet'],
    ['src/views/OutboundDetail.vue', '针式打印', 'dot-print-sheet'],
  ]
  for (const [path, button, sheet] of documents) {
    const text = source(path)
    assert.match(text, new RegExp(button))
    assert.match(text, new RegExp(`print-sheet ${sheet}`))
    assert.match(text, /useDocumentPrint/)
  }
  assert.match(printComposable, /window\.print\(\)/)
  assert.match(printComposable, /afterprint/)
  assert.match(printCss, /@page a4-document/)
  assert.match(printCss, /body\.is-printing #app[\s\S]*height:\s*auto\s*!important/)
  assert.match(printCss, /body\.is-printing > :not\(#app\)[\s\S]*display:\s*none\s*!important/)
  assert.match(printCss, /table-header-group/)
  assert.match(printCss, /break-inside:\s*avoid/)
  assert.match(printCss, /size:\s*241mm 140mm/)
})

test('HID scanner handles IME composition, continuous scans and explicit duplicates', () => {
  assert.match(scanner, /@compositionstart="composing = true"/)
  assert.match(scanner, /@compositionend="handleCompositionEnd"/)
  assert.match(scanner, /event\.isComposing/)
  assert.match(scanner, /@keydown\.enter\.prevent/)
  assert.match(scanner, /inputRef\.value\?\.focus\(\)/)
  assert.match(inboundCreate, /const scanQueue: string\[\] = \[\]/)
  assert.match(inboundCreate, /while \(scanQueue\.length\)/)
  assert.match(inboundCreate, /重复扫描/)
  assert.match(inboundCreate, /existing\.packageQuantity = Number\(existing\.packageQuantity \|\| 0\) \+ 1/)
  assert.match(inboundCreate, /包装条码识别失败/)
  assert.match(inboundCreate, /applyPackageCodeScan/)
  assert.match(inboundCreate, /批量粘贴重量/)
  assert.match(inboundCreate, /填入空白重量/)
})

test('inbound actual weights may remain incomplete in draft but are required for review submission', () => {
  assert.ok(inboundCreate.includes('async function buildPayload(requireSubmissionComplete = false)'))
  assert.ok(inboundCreate.includes('requireSubmissionComplete && form.items.some'))
  assert.ok(inboundCreate.includes('buildPayload(true)'))
  assert.match(inboundCreate, /sameQuantity\(sumQuantities\(item\.packageInstances\.map/)
  assert.doesNotMatch(inboundCreate, /quantityHundredths/);
  assert.match(inboundCreate, /保存草稿时实重可以留空；提交审核前必须全部录入/)
  assert.match(inboundCreate, /提交审核前需补齐/)
})

test('business quantity display preserves six decimals and required table fields use red markers', () => {
  assert.match(numberFormat, /quantity\([^\n]+digits = 6/)
  assert.match(printCss, /\.table-required-mark/)
  assert.ok(printCss.includes('color: var(--el-color-danger)'))
  for (const path of [
    'src/views/PurchaseOrderCreate.vue',
    'src/views/SalesOrderCreate.vue',
    'src/views/InboundCreate.vue',
    'src/views/OutboundCreate.vue',
    'src/views/DirectTradeCreate.vue',
    'src/views/InventoryDocumentCreate.vue',
    'src/views/CrmQuoteEdit.vue',
    'src/views/AccountingVoucherEdit.vue',
  ]) {
    const text = source(path)
    assert.match(text, /table-required-mark/, path)
  }
  for (const path of [
    'src/views/PurchaseOrderCreate.vue',
    'src/views/SalesOrderCreate.vue',
    'src/views/InboundCreate.vue',
    'src/views/OutboundCreate.vue',
  ]) {
    assert.match(source(path), /v-model="row\.quantity"[^\n]+:precision="6"/, path)
  }
})

test('attachment failures retain context and reconcile uncertain success by SHA-256', () => {
  assert.match(inboundCreate, /persistedInboundId/)
  assert.match(inboundCreate, /item\.status = 'fail'/)
  assert.match(inboundCreate, /重试失败图片/)
  assert.match(inboundCreate, /sha256File\(file\)/)
  assert.match(inboundCreate, /if \(!result\.attachmentFailures\) \{/)
  assert.match(inboundCreate, /queryKey: \['inbound-detail', result\.id\]/)
  assert.match(inboundCreate, /queryKey: \['inbound-attachments', result\.id\]/)
  assert.match(inboundDetail, /v-model:file-list="attachmentUploadFiles"/)
  assert.match(inboundDetail, /retryFailedAttachments/)
  assert.match(inboundDetail, /sha256File\(file\)/)
  assert.match(apiClient, /timeout:\s*30000/)
})

test('unsaved drafts are isolated by user and document and cleared after successful save', () => {
  assert.match(sessionDraft, /window\.sessionStorage/)
  assert.match(sessionDraft, /beforeunload/)
  assert.match(sessionDraft, /恢复未提交内容/)
  for (const [path, kind] of [
    ['src/views/PurchaseOrderCreate.vue', 'purchase-order'],
    ['src/views/SalesOrderCreate.vue', 'sales-order'],
    ['src/views/InboundCreate.vue', 'inbound'],
    ['src/views/OutboundCreate.vue', 'outbound'],
  ]) {
    const text = source(path)
    assert.match(text, /appStore\.session\?\.userId/)
    assert.match(text, new RegExp(`:${kind}:`))
    assert.match(text, /useSessionDraft/)
    assert.match(text, /clearDraft\(\)/)
  }
  assert.match(app, /copper:workspace-tab-closed/)
  assert.match(sessionDraft, /copper:workspace-tab-closed/)
})

test('acceptance report closes the scoped A4 gate while keeping deferred physical work open', () => {
  assert.match(acceptance, /Issue `soyia\/copper_web#43` 可关闭并进入 M6/)
  assert.match(acceptance, /- \[x\] 实体 HP Laser MFP 136w/)
  assert.match(acceptance, /Deploy Issue `#63`/)
  for (const gate of [
    '真实独立外接盘',
    '与主机分离的远程 NAS',
    '独立空白设备 B 换机恢复',
    '客户离线恢复私钥交接',
    '实体 USB/蓝牙 HID 扫码枪',
  ]) assert.match(acceptance, new RegExp(`- \\[ \\] ${gate}`))
})
