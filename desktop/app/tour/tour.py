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

    python tour.py --app PATH --out FOLDER [--via devtools|webdriver] [--sites sites.json]

`--sites` swaps the internet's addresses for others (a local test site, when trying the tour off Windows).
"""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

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

    def js(self, script):
        return self.window.run(script)

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


def looked_up(tour):
    """Whether the card's lookup has ended: its state holds an answer, not the "Finding…" pill, and is not still empty
    from the moment before the lookup began."""
    return tour.js(
        "const s = document.querySelector('#result .card__state');"
        " return !!s && s.children.length > 0 && !s.querySelector('.finding')"
    )


def run(tour, sites):
    tour.wait(lambda: tour.js("return !!document.getElementById('address')?.offsetParent"), 30)
    tour.shot('home', title=tour.js('return document.title'))

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


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--app', required=True, help='the installed Chaya program')
    parser.add_argument('--out', required=True, help='where the pictures go')
    parser.add_argument('--via', choices=('devtools', 'webdriver'), default='devtools' if os.name == 'nt' else 'webdriver')
    parser.add_argument('--driver', default='http://127.0.0.1:4444', help='tauri-driver, for --via webdriver')
    parser.add_argument('--sites', help='a JSON file of addresses to use instead of the internet\'s')
    args = parser.parse_args()

    # Windows' console and files default to its code page, which has no room for a page's '·' or 'é'.
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')
    sites = dict(SITES)
    if args.sites:
        sites.update(json.loads(Path(args.sites).read_text()))
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    try:
        window = DevToolsWindow(args.app, out) if args.via == 'devtools' else WebDriverWindow(args.app, args.driver)
    except Exception as e:
        print(f'The app could not be started: {e}', file=sys.stderr)
        return 1

    tour = Tour(window, out)
    try:
        run(tour, sites)
    finally:
        (out / 'steps.json').write_text(json.dumps(tour.steps, indent=2, ensure_ascii=False), encoding='utf-8')
        try:
            window.close()
        except Exception:
            pass
    return 0


if __name__ == '__main__':
    sys.exit(main())
