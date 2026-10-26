#!/usr/bin/env python3
"""Package a clean committed public source tree; no binaries or private history."""
from pathlib import Path
import argparse,gzip,hashlib,io,json,os,runpy,subprocess,tarfile
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output-dir',type=Path,required=True);a=p.parse_args()
if subprocess.check_output(['git','-C',str(root),'status','--porcelain']).strip():raise SystemExit('Refusing dirty source tree')
runpy.run_path(str(root/'scripts/verify-source-release.py'))['verify'](root)
commit=subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip();version=json.loads((root/'SOURCE_ORIGIN.json').read_text())['version'];prefix='copper-'+version+'-source/'
raw=subprocess.check_output(['git','-C',str(root),'archive','--format=tar','--prefix='+prefix,'HEAD'])
expected=set(subprocess.check_output(['git','-C',str(root),'ls-files','-z']).decode().split('\0'))-{''};actual=set()
with tarfile.open(fileobj=io.BytesIO(raw),mode='r:') as t:
 for m in t.getmembers():
  if m.isdir():continue
  if not m.isfile() or not m.name.startswith(prefix):raise SystemExit('Unsafe source archive entry')
  rel=m.name[len(prefix):]
  if rel in actual or rel not in expected or t.extractfile(m).read()!=(root/rel).read_bytes():raise SystemExit('Archive content differs')
  actual.add(rel)
if actual!=expected:raise SystemExit('Archive omitted tracked files')
out=a.output_dir.resolve()
if out==root or root in out.parents:raise SystemExit('Output directory must be outside source tree')
out.mkdir(parents=True,exist_ok=True);target=out/('copper-'+version+'-source.tar.gz')
if target.exists():raise SystemExit('Output already exists; never replace a release asset')
with target.open('xb') as stream:
 with gzip.GzipFile(filename='',mode='wb',fileobj=stream,mtime=0) as z:z.write(raw)
h=hashlib.sha256(target.read_bytes()).hexdigest();(out/'SHA256SUMS').write_text(h+'  '+target.name+'\n')
print(json.dumps({'status':'SOURCE_PACKAGE_CLOSED','commit':commit,'sha256':h,'bytes':target.stat().st_size,'files':len(actual),'archive':str(target)}))
