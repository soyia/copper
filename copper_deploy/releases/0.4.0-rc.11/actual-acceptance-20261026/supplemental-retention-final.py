#!/usr/bin/env python3
"""One-run RC11 supplemental final audit after observed normal chain retention."""
import argparse, fcntl, hashlib, importlib.util, json, os, re, runpy, sys
from datetime import datetime, timezone
from pathlib import Path

DEFAULT_DRIVER=Path('/home/ubuntu/copper-acceptance-20261025/rc11-acceptance-driver')
PRUNED='PRUNED_BY_POLICY_ORIGINAL_BYTES_NOT_RETAINED'
OBS_SOURCE='actual exec_command session 78186 stdout during new-full phase'
sys.dont_write_bytecode=True
def require(value,message):
 if not value:raise RuntimeError(message)
def load(path):return json.loads(Path(path).read_text())
def digest(path):
 h=hashlib.sha256()
 with Path(path).open('rb') as stream:
  for chunk in iter(lambda:stream.read(1024*1024),b''):h.update(chunk)
 return h.hexdigest()
def metadata_time(status,site):
 match=re.fullmatch(re.escape(site)+r'_(\d{8}T\d{6}Z)_([0-9a-f]{32})_(manual|scheduled)\.copperbak',status['archive'])
 require(match and match[2]==status['backupId'],'Backup status filename/ID mismatch')
 stamp=datetime.strptime(match[1],'%Y%m%dT%H%M%SZ').replace(tzinfo=timezone.utc)
 return {'filenameTimestampUTC':stamp.isoformat(),'day':stamp.date().isoformat(),'isoWeek':[stamp.isocalendar().year,stamp.isocalendar().week],'month':[stamp.year,stamp.month]}
def validate_ledger(ledger,phases,env_sha,runtime_hashes):
 require(ledger.get('envSha256')==env_sha and ledger.get('runtimeFileHashes')==runtime_hashes,'Original env/runtime binding differs')
 require(set(ledger.get('phases',{}))==set(phases[:-1]),'Exactly 15 original phases required; original final must not have been attempted')
 require(all(ledger['phases'][name].get('status')=='PASS' for name in phases[:-1]),'An original phase is not PASS')
def validate_retention_observation(observation,full,inc,new,site,policy):
 require(policy=={'daily':7,'weekly':4,'monthly':6},'This supplement only covers the actual ordinary 7/4/6 policy')
 require(full.get('formatVersion')=='copper-backup/v1' and new.get('formatVersion')=='copper-backup/v1' and inc.get('formatVersion')=='copper-backup/v2','Wrong original backup formats')
 require(len({full['backupId'],inc['backupId'],new['backupId']})==3,'Backup identities are not distinct')
 require(inc.get('baseBackupId')==full['backupId'] and inc.get('parentBackupId')==full['backupId'] and inc.get('chainSequence')==1,'Original full/incremental checkpoint chain differs')
 require(observation.get('format')=='copper-internal-retention-operator-observation/v1' and observation.get('observationSource')==OBS_SOURCE,'Expected explicit actual-session operator observation')
 require(observation.get('productStructuredDeletionReceiptClaimed') is False,'Operator observation must not be relabeled as a product receipt')
 require(observation.get('newFullBackupId')==new['backupId'] and observation.get('newFullPhaseExitedSuccessfully') is True,'Observed new-full phase/ID mismatch')
 require(observation.get('observedLines')==['Copper：已按保留策略删除旧备份：'+s['archive'] for s in [full,inc]],'Observed deletion lines must exactly match both original checkpoints')
 require(observation.get('oldIncrementalOriginalBytes')==PRUNED and observation.get('financialExpectationsChanged') is False and observation.get('frozenDriverModified') is False,'Observation availability or integrity statement differs')
 copy=observation.get('firstFullIndependentCopy',{})
 require(copy.get('location')=='MAC_SYNTHETIC_VERIFICATION_COPY' and copy.get('archiveBasename')==full['archive'] and copy.get('backupId')==full['backupId'] and copy.get('sha256')==full['sha256'] and copy.get('sidecarVerifiedAgain') is True,'Independent Mac first-full original SHA proof mismatch')
 periods={key:metadata_time(s,site) for key,s in [('full',full),('incremental',inc),('new-full',new)]}
 require(periods['new-full']['filenameTimestampUTC']>periods['full']['filenameTimestampUTC'],'New full must be later')
 require(all(periods['new-full'][k]==periods['full'][k] for k in ['day','isoWeek','month']),'Old/new full are not in the same actual retention periods')
 return periods
