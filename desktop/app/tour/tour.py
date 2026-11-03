"""A tour of Chaya for Windows, with a screenshot of the whole screen at each step.

CI installs the app from its installer and runs this on a Windows machine (`desktop.yml`, job `tour`), so what
the app looks like there can be seen rather than guessed: where the page sits, fonts, the title bar, a real
lookup. It drives the window's own content by running script in it, and photographs the screen, the browser's
page and Windows' own frame included.

Two ways in:
- `--via devtools` (Windows): starts the app with WebView2's DevTools port (9333) open and speaks the DevTools
  protocol to the window. The port is opened by `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS`, or, for an app run
  elevated as on CI, where WebView2 ignores that variable, by the machine's WebView2 policy (`desktop.yml`).
  msedgedriver cannot be used: it opens the port through those variables.
- `--via webdriver` (Linux): `tauri-driver` with WebKitWebDriver starts the app, for trying the tour here.

A step that cannot be done (a site that refuses a data centre, a lookup that times out) is noted and the tour
goes on; only a tour that cannot start the app fails. What each step saw goes into `steps.json` beside the
pictures.

    python tour.py --app PATH --out FOLDER [--via devtools|webdriver] [--sites sites.json] [--seed]

`--sites` swaps the internet's addresses for others (a local test site, when trying the tour off Windows). `--seed`
puts two saved videos in place before the app starts (`fixtures/`: a 30-second clip with its English subtitles, and
the JFK sample whisper.cpp ships, with none; and their entries in the Downloads list), for the player's step and for
making subtitles on the PC. It writes into the person's own Downloads folder and Chaya's own
data folder, so only on a machine kept for the tour (CI's).
"""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

FIXTURES = Path(__file__).parent / 'fixtures'
CLIP = 'A walk in the park'
# 11 seconds of a speech, with no subtitles: Chaya makes them.
SPEECH = 'Ask not'

SITES = {
    # A short, old, public video: the first one uploaded to YouTube.
    'youtube': 'https://www.youtube.com/watch?v=jNQXAC9IVRw',
    # A public Pinterest pin, as the phone's link tests have it.
    'pin': 'https://www.pinterest.com/pin/664281013778109217/',
    'page': 'https://en.wikipedia.org/wiki/Cat',
    # A page with a video in a <video> element, and ads.
    'video_page': 'https://www.w3schools.com/html/html5_video.asp',
    'news': 'https://www.theguardian.com/international',
}


def screenshot(path):
    """The whole screen, as the person would see it."""
    try:
        from PIL import ImageGrab
        ImageGrab.grab(all_screens=True).save(path)
        return
    except Exception:
        pass
    # Off Windows, without Pillow's screen grab.
    subprocess.run(['import', '-window', 'root', str(path)], check=True)


class WebDriverWindow:
    """The window's content through WebDriver (`tauri-driver`, which starts the app)."""

    def __init__(self, app, address='http://127.0.0.1:4444'):
        from selenium import webdriver
        from selenium.common.exceptions import WebDriverException
        from selenium.webdriver.common.options import ArgOptions

        options = ArgOptions()
        options.set_capability('browserName', 'wry')
        options.set_capability('tauri:options', {'application': os.path.abspath(app)})
        self.driver = None
        last = None
        for _ in range(10):
            try:
                self.driver = webdriver.Remote(command_executor=address, options=options)
                break
            except WebDriverException as e:
                last = e
                time.sleep(3)
        if self.driver is None:
            raise RuntimeError(f'The app could not be started: {last}')

    def run(self, script):
        return self.driver.execute_script(script)

    def close(self):
        self.driver.quit()


class DevToolsWindow:
    """The window's content through WebView2's DevTools port, opened by starting the app with it."""

    PORT = 9333

    def __init__(self, app, out):
        import websocket  # websocket-client

        if os.name == 'nt':
            # The runner's own console stays open on the desktop; out of the pictures with it.
            subprocess.run(['powershell', '-NoProfile', '-Command', '(New-Object -ComObject Shell.Application).MinimizeAll()'])
            time.sleep(1)
        env = dict(os.environ, WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=f'--remote-debugging-port={self.PORT}')
        self.log = open(out / 'app.log', 'w', encoding='utf-8')
        self.process = subprocess.Popen([app], env=env, stdout=self.log, stderr=subprocess.STDOUT)
        target, targets = None, []
        for _ in range(45):
            if self.process.poll() is not None:
                break
            try:
                with urllib.request.urlopen(f'http://127.0.0.1:{self.PORT}/json/list', timeout=2) as answer:
                    targets = json.load(answer)
                # The window's own content; the browser's page, when one is open, is another target.
                target = next((t for t in targets if t.get('type') == 'page' and 'tauri.localhost' in t.get('url', '')), None)
                if target:
                    break
            except OSError:
                pass
            time.sleep(1)
        if not target:
            self.why_not(out, targets)
            self.process.kill()
            raise RuntimeError('The app never opened its DevTools port; see app.log, processes.txt and no-port.png.')
        self.socket = websocket.create_connection(target['webSocketDebuggerUrl'], timeout=30, suppress_origin=True)
        self.next_id = 0

    def why_not(self, out, targets):
        """What there is to see when the port never opens: the app, the browser processes it started and their
        command lines, the screen."""
        lines = [f'app: {"still running" if self.process.poll() is None else f"ended with {self.process.returncode}"}',
                 f'targets: {json.dumps(targets)}']
        if os.name == 'nt':
            query = ("Get-CimInstance Win32_Process | Where-Object { $_.Name -match 'chaya|msedgewebview2' } | "
                     "ForEach-Object { \"$($_.ProcessId) $($_.Name) $($_.CommandLine)\" }")
            done = subprocess.run(['powershell', '-NoProfile', '-Command', query], capture_output=True, text=True)
            lines.append(done.stdout + done.stderr)
        (out / 'processes.txt').write_text('\n'.join(lines), encoding='utf-8')
        print('\n'.join(lines)[:4000], flush=True)
        try:
            screenshot(out / 'no-port.png')
        except Exception:
            pass

    def send(self, method, params, socket=None):
        """Sends one DevTools command (to the window, unless another target's `socket` is given); its result."""
        socket = socket or self.socket
        self.next_id += 1
        socket.send(json.dumps({'id': self.next_id, 'method': method, 'params': params}))
        while True:
            answer = json.loads(socket.recv())
            if answer.get('id') == self.next_id:
                return answer.get('result', {})

    def run(self, script, socket=None, gesture=False):
        expression = f'(function () {{ {script} }})()'
        params = {'expression': expression, 'returnByValue': True, 'userGesture': gesture}
        result = self.send('Runtime.evaluate', params, socket)
        if 'exceptionDetails' in result:
            raise RuntimeError(result['exceptionDetails'].get('exception', {}).get('description', 'script failed'))
        return result.get('result', {}).get('value')

    def run_in_page(self, script, gesture=False):
        """Runs `script` in the browser's page, which is a DevTools target of its own; None with no page open.
        `gesture`: as if the person had just clicked, which fullscreen asks for."""
        import websocket

        with urllib.request.urlopen(f'http://127.0.0.1:{self.PORT}/json/list', timeout=2) as answer:
            targets = json.load(answer)
        target = next((t for t in targets if t.get('type') == 'page' and 'tauri.localhost' not in t.get('url', '')), None)
        if not target:
            return None
        socket = websocket.create_connection(target['webSocketDebuggerUrl'], timeout=10, suppress_origin=True)
        try:
            return self.run(script, socket, gesture)
        finally:
            socket.close()

    def press(self, key, code):
        """Presses `key` in the window as the keyboard would, so WebView2's own keys see it too."""
        for kind in ('rawKeyDown', 'keyUp'):
            self.send('Input.dispatchKeyEvent', {'type': kind, 'key': key, 'code': key, 'windowsVirtualKeyCode': code})

    def close(self):
        try:
            self.socket.close()
        finally:
            self.process.kill()
            self.log.close()


