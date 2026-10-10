"""Listing an account's posts for a ZIP archive, with a stand-in for gallery-dl's lookup (no network)."""
import datetime
import json

from gallery_dl import exception as gdl_exception
from gallery_dl.extractor.message import Message

from chaya_engine import profiles


class FakeExtractor:
    def __init__(self, files, fail=None, before_each=None):
        self.category = 'instagram'
        self._files = files
        self._fail = fail
        self._before_each = before_each or (lambda: None)

    def __iter__(self):
        yield Message.Directory, '', {}
        for url, info in self._files:
            self._before_each()
            yield Message.Url, url, info
        if self._fail:
            raise self._fail


DAY = datetime.datetime(2024, 5, 1, 12, 0)
FILES = [
    ('https://cdn/one.jpg', {'extension': 'jpg', 'post_shortcode': 'Cabc', 'num': 1, 'post_date': DAY}),
    ('https://cdn/two.mp4', {'extension': 'mp4', 'post_shortcode': 'Cabc', 'num': 2, 'post_date': DAY}),
    ('https://cdn/three.jpg', {'extension': 'jpg', 'post_shortcode': 'Cdef', 'num': 1, 'post_date': DAY}),
]


def _list(tmp_path, extractor, cookie_file='cookies.txt', seen=None):
    out, stop = tmp_path / 'list.jsonl', tmp_path / 'stop'

    def find(url):
        if seen is not None:
            seen.append(url)
        return extractor

    result = json.loads(profiles.list_profile('instagram', 'someone', str(tmp_path), cookie_file,
                                              str(out), str(stop), find=find))
    return result, out


def test_every_file_is_listed_with_a_stable_name_in_the_archive(tmp_path):
    seen = []
    result, out = _list(tmp_path, FakeExtractor(FILES), seen=seen)

    assert result == {'count': 3}
    assert seen == ['https://www.instagram.com/someone/posts/']
    lines = [json.loads(line) for line in out.read_text().splitlines()]
    assert [line['name'] for line in lines] == ['2024-05-01 Cabc 1.jpg', '2024-05-01 Cabc 2.mp4', '2024-05-01 Cdef 1.jpg']
    assert [line['kind'] for line in lines] == ['image', 'video', 'image']
    assert lines[0]['url'] == 'https://cdn/one.jpg'


def test_x_accounts_are_listed_from_their_media_timeline():
    assert profiles.posts_url('twitter', 'someone') == 'https://x.com/someone/media'


def test_without_a_sign_in_the_account_is_listed_as_a_visitor_sees_it(tmp_path):
    result, out = _list(tmp_path, FakeExtractor(FILES[:1]), cookie_file=None)

    assert result == {'count': 1}
    assert len(out.read_text().splitlines()) == 1


def test_a_visitor_turned_away_is_told_to_sign_in_not_that_the_account_is_missing(tmp_path):
    hidden, _ = _list(tmp_path, FakeExtractor([], fail=gdl_exception.NotFoundError('user')), cookie_file=None)
    walled, _ = _list(tmp_path, FakeExtractor([], fail=Exception('Redirected to login page')), cookie_file=None)

    assert hidden['error']['kind'] == 'needs_login'
    assert walled['error']['kind'] == 'needs_login'


def test_a_stop_ends_the_listing_and_leaves_no_finished_list(tmp_path):
    stop = tmp_path / 'stop'
    calls = []

    def stop_after_first():
        calls.append(1)
        if len(calls) == 2:
            stop.write_text('')

    result, out = _list(tmp_path, FakeExtractor(FILES, before_each=stop_after_first))

    assert result == {'stopped': True, 'count': 1}
    assert not out.exists(), 'a stopped list must not look finished'


def test_a_failure_part_way_leaves_no_finished_list_and_is_explained(tmp_path):
    result, out = _list(tmp_path, FakeExtractor(FILES, fail=gdl_exception.AuthRequired('cookies')))

    assert result['error']['kind'] == 'needs_login'
    assert not out.exists()


def test_a_private_or_missing_account_is_explained(tmp_path):
    private, _ = _list(tmp_path, FakeExtractor([], fail=gdl_exception.AuthorizationError('private')))
    missing, _ = _list(tmp_path, FakeExtractor([], fail=gdl_exception.NotFoundError('user')))

    assert private['error']['kind'] == 'private'
    assert missing['error']['kind'] == 'unavailable'


def test_names_are_safe_for_any_file_system():
    assert profiles.entry_name({'tweet_id': 42, 'num': 3}, 'jpg') == '42 3.jpg'
    assert profiles.entry_name({'post_shortcode': 'a/b:c', 'num': 1}, 'jpg') == 'a_b_c 1.jpg'
    assert profiles.entry_name({}, 'png') == '1.png'
