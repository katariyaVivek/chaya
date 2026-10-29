"""The script that puts the Windows engine together: what it reads, what it refuses, and the folder it leaves."""
import json
import zipfile

import pytest

import prepare


def test_the_engine_uses_the_versions_the_phone_pins():
    pins = prepare.pins()

    assert any(pin.startswith('yt-dlp==') for pin in pins)
    assert any(pin.startswith('yt-dlp-ejs==') for pin in pins)
    assert any(pin.startswith('gallery-dl==') for pin in pins)
    assert all('==' in pin for pin in pins)


def test_a_download_that_does_not_match_its_checksum_is_refused(tmp_path):
    file = tmp_path / 'python.zip'
    file.write_bytes(b'not the file python.org published')

    with pytest.raises(SystemExit, match='Not using it'):
        prepare.unpack_python(file, tmp_path / 'python')
    assert not (tmp_path / 'python').exists()

    with pytest.raises(SystemExit, match='Not using it'):
        prepare.place_quickjs(file, tmp_path / 'qjs.exe')
    assert not (tmp_path / 'qjs.exe').exists()


def test_a_file_that_matches_its_checksum_is_used(tmp_path, monkeypatch):
    archive = tmp_path / 'python.zip'
    with zipfile.ZipFile(archive, 'w') as zipped:
        zipped.writestr('python.exe', 'MZ')
    monkeypatch.setitem(prepare.PYTHON, 'sha256', prepare.hashlib.sha256(archive.read_bytes()).hexdigest())

    prepare.unpack_python(archive, tmp_path / 'python')

    assert (tmp_path / 'python' / 'python.exe').read_text() == 'MZ'


def test_the_engine_folder_holds_the_launcher_the_engine_and_what_is_in_it(tmp_path, monkeypatch):
    def fake_install(target, for_windows):
        assert for_windows is False
        target.mkdir(parents=True)
        return {'yt-dlp': '2026.8.19'}

    monkeypatch.setattr(prepare, 'install_packages', fake_install)
    out = tmp_path / 'engine'

    prepare.main(['--host', '--out', str(out)])

    assert (out / 'run.py').is_file()
    assert (out / 'licenses' / 'quickjs-ng.txt').is_file()
    engine = out / 'packages' / 'chaya_engine'
    assert (engine / 'cli.py').is_file() and (engine / 'extract.py').is_file()
    assert not list(engine.rglob('__pycache__'))
    assert json.loads((out / 'engine.json').read_text()) == {
        'packages': {'yt-dlp': '2026.8.19'}, 'python': None, 'quickjs': None, 'ffmpeg': None, 'whisper': None}


def test_an_engine_prepared_again_starts_from_an_empty_folder(tmp_path, monkeypatch):
    monkeypatch.setattr(prepare, 'install_packages', lambda target, for_windows: target.mkdir(parents=True) or {})
    out = tmp_path / 'engine'
    out.mkdir()
    (out / 'left-over.txt').write_text('from an older engine')

    prepare.main(['--host', '--out', str(out)])

    assert not (out / 'left-over.txt').exists()


def test_the_ffmpeg_given_goes_in_with_its_licence(tmp_path, monkeypatch):
    monkeypatch.setattr(prepare, 'install_packages', lambda target, for_windows: target.mkdir(parents=True) or {})
    built = tmp_path / 'built'
    built.mkdir()
    (built / 'ffmpeg.exe').write_bytes(b'MZ')
    (built / 'ffmpeg-LICENSE.txt').write_text('ffmpeg (ffmpeg.exe), shipped with Chaya for Windows.\n'
                                              'FFmpeg n8.1.3, built from https://github.com/FFmpeg/FFmpeg\n')
    out = tmp_path / 'engine'

    prepare.main(['--host', '--out', str(out), '--ffmpeg', str(built / 'ffmpeg.exe')])

    assert (out / 'ffmpeg.exe').read_bytes() == b'MZ'
    assert 'n8.1.3' in (out / 'licenses' / 'ffmpeg.txt').read_text()
    assert json.loads((out / 'engine.json').read_text())['ffmpeg'] == 'n8.1.3'


def test_the_whisper_given_goes_in_with_its_licence(tmp_path, monkeypatch):
    monkeypatch.setattr(prepare, 'install_packages', lambda target, for_windows: target.mkdir(parents=True) or {})
    built = tmp_path / 'built'
    built.mkdir()
    (built / 'whisper-cli.exe').write_bytes(b'MZ')
    (built / 'whisper-LICENSE.txt').write_text('whisper-cli (whisper-cli.exe), shipped with Chaya for Windows.\n'
                                               'whisper.cpp v1.9.5, built from https://github.com/ggml-org/whisper.cpp\n')
    out = tmp_path / 'engine'

    prepare.main(['--host', '--out', str(out), '--whisper', str(built / 'whisper-cli.exe')])

    assert (out / 'whisper-cli.exe').read_bytes() == b'MZ'
    assert 'v1.9.5' in (out / 'licenses' / 'whisper.txt').read_text()
    assert json.loads((out / 'engine.json').read_text())['whisper'] == 'v1.9.5'


def test_a_whisper_without_its_licence_is_refused(tmp_path):
    (tmp_path / 'whisper-cli.exe').write_bytes(b'MZ')

    with pytest.raises(SystemExit, match='whisper-LICENSE'):
        prepare.place_program(tmp_path / 'whisper-cli.exe', tmp_path, 'whisper', 'x')


def test_an_ffmpeg_without_its_licence_is_refused(tmp_path):
    (tmp_path / 'ffmpeg.exe').write_bytes(b'MZ')

    with pytest.raises(SystemExit, match='LICENSE'):
        prepare.place_ffmpeg(tmp_path / 'ffmpeg.exe', tmp_path)


def test_a_model_pinned_unlike_hugging_faces_list_is_named():
    import check_models

    pins = [{'file': 'ggml-tiny.bin', 'size': 10, 'sha256': 'a' * 64},
            {'file': 'ggml-small.bin', 'size': 20, 'sha256': 'b' * 64},
            {'file': 'ggml-gone.bin', 'size': 30, 'sha256': 'c' * 64}]
    found = {'ggml-tiny.bin': (10, 'a' * 64), 'ggml-small.bin': (21, 'd' * 64)}

    assert check_models.problems(pins, found) == [
        'ggml-small.bin: size 21, pinned 20',
        f"ggml-small.bin: SHA-256 {'d' * 64}, pinned {'b' * 64}",
        'ggml-gone.bin: not listed on Hugging Face',
    ]
    assert check_models.problems(pins[:1], found) == []
