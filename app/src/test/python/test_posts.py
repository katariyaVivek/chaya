"""Posts with pictures: what gallery-dl lists, and when the engine asks it instead of yt-dlp.

gallery-dl's lookup is replaced by a stand-in extractor, so nothing here touches the network.
"""
import json

import pytest
from gallery_dl.extractor.message import Message

from chaya_engine import extract as engine
from chaya_engine import posts


class FakeExtractor:
    """Yields the messages gallery-dl's real extractor would, for one post."""

    def __init__(self, category, files, meta=None):
        self.category = category
        self._files = files
        self._meta = meta or {}

    def __iter__(self):
        yield Message.Directory, '', dict(self._meta)
        for url, extra in self._files:
            yield Message.Url, url, {**self._meta, **extra}


CAROUSEL = FakeExtractor('instagram', [
    ('https://scontent.cdninstagram.com/one.jpg', {'extension': 'jpg', 'width': 1080, 'height': 1350}),
    ('https://scontent.cdninstagram.com/two.mp4', {'extension': 'mp4', 'width': 720, 'height': 1280,
                                                   '_http_headers': {'Referer': 'https://www.instagram.com/'}}),
    ('https://scontent.cdninstagram.com/three.webp', {'extension': 'webp'}),
], meta={'category': 'instagram', 'post_shortcode': 'Cabc123', 'username': 'someone',
         'description': 'Sunset at the lake\nsecond line #tags', 'post_url': 'https://www.instagram.com/p/Cabc123/'})


def test_every_item_of_a_post_is_listed_in_order(tmp_path):
    meta, items = posts.items_of('https://www.instagram.com/p/Cabc123/', str(tmp_path), find=lambda url: CAROUSEL)

    assert [item['url'].rsplit('/', 1)[1] for item in items] == ['one.jpg', 'two.mp4', 'three.webp']
    assert [item['kind'] for item in items] == ['image', 'video', 'image']
    assert items[0]['width'] == 1080 and items[0]['height'] == 1350
    assert items[1]['http_headers'] == {'Referer': 'https://www.instagram.com/'}
    assert meta['username'] == 'someone'


def test_a_post_is_described_by_its_caption_author_and_first_picture(tmp_path):
    meta, items = posts.items_of('https://www.instagram.com/p/Cabc123/', str(tmp_path), find=lambda url: CAROUSEL)

    described = posts.describe(meta, items)

    assert described['title'] == 'Sunset at the lake'
    assert described['uploader'] == 'someone'
    assert described['thumbnail'] == 'https://scontent.cdninstagram.com/one.jpg'
    assert described['id'] == 'Cabc123'
    assert described['extractor_key'] == 'Instagram'


def test_a_tweet_is_titled_by_its_text_not_a_picture_alt_text(tmp_path):
    tweet = FakeExtractor('twitter', [('https://pbs.twimg.com/media/a.jpg?name=orig', {
        'extension': 'jpg', 'description': 'alt text for the picture'})],
        meta={'category': 'twitter', 'tweet_id': 42, 'content': 'Look at this', 'author': {'name': 'handle'}})
    meta, items = posts.items_of('https://x.com/handle/status/42', str(tmp_path), find=lambda url: tweet)

    described = posts.describe(meta, items)

    assert described['title'] == 'Look at this'
    assert described['uploader'] == 'handle'
    assert described['id'] == '42'


def test_a_post_without_a_caption_is_named_after_its_author():
    assert posts.describe({'username': 'someone'}, [])['title'] == 'Post by someone'
    assert posts.describe({}, [])['title'] == 'Post'


def test_a_long_caption_is_cut_to_one_short_line():
    assert posts._caption({'description': 'x' * 200}) == 'x' * 79 + '…'
    assert posts._caption({'description': ''}) is None


def test_a_video_only_handed_to_yt_dlp_falls_back_to_its_plain_address(tmp_path):
    post = FakeExtractor('instagram', [
        ('ytdl:https://www.instagram.com/p/X/1.mp4', {'extension': 'mp4', '_fallback': ('https://cdn/x.mp4',)}),
        ('ytdl:https://www.instagram.com/p/X/2.mp4', {'extension': 'mp4'}),
    ])
    _, items = posts.items_of('https://www.instagram.com/p/X/', str(tmp_path), find=lambda url: post)

    assert [item['url'] for item in items] == ['https://cdn/x.mp4']


