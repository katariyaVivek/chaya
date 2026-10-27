"""The engine as a program, the way Chaya for Windows runs it: arguments in, one line of JSON out."""
import json
import stat
import sys

from chaya_engine import cli


def test_extract_prints_the_engines_answer_with_its_arguments(monkeypatch, capsys):
    seen = {}

    def fake_extract(url, cache_dir, cookie_file=None, js_runtime=None, fragments=False, referer=None):
        seen.update(url=url, cache_dir=cache_dir, cookie_file=cookie_file, js_runtime=js_runtime, fragments=fragments,
                    referer=referer)
        return json.dumps({'media': {'title': 'Ünïcödé'}})

    monkeypatch.setattr('chaya_engine.extract.extract', fake_extract)

    code = cli.main(['extract', 'https://youtu.be/x', '--cache', '/cache', '--js-runtime', '/engine/qjs.exe',
                     '--fragments', '--referer', 'https://example.com/watch'])

    assert code == 0
    assert seen == {'url': 'https://youtu.be/x', 'cache_dir': '/cache', 'cookie_file': None,
                    'js_runtime': '/engine/qjs.exe', 'fragments': True, 'referer': 'https://example.com/watch'}
    last_line = capsys.readouterr().out.strip().splitlines()[-1]
    assert json.loads(last_line) == {'media': {'title': 'Ünïcödé'}}


def test_a_sign_in_is_passed_only_when_given(monkeypatch, capsys):
    seen = {}
    monkeypatch.setattr('chaya_engine.extract.extract',
                        lambda url, cache_dir, cookie_file=None, js_runtime=None, fragments=False, referer=None:
                        seen.update(c=cookie_file, f=fragments) or '{}')

    cli.main(['extract', 'https://x.com/a/status/1', '--cache', '/c', '--cookies', '/tmp/cookies.txt'])

    assert seen == {'c': '/tmp/cookies.txt', 'f': False}


def test_profile_lists_the_account_with_its_arguments_and_prints_the_summary(monkeypatch, capsys):
    seen = {}

    def fake_list(platform, username, cache_dir, cookie_file, out_path, stop_path, find=None):
        seen.update(platform=platform, username=username, cache_dir=cache_dir, cookie_file=cookie_file,
                    out_path=out_path, stop_path=stop_path)
        return json.dumps({'count': 3})

    monkeypatch.setattr('chaya_engine.profiles.list_profile', fake_list)

    code = cli.main(['profile', 'instagram', 'some.one', '--cache', '/cache', '--out', '/tmp/list.jsonl',
                     '--stop', '/tmp/stop'])

    assert code == 0
    assert seen == {'platform': 'instagram', 'username': 'some.one', 'cache_dir': '/cache', 'cookie_file': None,
                    'out_path': '/tmp/list.jsonl', 'stop_path': '/tmp/stop'}
    assert json.loads(capsys.readouterr().out.strip().splitlines()[-1]) == {'count': 3}


def test_a_profile_is_listed_with_a_sign_in_only_when_given(monkeypatch, capsys):
    seen = {}
    monkeypatch.setattr('chaya_engine.profiles.list_profile',
                        lambda p, u, cache, cookies, out, stop, find=None: seen.update(c=cookies) or '{}')

    cli.main(['profile', 'twitter', 'someone', '--cache', '/c', '--out', '/o', '--stop', '/s',
              '--cookies', '/tmp/cookies.txt'])

    assert seen == {'c': '/tmp/cookies.txt'}


def test_selftest_reports_the_engines_versions(capsys):
    assert cli.main(['selftest']) == 0

    answer = json.loads(capsys.readouterr().out.strip().splitlines()[-1])
    assert answer['yt_dlp']
    assert answer['gallery_dl']
    assert answer['post_links_known'] is True
    assert 'js_runtime' not in answer


def test_selftest_says_when_the_quickjs_program_is_missing(tmp_path, capsys):
    cli.main(['selftest', '--js-runtime', str(tmp_path / 'qjs.exe')])

    answer = json.loads(capsys.readouterr().out.strip().splitlines()[-1])
    assert answer['js_runtime'] == {'found': False}


def test_selftest_reads_the_quickjs_programs_version(tmp_path, capsys):
    if sys.platform == 'win32':
        return  # the stand-in below is a shell script
    qjs = tmp_path / 'qjs'
    # What `qjs --help` prints first, for the QuickJS-ng release Chaya for Windows ships.
    qjs.write_text('#!/bin/sh\necho "QuickJS-ng version 0.17.0"\nexit 1\n')
    qjs.chmod(qjs.stat().st_mode | stat.S_IEXEC)

    cli.main(['selftest', '--js-runtime', str(qjs)])

    answer = json.loads(capsys.readouterr().out.strip().splitlines()[-1])
    assert answer['js_runtime'] == {'found': True, 'name': 'quickjs-ng', 'version': '0.17.0', 'supported': True}
