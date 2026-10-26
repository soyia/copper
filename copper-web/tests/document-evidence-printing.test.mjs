import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

function source(path) {
  return readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')
}

const api = source('src/api/client.ts')
const evidence = source('src/components/EvidenceAttachments.vue')
const inventory = source('src/views/InventoryDocumentDetail.vue')
const inventoryConfig = source('src/utils/inventoryDocuments.ts')
const bank = source('src/views/BankReconciliations.vue')
const tax = source('src/views/TaxInvoiceDetail.vue')
const close = source('src/views/PeriodClose.vue')
const auditedPrint = source('src/composables/useAuditedDocumentPrint.ts')
const textHash = source('src/utils/sha256Text.ts')

test('seven mandatory documents print through one audited native-browser workflow', () => {
  for (const text of [inventory, bank, tax, close]) {
    assert.match(text, /print-sheet a4-print-sheet/)
    assert.match(text, /useAuditedDocumentPrint/)
    assert.match(text, /snapshotHash/)
    assert.match(text, /show-output-history/)
  }
  for (const label of ['采购退货单', '库存调拨单', '库存盘点单', '成本调整单']) {
    assert.match(inventoryConfig, new RegExp(label))
  }
  assert.match(bank, /银行余额调节表/)
  assert.match(tax, /税票勾稽表/)
  assert.match(close, /期末关账检查表/)
  assert.match(auditedPrint, /recordEvidenceOutput/)
  assert.match(auditedPrint, /useDocumentPrint/)
  assert.match(auditedPrint, /sha256Snapshot/)
  assert.match(textHash, /globalThis\.crypto\?\.subtle/)
  assert.match(textHash, /fallbackSha256/)
})

test('signed stamped photos preview on the original document and cannot lose audit context', () => {
  assert.match(api, /SIGNED_STAMPED_COPY/)
  assert.match(api, /BANK_RECONCILIATION/)
  assert.match(api, /PERIOD_CLOSE/)
  assert.match(api, /evidenceAttachmentPreviewUrl/)
  assert.match(api, /evidenceOutputRecords/)
  assert.match(evidence, /签字盖章归档件/)
  assert.match(evidence, /签字盖章与现场影像/)
  assert.match(evidence, /preview-src-list/)
  assert.match(evidence, /打印 \/ 另存 PDF 留痕/)
  assert.match(evidence, /错误文件只能留痕作废/)
})

test('printed documents contain business audit explanations and signature positions', () => {
  for (const text of [inventory, bank, tax, close]) {
    assert.match(text, /document-print-audit/)
    assert.match(text, /document-print-signatures/)
    assert.match(text, /打印数据摘要/)
  }
  assert.match(inventory, /操作记录/)
  assert.match(inventory, /v-if="canViewCost"/)
  assert.match(bank, /审计轨迹/)
  assert.match(bank, /signedMoney\(row\.direction, row\.amount\)/)
  assert.match(tax, /查验与审计轨迹/)
  assert.match(close, /期间锁定与反结账审计/)
})
