#!/bin/bash
#
# ps-genconfig - بازسازی کانفیگ Xray از روی فایل‌های تنظیمات
#
# ورودی‌ها:
#   /etc/ps-gateway/server.env      اطلاعات سرور خارج
#   /etc/ps-gateway/settings.env    تنظیمات دروازه
#   /etc/ps-gateway/domains/*.list  لیست دامنه‌ها
# خروجی:
#   /etc/ps-gateway/config.json
#

set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; NC='\033[0m'
ok()  { echo -e "${GREEN}✓ $1${NC}"; }
err() { echo -e "${RED}✗ $1${NC}"; }

CONF_DIR="/etc/ps-gateway"
SHARE_DIR="/usr/local/share/ps-gateway"
XRAY_BIN="/usr/local/bin/ps-xray"

[ "$(id -u)" -eq 0 ] || { err "با sudo اجرا کنید"; exit 1; }
[ -f "${CONF_DIR}/server.env" ]   || { err "فایل server.env پیدا نشد"; exit 1; }
[ -f "${CONF_DIR}/settings.env" ] || { err "فایل settings.env پیدا نشد"; exit 1; }

# shellcheck disable=SC1090
. "${CONF_DIR}/server.env"
# shellcheck disable=SC1090
. "${CONF_DIR}/settings.env"

to_json_domains() {
    local file="$1"
    [ -f "$file" ] || { echo ""; return; }
    grep -vE '^[[:space:]]*(#|$)' "$file" \
        | tr -d ' \t\r' \
        | grep -v '^$' \
        | sort -u \
        | sed 's/^/"domain:/; s/$/"/' \
        | paste -sd, -
}

PSN_DOMAINS="$(to_json_domains "${CONF_DIR}/domains/psn.list")"
GAMES_DOMAINS="$(to_json_domains "${CONF_DIR}/domains/games.list")"
CUSTOM_DOMAINS="$(to_json_domains "${CONF_DIR}/domains/custom.list")"

[ -n "$PSN_DOMAINS" ] || { err "لیست دامنه‌های PSN خالی است"; exit 1; }

# وقتی حالت بازی‌ها خاموش است، دامنه ناشران از تونل رد نمی‌شود
if [ "${GAMES_MODE:-on}" != "on" ]; then
    GAMES_DOMAINS=""
fi

# دامنه‌های دلخواه کاربر همیشه اعمال می‌شوند
TUNNEL_DOMAINS="$PSN_DOMAINS"
[ -n "$GAMES_DOMAINS" ]  && TUNNEL_DOMAINS="${TUNNEL_DOMAINS},${GAMES_DOMAINS}"
[ -n "$CUSTOM_DOMAINS" ] && TUNNEL_DOMAINS="${TUNNEL_DOMAINS},${CUSTOM_DOMAINS}"

SID_JSON='""'
[ -n "${SRV_SID:-}" ] && SID_JSON="\"${SRV_SID}\""

FLOW_JSON=""
[ -n "${SRV_FLOW:-}" ] && FLOW_JSON="\"flow\": \"${SRV_FLOW}\","

