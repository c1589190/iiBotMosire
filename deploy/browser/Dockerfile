# Chromium CDP sidecar for iibot-video-connect remote-cdp mode.
# The CDP port is bound to loopback; run this container in the same network
# namespace as the Java process (for example network_mode: service:iibot).
FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       ca-certificates chromium curl ffmpeg fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --gid 10001 iibot \
    && useradd --uid 10001 --gid iibot --create-home iibot \
    && mkdir -p /data/chromium \
    && chown -R iibot:iibot /data

USER iibot
ENV IIBOT_CHROMIUM=/usr/bin/chromium

EXPOSE 9222
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD curl -fsS http://127.0.0.1:9222/json/version || exit 1

ENTRYPOINT ["/usr/bin/chromium", \
    "--headless=new", \
    "--disable-gpu", \
    "--disable-dev-shm-usage", \
    "--no-first-run", \
    "--remote-debugging-address=127.0.0.1", \
    "--remote-debugging-port=9222", \
    "--user-data-dir=/data/chromium", \
    "about:blank"]
