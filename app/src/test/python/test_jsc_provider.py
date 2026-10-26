"""Guards the one place chaya reaches into yt-dlp's internals: the provider that solves YouTube's
JavaScript challenges with the QuickJS engine inside the app. If a yt-dlp release moves these
internals, this fails here instead of on a phone."""
import sys

import pytest

from yt_dlp.extractor.youtube.jsc._registry import _jsc_preferences, _jsc_providers
from yt_dlp.extractor.youtube.jsc.provider import JsChallengeProviderError

from chaya_engine import jsc_provider


def test_the_provider_is_registered_under_its_class_name():
    assert _jsc_providers.value['ChayaQuickJS'] is jsc_provider.ChayaQuickJSJCP


def test_the_provider_is_ranked_ahead_of_yt_dlps_own_runtimes():
    # yt-dlp adds up every registered preference for a provider and tries the highest first;
    # its own Deno/Node/Bun/QuickJS providers register none, so any positive rank puts this one ahead.
    rank = sum(preference(_provider(), []) for preference in _jsc_preferences.value)

    assert rank == 2000


def test_inside_the_app_the_provider_needs_no_runtime_binary(fake_java):
    fake_java({})

    assert _provider().is_available() is True


def test_outside_the_app_the_provider_stands_aside_for_yt_dlps_own(monkeypatch):
    # Chaya for Windows: no Kotlin to run the script, and a QuickJS program of its own instead.
    monkeypatch.setitem(sys.modules, 'java', None)

    assert _provider().is_available() is False


def test_the_script_goes_to_kotlin_and_what_it_printed_comes_back(fake_java):
    seen = []

    class FakeSolver:
        @staticmethod
        def run(script):
            seen.append(script)
            return '{"type": "result", "responses": []}\n'

    fake_java({'com.chaya.app.platform.JsSolver': FakeSolver})

    answer = _provider()._run_js_runtime('console.log(1)')

    assert seen == ['console.log(1)']
    assert answer == '{"type": "result", "responses": []}\n'


def test_a_failure_in_kotlin_becomes_a_provider_error(fake_java):
    class BrokenSolver:
        @staticmethod
        def run(script):
            raise RuntimeError('QuickJsException: SyntaxError')

    fake_java({'com.chaya.app.platform.JsSolver': BrokenSolver})

    with pytest.raises(JsChallengeProviderError, match='SyntaxError'):
        _provider()._run_js_runtime('not javascript')


def _provider():
    # Built without __init__, which needs a running yt-dlp extractor; the pieces tested here don't use it.
    provider = jsc_provider.ChayaQuickJSJCP.__new__(jsc_provider.ChayaQuickJSJCP)
    provider._available = True
    return provider
