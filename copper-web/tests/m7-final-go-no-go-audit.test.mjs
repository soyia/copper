import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import test from 'node:test'

const root = process.cwd()
const report = readFileSync(join(root, 'docs/self-hosting/M7_07_FINAL_GO_NO_GO_AUDIT.md'), 'utf8')
const roadmap = readFileSync(join(root, 'docs/SELF_HOSTING_AND_DATA_CONTROL_ROADMAP.md'), 'utf8')
const tasks = readFileSync(join(root, 'docs/GITHUB_ROADMAP_TASKS.md'), 'utf8')
const snapshot = JSON.parse(readFileSync(join(root, 'docs/self-hosting/evidence/M7_07_FINAL_AUDIT_SNAPSHOT.json'), 'utf8'))

test('M7-07 freezes exact three-repository heads and fails closed before v1.0.0', () => {
  assert.equal(snapshot.formatVersion, 'copper-m7-final-audit/v1')
  assert.equal(snapshot.scope, 'INTERNAL_REHEARSAL_ONLY')
  assert.equal(snapshot.decision.internalAuditStatus, 'COMPLETE')
  assert.equal(snapshot.decision.formalReleaseStatus, 'FORMAL_RELEASE_BLOCKED')
  assert.equal(snapshot.decision.recommendation, 'NO_GO')
  assert.equal(snapshot.decision.productOwnerDecision, 'NO_GO')
  assert.equal(snapshot.decision.decidedBy, 'Tian')
  assert.equal(snapshot.decision.decidedAt, '2026-07-18T21:14:01+08:00')
  assert.equal(snapshot.decision.decisionRecord, 'soyia/copper_web#76')
  assert.equal(snapshot.decision.v1TagAllowed, false)
  assert.equal(snapshot.repositories.copper_web, '1759b7bc08757777f5cc3c8c4dad94b7687e16cc')
  assert.equal(snapshot.repositories.copper_backend, 'f721efab70e31fd1e3448f508b9733e8112ea003')
  assert.equal(snapshot.repositories.copper_deploy, '7dc281a83b0bf2ea78dca576f56e65d85d699c5d')
  assert.match(report, /建议决定：`NO_GO`/)
  assert.match(report, /产品负责人书面决定：`NO_GO`/)
  assert.match(report, /禁止创建 `v1\.0\.0`/)
})

test('M7-07 records current-tree pass separately from blocking history and platform refs', () => {
  assert.equal(snapshot.strictConfidentialityAudit.currentTree.result, 'PASS')
  assert.equal(snapshot.strictConfidentialityAudit.currentTree.blocking, 0)
  assert.equal(snapshot.strictConfidentialityAudit.ordinaryHistory.result, 'BLOCK')
  assert.equal(snapshot.strictConfidentialityAudit.ordinaryHistory.blocking, 41)
  assert.deepEqual(snapshot.strictConfidentialityAudit.ordinaryHistory.byRepository, {
    copper_backend: 16,
    copper_deploy: 7,
    copper_web: 18,
  })
  assert.equal(
    snapshot.strictConfidentialityAudit.platformPullRequestRefs,
    'REENUMERATION_REQUIRED_AFTER_GITHUB_SUPPORT_DISPOSITION',
  )
  assert.match(report, /此前“115 个 PR refs \/ 48 项”\s*只是旧快照/)
})

test('M7-07 rejects relabeling the internal rc.4 combination as a stable release', () => {
  assert.equal(snapshot.releaseState.currentInternalCandidate, '0.4.0-rc.4')
  assert.equal(snapshot.releaseState.manifestValidation, 'FAIL')
  assert.equal(snapshot.releaseState.manifestMissingFields.length, 8)
  assert.equal(snapshot.releaseState.sourceAlignment.backend, true)
  assert.equal(snapshot.releaseState.sourceAlignment.web, false)
  assert.equal(snapshot.releaseState.sourceAlignment.deploy, false)
  assert.match(report, /不能改名或复用为 `1\.0\.0`/)
})

test('roadmap and task tracker record owner NO-GO without clearing formal blockers', () => {
  assert.match(roadmap, /产品负责人 Tian 于 2026-07-18 签署 `NO_GO`/)
  assert.match(roadmap, /关闭 Issue #76 只关闭本轮决定/)
  assert.match(tasks, /产品负责人 Tian 已于 2026-07-18 签署 `NO_GO`/)
  assert.match(tasks, /全部正式阻断继续分别跟踪/)
})
