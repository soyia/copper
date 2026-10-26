import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
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

function createRepository(root, name) {
  const repository = join(root, name)
  mkdirSync(repository, { recursive: true })
  git(repository, 'init', '-b', 'main')
  git(repository, 'config', 'user.name', 'Fixture User')
  git(
    repository,
    'config',
    'user.email',
    ['12345+fixture', 'users.noreply.github.com'].join('@'),
  )
  writeFileSync(join(repository, 'README.md'), 'synthetic audit fixture\n')
  git(repository, 'add', 'README.md')
  git(repository, 'commit', '-m', 'initial clean fixture')
  return repository
}

function commitWithCommitter(repository, email, fileName) {
  writeFileSync(join(repository, fileName), `${fileName}\n`)
  git(repository, 'add', fileName)
  execFileSync('git', ['-C', repository, 'commit', '-m', fileName], {
    env: {
      ...process.env,
      GIT_COMMITTER_NAME: 'GitHub',
      GIT_COMMITTER_EMAIL: email,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
}

function runAudit(repository, report) {
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
      '--gate',
      'strict',
    ],
    { encoding: 'utf8' },
  )
}

test('accepts only the exact GitHub platform noreply committer address', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'copper-backend-github-identity-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))

  const officialRepository = createRepository(root, 'official')
  commitWithCommitter(
    officialRepository,
    ['noreply', 'github.com'].join('@'),
    'platform-merge.txt',
  )
  const officialReportPath = join(root, 'official.json')
  const officialResult = runAudit(officialRepository, officialReportPath)
  assert.equal(officialResult.status, 0, officialResult.stderr)
  const officialReport = JSON.parse(readFileSync(officialReportPath, 'utf8'))
  assert.ok(
    !officialReport.findings.some(
      (finding) => finding.ruleId === 'PRIVACY-GIT-IDENTITY-REVIEW',
    ),
  )

  const lookalikeRepository = createRepository(root, 'lookalike')
  commitWithCommitter(
    lookalikeRepository,
    ['automation', 'github.com'].join('@'),
    'lookalike-merge.txt',
  )
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
