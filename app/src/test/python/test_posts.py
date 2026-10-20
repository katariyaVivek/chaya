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

    def add_info_extractor(self, ie):
        pass

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


# region an Instagram post as yt-dlp read it without a sign-in

def _image(url, width, height):
    return {'url': url, 'width': width, 'height': height}


INSTAGRAM_CAROUSEL = {
    'code': 'DAbc123',
    'caption': {'text': 'Sunset at the pier\nsecond line'},
    'user': {'username': 'pier_photos'},
    'carousel_media': [
        {'image_versions2': {'candidates': [
            _image('https://scontent.cdninstagram.com/v/t51/1_n.jpg?stp=dst-jpg_s320x320', 320, 400),
            _image('https://scontent.cdninstagram.com/v/t51/1_n.jpg?stp=dst-jpg_e35', 1080, 1350),
        ]}},
        {'video_versions': [
            {'url': 'https://scontent.cdninstagram.com/o1/v/t16/clip_480.mp4', 'width': 480, 'height': 600},
            {'url': 'https://scontent.cdninstagram.com/o1/v/t16/clip_720.mp4', 'width': 720, 'height': 900},
        ], 'image_versions2': {'candidates': [_image('https://scontent.cdninstagram.com/cover.jpg', 720, 900)]}},
        {'image_versions2': {'candidates': [_image('https://scontent.cdninstagram.com/v/t51/3_n.webp', 1080, 1080)]}},
    ],
}


def test_an_instagram_carousel_gives_every_picture_and_video_in_order_at_full_size():
    meta, items = posts.instagram_post(INSTAGRAM_CAROUSEL)

    assert [(i['kind'], i['ext'], i['width']) for i in items] == [('image', 'jpg', 1080), ('video', 'mp4', 720),
                                                                 ('image', 'webp', 1080)]
    assert items[0]['url'].endswith('dst-jpg_e35')
    assert items[1]['url'].endswith('clip_720.mp4')
    described = posts.describe(meta, items)
    assert described['title'] == 'Sunset at the pier'
    assert described['uploader'] == 'pier_photos'
    assert described['id'] == 'DAbc123'
    assert described['webpage_url'] == 'https://www.instagram.com/p/DAbc123/'
    assert described['thumbnail'] == items[0]['url']
    assert described['extractor_key'] == 'Instagram'


def test_a_single_instagram_picture_is_one_item():
    _, items = posts.instagram_post({'code': 'X', 'image_versions2': {'candidates': [
        _image('https://scontent.cdninstagram.com/a.jpg', 640, 640)]}})

    assert [(i['kind'], i['url']) for i in items] == [('image', 'https://scontent.cdninstagram.com/a.jpg')]


def test_something_that_is_not_a_post_gives_nothing():
    assert posts.instagram_post(None) == ({}, [])

# endregion


# region a Pinterest pin


class FakePinExtractor(FakeExtractor):
    """gallery-dl's Pinterest extractor as far as the engine touches it: it turns each video of the pin into one
    file with `_extract_video`, as the real one does."""

    def __init__(self, files, meta=None, videos=()):
        super().__init__('pinterest', files, meta)
        self._videos = videos

    def _extract_video(self, video):
        return video['video_list']['V_HLSV4']

    def __iter__(self):
        yield Message.Directory, '', dict(self._meta)
        for url, extra in self._files:
            yield Message.Url, url, {**self._meta, **extra}
        for video in self._videos:
            chosen = self._extract_video(video)
            url, ext = chosen['url'], chosen['url'].rsplit('.', 1)[1]
            if ext == 'm3u8':
                # gallery-dl hands a stream to yt-dlp
                url, ext = 'ytdl:' + url, 'mp4'
            yield Message.Url, url, {**self._meta, 'extension': ext, 'width': chosen.get('width'),
                                     'height': chosen.get('height')}


PIN = {'category': 'pinterest', 'id': '1084663891475263837', 'title': '', 'grid_title': 'Kitchen gadgets',
       'description': 'A longer description', 'pinner': {'username': 'saver'},
       'native_creator': {'username': 'maker'}}

STORY_VIDEO = {'video_list': {
    'V_HLSV4': {'url': 'https://v1.pinimg.com/videos/hls/ab.m3u8', 'width': 720, 'height': 1280},
    'V_EXP4': {'url': 'https://v1.pinimg.com/videos/expMp4/ab_t4.mp4', 'width': 540, 'height': 960},
    'V_EXP7': {'url': 'https://v1.pinimg.com/videos/expMp4/ab_t7.mp4', 'width': 1080, 'height': 1920},
}}


