"""Every picture and video in an Instagram or X post, found with gallery-dl.

yt-dlp only sees videos: it fails on a post of photos ("There is no video in this post") and keeps only the
videos of a carousel. When a post has pictures, gallery-dl lists all of it, in the post's order.
"""
import os
import threading
from urllib.parse import urlparse

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


def instagram_post(product):
    """(meta, items) for an Instagram post as yt-dlp read it without a sign-in, pictures included.

    [product] is the post's media record (Instagram's own JSON, which yt-dlp reads and then keeps only the videos
    of). A carousel lists its pieces in "carousel_media"; a single picture or video is the record itself. [meta] has
    the keys `describe` reads.
    """
    if not isinstance(product, dict):
        return {}, []
    pieces = [piece for piece in product.get('carousel_media') or [] if isinstance(piece, dict)] or [product]
    items = [item for item in (_instagram_item(piece) for piece in pieces) if item]
    user = product.get('user') if isinstance(product.get('user'), dict) else {}
    caption = product.get('caption') if isinstance(product.get('caption'), dict) else {}
    code = product.get('code')
    meta = {
        'category': 'instagram',
        'description': caption.get('text') or '',
        'username': user.get('username'),
        'post_shortcode': code,
        'post_url': f'https://www.instagram.com/p/{code}/' if code else None,
    }
    return meta, items


def _instagram_item(piece):
    videos = [v for v in piece.get('video_versions') or [] if isinstance(v, dict) and v.get('url')]
    if videos:
        best, kind, ext = _largest(videos), 'video', 'mp4'
    else:
        images = (piece.get('image_versions2') or {}).get('candidates') or []
        images = [i for i in images if isinstance(i, dict) and i.get('url')]
        if not images:
            return None
        best, kind = _largest(images), 'image'
        ext = _extension_of(best['url']) or 'jpg'
    return {
        'url': best['url'],
        'kind': kind,
        'ext': ext,
        'width': best.get('width') or None,
        'height': best.get('height') or None,
        'http_headers': {'Referer': 'https://www.instagram.com/'},
    }


def _largest(versions):
    return max(versions, key=lambda v: (v.get('width') or 0) * (v.get('height') or 0))


def _extension_of(url):
    name = urlparse(url).path.rsplit('/', 1)[-1]
    ext = name.rsplit('.', 1)[-1].lower() if '.' in name else ''
    return ext if ext in ('jpg', 'jpeg', 'png', 'webp', 'heic') else None


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
