import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'

const source = (path) => fs.readFileSync(path, 'utf8')

test('ERP-15 exposes one lightweight exception worklist and immutable detail trail', () => {
  const menu = source('src/App.vue')
  const router = source('src/router/index.ts')
  const list = source('src/views/RiskExceptions.vue')
  const detail = source('src/views/RiskExceptionDetail.vue')

  assert.match(menu, /经营管理/)
  assert.match(menu, /例外审批/)
  assert.match(router, /path: '\/risk-exceptions'/)
  assert.match(router, /path: '\/risk-exceptions\/:id'/)
  for (const contract of ['普通业务不增加审批步骤', '批准不等于执行业务', '待负责人处理', '已批准待执行', '审批阈值']) {
    assert.match(list, new RegExp(contract), contract)
  }
  for (const contract of ['冻结业务快照', '不可变审批轨迹', '回到原单执行', '执行付款', '转交', '撤回']) {
    assert.match(detail, new RegExp(contract), contract)
  }
})

test('ERP-15 routes every in-scope high-risk action through one authorization helper', () => {
  const helper = source('src/utils/riskException.ts')
  assert.match(helper, /riskExceptionSourceStatus/)
  assert.match(helper, /activeRequest\?\.status === 'APPROVED'/)
  assert.match(helper, /activeRequest\?\.status === 'PENDING'/)
  assert.match(helper, /提交后不会自动/)

  const integrations = {
    'src/views/SalesOrderDetail.vue': /approvalId/,
    'src/views/OutboundDetail.vue': /approvalId/,
    'src/views/InventoryDocumentDetail.vue': /resolveRiskException/,
    'src/views/PartnerFundAdjustmentDetail.vue': /resolveRiskException/,
    'src/views/TreasuryDocuments.vue': /resolveRiskException/,
    'src/views/TreasuryDocumentDetail.vue': /resolveRiskException/,
    'src/views/Payments.vue': /resolveRiskException/,
    'src/views/SupplierPayments.vue': /resolveRiskException/,
    'src/views/CustomerAdvances.vue': /resolveRiskException/,
    'src/components/SupplierPaymentForm.vue': /submitSupplierPaymentException/,
  }
  for (const [path, contract] of Object.entries(integrations)) assert.match(source(path), contract, path)
})

test('ERP-15 keeps approval separate from payment and business execution', () => {
  const client = source('src/api/client.ts')
  const detail = source('src/views/RiskExceptionDetail.vue')
  const helper = source('src/utils/riskException.ts')

  assert.match(client, /submitSupplierPaymentException/)
  assert.match(client, /executeRiskException/)
  assert.match(helper, /尚未发生实际付款/)
  assert.match(detail, /批准不会自动执行原业务/)
  assert.match(detail, /真正建立付款/)
})
