#!/usr/bin/env node

import { createHash } from 'node:crypto'
import {
  lstatSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  realpathSync,
  statSync,
  writeFileSync,
} from 'node:fs'
import { basename, dirname, extname, join, relative, resolve, sep } from 'node:path'
import { spawnSync } from 'node:child_process'

const REPORT_FORMAT = 'copper-confidentiality-audit/v1'
const SCANNER_VERSION = '1.0.1'
const SAFE_TOP_LEVEL = new Set(['.github', 'config', 'docs', 'scripts', 'src', 'tests'])
const GITHUB_PLATFORM_NOREPLY_ADDRESS = ['noreply', 'github.com'].join('@')

function fail(message, exitCode = 2) {
  process.stderr.write(`M6 confidentiality audit error: ${message}\n`)
  process.exit(exitCode)
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex')
}

function isApprovedGitHubNoreplyAddress(email) {
  const normalizedEmail = email.toLowerCase()
  return (
    normalizedEmail === GITHUB_PLATFORM_NOREPLY_ADDRESS ||
    normalizedEmail.endsWith('@users.noreply.github.com')
  )
}

function parseArguments(argv) {
  const options = {
    repositories: [],
    candidateDirectories: [],
    scope: 'all',
    gate: 'strict',
  }

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]
    const nextValue = () => {
      const value = argv[index + 1]
      if (!value || value.startsWith('--')) fail(`${argument} requires a value`)
      index += 1
      return value
    }

    if (argument === '--repo') options.repositories.push(parseNamedPath(nextValue(), '--repo'))
    else if (argument === '--candidate-dir') {
      options.candidateDirectories.push(parseNamedPath(nextValue(), '--candidate-dir'))
    } else if (argument === '--config') options.configPath = resolve(nextValue())
    else if (argument === '--baseline') options.baselinePath = resolve(nextValue())
    else if (argument === '--report') options.reportPath = resolve(nextValue())
    else if (argument === '--scope') options.scope = nextValue()
    else if (argument === '--gate') options.gate = nextValue()
    else if (argument === '--help') options.help = true
    else fail(`unknown argument: ${argument}`)
  }

  if (!['all', 'current', 'history'].includes(options.scope)) {
    fail('--scope must be all, current or history')
  }
  if (!['strict', 'new'].includes(options.gate)) fail('--gate must be strict or new')
  return options
}

function parseNamedPath(value, argumentName) {
  const separatorIndex = value.indexOf('=')
  if (separatorIndex < 1 || separatorIndex === value.length - 1) {
    fail(`${argumentName} must use name=/absolute/or/relative/path`)
  }
  const name = value.slice(0, separatorIndex)
  if (!/^[a-z][a-z0-9_-]{1,63}$/i.test(name)) fail(`unsafe repository name: ${name}`)
  return { name, path: resolve(value.slice(separatorIndex + 1)) }
}

function usage() {
  return `Usage:
  node scripts/security/audit-repositories.mjs \\
    --repo copper_web=/path/to/copper-web \\
    [--repo copper_backend=/path/to/copper_backend] \\
    [--repo copper_deploy=/path/to/copper_deploy] \\
    --config config/m6-confidentiality-audit.json \\
    [--baseline config/m6-known-confidentiality-findings.json] \\
    --report /private/path/report.json \\
    [--gate strict|new] \\
    [--scope all|current|history] \\
    [--candidate-dir release_assets=/path/to/candidate]

The report contains only rule identifiers, classifications, counts, object identifiers,
and redacted path fingerprints. Matching content and original paths are never emitted.
`
}

function runGit(repositoryPath, arguments_, options = {}) {
  const result = spawnSync('git', ['-C', repositoryPath, ...arguments_], {
    encoding: options.binary ? null : 'utf8',
    input: options.input,
    maxBuffer: 512 * 1024 * 1024,
  })
  if (result.status !== 0) {
    const commandName = arguments_.slice(0, 2).join(' ')
    throw new Error(`git ${commandName} failed with exit ${result.status ?? 'unknown'}`)
  }
  return result.stdout
}

