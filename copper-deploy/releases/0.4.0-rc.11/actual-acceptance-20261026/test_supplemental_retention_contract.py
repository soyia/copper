#!/usr/bin/env python3
"""Synthetic guard contracts only; never creates actual stage/final reports."""
import copy,importlib.util,unittest,sys
from pathlib import Path
sys.dont_write_bytecode=True
P=Path(__file__).with_name('supplemental-retention-final.py')
s=importlib.util.spec_from_file_location('retention_supplement_unit',P);m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class Contracts(unittest.TestCase):
 def setUp(self):
  self.site='copper-acceptance-rc11'
  def status(ident,stamp,fmt,sha):return {'backupId':ident*32,'archive':self.site+'_'+stamp+'_'+ident*32+'_manual.copperbak','sha256':sha*64,'formatVersion':fmt}
  self.full=status('1','20200101T000000Z','copper-backup/v1','a');self.inc=status('2','20200101T001000Z','copper-backup/v2','b');self.new=status('3','20200101T002000Z','copper-backup/v1','c')
  self.inc.update(baseBackupId=self.full['backupId'],parentBackupId=self.full['backupId'],chainSequence=1)
  self.obs={'syntheticUnitFixtureNotActualEvidence':True,'format':'copper-internal-retention-operator-observation/v1','observationSource':m.OBS_SOURCE,'productStructuredDeletionReceiptClaimed':False,'newFullBackupId':self.new['backupId'],'newFullPhaseExitedSuccessfully':True,'observedLines':['Copper：已按保留策略删除旧备份：'+x['archive'] for x in [self.full,self.inc]],'oldIncrementalOriginalBytes':m.PRUNED,'financialExpectationsChanged':False,'frozenDriverModified':False,'firstFullIndependentCopy':{'location':'MAC_SYNTHETIC_VERIFICATION_COPY','archiveBasename':self.full['archive'],'backupId':self.full['backupId'],'sha256':self.full['sha256'],'sidecarVerifiedAgain':True}}
  self.policy={'daily':7,'weekly':4,'monthly':6}
 def validate(self):return m.validate_retention_observation(self.obs,self.full,self.inc,self.new,self.site,self.policy)
 def test_actual_policy_bucket_shape_accepts_matching_synthetic_facts(self):self.assertEqual(self.validate()['full']['day'],'2020-01-01')
 def test_observation_is_never_product_structured_receipt(self):
  self.obs['productStructuredDeletionReceiptClaimed']=True
  with self.assertRaises(RuntimeError):self.validate()
 def test_wrong_deleted_filename_rejected(self):
  self.obs['observedLines'][1]+='-wrong'
  with self.assertRaises(RuntimeError):self.validate()
 def test_other_new_full_identity_rejected(self):
  self.obs['newFullBackupId']='4'*32
  with self.assertRaises(RuntimeError):self.validate()
 def test_failed_observed_phase_rejected(self):
  self.obs['newFullPhaseExitedSuccessfully']=False
  with self.assertRaises(RuntimeError):self.validate()
 def test_mac_original_copy_hash_mismatch_rejected(self):
  self.obs['firstFullIndependentCopy']['sha256']='0'*64
  with self.assertRaises(RuntimeError):self.validate()
 def test_different_utc_bucket_rejected(self):
  self.new['archive']=self.new['archive'].replace('20200101','20200102')
  with self.assertRaises(RuntimeError):self.validate()
 def test_other_policy_or_chain_rejected(self):
  self.policy['daily']=1
  with self.assertRaises(RuntimeError):self.validate()
  self.policy['daily']=7;self.inc['parentBackupId']='4'*32
  with self.assertRaises(RuntimeError):self.validate()
 def test_15_passed_original_phases_and_no_final_required(self):
  phases=tuple('phase'+str(i) for i in range(15))+('final',);ledger={'envSha256':'synthetic-env','runtimeFileHashes':{'x':'synthetic-runtime'},'phases':{name:{'status':'PASS'} for name in phases[:-1]}}
  m.validate_ledger(ledger,phases,'synthetic-env',{'x':'synthetic-runtime'})
  ledger['phases']['final']={'status':'PASS'}
  with self.assertRaises(RuntimeError):m.validate_ledger(ledger,phases,'synthetic-env',{'x':'synthetic-runtime'})
 def test_unknown_phase_or_changed_env_rejected(self):
  phases=('a','final');ledger={'envSha256':'s','runtimeFileHashes':{},'phases':{'a':{'status':'RUNNING'}}}
  with self.assertRaises(RuntimeError):m.validate_ledger(ledger,phases,'s',{})
  ledger['phases']['a']['status']='PASS'
  with self.assertRaises(RuntimeError):m.validate_ledger(ledger,phases,'changed',{})
if __name__=='__main__':unittest.main(verbosity=2)
