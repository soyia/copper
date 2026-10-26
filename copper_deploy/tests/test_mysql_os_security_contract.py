#!/usr/bin/env python3
"""Execute the production OS RUN with public-package mocks and temporary paths only."""
import hashlib
import json
import os
import pathlib
import re
import shlex
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
MINIMUM = {
    "openssl": "1:3.5.8-2.0.1.el9_8", "openssl-libs": "1:3.5.8-2.0.1.el9_8",
    "curl": "0:7.76.1-40.el9_8.7", "libcurl": "0:7.76.1-40.el9_8.7",
    "libevent": "0:2.1.13-1.el9_8", "glib2": "0:2.68.4-19.el9_8.2",
    "libxml2": "0:2.9.13-14.el9_8.5", "sqlite-libs": "0:3.34.1-11.el9_8",
    "pcre2": "0:10.40-7.0.1.el9_8", "pcre2-syntax": "0:10.40-7.0.1.el9_8",
    "libacl": "0:2.4.0-1.el9_8",
}
RPM_BYTES = b"synthetic test-only fixed mysqlbinlog RPM payload"


def safe_production_os_run(root):
    text = (ROOT / "images/mysql/Dockerfile").read_text()
    command = re.search(r"^RUN (set -eu;.*?)\n\nCOPY", text, re.M | re.S)[1].replace("\\\n", " ")
    paths = {
        "/tmp/copper-rpms.before": root / "runtime/tmp/rpms.before",
        "/tmp/copper-rpms.after": root / "runtime/tmp/rpms.after",
        "/tmp/copper-mysql.before": root / "runtime/tmp/mysql.before",
        "/tmp/copper-mysql.after": root / "runtime/tmp/mysql.after",
        "/tmp/copper-mysql-cli.sha256": root / "runtime/tmp/mysql-cli.sha256",
        "/tmp/mysql-community-client.rpm": root / "runtime/tmp/mysql-community-client.rpm",
        "/tmp/usr/bin/mysqlbinlog": root / "runtime/tmp/usr/bin/mysqlbinlog",
        "/usr/bin/mysqlbinlog": root / "formal/mysqlbinlog",
        "/var/cache/dnf": root / "runtime/cache/dnf",
        "/tmp/usr": root / "runtime/tmp/usr",
    }
    # Two-pass replacement never rewrites an already mapped temporary path.
    for i, actual in enumerate(paths):
        assert actual in command, actual
        command = command.replace(actual, "__COPPER_TEST_PATH_" + str(i) + "__")
    for i, destination in enumerate(paths.values()):
        command = command.replace("__COPPER_TEST_PATH_" + str(i) + "__", shlex.quote(str(destination)))
    assert "cd /tmp " in command
    command = command.replace("cd /tmp ", "cd " + shlex.quote(str(root / "runtime/tmp")) + " ")
    for published_sha in (
        "6b4ab8b6f711a0f8460a411049ee7418c214aaa5bf64014c08e256db65ade69b",
        "28f32b4f932cb84ad3bb6e87b8cd26c02ce76d90b1082415d5dad8ff6014d6a8",
    ):
        assert published_sha in command
        command = command.replace(published_sha, hashlib.sha256(RPM_BYTES).hexdigest())
    return command


