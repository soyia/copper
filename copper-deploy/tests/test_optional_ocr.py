#!/usr/bin/env python3
"""Optional OCR validation and Compose routing; Docker is always a local stub."""
import argparse
import json
import os
from pathlib import Path
import runpy
import shlex
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
OCR = runpy.run_path(str(ROOT / 'scripts/ocr-config.py'))
SETTINGS = {'ocrEnabled': 'true', 'ocrEndpoint': 'http://copper-ocr:8080/ocr',
            'ocrNetwork': 'copper-private-ocr', 'ocrEngine': 'PADDLEOCR_LOCAL',
            'ocrModel': 'PP-OCRv6-medium', 'ocrTimeoutSeconds': '120'}
ENV_KEYS = ('COPPER_OCR_ENABLED', 'COPPER_OCR_ENDPOINT', 'COPPER_OCR_NETWORK',
            'COPPER_OCR_ENGINE', 'COPPER_OCR_MODEL', 'COPPER_OCR_TIMEOUT_SECONDS')
VALUE_KEYS = ('ocrEnabled', 'ocrEndpoint', 'ocrNetwork', 'ocrEngine', 'ocrModel', 'ocrTimeoutSeconds')


class OptionalOcrTests(unittest.TestCase):
    def test_optional_configuration_and_old_runtime_are_accepted(self):
        OCR['validate'](SETTINGS)
        OCR['validate_runtime']({'siteId': 'old-format-site'})
        OCR['validate']({**SETTINGS, 'ocrEnabled': 'false', 'ocrEndpoint': '', 'ocrNetwork': ''})

    def test_invalid_and_sensitive_configuration_is_rejected_without_echoing_url(self):
        for changes in ({'ocrEndpoint': ''}, {'ocrNetwork': ''}, {'ocrTimeoutSeconds': '301'},
                        {'ocrEnabled': 'yes'}, {'ocrNetwork': 'unsafe network'}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                OCR['validate']({**SETTINGS, **changes})
        for endpoint in ('http://127.0.0.1:8080/ocr', 'http://localhost:8080/ocr',
                         'http://mysql:3306/ocr', 'http://8.8.8.8/ocr', 'http://localhost.:8080/ocr',
                         'http://127.1/ocr', 'http://2130706433/ocr', 'http://0x7f000001/ocr',
                         'http://' + 'fixture-user:' + 'example-only' + '@copper-ocr:8080/ocr',
                         'http://copper-ocr:8080/ocr?token=example-only'):
            with self.subTest(endpoint=endpoint), self.assertRaises(ValueError) as caught:
                OCR['validate']({**SETTINGS, 'ocrEndpoint': endpoint})
            self.assertNotIn(endpoint, str(caught.exception))
            self.assertNotIn('example-only', str(caught.exception))
        with self.assertRaisesRegex(ValueError, '必须完整保留'):
            OCR['validate_runtime']({'ocrEnabled': 'true'})

    def stub_compose(self, enabled, command, network='internal', stage=False):
        with tempfile.TemporaryDirectory(prefix='copper-ocr-contract-') as temporary:
            base = Path(temporary)
            env_file = base / 'copper.env'
            values = {'COPPER_DEPLOY_MODE': 'external-proxy', 'COPPER_PROJECT_NAME': 'copper-restore-isolated'}
            if enabled:
                values.update(dict(zip(ENV_KEYS, (SETTINGS[key] for key in VALUE_KEYS))))
            env_file.write_text(''.join(f'{key}={value}\n' for key, value in values.items()))
            docker = base / 'docker'
            docker.write_text('''#!/usr/bin/env python3
import json,os,sys
with open(os.environ['DOCKER_CALLS'],'a') as output: output.write(json.dumps(sys.argv[1:])+'\\n')
if sys.argv[1] == 'compose':
 if any(key.startswith('COPPER_') for key in os.environ): raise SystemExit(88)
 if os.environ.get('DOCKER_HOST') != 'unix:///fixture.sock': raise SystemExit(89)
if sys.argv[1:3] == ['network','inspect']:
 if os.environ['NETWORK_KIND'] == 'missing': raise SystemExit(1)
 print(json.dumps([{'Name':'copper-private-ocr','Internal':os.environ['NETWORK_KIND']!='external',
                   'Labels':{'com.docker.compose.network':'data'} if os.environ['NETWORK_KIND']=='data' else {}}]))
''')
            docker.chmod(0o755)
            script = f'source {shlex.quote(str(ROOT / "scripts/lib.sh"))}\n'
            if stage:
                restore = (ROOT / 'scripts/restore.sh').read_text()
                script += restore[restore.index('stage_compose() {'):restore.index('\ncurrent_mysql_cli() {')] + '\n'
                script += f'stage_compose {shlex.quote(str(env_file))} {command}\n'
            else:
                script += f'compose_cmd {command}\n'
            result = subprocess.run(['bash', '-c', script], text=True, capture_output=True,
                env={**os.environ, 'PATH':str(base)+os.pathsep+os.environ['PATH'],
                     'COPPER_ENV_FILE':str(env_file), 'DOCKER_CALLS':str(base/'calls'),
                     'NETWORK_KIND':network, 'COMPOSE_PROJECT_NAME':'copper',
                     'COPPER_VAR_DIR':'/fixture-source-data', 'COPPER_ETC_DIR':'/fixture-source-etc',
                     'COPPER_PROJECT_NAME':'copper', 'COPPER_OCR_ENDPOINT':'http://wrong-source:8080/ocr',
                     'COPPER_OCR_ENABLED':'false', 'DOCKER_HOST':'unix:///fixture.sock'})
            calls = [json.loads(line) for line in (base/'calls').read_text().splitlines()]
            return result, calls

    def test_default_manual_mode_does_not_inspect_or_add_ocr_network(self):
        result, calls = self.stub_compose(False, 'up -d')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(calls), 1)
        self.assertNotIn(str(ROOT / 'compose.ocr.yaml'), calls[0])

    def test_enabled_mode_requires_actual_internal_network(self):
        result, calls = self.stub_compose(True, 'up -d')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls[0], ['network','inspect','copper-private-ocr'])
        self.assertIn(str(ROOT/'compose.ocr.yaml'), calls[1])
        for network in ('missing', 'external', 'data'):
            with self.subTest(network=network):
                result, calls = self.stub_compose(True, 'up -d', network)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(len(calls), 1)
                self.assertIn('错误', result.stderr)

    def test_teardown_and_stage_project_are_safe_when_network_is_gone(self):
        for stage in (False, True):
            for command in ('down --remove-orphans', 'stop'):
                result, calls = self.stub_compose(True, command, 'missing', stage)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(len(calls), 1)
                self.assertEqual(calls[0][calls[0].index('-p')+1], 'copper-restore-isolated')
        result, calls = self.stub_compose(True, 'up -d', stage=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls[-1][calls[-1].index('-p')+1], 'copper-restore-isolated')

    def test_backup_config_preserves_new_settings_and_rejects_partial_or_sensitive_values(self):
        metadata = runpy.run_path(str(ROOT/'scripts/backup-metadata.py'))
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)/'runtime.json'
            values = {key:'fixture' for key in metadata['RUNTIME_REQUIRED_KEYS']}
            for optional in ({}, SETTINGS):
                all_values = {**values, **optional}
                metadata['command_runtime_config'](argparse.Namespace(output=str(output),
                    values=[f'{key}={value}' for key,value in all_values.items()]))
                self.assertEqual(json.loads(output.read_text())['values'], all_values)
            for optional in ({'ocrEnabled':'true'}, {**SETTINGS,'ocrEndpoint':'http://' + 'fixture-user:' + 'example-only' + '@copper-ocr/ocr'}):
                with self.assertRaises(SystemExit) as caught:
                    metadata['command_runtime_config'](argparse.Namespace(output=str(output),
                        values=[f'{key}={value}' for key,value in {**values, **optional}.items()]))
                self.assertNotIn('example-only', str(caught.exception))

    def test_restore_stage_preserves_explicit_target_ocr_settings(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            values = {key:'fixture' for key in ('COPPER_RELEASE_VERSION','COPPER_WEB_IMAGE','COPPER_BACKEND_IMAGE','COPPER_MYSQL_IMAGE')}
            values.update(dict(zip(ENV_KEYS,(SETTINGS[key] for key in VALUE_KEYS))))
            env_file = base/'copper.env'
            env_file.write_text(''.join(f'{key}={value}\n' for key,value in values.items()))
            restore = (ROOT/'scripts/restore.sh').read_text()
            function = restore[restore.index('write_stage_env() {'):restore.index('\nprepare_stage_directories() {')]
            script = f'source {shlex.quote(str(ROOT/"scripts/lib.sh"))}\n{function}\n'
            script += 'site_id=fixture; backup_dir=/tmp/copper-backups; restore_root=/tmp/copper-restores\n'
            script += f'write_stage_env {shlex.quote(str(base/"stage.env"))} isolated /tmp/copper-etc /tmp/copper-var 18080\n'
            result = subprocess.run(['bash','-c',script], text=True,capture_output=True,
                                    env={**os.environ,'COPPER_ENV_FILE':str(env_file)})
            self.assertEqual(result.returncode,0,result.stderr)
            restored = dict(line.split('=',1) for line in (base/'stage.env').read_text().splitlines())
            self.assertEqual({key:restored[key] for key in ENV_KEYS}, {key:values[key] for key in ENV_KEYS})
            self.assertEqual(restored['COPPER_PROJECT_NAME'], 'copper-restore-isolated')


if __name__ == '__main__':
    unittest.main()
