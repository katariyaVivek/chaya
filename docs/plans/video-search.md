# Plan: search videos by name across platforms

Status: a plan, not built. Written 2026-10-20 for the owner to build from.

## What it does for the person

On the start page, a **Search videos** chip opens a search screen. The person types a name, even a misspelt
or partial one ("mentlist s1 e2", "chaplin kid 1921"). Chaya asks several video platforms at once and shows
one merged list, best match first. Each result is either:

- **one video**: tapping it opens the same sheet a pasted link opens today (qualities, Audio only, Download);
- **a playlist or a multi-file item** (a season uploaded as a playlist, an Internet Archive item holding
  every episode): tapping it lists the episodes, and the person downloads one, some, or all of them as a
  ZIP.

## Scope

**In:** platforms that have their own search and that yt-dlp already downloads from.

**Out:**
- General web indexes. yt-dlp's `gvsearch` (Google Video) scrapes Google's HTML by a CSS class name, and
  `yvsearch` (Yahoo Video) returns links to arbitrary sites; both are fragile.
- Streaming and torrent index sites.
- Logging in to a platform to search it.

## Sources

The container this plan was written in blocks every video host, so **none of these were tried live**. Check
each one on the phone (or with `curl` from a computer) before building on it; PR 1 below includes a debug
screen for exactly that.

| Source | How to search | What a result links to | Notes |
|---|---|---|---|
| YouTube | yt-dlp `ytsearch20:<q>` | `youtube.com/watch?v=` | Already the best-supported site in Chaya. For playlists (seasons) use the search URL `https://www.youtube.com/results?search_query=<q>&sp=EgIQAw%3D%3D` (the "Playlist" filter; verify the `sp` value), which yt-dlp's `YoutubeSearchURLIE` reads. |
| Bilibili | yt-dlp `bilisearch20:<q>` | `bilibili.com/video/BV…` | Large catalogue of full-length uploads. Search may need Bilibili's `buvid3` cookie; yt-dlp fetches one itself. Titles are often Chinese, so ranking has to cope with CJK text. |
| Dailymotion | yt-dlp's `DailymotionSearchIE` on `https://www.dailymotion.com/search/<q>/videos`, or the Data API `GET https://api.dailymotion.com/videos?search=<q>&fields=id,title,duration,owner.screenname,thumbnail_360_url,url,views_total&limit=20` | `dailymotion.com/video/…` | The API's `search` parameter is documented; whether it still works without a key is not confirmed. |
| Internet Archive | `https://archive.org/advancedsearch.php?q=<q> AND mediatype:(movies)&fl[]=identifier&fl[]=title&fl[]=year&fl[]=downloads&rows=20&output=json` | `archive.org/details/<id>` | Films, old TV, cartoons. Results are under `response.docs`. An item's files are at `https://archive.org/metadata/<id>` and download as plain files from `https://archive.org/download/<id>/<file>`, which suits the ZIP pipeline as it is. Expect occasional rate limiting. |
| PeerTube (Sepia Search) | `https://sepiasearch.org/api/v1/search/videos?search=<q>&count=20` | the hosting instance's `/w/<id>` | Same JSON shape as PeerTube's own `/api/v1/search/videos`. yt-dlp's PeerTube extractor downloads from known instances; an unknown instance may need the generic extractor. |
| NicoNico | yt-dlp `nicosearch20:<q>` | `nicovideo.jp/watch/…` | Japanese-heavy. Optional; off by default. |

Make sources a setting (on/off each), so a broken one can be switched off without an app update. The engine
updater already refreshes yt-dlp daily, which fixes most search breakages without an app release.

## How it fits the existing code

The seams named here were checked on `main` at `f24900a`.

### Engine: `chaya_engine/search.py` (new)

```python
def search(query, sources, per_source, cache_dir, deadline_seconds=12):
    """Returns JSON: {"results": [...], "failed": {"<source>": "<kind>"}}"""
```

- Each yt-dlp source runs `YoutubeDL({... 'extract_flat': 'in_playlist', 'playlistend': per_source,
  'socket_timeout': 10, 'skip_download': True}).extract_info(target, download=False)`. Flat extraction
  returns titles, durations, channels and thumbnails without resolving formats, so one source takes about a
  second rather than twenty.
  - Use the same base options as `extract.py:30-40`.
  - Leave out `noplaylist`, which `extract()` sets.
- Run the sources in a `ThreadPoolExecutor`, with one `YoutubeDL` per thread. The work is network-bound, so
  the GIL is not the limit.
- Return whatever has arrived by the deadline. A source that times out or raises is listed under `failed`,
  with its kind from the existing `extract._kind_of` (bot_check, geo, network and so on).
- Each result is shaped as `{source, url, title, channel, duration, thumbnail, views, kind: "video" |
  "playlist", count}`. A playlist entry from a YouTube playlist search has `_type: "url"` and an
  `ie_key` of `YoutubeTab`.
