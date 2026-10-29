"""Checks the speech models Chaya pins (desktop/core/src/speech/models.json) against Hugging Face's own list of
whisper.cpp's models: each file's size, and the SHA-256 its storage names it by. CI runs it, so a pin that is wrong
fails the build rather than every person's first fetch.

    python desktop/engine/check_models.py
"""
import json
import sys
import urllib.request
from pathlib import Path

PINS = Path(__file__).resolve().parents[1] / 'core' / 'src' / 'speech' / 'models.json'
LISTING = 'https://huggingface.co/api/models/ggerganov/whisper.cpp/tree/main'


def listed():
    """{file: (size, sha256)} for every file Hugging Face lists for whisper.cpp's models."""
    with urllib.request.urlopen(LISTING, timeout=60) as answer:
        entries = json.load(answer)
    return {e['path']: (e.get('lfs', {}).get('size', e.get('size')), e.get('lfs', {}).get('oid')) for e in entries}


def problems(pins, found):
    """What differs between the pins and what Hugging Face lists, one line each."""
    out = []
    for model in pins:
        size, sha256 = found.get(model['file'], (None, None))
        if size is None:
            out.append(f"{model['file']}: not listed on Hugging Face")
            continue
        if size != model['size']:
            out.append(f"{model['file']}: size {size}, pinned {model['size']}")
        if sha256 != model['sha256']:
            out.append(f"{model['file']}: SHA-256 {sha256}, pinned {model['sha256']}")
    return out


def main():
    pins = json.loads(PINS.read_text(encoding='utf-8'))
    wrong = problems(pins, listed())
    for line in wrong:
        print(line)
    if wrong:
        print(f'{len(wrong)} pin(s) in {PINS.name} differ from Hugging Face.')
        return 1
    print(f'All {len(pins)} speech models are pinned as Hugging Face lists them.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
