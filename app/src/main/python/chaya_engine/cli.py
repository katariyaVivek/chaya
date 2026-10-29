"""The engine as a program, for Chaya for Windows.

The phone calls `extract` through Chaquopy. The desktop app has no Python inside it, so it runs a bundled
Python with this module instead, and reads one line of JSON from what it prints:

    python -I run.py extract URL --cache DIR [--cookies FILE] [--js-runtime QJS] [--fragments] [--subtitles]
                                 [--referer PAGE]
    python -I run.py selftest [--js-runtime QJS]
    python -I run.py profile SITE USERNAME --cache DIR --out LIST --stop FILE [--cookies FILE]

`extract` prints what `extract.extract` returns. `profile` lists an account's pictures and videos into LIST for a ZIP
(`profiles.list_profile`, one JSON object a line) and prints its summary; creating FILE stops it. `selftest` prints `selftest.status`, plus what yt-dlp makes
of the QuickJS program when one is given. Both exit 0 with an answer; any other exit, or a last line that is
not JSON, means the engine itself is broken.
"""
import argparse
import json
import sys


def main(argv=None):
    # Windows' console encoding is not UTF-8; the app reads UTF-8 whatever the PC's language.
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding='utf-8', errors='replace')

    parser = argparse.ArgumentParser(prog='chaya_engine', description='Chaya\'s yt-dlp engine.')
    commands = parser.add_subparsers(dest='command', required=True)

    extract_command = commands.add_parser('extract', help='Look a link up.')
    extract_command.add_argument('url')
    extract_command.add_argument('--cache', required=True, help="yt-dlp's cache folder")
    extract_command.add_argument('--cookies', help='a Netscape cookie file, only when the person chose a sign-in')
    extract_command.add_argument('--js-runtime', help="a QuickJS program for YouTube's challenges")
    extract_command.add_argument('--fragments', action='store_true',
                                 help="list each DASH format's pieces, for an app that fetches them itself")
    extract_command.add_argument('--subtitles', action='store_true',
                                 help="list the video's language and the site's subtitles, for an app that saves them")
    extract_command.add_argument('--referer', help='the page a stream was found on, sent as its referrer')

    profile_command = commands.add_parser('profile', help="List an account's posts for a ZIP.")
    profile_command.add_argument('site', choices=('instagram', 'twitter'))
    profile_command.add_argument('username')
    profile_command.add_argument('--cache', required=True, help="gallery-dl's cache folder")
    profile_command.add_argument('--out', required=True, help='the list, written as LIST.partial until complete')
    profile_command.add_argument('--stop', required=True, help='a file whose appearance stops the listing')
    profile_command.add_argument('--cookies', help='a Netscape cookie file, only when the person chose a sign-in')

    selftest_command = commands.add_parser('selftest', help='Check the engine without using the network.')
    selftest_command.add_argument('--js-runtime', help='a QuickJS program to check as well')

    args = parser.parse_args(argv)
    if args.command == 'extract':
        from .extract import extract

        print(extract(args.url, args.cache, args.cookies, js_runtime=args.js_runtime, fragments=args.fragments,
                      referer=args.referer, subtitles=args.subtitles))
    elif args.command == 'profile':
        from .profiles import list_profile

        print(list_profile(args.site, args.username, args.cache, args.cookies, args.out, args.stop))
    else:
        print(selftest(args.js_runtime))
    sys.stdout.flush()
    return 0


def selftest(js_runtime=None):
    """`selftest.status`, and when [js_runtime] is given, whether yt-dlp can use it for YouTube."""
    from .selftest import status

    answer = json.loads(status())
    if js_runtime:
        answer['js_runtime'] = _js_runtime_info(js_runtime)
    return json.dumps(answer)


def _js_runtime_info(path):
    from yt_dlp.utils._jsruntime import QuickJsRuntime

    info = QuickJsRuntime(path).info
    if info is None:
        return {'found': False}
    return {'found': True, 'name': info.name, 'version': info.version, 'supported': info.supported}


if __name__ == '__main__':
    sys.exit(main())
