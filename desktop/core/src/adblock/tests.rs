//! The phone's ad-blocking tests (`app/src/test/java/com/chaya/app/adblock`), ported: the rule syntax rule by
//! rule, the real lists, their weekly fetch against a local server, and the blocker the page asks.

use std::path::PathBuf;

use super::kind::{IMAGE, MEDIA, SCRIPT, STYLESHEET, SUBDOCUMENT, UNKNOWN};
use super::lists::{Lists, Source, MIN_RULES, RETRY_AFTER_SECS, UPDATE_EVERY_SECS};
use super::settings::Settings;
use super::*;
use crate::download::testserver::{Behaviour, TestServer};

/// Enough of the public suffix list for these tests: two labels, or three under co.uk.
fn sites(host: &str) -> String {
    let labels: Vec<&str> = host.split('.').collect();
    let keep = if host.ends_with(".co.uk") { 3 } else { 2 };
    labels[labels.len().saturating_sub(keep)..].join(".")
}

fn engine(rules: &[&str]) -> FilterEngine {
    let mut builder = Builder::new(sites);
    rules.iter().for_each(|r| builder.add(r));
    builder.build()
}

fn blocks(e: &FilterEngine, url: &str) -> bool {
    blocks_on(e, url, "https://news.example/article", UNKNOWN)
}

fn blocks_on(e: &FilterEngine, url: &str, page: &str, kind: u16) -> bool {
    e.should_block(url, kind, &e.page(page))
}

// ── Addresses ────────────────────────────────────────────────────────────────────────────────

#[test]
fn a_bare_host_rule_blocks_that_host_and_every_host_under_it_and_nothing_else() {
    let e = engine(&["||ads.example.com^"]);
    assert!(blocks(&e, "https://ads.example.com/banner.png"));
    assert!(blocks(&e, "https://cdn.ads.example.com/x.js?y=1"));
    assert!(blocks(&e, "http://ads.example.com:8080/"));
    assert!(blocks(&e, "https://ads.example.com"));
    assert!(!blocks(&e, "https://badads.example.com/"));
    assert!(!blocks(&e, "https://example.com/ads.example.com/"));
    assert!(!blocks(&e, "https://ads.example.com.evil.net/"));
}

#[test]
fn a_host_rule_with_a_path_matches_from_the_start_of_a_host_label() {
    let e = engine(&["||tracker.net/collect^"]);
    assert!(blocks(&e, "https://tracker.net/collect?id=1"));
    assert!(blocks(&e, "https://eu.tracker.net/collect"));
    assert!(!blocks(&e, "https://tracker.net/collector"));
    assert!(!blocks(&e, "https://mytracker.net/collect"));
}

#[test]
fn wildcards_separators_and_anchors() {
    let e = engine(&["/banner/*/ad_", "|https://pop.", "swf|", "&ad_type=^"]);
    assert!(blocks(&e, "https://site.example/banner/300x250/ad_1.png"));
    assert!(!blocks(&e, "https://site.example/banner/ad_1.png"));
    assert!(blocks(&e, "https://pop.example/"));
    assert!(!blocks(&e, "https://x.example/?u=https://pop.example/"));
    assert!(blocks(&e, "https://x.example/movie.swf"));
    assert!(!blocks(&e, "https://x.example/movie.swf?x"));
    assert!(blocks(&e, "https://x.example/?a=1&ad_type=&b"));
    assert!(blocks(&e, "https://x.example/?a=1&ad_type="), "a separator also matches the end");
}

#[test]
fn rules_match_whatever_the_case_of_the_address_unless_they_ask_not_to() {
    let e = engine(&["/AdServer/*", "/TrackMe$match-case"]);
    assert!(blocks(&e, "https://x.example/adserver/a.js"));
    assert!(blocks(&e, "https://x.example/ADSERVER/a.js"));
    assert!(blocks(&e, "https://x.example/TrackMe"));
    assert!(!blocks(&e, "https://x.example/trackme"));
}

