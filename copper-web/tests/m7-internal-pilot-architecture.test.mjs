import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const architecture = readFileSync(
  new URL('../docs/self-hosting/M7_INTERNAL_PILOT_AND_RELEASE_ARCHITECTURE.md', import.meta.url),
  'utf8',
)
const roadmap = readFileSync(
  new URL('../docs/SELF_HOSTING_AND_DATA_CONTROL_ROADMAP.md', import.meta.url),
  'utf8',
)
const githubTasks = readFileSync(
  new URL('../docs/GITHUB_ROADMAP_TASKS.md', import.meta.url),
  'utf8',
)
const pricing = readFileSync(
  new URL('../docs/self-hosting/PRICING_AND_SERVICE_CATALOG.md', import.meta.url),
  'utf8',
)

test('M7 keeps internal rehearsal separate from customer delivery and public release', () => {
  for (const state of [
    'INTERNAL_REHEARSAL_ONLY',
    'FORMAL_RELEASE_BLOCKED',
    '内部演练',
    '真实试点',
    '正式收费交付',
    '公开发布',
  ]) {
    assert.ok(architecture.includes(state), `missing M7 state: ${state}`)
  }

  assert.match(roadmap, /M7-01 至 M7-06 的内部工程工作已完成/)
  assert.match(roadmap, /M7-02 首份样本为 `SUPPORT_REQUIRED`/)
  assert.match(roadmap, /真实非开发客户独立样本移至 M7-07 正式发布门槛/)
  assert.match(roadmap, /### M7-03 首日经营全闭环/)
  assert.match(roadmap, /### M7-04 升级、备份、恢复/)
  assert.match(roadmap, /FIRST_RELEASE_CLOUD_ONLY/)
  assert.match(architecture, /不得称客户试点、正式交付、正式报价或已开源/)
})

test('M7 self-service evidence is operator-led and counts hidden support', () => {
  assert.match(architecture, /产品负责人.*亲自运行文档命令和浏览器流程/)
  assert.match(architecture, /默认不 SSH 登录、不代为复制命令、不修改服务器/)
  assert.match(architecture, /每次超出正式手册的信息都必须编号/)
  assert.match(architecture, /文档缺陷/)
  assert.match(architecture, /产品\/自动化缺陷/)
  assert.match(architecture, /环境前提/)
  assert.match(architecture, /操作理解/)
  assert.match(architecture, /第三方等待/)
})

test('M7 covers the full business and operational lifecycle', () => {
  for (const capability of [
    '采购订单',
    '入库',
    '库存',
    '销售订单',
    '出库',
    '应付',
    '应收',
    '付款',
    '收款',
    '核销',
    '对账',
    '受支持来源升级',
    '加密备份',
    '旁路恢复',
    '完整人可读导出',
    '阻断公共互联网',
  ]) {
    assert.ok(architecture.includes(capability), `missing M7 capability: ${capability}`)
  }

  assert.match(architecture, /十五类商品的可配置性必须由自动契约覆盖/)
  assert.match(architecture, /不写 SQL、不修改源码/)
})

test('M7 records cost and learning evidence without creating feature tiers', () => {
  for (const metric of [
    '总墙钟',
    '操作者主动时间',
    '支持事件',
    '失败步骤',
    '备份大小',
    '恢复时间',
    '学习',
  ]) {
    assert.ok(architecture.includes(metric), `missing M7 metric: ${metric}`)
  }

  for (const price of ['¥0', '¥349', '¥649', '¥99', '¥995', '¥249', '¥150']) {
    assert.ok(architecture.includes(price), `missing M7 price candidate: ${price}`)
    assert.ok(pricing.includes(price), `M7 price drifted from catalog: ${price}`)
  }

  assert.match(architecture, /不得制造功能阉割版/)
})

test('M7 issue graph ends in a fail-closed 1.0.0 decision', () => {
  const issues = [
    'copper_web/issues/72',
    'copper_web/issues/73',
    'copper_deploy/issues/86',
    'copper_web/issues/74',
    'copper_deploy/issues/87',
    'copper_web/issues/75',
    'copper_deploy/issues/88',
    'copper_web/issues/76',
  ]

  for (const issue of issues) {
    assert.ok(architecture.includes(issue), `architecture missing M7 issue: ${issue}`)
    assert.ok(githubTasks.includes(issue), `task list missing M7 issue: ${issue}`)
  }

  assert.match(architecture, /客户自有公网云 Ubuntu Server 24\.04 LTS x86_64 完整样本/)
  assert.match(architecture, /精确 `origin\/main` 闭集介质.*没有新增支持命令/)
  assert.match(architecture, /每增加一种拟对外支持的云平台.*都必须先补齐/)
  assert.match(architecture, /本地\/LAN 与第三环境.*移出 `1\.0` 首发兼容清单/)
  assert.match(architecture, /`1\.0\.0` 只在 M7-07 明确 `GO` 后创建/)
  assert.match(architecture, /M6 的 `0\.4\.0-rc\.2` 是历史内部证据，不得直接改名为 `1\.0\.0`/)
})
