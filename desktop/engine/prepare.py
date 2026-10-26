"""Puts together the engine Chaya for Windows ships: a Python, yt-dlp and gallery-dl, chaya_engine, and QuickJS.

    python desktop/engine/prepare.py                 # the Windows engine (CI, or a Windows PC)
    python desktop/engine/prepare.py --host          # an engine for this computer's own Python (Linux, macOS)

The result is desktop/app/src-tauri/engine/, which the installer carries (tauri.conf.json, bundle.resources):

    engine/python/      Python's Windows embeddable package (not with --host)
    engine/packages/    the engine packages at the versions the phone app pins, and chaya_engine itself
    engine/qjs.exe      QuickJS-ng, which yt-dlp runs YouTube's challenge scripts in (not with --host)
    engine/ffmpeg.exe   the cut-down ffmpeg that joins picture and sound, when given with --ffmpeg
                        (build_ffmpeg.sh makes it; without it, qualities that need joining are not offered)
    engine/run.py       what the app starts: `python -I run.py extract URL --cache DIR ...`
    engine/engine.json  what is in it, read by the app and shown in diagnostics

Everything downloaded is checked against a SHA-256 written here; the packages come from PyPI through pip, at the
versions in app/build.gradle.kts (enginePackages), so the phone and the desktop run the same engine.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
ENGINE_SOURCE = REPO / 'app' / 'src' / 'main' / 'python' / 'chaya_engine'
PINS_FILE = REPO / 'app' / 'build.gradle.kts'
HERE = Path(__file__).resolve().parent
OUT = REPO / 'desktop' / 'app' / 'src-tauri' / 'engine'

# Python 3.13, as on the phone (Chaquopy). 3.13.17 is the last 3.13 release with Windows downloads; later
# 3.13 releases are source-only security fixes.
PYTHON = {
    'version': '3.13.17',
    'url': 'https://www.python.org/ftp/python/3.13.17/python-3.13.17-embed-amd64.zip',
    'sha256': '9e35319bffedab8b36ad44d8f377cb4f1733bcec257a0b6d04d94f01486ddb38',
}
# yt-dlp 2026.8.19 accepts QuickJS-ng 0.12 or newer for YouTube's challenges.
QUICKJS = {
    'version': '0.17.0',
    'url': 'https://github.com/quickjs-ng/quickjs/releases/download/v0.17.0/qjs-windows-x86_64.exe',
    'sha256': '2aeabf0092c3262d6b2609824418f7dd7ed1f1df939f73b2b15645230cac0d77',
}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    parser.add_argument('--host', action='store_true',
                        help="for this computer's own Python, to run the app in development off Windows")
    parser.add_argument('--out', type=Path, default=OUT)
    parser.add_argument('--python-zip', type=Path, help='a downloaded copy of the embeddable package')
    parser.add_argument('--qjs', type=Path, help='a downloaded copy of qjs-windows-x86_64.exe')
    parser.add_argument('--ffmpeg', type=Path,
                        help='the ffmpeg build_ffmpeg.sh made, with its ffmpeg-LICENSE.txt beside it')
    args = parser.parse_args(argv)

    out = args.out
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True)

    manifest = {'packages': {}, 'python': None, 'quickjs': None, 'ffmpeg': None}
    if not args.host:
        unpack_python(args.python_zip or download(PYTHON), out / 'python')
        manifest['python'] = PYTHON['version']
        place_quickjs(args.qjs or download(QUICKJS), out / 'qjs.exe')
        manifest['quickjs'] = QUICKJS['version']
    if args.ffmpeg:
        manifest['ffmpeg'] = place_ffmpeg(args.ffmpeg, out)
    else:
        print('No --ffmpeg: qualities whose picture and sound need joining will not be offered.')
    manifest['packages'] = install_packages(out / 'packages', for_windows=not args.host)
    copy_engine(out / 'packages' / 'chaya_engine')
    shutil.copy2(HERE / 'run.py', out / 'run.py')
    shutil.copytree(HERE / 'licenses', out / 'licenses', dirs_exist_ok=True)
    if not args.host and sys.platform == 'win32':
        # Compiled once here, so the engine does not compile yt-dlp again at every lookup.
        python = out / 'python' / 'python.exe'
        subprocess.run([str(python), '-I', '-m', 'compileall', '-q', '-j', '0', str(out / 'packages')], check=True)
    (out / 'engine.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print(f'Engine ready in {out}: {manifest}')


def pins():
    """The engine packages as the phone app pins them, e.g. ['yt-dlp==2026.8.19', ...]."""
    text = PINS_FILE.read_text(encoding='utf-8')
    block = re.search(r'val enginePackages = listOf\((.*?)\)', text, re.S)
    if not block:
        raise SystemExit(f'No enginePackages list in {PINS_FILE}')
    found = re.findall(r'"([A-Za-z0-9._-]+==[^"]+)"', block.group(1))
    if not found:
        raise SystemExit(f'enginePackages in {PINS_FILE} names no packages')
    return found


def download(item):
    """Fetches [item]'s url into a temporary file and checks its SHA-256; returns the file."""
    target = Path(tempfile.mkdtemp(prefix='chaya-engine-')) / item['url'].rsplit('/', 1)[-1]
    print(f'Fetching {item["url"]}')
    with urllib.request.urlopen(item['url'], timeout=120) as response, open(target, 'wb') as file:
        shutil.copyfileobj(response, file)
    check_sha256(target, item['sha256'])
    return target


