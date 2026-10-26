#!/usr/bin/env python3
"""Verify the public source inventory without running application or build code."""
from pathlib import Path,PurePosixPath
import argparse,hashlib,json,subprocess,sys

def require(ok,message):
 if not ok:raise ValueError(message)
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def verify(root):
 root=root.resolve();manifest='release/CONTENTS.json';doc=json.loads((root/manifest).read_text());files=doc['files']
 require(doc['format']=='copper-public-source-contents/v1' and manifest not in files,'invalid closed inventory')
 for name,row in files.items():
  pure=PurePosixPath(name);require(not pure.is_absolute() and '..' not in pure.parts and str(pure)==name and '\\' not in name,'unsafe inventory path')
  p=root/name;require(p.is_file() and not p.is_symlink(),'missing or unsafe file: '+name)
  require(p.stat().st_size==row['bytes'] and digest(p)==row['sha256'],'file digest differs: '+name)
  require(bool(p.stat().st_mode&0o111)==(row['gitMode']=='100755'),'executable mode differs: '+name)
 git=subprocess.run(['git','-C',str(root),'rev-parse','--show-toplevel'],capture_output=True,text=True)
 if git.returncode==0 and Path(git.stdout.strip()).resolve()==root:
  actual=set(subprocess.check_output(['git','-C',str(root),'ls-files','-z']).decode().split('\0'))-{''}
 else:
  actual=set()
  for p in root.rglob('*'):
   require(not p.is_symlink(),'source archive contains a link')
   if p.is_file():actual.add(str(p.relative_to(root)))
 require(actual==set(files)|{manifest},'source file set differs; verify before building in an extracted archive')
 origin=json.loads((root/'SOURCE_ORIGIN.json').read_text());index=json.loads((root/'release/SOURCE_FILE_INDEX.json').read_text())
 require(origin['license']=='AGPL-3.0-only' and len(index)==origin['originalTrackedFileCount'],'origin/index differs')
 changed=[]
 for name,row in index.items():
  require(name in files and digest(root/name)==row['publishedSha256'],'original source mapping differs: '+name)
  require(row['gitMode']==files[name]['gitMode'],'original mode differs: '+name)
  if row['publishedSha256']!=row['originalSha256']:changed.append(name)
 require(changed==origin['packagingOnlyModifiedFiles'],'unrecorded source change')
 required_license='d8a6cc31abc16b6748c7a21f21611f5a1ec33f67d22ca23d7da1c19b95496bee'
 for directory in ['',* [v['publishedDirectory'] for v in origin['components'].values()]]:require(digest(root/directory/'LICENSE')==required_license,'AGPL text changed')
 for name,expected in {'main.go':'ac320c3ac47ad8abed7a0d2595639d78f9ff55cf1dba8494a66cd8851c5d076e','LICENSE':'cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30','NOTICE':'1e8070f17d98fe6156a6aaa937039e5985c0ae94853096de49e85113f4b4bef3'}.items():require(digest(root/'copper-web/docker/caddy'/name)==expected,'third-party original changed')
 return {'status':'PASS','version':origin['version'],'files':len(files)+1,'originalTrackedFiles':len(index),'license':origin['license'],'runtimeSourceBytesChanged':False,'verificationScope':'tracked source tree when in Git; exact ordinary files when extracted; build outputs not part of the release'}
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1]);a=p.parse_args()
 try:print(json.dumps(verify(a.root)))
 except (ValueError,KeyError,OSError,json.JSONDecodeError,subprocess.SubprocessError) as e:print('Source verification failed: '+str(e),file=sys.stderr);sys.exit(1)
