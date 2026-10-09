"""Solves YouTube's JavaScript challenges inside the app.

yt-dlp ships providers that run its solver script in an external runtime (Deno, Node, Bun or
QuickJS) found on the machine. A phone has none of those, so this provider hands the script to the
QuickJS library bundled with the app instead (`JsSolver` on the Kotlin side).

Importing this module is what registers the provider; no plugin folder scanning is involved.
"""
from yt_dlp.extractor.youtube.jsc._builtin.ejs import EJSBaseJCP
from yt_dlp.extractor.youtube.jsc.provider import (
    JsChallengeProviderError,
    register_preference,
    register_provider,
)


@register_provider
class ChayaQuickJSJCP(EJSBaseJCP):
    PROVIDER_NAME = 'chaya-quickjs'
    JS_RUNTIME_NAME = 'quickjs'
    BUG_REPORT_LOCATION = 'https://github.com/katariyaVivek/chaya/issues'

    def is_available(self, /) -> bool:
        # The base class asks yt-dlp for an installed JS runtime binary; this provider needs none.
        return self._available

    def _run_js_runtime(self, stdin: str, /) -> str:
        from java import jclass  # Chaquopy's bridge; only importable inside the app

        try:
            return str(jclass('com.chaya.app.platform.JsSolver').run(stdin))
        except Exception as error:  # Java exceptions arrive as Python exceptions
            raise JsChallengeProviderError(f'The embedded QuickJS failed: {error}') from error


@register_preference(ChayaQuickJSJCP)
def _chaya_quickjs_preference(provider, requests) -> int:
    # Ahead of yt-dlp's own runtimes, none of which exist on a phone.
    return 2000