class Tour:
    def __init__(self, window, out):
        self.window = window
        self.out = out
        self.steps = []

    def shot(self, name, **seen):
        path = self.out / f'{len(self.steps) + 1:02d}-{name}.png'
        time.sleep(0.6)  # let a transition finish
        screenshot(path)
        self.steps.append({'step': name, 'picture': path.name, **seen})
        print(f'{path.name}: {json.dumps(seen, ensure_ascii=False)}', flush=True)

    def failed(self, name, error):
        self.steps.append({'step': name, 'failed': str(error).splitlines()[0][:300]})
        print(f'{name}: failed: {error}', flush=True)
        try:
            self.shot(f'{name}-failed')
        except Exception:
            pass

    def js(self, script, gesture=False):
        if gesture and isinstance(self.window, DevToolsWindow):
            return self.window.run(script, gesture=True)
        return self.window.run(script)

    def press_in_player(self, key):
        """A key pressed in the player, as the keyboard sends it there."""
        self.js("document.getElementById('player-video').dispatchEvent(new KeyboardEvent('keydown',"
                f" {{key: {json.dumps(key)}, bubbles: true, cancelable: true}})); return 1")

    def click(self, selector, index=0):
        """Clicks what `selector` finds (the `index`th); false when there is nothing there."""
        return self.js(f"const e = document.querySelectorAll({json.dumps(selector)})[{index}]; if (!e) return false; e.click(); return true;")

    def wait(self, condition, seconds, every=0.5):
        """Waits until `condition()` is truthy; returns its last value."""
        end = time.time() + seconds
        value = None
        while time.time() < end:
            try:
                value = condition()
            except Exception:
                value = None
            if value:
                return value
            time.sleep(every)
        return value

    def type_address(self, text):
        """What typing an address and pressing Enter does: the bar's form is submitted with it."""
        self.js(
            "const field = document.getElementById('address'); field.focus(); "
            f"field.value = {json.dumps(text)}; field.dispatchEvent(new Event('input')); "
            "document.getElementById('address-form').requestSubmit(); return true;"
        )

    def card_text(self):
        return self.js("const c = document.querySelector('#result .card'); return c ? c.innerText : '';")


def seed_saved_videos():
    """The tour's clips in Downloads, the first with its subtitles, each listed as a finished download in Chaya's data
    folder (as the app keeps them: `downloads.json` in %APPDATA%\\com.chaya.desktop). Their numbers."""
    return seed_saved_video(CLIP, [f'{CLIP}.en.srt']), seed_saved_video(SPEECH, [])


def data_folder():
    """Chaya's data folder, made if it is not there yet."""
    if os.name == 'nt':
        data = Path(os.environ['APPDATA']) / 'com.chaya.desktop'
    else:
        data = Path(os.environ.get('XDG_DATA_HOME', Path.home() / '.local' / 'share')) / 'com.chaya.desktop'
    data.mkdir(parents=True, exist_ok=True)
    return data


def seed_tabs(url):
    """A tab kept from a last run, as the app keeps them (`tabs.json`): shown in the strip, its page not made until it
    is clicked."""
    (data_folder() / 'tabs.json').write_text(json.dumps([{'url': url, 'title': 'Kept from the last run'}]),
                                             encoding='utf-8')


def seed_saved_video(clip, beside):
    """`clip`.mp4 and the files `beside` it in Downloads, and listed as a finished download; its number."""
    downloads = Path.home() / 'Downloads'
    data = data_folder()
    downloads.mkdir(parents=True, exist_ok=True)
    video = downloads / f'{clip}.mp4'
    for name in (f'{clip}.mp4', *beside):
        (downloads / name).write_bytes((FIXTURES / name).read_bytes())
    store = data / 'downloads.json'
    listed = json.loads(store.read_text(encoding='utf-8')) if store.exists() else []
    task = json.loads((FIXTURES / 'saved-video.json').read_text(encoding='utf-8'))
    size = video.stat().st_size
    task.update(id=max([t['id'] for t in listed] + [0]) + 1, title=clip, path=str(video), done=size, total=size,
                added=int(time.time()) - 60, ended=int(time.time()) - 60)
    store.write_text(json.dumps(listed + [task]), encoding='utf-8')
    return task['id']


def watch(tour, id):
    """A saved video opens in Chaya's player and plays with its subtitles, drawn over it and listed beside it;
    clicking a line plays from there; F fills the window; closed and opened again, it starts where it stopped."""
    row = f'#download-list .row[data-id="{id}"] .row__title'
    video = "document.getElementById('player-video')"
    # As a click: a video starts playing on its own only after one.
    tour.js(f"document.querySelector('{row}').click(); return 1", gesture=True)
    tour.wait(lambda: not tour.js("return document.getElementById('player').hidden"), 10)
    playing = tour.wait(lambda: tour.js(f"return {video}.currentTime > 1.5"), 20)
    caption = tour.wait(lambda: tour.js("const c = document.getElementById('player-caption');"
                                        " return c.hidden ? '' : c.textContent"), 10)
    lines = tour.js("return [...document.querySelectorAll('#player-lines .player__line')].map(l => l.innerText)")
    tour.shot('player', playing=bool(playing), caption=caption, lines=len(lines or []),
              subtitles=tour.js("return document.getElementById('player-subtitles').innerText"),
              error=tour.js(f"return {video}.error ? {video}.error.message || String({video}.error.code) : ''"))

    # The line at 9 seconds, clicked: the video plays from there, the line marked as the one on show.
    tour.js("document.querySelectorAll('#player-lines .player__line')[2].click(); return 1", gesture=True)
    sought = tour.wait(lambda: tour.js(f"const t = {video}.currentTime; return t >= 9 && t < 13 ? t : 0"), 10)
    time.sleep(1)
    tour.shot('player-line-clicked', at=sought, current=tour.js(
        "return document.querySelector('#player-lines [aria-current=\"true\"]')?.innerText ?? ''"),
        caption=tour.js("return document.getElementById('player-caption').textContent"))

    tour.press_in_player('f')
    time.sleep(1)
    tour.shot('player-full-window', full=tour.js("return document.getElementById('player').classList.contains('player--full')"))
    tour.press_in_player('Escape')
    tour.press_in_player('Escape')
    closed = tour.wait(lambda: tour.js("return document.getElementById('player').hidden"), 5)

    # Opened again, it starts where it stopped, and says so.
    time.sleep(1)
    tour.js(f"document.querySelector('{row}').click(); return 1", gesture=True)
    resumed = tour.wait(lambda: tour.js("const r = document.getElementById('player-resumed');"
                                        " return r.hidden ? '' : r.innerText"), 15)
    at = tour.js(f"return {video}.currentTime")
    tour.shot('player-resumed', closed=bool(closed), resumed=resumed, at=at)
    tour.press_in_player('Escape')
    tour.wait(lambda: tour.js("return document.getElementById('player').hidden"), 5)


