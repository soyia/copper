import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

function source(path) {
  return readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')
}

const api = source('src/api/client.ts')
const router = source('src/router/index.ts')
const app = source('src/App.vue')
const reports = source('src/views/FinancialReports.vue')
const bank = source('src/views/BankReconciliations.vue')
const close = source('src/views/PeriodClose.vue')
const master = source('src/views/AccountingMasterData.vue')
const bankSelect = source('src/components/BusinessBankAccountSelect.vue')
const receiptForm = source('src/components/PaymentForm.vue')
const supplierPaymentForm = source('src/components/SupplierPaymentForm.vue')
const customerAdvances = source('src/views/CustomerAdvances.vue')
const acceptanceBills = source('src/views/AcceptanceBills.vue')

test('navigation and permissions expose reports bank reconciliation and formal period close', () => {
  for (const path of ['/accounting/reports', '/accounting/bank-reconciliation', '/accounting/period-close']) {
    assert.match(router, new RegExp(path.replaceAll('/', '\\/')), path)
  }
  for (const label of ['财务报表', '银行对账', '期末结账']) assert.match(app, new RegExp(label), label)
  assert.match(router, /financial\.report\.view/)
  assert.match(router, /financial\.bank\.view/)
  assert.match(master, /不再允许从主档直接锁定/)
})

test('bank fund sources select the actual enterprise account and refund supports controlled reversal', () => {
  assert.match(bankSelect, /businessBankAccounts\(false\)/)
  assert.match(receiptForm, /businessBankAccountId/)
  assert.match(supplierPaymentForm, /businessBankAccountId/)
  assert.match(customerAdvances, /reverseCustomerAdvanceRefund/)
  assert.match(customerAdvances, /先重新开放对账单并撤销匹配/)
  assert.match(acceptanceBills, /requiresBankAccount/)
  assert.match(api, /businessBankAccountName/)
})

test('financial statements show mapping balance and cash ledger reconciliation', () => {
  for (const apiName of ['balanceSheet', 'incomeStatement', 'cashFlowStatement']) {
    assert.match(api, new RegExp(`${apiName}:`), apiName)
  }
  assert.match(reports, /资产负债表/)
  assert.match(reports, /利润表/)
  assert.match(reports, /现金流量表/)
  assert.match(reports, /存在未映射科目/)
  assert.match(reports, /总账期末余额/)
  assert.match(reports, /货币资金勾稽差额/)
})

test('bank reconciliation keeps statement system funds differences reversals and audit separate', () => {
  for (const apiName of ['businessBankAccounts', 'createBankReconciliation', 'importBankStatementLines',
    'downloadBankStatementImportTemplate', 'previewBankStatementImport', 'bankStatementImportBatches',
    'bankStatementImport', 'commitBankStatementImport',
    'matchBankStatement', 'reverseBankMatch', 'voidBankStatementLine', 'createBankDifference',
    'reverseBankDifference', 'createBankPendingSystem', 'reverseBankPendingSystem',
    'confirmBankReconciliation', 'reopenBankReconciliation']) {
    assert.match(api, new RegExp(`${apiName}:`), apiName)
  }
  assert.match(bank, /银行流水、系统收付款和会计总账分别保留/)
  assert.match(bank, /逐笔匹配/)
  assert.match(bank, /银行侧差异/)
  assert.match(bank, /系统侧银行未达/)
  assert.match(bank, /先预检，再提交/)
  assert.match(bank, /重复或错误行不会入账/)
  assert.match(bank, /唯一高置信自动匹配/)
  assert.match(bank, /导入和匹配都不会创建收付款或修改余额/)
  assert.match(bank, /审计轨迹/)
  assert.match(bank, /已停用，仅限历史对账/)
})

test('period close presents all blocking layers profit voucher snapshot and formal reopen', () => {
  for (const apiName of ['periodClosePreview', 'generatePeriodProfitVoucher', 'formalClosePeriod', 'formalReopenPeriod']) {
    assert.match(api, new RegExp(`${apiName}:`), apiName)
  }
  assert.match(close, /业务单据、库存、往来、资金、税票、凭证、报表和总账/)
  assert.match(close, /不能通过改余额、删记录或跳过对账强行关闭/)
  assert.match(close, /损益结转凭证/)
  assert.match(close, /快照摘要/)
  assert.match(close, /规范反结账/)
})
