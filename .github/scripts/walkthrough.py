#!/usr/bin/env python3
"""Cloud-emulator walkthrough for Chaya.

Drives the debug build through two scenarios and saves evidence to ./evidence:

  hls    A public hls.js demo page. The media sheet should list ONE main
         stream, the quality picker one entry per rendition, and the chosen
         rendition should download.
  local  A page served from this runner with a pre-roll ad, a decorative
         background loop and the real video. The real video should be the main
         item and the ad should be folded away.

Every step is best effort: a failed tap is recorded and the run continues, so
one broken step never hides what happened after it. The visible UI text of each
state is printed to the job log, so results can be read without screenshots.
"""
import re
import shlex
import subprocess
import sys
import time
import traceback
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = "com.chaya.app"
APK = "app/build/outputs/apk/debug/app-debug.apk"
EVIDENCE = Path("evidence")
FIXTURE = Path("build/fixture")
PORT = 8000
HOST_URL = f"http://10.0.2.2:{PORT}"  # the emulator's alias for this runner
HLS_DEMO = "https://hlsjs.video-dev.org/demo/"

# Positions as fractions of the screen, used only when a node cannot be found by text.
FALLBACK = {
    "url_bar": (0.44, 0.065),
    "downloads_button": (0.91, 0.065),
    "fab": (0.883, 0.858),
}

PAGE = """<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Launch Event 2026 | Example News</title>
<meta property="og:title" content="Launch Event 2026">
<meta property="og:site_name" content="Example News">
<meta property="og:video" content="{BASE}/media/launch-event.mp4">
<meta property="og:image" content="{BASE}/media/poster.jpg">
<script type="application/ld+json">
{"@context":"https://schema.org","@type":"VideoObject","name":"Launch Event 2026",
 "contentUrl":"{BASE}/media/launch-event.mp4","duration":"PT1M30S",
 "thumbnailUrl":"{BASE}/media/poster.jpg"}
</script>
<style>body{font-family:sans-serif;margin:12px}video{width:100%;background:#000;display:block;margin:8px 0}</style>
</head><body>
<h1>Launch Event 2026</h1>
<div id="ad-slot" class="preroll-container">
  <video src="/media/vast/preroll-15s.mp4" muted autoplay playsinline></video>
</div>
<video src="/media/launch-event.mp4" poster="/media/poster.jpg" controls autoplay playsinline preload="auto"></video>
<video src="/media/hero-loop.mp4" muted autoplay loop playsinline style="height:80px;object-fit:cover"></video>
<p>Test page for the Chaya media sheet.</p>
</body></html>
"""

class AppDied(Exception):
    """The app's process disappeared mid-scenario (e.g. the emulator's system apps restarted)."""


results = []  # (step, ok, detail)
shot_count = 0
screen_w, screen_h = 1080, 2400
fixture_error = None


def log(message):
    print(f"[walkthrough {time.strftime('%H:%M:%S')}] {message}", flush=True)


def record(step, ok, detail=""):
    results.append((step, ok, detail))
    log(f"{'ok  ' if ok else 'FAIL'} {step} {detail}".rstrip())


def run(cmd, timeout=120, text=True):
    return subprocess.run(cmd, capture_output=True, text=text, timeout=timeout)


def adb(*args, timeout=120):
    return run(["adb", *args], timeout=timeout).stdout


def sh(command, timeout=120):
    return adb("shell", command, timeout=timeout)


def app_alive():
    return bool(sh(f"pidof {PKG}").strip())


def require_app():
    if not app_alive():
        raise AppDied(f"{PKG} is not running")


# --------------------------------------------------------------------------- #
# Screen reading and tapping
# --------------------------------------------------------------------------- #

def ui_dump():
    """Returns (nodes, raw_xml) for the active window; ([], b"") when it cannot be read."""
    for _ in range(4):
        out = run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], timeout=60)
        if "dumped" in (out.stdout + out.stderr).lower():
            raw = run(["adb", "exec-out", "cat", "/sdcard/ui.xml"], timeout=30, text=False).stdout
            try:
                return [n.attrib for n in ET.fromstring(raw).iter("node")], raw
            except ET.ParseError:
                pass
        time.sleep(2)
    return [], b""


def center(node):
    nums = [int(v) for v in re.findall(r"-?\d+", node.get("bounds", ""))]
    if len(nums) != 4:
        return None
    return (nums[0] + nums[2]) // 2, (nums[1] + nums[3]) // 2