def test_a_story_pin_gives_its_pictures_and_videos_but_not_its_text_or_music(tmp_path):
    story = FakePinExtractor([
        ('https://i.pinimg.com/originals/aa/bb/cc/page1.jpg', {'extension': 'jpg', 'width': 1000, 'height': 1500}),
        ('text:Step two: stir', {'extension': 'txt'}),
        ('https://v1.pinimg.com/audio/song.m4a', {'extension': 'm4a'}),
        ('https://i.pinimg.com/originals/aa/bb/cc/page3.gif', {'extension': 'gif'}),
    ], meta=PIN, videos=[STORY_VIDEO])

    _, items = posts.items_of('https://www.pinterest.com/pin/1084663891475263837/', str(tmp_path),
                              find=lambda url: story)

    assert [(item['kind'], item['ext']) for item in items] == [('image', 'jpg'), ('image', 'gif'), ('video', 'mp4')]


def test_a_pin_video_is_its_largest_plain_mp4_not_the_stream(tmp_path):
    pin = FakePinExtractor([], meta=PIN, videos=[STORY_VIDEO])

    _, items = posts.items_of('https://www.pinterest.com/pin/1/', str(tmp_path), find=lambda url: pin)

    assert [item['url'] for item in items] == ['https://v1.pinimg.com/videos/expMp4/ab_t7.mp4']
    assert items[0]['width'] == 1080


def test_a_pin_video_without_an_mp4_keeps_gallery_dls_choice(tmp_path):
    hls_only = {'video_list': {'V_HLSV4': {'url': 'https://v1.pinimg.com/videos/hls/ab.m3u8'}}}
    pin = FakePinExtractor([], meta=PIN, videos=[hls_only])

    chosen = pin._extract_video(hls_only)
    posts.items_of('https://www.pinterest.com/pin/1/', str(tmp_path), find=lambda url: pin)

    assert pin._extract_video(hls_only) == chosen


def test_a_pin_is_described_by_its_title_and_maker(tmp_path):
    pin = FakePinExtractor([('https://i.pinimg.com/originals/a.jpg', {'extension': 'jpg'})], meta=PIN)
    meta, items = posts.items_of('https://www.pinterest.com/pin/1084663891475263837/', str(tmp_path),
                                 find=lambda url: pin)

    described = posts.describe(meta, items)

    assert described['title'] == 'Kitchen gadgets'
    assert described['uploader'] == 'maker'
    assert described['id'] == '1084663891475263837'
    assert described['webpage_url'] == 'https://www.pinterest.com/pin/1084663891475263837/'
    assert described['extractor_key'] == 'Pinterest'
    assert described['thumbnail'] == 'https://i.pinimg.com/originals/a.jpg'


def test_a_pin_without_a_title_is_named_by_its_description():
    assert posts.describe({**PIN, 'grid_title': ''}, [])['title'] == 'A longer description'


class FakeShortLink:
    """gallery-dl's pin.it extractor: it only names the address the short link leads to."""
    category = 'pinterest'

    def __init__(self, target):
        self.target = target

    def __iter__(self):
        yield Message.Queue, self.target, {}


def test_a_pin_it_short_link_is_followed_to_its_pin(tmp_path):
    pin = FakePinExtractor([('https://i.pinimg.com/originals/a.jpg', {'extension': 'jpg'})], meta=PIN)
    found = {'https://pin.it/abc': FakeShortLink('https://www.pinterest.com/pin/1084663891475263837/'),
             'https://www.pinterest.com/pin/1084663891475263837/': pin}

    _, items = posts.items_of('https://pin.it/abc', str(tmp_path), find=found.get)

    assert [item['url'] for item in items] == ['https://i.pinimg.com/originals/a.jpg']


def test_a_short_link_to_a_whole_board_is_not_a_post(tmp_path):
    asked = []
    links = {'https://pin.it/board': FakeShortLink('https://www.pinterest.com/someone/recipes/')}

    def find(url):
        asked.append(url)
        return links.get(url)

    assert posts.items_of('https://pin.it/board', str(tmp_path), find=find) == ({}, [])
    assert asked == ['https://pin.it/board']


def test_gallery_dls_own_pinterest_extractor_lists_a_story_pin_the_way_the_engine_expects(tmp_path):
    """The real extractor at the pinned version, with only its request to Pinterest stood in for."""
    from gallery_dl import extractor as gdl_extractor

    def image(name):
        return {'type': 'story_pin_image_block', 'image_signature': name,
                'image': {'images': {'originals': {'url': f'https://i.pinimg.com/originals/{name}.jpg',
                                                   'width': 1000, 'height': 1500}}}}

    story_pin = {**PIN, 'story_pin_data': {'id': 's', 'pages': [
        {'id': 'p1', 'blocks': [image('one')]},
        {'id': 'p2', 'blocks': [{'type': 'story_pin_video_block', 'video': {'id': 'v', **STORY_VIDEO}}]},
        {'id': 'p3', 'blocks': [{'type': 'story_pin_paragraph_block', 'text': 'Step three', 'id': 't'}]},
        {'id': 'p4', 'blocks': [image('four'), {'type': 'story_pin_music_block',
                                                'audio': {'id': 'm', 'audio_url': 'https://v1.pinimg.com/a.m4a'}}]},
    ]}}

    def find(url):
        found = gdl_extractor.find(url)
        found.initialize()
        found.api.pin = lambda pin_id: story_pin
        return found

    _, items = posts.items_of('https://www.pinterest.com/pin/1084663891475263837/', str(tmp_path), find=find)

    assert [item['url'].rsplit('/', 1)[1] for item in items] == ['one.jpg', 'ab_t7.mp4', 'four.jpg']
    assert [item['kind'] for item in items] == ['image', 'video', 'image']