// ── Options ──────────────────────────────────────────────────────────────────────────────────

#[test]
fn third_party_rules_block_only_what_other_sites_send() {
    let e = engine(&["||cdn.widgets.example^$third-party", "||stats.example^$~third-party"]);
    assert!(blocks_on(&e, "https://cdn.widgets.example/w.js", "https://news.example/", UNKNOWN));
    assert!(!blocks_on(&e, "https://cdn.widgets.example/w.js", "https://www.widgets.example/", UNKNOWN));
    assert!(blocks_on(&e, "https://stats.example/p", "https://www.stats.example/", UNKNOWN));
    assert!(!blocks_on(&e, "https://stats.example/p", "https://news.example/", UNKNOWN));
}

#[test]
fn third_party_compares_registered_names_so_a_sites_own_subdomains_are_first_party() {
    let e = engine(&["||img.shop.co.uk^$third-party"]);
    assert!(!blocks_on(&e, "https://img.shop.co.uk/a.png", "https://www.shop.co.uk/", UNKNOWN));
    assert!(blocks_on(&e, "https://img.shop.co.uk/a.png", "https://www.other.co.uk/", UNKNOWN));
}

#[test]
fn domain_rules_apply_only_on_the_pages_they_name() {
    let e = engine(&["/sponsor.js$domain=news.example|~sport.news.example", "/promo.$domain=shop.*"]);
    assert!(blocks_on(&e, "https://cdn.example/sponsor.js", "https://www.news.example/", UNKNOWN));
    assert!(!blocks_on(&e, "https://cdn.example/sponsor.js", "https://sport.news.example/", UNKNOWN));
    assert!(!blocks_on(&e, "https://cdn.example/sponsor.js", "https://blog.example/", UNKNOWN));
    assert!(blocks_on(&e, "https://cdn.example/promo.png", "https://www.shop.co.uk/", UNKNOWN));
    assert!(blocks_on(&e, "https://cdn.example/promo.png", "https://shop.de/", UNKNOWN));
    assert!(!blocks_on(&e, "https://cdn.example/promo.png", "https://workshop.de/", UNKNOWN));
}

#[test]
fn a_rule_naming_kinds_of_request_never_matches_a_request_whose_kind_is_unknown() {
    let e = engine(&["/adframe.$subdocument", "/pixel.$~image", "/plain-ad."]);
    let page = "https://news.example/article";
    assert!(blocks_on(&e, "https://x.example/adframe.html", page, SUBDOCUMENT));
    assert!(!blocks_on(&e, "https://x.example/adframe.html", page, SCRIPT));
    assert!(!blocks_on(&e, "https://x.example/adframe.html", page, UNKNOWN));
    assert!(blocks_on(&e, "https://x.example/pixel.js", page, SCRIPT));
    assert!(!blocks_on(&e, "https://x.example/pixel.gif", page, IMAGE));
    assert!(!blocks_on(&e, "https://x.example/pixel.gif", page, UNKNOWN));
    assert!(blocks_on(&e, "https://x.example/plain-ad.css", page, STYLESHEET));
    assert!(blocks_on(&e, "https://x.example/plain-ad.css", page, UNKNOWN));
}

#[test]
fn exceptions_win_over_blocks_and_important_blocks_win_over_exceptions() {
    let e =
        engine(&["||ads.example^", "@@||ads.example/needed.js", "||track.example^$important", "@@||track.example^"]);
    assert!(blocks(&e, "https://ads.example/banner.js"));
    assert!(!blocks(&e, "https://ads.example/needed.js"));
    assert!(blocks(&e, "https://track.example/t.gif"));
}

#[test]
fn a_page_wide_exception_turns_everything_off_on_that_page_only() {
    let e = engine(&["||ads.example^", "##.ad-box", "@@||friendly.example^$document"]);
    assert!(!blocks_on(&e, "https://ads.example/a.js", "https://friendly.example/home", UNKNOWN));
    assert_eq!(e.page_css(&e.page("https://friendly.example/home")), "");
    assert!(blocks_on(&e, "https://ads.example/a.js", "https://other.example/", UNKNOWN));
}

