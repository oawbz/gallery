#!/usr/bin/env python3
"""Create/reuse a local signing key and send it to GitHub Secrets over stdin; never print secrets."""
import argparse
import base64
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--repo', default='oawbz/gallery')
parser.add_argument('--branch', default='codex/android-zh-openai-api')
parser.add_argument('--directory', type=Path, default=Path.home() / '.local/share/gallery-android-signing')
parser.add_argument('--create-only', action='store_true', help='Create/reuse local files without uploading or rerunning Actions')
parser.add_argument('--no-rerun', action='store_true')
parser.add_argument('--overwrite-secrets', action='store_true', help='Upload an existing, backed-up local key over repository secrets; never generate a replacement key')
args = parser.parse_args()
if not shutil.which('git'):
    sys.exit('Install Git first; it is required to keep signing files outside the checkout.')
if not shutil.which('keytool'):
    sys.exit('A JDK with keytool is required. Install Java 21 first.')
if not args.create_only and not shutil.which('gh'):
    sys.exit('Install GitHub CLI and run gh auth login first. No secrets have been uploaded.')
key_dir = args.directory.expanduser().resolve()
probe = key_dir
while not probe.exists():
    probe = probe.parent
if subprocess.run(['git', '-C', str(probe), 'rev-parse', '--show-toplevel'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
    sys.exit('Choose a signing directory outside any Git checkout.')
existing = set()
if not args.create_only:
    listed = subprocess.run(['gh', 'secret', 'list', '--repo', args.repo, '--json', 'name'], capture_output=True, text=True, check=True)
    existing = {item['name'] for item in json.loads(listed.stdout)} & {'ANDROID_SIGNING_KEYSTORE_BASE64', 'ANDROID_SIGNING_PASSWORD'}
    if existing and not args.overwrite_secrets:
        sys.exit('Signing secrets already exist and were left unchanged. To restore from your backed-up local key, use --overwrite-secrets; to rebuild, rerun the workflow in GitHub Actions.')
    if existing and args.overwrite_secrets and not ((key_dir / 'gallery.p12').is_file() and (key_dir / 'credentials.json').is_file()):
        sys.exit('Restore your existing signing files first. Refusing to generate a new key over existing repository signing secrets.')
key_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
key_dir.chmod(0o700)
keystore = key_dir / 'gallery.p12'
credentials = key_dir / 'credentials.json'
if keystore.exists() != credentials.exists():
    sys.exit('Incomplete signing files: restore the missing file from backup; refusing to replace an existing key.')
if not keystore.exists():
    password = secrets.token_urlsafe(40)
    env = os.environ.copy()
    env['GALLERY_SIGNING_PASSWORD'] = password
    temporary = key_dir / 'gallery-pending.p12'
    try:
        subprocess.run(['keytool', '-genkeypair', '-keystore', str(temporary), '-storetype', 'PKCS12',
                        '-alias', 'gallery', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10950',
                        '-dname', 'CN=Gallery Community Build', '-storepass:env', 'GALLERY_SIGNING_PASSWORD',
                        '-keypass:env', 'GALLERY_SIGNING_PASSWORD'], env=env, check=True)
        temporary.chmod(0o600)
        with credentials.open('x', encoding='utf-8') as output:
            credentials.chmod(0o600)
            json.dump({'password': password}, output)
        temporary.replace(keystore)
    except BaseException:
        temporary.unlink(missing_ok=True)
        raise
else:
    password = json.loads(credentials.read_text(encoding='utf-8'))['password']
print(f'Signing files retained in {key_dir}. Back up this directory securely; never commit or share it.')
if args.create_only:
    sys.exit(0)
for name, data in [('ANDROID_SIGNING_KEYSTORE_BASE64', base64.b64encode(keystore.read_bytes())),
                   ('ANDROID_SIGNING_PASSWORD', password.encode())]:
    subprocess.run(['gh', 'secret', 'set', name, '--repo', args.repo], input=data, check=True)
    print(f'Configured repository secret: {name}')
if args.no_rerun:
    sys.exit(0)
runs = subprocess.run(['gh', 'run', 'list', '--repo', args.repo, '--branch', args.branch,
                       '--limit', '1', '--json', 'databaseId,status'], capture_output=True, text=True, check=True)
items = json.loads(runs.stdout)
if not items:
    print('No workflow run found. Push the fixed branch to start a signed release.')
    sys.exit(0)
run_id = str(items[0]['databaseId'])
if items[0]['status'] != 'completed':
    print('Waiting for the current run to finish before restarting with signing secrets.')
    subprocess.run(['gh', 'run', 'watch', run_id, '--repo', args.repo])
subprocess.run(['gh', 'run', 'rerun', run_id, '--repo', args.repo], check=True)
print(f'Signed build restarted: https://github.com/{args.repo}/actions/runs/{run_id}')
