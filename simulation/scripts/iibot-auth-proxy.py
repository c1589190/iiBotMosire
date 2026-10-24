#!/usr/bin/env python3
"""Loopback-only HTTP proxy that injects the iibot control bearer token.

DSH MCP Connector connects to this proxy without credentials. The token is read
from IIBOT_CONTROL_TOKEN or ~/.config/iibot/control.env, stays in this process,
and is never printed. The proxy forwards streamable-HTTP MCP requests to the
loopback iibot control endpoint and streams SSE responses.
"""
import http.client
import os
import pathlib
import signal
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LISTEN_HOST = '127.0.0.1'
LISTEN_PORT = int(os.environ.get('IIBOT_PROXY_PORT', '8766'))
UPSTREAM_HOST = '127.0.0.1'
UPSTREAM_PORT = int(os.environ.get('IIBOT_CONTROL_PORT', '8765'))
CONTROL_ENV = pathlib.Path.home() / '.config' / 'iibot' / 'control.env'
PID_FILE = pathlib.Path('/home/cna/iiBotMosire/simulation/runtime/iibot-auth-proxy.pid')
UPSTREAM_TIMEOUT = 1900.0
HOP_HEADERS = {
    'connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
    'te', 'trailers', 'transfer-encoding', 'upgrade', 'host', 'authorization',
    'content-length',
}


def load_token():
    token = os.environ.get('IIBOT_CONTROL_TOKEN', '').strip()
    if token:
        return token
    try:
        for raw in CONTROL_ENV.read_text(encoding='utf-8').splitlines():
            line = raw.strip()
            if not line or line.startswith('#') or '=' not in line:
                continue
            key, value = line.split('=', 1)
            if key.strip() == 'IIBOT_CONTROL_TOKEN':
                value = value.strip()
                if len(value) >= 2 and value[0] in {'"', "'"} and value[-1] == value[0]:
                    value = value[1:-1]
                if value.strip():
                    return value.strip()
    except OSError:
        pass
    raise SystemExit('iibot control token unavailable')


TOKEN = load_token()


class ProxyHandler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    server_version = 'iibot-auth-proxy/1.0'

    def log_message(self, fmt, *args):
        # Deliberately omit headers, bodies and credentials.
        sys.stderr.write('%s %s\n' % (self.command, self.path))
        sys.stderr.flush()

    def do_GET(self):
        self._proxy()

    def do_POST(self):
        self._proxy()

    def do_DELETE(self):
        self._proxy()

    def do_PUT(self):
        self._proxy()

    def do_OPTIONS(self):
        self._proxy()

    def _proxy(self):
        length = self.headers.get('Content-Length')
        body = None
        if length is not None:
            try:
                n = int(length)
            except ValueError:
                self.send_error(400, 'invalid content length')
                return
            if n < 0:
                self.send_error(400, 'invalid content length')
                return
            body = self.rfile.read(n) if n else b''
        elif self.command in {'POST', 'PUT'}:
            body = b''

        headers = {}
        for key, value in self.headers.items():
            if key.lower() in HOP_HEADERS:
                continue
            headers[key] = value
        headers['Authorization'] = 'Bearer ' + TOKEN
        headers['Connection'] = 'close'

        conn = http.client.HTTPConnection(
            UPSTREAM_HOST, UPSTREAM_PORT, timeout=UPSTREAM_TIMEOUT)
        try:
            conn.request(self.command, self.path, body=body, headers=headers)
            response = conn.getresponse()
            self.send_response(response.status, response.reason)
            content_type = ''
            for key, value in response.getheaders():
                lower = key.lower()
                if lower in HOP_HEADERS:
                    continue
                if lower == 'content-type':
                    content_type = value.lower()
                self.send_header(key, value)
            self.send_header('Connection', 'close')
            self.end_headers()

            if 'text/event-stream' in content_type:
                while True:
                    line = response.readline()
                    if not line:
                        break
                    self.wfile.write(line)
                    self.wfile.flush()
            else:
                while True:
                    chunk = response.read(65536)
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    self.wfile.flush()
            self.close_connection = True
        except (BrokenPipeError, ConnectionResetError):
            self.close_connection = True
        except Exception:
            if not self.wfile.closed:
                try:
                    self.send_error(502, 'iibot control upstream unavailable')
                except Exception:
                    pass
            self.close_connection = True
        finally:
            conn.close()


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    PID_FILE.parent.mkdir(parents=True, exist_ok=True)
    server = Server((LISTEN_HOST, LISTEN_PORT), ProxyHandler)
    PID_FILE.write_text(str(os.getpid()) + '\n', encoding='utf-8')
    os.chmod(PID_FILE, 0o600)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    signal.signal(signal.SIGINT, lambda *_: sys.exit(0))
    print(f'iibot auth proxy listening on http://{LISTEN_HOST}:{LISTEN_PORT}', flush=True)
    server.serve_forever(poll_interval=0.5)


if __name__ == '__main__':
    main()