#[test]
fn pop_up_rules_apply_to_new_windows_only() {
    let e = engine(&["||popads.example^$popup", "||both.example^$popup,third-party"]);
    let page = e.page("https://news.example/");
    assert!(!blocks(&e, "https://popads.example/go"));
    assert!(e.should_block_popup("https://popads.example/go", &page));
    assert!(e.should_block_popup("https://both.example/go", &page));
    assert!(!e.should_block_popup("https://other.example/", &page));
}

#[test]
fn rules_this_engine_cannot_apply_faithfully_are_skipped() {
    let e = engine(&[
        r"/^https?:\/\/ads\./",                    // a regular expression
        "/adserver/",                              // also read as one, as Adblock Plus reads it
        "||x.example^$csp=script-src 'none'",      // rewrites a header
        "||y.example^$removeparam=utm",            // rewrites the address
        "||z.example^$redirect-rule=noop.js",      // only redirects when something else blocks
        "$third-party",                            // would block every third-party request
        "news.example##+js(set, adsbygoogle, {})", // a scriptlet
        "news.example##.ad:has-text(Sponsored)",   // procedural
        "news.example#?#.ad:-abp-has(.x)",         // procedural, by its own separator
    ]);
    assert!(!blocks(&e, "https://ads.example/"));
    assert!(!blocks(&e, "https://x.example/"));
    assert!(!blocks(&e, "https://y.example/?utm=1"));
    assert!(!blocks(&e, "https://z.example/a.js"));
    assert!(!blocks(&e, "https://cdn.other/a.js"));
    assert_eq!(e.page_css(&e.page("https://news.example/")), "");
    assert_eq!(e.rule_count(), 0);
}

#[test]
fn a_redirect_rule_still_blocks() {
    let e = engine(&["||ads.example/gpt.js$script,redirect=googletagservices_gpt.js"]);
    assert!(blocks_on(&e, "https://ads.example/gpt.js", "https://news.example/", SCRIPT));
}

#[test]
fn only_http_and_https_requests_are_ever_blocked() {
    let e = engine(&["/ad."]);
    assert!(!blocks(&e, "data:text/plain,/ad.x"));
    assert!(!blocks(&e, "blob:https://x.example/ad.1"));
}

// ── Hiding page elements ─────────────────────────────────────────────────────────────────────

#[test]
fn site_rules_are_hidden_on_that_site_and_its_subdomains_minus_exceptions() {
    let e = engine(&[
        "news.example,~live.news.example##.sponsor",
        "news.example##div[data-ad]",
        "www.news.example#@#.sponsor",
    ]);
    let css = e.page_css(&e.page("https://m.news.example/a"));
    assert!(css.contains(".sponsor{display:none!important}"));
    assert!(css.contains("div[data-ad]{display:none!important}"));
    assert!(!e.page_css(&e.page("https://live.news.example/")).contains(".sponsor"));
    assert!(!e.page_css(&e.page("https://www.news.example/")).contains(".sponsor"));
    assert_eq!(e.page_css(&e.page("https://other.example/")), "");
}

#[test]
fn rules_for_every_site_are_handed_out_by_the_classes_and_ids_a_page_has() {
    let e = engine(&[
        "##.ad-banner",
        "##.ad-banner > .inner",
        "###top-ad",
        "##a[href^=\"https://ads.\"]",
        "~safe.example##.promo",
    ]);
    let page = e.page("https://news.example/");
    let generic = e.generic_css(&page, [".ad-banner", ".article", "#top-ad"]);
    assert!(generic.contains(".ad-banner{display:none!important}"));
    assert!(generic.contains(".ad-banner > .inner{display:none!important}"));
    assert!(generic.contains("#top-ad{display:none!important}"));
    assert_eq!(e.generic_css(&page, [".article"]), "");
    let always = e.page_css(&page);
    assert!(always.contains("a[href^=\"https://ads.\"]"), "selectors without a class or id are always included");
    assert!(always.contains(".promo"));
    assert!(!e.page_css(&e.page("https://safe.example/")).contains(".promo"));
}

