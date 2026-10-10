"""Every picture and video an Instagram or X account has posted, listed with gallery-dl for a ZIP archive.

Without a sign-in, a site shows a visitor little or nothing of an account (Instagram a few recent posts at
most, X no timeline at all), so the person can choose to list with their sign-in (a cookie file). gallery-dl
paces its requests to the site; a long profile takes minutes to list. The list is written to a file as it
grows, one JSON object per line, so the app can show progress and a pause can stop it.
"""
import json
import os
import re

from gallery_dl import exception as gdl_exception
from gallery_dl import extractor as gdl_extractor
from gallery_dl.extractor.message import Message

from . import posts

# Pages of an account that are not its posts.
_INSTAGRAM_NOT_USERS = {
    'p', 'reel', 'reels', 'tv', 'stories', 'explore', 'accounts', 'direct', 'about', 'developer', 'legal',
    'web', 'challenge', 'emails', 'privacy', 'terms', 'session', 'oauth', 'directory',
}
_X_NOT_USERS = {
    'home', 'explore', 'notifications', 'messages', 'i', 'settings', 'search', 'compose', 'login', 'logout',
    'signup', 'tos', 'privacy', 'hashtag', 'intent', 'share', 'jobs', 'download', 'account',
}


def posts_url(platform, username):
    """The page gallery-dl reads for an account's posts: Instagram's posts grid, or X's media timeline."""
    if platform == 'instagram':
        return f'https://www.instagram.com/{username}/posts/'
    if platform == 'twitter':
        return f'https://x.com/{username}/media'
    raise ValueError(f'Unsupported site: {platform}')


def list_profile(platform, username, cache_dir, cookie_file, out_path, stop_path, find=None):
    """Lists the account's pictures and videos into [out_path]; returns a JSON summary.

    Each line of [out_path] is one file: its address, kind, extension, headers, and the [name] it gets in the
    archive ("2024-05-01 Cabc123 1.jpg"), which stays the same however often the account is listed. The file is
    written as "<out_path>.partial" and renamed when the listing is complete, so a finished list is never
    mistaken for half of one. Creating [stop_path] stops the listing at the next item.
    Returns {"count": n}, {"stopped": true, "count": n} or {"error": {"kind": ..., "detail": ...}}. Without
    [cookie_file], a refusal is reported as "needs_login": a site that hides an account from visitors often says
    the account does not exist.
    """
    from .extract import _kind_of  # the same explanations as for a single link

    partial = out_path + '.partial'
    count = 0
    try:
        with posts.LOCK:
            posts._configure(cache_dir, cookie_file)
            extractor = (find or gdl_extractor.find)(posts_url(platform, username))
            if extractor is None:
                raise ValueError('Unsupported URL')
            with open(partial, 'w', encoding='utf-8') as out:
                for message in extractor:
                    if os.path.exists(stop_path):
                        return json.dumps({'stopped': True, 'count': count})
                    if message[0] != Message.Url:
                        continue
                    file_url = posts.plain_address(message[1], message[2])
                    if not file_url:
                        continue
                    item = posts._item(file_url, message[2])
                    item['name'] = entry_name(message[2], item['ext'])
                    out.write(json.dumps(item) + '\n')
                    out.flush()
                    count += 1
        os.replace(partial, out_path)
        return json.dumps({'count': count})
    except (gdl_exception.AuthRequired, gdl_exception.AuthenticationError) as error:
        return _error('needs_login', error)
    except gdl_exception.AuthorizationError as error:
        return _error('private' if cookie_file else 'needs_login', error)
    except gdl_exception.NotFoundError as error:
        return _error('unavailable' if cookie_file else 'needs_login', error)
    except Exception as error:  # noqa: BLE001 - every failure becomes something the app can explain
        kind = _kind_of(error)
        return _error('needs_login' if not cookie_file and kind in ('unavailable', 'unknown') else kind, error)


def _error(kind, error):
    return json.dumps({'error': {'kind': kind, 'detail': str(error)[:400]}})


def entry_name(info, ext):
    """"2024-05-01 Cabc123 2.jpg": the post's date, its id and the file's place in it, safe as a file name."""
    date = info.get('post_date') or info.get('date')
    day = date.strftime('%Y-%m-%d') if hasattr(date, 'strftime') else ''
    post = info.get('post_shortcode') or info.get('tweet_id') or info.get('post_id') or info.get('media_id') or ''
    number = info.get('num') or 1
    stem = ' '.join(str(part) for part in (day, post, number) if part != '')
    stem = re.sub(r'[^\w .-]', '_', stem).strip() or 'item'
    return f'{stem}.{ext}'
