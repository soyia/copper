import assert from 'node:assert/strict'
import { readFile, readdir } from 'node:fs/promises'
import test from 'node:test'
import ts from 'typescript'

async function loadSourceModule(path) {
  const source = await readFile(new URL(`../${path}`, import.meta.url), 'utf8')
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.ESNext },
  })
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`)
}
const quantities = await loadSourceModule('src/utils/quantity.ts')
const format = await loadSourceModule('src/api/format.ts')

test('tiny stock balances and historical six-place quantities remain visible without changing money scale', () => {
  assert.equal(format.quantity(0.004), '0.004')
  assert.equal(format.quantity('0.000001'), '0.000001')
  assert.equal(format.quantity(1234.123456), '1,234.123456')
  assert.equal(format.quantity(1), '1')
  assert.equal(format.money(0.004), '0.00')
})

test('package sums and differences reconcile at six decimal places, including a one-micro-unit mismatch', () => {
  assert.equal(quantities.sumQuantities(['0.1', '0.2', '0.000001']), 0.300001)
  assert.equal(quantities.sumQuantities(['54.960001', '55.200002', '53.800003']), 163.960006)
  assert.equal(quantities.sameQuantity(0.1 + 0.2, '0.300000'), true)
  assert.equal(quantities.sameQuantity('0.004', '0.003999'), false)
  assert.equal(quantities.subtractQuantities('0.004001', '0.004'), 0.000001)
  assert.equal(quantities.roundQuantity('1.2345675'), 1.234568)
  assert.equal(quantities.roundQuantity('-1.2345675'), -1.234568)
  assert.equal(quantities.roundQuantity(1e-6), 0.000001)
  assert.throws(() => quantities.roundQuantity(Infinity), /有限数字/)
})

test('OCR kilograms convert to the actual stock mass unit without treating kilograms as tonnes or pieces', () => {
  assert.equal(quantities.kilogramsToBaseQuantity('1.234', 't'), 0.001234)
  assert.equal(quantities.kilogramsToBaseQuantity('1.234', '吨'), 0.001234)
  assert.equal(quantities.kilogramsToBaseQuantity('1.234567', 'kg'), 1.234567)
  assert.equal(quantities.kilogramsToBaseQuantity('1.234567', '克'), 1234.567)
  assert.throws(() => quantities.kilogramsToBaseQuantity(1, '盘'), /不能自动换算/)
  assert.throws(() => quantities.kilogramsToBaseQuantity(1, 'm'), /不能自动换算/)
})

async function vueFiles(directory) {
  const result = []
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const target = new URL(`${entry.name}${entry.isDirectory() ? '/' : ''}`, directory)
    if (entry.isDirectory()) result.push(...await vueFiles(target))
    else if (entry.name.endsWith('.vue')) result.push(target)
  }
  return result
}

test('quantity, mass, MOQ, order-multiple and packaging inputs never clamp operational quantities to cents', async () => {
  const failures = []
  let checked = 0
  for (const file of await vueFiles(new URL('../src/', import.meta.url))) {
    const source = await readFile(file, 'utf8')
    for (const tag of source.match(/<el-input-number\b(?:"[^"]*"|[^>])*>/g) || []) {
      const model = tag.match(/(?:v-model|:model-value)="([^"]+)"/)?.[1] || ''
      if (!/quantity|weight|orderMultiple|basisValues/i.test(model)) continue
      checked += 1
      if (/:precision="2"|:min="0\.01"/.test(tag)) failures.push(`${file.pathname}: ${model}`)
      assert.match(tag, /:precision="(?:6|packageQuantityPrecision\(row\))"/, `${file.pathname}: ${model}`)
    }
  }
  assert.ok(checked >= 50, 'all operational and master-data quantity entry points were scanned')
  assert.deepEqual(failures, [])
})

test('inbound manual, pasted, OCR and outbound whole-package paths share the six-place quantity contract', async () => {
  const source = async (file) => readFile(new URL(`../${file}`, import.meta.url), 'utf8')
  const inbound = await source('src/views/InboundCreate.vue')
  const outbound = await source('src/views/OutboundCreate.vue')
  const nonTrade = await source('src/views/InventoryNonTradeCreate.vue')
  const recognition = await source('src/components/PackageLabelRecognitionDialog.vue')
  assert.match(inbound, /\\d\{1,6\}/)
  assert.match(inbound, /sameQuantity\(sumQuantities/)
  assert.match(outbound, /sameQuantity\(sumQuantities/)
  for (const text of [inbound, outbound, nonTrade, recognition]) {
    assert.doesNotMatch(text, /toFixed\(2\)|quantityHundredths|>= 0\.005/)
  }
  for (const text of [inbound, outbound, recognition]) assert.match(text, /kilogramsToBaseQuantity/)
  assert.match(recognition, /actual_weight_kg: String\(roundQuantity\(row\.actualWeightKg\)\)/)
})