#[test]
fn generichide_keeps_only_a_sites_own_rules_and_a_generic_exception_removes_a_rule_everywhere() {
    let e = engine(&[
        "##.ad-banner",
        "##div[id^=\"ad-\"]",
        "shop.example##.deal-ad",
        "@@||shop.example^$generichide",
        "#@#.ad-banner",
    ]);
    let shop = e.page("https://shop.example/");
    assert_eq!(e.generic_css(&shop, [".ad-banner"]), "");
    assert_eq!(e.page_css(&shop), ".deal-ad{display:none!important}\n");
    assert!(!e.hides_generically(&shop));
    assert_eq!(e.generic_css(&e.page("https://news.example/"), [".ad-banner"]), "");
}

#[test]
fn selectors_that_could_break_out_of_their_css_rule_are_refused() {
    let e = engine(&["##.a{}body{display:none", "##.b</style>", "news.example##.c\\{"]);
    assert_eq!(e.page_css(&e.page("https://news.example/")), "");
    assert_eq!(e.generic_css(&e.page("https://news.example/"), [".a", ".b"]), "");
}

// ── Helpers ──────────────────────────────────────────────────────────────────────────────────

#[test]
fn rules_are_indexed_by_a_whole_word_of_their_pattern() {
    use super::engine::best_token;
    assert_eq!(best_token("/adserver/", false, false).as_deref(), Some("adserver"));
    assert_eq!(best_token("ad*", false, false), None, "a word next to a wildcard may be part of a longer word");
    assert_eq!(best_token("com/banner^", true, false).as_deref(), Some("banner"));
    assert_eq!(best_token("tracker", false, false), None, "a word at an unanchored end may continue in the address");
}

#[test]
fn the_host_of_an_address() {
    assert_eq!(host_of("https://user:pw@ADS.example.com:8443/x?y#z"), "ads.example.com");
    assert_eq!(host_of("http://a.example"), "a.example");
    assert_eq!(host_of("http://a.example./x"), "a.example");
    assert_eq!(host_of("http://[::1]:8080/x"), "[::1]");
    assert_eq!(host_of("about:blank"), "");
}

#[test]
fn sites_follow_the_public_suffix_list() {
    assert_eq!(public_suffix_site("www.bbc.co.uk"), "bbc.co.uk");
    assert_eq!(public_suffix_site("a.b.example.com"), "example.com");
    assert_eq!(public_suffix_site("someone.github.io"), "someone.github.io");
    assert_eq!(public_suffix_site("127.0.0.1"), "127.0.0.1");
    assert_eq!(public_suffix_site("localhost"), "localhost");
}

#[test]
fn a_request_the_browser_does_not_name_is_judged_by_its_file_name() {
    assert_eq!(kind::by_extension("https://x.example/a/b.JS?v=1"), SCRIPT);
    assert_eq!(kind::by_extension("https://x.example/p.png#x"), IMAGE);
    assert_eq!(kind::by_extension("https://x.example/v/master.m3u8"), MEDIA);
    assert_eq!(kind::by_extension("https://x.example/api/data"), UNKNOWN);
    assert_eq!(kind::by_extension("https://x.example"), UNKNOWN);
    assert_eq!(kind::by_extension("https://x.example/?file=a.js"), UNKNOWN);
}

// ── The lists the app carries ────────────────────────────────────────────────────────────────

fn bundled_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../app/src/main/assets/adblock")
}

fn real_engine() -> &'static FilterEngine {
    static ENGINE: std::sync::OnceLock<FilterEngine> = std::sync::OnceLock::new();
    ENGINE.get_or_init(|| {
        Lists::new(bundled_dir(), PathBuf::from("/nonexistent"), lists::default_sources())
            .build_engine(public_suffix_site)
            .expect("the lists the app carries")
    })
}