def visible_texts(nodes):
    seen = []
    for node in nodes:
        for key in ("text", "content-desc"):
            value = (node.get(key) or "").strip()
            if value and value not in seen:
                seen.append(value)
    return seen


def find(nodes, text=None, text_prefix=None, text_contains=None, desc_contains=None, cls=None):
    for node in nodes:
        node_text = (node.get("text") or "").strip()
        node_desc = node.get("content-desc") or ""
        if text is not None and node_text != text:
            continue
        if text_prefix is not None and not node_text.startswith(text_prefix):
            continue
        if text_contains is not None and text_contains not in node_text:
            continue
        if desc_contains is not None and desc_contains not in node_desc:
            continue
        if cls is not None and node.get("class") != cls:
            continue
        if center(node) is not None:
            return node
    return None


def tap_node(node):
    x, y = center(node)
    adb("shell", "input", "tap", str(x), str(y))


def tap_fraction(fx, fy):
    adb("shell", "input", "tap", str(int(screen_w * fx)), str(int(screen_h * fy)))


def dismiss_not_responding(nodes):
    """An emulator that is still settling can show an 'isn't responding' dialog; wait it out."""
    if any("responding" in (n.get("text") or "") for n in nodes):
        wait_button = find(nodes, text="Wait")
        if wait_button:
            tap_node(wait_button)
            record("dismissed an 'isn't responding' dialog", True)


def wait_for(label, timeout=40, **criteria):
    """Polls the screen until a node matches; returns (nodes, node-or-None)."""
    deadline = time.time() + timeout
    while True:
        require_app()
        nodes, _ = ui_dump()
        dismiss_not_responding(nodes)
        node = find(nodes, **criteria)
        if node:
            record(label, True)
            return nodes, node
        if time.time() > deadline:
            record(label, False, f"not found: {criteria}")
            return nodes, None
        time.sleep(2)


def tap_when(label, timeout=40, fallback=None, **criteria):
    _, node = wait_for(label, timeout, **criteria)
    if node:
        tap_node(node)
        return True
    if fallback:
        tap_fraction(*fallback)
    return False


def snapshot(name):
    """Saves a screenshot, the raw UI tree and the visible texts; prints the texts to the log."""
    global shot_count
    shot_count += 1
    base = f"{shot_count:02d}-{name}"
    png = run(["adb", "exec-out", "screencap", "-p"], timeout=60, text=False).stdout
    (EVIDENCE / f"{base}.png").write_bytes(png)
    nodes, raw = ui_dump()
    if raw:
        (EVIDENCE / f"{base}.xml").write_bytes(raw)
    texts = visible_texts(nodes)
    (EVIDENCE / f"{base}.txt").write_text("\n".join(texts), encoding="utf-8")
    shown = " | ".join(t.replace("\n", " / ")[:90] for t in texts[:40])
    log(f"{base}: {shown}")
    return nodes


def type_text(value, chunk=8):
    """Types in small pieces; one big burst can drop characters on a busy emulator."""
    for i in range(0, len(value), chunk):
        adb("shell", f"input text {shlex.quote(value[i:i + chunk])}")
        time.sleep(0.4)


def check_keyboard_closed():
    """The app must close the keyboard itself after Go; it covered the page and download button before."""
    require_app()
    snapshot("after-go")
    shown = sh("dumpsys input_method | grep -E 'mInputShown|isInputViewShown'")
    open_now = "mInputShown=true" in shown or "isInputViewShown=true" in shown
    detail = " ".join(shown.split())[:120] if open_now else ""
    record("keyboard closes after Go", not open_now, detail)


# --------------------------------------------------------------------------- #
# App control
# --------------------------------------------------------------------------- #

def launch_fresh():
    sh(f"am force-stop {PKG}")
    sh(f"pm clear {PKG}")
    sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    adb("logcat", "-c")
    adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity", timeout=180)
    _, field = wait_for("home screen shows the address field", 90, cls="android.widget.EditText")
    return field is not None


def open_url(url):
    nodes, _ = ui_dump()
    field = find(nodes, cls="android.widget.EditText")
    if field:
        tap_node(field)
    else:
        tap_fraction(*FALLBACK["url_bar"])
    time.sleep(1.5)
    type_text(url)
    time.sleep(1)
    adb("shell", "input", "keyevent", "66")  # Enter -> Go
    time.sleep(3)
    check_keyboard_closed()
    record(f"opened {url}", True)


