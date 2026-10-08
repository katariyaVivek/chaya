"""Finds out what a YouTube, Instagram, TikTok or X link points to, using yt-dlp.

Called from Kotlin (`PlatformEngine`) through Chaquopy. Only text crosses the language boundary: the
result is a JSON string, either {"media": {...}} or {"error": {"kind": ..., "detail": ...}}.
"""
import json

from yt_dlp import YoutubeDL

from . import jsc_provider  # noqa: F401  (importing registers the embedded JavaScript challenge solver)

# What the app needs to choose a quality and download it; everything else yt-dlp knows is left behind.
_FORMAT_FIELDS = (
    'format_id', 'url', 'ext', 'protocol', 'width', 'height', 'fps', 'vcodec', 'acodec',
    'tbr', 'abr', 'filesize', 'filesize_approx', 'format_note', 'language', 'language_preference',
    'dynamic_range', 'http_headers',
)
_INFO_FIELDS = (
    'id', 'title', 'uploader', 'channel', 'duration', 'webpage_url', 'extractor_key', 'is_live',
    'live_status',
)


def extract(url, cache_dir, cookie_file=None):
    """Returns a JSON string describing [url]: its title, poster, and the formats it can be saved in."""
    options = {
        'quiet': True,
        'no_warnings': True,
        'skip_download': True,
        'noplaylist': True,
        'socket_timeout': 20,
        'retries': 2,
        'extractor_retries': 2,
        'cachedir': cache_dir,
        'color': 'no_color',
    }
    if cookie_file:
        options['cookiefile'] = cookie_file

    try:
        with YoutubeDL(options) as ydl:
            info = ydl.sanitize_info(ydl.extract_info(url, download=False))
        return json.dumps({'media': _shape(info)})
    except Exception as error:  # noqa: BLE001 - every failure becomes something the app can explain
        return json.dumps({'error': {'kind': _kind_of(error), 'detail': str(error)[:400]}})


def _shape(info):
    # A carousel post (several photos or videos) arrives as a playlist; the first item is the one shown.
    if info.get('_type') == 'playlist':
        entries = [entry for entry in info.get('entries') or [] if entry]
        if not entries:
            raise ValueError('This link has nothing to download.')
        info = entries[0]

    formats = [{key: f.get(key) for key in _FORMAT_FIELDS} for f in info.get('formats') or [] if f.get('url')]
    if not formats and info.get('url'):
        # Some sites answer with one ready-to-use file and no format list.
        formats.append({key: info.get(key) for key in _FORMAT_FIELDS})

    shaped = {key: info.get(key) for key in _INFO_FIELDS}
    shaped['thumbnail'] = _best_thumbnail(info)
    shaped['formats'] = formats
    return shaped


def _best_thumbnail(info):
    if info.get('thumbnail'):
        return info['thumbnail']
    candidates = [t for t in info.get('thumbnails') or [] if t.get('url')]
    if not candidates:
        return None
    candidates.sort(key=lambda t: (t.get('preference') or 0, (t.get('width') or 0) * (t.get('height') or 0)))
    return candidates[-1]['url']


def _kind_of(error):
    text = str(error).lower()
    if "confirm you" in text and 'bot' in text:
        return 'bot_check'
    if 'private' in text:
        return 'private'
    if any(word in text for word in (
            'log in', 'login', 'sign in', 'cookies', 'authentication', 'members-only', 'members only',
            'join this channel')):
        return 'needs_login'
    if any(word in text for word in ('geo', 'not available in your country', 'region')):
        return 'geo'
    if 'unsupported url' in text:
        return 'unsupported'
    if any(word in text for word in (
            'not available', 'unavailable', 'removed', 'not found', 'does not exist', '404', 'no video')):
        return 'unavailable'
    if any(word in text for word in (
            'timed out', 'timeout', 'unable to download', 'name or service', 'connection',
            'too many requests', 'ip address is blocked')):
        return 'network'
    return 'unknown'