#[test]
fn the_lists_are_whole() {
    assert!(real_engine().rule_count() > 100_000, "rules: {}", real_engine().rule_count());
}

#[test]
fn common_ads_and_trackers_are_blocked() {
    let page = "https://www.theguardian.com/world/2026/oct/10/story";
    for url in [
        "https://securepubads.g.doubleclick.net/tag/js/gpt.js",
        "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=ca-pub-1",
        "https://www.googletagmanager.com/gtm.js?id=GTM-1",
        "https://www.google-analytics.com/g/collect?v=2",
        "https://c.amazon-adsystem.com/aax2/apstag.js",
        "https://cdn.taboola.com/libtrc/x/loader.js",
    ] {
        assert!(blocks_on(real_engine(), url, page, SCRIPT), "{url}");
    }
}

#[test]
fn nothing_chaya_downloads_from_or_the_video_sites_need_to_play_is_blocked() {
    let youtube = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    let instagram = "https://www.instagram.com/p/Cabc123/";
    let x = "https://x.com/someone/status/1";
    let tiktok = "https://www.tiktok.com/@someone/video/1";
    let news = "https://news.example/";
    for (page, url, kind) in [
        (youtube, "https://rr3---sn-4g5e6nzz.googlevideo.com/videoplayback?expire=1&itag=18", MEDIA),
        (youtube, "https://rr3---sn-4g5e6nzz.googlevideo.com/videoplayback?expire=1&itag=18", UNKNOWN),
        (youtube, "https://www.youtube.com/s/player/abc/player_ias.vflset/en_US/base.js", SCRIPT),
        (youtube, "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg", IMAGE),
        (youtube, "https://www.youtube.com/youtubei/v1/player?key=x", UNKNOWN),
        (instagram, "https://scontent-lhr8-1.cdninstagram.com/v/t51.29350-15/1_n.jpg?stp=dst-jpg", IMAGE),
        (instagram, "https://instagram.flhr8-1.fna.fbcdn.net/o1/v/t16/f2/m86/a.mp4?efg=x", MEDIA),
        (instagram, "https://www.instagram.com/graphql/query", UNKNOWN),
        (instagram, "https://static.cdninstagram.com/rsrc.php/v3/yR/r/a.js", SCRIPT),
        (x, "https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/720x1280/a.mp4?tag=12", MEDIA),
        (x, "https://pbs.twimg.com/media/Fabc?format=jpg&name=small", IMAGE),
        (x, "https://abs.twimg.com/responsive-web/client-web/main.a.js", SCRIPT),
        (x, "https://api.x.com/graphql/abc/TweetDetail?variables=x", UNKNOWN),
        (tiktok, "https://v16-webapp-prime.tiktok.com/video/tos/a/?mime_type=video_mp4", MEDIA),
        (news, "https://code.jquery.com/jquery-3.7.1.min.js", SCRIPT),
        (news, "https://fonts.googleapis.com/css2?family=Inter", STYLESHEET),
    ] {
        assert!(!blocks_on(real_engine(), url, page, kind), "{url}");
    }
}

#[test]
fn the_video_sites_keep_their_own_pages() {
    for page in
        ["https://www.youtube.com/watch?v=dQw4w9WgXcQ", "https://www.instagram.com/someone/", "https://x.com/home"]
    {
        assert!(!real_engine().page(page).allow_all, "{page}");
    }
}

// ── Fetching fresh lists ─────────────────────────────────────────────────────────────────────

const DAY: u64 = 24 * 60 * 60;
const NOW: u64 = 1_800_000_000;

/// A list as the sites serve it: the header, then enough rules to be believed.
fn list(rules: &[&str]) -> Vec<u8> {
    let mut text = String::from("[Adblock Plus 2.0]\n! Title: Test\n");
    for rule in rules {
        text.push_str(rule);
        text.push('\n');
    }
    for i in 0..MIN_RULES {
        text.push_str(&format!("||filler-{i}.example^\n"));
    }
    text.into_bytes()
}