- Archive.org and Sepia are plain JSON. Fetch them in Kotlin with OkHttp instead (below), where
  MockWebServer can test them, as CLAUDE.md asks of network code.
- `paths._KEEP` (`paths.py:19`) does not list the new module, so an engine update re-imports it, as wanted.

### Kotlin: engine call

- Add `PlatformEngine.search(query, sources)` next to `extract` (`PlatformEngine.kt:32`).
  - Give it its own `Mutex`, as `oneListing` (`:40`) does for account listings. Under `oneAtATime` a
    search would queue behind a link lookup and the other way round.
- Kotlin cannot interrupt a running Python call.
  - Keep a `ticket`, as `PlatformLinks` does (`PlatformLinks.kt:109`), so a newer query discards an older
    answer.
  - Keep the Python deadline short, so an abandoned search does not hold the engine for long.
- Add `SearchSource` implementations for archive.org and Sepia over the app's OkHttp client. All sources
  meet in one `MultiSearch` that runs them concurrently and merges the results as they arrive. Show results
  as they arrive; do not wait for the slowest source.

### Ranking: `search/SearchRanker.kt` (new, pure Kotlin)

This class has no Android imports, so it can be tested in the scratch Kotlin/JVM project (see CLAUDE.md).

1. **Normalise** both the query and the titles:
   - lowercase, and strip accents with `java.text.Normalizer` (NFKD, then drop combining marks);
   - turn `&` into "and" and drop the remaining punctuation;
   - collapse spaces;
   - keep CJK characters, comparing them as single-character tokens.
2. **Read episode markers** in both the query and the titles:
   - forms: `s1e2`, `s01e02`, `1x02`, "season 1 episode 2", "ep 2", "episode 2", "part 2";
   - each becomes `(season?, episode?)`;
   - years in the range 1900–2099 are kept as a separate token.
3. **Title match.** Score how many query tokens appear in the title:
   - weight each token by how rare it is among this result set;
   - a title token within edit distance 1 of a query token counts as present (2 for tokens of 8+
     characters), using Damerau–Levenshtein, so swapped letters count as one typo;
   - this is the main signal, about 60% of the score.
4. **Episode match.** A matching season and episode adds about 15%. A different episode from the same
   season subtracts.
5. **Length fit.**
   - When the query reads like a film (no episode marker, a year, or "movie"/"film"), results over 60
     minutes gain.
   - Results under 2 minutes lose, unless the query asked for a trailer or a clip.
   - About 10%.
6. **Signals of a copy rather than the thing.** "trailer", "clip", "reaction", "review", "explained",
   "edit", "#shorts" in a title subtract, unless the query contains that word.
7. **Popularity.** `log10(views)`, scaled within the result set; about 10%.
8. **Duplicates.** The same normalised title with durations within 2% of each other collapse into one row
   that lists every source. Keep the source with the best quality as the default.

Test this with fixed lists of titles. For example:
- "mentlist s1e2" must rank "The Mentalist S01E02 Red Hair and Silver Tape" above "The Mentalist S01E01"
  and above "Mentalist trailer";
- a 4-second Short must never outrank a 90-minute upload for "the kid 1921".

### Single download

`BrowserViewModel.openLink` (`BrowserViewModel.kt:589`) runs only for YouTube, Instagram, TikTok and X,
because `PlatformMatcher.match` (`PlatformMatcher.kt:38`) knows only those. A search result is already known
to be a yt-dlp URL, so:

- Add a way to look up a match that came from search, for example `PlatformLinks.look(match: PlatformMatch)`,
  where the match carries the source as its platform.
- The rest is reused as it is: `PlatformLinks.resolve` → `FormatSelector.choices` → `PlatformSheet` →
  `downloadLink`.
- `PlatformMatch.platform` currently names the four sites. Widen it, or add a source field, and check
  every `when` over it.

### Seasons and playlists as a ZIP

Account downloads already make ZIPs, and that pipeline should be reused:
- `ArchiveLister` (`Archive.kt:10`)
- `DownloadManager.startArchive` (`:230`) → `runArchive` (`:649`) → `fetchArchiveEntry` (`:698`) →
  `packArchive` (`:768`)

Two differences matter.

1. **Entries must be resolved when they are fetched, not when they are listed.**
   - Instagram picture URLs last for hours. YouTube and Bilibili media URLs expire and are often split into
     separate video and audio.
   - So the lister writes each entry as a page URL (`{"name": "S01E02 Red Hair….mp4", "page":
     "https://youtube.com/watch?v=…"}`).
   - `fetchArchiveEntry` resolves a `page` entry through `PlatformEngine.extract` just before fetching it.
     It then picks a format with `FormatSelector` at the quality the person chose for the whole season.
     Where the video and audio are separate, it joins them the way a link download already does
     (`audioToMerge`).
   - Archive.org entries are plain files and can be fetched as they are listed.
   - The lister is a new `chaya_engine/playlists.py`, with `list_playlist(url, cache_dir, out_path,
     stop_path)`. It mirrors `profiles.list_profile`: JSONL written to `.partial`, a stop file, and the same
     kinds of error. The source scheme is `chaya-archive:playlist:<url>`.