MOCK = r'''import hashlib,json,os,pathlib,re,shutil,sys
root=pathlib.Path(os.environ['COPPER_MYSQL_TEST_ROOT']).resolve();scenario=os.environ['COPPER_MYSQL_TEST_SCENARIO'];name=pathlib.Path(sys.argv[0]).name;args=sys.argv[1:]
minimum=json.loads((root/'minimum.json').read_text());statefile=root/'state.json';state=json.loads(statefile.read_text())
with (root/'calls.jsonl').open('a') as f:f.write(json.dumps({'tool':name,'args':args})+'\n')
def owned(value):
 p=pathlib.Path(value).resolve();assert p.is_relative_to(root),str(p);return p
if name=='microdnf':
 if args[0]=='install':assert args==['install','-y','--setopt=install_weak_deps=0','cpio','util-linux']
 elif args[0]=='upgrade':
  assert args==['upgrade','-y','--setopt=install_weak_deps=0','--disablerepo=*','--enablerepo=ol9_baseos_latest',*minimum]
  if scenario=='repo-missing':sys.exit(41)
  state['updated']=True
  if scenario.startswith('elf-'):
   p=root/'bin'/scenario.removeprefix('elf-');p.write_bytes(p.read_bytes()+b'\n# changed by simulated transaction\n')
  if scenario=='mysql-rpm':state['mysqlDrift']=True
  if scenario=='mysql-plugin-rpm':state['pluginDrift']=True
  statefile.write_text(json.dumps(state))
 elif args==['clean','all']:pass
 else:raise AssertionError(args)
elif name=='rpm':
 if args[0]=='-qa':
  print('mysql-community-server-minimal 0:'+('8.4.11' if state.get('mysqlDrift') else '8.4.10')+'-1.el9.x86_64')
  print('mysql-community-client-plugins 0:'+('8.4.11' if state.get('pluginDrift') else '8.4.10')+'-1.el9.x86_64')
  print('openssl 1:3.5.8-2.0.1.el9_8.x86_64')
 elif args[0]=='-q':
  package=args[-1];assert state['updated'];value=minimum[package]
  if scenario=='old-'+package:value='1:3.5.5-4.0.1.el9_8' if package.startswith('openssl') else '0:1.0-1.el9'
  if scenario=='fips-'+package:value='10:3.5.8-2.0.1.el9_8_fips'
  if scenario=='query-missing-'+package:sys.exit(42)
  print(value,end='')
 elif args[0]=='--eval':
  m=re.fullmatch(r"%\{lua:print\(rpm\.vercmp\('([^']+)', '([^']+)'\)\)\}",args[1]);assert m,args
  actual,required=m.groups();assert required in minimum.values()
  # The real image uses librpm's native comparator. This mock supplies its
  # result deliberately; no local lexical/version-sort emulation is used.
  if scenario=='comparator-error':sys.exit(43)
  print(0 if actual==required else -1)
 else:raise AssertionError(args)
elif name=='curl':
 p=owned(args[args.index('--output')+1]);p.write_bytes(b'tampered' if scenario=='bad-client-rpm' else bytes.fromhex((root/'rpm-bytes.hex').read_text()))
elif name=='rpm2cpio':print('synthetic cpio stream')
elif name=='cpio':
 assert pathlib.Path.cwd().resolve()==root/'runtime/tmp'
 p=root/'runtime/tmp/usr/bin/mysqlbinlog';p.parent.mkdir(parents=True,exist_ok=True);p.write_text('#!/bin/sh\nprintf "mysqlbinlog 8.4.10\\n"\n');p.chmod(0o755)
elif name=='install':
 assert args[:6]==['-o','root','-g','root','-m','0755'];src,dst=map(owned,args[-2:]);shutil.copyfile(src,dst);dst.chmod(0o755)
elif name=='rm':
 for value in args:
  if value.startswith('-'):continue
  p=owned(value)
  if p.is_dir():shutil.rmtree(p)
  else:p.unlink(missing_ok=True)
else:raise AssertionError(name)
'''


