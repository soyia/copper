import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = (path) => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')

test('mobile shell exposes exactly the five permission-driven fast tasks', () => {
  const router = source('src/router/index.ts')
  const home = source('src/views/mobile/MobileWorkbench.vue')
  for (const path of ['/mobile/stock', '/mobile/quote-order', '/mobile/payment', '/mobile/stocktake', '/mobile/evidence']) {
    assert.match(router, new RegExp(path.replaceAll('/', '\\/')))
    assert.match(home, new RegExp(path.replaceAll('/', '\\/')))
  }
  for (const permission of ['inventory.view', 'crm.record.manage', 'sales.manage', 'finance.view', 'inventory.stocktake', 'evidence.manage']) {
    assert.match(home, new RegExp(permission.replaceAll('.', '\\.')))
  }
  assert.match(home, /appStore\.hasPermission/)
  assert.doesNotMatch(home, /切换角色|role switch/i)
})

test('mobile business writes reuse authoritative services and stay as drafts where required', () => {
  const sale = source('src/views/mobile/MobileQuickSale.vue')
  const stocktake = source('src/views/mobile/MobileStocktake.vue')
  const payment = source('src/views/mobile/MobilePayment.vue')
  assert.match(sale, /api\.createCrmQuote/)
  assert.match(sale, /api\.createSalesOrder/)
  assert.match(sale, /销售订单草稿/)
  assert.doesNotMatch(sale, /confirmSalesOrder|activateCrmQuote/)
  assert.match(stocktake, /api\.createInventoryStocktake/)
  assert.doesNotMatch(stocktake, /confirmInventoryStocktake/)
  assert.match(stocktake, /countedQuantity/)
  assert.match(stocktake, /differenceReason/)
  assert.match(payment, /<PaymentForm/)
})

test('camera helpers never turn a scan or photo into an accounting fact', () => {
  const scan = source('src/components/mobile/MobileScanInput.vue')
  const evidence = source('src/views/mobile/MobileEvidence.vue')
  assert.match(scan, /getUserMedia/)
  assert.match(scan, /BarcodeDetector/)
  assert.match(scan, /蓝牙扫码枪/)
  assert.match(evidence, /capture="environment"/)
  assert.match(evidence, /api\.uploadEvidenceAttachment/)
  assert.match(evidence, /SHA-256/)
  assert.doesNotMatch(evidence, /createInbound|createOutbound|createPayment|confirmInventory/)
})

test('PWA caches the static shell and explicitly excludes every API request', () => {
  const manifest = source('public/manifest.webmanifest')
  const worker = source('public/sw.js')
  const main = source('src/main.ts')
  assert.match(manifest, /"start_url": "\/mobile"/)
  assert.match(main, /serviceWorker\.register\('\/sw\.js'\)/)
  assert.match(worker, /url\.pathname\.startsWith\('\/api\/'\)/)
  assert.doesNotMatch(worker, /cache\.put\([^)]*\/api\//)
})

test('mobile operations require a live Copper host before submitting', () => {
  for (const path of [
    'src/views/mobile/MobileQuickSale.vue',
    'src/views/mobile/MobilePayment.vue',
    'src/views/mobile/MobileStocktake.vue',
    'src/views/mobile/MobileEvidence.vue',
  ]) {
    const content = source(path)
    assert.match(content, /connectivity\.isAvailable/)
  }
})

test('mobile drafts are user-scoped, cleared on logout, and never auto-post business facts', () => {
  const sale = source('src/views/mobile/MobileQuickSale.vue')
  const stocktake = source('src/views/mobile/MobileStocktake.vue')
  const mobileShell = source('src/components/mobile/MobileShell.vue')
  const appShell = source('src/App.vue')
  const draftKeys = source('src/utils/mobileDrafts.ts')

  assert.match(sale, /mobileDraftKey\(appStore\.session\?\.userId/)
  assert.match(stocktake, /mobileDraftKey\(appStore\.session\?\.userId/)
  assert.match(draftKeys, /userId \|\| 0/)
  assert.match(mobileShell, /clearMobileDrafts\(appStore\.session\?\.userId\)/)
  assert.match(appShell, /clearMobileDrafts\(appStore\.session\?\.userId\)/)
  assert.doesNotMatch(sale, /confirmSalesOrder|activateCrmQuote/)
  assert.doesNotMatch(stocktake, /confirmInventoryStocktake/)
})

test('phone login enters the unified mobile workbench without weakening password-change handling', () => {
  const navigation = source('src/utils/mobileNavigation.ts')
  const login = source('src/views/Login.vue')
  const router = source('src/router/index.ts')

  assert.match(navigation, /userAgentData\?\.mobile/)
  assert.match(navigation, /Android\.\+Mobile\|iPhone\|iPod/)
  assert.match(navigation, /viewportWidth <= 1024/)
  assert.match(navigation, /safeRedirect\.startsWith\('\/mobile'\) \? safeRedirect : '\/mobile'/)
  assert.match(navigation, /!requestedRedirect\.startsWith\('\/\/'\)/)
  assert.match(login, /resolvePostLoginPath\(route\.query\.redirect\)/)
  assert.match(login, /session\.passwordChangeRequired \? '\/settings\?section=password' : redirect/)
  assert.match(router, /resolvePostLoginPath\(to\.query\.redirect\)/)
})