2. **Disk space.**
   - `packArchive` copies every fetched file into the ZIP, so a 10 GB season needs about 20 GB free at its
     peak.
   - Choose one of these:
     - delete each file once it is in the ZIP;
     - better, write the ZIP as each file finishes, with `ZipOutputStream` stored at `NO_COMPRESSION` as it
       is now;
     - offer "Save as a folder" next to "Save as ZIP".
   - Check free space before starting (`StatFs`) against the sum of the estimated sizes, and say how much
     is needed.

The Downloads library already shows a ZIP as one tile and opens it to its files (`ZipView`). Videos inside
a ZIP cannot be streamed by the player without unpacking. A folder saved by "Save as a folder" plays in
place. This is a reason to make the folder the default for video and keep ZIP for sharing.

### Screen and navigation

- Add `Screen.Search` to `Screen.kt` and a `composable` for it in `ChayaNavHost.kt:57-98`.
- Add a `HomeChip(Icons.Default.Search, "Search videos", …)` beside Bookmarks and History in
  `BrowserChrome.kt:736`.
- Hand a picked result back to the browser through the existing `PagesToOpen` pattern, or open the link
  sheet over the search screen itself. The second keeps the results in place when the sheet closes, which
  matters when downloading several episodes.
- Follow `DESIGN.md` for the screen:
  - one row per result: thumbnail, title, channel · source · duration, and a "12 episodes" badge on a
    playlist;
  - source chips to filter;
  - a strip at the top naming any source that failed ("Bilibili didn't answer").

## Privacy and docs

- The query goes straight to each platform that is switched on. Nothing goes anywhere else. Update
  `PRIVACY_POLICY.md` to say which platforms receive search words, and that each sees the person's IP
  address, as when visiting the site.
- Do not log the query.
  - Log `SearchRan(sources, resultCount, failed)` with the query's length only.
  - It needs a new `ChayaEvent` with branches in `EventLog.format` (`EventLog.kt:61`) and
    `CrashReporter.formatEvent`.
- Recent searches, if they are kept at all, stay on the phone, behind the same switch as history.
- Update `LIMITATIONS.md` with:
  - which platforms are searched;
  - that results are only what those platforms return;
  - that downloads still follow the existing rules: no DRM, no sign-in unless chosen.
- Update `docs/ARCHITECTURE.md` with the new `search` package and `docs/ROADMAP.md` with each PR.

## Build order (a stack of PRs, merged in order)

1. **Engine search, no screen.**
   - Build:
     - `search.py` and `PlatformEngine.search`;
     - the archive.org and Sepia sources;
     - `MultiSearch`;
     - `SearchRanker`.
   - Add a "Try search" row in Diagnostics, so each source can be checked on the phone first.
   - Tests:
     - pytest with a fake `YoutubeDL`, copying `test_extract.py:112-180`, covering the result shape,
       deadlines and a failing source;
     - MockWebServer tests for archive.org and Sepia;
     - plain JUnit for the ranker;
     - an `androidTest` that runs `search.search` against MockWebServer under Chaquopy, as
       `EngineOnDeviceTest` does.
2. **Search screen and single downloads.**
   - Build:
     - the screen and its start-page chip;
     - looking up a match that came from search;
     - the result sheet.
   - Tests:
     - Robolectric ViewModel tests with a stand-in search, in the style of `BrowserViewModelLinksTest`;
     - a Compose test for the result row and the failed-source strip.
   - Label the PR `ui-check`.
3. **Episodes of a playlist or item.**
   - Build:
     - `playlists.py`, listing entries flat;
     - an episode list with checkboxes;
     - downloading the chosen episodes as ordinary single downloads, grouped in Downloads under the
       playlist's name.
   - This gives most of the value before the ZIP work.
4. **A whole season as a ZIP or a folder.**
   - Build:
     - entries resolved when fetched;
     - the ZIP written as files finish;
     - the free-space check;
     - "Save as a folder".
   - Tests: extend `DownloadManagerArchiveTest` with `page` entries and a resolver stand-in.
   - Label the PR `ui-check`.

## Things to check first, on the phone

1. Each source answers a plain query from the phone's network, and how long it takes. Use the Diagnostics
   row from PR 1.
2. The YouTube playlist filter value (`sp=EgIQAw%3D%3D`) still returns playlists.
3. Whether Dailymotion's API needs a key now. If it does, use yt-dlp's search-page extractor instead.
4. Whether Bilibili search works without signing in, from the person's country.
5. How many results per source keep a search under about 3 seconds. Start with 15.