# Xray قالب فایل را از پسوند تشخیص می‌دهد، پس فایل موقت باید .json باشد
TMPDIR_GEN="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_GEN"' EXIT
TMP="${TMPDIR_GEN}/config.json"
cat > "$TMP" <<JSONEOF
{
  "log": { "loglevel": "warning" },

  "dns": {
    "queryStrategy": "UseIPv4",
    "servers": [
      {
        "address": "https://1.1.1.1/dns-query",
        "domains": [${TUNNEL_DOMAINS}],
        "skipFallback": true
      },
      "localhost"
    ]
  },

  "inbounds": [
    {
      "tag": "http-in",
      "listen": "0.0.0.0",
      "port": ${HTTP_PORT:-8888},
      "protocol": "http",
      "settings": { "allowTransparent": false },
      "sniffing": { "enabled": true, "destOverride": ["http", "tls"] }
    },
    {
      "tag": "socks-in",
      "listen": "0.0.0.0",
      "port": ${SOCKS_PORT:-1080},
      "protocol": "socks",
      "settings": { "auth": "noauth", "udp": true },
      "sniffing": { "enabled": true, "destOverride": ["http", "tls"] }
    },
    {
      "tag": "dns-in",
      "listen": "0.0.0.0",
      "port": 53,
      "protocol": "dokodemo-door",
      "settings": { "address": "1.1.1.1", "port": 53, "network": "tcp,udp" }
    },
    {
      "tag": "tproxy-in",
      "listen": "0.0.0.0",
      "port": ${TPROXY_PORT:-12345},
      "protocol": "dokodemo-door",
      "settings": { "network": "tcp,udp", "followRedirect": true },
      "streamSettings": { "sockopt": { "tproxy": "tproxy" } },
      "sniffing": { "enabled": true, "destOverride": ["http", "tls"], "routeOnly": false }
    }
  ],

  "outbounds": [
    {
      "tag": "direct",
      "protocol": "freedom",
      "settings": { "domainStrategy": "UseIP" },
      "streamSettings": { "sockopt": { "mark": ${BYPASS_MARK:-255} } }
    },
    {
      "tag": "proxy",
      "protocol": "vless",
      "settings": {
        "vnext": [
          {
            "address": "${SRV_HOST}",
            "port": ${SRV_PORT:-443},
            "users": [
              { "id": "${SRV_UUID}", ${FLOW_JSON} "encryption": "none" }
            ]
          }
        ]
      },
      "streamSettings": {
        "network": "tcp",
        "security": "reality",
        "realitySettings": {
          "serverName": "${SRV_SNI}",
          "fingerprint": "${SRV_FP:-chrome}",
          "publicKey": "${SRV_PBK}",
          "shortId": ${SID_JSON},
          "spiderX": ""
        },
        "sockopt": { "mark": ${BYPASS_MARK:-255} }
      }
    },
    { "tag": "dns-out", "protocol": "dns", "settings": { "nonIPQuery": "skip" } },
    { "tag": "block", "protocol": "blackhole" }
  ],

  "routing": {
    "domainStrategy": "IPIfNonMatch",
    "rules": [
      { "type": "field", "inboundTag": ["dns-in"], "outboundTag": "dns-out" },
      { "type": "field", "inboundTag": ["tproxy-in"], "port": 53, "network": "tcp,udp", "outboundTag": "dns-out" },
      { "type": "field", "domain": [${TUNNEL_DOMAINS}], "outboundTag": "proxy" },
      { "type": "field", "ip": ["1.1.1.1/32", "1.0.0.1/32"], "outboundTag": "proxy" },
      { "type": "field", "ip": ["geoip:private"], "outboundTag": "direct" },
      { "type": "field", "network": "tcp,udp", "outboundTag": "direct" }
    ]
  }
}
JSONEOF

if command -v jq >/dev/null 2>&1 && ! jq empty "$TMP" 2>/dev/null; then
    err "کانفیگ تولیدشده JSON معتبری نیست"
    exit 1
fi

if [ -x "$XRAY_BIN" ]; then
    if ! XRAY_LOCATION_ASSET="$SHARE_DIR" "$XRAY_BIN" run -test -c "$TMP" >/dev/null 2>&1; then
        err "Xray کانفیگ را نپذیرفت:"
        XRAY_LOCATION_ASSET="$SHARE_DIR" "$XRAY_BIN" run -test -c "$TMP" 2>&1 | tail -20
        exit 1
    fi
fi

install -m 600 "$TMP" "${CONF_DIR}/config.json"

DOMAIN_COUNT="$(echo "$TUNNEL_DOMAINS" | tr ',' '\n' | grep -c . || true)"
ok "کانفیگ ساخته شد (${DOMAIN_COUNT} دامنه از تونل عبور می‌کنند)"
