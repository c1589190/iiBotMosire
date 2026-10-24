#!/usr/bin/env python3
"""Read-only board watchdog with explicit publish-existing mode.

Default policy:
- Poll tickets and pending posts.
- Record only state changes.
- Never generate narrative, advance a campaign clock, auto-reply tickets, or publish.

Optional `--publish-existing`:
- Only approve/publish posts that already exist as PENDING_APPROVAL when this
  process starts and appear later. It never creates a post.
- Requires a human to start the process explicitly.
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
OUT = ROOT / 'simulation/runtime/board-autopilot'
OUT.mkdir(parents=True, exist_ok=True)
os.chmod(OUT, 0o700)
LOCK_FILE = OUT / 'autopilot.lock'
PID_FILE = OUT / 'autopilot.pid'
EVENTS = OUT / 'events.jsonl'
STATUS = OUT / 'status.json'
LOG = OUT / 'autopilot.log'
INTERVAL = 5.0
MAX_RUNTIME = 24 * 60 * 60
POLL_TIMEOUT = 60


def utcnow():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def log_line(text):
    with LOG.open('a', encoding='utf-8') as handle:
        handle.write(f'{utcnow()} {text}\n')
    os.chmod(LOG, 0o600)


def append_event(kind, payload):
    row = {'observedAt': utcnow(), 'event': kind, 'payload': payload}
    with EVENTS.open('a', encoding='utf-8') as handle:
        handle.write(json.dumps(row, ensure_ascii=False) + '\n')
    os.chmod(EVENTS, 0o600)
    log_line(f'{kind} {json.dumps(payload, ensure_ascii=False)[:500]}')


def write_status(payload):
    tmp = STATUS.with_suffix('.tmp')
    tmp.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    os.chmod(tmp, 0o600)
    tmp.replace(STATUS)


def call_json(tool, args=None, timeout=POLL_TIMEOUT):
    payload = json.dumps(args or {}, ensure_ascii=False)
    proc = subprocess.run([str(MCP), tool, payload], cwd=str(ROOT),
                          text=True, capture_output=True, timeout=timeout)
    if proc.returncode != 0:
        raise RuntimeError(f'{tool} rc={proc.returncode}')
    for line in reversed(proc.stdout.splitlines()):
        line = line.strip()
        if not line.startswith('{'):
            continue
        parsed = json.loads(line)
        if parsed.get('ok') is not True:
            raise RuntimeError(f"{tool} code={parsed.get('code')}")
        return parsed.get('data', {})
    raise RuntimeError(f'{tool} returned no JSON')


def board_snapshot():
    tickets = call_json('tf_ticket_board', {})
    posts = call_json('tf_post_list', {'status': 'PENDING_APPROVAL'})
    ticket_rows = tickets.get('openClaimedRecent') or tickets.get('recent') or []
    ticket_ids = sorted(
        (row.get('ticketId'), row.get('status'), row.get('revision'))
        for row in ticket_rows if row.get('ticketId')
    )
    post_ids = sorted(
        (row.get('postId'), row.get('revision'), row.get('visibility'))
        for row in posts.get('posts', []) if row.get('postId')
    )
    return {
        'ticketCount': tickets.get('count', len(ticket_ids)),
        'openClaimedTickets': ticket_ids,
        'pendingPosts': post_ids,
    }


def publish_existing(posts):
    published = []
    for summary in posts:
        post_id = summary.get('postId')
        revision = int(summary.get('revision') or 1)
        if not post_id:
            continue
        approved = call_json('tf_post_decide', {
            'postId': post_id,
            'decision': 'APPROVE',
            'expectedRevision': revision,
            'idempotencyKey': f'watchdog-approve-{post_id}-r{revision}',
        })
        next_revision = int(approved.get('revision') or revision + 1)
        result = call_json('tf_post_publish', {
            'postId': post_id,
            'expectedRevision': next_revision,
            'idempotencyKey': f'watchdog-publish-{post_id}-r{next_revision}',
        })
        published.append({
            'postId': post_id,
            'revision': result.get('revision'),
            'deliveryState': result.get('deliveryState'),
        })
    return published


def parse_args(argv):
    parser = argparse.ArgumentParser(description='Read-only iiBot board watchdog')
    parser.add_argument('--publish-existing', action='store_true',
                        help='explicitly approve/publish posts that already exist; never create posts')
    parser.add_argument('--interval', type=float, default=INTERVAL,
                        help='poll interval seconds (default 5)')
    parser.add_argument('--once', action='store_true', help='run one poll then exit')
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    if args.interval < 1.0 or args.interval > 3600.0:
        print('interval must be in [1, 3600]', file=sys.stderr)
        return 2
    lock_handle = LOCK_FILE.open('w')
    try:
        fcntl.flock(lock_handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        print('another watchdog is already running', file=sys.stderr)
        return 0
    PID_FILE.write_text(str(os.getpid()) + '\n', encoding='utf-8')
    os.chmod(PID_FILE, 0o600)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    signal.signal(signal.SIGINT, lambda *_: sys.exit(0))

    started = time.monotonic()
    last_fingerprint = None
    poll_count = 0
    while time.monotonic() - started < MAX_RUNTIME:
        try:
            snapshot = board_snapshot()
            fingerprint = json.dumps(snapshot, ensure_ascii=False, sort_keys=True)
            if fingerprint != last_fingerprint:
                append_event('BOARD_SNAPSHOT_CHANGED', snapshot)
                last_fingerprint = fingerprint
            published = []
            if args.publish_existing:
                pending = call_json('tf_post_list', {'status': 'PENDING_APPROVAL'}).get('posts', [])
                published = publish_existing(pending)
                if published:
                    append_event('POST_PUBLISHED_BY_EXPLICIT_WATCHDOG', {'posts': published})
            poll_count += 1
            write_status({
                'pid': os.getpid(),
                'startedAt': dt.datetime.fromtimestamp(
                    time.time() - (time.monotonic() - started), dt.timezone.utc).isoformat(),
                'intervalSeconds': args.interval,
                'lastPollAt': utcnow(),
                'pollCount': poll_count,
                'lastError': None,
                'publishExisting': bool(args.publish_existing),
                'lastPublished': published,
                'policy': 'read-only board snapshot; no narrative generation; no clock advance; '
                          'no ticket auto-reply; publish-existing requires explicit flag',
                'snapshot': snapshot,
            })
            if args.once:
                return 0
        except Exception as exc:  # boundary: no raw stack/tool payload in status
            write_status({
                'pid': os.getpid(),
                'lastPollAt': utcnow(),
                'lastError': type(exc).__name__,
                'pollCount': poll_count,
                'publishExisting': bool(args.publish_existing),
                'policy': 'read-only board snapshot; no narrative generation; no clock advance; '
                          'no ticket auto-reply; publish-existing requires explicit flag',
            })
            append_event('WATCHDOG_ERROR', {'error': type(exc).__name__})
            if args.once:
                return 1
        elapsed = time.monotonic() - started
        if args.once:
            return 0
        remaining = args.interval - ((time.monotonic() - started) % args.interval)
        time.sleep(max(0.05, remaining))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
