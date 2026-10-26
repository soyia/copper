import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

function source(path) {
  return readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')
}

const inventory = source('src/views/Inventory.vue')
const inboundCreate = source('src/views/InboundCreate.vue')
const labelRecognition = source('src/components/PackageLabelRecognitionDialog.vue')

test('inventory packaging snapshot hash is the final full-width detail row', () => {
  const detailStart = inventory.indexOf('<el-descriptions :column="3" border size="small">')
  const detailEnd = inventory.indexOf('</el-descriptions>', detailStart)
  const detail = inventory.slice(detailStart, detailEnd)
  const hashPosition = detail.indexOf('label="快照校验码"')
  const hashItemEnd = detail.indexOf('</el-descriptions-item>', hashPosition)

  assert.match(detail, /label="规则来源" :span="2"/)
  assert.match(detail, /label="包装条码" :span="2"/)
  assert.ok(hashPosition > detail.indexOf('label="到期日"'))
  assert.ok(detail.slice(hashPosition).includes(':span="3"'))
  assert.equal(detail.slice(hashItemEnd + 23).includes('<el-descriptions-item'), false)
})

test('actual-weight packages inherit one production date from their inventory batch', () => {
  assert.match(inventory, /本批次生产日期：\{\{ formatDate\(activeBatch\.productionDate\) \}\}/)
  assert.match(inboundCreate, /:require-same-production-date="true"/)
  assert.match(labelRecognition, /这些标签的生产日期不同，请拆成多条入库明细后分别识别/)
})

test('actual weight is the first column in the per-package table', () => {
  const sectionStart = inventory.indexOf('<span>逐件实重</span>')
  const tableStart = inventory.indexOf('<el-table :data="packageInstances"', sectionStart)
  const tableEnd = inventory.indexOf('</el-table>', tableStart)
  const table = inventory.slice(tableStart, tableEnd)

  assert.ok(table.indexOf('label="实际重量"') < table.indexOf('label="箱码 / 包装码"'))
  assert.ok(table.indexOf('label="实际重量"') < table.indexOf('label="包装单位"'))
})
