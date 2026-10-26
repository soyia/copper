import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

function source(path) {
  return readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')
}

const api = source('src/api/client.ts')
const router = source('src/router/index.ts')
const app = source('src/App.vue')
const dashboard = source('src/views/TaxInvoiceDashboard.vue')
const list = source('src/views/TaxInvoices.vue')
const edit = source('src/views/TaxInvoiceEdit.vue')
const detail = source('src/views/TaxInvoiceDetail.vue')
const accountingDetail = source('src/views/AccountingVoucherDetail.vue')

test('tax invoices are an independent workspace domain with input output and reconciliation', () => {
  for (const path of ['/tax-invoices', '/tax-invoices/input', '/tax-invoices/output', '/tax-invoices/reconciliation']) {
    assert.match(router, new RegExp(path.replaceAll('/', '\\/')), path)
  }
  for (const label of ['发票工作台', '进项发票', '销项发票', '税票勾稽']) assert.match(app, new RegExp(label), label)
  assert.match(router, /invoice\.manage/)
  assert.match(router, /invoice\.view/)
})

test('invoice entry preserves tax snapshots line rounding and blue red history', () => {
  assert.match(edit, /税率和法规依据在保存时形成历史快照/)
  assert.match(edit, /原蓝字发票/)
  assert.match(edit, /数电发票号码必须为20位数字/)
  assert.match(edit, /原文件 SHA-256/)
  assert.match(edit, /订单、出入库、应收应付和收付款仍按各自单据确认/)
  assert.match(api, /createTaxInvoice:/)
  assert.match(api, /updateTaxInvoice:/)
  assert.match(api, /confirmTaxInvoice:/)
  assert.match(api, /voidTaxInvoice:/)
})

test('tax workflow separates verification booking deduction transfer out and red confirmation', () => {
  for (const apiName of ['verifyTaxInvoice', 'confirmTaxInvoiceBooking', 'confirmTaxInvoiceUsage',
    'transferOutTaxInvoice', 'updateRedTaxInvoiceConfirmation']) assert.match(api, new RegExp(`${apiName}:`), apiName)
  assert.match(detail, /确认单72小时窗口/)
  assert.match(detail, /进项税额转出/)
  assert.match(detail, /申报抵扣/)
  assert.match(detail, /税务数字账户入账标识/)
  assert.match(detail, /更正必须作废或开具红字票/)
})

test('five-layer matching never treats invoices as payment or business confirmation', () => {
  assert.match(dashboard, /开票不等于收付款，收付款也不等于开票/)
  for (const label of ['票合同', '票货', '票款', '资金参考', '票账']) assert.match(detail, new RegExp(label), label)
  assert.match(detail, /同一发票可关联多张单据，同一单据也可分多张发票/)
  assert.match(list, /差异不通过覆盖原票处理/)
  assert.match(api, /addTaxInvoiceRelation:/)
  assert.match(api, /reverseTaxInvoiceRelation:/)
  assert.match(accountingDetail, /关联发票/)
  assert.match(accountingDetail, /以下关联均可双向追溯/)
})
