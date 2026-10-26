import { execFileSync } from 'node:child_process'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

const required = ['COPPER_VERSION', 'COPPER_REVISION', 'COPPER_BUILD_TIME']
for (const name of required) {
  if (!process.env[name]?.trim()) {
    throw new Error(`缺少生产构建参数 ${name}`)
  }
}

if (!/^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/.test(process.env.COPPER_VERSION)) {
  throw new Error('COPPER_VERSION 必须是语义化版本号')
}
if (!/^[0-9a-f]{40}$/i.test(process.env.COPPER_REVISION)) {
  throw new Error('COPPER_REVISION 必须是完整的 40 位 Git 提交 SHA')
}
if (Number.isNaN(Date.parse(process.env.COPPER_BUILD_TIME))) {
  throw new Error('COPPER_BUILD_TIME 必须是有效的 ISO-8601 时间')
}

const packageJson = JSON.parse(readFileSync(resolve('package.json'), 'utf8'))
const dependencyTree = JSON.parse(execFileSync(
  'pnpm',
  ['list', '--prod', '--depth', 'Infinity', '--json'],
  { encoding: 'utf8' },
))[0]

const dependencies = new Map()
collectDependencies(dependencyTree.dependencies ?? {}, dependencies)

const outputDirectory = resolve('build-metadata')
mkdirSync(outputDirectory, { recursive: true })
writeJson('build-info.json', {
  product: 'Copper Web',
  version: process.env.COPPER_VERSION,
  revision: process.env.COPPER_REVISION.toLowerCase(),
  builtAt: new Date(process.env.COPPER_BUILD_TIME).toISOString(),
  packageVersion: packageJson.version,
  buildRuntime: {
    node: process.version,
    pnpm: execFileSync('pnpm', ['--version'], { encoding: 'utf8' }).trim(),
  },
  runtime: {
    server: 'Caddy 2.11.7+copper.security.1 (Copper security rebuild)',
    go: 'go1.27.3',
  },
})
writeJson('production-dependencies.json', {
  generatedFor: process.env.COPPER_REVISION.toLowerCase(),
  dependencies: [...dependencies.entries()]
    .map(([name, version]) => ({ name, version }))
    .sort((left, right) => left.name.localeCompare(right.name)),
})

function collectDependencies(tree, result) {
  for (const [name, value] of Object.entries(tree)) {
    if (value?.version) result.set(name, String(value.version))
    collectDependencies(value?.dependencies ?? {}, result)
  }
}

function writeJson(fileName, value) {
  writeFileSync(
    resolve(outputDirectory, fileName),
    `${JSON.stringify(value, null, 2)}\n`,
    'utf8',
  )
}
