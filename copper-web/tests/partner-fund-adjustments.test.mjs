import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'

const read = (path) => fs.readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')

test('ERP-02 removes direct balance action and exposes formal list create detail routes', () => {
  const api = read('src/api/client.ts')
  const router = read('src/router/index.ts')
  const app = read('src/App.vue')
  assert.doesNotMatch(api, /createPartnerAccountAction|\/partner-account-actions/)
  assert.match(api, /createPartnerFundAdjustment/)
  assert.match(api, /confirmPartnerFundAdjustment/)
  assert.match(api, /reversePartnerFundAdjustment/)
  assert.match(router, /\/partner-fund-adjustments\/new/)
  assert.match(router, /\/partner-fund-adjustments\/:id/)
  assert.match(router, /path: '\/partner-accounts', redirect: '\/partner-fund-adjustments'/)
  assert.match(app, /label: '余额调整单'/)
})

test('ERP-02 UI separates drafts approval accounting tax and reversal semantics', () => {
  const list = read('src/views/PartnerFundAdjustments.vue')
  const create = read('src/views/PartnerFundAdjustmentCreate.vue')
  const detail = read('src/views/PartnerFundAdjustmentDetail.vue')
  const sop = read('docs/OWNER_OPERATIONS_AND_FINANCE_SOP.md')
  for (const value of ['CUSTOMER_REWARD_GRANT', 'SUPPLIER_REBATE_GRANT', 'OPENING_BALANCE', 'BALANCE_CORRECTION']) {
    assert.match(create, new RegExp(value))
  }
  assert.match(list, /直接调账入口/)
  assert.match(create, /不会生成现金或银行流水/)
  assert.match(detail, /确认并过账/)
  assert.match(detail, /PARTNER_FUND_ADJUSTMENT/)
  assert.match(detail, /草稿凭证/)
  assert.match(detail, /冲正会生成反向余额流水/)
  assert.match(sop, /直接增加\/扣减余额的旧入口已经退出/)
  assert.match(sop, /partner\.fund\.adjust\.approve/)
})

test('ERP-02 UI respects fine-grained adjustment permissions', () => {
  const list = read('src/views/PartnerFundAdjustments.vue')
  const detail = read('src/views/PartnerFundAdjustmentDetail.vue')
  const router = read('src/router/index.ts')
  assert.match(list, /partner\.fund\.adjust\.manage/)
  assert.match(detail, /partner\.fund\.adjust\.approve/)
  assert.match(detail, /partner\.fund\.adjust\.reverse/)
  assert.match(router, /permission: 'partner\.fund\.adjust\.view'/)
})