def cache_kb():
    out = sh(f"run-as {PKG} du -sk cache/media3-cache 2>&1")
    match = re.match(r"\s*(\d+)", out)
    return int(match.group(1)) if match else out.strip()[:60]


def monitor_download(tag, seconds):
    """Watches the Downloads screen and the on-disk stream cache until the file is saved or time runs out."""
    start = time.time()
    last_shot = 0.0
    done = False
    while time.time() - start < seconds:
        require_app()
        nodes, _ = ui_dump()
        texts = visible_texts(nodes)
        interesting = [t.replace("\n", " / ") for t in texts if re.search(r"\d+%|Saved|Failed|Paused|\bMB\b|\bKB\b| B /", t)]
        log(f"[{tag}] t={int(time.time() - start)}s cache={cache_kb()}KB ui={interesting[:6]}")
        if any("Saved" in t for t in texts):
            done = True
            break
        if time.time() - last_shot > 60:
            snapshot(f"{tag}-downloads-{int(time.time() - start)}s")
            last_shot = time.time()
        time.sleep(15)
    snapshot(f"{tag}-downloads-final")
    record(f"{tag}: download finishes", done, f"after {int(time.time() - start)}s")


def save_logcat(tag):
    text = adb("logcat", "-d", "-v", "threadtime", timeout=180)
    (EVIDENCE / f"logcat-{tag}.txt").write_text(text, encoding="utf-8", errors="replace")
    keep = re.compile(r"chaya|DownloadManager|ExoPlayer|HlsMediaSource|HlsDownloader|StreamDownloader|"
                      r"AndroidRuntime|FATAL|StrictMode|media3", re.I)
    lines = [line for line in text.splitlines() if keep.search(line)]
    (EVIDENCE / f"logcat-{tag}-app.txt").write_text("\n".join(lines[-3000:]), encoding="utf-8")
    if "FATAL EXCEPTION" in text and f"Process: {PKG}" in text:
        record(f"{tag}: the app crashed", False, "see logcat")


# --------------------------------------------------------------------------- #
# Scenarios
# --------------------------------------------------------------------------- #

def scenario_hls():
    if not launch_fresh():
        return
    snapshot("hls-home")
    open_url(HLS_DEMO)
    wait_for("hls: media button appears", 120, desc_contains="Detected media")
    time.sleep(20)  # let the stream play so the page reports its player and the stream pieces
    snapshot("hls-page")
    tap_when("hls: open the media sheet", 20, FALLBACK["fab"], desc_contains="Detected media")
    wait_for("hls: media sheet shows", 30, text="On this page")
    snapshot("hls-sheet")
    if not tap_when("hls: tap Download", 20, text="Download"):
        return
    wait_for("hls: quality picker shows", 60, text="Choose quality")
    snapshot("hls-picker")
    tap_when("hls: pick 184p", 20, text_prefix="184p")
    time.sleep(1)
    snapshot("hls-picker-184p")
    tap_when("hls: start the download", 20, text_prefix="Download 184p")
    time.sleep(3)
    # The download does not need the page. Leaving it stops the 720p video the page keeps decoding,
    # which otherwise starves the emulator for the minutes the download takes.
    tap_when("hls: go Home, which unloads the page", 20, desc_contains="Home")
    time.sleep(2)
    tap_when("hls: open Downloads", 20, FALLBACK["downloads_button"], desc_contains="Downloads")
    monitor_download("hls", 300)


def make_fixture():
    media = FIXTURE / "media"
    (media / "vast").mkdir(parents=True, exist_ok=True)

    def video(out, source, seconds, size, audio=True):
        cmd = ["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i",
               f"{source}=duration={seconds}:size={size}:rate=10"]
        if audio:
            cmd += ["-f", "lavfi", "-i", f"sine=frequency=440:duration={seconds}"]
        cmd += ["-c:v", "libx264", "-preset", "ultrafast", "-crf", "36", "-pix_fmt", "yuv420p"]
        if audio:
            cmd += ["-c:a", "aac", "-b:a", "32k", "-shortest"]
        cmd += ["-movflags", "+faststart", str(out)]
        subprocess.run(cmd, check=True, timeout=300)

    video(media / "launch-event.mp4", "testsrc", 90, "640x360")
    video(media / "vast" / "preroll-15s.mp4", "testsrc2", 15, "320x180")
    video(media / "hero-loop.mp4", "smptebars", 8, "640x360", audio=False)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=640x360:rate=1",
                    "-frames:v", "1", str(media / "poster.jpg")], check=True, timeout=60)
    (FIXTURE / "index.html").write_text(PAGE.replace("{BASE}", HOST_URL), encoding="utf-8")


