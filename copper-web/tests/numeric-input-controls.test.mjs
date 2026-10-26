import assert from 'node:assert/strict'
import { readdir, readFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const sourceRoot = fileURLToPath(new URL('../src', import.meta.url))

async function vueFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  const nested = await Promise.all(entries.map(async (entry) => {
    const target = path.join(directory, entry.name)
    if (entry.isDirectory()) return vueFiles(target)
    return entry.isFile() && entry.name.endsWith('.vue') ? [target] : []
  }))
  return nested.flat()
}

test('all numeric inputs use plain fields without plus or minus controls', async () => {
  const failures = []
  for (const file of await vueFiles(sourceRoot)) {
    const source = await readFile(file, 'utf8')
    const tags = source.match(/<el-input-number\b(?:"[^"]*"|[^>])*>/g) || []
    tags.forEach((tag, index) => {
      if (!/\s:controls="false"/.test(tag) || /controls-position=/.test(tag)) {
        failures.push(`${path.relative(sourceRoot, file)}#${index + 1}`)
      }
    })
  }
  assert.deepEqual(failures, [])
})