function compileRules(config) {
  const compile = (rule) => {
    if (!rule.id || !rule.category || !rule.severity || !rule.pattern) {
      fail('every rule requires id, category, severity and pattern')
    }
    return {
      ...rule,
      expression: new RegExp(rule.pattern, rule.flags ?? ''),
      ignoreExpressions: (rule.ignorePatterns ?? []).map((pattern) => new RegExp(pattern, 'i')),
    }
  }
  return {
    pathRules: config.pathRules.map(compile),
    contentRules: config.contentRules.map(compile),
  }
}

function validateConfig(config) {
  if (config.format !== 'copper-confidentiality-audit-config/v1') {
    fail('unsupported audit config format')
  }
  if (!Number.isSafeInteger(config.maxObjectBytes) || config.maxObjectBytes < 1024) {
    fail('maxObjectBytes must be an integer of at least 1024')
  }
  for (const exception of config.approvedFindings ?? []) {
    if (
      !/^[a-f0-9]{64}$/.test(exception.findingId ?? '') ||
      !['false-positive', 'authorized-retain'].includes(exception.decision) ||
      typeof exception.owner !== 'string' ||
      exception.owner.trim().length < 2 ||
      typeof exception.reason !== 'string' ||
      exception.reason.trim().length < 12 ||
      !/^\d{4}-\d{2}-\d{2}$/.test(exception.expiresOn ?? '')
    ) {
      fail('approved findings require findingId, decision, owner, reason and expiresOn')
    }
    if (exception.expiresOn < new Date().toISOString().slice(0, 10)) {
      fail(`approved finding ${exception.findingId} has expired`)
    }
  }
}

