import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const sop = readFileSync(
  new URL('../docs/OWNER_OPERATIONS_AND_FINANCE_SOP.md', import.meta.url),
  'utf8',
)
const agents = readFileSync(new URL('../AGENTS.md', import.meta.url), 'utf8')
const selfHostingReadme = readFileSync(
  new URL('../docs/self-hosting/README.md', import.meta.url),
  'utf8',
)

test('owner SOP covers the required daily and month-end operations', () => {
  for (const visualControl of [
    '老板一页图',
    'flowchart LR',
    'flowchart TD',
    '系统入口速查',
    '<details>',
  ]) {
    assert.ok(
      sop.includes(visualControl),
      `missing visual control: ${visualControl}`,
    )
  }
  assert.equal(sop.match(/```mermaid/g)?.length, 3)
  assert.equal(sop.match(/<details>/g)?.length, 3)
  assert.equal(sop.match(/<\/details>/g)?.length, 3)

  for (const heading of [
    '日常开单 SOP',
    '错单冲正 SOP',
    '发票勾稽 SOP',
    '银行对账 SOP',
    '月底关账 SOP',
    '反结账限制',
    '备份与恢复 SOP',
  ]) {
    assert.ok(sop.includes(heading), `missing SOP section: ${heading}`)
  }

  for (const control of [
    '草稿可以改，确认以后不能直接覆盖',
    '先撤下游，再改上游',
    '开票不等于收款',
    '确认销售只形成待发承诺',
    '正式结账并锁定期间',
    '按时间倒序逐期反结账',
  ]) {
    assert.ok(sop.includes(control), `missing operating control: ${control}`)
  }
})

test('owner SOP keeps reversal, reconciliation, and restore fail-closed', () => {
  for (const safeguard of [
    '不能只撤销银行匹配',
    '不能用 Copper 的“作废”代替税务处理',
    '不能拿旧备份覆盖后来发生的真实业务',
    '只恢复到空数据库、空数据目录和隔离端口',
    '不原地覆盖仍可用的生产数据',
    '业务导出不能代替整站备份',
  ]) {
    assert.ok(sop.includes(safeguard), `missing safeguard: ${safeguard}`)
  }

  assert.doesNotMatch(sop, /DROP\s+DATABASE|DELETE\s+FROM|rm\s+-rf|git\s+reset\s+--hard/i)
})

test('owner SOP is governed and discoverable from durable documentation', () => {
  assert.match(agents, /OWNER_OPERATIONS_AND_FINANCE_SOP\.md/)
  assert.match(agents, /改单、冲正、对账、结账、反结账、备份或恢复/)
  assert.match(selfHostingReadme, /OWNER_OPERATIONS_AND_FINANCE_SOP\.md/)
  assert.match(sop, /国家税务总局公告 2024 年第 11 号/)
  assert.match(sop, /M2_BACKUP_EXPORT_ARCHITECTURE\.md/)
})