struct Fixture {
    _temp: tempfile::TempDir,
    bundled: PathBuf,
    fetched: PathBuf,
}

fn fixture() -> Fixture {
    let temp = tempfile::tempdir().unwrap();
    let bundled = temp.path().join("bundled");
    std::fs::create_dir_all(&bundled).unwrap();
    std::fs::write(bundled.join("ads.txt"), list(&["||bundled-ads.example^"])).unwrap();
    std::fs::write(bundled.join("trackers.txt"), list(&["||bundled-trackers.example^"])).unwrap();
    let fetched = temp.path().join("fetched");
    Fixture { _temp: temp, bundled, fetched }
}

fn lists(f: &Fixture, server: &TestServer) -> Lists {
    let sources = vec![
        Source { file_name: "ads.txt".into(), url: server.url("/ads.txt") },
        Source { file_name: "trackers.txt".into(), url: server.url("/trackers.txt") },
    ];
    Lists::new(f.bundled.clone(), f.fetched.clone(), sources)
}

fn lists_block(lists: &Lists, url: &str) -> bool {
    let engine = lists.build_engine(public_suffix_site).unwrap();
    engine.should_block(url, SCRIPT, &engine.page("https://news.example/"))
}

fn client() -> reqwest::Client {
    reqwest::Client::new()
}

#[test]
fn the_copies_in_the_app_are_used_until_fresh_ones_are_fetched() {
    let f = fixture();
    let server = TestServer::start(Vec::new(), Behaviour::default());
    let lists = lists(&f, &server);
    assert!(lists_block(&lists, "https://bundled-ads.example/a.js"));
    assert!(lists_block(&lists, "https://bundled-trackers.example/t.js"));
    assert_eq!(lists.last_updated(), None);
}

#[tokio::test]
async fn when_a_week_has_passed_every_list_is_fetched_and_the_fresh_copies_replace_the_apps() {
    let f = fixture();
    let server = TestServer::with_files(
        vec![
            ("/ads.txt".into(), list(&["||fresh-ads.example^"])),
            ("/trackers.txt".into(), list(&["||fresh-trackers.example^"])),
        ],
        Behaviour::default(),
    );
    let lists = lists(&f, &server);

    assert!(lists.update_if_due(&client(), NOW).await);

    let asked = server.requests();
    assert_eq!(asked.len(), 2);
    assert!(asked[0].starts_with("get /ads.txt ") && asked[1].starts_with("get /trackers.txt "));
    assert!(asked.iter().all(|r| !r.contains("\ncookie:")), "nothing about the person is sent");
    assert!(lists_block(&lists, "https://fresh-ads.example/a.js"));
    assert!(!lists_block(&lists, "https://bundled-ads.example/a.js"));
    assert_eq!(lists.last_updated(), Some(NOW));
    let names: Vec<_> = std::fs::read_dir(&f.fetched).unwrap().map(|e| e.unwrap().file_name()).collect();
    assert!(names.iter().all(|n| !n.to_string_lossy().ends_with(".download")), "{names:?}");

    // Within the week nothing is fetched.
    assert!(!lists.update_if_due(&client(), NOW + 6 * DAY).await);
    assert_eq!(server.requests().len(), 2);
    assert!(lists.is_update_due(NOW + UPDATE_EVERY_SECS));
}

#[tokio::test]
async fn something_that_is_not_a_list_is_thrown_away_the_lists_in_use_stay_and_the_next_try_waits_hours() {
    let f = fixture();
    let server = TestServer::with_files(
        vec![
            ("/ads.txt".into(), list(&["||fresh-ads.example^"])),
            ("/trackers.txt".into(), b"<html>Captive portal: please log in</html>".to_vec()),
        ],
        Behaviour::default(),
    );
    let lists = lists(&f, &server);

    assert!(!lists.update_if_due(&client(), NOW).await);

    assert!(lists_block(&lists, "https://bundled-ads.example/a.js"), "neither list is replaced when one fails");
    assert!(!lists_block(&lists, "https://fresh-ads.example/a.js"));
    assert_eq!(lists.last_updated(), None);
    assert!(!lists.is_update_due(NOW + 2 * 60 * 60), "not again within six hours");
    assert!(lists.is_update_due(NOW + RETRY_AFTER_SECS));
}