def test_other_sites_are_left_to_yt_dlp(tmp_path):
    assert posts.items_of('https://example.com/a', str(tmp_path), find=lambda url: None) is None
    other = FakeExtractor('reddit', [('https://i.redd.it/a.jpg', {'extension': 'jpg'})])
    assert posts.items_of('https://reddit.com/r/a', str(tmp_path), find=lambda url: other) is None


def test_the_sign_in_reaches_gallery_dl_and_its_cache_stays_in_the_app(tmp_path):
    from gallery_dl import config

    posts.items_of('https://example.com/a', str(tmp_path), cookie_file='/tmp/cookies.txt', find=lambda url: None)

    assert config.get(('extractor',), 'cookies') == '/tmp/cookies.txt'
    assert config.get(('cache',), 'file') == str(tmp_path / 'gallery-dl.sqlite3')
    assert config.get(('extractor', 'instagram'), 'videos') == 'merged'


# region when the engine asks gallery-dl


class FailingYoutubeDL:
    """Stands in for yt-dlp failing the way it does on a post of photos."""

    message = 'There is no video in this post'

    def __init__(self, options):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def extract_info(self, url, download):
        raise Exception(self.message)

    def sanitize_info(self, info):
        return info


@pytest.fixture
def ytdlp_fails(monkeypatch):
    # A class of its own per test, so a test that changes the message leaves the others alone.
    failing = type('Failing', (FailingYoutubeDL,), {})
    monkeypatch.setattr(engine, 'YoutubeDL', failing)
    return failing


def test_a_post_of_photos_that_yt_dlp_refuses_comes_back_as_items(tmp_path, monkeypatch, ytdlp_fails):
    monkeypatch.setattr(posts.gdl_extractor, 'find', lambda url: CAROUSEL)

    result = json.loads(engine.extract('https://www.instagram.com/p/Cabc123/', str(tmp_path)))

    media = result['media']
    assert len(media['items']) == 3
    assert media['formats'] == []
    assert media['title'] == 'Sunset at the lake'
    assert media['is_live'] is False


def test_when_gallery_dl_finds_nothing_yt_dlp_s_error_is_kept(tmp_path, monkeypatch, ytdlp_fails):
    monkeypatch.setattr(posts.gdl_extractor, 'find', lambda url: FakeExtractor('instagram', []))

    result = json.loads(engine.extract('https://www.instagram.com/p/gone/', str(tmp_path)))

    assert result['error']['kind'] == 'unavailable'


def test_a_sign_in_wall_is_not_handed_to_gallery_dl(tmp_path, monkeypatch, ytdlp_fails):
    ytdlp_fails.message = 'This content is only available for registered users who log in'
    asked = []
    monkeypatch.setattr(posts.gdl_extractor, 'find', lambda url: asked.append(url))

    result = json.loads(engine.extract('https://www.instagram.com/p/private/', str(tmp_path)))

    assert result['error']['kind'] == 'needs_login'
    assert asked == []


def test_youtube_never_asks_gallery_dl(tmp_path, monkeypatch, ytdlp_fails):
    ytdlp_fails.message = 'Video unavailable'
    asked = []
    monkeypatch.setattr(posts.gdl_extractor, 'find', lambda url: asked.append(url))

    engine.extract('https://www.youtube.com/watch?v=x', str(tmp_path))

    assert asked == []


def test_a_carousel_yt_dlp_saw_as_videos_is_listed_whole(tmp_path, monkeypatch):
    class CarouselYoutubeDL(FailingYoutubeDL):
        def extract_info(self, url, download):
            return {'_type': 'playlist', 'entries': [{'id': 'v', 'title': 'clip', 'url': 'https://cdn/v.mp4'}]}

    monkeypatch.setattr(engine, 'YoutubeDL', CarouselYoutubeDL)
    monkeypatch.setattr(posts.gdl_extractor, 'find', lambda url: CAROUSEL)

    media = json.loads(engine.extract('https://www.instagram.com/p/Cabc123/', str(tmp_path)))['media']

    assert [item['kind'] for item in media['items']] == ['image', 'video', 'image']


# endregion
