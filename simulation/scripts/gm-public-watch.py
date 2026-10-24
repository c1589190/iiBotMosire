#!/usr/bin/env python3
"""Read-only public-group watcher for the iiBot personal campaign.

It periodically asks the GM MCP history tool for the latest public-group page,
records newly observed messages to a private runtime event log, and does not
send QQ messages, create posts, advance turns, or write world state.
"""
import argparse
import datetime as dt
import fcntl
import json
import os
import pathlib
import signal
import subprocess
import sys
import time

ROOT = pathlib.Path('/home/cna/iiBotMosire')
MCP = ROOT / 'simulation/scripts/06-mcp.sh'
IDENT = ROOT / 'simulation/init/identities.local.json'
OUT = ROOT / 'simulation/runtime/public-watch'
LOCK_FILE = OUT / 'watch.lock'
PID_FILE = OUT / 'watch.pid'
EVENTS = OUT / 'events.jsonl'
STATUS = OUT / 'status.json'
LOG = OUT / 'watch.log'
DEFAULT_INTERVAL = 30.0
POLL_TIMEOUT = 45
MAX_RUNTIME_SECONDS = 7 * 24 * 60 * 60
SEEN_LIMIT = 500


def utcnow():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def write_json_atomic(path, value):
    tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    os.chmod(tmp, 0o600)
    tmp.replace(path)


def append_log(text):
    with LOG.open('a', encoding='utf-8') as handle:
        handle.write(f'{utcnow()} {text}\n')
    os.chmod(LOG, 0o600)


def append_event(kind, payload):
    row = {'observedAt': utcnow(), 'event': kind, 'payload': payload}
    with EVENTS.open('a', encoding='utf-8') as handle:
        handle.write(json.dumps(row, ensure_ascii=False) + '\n')
    os.chmod(EVENTS, 0o600)
    append_log(kind + ' ' + json.dumps(payload, ensure_ascii=False)[:400])


def call_tool(name, args):
    proc = subprocess.run(
        [str(MCP), name, json.dumps(args, ensure_ascii=False)],
        cwd=str(ROOT), text=True, capture_output=True, timeout=POLL_TIMEOUT)
    if proc.returncode != 0:
        raise RuntimeError(f'{name} rc={proc.returncode}')
    for line in reversed(proc.stdout.splitlines()):
        line = line.strip()
        if not line.startswith('{'):
            continue
        parsed = json.loads(line)
        if parsed.get('ok') is not True:
            raise RuntimeError(f"{name} code={parsed.get('code')}")
        return parsed.get('data', {})
    raise RuntimeError(f'{name} returned no JSON')


def public_group_id():
    data = json.loads(IDENT.read_text(encoding='utf-8'))
    group_id = str(data.get('publicGroupId') or '').strip()
    if not group_id:
        raise RuntimeError('public group id missing')
    return group_id


def load_seen():
    status = {}
    if STATUS.exists():
        try:
            status = json.loads(STATUS.read_text(encoding='utf-8'))
        except Exception:
            status = {}
    values = status.get('seenMessageIds') or []
    return set(str(v) for v in values if str(v).strip())


def save_seen(seen):
    ordered = list(seen)[-SEEN_LIMIT:]
    return ordered


def parse_args(argv):
    parser = argparse.ArgumentParser(description='read-only iiBot public-group watcher')
    parser.add_argument('--interval', type=float, default=DEFAULT_INTERVAL)
    parser.add_argument('--once', action='store_true')
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    if args.interval < 5.0 or args.interval > 3600.0:
        print('interval must be in [5, 3600]', file=sys.stderr)
        return 2
    OUT.mkdir(parents=True, exist_ok=True)
    os.chmod(OUT, 0o700)
    lock_handle = LOCK_FILE.open('w')
    try:
        fcntl.flock(lock_handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        print('another public watcher is already running', file=sys.stderr)
        return 0
    PID_FILE.write_text(str(os.getpid()) + '\n', encoding='utf-8')
    os.chmod(PID_FILE, 0o600)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    signal.signal(signal.SIGINT, lambda *_: sys.exit(0))

    group_id = public_group_id()
    seen = load_seen()
    baseline_done = bool(seen)
    started = time.monotonic()
    poll_count = 0
    last_error = None

    while time.monotonic() - started < MAX_RUNTIME_SECONDS:
        loop_start = time.monotonic()
        try:
            page = call_tool('tf_qq_history_read', {
                'chatType': 'group',
                'targetId': group_id,
                'count': 20,
                'direction': 'older',
            })
            messages = page.get('messages') or []
            new_messages = []
            for message in messages:
                mid = str(message.get('messageId') or '')
                if not mid:
                    continue
                if mid in seen:
                    continue
                seen.add(mid)
                new_messages.append({
                    'messageId': mid,
                    'senderId': message.get('senderId'),
                    'senderName': message.get('senderName'),
                    'sentAt': message.get('sentAt'),
                    'text': message.get('text'),
                })
            if baseline_done:
                for message in sorted(new_messages, key=lambda item: item.get('sentAt') or ''):
                    append_event('NEW_PUBLIC_MESSAGE', message)
            else:
                append_event('PUBLIC_BASELINE', {'count': len(new_messages)})
                baseline_done = True
            last_error = None
        except Exception as exc:
            last_error = type(exc).__name__
            append_log(f'ERROR {last_error}: {str(exc)[:180]}')

        poll_count += 1
        write_json_atomic(STATUS, {
            'pid': os.getpid(),
            'startedAt': dt.datetime.fromtimestamp(
                time.time() - (time.monotonic() - started), dt.timezone.utc).isoformat(),
            'intervalSeconds': args.interval,
            'lastPollAt': utcnow(),
            'lastError': last_error,
            'pollCount': poll_count,
            'knownMessageCount': len(seen),
            'seenMessageIds': save_seen(seen),
            'policy': 'read-only public history watcher; no QQ send, no post creation, '
                      'no turn advancement, no world write',
        })
        if args.once:
            return 0 if last_error is None else 1
        elapsed = time.monotonic() - loop_start
        if elapsed < args.interval:
            time.sleep(args.interval - elapsed)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
