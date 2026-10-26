import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import {
  mkdtempSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import test from 'node:test'

const projectRoot = resolve(new URL('..', import.meta.url).pathname)
const scanner = join(projectRoot, 'scripts/security/audit-repositories.mjs')
const config = join(projectRoot, 'config/m6-confidentiality-audit.json')

function git(repository, ...arguments_) {
  return execFileSync('git', ['-C', repository, ...arguments_], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim()
}

function gitWithEnvironment(repository, environment, ...arguments_) {
  return execFileSync('git', ['-C', repository, ...arguments_], {
    encoding: 'utf8',
    env: { ...process.env, ...environment },
    stdio: ['ignore', 'pipe', 'pipe'],
  }).trim()
}

function createRepository(root, name = 'fixture') {
  const repository = join(root, name)
  mkdirSync(repository, { recursive: true })
  git(repository, 'init', '-b', 'main')
  git(repository, 'config', 'user.name', 'M6 Audit Fixture')
  git(repository, 'config', 'user.email', 'audit@example.com')
  writeFileSync(join(repository, 'README.md'), 'synthetic audit fixture\n')
  git(repository, 'add', 'README.md')
  git(repository, 'commit', '-m', 'initial clean fixture')
  return repository
}

function runAudit(repository, report, extraArguments = []) {
  return spawnSync(
    process.execPath,
    [
      scanner,
      '--repo',
      `fixture=${repository}`,
      '--config',
      config,
      '--report',
      report,
      '--scope',
      'all',
      ...extraArguments,
    ],
    { encoding: 'utf8' },
  )
}

test('strict audit detects a removed historical secret without storing content or original path', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'copper-m6-history-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const repository = createRepository(root)
  const sensitivePath = '.env.customer-name'
  const syntheticSecret = ['-----BEGIN ', 'PRIVATE KEY-----', '\nfixture-only\n'].join('')
  writeFileSync(join(repository, sensitivePath), syntheticSecret)
  git(repository, 'add', sensitivePath)
  git(repository, 'commit', '-m', 'add synthetic scanner fixture')
  rmSync(join(repository, sensitivePath))
  git(repository, 'add', '-u')
  git(repository, 'commit', '-m', 'remove synthetic scanner fixture')

  const reportPath = join(root, 'strict-report.json')
  const result = runAudit(repository, reportPath)
  assert.equal(result.status, 1)
  const reportText = readFileSync(reportPath, 'utf8')
  const report = JSON.parse(reportText)
  assert.equal(report.decision, 'BLOCK')
  assert.ok(report.findings.some((finding) => finding.ruleId === 'SECRET-CONTENT-PRIVATE-KEY'))
  assert.ok(report.findings.some((finding) => finding.scope === 'history'))
  assert.doesNotMatch(reportText, /fixture-only/)
  assert.ok(!reportText.includes(sensitivePath))
  assert.equal(report.policy.matchingContentStored, false)
  assert.equal(report.policy.originalPathsStored, false)
})

test('new-leak gate accepts exact known blockers but rejects changed confidential content', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'copper-m6-baseline-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const repository = createRepository(root)
  const historicalPath = '.env.legacy'
  const oldSyntheticSecret = ['-----BEGIN ', 'PRIVATE KEY-----', '\nlegacy-fixture\n'].join('')
  writeFileSync(join(repository, historicalPath), oldSyntheticSecret)
  git(repository, 'add', historicalPath)
  git(repository, 'commit', '-m', 'add known synthetic finding')

  const strictReportPath = join(root, 'strict.json')
  assert.equal(runAudit(repository, strictReportPath).status, 1)
  const strictReport = JSON.parse(readFileSync(strictReportPath, 'utf8'))
  const baselinePath = join(root, 'baseline.json')
  writeFileSync(
    baselinePath,
    JSON.stringify({
      format: 'copper-confidentiality-baseline/v1',
      knownFindingIds: strictReport.findings.map((finding) => finding.findingId),
    }),
  )

  const knownReportPath = join(root, 'known.json')
  const known = runAudit(repository, knownReportPath, ['--baseline', baselinePath, '--gate', 'new'])
  assert.equal(known.status, 0)
  const knownReport = JSON.parse(readFileSync(knownReportPath, 'utf8'))
  assert.equal(knownReport.summary.newBlocking, 0)
  assert.ok(knownReport.summary.knownBlockers > 0)

  const changedSyntheticSecret = ['-----BEGIN ', 'PRIVATE KEY-----', '\nnew-fixture\n'].join('')
  writeFileSync(join(repository, historicalPath), changedSyntheticSecret)
  const changedReportPath = join(root, 'changed.json')
  const changed = runAudit(repository, changedReportPath, [
    '--baseline',
    baselinePath,
    '--gate',
    'new',
  ])
  assert.equal(changed.status, 1)
  const changedText = readFileSync(changedReportPath, 'utf8')
  const changedReport = JSON.parse(changedText)
  assert.ok(changedReport.summary.newBlocking > 0)
  assert.doesNotMatch(changedText, /new-fixture/)
})

