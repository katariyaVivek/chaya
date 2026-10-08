import json

import pytest

from chaya_engine import extract as engine


# region error kinds


@pytest.mark.parametrize('message, kind', [
    ("Sign in to confirm you’re not a bot. Use --cookies-from-browser or --cookies for the authentication.", 'bot_check'),
    ("Private video. Sign in if you've been granted access to this video", 'private'),
    ("Instagram sent an empty media response. Check if this post is accessible in your browser without being "
     "logged-in. If it is not, then use --cookies-from-browser", 'needs_login'),
    ('Video unavailable. This video is not available in your country', 'geo'),
    ('Unsupported URL: https://example.com/x', 'unsupported'),
    ('Video unavailable', 'unavailable'),
    ('This video has been removed by the uploader', 'unavailable'),
    ('<urlopen error [Errno -2] Name or service not known>', 'network'),
    ('Unable to download webpage: HTTP Error 503', 'network'),
    ('The read operation timed out', 'network'),
    ('HTTP Error 429: Too Many Requests', 'network'),
    ('Your IP address is blocked from accessing this post', 'network'),
    ('Join this channel to get access to members-only content like this video', 'needs_login'),
    ('No video could be found in this tweet', 'unavailable'),
    ('something nobody has seen before', 'unknown'),
])
def test_failures_are_sorted_into_kinds_the_app_can_explain(message, kind):
    assert engine._kind_of(Exception(message)) == kind


# endregion

# region shaping what yt-dlp found


def test_only_what_the_app_needs_is_kept():
    shaped = engine._shape({
        'id': 'abc',
        'title': 'A video',
        'uploader': 'Someone',
        'duration': 12.5,
        'webpage_url': 'https://example.com/v',
        'extractor_key': 'Youtube',
        'is_live': False,
        'thumbnail': 'https://example.com/t.jpg',
        'automatic_captions': {'en': [{'url': 'x' * 10_000}]},
        'formats': [
            {'format_id': '18', 'url': 'https://example.com/18', 'ext': 'mp4', 'width': 640, 'height': 360,
             'vcodec': 'avc1', 'acodec': 'mp4a', 'fragments': [{'url': 'piece'}] * 5,
             'language_preference': 10, 'dynamic_range': 'SDR',
             'http_headers': {'User-Agent': 'UA'}},
            {'format_id': 'sb0', 'ext': 'mhtml'},  # a placeholder without an address
        ],
    })

    assert shaped['id'] == 'abc'
    assert shaped['title'] == 'A video'
    assert shaped['thumbnail'] == 'https://example.com/t.jpg'
    assert 'automatic_captions' not in shaped
    assert [f['format_id'] for f in shaped['formats']] == ['18']
    assert 'fragments' not in shaped['formats'][0]
    assert shaped['formats'][0]['http_headers'] == {'User-Agent': 'UA'}
    # which audio track is the original, and whether a picture is HDR, decide what the app picks
    assert shaped['formats'][0]['language_preference'] == 10
    assert shaped['formats'][0]['dynamic_range'] == 'SDR'
    json.dumps(shaped)  # must be plain data all the way down


def test_a_carousel_shows_its_first_item():
    carousel = {
        '_type': 'playlist',
        'entries': [
            None,
            {'id': 'one', 'title': 'First', 'formats': [{'format_id': 'v', 'url': 'https://example.com/1.mp4'}]},
            {'id': 'two', 'title': 'Second'},
        ],
    }

    assert engine._shape(carousel)['id'] == 'one'


def test_an_empty_carousel_is_an_error():
    with pytest.raises(ValueError):
        engine._shape({'_type': 'playlist', 'entries': [None]})


def test_a_site_that_answers_with_one_ready_file_and_no_format_list():
    shaped = engine._shape({'id': 'x', 'title': 't', 'url': 'https://example.com/x.mp4', 'ext': 'mp4'})

    assert [f['url'] for f in shaped['formats']] == ['https://example.com/x.mp4']
    assert shaped['formats'][0]['ext'] == 'mp4'


def test_the_poster_is_the_named_thumbnail_or_else_the_largest():
    assert engine._best_thumbnail({'thumbnail': 'named', 'thumbnails': [{'url': 'other'}]}) == 'named'
    assert engine._best_thumbnail({'thumbnails': [
        {'url': 'small', 'width': 10, 'height': 10},
        {'url': 'big', 'width': 100, 'height': 100},
        {'url': 'broken'},
    ]}) == 'big'
    assert engine._best_thumbnail({'thumbnails': [{'url': 'low', 'preference': -2}, {'url': 'high', 'preference': 1}]}) == 'high'
    assert engine._best_thumbnail({}) is None


# endregion

# region the envelope handed back to Kotlin


class _FakeYoutubeDL:
    options = None
    result = None

    def __init__(self, options):
        type(self).options = options

    def __enter__(self):
        return self

    def __exit__(self, *exc_info):
        return False

    def extract_info(self, url, download):
        assert download is False
        return type(self).result

    def sanitize_info(self, info):
        return info


def test_a_found_video_comes_back_as_media(monkeypatch):
    _FakeYoutubeDL.result = {'id': 'x', 'title': 'T', 'formats': [{'format_id': 'a', 'url': 'https://example.com/a'}]}
    monkeypatch.setattr(engine, 'YoutubeDL', _FakeYoutubeDL)

    answer = json.loads(engine.extract('https://example.com/v', '/cache'))

    assert answer['media']['title'] == 'T'
    assert answer['media']['formats'][0]['url'] == 'https://example.com/a'
    assert 'error' not in answer


def test_extraction_only_looks_and_uses_the_given_cache(monkeypatch):
    _FakeYoutubeDL.result = {'id': 'x', 'title': 'T'}
    monkeypatch.setattr(engine, 'YoutubeDL', _FakeYoutubeDL)

    engine.extract('https://example.com/v', '/cache')

    assert _FakeYoutubeDL.options['skip_download'] is True
    assert _FakeYoutubeDL.options['noplaylist'] is True
    assert _FakeYoutubeDL.options['cachedir'] == '/cache'
    assert 'cookiefile' not in _FakeYoutubeDL.options


def test_the_browsers_cookies_are_handed_over_when_given(monkeypatch):
    _FakeYoutubeDL.result = {'id': 'x', 'title': 'T'}
    monkeypatch.setattr(engine, 'YoutubeDL', _FakeYoutubeDL)

    engine.extract('https://example.com/v', '/cache', '/cookies.txt')

    assert _FakeYoutubeDL.options['cookiefile'] == '/cookies.txt'


def test_a_failure_comes_back_as_an_error_with_its_kind(monkeypatch):
    class Failing(_FakeYoutubeDL):
        def extract_info(self, url, download):
            raise RuntimeError('Private video. Sign in if you\'ve been granted access to this video')

    monkeypatch.setattr(engine, 'YoutubeDL', Failing)

    answer = json.loads(engine.extract('https://example.com/v', '/cache'))

    assert answer['error']['kind'] == 'private'
    assert 'Private video' in answer['error']['detail']
    assert 'media' not in answer


def test_a_very_long_failure_message_is_cut(monkeypatch):
    class Failing(_FakeYoutubeDL):
        def extract_info(self, url, download):
            raise RuntimeError('x' * 5_000)

    monkeypatch.setattr(engine, 'YoutubeDL', Failing)

    answer = json.loads(engine.extract('https://example.com/v', '/cache'))

    assert len(answer['error']['detail']) <= 400


# endregion
