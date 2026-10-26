import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const dockerfile = readFileSync(new URL('../Dockerfile', import.meta.url), 'utf8')
const caddyfile = readFileSync(new URL('../Caddyfile', import.meta.url), 'utf8')
const dockerignore = readFileSync(new URL('../.dockerignore', import.meta.url), 'utf8')
const packageJson = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'))
const metadataGenerator = readFileSync(new URL('../scripts/generate-build-metadata.mjs', import.meta.url), 'utf8')
const publishWorkflow = readFileSync(new URL('../.github/workflows/publish-m1-image.yml', import.meta.url), 'utf8')
const transferWorkflow = readFileSync(new URL('../.github/workflows/transfer-m7-internal-image.yml', import.meta.url), 'utf8')

test('production image locks build and runtime images by digest', () => {
  assert.match(dockerfile, /^# syntax=docker\/dockerfile:1\.7@sha256:[0-9a-f]{64}$/m)
  assert.match(dockerfile, /FROM node:22\.22\.3-alpine3\.22@sha256:[0-9a-f]{64} AS build/)
  assert.match(dockerfile, /FROM caddy:2\.11\.7-alpine@sha256:[0-9a-f]{64}/)
  assert.match(dockerfile, /FROM golang:1\.27\.3-alpine3\.23@sha256:[0-9a-f]{64} AS caddy-build/)
  assert.match(metadataGenerator, /server: 'Caddy 2\.11\.7\+copper\.security\.1 \(Copper security rebuild\)'/)
  assert.match(dockerfile, /corepack prepare pnpm@9\.15\.7 --activate/)
  assert.match(dockerfile, /pnpm install --frozen-lockfile/)
})

test('runtime contains static output and traceable metadata but no build workspace', () => {
  const runtimeStage = dockerfile.split(/FROM caddy:/)[1]
  assert.ok(runtimeStage)
  assert.match(runtimeStage, /COPY --from=build .*\/app\/dist \/srv/)
  assert.match(runtimeStage, /COPY --from=build .*\/app\/build-metadata \/usr\/share\/copper/)
  assert.doesNotMatch(runtimeStage, /vite|node_modules|\/app\/src/)
  assert.match(runtimeStage, /adduser .*COPPER_UID.* copper/)
  assert.match(runtimeStage, /COPY --from=caddy-build \/out\/caddy \/usr\/bin\/caddy/)
  assert.match(runtimeStage, /libcrypto3=3\.5\.9-r0 libssl3=3\.5\.9-r0 zlib=1\.3\.2-r1/)
  assert.match(runtimeStage, /ca-certificates=20261020-r0 ca-certificates-bundle=20261020-r0/)
  assert.match(runtimeStage, /apk del --no-network curl/)
  assert.match(runtimeStage, /USER copper:copper/)
  assert.match(dockerfile, /org\.opencontainers\.image\.version=\$COPPER_VERSION/)
  assert.match(dockerfile, /org\.opencontainers\.image\.revision=\$COPPER_REVISION/)
  assert.match(dockerfile, /org\.opencontainers\.image\.created=\$COPPER_BUILD_TIME/)
})

test('gateway preserves the backend api context and same-origin cookies', () => {
  assert.match(caddyfile, /@api path \/api \/api\/\*/)
  assert.match(caddyfile, /handle @api \{\s*reverse_proxy \{\$COPPER_BACKEND_URL:/s)
  assert.doesNotMatch(caddyfile, /handle_path\s+@api|uri\s+(strip_prefix|replace).*\/api/)
  assert.doesNotMatch(caddyfile, /Access-Control-Allow-Origin|header_(?:up|down)\s+Set-Cookie/)
})

test('gateway covers history routing, cache, uploads and health', () => {
  assert.match(caddyfile, /try_files \{path\} \/index\.html/)
  assert.match(caddyfile, /handle \/assets\/\*/)
  assert.match(caddyfile, /max-age=31536000, immutable/)
  assert.match(caddyfile, /max_size 12MB/)
  assert.match(caddyfile, /handle \/healthz/)
  assert.match(caddyfile, /handle \/readyz/)
  assert.match(dockerfile, /HEALTHCHECK/)
})

test('docker context excludes local output, source-only documents and secrets', () => {
  for (const expected of ['.git', 'node_modules', 'dist', 'docs', 'tests', '.env', '.env.*']) {
    assert.match(dockerignore, new RegExp(`^${expected.replace('.', '\\.')}$`, 'm'))
  }
  assert.equal(packageJson.packageManager.split('+')[0], 'pnpm@9.15.7')
})

test('release workflow publishes both native platforms and source-bound offline archives', () => {
  assert.match(publishWorkflow, /platform: linux\/amd64\s+arch: amd64\s+runner: ubuntu-24\.04/)
  assert.match(publishWorkflow, /platform: linux\/arm64\s+arch: arm64\s+runner: ubuntu-24\.04-arm/)
  assert.match(publishWorkflow, /docker pull --platform "\$PLATFORM" "\$exact"/)
  assert.match(publishWorkflow, /docker image inspect --format '\{\{\.Architecture\}\}' "\$delivery_tag"/)
  assert.doesNotMatch(publishWorkflow, /docker image inspect --platform/)
  assert.match(publishWorkflow, /docker save "\$delivery_tag" \| gzip -n/)
  assert.match(publishWorkflow, /copper-image-platform\/v1/)
  assert.match(publishWorkflow, /copper-image-bom\/v1/)
  assert.match(publishWorkflow, /docker buildx imagetools create/)
  assert.match(publishWorkflow, /release_version="0\.0\.0-m5\.\$\{GITHUB_RUN_ID\}\.\$\{GITHUB_RUN_ATTEMPT\}"/)
  assert.match(publishWorkflow, /Release version must be semantic/)
  assert.doesNotMatch(publishWorkflow, /release_version="source-\$GITHUB_SHA"/)
  assert.match(publishWorkflow, /needs: build-platform/)
  assert.match(publishWorkflow, /sha256sum copper-web-amd64\.tar\.gz copper-web-arm64\.tar\.gz/)
  assert.match(publishWorkflow, /actions\/upload-artifact@b7c566a772e6b6bfb58ed0dc250532a479d7789f/)
  assert.match(publishWorkflow, /actions\/download-artifact@37930b1c2abaa49bbe596cd826c3c89aef350131/)
  assert.doesNotMatch(publishWorkflow, /(?:^|[\s"':])latest(?:$|[\s"'])/m)
})

test('artifact quota bypass is explicit, manual and internal-rehearsal only', () => {
  assert.match(publishWorkflow, /internal_rehearsal_without_artifacts:\s+description: INTERNAL_REHEARSAL_ONLY/)
  assert.match(publishWorkflow, /internal_rehearsal_without_artifacts:[\s\S]*?default: false[\s\S]*?type: boolean/)
  assert.match(publishWorkflow, /github\.event_name == 'workflow_dispatch' && inputs\.internal_rehearsal_without_artifacts/)
  assert.doesNotMatch(publishWorkflow, /continue-on-error:\s*true/)
})

test('M7 image transfer is manual, private, draft-only and isolated from publication', () => {
  assert.match(transferWorkflow, /^\s*workflow_dispatch:/m)
  assert.doesNotMatch(transferWorkflow, /^\s*(?:push|pull_request):/m)
  assert.match(transferWorkflow, /description: INTERNAL_REHEARSAL_ONLY/)
  assert.match(transferWorkflow, /\^m7-internal-transfer-\[0-9A-Za-z\._-\]/)
  assert.match(transferWorkflow, /Internal transfer is forbidden for a public repository/)
  assert.match(transferWorkflow, /Transfer target must be an existing private draft release/)
  assert.match(transferWorkflow, /contents: write/)
  assert.match(transferWorkflow, /transfer_draft_id:/)
  assert.match(transferWorkflow, /releases\/\$TRANSFER_DRAFT_ID/)
  assert.match(transferWorkflow, /https:\/\/uploads\.github\.com\/repos\/\$GITHUB_REPOSITORY/)
  assert.match(transferWorkflow, /--retry 3/)
  assert.doesNotMatch(transferWorkflow, /Authorization: Bearer/)
  assert.doesNotMatch(transferWorkflow, /gh release upload "\$TRANSFER_TAG"/)
  assert.doesNotMatch(transferWorkflow, /actions\/upload-artifact/)
  assert.doesNotMatch(publishWorkflow, /internal_rehearsal_transfer_draft_tag/)
})

test('gateway security rebuild uses verified locked modules and keeps upstream identity', () => {
  const caddyModule = readFileSync(new URL('../docker/caddy/go.mod', import.meta.url), 'utf8')
  const caddyChecksums = readFileSync(new URL('../docker/caddy/go.sum', import.meta.url), 'utf8')
  const caddyMain = readFileSync(new URL('../docker/caddy/main.go', import.meta.url), 'utf8')
  assert.match(caddyModule, /^go 1\.27\.3$/m)
  for (const [name, version] of [
    ['github.com/caddyserver/caddy/v2', 'v2.11.7'],
    ['golang.org/x/net', 'v0.60.0'],
    ['github.com/klauspost/compress', 'v1.20.1'],
  ]) {
    assert.ok(caddyModule.includes(`${name} ${version}`))
    assert.ok(caddyChecksums.includes(`${name} ${version} h1:`))
  }
  assert.doesNotMatch(caddyModule, /^replace\s/m)
  assert.match(caddyMain, /"time\/tzdata"/)
  assert.match(caddyMain, /github\.com\/caddyserver\/caddy\/v2\/modules\/standard/)
  assert.match(dockerfile, /go mod verify/)
  assert.match(dockerfile, /CGO_ENABLED=0 go build -mod=readonly -trimpath -buildvcs=false/)
  assert.match(dockerfile, /CustomVersion=v2\.11\.7\+copper\.security\.1/)
  assert.match(dockerfile, /ENV CADDY_VERSION=v2\.11\.7\+copper\.security\.1/)
})
