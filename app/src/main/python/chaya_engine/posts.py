"""Every picture and video in an Instagram or X post, found with gallery-dl.

yt-dlp only sees videos: it fails on a post of photos ("There is no video in this post") and keeps only the
videos of a carousel. When a post has pictures, gallery-dl lists all of it, in the post's order.
"""
import os
import threading

from gallery_dl import config as gdl_config
from gallery_dl import extractor as gdl_extractor
from gallery_dl.extractor.message import Message

# gallery-dl keeps its settings in one global place, including which sign-in to use. Only one lookup may use it
# at a time, or a post looked up while an account is being listed could reset the listing's settings or borrow
# its sign-in.
LOCK = threading.Lock()

# gallery-dl's names for the sites whose posts can hold pictures.
POST_SITES = ('instagram', 'twitter')

_VIDEO_EXTENSIONS = ('mp4', 'mov', 'm4v', 'webm')


def items_of(url, cache_dir, cookie_file=None, find=None):
    """Returns (meta, items) for the post at [url], or None when gallery-dl has nothing for the link.

    [meta] is gallery-dl's description of the post (caption, author); each item is a dict the app reads
    (url, kind, ext, width, height, http_headers). [find] stands in for gallery-dl's lookup in tests.
    """
    with LOCK:
        _configure(cache_dir, cookie_file)
        extractor = (find or gdl_extractor.find)(url)
        if extractor is None or extractor.category not in POST_SITES:
            return None

        meta, items = None, []
        for message in extractor:
            if message[0] != Message.Url:
                continue
            file_url = plain_address(message[1], message[2])
            if file_url:
                meta = meta or message[2]
                items.append(_item(file_url, message[2]))
        return (meta or {}), items


def plain_address(file_url, info):
    """[file_url], or for a video gallery-dl would hand to yt-dlp ("ytdl:..."), its plain fallback address."""
    if not file_url.startswith('ytdl:'):
        return file_url
    return next((u for u in info.get('_fallback') or () if not u.startswith('ytdl:')), None)


def describe(meta, items):
    """The fields the app shows for a post: a title from its caption, its author, and a poster."""
    author = _author(meta)
    return {
        'id': str(meta.get('post_shortcode') or meta.get('tweet_id') or meta.get('post_id') or '') or None,
        'title': _caption(meta) or (f'Post by {author}' if author else 'Post'),
        'uploader': author,
        'thumbnail': next((item['url'] for item in items if item['kind'] == 'image'), None),
        'webpage_url': meta.get('post_url'),
        'extractor_key': {'instagram': 'Instagram', 'twitter': 'Twitter'}.get(meta.get('category')),
    }


def _item(url, info):
    ext = (info.get('extension') or 'jpg').lower()
    return {
        'url': url,
        'kind': 'video' if ext in _VIDEO_EXTENSIONS else 'image',
        'ext': ext,
        'width': info.get('width') or None,
        'height': info.get('height') or None,
        'http_headers': dict(info.get('_http_headers') or {}),
    }


def _caption(meta):
    # On X a file's "description" is the picture's alt text, so the tweet's own text comes first.
    text = (meta.get('content') or meta.get('description') or '').strip()
    first = text.splitlines()[0].strip() if text else ''
    return (first[:79].rstrip() + '…') if len(first) > 80 else (first or None)


def _author(meta):
    author = meta.get('author') if isinstance(meta.get('author'), dict) else {}
    return meta.get('username') or author.get('name') or None


def _configure(cache_dir, cookie_file):
    gdl_config.clear()
    gdl_config.set(('extractor',), 'timeout', 20)
    gdl_config.set(('extractor',), 'retries', 2)
    # Plain MP4 addresses rather than DASH manifests, which gallery-dl would need yt-dlp to join.
    gdl_config.set(('extractor', 'instagram'), 'videos', 'merged')
    gdl_config.set(('cache',), 'file', os.path.join(cache_dir, 'gallery-dl.sqlite3'))
    if cookie_file:
        gdl_config.set(('extractor',), 'cookies', cookie_file)