#[tokio::test]
async fn a_server_error_or_a_cut_off_list_keeps_the_lists_in_use() {
    let f = fixture();
    let refusing = TestServer::start(Vec::new(), Behaviour { status: Some(503), ..Behaviour::default() });
    assert!(!lists(&f, &refusing).update_if_due(&client(), NOW).await);

    let cut_off = TestServer::start(b"[Adblock Plus 2.0]\n||fresh-ads.example^\n".to_vec(), Behaviour::default());
    let lists = lists(&f, &cut_off);
    assert!(!lists.update_if_due(&client(), NOW + 7 * 60 * 60).await);
    assert!(lists_block(&lists, "https://bundled-ads.example/a.js"));
}

// ── The blocker the page asks ────────────────────────────────────────────────────────────────

fn blocker() -> AdBlocker {
    let blocker = AdBlocker::new(Settings::in_memory(), sites);
    blocker.use_engine(engine(&[
        "||ads.example^",
        "/banner.$image",
        "||popads.example^$popup",
        "news.example##.sponsor",
        "##.ad-banner",
        "@@||friendly.example^$document",
    ]));
    blocker
}

#[test]
fn requests_are_judged_by_the_page_shown_and_each_one_blocked_is_counted() {
    let b = blocker();
    b.begin_page("https://www.news.example/story");
    assert!(b.should_block("https://ads.example/a.js", SCRIPT));
    assert!(b.should_block("https://cdn.example/banner.gif", IMAGE));
    assert!(!b.should_block("https://cdn.example/photo.jpg", IMAGE));
    let shield = b.shield();
    assert_eq!((shield.blocked, shield.site.as_str(), shield.blocking), (2, "news.example", true));

    b.begin_page("https://www.news.example/next");
    assert_eq!(b.shield().blocked, 0, "a new page counts afresh");
}

#[test]
fn nothing_is_blocked_or_hidden_when_the_person_turns_blocking_off_or_allows_the_site() {
    let b = blocker();
    b.begin_page("https://www.news.example/story");

    b.settings().set_enabled(false).unwrap();
    assert!(!b.should_block("https://ads.example/a.js", SCRIPT));
    assert!(!b.hides());
    assert_eq!(b.hiding_css("https://www.news.example/story", true, [".ad-banner"]), "");
    assert!(!b.shield().enabled && !b.shield().blocking);

    b.settings().set_enabled(true).unwrap();
    b.settings().set_allowed("news.example", true).unwrap();
    assert!(!b.should_block("https://ads.example/a.js", SCRIPT));
    assert_eq!(b.hiding_css("https://www.news.example/story", true, [".ad-banner"]), "");
    assert!(b.shield().enabled && !b.shield().blocking);

    b.settings().set_allowed("news.example", false).unwrap();
    assert!(b.should_block("https://ads.example/a.js", SCRIPT));
}

#[test]
fn the_page_itself_is_never_blocked_only_what_it_loads() {
    let b = blocker();
    b.begin_page("https://ads.example/landing");
    assert!(!b.should_block("https://ads.example/landing", kind::DOCUMENT));
    assert!(b.should_block("https://ads.example/frame.html", kind::DOCUMENT), "another document is a frame");
    assert!(b.should_block("https://ads.example/a.js", SCRIPT));
}

