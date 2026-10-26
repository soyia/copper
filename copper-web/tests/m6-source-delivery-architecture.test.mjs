import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import test from 'node:test'

const architecture = readFileSync(
  new URL('../docs/self-hosting/M6_SOURCE_DELIVERY_OPEN_SOURCE_ARCHITECTURE.md', import.meta.url),
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

test('M6 source delivery binds the full core to an exact reproducible release', () => {
  for (const repository of ['copper_web', 'copper_backend', 'copper_deploy']) {
    assert.match(architecture, new RegExp(`\\b${repository}\\b`))
  }

  for (const binding of [
    'copper-source-delivery/v1',
    '精确提交 SHA',
    '迁移集合',
    '不可变镜像摘要',
    '依赖锁',
    'SBOM',
    '每文件 SHA-256',
    'SOURCE_DATE_EPOCH',
  ]) {
    assert.ok(architecture.includes(binding), `missing source delivery binding: ${binding}`)
  }

  assert.match(architecture, /客户源码交付版、未来公开版和官方部署版使用同一套核心业务源码/)
  assert.match(architecture, /采购、入库、库存、销售、出库、应收应付、收付款、对账、导出、备份恢复和升级能力不得以“内部服务”为名从交付或公开核心中移除/)
})

test('M6 remains fail-closed for secrets, customer data and legal review', () => {
  for (const excluded of [
    '生产或测试密钥',
    '客户数据库',
    '数据库备份',
    '个人信息',
    '权属不清',
  ]) {
    assert.ok(architecture.includes(excluded), `missing excluded asset: ${excluded}`)
  }

  assert.match(architecture, /M6-01 只冻结决策流程，不选择最终许可证/)
  assert.match(architecture, /不创建或复制“暂定”的最终 `LICENSE`/)
  assert.match(architecture, /知识产权律师/)
  assert.match(architecture, /失败关闭/)
})

test('M6 delivery tree excludes the unlicensed Hongtai website archive', () => {
  assert.equal(
    existsSync(new URL('../docs/product-catalog/hongtai-copper/', import.meta.url)),
    false,
    'Hongtai website snapshots and external images must not re-enter the delivery tree',
  )
  assert.equal(
    existsSync(new URL('../scripts/sync-hongtai-catalog.py', import.meta.url)),
    false,
    'the removed website archiver must not be able to regenerate the excluded directory',
  )
  assert.match(architecture, /宏泰官网抓取快照及外部图片已从当前树移除/)
  assert.match(architecture, /GitHub 托管历史 PR refs、页面与缓存不随普通 Git 改写删除/)
})

test('M6 uses GitHub as the only authority and keeps deferred M5 certification separate', () => {
  assert.match(architecture, /GitHub `origin\/main` 是当前唯一集成和交付权威源/)
  assert.match(architecture, /当前 M6 工作不访问、不修改、不配置 Gitee/)
  assert.match(architecture, /不会自动完成 M5-04、M5-05 或 M5-07/)
  assert.match(architecture, /不会产生本地 `Certified` 平台结论/)

  assert.match(
    roadmap,
    /M6 \| 客户源码交付与公开开源准备 \| 内部全流程演练已通过、正式退出未通过/,
  )
  assert.match(roadmap, /FORMAL_RELEASE_BLOCKED/)
  assert.match(roadmap, /当前 M6 工作不读取、写入或同步 Gitee/)
  assert.match(githubTasks, /M6 总跟踪 Issue `#57` 与 M6-01 至 M6-07 已创建/)
})

test('M6 issue graph covers architecture through final exit in dependency order', () => {
  const expectedIssues = [
    'copper_web/issues/58',
    'copper_web/issues/59',
    'copper_deploy/issues/69',
    'copper_deploy/issues/70',
    'copper_web/issues/60',
    'copper_deploy/issues/71',
    'copper_web/issues/61',
  ]

  for (const issue of expectedIssues) {
    assert.ok(architecture.includes(issue), `missing M6 issue: ${issue}`)
    assert.ok(githubTasks.includes(issue), `missing roadmap issue: ${issue}`)
  }

  assert.match(architecture, /M6-02 与 M6-03 可在 M6-01 合并后并行/)
  assert.match(architecture, /M6-07 必须最后执行/)
})
