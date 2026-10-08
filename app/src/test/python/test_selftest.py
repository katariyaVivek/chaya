import json

from chaya_engine import selftest


def test_status_reports_what_the_engine_runs_on_and_that_the_solver_is_registered():
    status = json.loads(selftest.status())

    assert status['provider_registered'] is True
    assert status['yt_dlp'].startswith('20')
    assert status['yt_dlp_ejs'].count('.') == 2


def test_the_solver_check_hands_yt_dlps_real_scripts_to_kotlin(fake_java):
    received = []

    class FakeSolver:
        @staticmethod
        def run(script):
            received.append(script)
            return '{"type": "error", "error": "nothing to solve"}\n'

    fake_java({'com.chaya.app.platform.JsSolver': FakeSolver})

    result = json.loads(selftest.solver_check())

    script = received[0]
    # yt-dlp's two solver scripts, wired together the way its own runtimes receive them, then one call.
    assert 'Object.assign(globalThis, lib);' in script
    assert 'jsc(' in script
    assert script.rstrip().endswith(');')
    assert len(script) > 100_000  # the real bundle, not a stub
    assert result['type'] == 'error'
    assert result['script_bytes'] == len(script)
    assert result['millis'] >= 0


def test_the_solver_check_passes_the_player_it_is_given(fake_java):
    received = []

    class FakeSolver:
        @staticmethod
        def run(script):
            received.append(script)
            return '{"type": "result", "responses": []}\n'

    fake_java({'com.chaya.app.platform.JsSolver': FakeSolver})

    selftest.solver_check(player='var marker_from_the_test = 42;')

    assert 'marker_from_the_test' in received[0]


def test_the_player_benchmark_fetches_the_current_player_and_times_the_solver_on_it(fake_java, monkeypatch):
    import io
    import urllib.request

    fetched = []

    def fake_urlopen(url, timeout=None):
        fetched.append(url)
        if url.endswith('iframe_api'):
            return io.BytesIO(b'var url = "https://www.youtube.com/s/player/a1b2c3d4/www-widgetapi.vflset/www-widgetapi.js";')
        return io.BytesIO(b'var player_marker = 7;')

    received = []

    class FakeSolver:
        @staticmethod
        def run(script):
            received.append(script)
            return '{"type": "result", "responses": [], "preprocessed_player": "..."}\n'

    monkeypatch.setattr(urllib.request, 'urlopen', fake_urlopen)
    fake_java({'com.chaya.app.platform.JsSolver': FakeSolver})

    result = json.loads(selftest.player_benchmark())

    assert fetched == [
        'https://www.youtube.com/iframe_api',
        'https://www.youtube.com/s/player/a1b2c3d4/player_ias.vflset/en_US/base.js',
    ]
    assert result['player_id'] == 'a1b2c3d4'
    assert result['player_bytes'] == len('var player_marker = 7;')
    assert result['type'] == 'result'
    assert 'player_marker' in received[0]


def test_the_player_benchmark_says_so_when_the_page_has_no_player(monkeypatch):
    import io
    import urllib.request

    import pytest

    monkeypatch.setattr(urllib.request, 'urlopen', lambda url, timeout=None: io.BytesIO(b'nothing useful'))

    with pytest.raises(RuntimeError, match='No player id'):
        selftest.player_benchmark()