def make_subtitles(tour, id):
    """Subtitles made on the PC: Tiny picked in Settings › Speech recognition (fetched from Hugging Face the first
    time), then Make subtitles on a saved speech with none. What whisper.cpp heard is listed beside the video."""
    tour.click('#settings-button')
    available = tour.wait(lambda: not tour.js("return document.getElementById('settings-speech').hidden"), 10)
    tour.js("[...document.querySelectorAll('#speech-models .model__pick')].find(b => b.innerText.startsWith('Tiny'))"
            "?.click(); return 1")
    time.sleep(0.5)
    tour.shot('speech-settings', available=bool(available), models=tour.js(
        "return [...document.querySelectorAll('#speech-models .model')].map(m => m.innerText.replace(/\\n/g, ' | '))"))
    tour.click('#settings-button')

    tour.js(f"document.querySelector('#download-list .row[data-id=\"{id}\"] .row__title').click(); return 1", gesture=True)
    # No subtitles yet: the transcript's one action is to make them.
    tour.wait(lambda: tour.js("return !document.getElementById('player-none').hidden"
                              " && !document.getElementById('player-make-first').hidden"), 15)
    tour.shot('no-subtitles-yet', transcript=tour.js("return document.getElementById('panel-transcript').innerText"))
    tour.click('#player-make-first')
    making = "const m = document.getElementById('player-making'); return m.hidden ? '' : m.innerText"
    tour.wait(lambda: 'Fetching' in (tour.js(making) or '') or 'Making' in (tour.js(making) or ''), 30)
    tour.shot('making-subtitles', making=tour.js(making))
    # Made (its lines listed; the note goes after a few seconds), or failed (the note offers Try again).
    finished = "return document.querySelectorAll('#player-lines .player__line').length > 0" \
               " || (document.getElementById('player-making').innerText || '').includes('Try again')"
    tour.wait(lambda: tour.js(finished), 300, every=2)
    lines = tour.js("return [...document.querySelectorAll('#player-lines .player__line')].map(l => l.innerText)")
    heard = ' '.join(lines or []).lower()
    tour.shot('subtitles-made', making=tour.js(making), lines=lines,
              ask_not='ask not what your country can do for you' in heard,
              head=tour.js("return document.getElementById('player-lines-head').textContent"),
              subtitles=tour.js("return document.getElementById('player-subtitles').innerText"))
    tour.press_in_player('Escape')
    tour.wait(lambda: tour.js("return document.getElementById('player').hidden"), 5)


def restored_tab(tour):
    """The tab kept from the last run (`seed_tabs`), clicked: its page is made then, and the app still answers. Made in
    a synchronous command, WebView2 waited for the window's thread, which was waiting for it: Chaya froze."""
    tour.wait(lambda: tour.js("return document.querySelectorAll('#tabstrip .tab').length") >= 1, 10)
    tabs = tour.js("return [...document.querySelectorAll('#tabstrip .tab')].map(t => t.innerText.trim())")
    tour.click('#tabstrip .tab .tab__title', 0)
    time.sleep(3)
    tour.js("window.__answered = null; window.__TAURI__.core.invoke('list_tabs')"
            ".then(v => (window.__answered = v)); return 1")
    answered = tour.wait(lambda: tour.js("return window.__answered"), 20)
    tour.shot('restored-tab', tabs=tabs, answers=bool(answered), shown=(answered or {}).get('active'),
              address=tour.js("return document.getElementById('address').value"))
    tour.click('#page-home')
    time.sleep(1)


def poster_steps(tour):
    """For each saved video: whether the app serves its poster, and if not, how far a frame of it gets in the window
    (the video loads, seeks, is drawn, and the canvas can be read), as the window makes posters."""
    tour.js("""
      window.__posterSteps = null;
      const file = (name) => window.__TAURI__.core.convertFileSrc(name, 'chaya-file');
      const ids = [...document.querySelectorAll('#download-list .row')].map((r) => r.dataset.id);
      Promise.all(ids.map(async (id) => {
        const served = await new Promise((ok) => {
          const img = new Image();
          img.onload = () => ok('served');
          img.onerror = () => ok('not served');
          img.src = file(`poster-${id}`);
        });
        if (served === 'served') return [id, served];
        const video = document.createElement('video');
        video.muted = true; video.preload = 'auto'; video.crossOrigin = 'anonymous';
        const step = (event) => new Promise((ok, no) => {
          video.addEventListener(event, ok, { once: true });
          video.addEventListener('error', () => no(`error ${video.error?.code}: ${video.error?.message}`), { once: true });
          setTimeout(() => no(`no ${event} in 15 s`), 15000);
        });
        try {
          video.src = file(String(id));
          await step('loadedmetadata');
          if (!video.videoWidth) return [id, served, 'no picture in it'];
          video.currentTime = Math.min(30, (video.duration || 10) / 10);
          await step('seeked');
          const canvas = document.createElement('canvas');
          canvas.width = 64; canvas.height = 36;
          canvas.getContext('2d').drawImage(video, 0, 0, 64, 36);
          canvas.toDataURL('image/jpeg');
          return [id, served, 'a frame can be taken'];
        } catch (e) {
          return [id, served, String(e)];
        }
      })).then((steps) => (window.__posterSteps = steps));
      return 1""")
    return tour.wait(lambda: tour.js("return window.__posterSteps"), 40)


class StandInService:
    """An OpenAI-compatible service on this machine, for Settings › AI model's Test: it lists one model, answers
    every question with the JSON Test asks for, and keeps the keys it was sent."""

    def __init__(self):
        import http.server
        import threading

        keys = self.keys = []

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def answer(self, body):
                data = json.dumps(body).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):
                keys.append(self.headers.get('Authorization'))
                self.answer({'data': [{'id': 'stand-in-model'}, {'id': 'text-embedding-stand-in'}]})

            def do_POST(self):
                keys.append(self.headers.get('Authorization'))
                asked = json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))) or b'{}')
                question = (asked.get('messages') or [{}])[-1].get('content', '')
                if 'best highlights' in question:
                    reply = json.dumps({'highlights': [
                        {'from': 1, 'to': 2, 'title': 'Into the park', 'reason': 'Where the walk begins.', 'score': 8},
                        {'from': 6, 'to': 7, 'title': 'The end of the walk', 'reason': 'A quiet close.', 'score': 7},
                    ]})
                elif 'Translate these lines' in question:
                    # Each line asked for, by its number: "12: text" becomes {"n": 12, "text": "FR text"}.
                    wanted = question.split('and no others:\n', 1)[1].splitlines()
                    lines = [{'n': int(l.split(':', 1)[0]), 'text': 'FR ' + l.split(':', 1)[1].strip()} for l in wanted if ':' in l]
                    reply = json.dumps({'lines': lines})
                else:
                    reply = '{"ok": true, "word": "Chaya"}'
                self.answer({'choices': [{'message': {'role': 'assistant', 'content': reply}}]})

        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.address = f'http://127.0.0.1:{self.server.server_address[1]}/v1'
        threading.Thread(target=self.server.serve_forever, daemon=True).start()


