import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = (path) => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8')

test('forced password dialog cannot be dismissed without changing password or logging out', () => {
  const dialog = source('src/components/ForcedPasswordChangeDialog.vue')

  assert.match(dialog, /:show-close="false"/)
  assert.match(dialog, /:close-on-click-modal="false"/)
  assert.match(dialog, /:close-on-press-escape="false"/)
  assert.match(dialog, /:before-close="preventClose"/)
  assert.match(dialog, /function preventClose\(\)/)
  assert.match(dialog, /title="修改密码"/)
  assert.doesNotMatch(dialog, /emit\('close'\)|emit\('update:visible'\)|emit\('update:modelValue'\)/)
  assert.match(dialog, /退出当前账号/)
  assert.match(dialog, /#before-submit/)
})

test('the authentication shell and router both enforce required password change', () => {
  const app = source('src/App.vue')
  const router = source('src/router/index.ts')

  assert.match(app, /appStore\.session\?\.passwordChangeRequired/)
  assert.match(app, /<ForcedPasswordChangeDialog/)
  assert.match(app, /@success="handlePasswordChanged"/)
  assert.match(app, /@logout="handleForcedLogout"/)
  assert.match(router, /appStore\.session\?\.passwordChangeRequired/)
  assert.match(router, /to\.path !== '\/settings'/)
  assert.match(router, /'\/settings\?section=password'/)
})

test('settings and forced dialog reuse one password-change form and end the old session', () => {
  const form = source('src/components/PasswordChangeForm.vue')
  const dialog = source('src/components/ForcedPasswordChangeDialog.vue')
  const settings = source('src/views/Settings.vue')
  const app = source('src/App.vue')

  assert.match(form, /api\.changePassword/)
  assert.match(form, /autocomplete="current-password"/)
  assert.match(form, /autocomplete="new-password"/)
  assert.match(dialog, /<PasswordChangeForm/)
  assert.match(settings, /<PasswordChangeForm/)
  assert.match(settings, /copper:password-changed/)
  assert.match(app, /appStore\.session = null/)
  assert.match(app, /router\.replace\('\/login'\)/)
})
