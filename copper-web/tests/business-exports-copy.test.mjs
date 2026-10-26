import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const view = readFileSync(new URL('../src/views/BusinessExports.vue', import.meta.url), 'utf8')

test('completed exports expose full selectable file name and SHA-256 with copy actions', () => {
  assert.match(view, /copyResult\(row\.fileName, '文件名'\)/)
  assert.match(view, /copyResult\(row\.archiveSha256, 'SHA-256'\)/)
  assert.match(view, /\{\{ row\.fileName \}\}/)
  assert.match(view, /\{\{ row\.archiveSha256 \}\}/)
  assert.match(view, /user-select:\s*text/)
  assert.match(view, /overflow-wrap:\s*anywhere/)
  assert.doesNotMatch(view, /shortHash\(row\.archiveSha256\)/)
})
