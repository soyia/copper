import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')

test('ERP-18 exposes one permission-aware opening migration workbench', () => {
  const app = read('src/App.vue')
  const router = read('src/router/index.ts')
  const view = read('src/views/DataMigrations.vue')

  assert.match(app, /期初数据迁移/)
  assert.match(app, /permission: 'data\.migration\.view'/)
  assert.match(router, /path: '\/data-migrations'/)
  assert.match(router, /permission: 'data\.migration\.view'/)
  for (const permission of ['data.migration.manage', 'data.migration.apply', 'data.migration.rollback', 'data.migration.cutover']) {
    assert.match(view, new RegExp(permission.replaceAll('.', '\\.')))
  }
})

test('ERP-18 keeps preflight application cutover rollback and reconciliation semantics explicit', () => {
  const api = read('src/api/client.ts')
  const view = read('src/views/DataMigrations.vue')

  for (const route of ['/data-migrations/upload', '/apply', '/rollback', '/cutover', '/consistency-check']) {
    assert.match(api, new RegExp(route.replaceAll('/', '\\/')))
  }
  for (const contract of ['上传只生成逐行预检结果', '应用不会立即记账', '确认切换', '整批撤销', '一致性检查', '后续修正必须留痕']) {
    assert.match(view, new RegExp(contract))
  }
})

test('opening receivables and payables remain navigable and settle through treasury facts', () => {
  const report = read('src/components/SettlementReportPage.vue')
  const detail = read('src/views/TreasuryDocumentDetail.vue')
  const api = read('src/api/client.ts')

  for (const type of ['OPENING_RECEIVABLE', 'OPENING_PAYABLE']) {
    assert.match(report, new RegExp(type))
    assert.match(detail, new RegExp(type))
    assert.match(api, new RegExp(type))
  }
  assert.match(report, /treasury\/documents/)
  assert.match(detail, /!openingDocument/)
  assert.match(detail, /MIGRATION_APPLY:'期初迁移应用'/)
  assert.match(read('src/views/AccountingVoucherDetail.vue'), /SOURCE_GENERATE: '来源业务自动制证'/)
})

test('rolled-back batches distinguish current facts from retained application audit', () => {
  const view = read('src/views/DataMigrations.vue')

  assert.match(view, /value === 'ROLLED_BACK' \? '曾应用' : '已应用'/)
  assert.match(view, /事实已撤销，保留历史应用行数供审计/)
})