test('shallow history and oversized objects fail closed', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'copper-m6-incomplete-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const source = createRepository(root, 'source')
  writeFileSync(join(source, 'second.txt'), 'second commit\n')
  git(source, 'add', 'second.txt')
  git(source, 'commit', '-m', 'second fixture commit')

  const shallow = join(root, 'shallow')
  execFileSync('git', ['clone', '--depth', '1', `file://${source}`, shallow], { stdio: 'ignore' })
  const shallowReportPath = join(root, 'shallow.json')
  const shallowResult = runAudit(shallow, shallowReportPath)
  assert.equal(shallowResult.status, 1)
  const shallowReport = JSON.parse(readFileSync(shallowReportPath, 'utf8'))
  assert.ok(shallowReport.findings.some((finding) => finding.ruleId === 'AUDIT-INCOMPLETE-SHALLOW-CLONE'))

  const smallLimitConfig = join(root, 'small-limit-config.json')
  const parsedConfig = JSON.parse(readFileSync(config, 'utf8'))
  parsedConfig.maxObjectBytes = 1024
  writeFileSync(smallLimitConfig, JSON.stringify(parsedConfig))
  writeFileSync(join(source, 'large.bin'), Buffer.alloc(2048, 0x41))
  const largeReportPath = join(root, 'large.json')
  const largeResult = spawnSync(
    process.execPath,
    [
      scanner,
      '--repo',
      `fixture=${source}`,
      '--config',
      smallLimitConfig,
      '--report',
      largeReportPath,
      '--scope',
      'current',
    ],
    { encoding: 'utf8' },
  )
  assert.equal(largeResult.status, 1)
  const largeReport = JSON.parse(readFileSync(largeReportPath, 'utf8'))
  assert.ok(largeReport.findings.some((finding) => finding.ruleId === 'AUDIT-INCOMPLETE-LARGE-FILE'))
})

test('audit rules distinguish actual values from header names, runtime reads, and digest fragments', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'copper-m6-precision-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const repository = createRepository(root)
  const authorization = ['Authori', 'zation'].join('')
  const cookie = ['Cook', 'ie'].join('')
  const mobile = ['138', '0013', '8000'].join('')
  writeFileSync(
    join(repository, 'precision.txt'),
    [
      'for (Cookie cookie : cookies) {}',
      'initialPassword="$(cat /run/secret)"',
      'sha256: aa1513809944ccddeeff00112233445566778899aabbccddeeff0011223344',
      authorization + ': Bearer abcdefghijklmnop',
      cookie + ': session=abcdefghijk',
      'mobile: ' + mobile,
    ].join('\n'),
  )

  const reportPath = join(root, 'precision.json')
  const result = runAudit(repository, reportPath, ['--scope', 'current'])
  assert.equal(result.status, 1)
  const report = JSON.parse(readFileSync(reportPath, 'utf8'))
  const rules = report.findings.map((finding) => finding.ruleId)
  assert.ok(rules.includes('PRIVACY-CONTENT-AUTH-HEADER'))
  assert.ok(rules.includes('PRIVACY-CONTENT-CN-MOBILE'))
  assert.ok(!rules.includes('SECRET-CONTENT-CREDENTIAL-ASSIGNMENT'))
  assert.equal(
    report.findings.find((finding) => finding.ruleId === 'PRIVACY-CONTENT-AUTH-HEADER')?.matchCount,
    2,
  )
  assert.equal(
    report.findings.find((finding) => finding.ruleId === 'PRIVACY-CONTENT-CN-MOBILE')?.matchCount,
    1,
  )
})

