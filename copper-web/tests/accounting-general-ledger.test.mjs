import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

function source(path) {
  return readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')
}

const api = source('src/api/client.ts')
const router = source('src/router/index.ts')
const app = source('src/App.vue')
const dashboard = source('src/views/AccountingDashboard.vue')
const vouchers = source('src/views/AccountingVouchers.vue')
const voucherEdit = source('src/views/AccountingVoucherEdit.vue')
const voucherDetail = source('src/views/AccountingVoucherDetail.vue')
const master = source('src/views/AccountingMasterData.vue')
const rules = source('src/views/AccountingRules.vue')
const ledger = source('src/views/AccountingLedger.vue')

test('accounting navigation exposes independent workbench vouchers ledger master data and rules', () => {
  for (const path of ['/accounting', '/accounting/vouchers', '/accounting/ledger', '/accounting/master-data', '/accounting/rules']) {
    assert.match(router, new RegExp(path.replaceAll('/', '\\/')), path)
  }
  for (const label of ['会计工作台', '会计凭证', '总账与明细账', '科目与期间', '自动制证规则']) {
    assert.match(app, new RegExp(label), label)
  }
  assert.match(router, /accounting\.manage/)
})

test('voucher workflow separates draft review posting cancellation and reversal', () => {
  for (const apiName of ['createAccountingVoucher', 'updateAccountingVoucher', 'reviewAccountingVoucher',
    'rejectAccountingVoucher', 'postAccountingVoucher', 'cancelAccountingVoucher',
    'reverseAccountingVoucher', 'generateMissingAccountingVouchers']) {
    assert.match(api, new RegExp(`${apiName}:`), apiName)
  }
  assert.match(vouchers, /记账后将形成不可直接修改的总账分录/)
  assert.match(vouchers, /制证异常/)
  assert.match(voucherEdit, /借贷合计必须相等/)
  assert.match(voucherEdit, /不得用手工凭证替代业务处理/)
  assert.match(voucherDetail, /凭证审计轨迹/)
  assert.match(voucherDetail, /查看业务过账/)
})

test('accounts periods and versioned rules preserve audit and effective-date semantics', () => {
  assert.match(master, /科目一旦被凭证使用/)
  assert.match(master, /期间关闭统一由“期末结账”/)
  assert.match(master, /反结账也必须从同一入口保留冲销和审计证据/)
  assert.match(rules, /已发布历史版本不覆盖/)
  assert.match(rules, /同一业务事实在同一日期只能有一条有效映射/)
  assert.match(api, /publishAccountingRule:/)
  assert.match(api, /closeAccountingPeriod:/)
})

test('accounting coverage matrix exposes edge facts and blocks silent partial vouchers', () => {
  assert.match(api, /accountingRuleCoverage:/)
  assert.match(api, /\/accounting\/rule-coverage/)
  for (const text of ['覆盖矩阵', '业务事实', '稳定凭证结果', '当前生效规则', '阻断项', '现金', '银行', '承兑']) {
    assert.match(rules, new RegExp(text), text)
  }
  for (const status of ['MISSING_RULE', 'AMBIGUOUS_RULE', 'UNREGISTERED_RULE', 'UNREGISTERED_FACT']) {
    assert.match(rules, new RegExp(status), status)
  }
  assert.match(rules, /ACCOUNTING_RULE/)
  assert.match(rules, /SOURCE_REVERSAL/)
  assert.match(rules, /DIRECT_VOUCHER/)
  assert.match(rules, /NO_VOUCHER/)
})

test('trial balance and detail ledger only present posted accounting results', () => {
  assert.match(dashboard, /业务过账生成凭证草稿，审核后才能记入总账/)
  assert.match(ledger, /仅汇总已记账凭证/)
  assert.match(ledger, /试算差额/)
  assert.match(ledger, /余额采用借方为正、贷方为负/)
  assert.match(api, /accountingTrialBalance:/)
  assert.match(api, /accountingLedger:/)
})
