import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const detail = readFileSync(
  new URL('../src/views/PackagingTemplateDetail.vue', import.meta.url),
  'utf8',
)

test('a template without packaging levels uses one compact table empty row', () => {
  assert.match(detail, /<template #empty>/)
  assert.match(detail, /当前模板仅使用主库存单位，没有包装层次/)
  assert.match(detail, /packaging-rules-table \.el-table__empty-block/)
  assert.match(detail, /min-height: 56px/)
  assert.doesNotMatch(detail, /<el-empty v-if="!templateDetail\.rules\?\.length"/)
})