test('audit does not require review for GitHub user noreply identities', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'copper-m6-noreply-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const repository = createRepository(root)
  const noreply = ['12345+fixture', 'users.noreply.github.com'].join('@')
  git(repository, 'config', 'user.email', noreply)
  git(repository, 'commit', '--amend', '--no-edit', '--reset-author')
  writeFileSync(join(repository, 'noreply.txt'), 'noreply fixture\n')
  git(repository, 'add', 'noreply.txt')
  git(repository, 'commit', '-m', 'noreply fixture')

  const reportPath = join(root, 'noreply.json')
  const result = runAudit(repository, reportPath)
  assert.equal(result.status, 0)
  const report = JSON.parse(readFileSync(reportPath, 'utf8'))
  assert.ok(!report.findings.some((finding) => finding.ruleId === 'PRIVACY-GIT-IDENTITY-REVIEW'))
})

test('audit accepts GitHub official noreply only for the exact platform address', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'copper-m6-github-noreply-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))

  const officialRepository = createRepository(root, 'official')
  const userNoreply = ['12345+fixture', 'users.noreply.github.com'].join('@')
  git(officialRepository, 'config', 'user.name', 'Fixture User')
  git(officialRepository, 'config', 'user.email', userNoreply)
  git(officialRepository, 'commit', '--amend', '--no-edit', '--reset-author')
  writeFileSync(join(officialRepository, 'platform-merge.txt'), 'platform merge fixture\n')
  git(officialRepository, 'add', 'platform-merge.txt')
  gitWithEnvironment(
    officialRepository,
    {
      GIT_COMMITTER_NAME: 'GitHub',
      GIT_COMMITTER_EMAIL: ['noreply', 'github.com'].join('@'),
    },
    'commit',
    '-m',
    'GitHub platform merge fixture',
  )

  const officialReportPath = join(root, 'official.json')
  const officialResult = runAudit(officialRepository, officialReportPath)
  assert.equal(officialResult.status, 0)
  const officialReport = JSON.parse(readFileSync(officialReportPath, 'utf8'))
  assert.ok(
    !officialReport.findings.some(
      (finding) => finding.ruleId === 'PRIVACY-GIT-IDENTITY-REVIEW',
    ),
  )

  const lookalikeRepository = createRepository(root, 'lookalike')
  git(lookalikeRepository, 'config', 'user.name', 'GitHub Lookalike')
  git(
    lookalikeRepository,
    'config',
    'user.email',
    ['automation', 'github.com'].join('@'),
  )
  git(lookalikeRepository, 'commit', '--amend', '--no-edit', '--reset-author')

  const lookalikeReportPath = join(root, 'lookalike.json')
  const lookalikeResult = runAudit(lookalikeRepository, lookalikeReportPath)
  assert.equal(lookalikeResult.status, 1)
  const lookalikeReport = JSON.parse(readFileSync(lookalikeReportPath, 'utf8'))
  assert.ok(
    lookalikeReport.findings.some(
      (finding) => finding.ruleId === 'PRIVACY-GIT-IDENTITY-REVIEW',
    ),
  )
})

test('Web PR and publication workflows enforce complete-history confidentiality gates', () => {
  const prWorkflow = readFileSync(join(projectRoot, '.github/workflows/verify-m6-confidentiality.yml'), 'utf8')
  const securityWorkflow = readFileSync(join(projectRoot, '.github/workflows/verify-m4-security.yml'), 'utf8')
  const publishWorkflow = readFileSync(join(projectRoot, '.github/workflows/publish-m1-image.yml'), 'utf8')
  const baseline = JSON.parse(
    readFileSync(join(projectRoot, 'config/m6-known-confidentiality-findings.json'), 'utf8'),
  )

  assert.match(prWorkflow, /fetch-depth: 0/)
  assert.match(prWorkflow, /ref: \$\{\{ github\.event\.pull_request\.head\.sha \|\| github\.sha \}\}/)
  assert.match(prWorkflow, /--gate "\$gate"/)
  assert.match(prWorkflow, /if: always\(\)/)
  assert.match(securityWorkflow, /ref: \$\{\{ github\.event\.pull_request\.head\.sha \|\| github\.sha \}\}/)
  assert.match(securityWorkflow, /--gate new/)
  assert.match(publishWorkflow, /needs: confidentiality/)
  assert.match(publishWorkflow, /--gate strict/)
  assert.equal(baseline.status, 'ordinary-github-refs-clean-platform-pr-refs-pending')
  assert.equal(baseline.knownFindingIds.length, 0)
})