def start_fixture_server():
    servers = (
        ["npx", "--yes", "http-server", str(FIXTURE), "-p", str(PORT), "-c-1", "--silent"],  # supports Range
        [sys.executable, "-m", "http.server", str(PORT), "--directory", str(FIXTURE)],
    )
    for cmd in servers:
        proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(40):
            time.sleep(1)
            if proc.poll() is not None:
                break
            if run(["curl", "-sfI", f"http://127.0.0.1:{PORT}/index.html"]).returncode == 0:
                log(f"fixture server up ({cmd[0]} {cmd[1]})")
                return proc
        proc.terminate()
    raise RuntimeError("could not start the fixture server")


def scenario_local():
    if fixture_error:
        record("local: test page", False, fixture_error)
        return
    if not launch_fresh():
        return
    open_url(f"{HOST_URL}/index.html")
    wait_for("local: media button appears", 90, desc_contains="Detected media")
    time.sleep(15)
    snapshot("local-page")
    tap_when("local: open the media sheet", 20, FALLBACK["fab"], desc_contains="Detected media")
    wait_for("local: media sheet shows", 30, text="On this page")
    snapshot("local-sheet")
    if tap_when("local: show likely ads", 10, desc_contains="likely ads"):
        time.sleep(1)
        snapshot("local-sheet-ads")
    if not tap_when("local: tap Download", 20, text="Download"):
        return
    time.sleep(5)
    tap_when("local: open Downloads", 20, FALLBACK["downloads_button"], desc_contains="Downloads")
    monitor_download("local", 90)
    log("saved files: " + sh(f"run-as {PKG} ls -la files/downloads 2>&1").strip())


def main():
    global screen_w, screen_h, fixture_error
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    adb("wait-for-device")
    adb("logcat", "-G", "16M")
    install = run(["adb", "install", "-r", "-t", "-g", APK], timeout=300)
    record("install the debug build", "Success" in install.stdout, install.stdout.strip()[-80:])
    size = re.search(r"(\d+)x(\d+)", sh("wm size").strip().splitlines()[-1])
    if size:
        screen_w, screen_h = int(size.group(1)), int(size.group(2))
    log(f"screen {screen_w}x{screen_h}")
    # Show the soft keyboard even with the emulator's hardware keyboard, so the keyboard check means something.
    sh("settings put secure show_ime_with_hard_keyboard 1")
    time.sleep(20)  # let the freshly booted system finish its own start-up work

    server = None
    try:
        make_fixture()
        server = start_fixture_server()
    except Exception:
        fixture_error = traceback.format_exc(limit=2).strip().splitlines()[-1]
        record("build the local test page", False, fixture_error)

    for name, scenario in (("hls", scenario_hls), ("local", scenario_local)):
        for attempt in (1, 2):
            first_result = len(results)
            try:
                scenario()
                break
            except AppDied as died:
                save_logcat(f"{name}-attempt{attempt}")
                del results[first_result:]  # the aborted attempt proves nothing either way
                if attempt == 1:
                    record(f"{name}: the app was killed mid-run, retrying once", True, str(died))
                else:
                    record(f"{name}: the app was killed again", False, str(died))
            except Exception:
                record(f"{name}: scenario raised", False, traceback.format_exc(limit=3).strip().splitlines()[-1])
                break
        save_logcat(name)

    if server:
        server.terminate()

    lines = ["# Emulator walkthrough", "", "| Step | Result | Detail |", "|---|---|---|"]
    for step, ok, detail in results:
        lines.append(f"| {step} | {'ok' if ok else 'FAIL'} | {detail.replace('|', '/')} |")
    summary = "\n".join(lines)
    (EVIDENCE / "summary.md").write_text(summary, encoding="utf-8")
    print("\n" + summary, flush=True)
    sys.exit(1 if any(not ok for _, ok, _ in results) else 0)


if __name__ == "__main__":
    main()
