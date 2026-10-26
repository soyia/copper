import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const partners = await readFile(new URL('../src/views/Partners.vue', import.meta.url), 'utf8')
const dialog = partners.slice(partners.indexOf('<el-dialog'), partners.indexOf('</el-dialog>'))

test('partner form guides users through selected role profiles before saving', () => {
  assert.match(partners, /const formSteps = computed<FormTab\[\]>\(\(\) => \[\s*'base',[\s\S]*'customer' as const[\s\S]*'supplier' as const/)
  assert.match(partners, /hasNextFormStep\.value[\s\S]*\? '下一步' : '保存'/)
  assert.match(partners, /@click="handlePrimaryAction">\{\{ primaryActionLabel \}\}/)
  assert.match(partners, /goPreviousFormStep/)
  assert.doesNotMatch(dialog, /@click="save">保存/)
})

test('credit code is required except for individual partners', () => {
  assert.match(partners, /const requiresCreditCode = computed\(\(\) => form\.entityType !== 'INDIVIDUAL'\)/)
  assert.match(partners, /prop="creditCode"/)
  assert.match(partners, /企业和个体工商户必须填写统一社会信用代码/)
  assert.match(partners, /个人主体可不填/)
})

test('initial contact copy does not force users to decide a primary contact', () => {
  assert.match(dialog, /联系人（选填）/)
  assert.match(dialog, /销售、采购、收货、财务等都可以/)
  assert.match(dialog, /label="职务\/用途"/)
  assert.doesNotMatch(dialog, />主要联系人</)
})
