#!/usr/bin/env python3
"""TEMPORARY, for finding why the cloud emulator vanishes; not part of any PR.

Every time the emulator vanished in the walkthrough it was seconds after a freshly cleared Chaya was sent to the
local test page at http://10.0.2.2:8000. This repeats exactly that step, changing one thing per VARIANT:

  host-media     the test page with its three videos, from 10.0.2.2 (the control: what the walkthrough does)
  host-plain     a page of text only, from 10.0.2.2
  host-bytes     a page with no <video> that fetches the same video files as bytes, from 10.0.2.2
  reverse-media  the test page with its three videos, from 127.0.0.1 through `adb reverse` (not the emulator's
                 own network)

With GDB=1 a debugger rides along on the emulator and writes what ended it (signal or exit, and every thread's
stack) to evidence/gdb.txt.
"""
import os
import subprocess
import sys
import time

import walkthrough as w

VARIANT = os.environ.get("VARIANT", "host-media")
ROUNDS = int(os.environ.get("ROUNDS", "25"))
GDB = os.environ.get("GDB") == "1"
LOCAL_URL = f"http://127.0.0.1:{w.PORT}"

PLAIN = """<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Plain page</title></head>
<body><h1>Plain page</h1><p>Text only: no video, no picture, no script.</p></body></html>"""

BYTES = """<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Bytes page</title></head>
<body><h1>Bytes page</h1><p id="out">fetching</p>
<script>
// The same files the test page's players ask for, as plain bytes: ranges, one dropped part-way, one whole.
const files = ['/media/launch-event.mp4', '/media/vast/preroll-15s.mp4', '/media/hero-loop.mp4', '/media/poster.jpg'];
const jobs = [];
for (const f of files) {
  jobs.push(fetch(f, {headers: {Range: 'bytes=0-'}}).then(r => r.arrayBuffer()).then(b => b.byteLength));
  const stop = new AbortController();
  jobs.push(fetch(f + '?again', {signal: stop.signal}).then(r => r.body.getReader().read()).then(() => stop.abort()).then(() => 'dropped'));
  jobs.push(fetch(f, {headers: {Range: 'bytes=1000-'}}).then(r => r.arrayBuffer()).then(b => b.byteLength));
}
Promise.allSettled(jobs).then(r => { document.getElementById('out').textContent = 'done ' + r.map(x => x.value || x.reason).join(' '); });
</script></body></html>"""


def emulator_pids():
    out = subprocess.run(["pgrep", "-f", "qemu-system"], capture_output=True, text=True).stdout.split()
    return [int(p) for p in out]


def attach_gdb():
    pids = emulator_pids()
    w.log(f"emulator processes: {pids}")
    if not pids:
        return None
    script = [
        "set pagination off", "set confirm off", "set print thread-events off",
        # The emulator uses plenty of signals for itself; only the ones that end a process stop it here.
        "handle all nostop noprint pass",
        "handle SIGSEGV SIGABRT SIGBUS SIGILL SIGFPE SIGSYS SIGTRAP stop print pass",
        "catch syscall exit_group",
        "continue",
        "echo \\n=== STOPPED: the thread that ended it ===\\n",
        "info program",
        "bt 40",
        "echo \\n=== registers ===\\n",
        "info registers rip rsp",
        "echo \\n=== every thread ===\\n",
        "thread apply all bt 25",
        "echo \\n=== libraries ===\\n",
        "info sharedlibrary",
        "detach",
    ]
    cmd = ["sudo", "gdb", "-q", "-batch", "-p", str(pids[0])]
    for line in script:
        cmd += ["-ex", line]
    out = open(w.EVIDENCE / "gdb.txt", "w")
    proc = subprocess.Popen(cmd, stdout=out, stderr=subprocess.STDOUT)
    # Reading the emulator's libraries holds it still for a while; wait until it answers again.
    for _ in range(60):
        time.sleep(5)
        try:
            if w.emulator_answers() and w.sh("echo ok", timeout=60).strip() == "ok":
                break
        except subprocess.TimeoutExpired:
            pass
    w.log(f"gdb attached to {pids[0]} (gdb alive: {proc.poll() is None})")
    return proc


def main():
    w.EVIDENCE.mkdir(parents=True, exist_ok=True)
    w.adb("wait-for-device")
    install = w.run(["adb", "install", "-r", "-t", "-g", w.APK], timeout=300)
    w.log(f"install: {install.stdout.strip()[-40:]}")
    import re
    size = re.search(r"(\d+)x(\d+)", w.sh("wm size").strip().splitlines()[-1])
    if size:
        w.screen_w, w.screen_h = int(size.group(1)), int(size.group(2))
    w.sh("settings put secure show_ime_with_hard_keyboard 1")
    time.sleep(20)

    w.make_fixture()
    (w.FIXTURE / "plain.html").write_text(PLAIN, encoding="utf-8")
    (w.FIXTURE / "bytes.html").write_text(BYTES, encoding="utf-8")
    (w.FIXTURE / "index-local.html").write_text(w.PAGE.replace("{BASE}", LOCAL_URL), encoding="utf-8")
    server = w.start_fixture_server()

    if VARIANT == "reverse-media":
        w.log("adb reverse: " + w.adb("reverse", f"tcp:{w.PORT}", f"tcp:{w.PORT}").strip())
    url = {
        "host-media": f"{w.HOST_URL}/index.html",
        "host-plain": f"{w.HOST_URL}/plain.html",
        "host-bytes": f"{w.HOST_URL}/bytes.html",
        "reverse-media": f"{LOCAL_URL}/index-local.html",
    }[VARIANT]

    gdb = attach_gdb() if GDB else None
    w.log(f"STRESS variant={VARIANT} rounds={ROUNDS} gdb={GDB} url={url}")

    died = None
    for i in range(1, ROUNDS + 1):
        phase = "launch"
        try:
            if not w.launch_fresh():
                raise RuntimeError("the address field never showed")
            phase = "open"
            w.log(f"round {i}: opening")
            w.open_url(url)
            phase = "after"
            time.sleep(8)
            texts = " | ".join(w.visible_texts(w.ui_dump()[0]))[:160]
            w.log(f"round {i}: alive, shows: {texts}")
        except Exception as error:  # includes EmulatorGone and adb timing out
            if not w.emulator_answers():
                died = (i, phase)
                w.log(f"round {i}: THE EMULATOR IS GONE (phase {phase}; {type(error).__name__}: {error}); "
                      f"processes left: {emulator_pids()}")
                break
            w.log(f"round {i}: step failed with the emulator still there ({type(error).__name__}: {error})")
        if not w.emulator_answers():
            died = (i, "between rounds")
            w.log(f"round {i}: THE EMULATOR IS GONE after the round; processes left: {emulator_pids()}")
            break

    w.stop_fixture_server(server)
    if gdb is not None:
        try:
            gdb.wait(timeout=120 if died else 5)
        except subprocess.TimeoutExpired:
            subprocess.run(["sudo", "pkill", "-INT", "gdb"])
            time.sleep(3)
            subprocess.run(["sudo", "pkill", "gdb"])
    verdict = f"DIED in round {died[0]} of {ROUNDS} ({died[1]})" if died else f"SURVIVED all {ROUNDS} rounds"
    w.log(f"STRESS RESULT variant={VARIANT} gdb={GDB}: {verdict}")
    (w.EVIDENCE / "stress-result.txt").write_text(f"{VARIANT} gdb={GDB}: {verdict}\n", encoding="utf-8")
    # The job is green either way: a death here is a finding, read from the log
    sys.exit(0)


if __name__ == "__main__":
    main()