def ai_model(tour, clip):
    """Settings › AI model with a service on this machine: its address, a key (kept by Windows Credential Manager),
    a model from its list, and Test. The service must get the key; ai.json must not hold it. The key is removed after."""
    service = StandInService()
    key = 'chaya-tour-key-123'
    tour.click('#settings-button')
    tour.wait(lambda: tour.js("return document.getElementById('ai-service').options.length > 1"), 10)
    time.sleep(0.8)
    set_field = "const e = document.getElementById({}); e.value = {}; e.dispatchEvent(new Event('change')); return 1"
    tour.js(set_field.format(json.dumps('ai-service'), json.dumps('custom')))
    time.sleep(0.8)
    tour.js(set_field.format(json.dumps('ai-address'), json.dumps(service.address)))
    time.sleep(0.8)
    tour.js(f"document.getElementById('ai-key').value = {json.dumps(key)}; document.getElementById('ai-key-save').click(); return 1")
    tour.wait(lambda: 'Credential Manager' in (tour.js("return document.getElementById('ai-key-note').innerText") or ''), 10)
    listed = tour.wait(lambda: tour.js("return [...document.getElementById('ai-models').options].map(o => o.value)"), 10)
    tour.js(set_field.format(json.dumps('ai-model'), json.dumps('stand-in-model')))
    time.sleep(0.5)
    tour.click('#ai-test')
    # Its answer, or why there is none: anything but "Asking…".
    answered = "const n = document.getElementById('ai-note').textContent; return n.startsWith('Asking') ? '' : n"
    note = tour.wait(lambda: tour.js(answered), 20)
    tour.js("document.getElementById('settings-ai').scrollIntoView(); return 1")
    kept = (Path(os.environ['APPDATA']) / 'com.chaya.desktop' / 'ai.json') if os.name == 'nt' else None
    in_file = kept.read_text(encoding='utf-8') if kept and kept.exists() else ''
    tour.shot('ai-model', note=note, models=listed, key_note=tour.js("return document.getElementById('ai-key-note').innerText"),
              key_sent=service.keys.count(f'Bearer {key}'), asked=len(service.keys),
              key_in_ai_json=key in in_file, ai_json=in_file.replace('\n', ' '))
    tour.click('#settings-button')
    time.sleep(0.5)
    try:
        translate(tour, clip)
    except Exception as e:
        tour.failed('translated', e)
    try:
        find_highlights(tour, clip)
    except Exception as e:
        tour.failed('highlights', e)
    try:
        make_clips(tour, clip)
    except Exception as e:
        tour.failed('clips', e)
    try:
        export_clips(tour, clip)
    except Exception as e:
        tour.failed('exported', e)
        tour.press_in_player('Escape')
        tour.press_in_player('Escape')
        tour.press_in_player('Escape')
    tour.click('#settings-button')
    time.sleep(0.8)
    # The key out of Credential Manager again, and no service chosen.
    tour.js("document.querySelector('#ai-key-note .ai__link')?.click(); return 1")
    time.sleep(1)
    removed = 'Credential Manager' not in (tour.js("return document.getElementById('ai-key-note').innerText") or '')
    tour.js(set_field.format(json.dumps('ai-service'), json.dumps('')))
    tour.steps.append({'step': 'ai-key-removed', 'removed': removed})
    print(f'ai: key removed {removed}', flush=True)
    tour.click('#settings-button')
    service.server.shutdown()


def translate(tour, clip):
    """The clip's English subtitles translated into French by the stand-in service: listed beside the English, shown,
    and saved next to the video."""
    tour.js(f"document.querySelector('#download-list .row[data-id=\"{clip}\"] .row__title').click(); return 1", gesture=True)
    tour.wait(lambda: tour.js("return !document.getElementById('player').hidden"), 10)
    tour.click('#tab-subtitles')
    tour.wait(lambda: not tour.js("return document.getElementById('player-translate-box').hidden"), 15)
    tour.js("const s = document.getElementById('player-translate-to'); s.value = 'fr'; s.dispatchEvent(new Event('change')); return 1")
    tour.click('#player-translate')
    shown = "return document.getElementById('player-subtitles').innerText"
    tour.wait(lambda: 'French' in (tour.js(shown) or ''), 60)
    time.sleep(1)
    srt = Path.home() / 'Downloads' / f'{CLIP}.fr.srt'
    tour.shot('translated', card=tour.js("return document.getElementById('player-translating-text').textContent"),
              subtitles=tour.js(shown), head=tour.js("return document.getElementById('player-lines-head').textContent"),
              first=tour.js("return document.querySelector('#player-lines .player__line')?.innerText"),
              file=srt.exists(), file_start=srt.read_text(encoding='utf-8')[:80] if srt.exists() else '')
    # Both at once: the original with its translation, at a moment with a line.
    tour.js("[...document.querySelectorAll('#player-second button')].find(b => b.textContent === 'English')?.click(); return 1")
    tour.js("const v = document.getElementById('player-video'); v.pause(); v.currentTime = 2; return 1")
    time.sleep(1.5)
    tour.shot('two-languages', top=tour.js("return document.getElementById('player-caption-top').textContent"),
              under=tour.js("return document.getElementById('player-caption-under').textContent"),
              versions=tour.js("return document.getElementById('player-versions').innerText"),
              styles=tour.js("return document.querySelectorAll('#player-styles .styles__lang').length"))
    tour.press_in_player('Escape')
    tour.wait(lambda: tour.js("return document.getElementById('player').hidden"), 5)


def find_highlights(tour, clip):
    """The clip's highlights found by the stand-in service: listed with their times, marked on the seek bar, and the
    second played from its start when clicked."""
    tour.js(f"document.querySelector('#download-list .row[data-id=\"{clip}\"] .row__title').click(); return 1", gesture=True)
    tour.wait(lambda: tour.js("return !document.getElementById('player').hidden"), 10)
    tour.click('#tab-highlights')
    tour.wait(lambda: not tour.js("return document.getElementById('player-find-row').hidden"), 15)
    tour.click('#player-find')
    found = "return [...document.querySelectorAll('#player-highlights .highlight')].map(h => h.innerText.replace(/\\n/g, ' | '))"
    tour.wait(lambda: tour.js(found), 30)
    tour.js("document.querySelectorAll('#player-highlights .highlight')[1]?.click(); return 1")
    time.sleep(1.5)
    tour.shot('highlights', found=tour.js(found),
              head=tour.js("return document.getElementById('player-highlights-head').textContent"),
              marks=tour.js("return document.querySelectorAll('#player-marks .controls__mark').length"),
              playing_from=tour.js("return Math.round(document.getElementById('player-video').currentTime)"))
    tour.press_in_player('Escape')
    tour.wait(lambda: tour.js("return document.getElementById('player').hidden"), 5)