def comparisons(evidence,number,expected_id,expected_sha):
 root=evidence/('stage'+str(number));state=load(root/'status.json')
 require(state.get('status')=='VALIDATED' and state.get('backupId')==expected_id,'Wrong historical validated stage identity')
 require(state.get('archiveSha256')==expected_sha,'Historical stage archive SHA differs')
 if number==2:require(state.get('archiveSha256')==expected_sha and int(state.get('incrementalCount',0))==0,'Second stage must bind only the new full')
 for name in ['imported-result.json','staged-result.json']:
  doc=load(root/name)
  require(doc.get('result')=='passed' and not doc.get('mismatches') and not doc.get('foreignKeyViolations') and doc.get('foreignKeyConstraintCount')==92,'Formal stage comparison did not pass with 92 FK')
 for name in ['schema.json','record-counts.json','business-totals.json','attachments.json']:
  expected=load(root/'expected'/name)
  require(expected==load(root/'imported'/name)==load(root/'staged'/name),'Stored stage reports disagree: '+name)
 require(str(load(root/'staged/schema.json').get('schemaVersion'))=='68','Stage schema differs')
 return {'statusFileSha256':digest(root/'status.json'),'importedComparisonSha256':digest(root/'imported-result.json'),'stagedComparisonSha256':digest(root/'staged-result.json')}
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--driver-dir',type=Path,default=DEFAULT_DRIVER);p.add_argument('--observation',type=Path,required=True);p.add_argument('--new-receipt',type=Path,required=True);p.add_argument('--output',type=Path)
 a=p.parse_args();require(os.geteuid()==0,'Run on the owned acceptance server as root')
 require(not any(k.startswith(('COPPER_TEST_','COPPER_RESTORE_TEST_','COPPER_BACKUP_TEST_')) for k in os.environ),'No test bypass flags allowed')
 driver=a.driver_dir.resolve();runpy.run_path(str(driver/'verify-package.py'))['verify']()
 spec=importlib.util.spec_from_file_location('frozen_rc11_driver',driver/'phase-runner.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
 c=m.config();require(c['COPPER_SITE_ID']=='copper-acceptance-rc11','Wrong site')
 evidence=m.EVIDENCE;output=a.output or evidence/'supplemental-retention-final.json'
 require(not output.exists() and not output.is_symlink(),'Fresh supplemental output required')
 require(not (evidence/'api-final.json').exists() and not (evidence/'db-final').exists(),'Original final evidence already exists; reconcile it before this one-run audit')
 with (m.RUNTIME/'.phase-runner.lock').open('r+') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
  ledger_sha=digest(m.LEDGER);ledger=m.read_private(m.LEDGER)
  validate_ledger(ledger,m.PHASES,digest(m.ENV),m.TARGET['runtimeFileHashes'])
  full,inc,new=[load(evidence/('backup-'+name+'-status.json')) for name in ['full','incremental','new-full']]
  policy={key:int(c.get('COPPER_BACKUP_KEEP_'+key.upper()) or default) for key,default in [('daily',7),('weekly',4),('monthly',6)]}
  observation=load(a.observation);periods=validate_retention_observation(observation,full,inc,new,m.SITE,policy)
  paths=m.read_private(m.PATHS);etc,var,backup,restore=m.roots(c)
  for key,status in [('full',full),('incremental',inc),('new-full',new)]:
   require(Path(paths[key])==backup/status['archive'],'Backup path escaped current configured root')
  require(not Path(paths['incremental']).exists() and not Path(paths['incremental']).is_symlink(),'Incremental was declared pruned but original bytes are present/linked; reconcile availability')
  old_full=Path(paths['full']);full_local=False
  if old_full.exists():
   require(old_full.is_file() and not old_full.is_symlink() and digest(old_full)==full['sha256'],'Present first full differs from original SHA');full_local=True
  first=comparisons(evidence,1,inc['backupId'],full['sha256'])
  chain=load(evidence/'stage1/chain-summary.json')
  require(chain=={'baseBackupId':full['backupId'],'lastBackupId':inc['backupId'],'chainLength':1},'Original verified chain summary mismatch')
  switched=load(evidence/'switch1-status.json');require(switched.get('status')=='SWITCHED' and switched.get('backupId')==inc['backupId'],'Original switch report mismatch')
  rebuilt=load(evidence/'rebuild-persistence.json');require(rebuilt.get('status')=='PASS' and rebuilt['before']['directories']==rebuilt['after']['directories'] and rebuilt['before']['attachments']==rebuilt['after']['attachments'] and all(rebuilt['before']['containers'][k]!=rebuilt['after']['containers'][k] for k in ['mysql','backend','web']),'Original rebuild persistence report mismatch')
  archive,new_status=m.check_archive('new-full');sidecar=archive.with_name(archive.name+'.sha256')
  require(not sidecar.is_symlink() and sidecar.read_text().splitlines()==[new['sha256']+'  '+new['archive']] and archive.stat().st_size==new['size'],'Latest full sidecar or size mismatch')
  latest=load(backup/'last-success.json');current_chain=load(backup/'chain-state.json')
  require(all(latest.get(k)==new.get(k) for k in ['backupId','archive','sha256','size','formatVersion']),'Latest backup status changed')
  require(current_chain.get('backupId')==new['backupId'] and current_chain.get('baseBackupId')==new['backupId'] and current_chain.get('chainSequence')==0 and current_chain.get('archiveSha256')==new['sha256'],'Latest continuation chain is not the new full')
  receipt=m.read_private(a.new_receipt);mac=load(evidence/'mac-new-full-verification.json')
  require(receipt.get('formatVersion')=='copper-key-backup-verification/v1' and receipt.get('result')=='PASSED' and receipt.get('backupId')==new['backupId'] and receipt.get('backupSha256')==new['sha256'],'Actual new-full Mac receipt mismatch')
  proof=receipt.get('proof','');require(re.fullmatch('[0-9a-f]{64}',proof) and hashlib.sha256(proof.encode()).hexdigest()==receipt.get('proofSha256'),'Receipt proof digest mismatch')
  require(mac.get('status')=='PASS' and mac.get('system')=='Darwin' and mac.get('backupId')==new['backupId'] and mac.get('backupSha256')==new['sha256'] and mac.get('receiptSha256')==digest(a.new_receipt) and mac.get('offlineToolSha256')==m.TARGET['runtimeFileHashes']['copper-key-offline'],'Safe current Mac report/receipt/tool binding mismatch')
  second=comparisons(evidence,2,new['backupId'],new['sha256'])
  require(not (restore/'release-rebackup-rc11').exists(),'Second sidecar not discarded')
  rollback=restore/'release-recovery-rc11'
  require(load(rollback/'state.json').get('status')=='SWITCHED' and all((rollback/'rollback'/n).is_dir() and not (rollback/'rollback'/n).is_symlink() for n in ['mysql','files']),'First switched rollback no longer retained')
  m.run(m.CTL+['backup','verify',archive])
  containers=m.compose_ids()
  # These are the original audited tools, reading the current source site.
  # Authentication/session bookkeeping is expected; no business mutation is sent.
  m.api_verify('final','continued');m.proof('final','continued')
  require(digest(m.LEDGER)==ledger_sha,'Original frozen phase ledger was changed')
  require(digest(m.ENV)==ledger['envSha256'],'Site environment changed during supplemental final')
  runpy.run_path(str(driver/'verify-package.py'))['verify']()
  facts=load(evidence/'db-final/report.json')['facts']
  summary={'format':'copper-rc11-supplemental-retention-final/v1','status':'PASS_WITH_EXPECTED_RETENTION_ROTATION','siteId':m.SITE,'version':m.TARGET['version'],'recordedAt':datetime.now(timezone.utc).isoformat(),'frozenOriginalFinalNotExecuted':True,'acceptanceComposition':{'originalPassedPhases':list(m.PHASES[:-1]),'supplementalFinal':True},'originalPhaseLedgerUnmodifiedSha256':ledger_sha,'runtimeFileHashes':m.TARGET['runtimeFileHashes'],'policy':{'effectiveCounts':policy,'selection':'Latest full base per UTC day/week/month and the latest complete chain','timeBuckets':periods},'operatorObservation':{'fileSha256':digest(a.observation),'source':observation['observationSource'],'productStructuredDeletionReceiptClaimed':False,'observedLines':observation['observedLines']},'previousBackups':{'full':{'backupId':full['backupId'],'originalSha256':full['sha256'],'availability':'MAC_ORIGINAL_COPY_VERIFIED_BY_OPERATOR'+('_AND_LOCAL_ORIGINAL_PRESENT' if full_local else ''),'macCopy':observation['firstFullIndependentCopy']},'incremental':{'backupId':inc['backupId'],'originalSha256':inc['sha256'],'availability':PRUNED,'originalBytesReconstructed':False}},'latestFull':{'backupId':new['backupId'],'sha256':new['sha256'],'archiveAndSidecarActuallyVerified':True,'actualMacReceiptSha256':digest(a.new_receipt),'independentStage2Passed':True},'stage1Evidence':first,'stage2Evidence':second,'stage2Discarded':True,'firstSwitchedRollbackRetained':True,'currentHealthyContainers':containers,'currentBusinessFacts':{k:facts[k] for k in ['schema','foreign_keys','quantity','amount_cost','receivable','payable']},'financialExpectationsChanged':False,'businessMutationCommandsSent':False,'authenticationSessionBookkeepingExpected':True,'threeLocalArchivesStillExistClaimed':False,'customerWitnessOrLegalApprovalClaimed':False}
  m.save(output,summary);print(json.dumps({'status':summary['status'],'summary':str(output),'summarySha256':digest(output),'originalLedgerUnchanged':True,'oldIncrementalAvailability':PRUNED}))
if __name__=='__main__':main()
