import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = (path) => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')

test('role page runs live acceptance for every small-business default job', () => {
  const page = source('src/views/RolePermissions.vue')
  const api = source('src/api/client.ts')
  assert.match(page, /默认岗位验收/)
  assert.match(page, /api\.defaultRoleAcceptance\(\)/)
  assert.match(page, /按实时数据库授权检查/)
  assert.match(api, /\/system\/roles\/default-acceptance/)
  assert.match(api, /interface DefaultRoleAcceptance/)
})

test('high-risk buttons use independent permissions', () => {
  const invoice = source('src/views/TaxInvoiceDetail.vue')
  const close = source('src/views/PeriodClose.vue')
  const bank = source('src/views/BankReconciliations.vue')
  assert.match(invoice, /hasPermission\('invoice\.void'\)/)
  assert.match(close, /hasPermission\('financial\.close\.reopen'\)/)
  assert.match(bank, /hasPermission\('financial\.bank\.account\.manage'\)/)
  assert.match(bank, /canManageBankAccounts/)
})

test('owner SOP explains default duties and split sensitive authority', () => {
  const sop = source('docs/OWNER_OPERATIONS_AND_FINANCE_SOP.md')
  for (const marker of [
    '默认岗位验收', 'invoice.void', 'financial.bank.account.manage',
    'financial.close.manage', 'financial.close.reopen',
  ]) assert.match(sop, new RegExp(marker.replaceAll('.', '\\.')), marker)
  assert.match(sop, /默认只有老板和系统管理员拥有反结账权限/)
})
