"""Finds out what a YouTube, Instagram, TikTok or X link points to, using yt-dlp.

Called from Kotlin (`PlatformEngine`) through Chaquopy. Only text crosses the language boundary: the
result is a JSON string, either {"media": {...}} or {"error": {"kind": ..., "detail": ...}}.

A post with pictures (an Instagram carousel, a tweet of photos) comes back with "items", every picture and
video in it, found by gallery-dl (see `posts`), and no "formats".
"""
import json
from urllib.parse import urljoin, urlparse

from yt_dlp import YoutubeDL
from yt_dlp.extractor.instagram import InstagramIE

from . import jsc_provider  # noqa: F401  (importing registers the embedded JavaScript challenge solver)
from . import posts

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


def extract(url, cache_dir, cookie_file=None, js_runtime=None, fragments=False):
    """Returns a JSON string describing [url]: its title, poster, and the formats it can be saved in.

    [js_runtime] is the path of a QuickJS program for YouTube's challenges. Chaya for Windows ships one; the
    phone has none and solves them through `jsc_provider` instead.

    With [fragments], a DASH format also lists its pieces' addresses, as yt-dlp found them in the manifest:
    Chaya for Windows fetches those itself. The phone hands the manifest to Media3 and never asks for them.
    """
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
    if js_runtime:
        options['js_runtimes'] = {'quickjs': {'path': js_runtime}}
    instagram = _KeepingInstagramIE() if _is_instagram(url) else None

    try:
        with YoutubeDL(options) as ydl:
            if instagram:
                ydl.add_info_extractor(instagram)
            info = ydl.sanitize_info(ydl.extract_info(url, download=False))
    except Exception as error:  # noqa: BLE001 - every failure becomes something the app can explain
        # yt-dlp fails on a post of photos ("There is no video in this post"), though it read the post: the
        # pictures are in what it kept. Elsewhere (X), gallery-dl can list it.
        post = _instagram_post_or_none(instagram)
        if not post and _may_hold_pictures(url, error):
            post = _post_or_none(url, cache_dir, cookie_file)
        return json.dumps({'media': post} if post else _error(error))

    try:
        # A post with pictures in it is offered whole, pictures and videos in its order.
        post = _instagram_post_or_none(instagram)
        if post:
            return json.dumps({'media': post})
        # A carousel arrives as a playlist of its videos only; when it holds pictures too, the whole post is wanted.
        if info.get('_type') == 'playlist' and _is_post_site(url):
            post = _post_or_none(url, cache_dir, cookie_file)
            if post:
                return json.dumps({'media': post})
        return json.dumps({'media': _shape(info, fragments)})
    except Exception as error:  # noqa: BLE001
        return json.dumps(_error(error))


def _error(error):
    return {'error': {'kind': _kind_of(error), 'detail': str(error)[:400]}}


_POST_HOSTS = ('instagram.com', 'x.com', 'twitter.com')


class _KeepingInstagramIE(InstagramIE):
    """yt-dlp's Instagram extractor, keeping the post it read. yt-dlp reads a public post whole without a sign-in
    (gallery-dl cannot) but keeps only its videos; [post] keeps the rest for `posts.instagram_post`."""

    post = None

    @classmethod
    def ie_key(cls):
        # Stands in for yt-dlp's own, under its name.
        return 'Instagram'

    def _extract_product(self, product_info, *args, **kwargs):
        self.post = product_info[0] if isinstance(product_info, list) and product_info else product_info
        return super()._extract_product(product_info, *args, **kwargs)


def _instagram_post_or_none(instagram):
    """The Instagram post yt-dlp read, as the app's media with every item, when it holds a picture; else None,
    and a post of videos only goes through yt-dlp's formats and the quality choice."""
    if instagram is None or instagram.post is None:
        return None
    meta, items = posts.instagram_post(instagram.post)
    if not any(item['kind'] == 'image' for item in items):
        return None
    media = {key: None for key in _INFO_FIELDS}
    media.update(posts.describe(meta, items))
    media.update({'is_live': False, 'formats': [], 'items': items})
    return media


def _is_instagram(url):
    host = (urlparse(url).hostname or '').lower()
    return host == 'instagram.com' or host.endswith('.instagram.com')


def _is_post_site(url):
    host = (urlparse(url).hostname or '').lower()
    return any(host == site or host.endswith('.' + site) for site in _POST_HOSTS)


def _may_hold_pictures(url, error):
    return _is_post_site(url) and _kind_of(error) in ('unavailable', 'unknown')


def _post_or_none(url, cache_dir, cookie_file):
    """The whole post at [url] as the app's media, or None when gallery-dl cannot list it."""
    try:
        found = posts.items_of(url, cache_dir, cookie_file)
    except Exception:  # noqa: BLE001 - yt-dlp's own answer (or error) is used instead
        return None
    if not found or not found[1]:
        return None
    meta, items = found
    media = {key: None for key in _INFO_FIELDS}
    media.update(posts.describe(meta, items))
    media.update({'is_live': False, 'formats': [], 'items': items})
    return media


def _shape(info, fragments=False):
    # A carousel post (several photos or videos) arrives as a playlist; the first item is the one shown.
    if info.get('_type') == 'playlist':
        entries = [entry for entry in info.get('entries') or [] if entry]
        if not entries:
            raise ValueError('This link has nothing to download.')
        info = entries[0]

    formats = [_format(f, fragments) for f in info.get('formats') or [] if f.get('url')]
    if not formats and info.get('url'):
        # Some sites answer with one ready-to-use file and no format list.
        formats.append({key: info.get(key) for key in _FORMAT_FIELDS})

    shaped = {key: info.get(key) for key in _INFO_FIELDS}
    shaped['thumbnail'] = _best_thumbnail(info)
    shaped['formats'] = formats
    return shaped


def _format(f, fragments):
    shaped = {key: f.get(key) for key in _FORMAT_FIELDS}
    urls = _fragment_urls(f) if fragments else None
    if urls:
        shaped['fragments'] = urls
    return shaped


def _fragment_urls(f):
    """A DASH format's pieces in order, each as an address (its initialization section first), or None when
    yt-dlp listed none it could name."""
    pieces = f.get('fragments')
    if 'dash' not in (f.get('protocol') or '') or not isinstance(pieces, list) or not pieces:
        return None
    base = f.get('fragment_base_url')
    urls = []
    for piece in pieces:
        url = piece.get('url') if isinstance(piece, dict) else None
        if not url and isinstance(piece, dict) and piece.get('path') and base:
            url = urljoin(base, piece['path'])
        if not url:
            return None
        urls.append(url)
    return urls


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