def make_clips(tour, clip):
    """Lines 2 to 4 chosen in the transcript (a click, then a Shift-click) and made into a clip with Ctrl+B; its end
    dragged on the timeline near line 5's end, where it snaps; its start moved a line with the arrow key; renamed; the
    timeline's picture track filled with frames and its sound read by the engine's ffmpeg (kept as videos/ID.peaks);
    the highlights added to the clips; all kept in the video's file."""
    tour.js(f"document.querySelector('#download-list .row[data-id=\"{clip}\"] .row__title').click(); return 1", gesture=True)
    tour.wait(lambda: tour.js("return !document.getElementById('player').hidden"), 10)
    tour.click('#tab-transcript')
    tour.wait(lambda: tour.js("return document.querySelectorAll('#player-lines .player__line').length"), 15)
    tour.click('#player-lines .player__line', 1)
    tour.js("document.querySelectorAll('#player-lines .player__line')[3].dispatchEvent("
            "new MouseEvent('click', {bubbles: true, cancelable: true, shiftKey: true})); return 1")
    chosen = tour.js("return document.getElementById('player-pick-text').textContent")
    tour.js("document.getElementById('player-video').dispatchEvent(new KeyboardEvent('keydown',"
            " {key: 'b', ctrlKey: true, bubbles: true, cancelable: true})); return 1")
    tour.wait(lambda: not tour.js("return document.getElementById('cut').hidden"), 10)
    made = tour.js("return document.getElementById('cut-times').textContent")
    # The end edge dragged to 19.3 s, 0.7 s short of line 5's end: it lets go at 20 s.
    tour.js("""
        const edge = document.getElementById('cut-end'), region = document.getElementById('cut-region').getBoundingClientRect();
        const [start, end] = [5000, 16000], x = region.right + (19300 - end) * region.width / (end - start);
        const r = edge.getBoundingClientRect(), y = r.top + r.height / 2;
        const send = (type, clientX) => edge.dispatchEvent(new PointerEvent(type, {bubbles: true, cancelable: true, pointerId: 7, clientX, clientY: y}));
        send('pointerdown', r.left + r.width / 2); send('pointermove', (r.left + x) / 2); send('pointermove', x); send('pointerup', x);
        return 1""")
    time.sleep(1)
    dragged = tour.js("return document.getElementById('cut-times').textContent")
    tour.js("const e = document.getElementById('cut-start'); e.focus(); e.dispatchEvent(new KeyboardEvent('keydown',"
            " {key: 'ArrowLeft', bubbles: true, cancelable: true})); return 1")
    time.sleep(1)
    nudged = tour.js("return document.getElementById('cut-times').textContent")
    tour.js("const n = document.getElementById('cut-name'); n.value = 'Counting the walk'; n.dispatchEvent(new Event('change')); return 1")
    time.sleep(1)
    # The timeline's picture track: the share of it covered by frames (opaque), not the empty slots' tint.
    framed = ("const c = document.getElementById('cut-pictures'); if (!c.width) return 0;"
              " const d = c.getContext('2d').getImageData(0, 0, c.width, c.height).data; let n = 0;"
              " for (let i = 3; i < d.length; i += 4) if (d[i] === 255) n++; return Math.round(100 * n / (d.length / 4))")
    tour.wait(lambda: (tour.js(framed) or 0) > 60, 20)
    peaks = data_folder() / 'videos' / f'{clip}.peaks'
    tour.wait(peaks.exists, 20)
    tour.shot('clip-made', chosen=chosen, made=made, dragged=dragged, nudged=nudged,
              lines_on_timeline=tour.js("return document.querySelectorAll('#cut-lines .cut__line').length"),
              picture_framed_percent=tour.js(framed), peaks=peaks.stat().st_size if peaks.exists() else 0,
              sound_note=tour.js("const n = document.getElementById('cut-sound-note'); return n.hidden ? '' : n.textContent"),
              ruler=tour.js("return [...document.querySelectorAll('#cut-ruler .cut__tick')].map(t => t.textContent)"))
    tour.click('#tab-highlights')
    time.sleep(0.5)
    tour.click('#player-highlights-all')
    time.sleep(1)
    tour.click('#tab-clips')
    time.sleep(0.5)
    listed = tour.js("return [...document.querySelectorAll('#player-clips .highlight')].map(h => h.innerText.replace(/\\n/g, ' | '))")
    kept = data_folder() / 'videos' / f'{clip}.json'
    in_file = json.loads(kept.read_text(encoding='utf-8')).get('clips', []) if kept.exists() else []
    tour.shot('clips', listed=listed, head=tour.js("return document.getElementById('player-clips-head').textContent"),
              kept=[f"{c['name']} {c['start']}-{c['end']}" for c in in_file])
    tour.press_in_player('Escape')
    tour.press_in_player('Escape')
    tour.wait(lambda: tour.js("return document.getElementById('player').hidden"), 5)


def exports_now(tour):
    """Every export of this run, as the app has them (`exports_status`)."""
    tour.js("window.__tourExports = null; window.__TAURI__.core.invoke('exports_status')"
            ".then(jobs => window.__tourExports = {jobs}, e => window.__tourExports = {jobs: [], error: String(e)}); return 1")
    answer = tour.wait(lambda: tour.js("return window.__tourExports"), 10, every=0.2)
    return (answer or {}).get('jobs', [])


def export_from_dialog(tour, choose, seconds=120):
    """Ctrl+E in the player, `choose` (selectors clicked in turn, those not there passed by), Export, and the jobs it
    made once each has ended (or `seconds` have gone): the dialog's words, any error it gave, and those jobs."""
    before = {job['job'] for job in exports_now(tour)}
    tour.js("document.getElementById('player-video').dispatchEvent(new KeyboardEvent('keydown',"
            " {key: 'e', ctrlKey: true, bubbles: true, cancelable: true})); return 1")
    tour.wait(lambda: tour.js("return !document.getElementById('export').hidden"), 10)
    for selector in choose:
        tour.click(selector)
        time.sleep(0.3)
    dialog = tour.js("return [...document.querySelectorAll('#export .export__form > :not([hidden]), #export-where, #export-go')]"
                     ".map(e => e.innerText.replace(/\\n/g, ' ')).join(' / ')")
    tour.click('#export-go')
    tour.wait(lambda: tour.js("return document.getElementById('export').hidden"
                              " || !document.getElementById('export-error').hidden"), 15)
    error = tour.js("const e = document.getElementById('export-error'); return e.hidden ? '' : e.textContent")
    if error:
        tour.press_in_player('Escape')
        return dialog, error, []

    def ended():
        jobs = [job for job in exports_now(tour) if job['job'] not in before]
        return jobs if jobs and all(job['state'] in ('done', 'failed', 'stopped') for job in jobs) else None

    jobs = tour.wait(ended, seconds, every=1) or [job for job in exports_now(tour) if job['job'] not in before]
    return dialog, error, jobs