class MysqlOsSecurityContractTest(unittest.TestCase):
    def run_production(self, scenario="success", arch="amd64"):
        with tempfile.TemporaryDirectory(prefix="copper-mysql-os-") as temporary:
            root = pathlib.Path(temporary).resolve()
            for path in ("bin", "formal", "runtime/tmp", "runtime/cache/dnf"):
                (root / path).mkdir(parents=True)
            (root / "minimum.json").write_text(json.dumps(MINIMUM))
            (root / "state.json").write_text(json.dumps({"updated": False}))
            (root / "rpm-bytes.hex").write_text(RPM_BYTES.hex())
            for tool in ("mysqld", "mysql", "mysqldump"):
                p = root / "bin" / tool
                p.write_text("#!/bin/sh\nprintf '" + tool + " 8.4.10\\n'\n")
                p.chmod(0o755)
            original = {tool: (root / "bin" / tool).read_bytes() for tool in ("mysqld", "mysql", "mysqldump")}
            for tool in ("rpm", "microdnf", "curl", "rpm2cpio", "cpio", "install", "rm"):
                p = root / "bin" / tool
                p.write_text("#!" + sys_executable() + "\n" + MOCK)
                p.chmod(0o755)
            env = os.environ | {"PATH": str(root / "formal") + os.pathsep + str(root / "bin") + os.pathsep + os.environ["PATH"],
                                "TARGETARCH": arch, "COPPER_MYSQL_TEST_ROOT": str(root), "COPPER_MYSQL_TEST_SCENARIO": scenario}
            result = subprocess.run(["/bin/sh", "-c", safe_production_os_run(root)], env=env, text=True, capture_output=True)
            calls = [json.loads(line) for line in (root / "calls.jsonl").read_text().splitlines()]
            remaining = list((root / "runtime/tmp").iterdir())
            for tool, content in original.items():
                if scenario != "elf-" + tool:
                    self.assertEqual((root / "bin" / tool).read_bytes(), content)
            return result, calls, remaining

    def test_exact_ordinary_update_and_mysql_guards_complete_for_both_architectures(self):
        for arch in ("amd64", "arm64"):
            with self.subTest(arch=arch):
                result, calls, remaining = self.run_production(arch=arch)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(remaining, [])
                versions = [x for x in calls if x["tool"] == "rpm" and x["args"][0] == "--eval"]
                self.assertEqual(len(versions), 11)
                self.assertTrue(all("rpm.vercmp" in x["args"][1] for x in versions))
                self.assertEqual(sum(x["tool"] == "rpm" and x["args"][0] == "-qa" for x in calls), 2)
                self.assertEqual([x["args"] for x in calls if x["tool"] == "microdnf"][-1], ["clean", "all"])
                for tool in ("mysqld", "mysql", "mysqldump"):
                    self.assertIn(tool + ": OK", result.stdout)

    def test_every_missing_security_patch_fails_closed(self):
        for package in MINIMUM:
            with self.subTest(package=package):
                result, calls, _ = self.run_production("old-" + package)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("security update unavailable: " + package, result.stderr)
                self.assertFalse(any(x["tool"] == "curl" for x in calls))

    def test_fips_stream_is_rejected_even_with_higher_epoch(self):
        result, calls, _ = self.run_production("fips-openssl")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("invalid ordinary OL9 package", result.stderr)
        self.assertFalse(any(x["tool"] == "curl" for x in calls))

    def test_repo_query_and_native_comparison_errors_stop_before_download(self):
        for scenario in ("repo-missing", "query-missing-openssl", "comparator-error"):
            with self.subTest(scenario=scenario):
                result, calls, _ = self.run_production(scenario)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(any(x["tool"] == "curl" for x in calls))

    def test_any_mysql_rpm_drift_rejects_the_build(self):
        for scenario in ("mysql-rpm", "mysql-plugin-rpm"):
            with self.subTest(scenario=scenario):
                result, calls, _ = self.run_production(scenario)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(any(x["tool"] == "microdnf" and x["args"] == ["clean", "all"] for x in calls))

    def test_each_core_mysql_elf_drift_rejects_the_build(self):
        for tool in ("mysqld", "mysql", "mysqldump"):
            with self.subTest(tool=tool):
                result, _, _ = self.run_production("elf-" + tool)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("FAILED", result.stdout + result.stderr)

    def test_fixed_mysqlbinlog_rpm_sha_check_remains_effective(self):
        result, calls, _ = self.run_production("bad-client-rpm")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("FAILED", result.stdout + result.stderr)
        self.assertFalse(any(x["tool"] == "install" for x in calls))


def sys_executable():
    import sys
    return sys.executable


if __name__ == "__main__":
    unittest.main()
