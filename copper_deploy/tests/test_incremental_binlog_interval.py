#!/usr/bin/env python3
"""Independent synthetic pre-DB interval checks. Never starts a DB or service."""
from pathlib import Path
import hashlib, importlib.util, json, shutil, subprocess, tempfile, unittest

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('isolated_interval_fixture',ROOT/'tests/test_backup_chain.py')
fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture)
H=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()

class Captured(Exception): pass

class BinlogIntervalTests(unittest.TestCase):
 def setUp(self):
  self.work=tempfile.TemporaryDirectory(prefix='copper-owned-binlog-interval-')
  self.addCleanup(self.work.cleanup)
  self.root=Path(self.work.name);self.payload=self.root/'payload'
  case=fixture.BackupChainTests('test_incremental_payload_verifies_all_declared_files_and_boundaries')
  def capture(*args,**kwargs):
   assert args[0]=='verify-incremental'
   shutil.copytree(Path(args[1]),self.payload)
   self.verify_args=list(map(str,args));self.verify_args[1]=str(self.payload);self.verify_args[5]=str(self.root/'summary.json')
   raise Captured()
  case.run_tool=capture
  try:case.test_incremental_payload_verifies_all_declared_files_and_boundaries()
  except Captured:pass
  else:self.fail('fixture capture did not execute')

 def verify(self,from_number,to_number,names,from_position=157,to_position=157,undeclared=()):
  manifest_path=self.payload/'manifest.json';manifest=json.loads(manifest_path.read_text())
  folder=self.payload/'database/binlog'
  for p in folder.iterdir():
   assert p.is_file() and p.parent==folder;p.unlink()
  entries=[]
  for number in names:
   name=f'binlog.{number:06d}';p=folder/name;p.write_bytes(('SYNTHETIC_PRE_DB_ONLY_'+str(number)).encode())
   entries.append({'path':'database/binlog/'+name,'name':name,'size':p.stat().st_size,'sha256':H(p)})
  for number in undeclared:(folder/f'binlog.{number:06d}').write_bytes(b'SYNTHETIC_UNDECLARED_CLOSED_FILE')
  manifest['database']['binlog']['from']={'file':f'binlog.{from_number:06d}','position':from_position}
  manifest['database']['binlog']['to']={'file':f'binlog.{to_number:06d}','position':to_position}
  manifest['database']['binlog']['files']=entries
  manifest_path.write_text(json.dumps(manifest)+'\n')
  files=sorted(p for p in self.payload.rglob('*') if p.is_file() and p.name!='checksums.sha256')
  (self.payload/'checksums.sha256').write_text(''.join(H(p)+'  '+p.relative_to(self.payload).as_posix()+'\n' for p in files))
  return subprocess.run(['python3',str(ROOT/'scripts/backup-chain.py'),*self.verify_args],text=True,capture_output=True)

 def expect(self,should_pass,*args,**kwargs):
  result=self.verify(*args,**kwargs)
  self.assertEqual(result.returncode==0,should_pass,result.stderr or 'Unexpected acceptance of incomplete binlog interval')

 def test_next_file_excluded_at_both_header_positions(self):
  for from_pos in [4,157]:
   for to_pos in [4,157]:
    with self.subTest(from_position=from_pos,to_position=to_pos):self.expect(True,1,2,[1],from_pos,to_pos)

 def test_all_closed_files_included_at_both_end_positions(self):
  for to_pos in [4,157]:
   with self.subTest(to_position=to_pos):self.expect(True,1,4,[1,2,3],157,to_pos)

 def test_missing_first_middle_or_last_closed_file_rejected(self):
  for names in [[2,3],[1,3],[1,2],[1]]:
   with self.subTest(names=names):self.expect(False,1,4,names)

 def test_to_file_and_before_from_file_are_not_part_of_interval(self):
  for names in [[1,2],[0,1]]:
   with self.subTest(names=names):self.expect(False,1,2,names)

 def test_unlisted_binlog_payload_file_rejected_even_when_checksummed(self):
  self.expect(False,1,2,[1],undeclared=[2])

 def test_same_or_reverse_file_interval_rejected(self):
  self.expect(False,1,1,[1],4,157)
  self.expect(False,2,1,[2])

 def test_duplicates_and_out_of_order_rejected(self):
  for names in [[1,1,2],[2,1]]:
   with self.subTest(names=names):self.expect(False,1,3,names)

 def test_numeric_suffix_boundary_preserves_continuity(self):
  self.expect(True,999999,1000001,[999999,1000000])

 def test_extreme_gap_rejected_without_range_allocation(self):
  self.expect(False,1,999999999999999999999,[1])

if __name__=='__main__':unittest.main(verbosity=2)