@pytest.mark.parametrize('url', [
    'https://www.pinterest.com/pin/1/', 'https://pinterest.co.uk/pin/1/', 'https://de.pinterest.com/pin/1/',
    'https://www.pinterest.com.au/pin/1/', 'https://pin.it/abc',
])
def test_pinterest_addresses_are_recognised(url):
    assert engine._is_pinterest(url)
    assert engine._is_post_site(url)


@pytest.mark.parametrize('url', ['https://notpinterest.com/pin/1/', 'https://pinterest.com.evil.example/pin/1/',
                                 'https://pin.it.example/abc'])
def test_lookalikes_are_not_pinterest(url):
    assert not engine._is_pinterest(url)


def test_a_pin_of_a_picture_that_yt_dlp_refuses_comes_back_as_items(tmp_path, monkeypatch, ytdlp_fails):
    ytdlp_fails.message = ('ERROR: [Pinterest] 1: No video formats found!; please report this issue on '
                           'https://github.com/yt-dlp/yt-dlp/issues?q= , filling out the appropriate issue template. '
                           'Confirm you are on the latest version using  yt-dlp -U')
    pin = FakePinExtractor([('https://i.pinimg.com/originals/a.jpg', {'extension': 'jpg'})], meta=PIN)
    monkeypatch.setattr(posts.gdl_extractor, 'find', lambda url: pin)

    media = json.loads(engine.extract('https://www.pinterest.com/pin/1/', str(tmp_path)))['media']

    assert [item['url'] for item in media['items']] == ['https://i.pinimg.com/originals/a.jpg']
    assert media['title'] == 'Kitchen gadgets'


def test_a_short_link_yt_dlp_does_not_know_goes_to_gallery_dl(tmp_path, monkeypatch, ytdlp_fails):
    ytdlp_fails.message = 'Unsupported URL: https://pin.it/abc'
    pin = FakePinExtractor([('https://i.pinimg.com/originals/a.jpg', {'extension': 'jpg'})], meta=PIN)
    monkeypatch.setattr(posts.gdl_extractor, 'find', lambda url: pin)

    media = json.loads(engine.extract('https://pin.it/abc', str(tmp_path)))['media']

    assert len(media['items']) == 1


def _found_by_yt_dlp(monkeypatch, info):
    class Found(FailingYoutubeDL):
        def extract_info(self, url, download):
            return info

    monkeypatch.setattr(engine, 'YoutubeDL', Found)


PIN_VIDEO = {'id': '1', 'title': 'Origami', 'extractor_key': 'Pinterest',
             'formats': [{'format_id': 'V_720P', 'url': 'https://v1.pinimg.com/videos/mc/720p/a.mp4'}]}


def test_a_video_pin_keeps_the_quality_choice(tmp_path, monkeypatch):
    _found_by_yt_dlp(monkeypatch, PIN_VIDEO)
    monkeypatch.setattr(posts.gdl_extractor, 'find', lambda url: FakePinExtractor([], meta=PIN, videos=[STORY_VIDEO]))

    media = json.loads(engine.extract('https://www.pinterest.com/pin/1/', str(tmp_path)))['media']

    assert 'items' not in media
    assert media['formats'][0]['format_id'] == 'V_720P'


def test_a_story_pin_yt_dlp_saw_as_one_video_is_listed_whole(tmp_path, monkeypatch):
    _found_by_yt_dlp(monkeypatch, PIN_VIDEO)
    story = FakePinExtractor([('https://i.pinimg.com/originals/page1.jpg', {'extension': 'jpg'})], meta=PIN,
                             videos=[STORY_VIDEO])
    monkeypatch.setattr(posts.gdl_extractor, 'find', lambda url: story)

    media = json.loads(engine.extract('https://www.pinterest.com/pin/1/', str(tmp_path)))['media']

    assert [item['kind'] for item in media['items']] == ['image', 'video']


def test_a_pin_showing_a_video_from_elsewhere_is_that_video(tmp_path, monkeypatch):
    _found_by_yt_dlp(monkeypatch, {**PIN_VIDEO, 'extractor_key': 'Vimeo'})
    asked = []
    monkeypatch.setattr(posts.gdl_extractor, 'find', lambda url: asked.append(url))

    media = json.loads(engine.extract('https://www.pinterest.com/pin/1/', str(tmp_path)))['media']

    assert media['extractor_key'] == 'Vimeo'
    assert asked == []

# endregion
