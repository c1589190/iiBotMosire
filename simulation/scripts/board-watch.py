#!/usr/bin/env python3
"""5-second MCP board watcher.

Records ticket/post changes only. It never approves, replies, closes or publishes.
"""
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
OUT = ROOT / 'simulation/runtime/board-watch'
OUT.mkdir(parents=True, exist_ok=True)
os.chmod(OUT, 0o700)
LOCK_FILE = OUT / 'watch.lock'
EVENTS = OUT / 'events.jsonl'
STATUS = OUT / 'status.json'
LOG = OUT / 'watch.log'
INTERVAL = 5.0
POLL_TIMEOUT = 45
MAX_RUNTIME_SECONDS = 24 * 60 * 60

lock_handle = LOCK_FILE.open('w')
try:
    fcntl.flock(lock_handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError:
    print('another board watcher is already running', file=sys.stderr)
    raise SystemExit(0)


def utcnow():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def write_json_atomic(path, value):
    tmp = path.with_suffix(path.suffix + '.tmp')
    tmp.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    os.chmod(tmp, 0o600)
    tmp.replace(path)


def append_event(kind, item):
    record = {'observedAt': utcnow(), 'event': kind, 'item': item}
    with EVENTS.open('a', encoding='utf-8') as handle:
        handle.write(json.dumps(record, ensure_ascii=False) + '\n')
    os.chmod(EVENTS, 0o600)
    with LOG.open('a', encoding='utf-8') as handle:
        handle.write(f"{record['observedAt']} {kind} "
                     f"{item.get('ticketId') or item.get('postId') or '-'} "
                     f"{item.get('type') or item.get('visibility') or '-'} "
                     f"{item.get('status') or '-'} "
                     f"{(item.get('title') or '')[:80]}\n")


def call_tool(name, args=None):
    payload = json.dumps(args or {}, ensure_ascii=False)
    proc = subprocess.run([str(MCP), name, payload], cwd=str(ROOT),
                          text=True, capture_output=True, timeout=POLL_TIMEOUT)
    if proc.returncode != 0:
        raise RuntimeError(f'{name} rc={proc.returncode}')
    for line in reversed(proc.stdout.splitlines()):
        line = line.strip()
        if line.startswith('{'):
            parsed = json.loads(line)
            if not parsed.get('ok', False):
                raise RuntimeError(f"{name} code={parsed.get('code')}")
            return parsed.get('data', {})
    raise RuntimeError(f'{name} returned no JSON')


def ticket_fingerprint(ticket):
    return (ticket.get('status'), ticket.get('revision'),
            ticket.get('replyCount'), ticket.get('updatedAt'))


def post_fingerprint(post):
    return (post.get('status'), post.get('revision'),
            post.get('deliveryState'), post.get('updatedAt'))


seen_tickets = {}
seen_posts = {}
started = time.monotonic()
last_ticket_poll = None
last_post_poll = None
last_error = None
baseline_done = False
poll_count = 0

signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
signal.signal(signal.SIGINT, lambda *_: sys.exit(0))

while time.monotonic() - started < MAX_RUNTIME_SECONDS:
    loop_start = time.monotonic()
    try:
        # Ticket board: list is the authoritative change feed.
        tickets = call_tool('tf_ticket_list', {}).get('tickets', [])
        for summary in tickets:
            tid = summary.get('ticketId')
            if not tid:
                continue
            fp = ticket_fingerprint(summary)
            if tid not in seen_tickets or seen_tickets[tid] != fp:
                detail = call_tool('tf_ticket_get', {'ticketId': tid})
                append_event('TICKET_BASELINE' if not baseline_done else 'TICKET_CHANGED', detail)
                seen_tickets[tid] = fp
        last_ticket_poll = utcnow()

        # Pending registration/notice posts: detect new or changed pending drafts.
        pending = call_tool('tf_post_list', {'status': 'PENDING_APPROVAL'}).get('posts', [])
        for summary in pending:
            post_id = summary.get('postId')
            if not post_id:
                continue
            fp = post_fingerprint(summary)
            if post_id not in seen_posts or seen_posts[post_id] != fp:
                detail = call_tool('tf_post_get', {'postId': post_id})
                append_event('POST_BASELINE' if not baseline_done else 'POST_CHANGED', detail.get('post', detail))
                seen_posts[post_id] = fp
        last_post_poll = utcnow()
        last_error = None
        baseline_done = True
    except Exception as exc:
        last_error = type(exc).__name__
        with LOG.open('a', encoding='utf-8') as handle:
            handle.write(f'{utcnow()} ERROR {last_error}: {str(exc)[:180]}\n')

    poll_count += 1
    write_json_atomic(STATUS, {
        'pid': os.getpid(),
        'startedAt': dt.datetime.fromtimestamp(
            time.time() - (time.monotonic() - started), dt.timezone.utc).isoformat(),
        'intervalSeconds': INTERVAL,
        'lastPollAt': utcnow(),
        'lastTicketPollAt': last_ticket_poll,
        'lastPostPollAt': last_post_poll,
        'lastError': last_error,
        'pollCount': poll_count,
        'knownTickets': len(seen_tickets),
        'knownPendingPosts': len(seen_posts),
        'eventsFile': str(EVENTS),
        'statusFile': str(STATUS),
    })

    elapsed = time.monotonic() - loop_start
    if elapsed < INTERVAL:
        time.sleep(INTERVAL - elapsed)