def check_sha256(path, expected):
    digest = hashlib.sha256(Path(path).read_bytes()).hexdigest()
    if digest != expected:
        raise SystemExit(f'{path} has SHA-256 {digest}, expected {expected}. Not using it.')


def unpack_python(archive, target):
    check_sha256(archive, PYTHON['sha256'])
    with zipfile.ZipFile(archive) as zipped:
        zipped.extractall(target)


def place_quickjs(program, target):
    check_sha256(program, QUICKJS['sha256'])
    shutil.copy2(program, target)


def place_ffmpeg(program, out):
    """Copies the ffmpeg build_ffmpeg.sh made, and its licence, into the engine; returns the FFmpeg version."""
    licence = program.with_name('ffmpeg-LICENSE.txt')
    if not program.is_file() or not licence.is_file():
        raise SystemExit(f'{program} or its ffmpeg-LICENSE.txt is missing. Make them with build_ffmpeg.sh.')
    shutil.copy2(program, out / program.name)
    (out / 'licenses').mkdir(exist_ok=True)
    shutil.copy2(licence, out / 'licenses' / 'ffmpeg.txt')
    found = re.search(r'FFmpeg (\S+), built from', licence.read_text(encoding='utf-8'))
    return found.group(1) if found else 'unknown'


def install_packages(target, for_windows):
    """Installs the pinned packages and what they need into [target]; returns {name: version} of all of them."""
    command = [sys.executable, '-m', 'pip', 'install', '--quiet', '--disable-pip-version-check',
               '--no-compile', '--only-binary=:all:', '--target', str(target), *pins()]
    if for_windows:
        # Wheels for the bundled Python, whatever computer this runs on.
        command[4:4] = ['--platform', 'win_amd64', '--python-version', PYTHON['version'].rsplit('.', 1)[0],
                        '--implementation', 'cp']
    subprocess.run(command, check=True)
    installed = {}
    for info in sorted(target.glob('*.dist-info')):
        name, _, version = info.name[:-len('.dist-info')].rpartition('-')
        installed[name.lower().replace('_', '-')] = version
    for leftover in ('bin', 'Scripts'):
        shutil.rmtree(target / leftover, ignore_errors=True)
    return installed


def copy_engine(target):
    shutil.copytree(ENGINE_SOURCE, target, ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))


if __name__ == '__main__':
    main()
