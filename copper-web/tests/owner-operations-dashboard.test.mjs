import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'

const source = (path) => fs.readFileSync(path, 'utf8')

test('owner dashboard exposes every high-priority operating queue with direct routes', () => {
  const dashboard = source('src/views/Dashboard.vue')
  for (const label of ['今日应收', '今日应付', '逾期应收', '逾期应付', '库存风险', '未匹配银行流水', '未勾稽发票', '待关账阻断']) {
    assert.match(dashboard, new RegExp(label), label)
  }
  for (const route of ['/receivables', '/payables', '/inventory/reconciliation', '/accounting/bank-reconciliation', '/tax-invoices/reconciliation', '/accounting/period-close']) {
    assert.match(dashboard, new RegExp(route.replaceAll('/', '\\/')), route)
  }
  assert.match(dashboard, /completeScope/)
  assert.match(dashboard, /本页不会用旧数字冒充当前结果/)
  assert.match(dashboard, /path: '\/accounting\/bank-reconciliation', query: \{ scope: 'unmatched' \}/)
})

test('owner dashboard shows mutually exclusive seven-day purchase arrival and sales shipment reminders', () => {
  const dashboard = source('src/views/Dashboard.vue')
  const api = source('src/api/client.ts')
  for (const label of ['近期收发货安排', "TODAY: '今日'", "TOMORROW: '明日'", "UPCOMING: '近期'", "'到货' : '出库'"]) {
    assert.match(dashboard, new RegExp(label), label)
  }
  assert.match(dashboard, /今天至未来 7 日仍未完成/)
  assert.match(dashboard, /直运采购不作为到货入库提醒/)
  assert.match(dashboard, /未来 7 日暂无待到货或待出库安排/)
  assert.match(dashboard, /canViewFulfillmentReminders/)
  assert.doesNotMatch(dashboard, /v-if="fulfillmentReminders\.length"/)
  assert.match(dashboard, /fulfillmentReminders/)
  assert.match(api, /FulfillmentReminder/)
})

test('receivable and payable queues preserve due scope and lead to settlement', () => {
  const receivables = source('src/views/Receivables.vue')
  const payables = source('src/views/Payables.vue')
  const report = source('src/components/SettlementReportPage.vue')
  assert.match(receivables, /direction="RECEIVABLE"/)
  assert.match(payables, /direction="PAYABLE"/)
  for (const contract of [/due-today/, /overdue/, /settlementDays/, /dueDate/, /overdueDays/, /route\.query\.scope/,
    /\/payments\/new/, /\/supplier-payments\/new/, /report\.summary\.unpaidAmount/, /report\.snapshotAt/, /<el-pagination/]) {
    assert.match(report, contract)
  }
  assert.doesNotMatch(report, /\.reduce\(/)
})

test('dashboard query filters reuse one workspace tab and payment forms restore origin', () => {
  const tabs = source('src/stores/workspaceTabs.ts')
  const receipt = source('src/views/PaymentCreate.vue')
  const payment = source('src/views/SupplierPaymentCreate.vue')
  assert.match(tabs, /return route\.path/)
  assert.doesNotMatch(tabs, /URLSearchParams/)
  for (const page of [receipt, payment]) {
    assert.match(page, /returnPath\(route/)
    assert.match(page, /route\.query\.sourceType/)
    assert.match(page, /route\.query\.sourceId/)
  }
})

test('inventory risk card and reconciliation page share one actionable risk vocabulary', () => {
  const reconciliation = source('src/views/InventoryReconciliation.vue')
  for (const riskField of ['negativeSourceCount', 'unavailableBatchCount', "status === 'RISK'", 'riskDescription']) {
    assert.match(reconciliation, new RegExp(riskField), riskField)
  }
})
