import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const packageJson = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'))
const viteConfig = readFileSync(new URL('../vite.config.ts', import.meta.url), 'utf8')

test('development and preview servers listen on LAN interfaces', () => {
  assert.match(packageJson.scripts.dev, /vite --host 0\.0\.0\.0 --port 8076/)
  assert.match(packageJson.scripts.preview, /vite preview --host 0\.0\.0\.0 --port 8077/)
})

test('LAN browsers still reach the backend through the same-origin API proxy', () => {
  assert.match(viteConfig, /process\.env\.COPPER_API_TARGET \|\| 'http:\/\/127\.0\.0\.1:8191'/)
  assert.match(viteConfig, /'\/api': apiProxy/)
})