function pathEvidence(repositoryName, originalPath) {
  const normalized = originalPath.split(sep).join('/').replace(/^\.\//, '')
  const firstSegment = normalized.split('/')[0]
  const safeTopLevel = SAFE_TOP_LEVEL.has(firstSegment) ? firstSegment : '_root'
  const extension = extname(basename(normalized)).toLowerCase()
  const safeExtension = /^\.[a-z0-9]{1,10}$/.test(extension) ? extension : ''
  const pathSha256 = sha256(`${repositoryName}\0${normalized}`)
  return {
    pathHint: `${repositoryName}/${safeTopLevel}/_redacted_${pathSha256.slice(0, 12)}${safeExtension}`,
    pathSha256,
  }
}

function createCollector(config, baseline) {
  const exceptions = new Map((config.approvedFindings ?? []).map((item) => [item.findingId, item]))
  const knownFindingIds = new Set(baseline?.knownFindingIds ?? [])
  const findings = new Map()

  function add({ repository, scope, rule, path, objectId, objectKind, matchCount = 1 }) {
    const evidence = pathEvidence(repository, path)
    const findingId = sha256(
      [repository, scope, rule.id, evidence.pathSha256, objectId ?? 'working-tree'].join('\0'),
    )
    const approved = exceptions.get(findingId)
    const known = knownFindingIds.has(findingId)
    const existing = findings.get(findingId)
    if (existing) {
      existing.matchCount += matchCount
      existing.locationCount += 1
      return
    }
    findings.set(findingId, {
      findingId,
      repository,
      scope,
      category: rule.category,
      severity: rule.severity,
      ruleId: rule.id,
      requiredDisposition: rule.requiredDisposition,
      disposition: approved?.decision ?? (known ? 'existing-blocker' : 'blocking'),
      approval: approved
        ? {
            owner: approved.owner,
            reason: approved.reason,
            expiresOn: approved.expiresOn,
          }
        : null,
      objectKind,
      objectId: objectId ?? null,
      ...evidence,
      matchCount,
      locationCount: 1,
    })
  }

  return { add, findings }
}

function ruleMatches(rule, value, ignoreAgainstWholeValue = false) {
  rule.expression.lastIndex = 0
  let count = 0
  let match
  while ((match = rule.expression.exec(value)) !== null) {
    const matchedText = match[0]
    const ignoreValue = ignoreAgainstWholeValue ? value : matchedText
    if (!rule.ignoreExpressions.some((expression) => expression.test(ignoreValue))) count += 1
    if (!rule.expression.global) break
    if (match[0].length === 0) rule.expression.lastIndex += 1
  }
  return count
}

function scanContent({ repository, scope, path, objectId, objectKind, content, rules, collector }) {
  const normalizedPath = path.split(sep).join('/')
  for (const rule of rules.pathRules) {
    if (ruleMatches(rule, normalizedPath, true) > 0) {
      collector.add({ repository, scope, rule, path: normalizedPath, objectId, objectKind })
    }
  }

  const text = content.toString('utf8')
  for (const rule of rules.contentRules) {
    const matchCount = ruleMatches(rule, text)
    if (matchCount > 0) {
      collector.add({
        repository,
        scope,
        rule,
        path: normalizedPath,
        objectId,
        objectKind,
        matchCount,
      })
    }
  }
}

function walkFiles(rootPath, excludedDirectories) {
  const root = realpathSync(rootPath)
  const files = []
  const walk = (directory) => {
    const entries = readdirSync(directory, { withFileTypes: true })
    entries.sort((left, right) => left.name.localeCompare(right.name))
    for (const entry of entries) {
      const fullPath = join(directory, entry.name)
      if (entry.isSymbolicLink()) continue
      if (entry.isDirectory()) {
        if (!excludedDirectories.has(entry.name)) walk(fullPath)
      } else if (entry.isFile()) files.push(fullPath)
    }
  }
  walk(root)
  return { root, files }
}

function incompleteRule(reason) {
  return {
    id: `AUDIT-INCOMPLETE-${reason}`,
    category: 'audit_incomplete',
    severity: 'critical',
    requiredDisposition: 'complete-auditable-scan-before-release',
  }
}

function scanDirectory({
  repository,
  rootPath,
  scope,
  config,
  rules,
  collector,
  releaseCandidate = false,
}) {
  const excluded = new Set(releaseCandidate ? ['.git'] : config.currentTreeExcludedDirectories)
  const { root, files } = walkFiles(rootPath, excluded)
  const stats = { filesDiscovered: files.length, filesScanned: 0, bytesScanned: 0, largeFiles: 0 }
  for (const filePath of files) {
    const relativePath = relative(root, filePath)
    const fileStat = statSync(filePath)
    if (fileStat.size > config.maxObjectBytes) {
      stats.largeFiles += 1
      collector.add({
        repository,
        scope,
        rule: incompleteRule('LARGE-FILE'),
        path: relativePath,
        objectId: sha256(`${repository}\0${scope}\0${relativePath}\0${fileStat.size}`),
        objectKind: 'file',
      })
      continue
    }
    const content = readFileSync(filePath)
    stats.filesScanned += 1
    stats.bytesScanned += content.length
    scanContent({
      repository,
      scope,
      path: relativePath,
      objectId: sha256(content),
      objectKind: 'file',
      content,
      rules,
      collector,
    })
  }
  return stats
}

function parseObjectListing(output) {
  const objects = new Map()
  for (const line of output.split('\n')) {
    if (!line) continue
    const separatorIndex = line.indexOf(' ')
    const objectId = separatorIndex === -1 ? line : line.slice(0, separatorIndex)
    const path = separatorIndex === -1 ? null : line.slice(separatorIndex + 1)
    if (!/^[a-f0-9]{40,64}$/.test(objectId)) throw new Error('unexpected object identifier')
    const existing = objects.get(objectId)
    if (!existing) objects.set(objectId, { objectId, paths: path ? [path] : [] })
    else if (path && !existing.paths.includes(path)) existing.paths.push(path)
  }
  return objects
}

function loadGitObjects(repositoryPath, objectRecords, maxObjectBytes) {
  const objectIds = [...objectRecords.keys()]
  if (objectIds.length === 0) return []
  const checkOutput = runGit(
    repositoryPath,
    ['cat-file', '--batch-check=%(objectname) %(objecttype) %(objectsize)'],
    { input: `${objectIds.join('\n')}\n` },
  )
  const metadata = checkOutput
    .trim()
    .split('\n')
    .map((line) => {
      const [objectId, type, sizeText] = line.split(' ')
      return { objectId, type, size: Number(sizeText) }
    })
    .filter((item) => ['blob', 'commit', 'tag'].includes(item.type))

  const readable = metadata.filter((item) => item.size <= maxObjectBytes)
  if (readable.length === 0) return metadata.map((item) => ({ ...item, content: null }))
  const batch = runGit(repositoryPath, ['cat-file', '--batch'], {
    binary: true,
    input: Buffer.from(`${readable.map((item) => item.objectId).join('\n')}\n`),
  })
  let offset = 0
  const contents = new Map()
  for (const expected of readable) {
    const newline = batch.indexOf(0x0a, offset)
    if (newline < 0) throw new Error('truncated git cat-file header')
    const [objectId, type, sizeText] = batch.subarray(offset, newline).toString('utf8').split(' ')
    const size = Number(sizeText)
    if (objectId !== expected.objectId || type !== expected.type || size !== expected.size) {
      throw new Error('git cat-file object order or metadata mismatch')
    }
    const contentStart = newline + 1
    const contentEnd = contentStart + size
    if (contentEnd >= batch.length + 1) throw new Error('truncated git cat-file content')
    contents.set(objectId, batch.subarray(contentStart, contentEnd))
    offset = contentEnd + 1
  }
  return metadata.map((item) => ({ ...item, content: contents.get(item.objectId) ?? null }))
}

function scanGitHistory({ repository, repositoryPath, config, rules, collector }) {
  const shallow = runGit(repositoryPath, ['rev-parse', '--is-shallow-repository']).trim() === 'true'
  const headCommit = runGit(repositoryPath, ['rev-parse', 'HEAD']).trim()
  const refs = runGit(repositoryPath, ['for-each-ref', '--format=%(refname) %(objectname)']).trim()
  const stats = {
    headCommit,
    shallow,
    refsSha256: sha256(refs),
    reachableObjects: 0,
    scannedObjects: 0,
    bytesScanned: 0,
    largeObjects: 0,
  }
  if (shallow) {
    collector.add({
      repository,
      scope: 'history',
      rule: incompleteRule('SHALLOW-CLONE'),
      path: 'git/history',
      objectId: headCommit,
      objectKind: 'repository',
    })
    return stats
  }

  const listing = parseObjectListing(runGit(repositoryPath, ['rev-list', '--objects', '--all']))
  stats.reachableObjects = listing.size
  const objects = loadGitObjects(repositoryPath, listing, config.maxObjectBytes)
  const gitIdentities = new Set()
  for (const object of objects) {
    const paths = listing.get(object.objectId)?.paths ?? []
    const evidencePaths = paths.length > 0 ? paths : [`git/${object.type}/${object.objectId}`]
    if (object.content === null) {
      stats.largeObjects += 1
      for (const path of evidencePaths) {
        collector.add({
          repository,
          scope: 'history',
          rule: incompleteRule('LARGE-OBJECT'),
          path,
          objectId: object.objectId,
          objectKind: object.type,
        })
      }
      continue
    }
    stats.scannedObjects += 1
    stats.bytesScanned += object.content.length
    let contentToScan = object.content
    if (object.type === 'commit' || object.type === 'tag') {
      const objectText = object.content.toString('utf8')
      const headerEnd = objectText.indexOf('\n\n')
      const headers = headerEnd === -1 ? objectText : objectText.slice(0, headerEnd)
      for (const line of headers.split('\n')) {
        if (!/^(?:author|committer|tagger) /.test(line)) continue
        const email = line.match(/<([^>]+)>/)?.[1]?.trim().toLowerCase()
        if (email && !isApprovedGitHubNoreplyAddress(email)) gitIdentities.add(sha256(email))
      }
      contentToScan = Buffer.from(headerEnd === -1 ? '' : objectText.slice(headerEnd + 2))
    }
    for (const path of evidencePaths) {
      scanContent({
        repository,
        scope: 'history',
        path,
        objectId: object.objectId,
        objectKind: object.type,
        content: contentToScan,
        rules,
        collector,
      })
    }
  }
  if (gitIdentities.size > 0) {
    collector.add({
      repository,
      scope: 'history',
      rule: {
        id: 'PRIVACY-GIT-IDENTITY-REVIEW',
        category: 'personal_information',
        severity: 'medium',
        requiredDisposition: 'document-author-consent-or-use-approved-noreply-address',
      },
      path: 'git/identities',
      objectId: sha256([...gitIdentities].sort().join('\0')),
      objectKind: 'identity-set',
      matchCount: gitIdentities.size,
    })
  }
  return stats
}

function summarize(findings, gate) {
  const summary = {
    total: findings.length,
    blocking: findings.filter((item) =>
      gate === 'strict'
        ? ['blocking', 'existing-blocker'].includes(item.disposition)
        : item.disposition === 'blocking',
    ).length,
    newBlocking: findings.filter((item) => item.disposition === 'blocking').length,
    knownBlockers: findings.filter((item) => item.disposition === 'existing-blocker').length,
    approved: findings.filter((item) =>
      ['false-positive', 'authorized-retain'].includes(item.disposition),
    ).length,
    byCategory: {},
    byRepository: {},
    byScope: {},
  }
  for (const finding of findings) {
    summary.byCategory[finding.category] = (summary.byCategory[finding.category] ?? 0) + 1
    summary.byRepository[finding.repository] = (summary.byRepository[finding.repository] ?? 0) + 1
    summary.byScope[finding.scope] = (summary.byScope[finding.scope] ?? 0) + 1
  }
  return summary
}

function main() {
  const options = parseArguments(process.argv.slice(2))
  if (options.help) {
    process.stdout.write(usage())
    return
  }
  if (!options.configPath || !options.reportPath) fail('--config and --report are required')
  if (options.repositories.length === 0 && options.candidateDirectories.length === 0) {
    fail('at least one --repo or --candidate-dir is required')
  }

  const config = JSON.parse(readFileSync(options.configPath, 'utf8'))
  validateConfig(config)
  let baseline = null
  if (options.baselinePath) {
    baseline = JSON.parse(readFileSync(options.baselinePath, 'utf8'))
    if (
      baseline.format !== 'copper-confidentiality-baseline/v1' ||
      !Array.isArray(baseline.knownFindingIds) ||
      baseline.knownFindingIds.some((item) => !/^[a-f0-9]{64}$/.test(item))
    ) {
      fail('invalid confidentiality baseline')
    }
  }
  if (options.gate === 'new' && !baseline) fail('--gate new requires --baseline')
  const rules = compileRules(config)
  const collector = createCollector(config, baseline)
  const repositoryReports = []

  for (const repository of options.repositories) {
    if (!lstatSync(repository.path).isDirectory()) fail(`${repository.name} is not a directory`)
    runGit(repository.path, ['rev-parse', '--git-dir'])
    const report = { name: repository.name }
    if (options.scope !== 'history') {
      report.current = scanDirectory({
        repository: repository.name,
        rootPath: repository.path,
        scope: 'current',
        config,
        rules,
        collector,
      })
    }
    if (options.scope !== 'current') {
      report.history = scanGitHistory({
        repository: repository.name,
        repositoryPath: repository.path,
        config,
        rules,
        collector,
      })
    }
    repositoryReports.push(report)
  }

  const candidateReports = []
  for (const candidate of options.candidateDirectories) {
    candidateReports.push({
      name: candidate.name,
      current: scanDirectory({
        repository: `release_${candidate.name}`,
        rootPath: candidate.path,
        scope: 'release-candidate',
        config,
        rules,
        collector,
        releaseCandidate: true,
      }),
    })
  }

  const findings = [...collector.findings.values()].sort((left, right) =>
    [left.repository, left.scope, left.ruleId, left.findingId]
      .join('\0')
      .localeCompare([right.repository, right.scope, right.ruleId, right.findingId].join('\0')),
  )
  const summary = summarize(findings, options.gate)
  const report = {
    format: REPORT_FORMAT,
    scannerVersion: SCANNER_VERSION,
    generatedAt: new Date().toISOString(),
    decision: summary.blocking === 0 ? 'PASS' : 'BLOCK',
    policy: {
      failClosed: true,
      gateMode: options.gate,
      matchingContentStored: false,
      originalPathsStored: false,
      historyScope: 'all-reachable-refs-in-non-shallow-clone',
      releaseScope: 'explicit-candidate-directories-before-publication',
    },
    repositories: repositoryReports,
    releaseCandidates: candidateReports,
    summary,
    findings,
  }
  mkdirSync(dirname(options.reportPath), { recursive: true, mode: 0o700 })
  writeFileSync(options.reportPath, `${JSON.stringify(report, null, 2)}\n`, { mode: 0o600 })

  process.stdout.write(
    `M6 confidentiality audit ${report.decision}: ${summary.blocking} blocking, ` +
      `${summary.approved} approved, ${summary.total} total findings.\n` +
      `Redacted report: ${options.reportPath}\n`,
  )
  if (summary.blocking > 0) process.exitCode = 1
}

try {
  main()
} catch (error) {
  fail(error instanceof Error ? error.message : String(error))
}
