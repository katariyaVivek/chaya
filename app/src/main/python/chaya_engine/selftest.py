"""Quick checks that the engine's pieces fit together on this phone.

Used by the on-device tests, and available to a diagnostics screen. Each function returns a JSON string,
so only text crosses to Kotlin, like the engine's own calls.
"""
import json
import time


def status():
    """Versions of what the engine runs on, whether its YouTube challenge solver is registered, and whether
    gallery-dl (pictures in posts) recognises Instagram and X post links. Nothing here uses the network.
    A set of packages fetched from PyPI is trusted only once this reports its versions (EngineSets)."""
    from gallery_dl import extractor as gdl_extractor
    from gallery_dl.version import __version__ as gallery_dl_version
    from yt_dlp.extractor.youtube.jsc._registry import _jsc_providers
    from yt_dlp.version import __version__ as yt_dlp_version
    import yt_dlp_ejs

    from . import jsc_provider  # noqa: F401  (importing registers the provider)
    from . import paths

    post_links = ('https://www.instagram.com/p/C0abcDEFghi/', 'https://x.com/someone/status/1234567890')
    return json.dumps({
        'yt_dlp': yt_dlp_version,
        'yt_dlp_ejs': yt_dlp_ejs.version,
        'provider_registered': 'ChayaQuickJS' in _jsc_providers.value,
        'gallery_dl': gallery_dl_version,
        'post_links_known': all(gdl_extractor.find(link) is not None for link in post_links),
        # The updated copies in use (EngineSets), or nothing while the app's own copy is.
        'files': paths.active(),
    })


def solver_check(player='var player = { value: 1 };'):
    """Runs yt-dlp's real challenge-solver scripts in the embedded QuickJS, on a made-up [player].

    A made-up player has nothing to solve, so the solver rejects it with an error of its own; that still proves
    the whole bundle loads and runs on this phone, and how long that takes. The solver throws rather than
    answers when a player is not shaped like YouTube's ("unexpected structure"), so a throw is reported as an
    "error" answer carrying its message. Returns the answer's type ("result" or "error"), the time in
    milliseconds, and the script's size.
    """
    from java import jclass  # Chaquopy's bridge; only importable inside the app
    import yt_dlp_ejs.yt.solver as solver

    data = {'type': 'player', 'player': player, 'requests': [], 'output_preprocessed': True}
    script = (
        f'{solver.lib()}\n'
        'Object.assign(globalThis, lib);\n'
        f'{solver.core()}\n'
        'let answer;\n'
        f'try {{ answer = jsc({json.dumps(data)}); }}\n'
        "catch (e) { answer = { type: 'error', error: String(e && e.message || e) }; }\n"
        'console.log(JSON.stringify(answer));\n'
    )
    started = time.monotonic()
    printed = str(jclass('com.chaya.app.platform.JsSolver').run(script))
    answer = json.loads(printed)
    return json.dumps({
        'type': answer.get('type'),
        'millis': round((time.monotonic() - started) * 1000),
        'script_bytes': len(script),
        'error': answer.get('error'),
    })


def player_benchmark():
    """Has the solver preprocess YouTube's current player script, to learn what that costs on this phone.

    The player is a multi-megabyte script, and parsing it in an interpreter is the heaviest thing the engine
    does, so this is the number that decides whether YouTube is practical. Only public script files are
    fetched. Needs the network. A "result" answer also means the pinned solver still understands today's
    player; an "error" says it does not. Returns [solver_check]'s fields plus the player's id and size.
    """
    import re
    import urllib.request

    with urllib.request.urlopen('https://www.youtube.com/iframe_api', timeout=30) as response:
        iframe_api = response.read().decode('utf-8', 'replace')
    # The page writes the address with escaped slashes (https:\/\/www.youtube.com\/s\/player\/…); this is the
    # pattern yt-dlp itself uses on it.
    found = re.search(r'player\\?/([0-9a-fA-F]{8})\\?/', iframe_api)
    if not found:
        raise RuntimeError('No player id in the iframe API')
    player_id = found.group(1)
    url = f'https://www.youtube.com/s/player/{player_id}/player_ias.vflset/en_US/base.js'
    with urllib.request.urlopen(url, timeout=60) as response:
        player = response.read().decode('utf-8', 'replace')

    result = json.loads(solver_check(player))
    result['player_id'] = player_id
    result['player_bytes'] = len(player)
    return json.dumps(result)
