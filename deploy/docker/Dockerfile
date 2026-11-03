# syntax=docker/dockerfile:1.7
#
# hermes + iiBot 单容器镜像：一个进程跑 iiBot（内嵌 Inbox + 两套 MCP），
# 一个进程跑 hermes gateway。两个进程靠 127.0.0.1 互相看见，和裸机拓扑一致。
#
# 版本钉在 deploy/docker/versions.env：宿主基础镜像 / iiBot jar / 插件 tag。
ARG HERMES_BASE=ghcr.io/nousresearch/hermes-agent:main
FROM ${HERMES_BASE}

USER root
SHELL ["/bin/bash", "-o", "pipefail", "-c"]

ARG IIBOT_VERSION=Alpha20261103
ARG IIBOT_SHA256=a362b0e5475ff345e45dca1f3b001875d1377b0203c67e0fc62b3bc181c8ded6
ARG IIBOT_REPO=https://github.com/c1589190/iiBotMosire
ARG PLUGINS_REPO=https://github.com/c1589190/InboxMosire-Hermes
ARG PLUGINS_REF=v0.2.6

# 宿主镜像里没有 JRE（实测 Dockerfile 里 0 处 openjdk）；iiBot 只要求 JDK 21 运行时
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      openjdk-21-jre-headless curl ca-certificates patch tini \
 && rm -rf /var/lib/apt/lists/*

# ---------- iiBot（全依赖 shade jar，单文件） ----------
RUN mkdir -p /opt/iibot \
 && curl -fsSL --retry 3 -o /opt/iibot/iibot-app.jar \
      "${IIBOT_REPO}/releases/download/${IIBOT_VERSION}/iibot-app-${IIBOT_VERSION}.jar" \
 && echo "${IIBOT_SHA256}  /opt/iibot/iibot-app.jar" | sha256sum -c -

# ---------- 插件与宿主补丁：只消费 tag，镜像内不再留第二份源码 ----------
RUN curl -fsSL --retry 3 -o /tmp/plugins.tar.gz \
      "${PLUGINS_REPO}/archive/refs/tags/${PLUGINS_REF}.tar.gz" \
 && mkdir -p /tmp/pl /opt/hermes-defaults/plugins /opt/hermes-reference \
 && tar -xzf /tmp/plugins.tar.gz -C /tmp/pl --strip-components=1 \
 && cp -a /tmp/pl/plugins/persona-mode /tmp/pl/plugins/inbox-wake /opt/hermes-defaults/plugins/ \
 && cp -a /tmp/pl/patches /opt/hermes-reference/ \
 && cp -a /tmp/pl/scripts/check-vision.py /opt/hermes-reference/ \
 && rm -rf /tmp/pl /tmp/plugins.tar.gz \
 && for id in persona-mode inbox-wake; do \
      printf '[build] plugin %s %s\n' "$id" \
        "$(sed -n 's/^version:[[:space:]]*"\?\([^"]*\)"\?/\1/p' /opt/hermes-defaults/plugins/$id/plugin.yaml)"; \
    done

# 宿主补丁：让 agent.system_prompt_override 生效（极简 system）。上游内置后自动跳过。
RUN set -e; \
    src=/opt/hermes/agent/system_prompt.py; \
    patch_file=/opt/hermes-reference/patches/0001-host-system-prompt-override.patch; \
    if [ ! -f "$src" ]; then \
      echo "[build] $HERMES_BASE 里没有 /opt/hermes/agent/system_prompt.py；请换 HERMES_BASE 或改这里的路径" >&2; \
      exit 1; \
    fi; \
    if grep -q _system_prompt_override "$src"; then \
      echo "[build] 宿主已内置 system_prompt_override，跳过补丁"; \
    else \
      patch -p1 --forward -d /opt/hermes < "$patch_file"; \
      grep -q _system_prompt_override "$src" || { echo "[build] 补丁没生效" >&2; exit 1; }; \
      echo "[build] 已应用宿主补丁 $(basename "$patch_file")"; \
    fi

# ---------- 首启播种 + s6 服务 + 主程序 ----------
COPY defaults/ /opt/hermes-defaults/deploy/
COPY iibot/run /etc/s6-overlay/s6-rc.d/iibot/run
COPY iibot/type /etc/s6-overlay/s6-rc.d/iibot/type
RUN mkdir -p /etc/s6-overlay/s6-rc.d/user/contents.d \
 && chmod +x /etc/s6-overlay/s6-rc.d/iibot/run \
 && touch /etc/s6-overlay/s6-rc.d/user/contents.d/iibot
COPY --chmod=0755 cont-init.d/05-iibot-seed /etc/cont-init.d/05-iibot-seed
COPY --chmod=0755 cmd.sh /opt/iibot/cmd.sh

ENV IIBOT_DATA_DIR=/opt/iibot/data \
    IIBOT_CONFIG=/opt/iibot/data/bot.json
VOLUME ["/opt/iibot/data"]

WORKDIR /opt/iibot
CMD ["/opt/iibot/cmd.sh"]