#[test]
fn a_page_the_lists_exempt_a_page_before_any_is_shown_or_before_the_lists_are_read_blocks_nothing() {
    let b = blocker();
    assert!(!b.should_block("https://ads.example/a.js", SCRIPT));

    b.begin_page("https://friendly.example/");
    assert!(!b.should_block("https://ads.example/a.js", SCRIPT));
    assert_eq!(b.hiding_css("https://friendly.example/", true, [".ad-banner"]), "");

    let reading = AdBlocker::new(Settings::in_memory(), sites);
    reading.begin_page("https://www.news.example/");
    assert!(!reading.should_block("https://ads.example/a.js", SCRIPT));
    assert!(!reading.hides());
    assert_eq!(reading.shield().site, "news.example", "the site is known without the lists");
    assert_eq!(reading.shield().rules, None);
}

#[test]
fn asking_whether_something_on_the_page_is_an_ad_counts_nothing() {
    let b = blocker();
    b.begin_page("https://www.news.example/");
    assert!(b.blocks("https://ads.example/promo.mp4", MEDIA));
    assert!(!b.blocks("https://cdn.example/story.mp4", MEDIA));
    assert_eq!(b.shield().blocked, 0);
}

#[test]
fn ad_pop_ups_are_refused_and_counted() {
    let b = blocker();
    b.begin_page("https://www.news.example/");
    assert!(b.should_block_popup("https://popads.example/go"));
    assert!(!b.should_block_popup("https://shop.example/"));
    assert_eq!(b.shield().blocked, 1);
}

#[test]
fn a_new_document_gets_its_sites_rules_and_every_document_the_rules_for_the_names_it_holds() {
    let b = blocker();
    b.begin_page("https://www.news.example/");
    assert!(b.hides());
    let first = b.hiding_css("https://www.news.example/", true, [".ad-banner", ".article"]);
    assert_eq!(first, ".sponsor{display:none!important}\n.ad-banner{display:none!important}\n");
    assert_eq!(b.hiding_css("https://www.news.example/", false, [".article"]), "");
    assert_eq!(
        b.hiding_css("https://www.news.example/", false, [".ad-banner"]),
        ".ad-banner{display:none!important}\n"
    );
}

#[test]
fn about_blank_has_no_site() {
    let b = blocker();
    b.begin_page("about:blank");
    assert_eq!(b.shield().site, "");
    assert!(!b.hides());
}

#[test]
fn settings_are_kept_and_read_back_and_a_broken_file_blocks_everywhere() {
    let temp = tempfile::tempdir().unwrap();
    let path = temp.path().join("adblock.json");
    let settings = Settings::open(path.clone());
    assert!(settings.enabled() && settings.blocks_on("news.example"));
    settings.set_allowed("news.example", true).unwrap();
    settings.set_allowed("", true).unwrap();

    let again = Settings::open(path.clone());
    assert!(!again.blocks_on("news.example") && again.blocks_on("other.example"));
    assert_eq!(again.allowed_sites(), vec!["news.example".to_string()]);
    again.set_enabled(false).unwrap();
    assert!(!Settings::open(path.clone()).enabled());

    std::fs::write(&path, b"{ not json").unwrap();
    assert!(Settings::open(path).blocks_on("news.example"));
}
#[test]
fn each_tab_has_its_own_page_and_count_and_shares_the_rules_and_the_persons_choices() {
    let first = AdBlocker::new(Settings::in_memory(), sites);
    let second = first.for_another_page();
    // The lists are read once, after the tabs were opened, and both use them.
    first.use_engine(engine(&["||ads.example^"]));
    first.begin_page("https://www.news.example/story");
    second.begin_page("https://www.shop.example/");
    assert!(first.should_block("https://ads.example/a.js", SCRIPT));
    assert!(second.should_block("https://ads.example/b.js", SCRIPT));
    assert!(second.should_block("https://ads.example/c.js", SCRIPT));
    assert_eq!((first.shield().site.as_str(), first.shield().blocked), ("news.example", 1));
    assert_eq!((second.shield().site.as_str(), second.shield().blocked), ("shop.example", 2));

    // Ads shown on one site, in either tab, are shown in both.
    second.settings().set_allowed("news.example", true).unwrap();
    assert!(!first.should_block("https://ads.example/d.js", SCRIPT));
}
