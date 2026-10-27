"""A tour of Chaya for Windows, with a screenshot of the whole screen at each step.

CI installs the app from its installer and runs this on a Windows machine (`desktop.yml`, job `tour`), so what
the app looks like there can be seen rather than guessed: where the page sits, fonts, the title bar, a real
lookup. It drives the window's own content through WebDriver (`tauri-driver`, which starts the app), and
photographs the screen, the browser's page and Windows' own frame included.

A step that cannot be done (a site that refuses a data centre, a lookup that times out) is noted and the tour
goes on; only a tour that cannot start the app fails. What each step saw goes into `steps.json` beside the
pictures.

    python tour.py --app PATH --out FOLDER [--driver http://127.0.0.1:4444] [--sites sites.json]

`--sites` swaps the internet's addresses for others (a local test site, when trying the tour off Windows).
"""

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

from selenium import webdriver
from selenium.common.exceptions import WebDriverException
from selenium.webdriver.common.by import By
from selenium.webdriver.common.keys import Keys
from selenium.webdriver.common.options import ArgOptions

SITES = {
    # A short, old, public video: the first one uploaded to YouTube.
    'youtube': 'https://www.youtube.com/watch?v=jNQXAC9IVRw',
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


class Tour:
    def __init__(self, driver, out):
        self.driver = driver
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

    def js(self, script, *args):
        return self.driver.execute_script(script, *args)

    def wait(self, condition, seconds, every=0.5):
        """Waits until `condition()` is truthy; returns its last value."""
        end = time.time() + seconds
        value = None
        while time.time() < end:
            try:
                value = condition()
            except WebDriverException:
                value = None
            if value:
                return value
            time.sleep(every)
        return value

    def type_address(self, text):
        field = self.driver.find_element(By.ID, 'address')
        field.click()
        field.send_keys(Keys.CONTROL, 'a')
        field.send_keys(text)
        field.send_keys(Keys.ENTER)

    def card_text(self):
        return self.js("const c = document.querySelector('#result .card'); return c ? c.innerText : '';")


def run(tour, sites):
    d = tour.driver
    tour.wait(lambda: d.find_element(By.ID, 'address').is_displayed(), 30)
    tour.shot('home', title=d.title)

    # A link: looked up by the bundled engine, its qualities offered.
    try:
        tour.type_address(sites['youtube'])
        answer = tour.wait(
            lambda: tour.js("return document.querySelectorAll('#result .choice .dl').length")
            or ('finding' not in tour.js("return document.querySelector('#result .card__state')?.innerHTML ?? 'finding'")),
            120,
        )
        tour.shot('link-looked-up', card=tour.card_text()[:400], choices=answer)
    except Exception as e:
        tour.failed('link-looked-up', e)

    # Download its smallest choice, then cancel it from the list: the card's button goes back to its pill.
    try:
        buttons = d.find_elements(By.CSS_SELECTOR, '#result .choice .dl')
        if buttons:
            buttons[-1].click()
            tour.wait(lambda: d.find_elements(By.CSS_SELECTOR, '#download-list .row'), 20)
            time.sleep(3)
            tour.shot('downloading', row=tour.js("return document.querySelector('#download-list .row')?.innerText ?? ''"))
            rows = d.find_elements(By.CSS_SELECTOR, '#download-list .row')
            state = rows[0].get_attribute('data-state') if rows else None
            if state not in ('completed', None):
                rows[0].find_element(By.CSS_SELECTOR, '.row__close').click()
                time.sleep(1.5)
                tour.shot('cancelled', button=buttons[-1].get_attribute('data-state'))
    except Exception as e:
        tour.failed('download', e)

    # A page in the built-in browser: where it sits in the window.
    for name, key, wait in (('page', 'page', 8), ('page-with-video', 'video_page', 12), ('news', 'news', 12)):
        try:
            tour.type_address(sites[key])
            tour.wait(lambda: tour.js("return document.body.classList.contains('browsing')"), 20)
            time.sleep(wait)
            found = tour.js("return [...document.querySelectorAll('.found__item')].map(e => e.innerText.replace(/\\s+/g, ' '))")
            shield = tour.js("return document.querySelector('#page-shield')?.getAttribute('aria-label') ?? ''")
            tour.shot(name, address=d.find_element(By.ID, 'address').get_attribute('value'), found=found, shield=shield)
        except Exception as e:
            tour.failed(name, e)

    # The ad blocker's panel, on the news page.
    try:
        d.find_element(By.ID, 'page-shield').click()
        time.sleep(1)
        tour.shot('shield', panel=tour.js("return document.getElementById('shield-panel').innerText"))
        d.find_element(By.ID, 'page-shield').click()
    except Exception as e:
        tour.failed('shield', e)

    # Night, then back to the theme Windows has.
    try:
        d.find_element(By.ID, 'settings-button').click()
        time.sleep(0.5)
        d.find_element(By.CSS_SELECTOR, '[data-theme-choice="night"]').click()
        tour.shot('night')
        d.find_element(By.CSS_SELECTOR, '[data-theme-choice="system"]').click()
        d.find_element(By.ID, 'settings-button').click()
    except Exception as e:
        tour.failed('night', e)

    # Close the page: the window's own content again.
    try:
        d.find_element(By.ID, 'page-close').click()
        time.sleep(1)
        tour.shot('closed', downloads=tour.js("return document.getElementById('download-list').innerText"))
    except Exception as e:
        tour.failed('closed', e)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--app', required=True, help='the installed Chaya program')
    parser.add_argument('--out', required=True, help='where the pictures go')
    parser.add_argument('--driver', default='http://127.0.0.1:4444')
    parser.add_argument('--sites', help='a JSON file of addresses to use instead of the internet\'s')
    args = parser.parse_args()

    sites = dict(SITES)
    if args.sites:
        sites.update(json.loads(Path(args.sites).read_text()))
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    options = ArgOptions()
    options.set_capability('browserName', 'wry')
    options.set_capability('tauri:options', {'application': os.path.abspath(args.app)})
    driver = None
    for _ in range(10):
        try:
            driver = webdriver.Remote(command_executor=args.driver, options=options)
            break
        except WebDriverException as e:
            last = e
            time.sleep(3)
    if driver is None:
        print(f'The app could not be started: {last}', file=sys.stderr)
        return 1

    tour = Tour(driver, out)
    try:
        run(tour, sites)
    finally:
        (out / 'steps.json').write_text(json.dumps(tour.steps, indent=2, ensure_ascii=False))
        try:
            driver.quit()
        except Exception:
            pass
    return 0


if __name__ == '__main__':
    sys.exit(main())