def export_clips(tour, clip):
    """The clips exported from the Clips tab (Ctrl+E): all of them as videos with one language burned in, Outline, at
    720p, each saved next to the video in Downloads and told of by a notice, with the encoder used; then one clip's
    subtitles as an SRT file timed from 0:00."""
    downloads = Path.home() / 'Downloads'
    tour.js(f"document.querySelector('#download-list .row[data-id=\"{clip}\"] .row__title').click(); return 1", gesture=True)
    tour.wait(lambda: tour.js("return !document.getElementById('player').hidden"), 10)
    tour.click('#tab-clips')
    tour.wait(lambda: tour.js("return !document.getElementById('player-export').hidden"), 15)
    tour.js("document.getElementById('player-video').pause(); return 1")

    def files(jobs, read=0):
        """Each job's file: whether it is in Downloads, next to the video, its size, and its first `read` characters."""
        seen = []
        for job in jobs:
            path = Path(job['path']) if job.get('path') else None
            there = bool(path and path.exists())
            seen.append({'name': path.name if path else None, 'next_to_video': bool(path and path.parent == downloads),
                         'exists': there, 'bytes': path.stat().st_size if there else 0,
                         **({'start': path.read_text(encoding='utf-8-sig')[:read]} if read and there else {})})
        return seen

    toasts = "return [...document.querySelectorAll('#toasts .toast')].map(t => t.innerText.replace(/\\n+/g, ' | '))"
    dialog, error, jobs = export_from_dialog(tour, [
        '[data-pick="clips:all"]', '#export-as [data-save-as="video"]', '[data-pick="subtitles:one"]',
        '[data-pick="look:outline"]', '#export-quality [data-quality="p720"]'])
    tour.shot('exported', dialog=dialog, error=error, notices=tour.js(toasts),
              jobs=[{k: job.get(k) for k in ('job', 'name', 'state', 'encoder', 'message', 'percent')} for job in jobs],
              files=files([job for job in jobs if job['state'] == 'done']))

    # One clip's subtitles: the first clip chosen on the timeline, and SRT.
    tour.click('#player-clips .highlight')
    time.sleep(0.5)
    dialog, error, jobs = export_from_dialog(tour, ['[data-pick="clips:chosen"]', '#export-as [data-save-as="srt"]'], 60)
    tour.shot('exported-srt', dialog=dialog, error=error, notices=tour.js(toasts),
              jobs=[{k: job.get(k) for k in ('job', 'name', 'state', 'message')} for job in jobs],
              files=files([job for job in jobs if job['state'] == 'done'], read=120))
    tour.press_in_player('Escape')
    tour.press_in_player('Escape')
    tour.wait(lambda: tour.js("return document.getElementById('player').hidden"), 5)


def own_video(tour):
    """A video of the person's own, in a folder outside Downloads, dropped on the window: Tauri's drop event is sent
    from the window's content as Windows would send it (or, if that cannot be sent, `add_own_videos` is asked as a drop
    asks it). It is listed where it is, opens in the player, and taken off the list again leaves the file as it was."""
    folder = Path.home() / 'Videos' / 'Chaya tour'
    folder.mkdir(parents=True, exist_ok=True)
    video = folder / 'Our meeting.mp4'
    video.write_bytes((FIXTURES / f'{SPEECH}.mp4').read_bytes())
    before = (video.stat().st_size, video.stat().st_mtime)
    row = "[...document.querySelectorAll('#download-list .row')].find(r => r.querySelector('.row__title').textContent === 'Our meeting.mp4')"
    tour.js("window.__TAURI__.event.emit('tauri://drag-drop', { paths: [" + json.dumps(str(video)) + "], position: { x: 400, y: 400 } });"
            " return 1", gesture=True)
    via = 'drop event'
    if not tour.wait(lambda: tour.js(f"return !!{row}"), 8):
        via = 'add_own_videos'
        tour.js("window.__TAURI__.core.invoke('add_own_videos', { paths: [" + json.dumps(str(video)) + "] }); return 1")
    listed = tour.wait(lambda: tour.js(f"const r = {row}; return r ? r.dataset.id + ' ' + r.querySelector('.row__meta').textContent : ''"), 10)
    # One file dropped opens in the player; added by the command alone, it is opened from the list.
    if tour.js("return document.getElementById('player').hidden") and listed:
        tour.js(f"{row}.querySelector('.row__title').click(); return 1", gesture=True)
    opened = tour.wait(lambda: not tour.js("return document.getElementById('player').hidden"), 10)
    playing = tour.wait(lambda: tour.js("return document.getElementById('player-video').currentTime > 1"), 15)
    tour.shot('own-video', via=via, listed=listed, opened=bool(opened), playing=bool(playing),
              title=tour.js("return document.getElementById('player-title').textContent"),
              note=tour.js("return document.querySelector('#own-note span')?.textContent ?? ''"))
    tour.js("document.getElementById('player-close').click(); return 1")
    tour.wait(lambda: tour.js("return document.getElementById('player').hidden"), 5)

    # Off the list: the row goes, the file stays as it was.
    label = tour.js(f"return {row}?.querySelector('.row__close').title ?? ''")
    tour.js(f"{row}?.querySelector('.row__close').click(); return 1")
    gone = tour.wait(lambda: tour.js(f"return !{row}"), 5)
    after = (video.stat().st_size, video.stat().st_mtime) if video.exists() else None
    tour.steps.append({'step': 'own-video-removed', 'remove_label': label, 'row_gone': bool(gone),
                       'file_kept': after == before, 'others_in_folder': sorted(p.name for p in folder.iterdir())})
    print(f'own video removed: row gone {bool(gone)}, file kept {after == before}', flush=True)


def search_lines(tour, clip):
    """Ctrl+F in the Downloads list, then "walk": the tour clip's lines that say it, marked, below the titles; the
    last ("The end of the walk.", at 25 s) clicked opens the clip in the player there."""
    tour.js("if (document.body.classList.contains('browsing')) document.getElementById('page-home').click();"
            " document.getElementById('address').blur(); return 1")
    time.sleep(0.5)
    tour.js("document.dispatchEvent(new KeyboardEvent('keydown', {key: 'f', ctrlKey: true, bubbles: true})); return 1")
    focused = tour.js("return document.activeElement?.id")
    tour.js("const f = document.getElementById('downloads-find'); f.value = 'walk'; f.dispatchEvent(new Event('input')); return 1")
    group = f".lines__video[data-id=\"{clip}\"]"
    found = tour.wait(lambda: tour.js(f"return document.querySelectorAll('{group} .lines__line').length"), 10)
    lines = tour.js(f"return [...document.querySelectorAll('{group} .lines__line')].map(l => l.dataset.start + ' ' +"
                    " [...l.querySelectorAll('mark')].map(m => m.textContent).join(','))")
    tour.shot('search-lines', ctrl_f_focus=focused, found=found, lines=lines,
              summary=tour.js("return document.querySelector('.lines__summary').textContent"))
    tour.js(f"document.querySelector('{group} .lines__line[data-start=\"25000\"]').click(); return 1", gesture=True)
    tour.wait(lambda: not tour.js("return document.getElementById('player').hidden"), 10)
    at = tour.wait(lambda: tour.js("const t = document.getElementById('player-video').currentTime; return t >= 25 && t < 30 ? t : 0"), 15)
    tour.shot('search-line-opened', at=at, title=tour.js("return document.getElementById('player-title').textContent"),
              current=tour.js("return document.querySelector('#player-lines [aria-current=\"true\"]')?.innerText ?? ''"))
    tour.js("document.getElementById('player-close').click(); return 1")
    tour.wait(lambda: tour.js("return document.getElementById('player').hidden"), 5)
    tour.js("const f = document.getElementById('downloads-find'); f.value = ''; f.dispatchEvent(new Event('input')); return 1")


