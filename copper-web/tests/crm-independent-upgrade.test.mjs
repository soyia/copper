import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

function source(path) {
  return readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')
}

const api = source('src/api/client.ts')
const router = source('src/router/index.ts')
const app = source('src/App.vue')
const dashboard = source('src/views/CrmDashboard.vue')
const leads = source('src/views/CrmLeads.vue')
const customers = source('src/views/CrmCustomerDetail.vue')
const opportunities = source('src/views/CrmOpportunityDetail.vue')
const quotes = source('src/views/CrmQuoteDetail.vue')
const quoteEdit = source('src/views/CrmQuoteEdit.vue')
const activities = source('src/components/CrmActivityPlanPanel.vue')
const salesCreate = source('src/views/SalesOrderCreate.vue')
const customerList = source('src/views/CrmCustomers.vue')
const agents = source('AGENTS.md')

test('CRM is exposed as an independent customer operating workspace', () => {
  for (const path of ['/crm/leads', '/crm/customers', '/crm/opportunities', '/crm/quotes', '/crm/activities']) {
    assert.match(router, new RegExp(path.replaceAll('/', '\\/')), path)
  }
  for (const label of ['客户经营', '客户待办', '线索（可选）', '客户经营档案', '商机（可选）', '报价', '活动与计划']) {
    assert.match(app, new RegExp(label), label)
  }
  assert.match(router, /crm\.record\.view/)
  assert.match(router, /crm\.analysis\.view/)
})

test('small-business CRM assists action without forcing a sales funnel', () => {
  for (const label of ['今天先联系谁', '报价待办', '复购提醒', '欠款提醒', '直接销售下单', '可选：线索与商机分析']) {
    assert.match(dashboard, new RegExp(label), label)
  }
  assert.match(customerList, /directOrder\(row\.partnerId\)/)
  assert.match(customerList, /directQuote\(row\.partnerId\)/)
  assert.match(customers, /直接销售下单/)
  assert.match(salesCreate, /不要求先建立线索、商机或报价/)
  assert.match(salesCreate, /route\.query\.customerId/)
  assert.match(agents, /不得为了补 CRM 记录耽误实际接单时间/)
  assert.match(agents, /只能形成建议和待办/)
})

test('lead qualification and opportunity closure preserve explicit lifecycle decisions', () => {
  for (const apiName of ['crmDuplicates', 'qualifyCrmLead', 'disqualifyCrmLead', 'reactivateCrmLead',
    'changeCrmOpportunityStage']) assert.match(api, new RegExp(`${apiName}:`), apiName)
  assert.match(leads, /先查重再保存/)
  assert.match(opportunities, /成交原因|流失原因/)
  assert.match(opportunities, /重新激活/)
})

test('quotes freeze tax packaging and payment snapshots before optional sales conversion', () => {
  for (const apiName of ['activateCrmQuote', 'reviseCrmQuote', 'acceptCrmQuote', 'rejectCrmQuote',
    'cancelCrmQuote', 'convertCrmQuote']) assert.match(api, new RegExp(`${apiName}:`), apiName)
  assert.match(quoteEdit, /保存后冻结快照/)
  assert.match(quoteEdit, /税务快照/)
  assert.match(quoteEdit, /付款条件/)
  assert.match(quotes, /转销售订单/)
  assert.match(quotes, /CRM 不会自动占库存或产生应收/)
  assert.match(quotes, /isActiveRoute/)
  assert.doesNotMatch(quoteEdit, /采购订单|供应商/)
})

test('activities plans and customer analysis remain separate read-only business concepts', () => {
  for (const apiName of ['crmActivities', 'createCrmActivity', 'crmPlans', 'createCrmPlan',
    'completeCrmPlan', 'cancelCrmPlan', 'crmCustomerAnalysis']) {
    assert.match(api, new RegExp(`${apiName}:`), apiName)
  }
  assert.match(activities, /已完成活动保留历史/)
  assert.match(activities, /未来安排单独作为计划/)
  assert.match(customers, /只读经营分析/)
  assert.match(dashboard, /交易、成本、毛利和欠款来自销售、库存、财务只读事实/)
})