def looked_up(tour):
    """Whether the card's lookup has ended: its state holds an answer, not the "Finding…" pill, and is not still empty
    from the moment before the lookup began."""
    return tour.js(
        "const s = document.querySelector('#result .card__state');"
        " return !!s && s.children.length > 0 && !s.querySelector('.finding')"
    )


def run(tour, sites, seeded=None):
    tour.wait(lambda: tour.js("return !!document.getElementById('address')?.offsetParent"), 30)
    tour.shot('home', title=tour.js('return document.title'))

    if seeded is not None:
        clip, speech = seeded
        try:
            watch(tour, clip)
        except Exception as e:
            tour.failed('player', e)
            tour.js("document.getElementById('player-close').click(); return 1")
        try:
            make_subtitles(tour, speech)
        except Exception as e:
            tour.failed('make-subtitles', e)
            tour.js("document.getElementById('player-close').click(); return 1")
        try:
            restored_tab(tour)
        except Exception as e:
            tour.failed('restored-tab', e)
        try:
            ai_model(tour, clip)
        except Exception as e:
            tour.failed('ai-model', e)
        # Step 9: a video of the person's own, and the search across every video's lines.
        try:
            own_video(tour)
        except Exception as e:
            tour.failed('own-video', e)
            tour.js("document.getElementById('player-close').click(); return 1")
        try:
            search_lines(tour, clip)
        except Exception as e:
            tour.failed('search-lines', e)
            tour.js("document.getElementById('player-close').click(); return 1")

    # A link: looked up by the bundled engine, its qualities offered.
    try:
        tour.type_address(sites['youtube'])
        answer = tour.wait(lambda: looked_up(tour), 120)
        tour.shot('link-looked-up', card=tour.card_text()[:400], choices=answer)
    except Exception as e:
        tour.failed('link-looked-up', e)

    # Download its smallest choice, then cancel it from the list: the card's button goes back to its pill.
    try:
        choices = tour.js("return document.querySelectorAll('#result .choice .dl').length")
        if choices:
            tour.click('#result .choice .dl', choices - 1)
            tour.wait(lambda: tour.js("return document.querySelectorAll('#download-list .row').length"), 20)
            time.sleep(3)
            tour.shot('downloading', row=tour.js("return document.querySelector('#download-list .row')?.innerText ?? ''"))
            state = tour.js("return document.querySelector('#download-list .row')?.dataset.state ?? null")
            if state not in ('completed', None):
                tour.click('#download-list .row .row__close')
                time.sleep(1.5)
                button = tour.js(f"return document.querySelectorAll('#result .choice .dl')[{choices - 1}]?.dataset.state ?? null")
                tour.shot('cancelled', button=button)
    except Exception as e:
        tour.failed('download', e)

    # A Pinterest pin: looked up by the same engine, a picture or GIF as a post, a video with its qualities.
    try:
        # The link's card is still there; cleared, the only card is the pin's own.
        tour.js("document.getElementById('result').replaceChildren(); return 1")
        tour.type_address(sites['pin'])
        found = tour.wait(lambda: looked_up(tour), 120)
        tour.shot('pin-looked-up', card=tour.card_text()[:400], found=found)
    except Exception as e:
        tour.failed('pin-looked-up', e)

    # A page in the built-in browser: where it sits in the window.
    for name, key, wait in (('page', 'page', 8), ('page-with-video', 'video_page', 12), ('news', 'news', 12)):
        try:
            tour.type_address(sites[key])
            tour.wait(lambda: tour.js("return document.body.classList.contains('browsing')"), 20)
            time.sleep(wait)
            found = tour.js("return [...document.querySelectorAll('.found__item')].map(e => e.textContent.replace(/\\s+/g, ' ').trim())")
            shield = tour.js("return document.querySelector('#page-shield')?.getAttribute('aria-label') ?? ''")
            address = tour.js("return document.getElementById('address').value")
            page_top = tour.js("return Math.round(document.getElementById('page').getBoundingClientRect().top)")
            pill = tour.js("const b = document.getElementById('page-media'); return b.hidden ? '' : b.textContent")
            tour.shot(name, address=address, found=found, shield=shield, page_top=page_top, pill=pill)
            # What the page holds, in its panel from the bar's pill, over a picture of the page.
            if pill:
                tour.click('#page-media')
                time.sleep(1)
                tour.shot(f'{name}-found', panel=tour.js("return document.getElementById('media-panel').innerText"))
                tour.click('#page-media')
        except Exception as e:
            tour.failed(name, e)

    # Reload, by its button and by F5: the page loads again (a new time origin), and the window stays as it was.
    if hasattr(tour.window, 'run_in_page'):
        try:
            loaded = lambda: tour.window.run_in_page('return performance.timeOrigin')
            first = loaded()
            tour.click('#page-reload')
            by_button = tour.wait(lambda: (loaded() or first) != first and loaded(), 15)
            tour.window.press('F5', 116)
            by_key = tour.wait(lambda: (loaded() or by_button) != by_button and loaded(), 15)
            still_browsing = tour.js("return document.body.classList.contains('browsing')")
            tour.steps.append({'step': 'reload', 'button': bool(by_button), 'f5': bool(by_key), 'window kept': still_browsing})
            print(f"reload: button {bool(by_button)}, F5 {bool(by_key)}, window kept {still_browsing}", flush=True)
            time.sleep(4)
        except Exception as e:
            tour.failed('reload', e)

        # A page's fullscreen (as a video's own button asks for it) takes the whole screen, and leaving gives the
        # window back as it was, the page under the bar again.
        try:
            covers = "return innerWidth >= screen.width - 1 && innerHeight >= screen.height - 1"
            tour.window.run_in_page("document.documentElement.requestFullscreen(); return 1", gesture=True)
            full = tour.wait(lambda: tour.window.run_in_page(covers), 10)
            time.sleep(1)
            tour.shot('page-fullscreen', covers_the_screen=bool(full))
            tour.window.run_in_page("document.exitFullscreen(); return 1")
            left = tour.wait(lambda: tour.window.run_in_page(covers) is False, 10)
            time.sleep(1.5)
            page_top = tour.js("return Math.round(document.getElementById('page').getBoundingClientRect().top)")
            tour.shot('fullscreen-left', left=bool(left), page_top=page_top)
        except Exception as e:
            tour.failed('page-fullscreen', e)

    # The Downloads list in the page's place, and Back from it to the page.
    try:
        tour.click('#page-downloads')
        time.sleep(1)
        tour.shot('downloads-over-page')
        tour.click('#page-back')
        time.sleep(1)
        back = not tour.js("return document.body.classList.contains('showing-downloads')")
        tour.steps.append({'step': 'back-from-downloads', 'to the page': back})
        print(f'back from downloads: to the page {back}', flush=True)
    except Exception as e:
        tour.failed('back-from-downloads', e)

    # The ad blocker's panel, on the news page.
    try:
        tour.click('#page-shield')
        time.sleep(1)
        tour.shot('shield', panel=tour.js("return document.getElementById('shield-panel').innerText"))
        tour.click('#page-shield')
    except Exception as e:
        tour.failed('shield', e)

    # Night, then back to the theme Windows has.
    try:
        tour.click('#settings-button')
        time.sleep(0.5)
        tour.click('[data-theme-choice="night"]')
        tour.shot('night')
        tour.click('[data-theme-choice="system"]')
        tour.click('#settings-button')
    except Exception as e:
        tour.failed('night', e)

    # Tabs: Home is a new tab's start, an address typed there opens a second tab, and the strip switches between them.
    try:
        tour.click('#page-home')
        time.sleep(0.5)
        tour.type_address(sites['page'])
        tour.wait(lambda: tour.js("return document.querySelectorAll('#tabstrip .tab').length") >= 2, 20)
        time.sleep(4)
        tabs = tour.js("return [...document.querySelectorAll('#tabstrip .tab')].map(t => t.innerText.trim())")
        tour.shot('tabs', tabs=tabs, address=tour.js("return document.getElementById('address').value"),
                  page_top=tour.js("return Math.round(document.getElementById('page').getBoundingClientRect().top)"))
        tour.click('#tabstrip .tab .tab__title', 0)
        time.sleep(2)
        first = tour.js("return document.getElementById('address').value")
        tour.js("document.dispatchEvent(new KeyboardEvent('keydown', {key: 'w', ctrlKey: true, bubbles: true, cancelable: true})); return 1")
        time.sleep(1.5)
        left = tour.js("return document.querySelectorAll('#tabstrip .tab').length")
        tour.steps.append({'step': 'tab-switch-and-close', 'first tab': first, 'tabs left': left,
                           'strip shown': not tour.js("return document.getElementById('tabstrip').hidden")})
        print(f'tabs: switched to {first}, {left} left', flush=True)
    except Exception as e:
        tour.failed('tabs', e)

    # The title bar is Chaya's: its maximize button maximizes the window, and restores it.
    try:
        tour.click('#caption-max')
        tour.wait(lambda: tour.js("return document.body.classList.contains('maximized')"), 10)
        time.sleep(1.5)
        tour.shot('maximized', label=tour.js("return document.getElementById('caption-max').getAttribute('aria-label')"),
                  page_top=tour.js("return Math.round(document.getElementById('page').getBoundingClientRect().top)"))
        tour.click('#caption-max')
        tour.wait(lambda: not tour.js("return document.body.classList.contains('maximized')"), 10)
        tour.steps.append({'step': 'restored', 'restored': True})
    except Exception as e:
        tour.failed('maximize', e)

    # Home: the window's own content again, the tab still open behind it in the strip.
    try:
        tour.click('#page-home')
        time.sleep(1)
        tour.shot('closed', downloads=tour.js("return document.getElementById('download-list').innerText"))
    except Exception as e:
        tour.failed('closed', e)

    # The Downloads list narrowed, as the phone's library is: by a chip, then by words in the name.
    try:
        shown = "return [...document.querySelectorAll('#download-list .row')].filter(r => !r.hidden).length"
        chips = tour.js("return [...document.querySelectorAll('#downloads-narrow .chip')].map(c => c.textContent)")
        everything = tour.js(shown)
        if chips:
            tour.click('#downloads-narrow .chip')
            time.sleep(0.5)
            tour.shot('downloads-narrowed', chips=chips, chosen=chips[0], shown=tour.js(shown), of=everything)
            tour.click('#downloads-narrow .chip[aria-pressed="true"]')
        words = tour.js("return document.querySelector('#download-list .row__title')?.textContent.split(/[ .]/)[0] ?? ''")
        if words and not tour.js("return document.getElementById('downloads-find-field').hidden"):
            tour.js(f"const f = document.getElementById('downloads-find'); f.value = {json.dumps(words)};"
                    " f.dispatchEvent(new Event('input')); return 1")
            time.sleep(0.5)
            tour.steps.append({'step': 'downloads-found', 'words': words, 'shown': tour.js(shown), 'of': everything})
            print(f'downloads found by "{words}": {tour.js(shown)} of {everything}', flush=True)
            tour.click('#narrow-clear')
    except Exception as e:
        tour.failed('downloads-narrowed', e)

    # The grid, as the phone's library: what was saved as tiles, a picture or a post opening in the viewer.
    try:
        if not tour.js("return document.getElementById('downloads-view').hidden"):
            tour.click('#downloads-view [data-view="grid"]')
            tour.wait(lambda: tour.js("return document.querySelectorAll('#download-grid .tile').length"), 10)
            time.sleep(1.5)
            tiles = tour.js("return [...document.querySelectorAll('#download-grid .tile')].map(t => t.getAttribute('aria-label'))")
            pictures = tour.js("return [...document.querySelectorAll('#download-grid .tile img')].filter(i => i.naturalWidth > 0).length")
            # The saved videos' posters, which the window makes from them (a canvas over a chaya-file video).
            posters = tour.js("return [...document.querySelectorAll('#download-grid .tile img')]"
                              ".filter(i => i.src.includes('poster-') && i.naturalWidth > 0).length")
            tour.shot('downloads-grid', tiles=tiles, pictures_shown=pictures, posters=posters,
                      poster_steps=poster_steps(tour))
            opens = tour.js("const t = [...document.querySelectorAll('#download-grid .tile')]"
                            ".find(t => t.querySelector('img[src*=\"chaya-file\"]') || t.querySelector('.tile__badge--count'));"
                            " if (!t) return false; t.click(); return true;")
            if opens:
                tour.wait(lambda: not tour.js("return document.getElementById('viewer').hidden"), 5)
                time.sleep(1)
                tour.shot('viewer', title=tour.js("return document.getElementById('viewer-title').textContent"),
                          count=tour.js("return document.getElementById('viewer-count').textContent"))
                tour.js("document.getElementById('viewer').dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true})); return 1")
                time.sleep(0.5)
                tour.steps.append({'step': 'viewer-closed', 'closed': tour.js("return document.getElementById('viewer').hidden")})
            tour.click('#downloads-view [data-view="list"]')
    except Exception as e:
        tour.failed('downloads-grid', e)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--app', required=True, help='the installed Chaya program')
    parser.add_argument('--out', required=True, help='where the pictures go')
    parser.add_argument('--via', choices=('devtools', 'webdriver'), default='devtools' if os.name == 'nt' else 'webdriver')
    parser.add_argument('--driver', default='http://127.0.0.1:4444', help='tauri-driver, for --via webdriver')
    parser.add_argument('--sites', help='a JSON file of addresses to use instead of the internet\'s')
    parser.add_argument('--seed', action='store_true',
                        help='put saved videos, and a tab kept from a last run, in place first')
    args = parser.parse_args()

    # Windows' console and files default to its code page, which has no room for a page's '·' or 'é'.
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    sites = dict(SITES)
    if args.sites:
        sites.update(json.loads(Path(args.sites).read_text()))
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    seeded = seed_saved_videos() if args.seed else None
    if args.seed:
        seed_tabs(sites['page'])
    try:
        window = DevToolsWindow(args.app, out) if args.via == 'devtools' else WebDriverWindow(args.app, args.driver)
    except Exception as e:
        print(f'The app could not be started: {e}', file=sys.stderr)
        return 1

    tour = Tour(window, out)
    try:
        run(tour, sites, seeded)
    finally:
        (out / 'steps.json').write_text(json.dumps(tour.steps, indent=2, ensure_ascii=False), encoding='utf-8')
        try:
            window.close()
        except Exception:
            pass
    return 0


if __name__ == '__main__':
    sys.exit(main())
