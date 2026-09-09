#!/bin/bash
#
# ps-check - تست و عیب‌یابی دسترسی به شبکه پلی‌استیشن
#
# نشان می‌دهد کدام سرویس مستقیم باز است، کدام فقط از تونل باز می‌شود،
# و آیا آی‌پی سرور خارج از طرف Sony مسدود شده یا نه.
#

set -uo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; BOLD='\033[1m'; DIM='\033[2m'; NC='\033[0m'
ok()   { echo -e "${GREEN}✓ $1${NC}"; }
bad()  { echo -e "${RED}✗ $1${NC}"; }
warn() { echo -e "${YELLOW}⚠ $1${NC}"; }
info() { echo -e "${BLUE}ℹ $1${NC}"; }
head2(){ echo; echo -e "${BOLD}▸ $1${NC}"; echo "----------------------------------------"; }

CONF_DIR="/etc/ps-gateway"
SETTINGS="${CONF_DIR}/settings.env"
SERVER_ENV="${CONF_DIR}/server.env"
SERVICE="ps-gateway"

SOCKS_PORT=1080
HTTP_PORT=8888
LAN_IP="127.0.0.1"

if [ -f "$SETTINGS" ]; then . "$SETTINGS"; fi
if [ -f "$SERVER_ENV" ] && [ "$(id -u)" -eq 0 ]; then . "$SERVER_ENV"; fi

PROXY="socks5h://127.0.0.1:${SOCKS_PORT}"
CURL_OPTS=(-s -o /dev/null -m 12 -w '%{http_code}')

FAILED=0

# ------------------------------------------------------------------
# توابع کمکی
# ------------------------------------------------------------------
HAVE_SS=0
command -v ss >/dev/null 2>&1 && HAVE_SS=1

tcp_port_open() {
    # آیا چیزی روی این پورت TCP محلی گوش می‌دهد؟
    local port="$1"
    if [ "$HAVE_SS" = "1" ] && ss -lnt 2>/dev/null | grep -q ":${port} "; then
        return 0
    fi
    timeout 3 bash -c ">/dev/tcp/127.0.0.1/${port}" 2>/dev/null
}

udp_port_open() {
    local port="$1"
    [ "$HAVE_SS" = "1" ] || return 2
    ss -lnu 2>/dev/null | grep -q ":${port} "
}

http_code() {
    # فقط کد وضعیت را برمی‌گرداند. اگر اتصال برقرار نشود 000 می‌دهد.
    local url="$1" via="${2:-direct}" out
    if [ "$via" = "tunnel" ]; then
        out="$(curl "${CURL_OPTS[@]}" --proxy "$PROXY" "$url" 2>/dev/null)" || true
    else
        out="$(curl "${CURL_OPTS[@]}" "$url" 2>/dev/null)" || true
    fi
    out="$(printf '%s' "$out" | tr -dc '0-9' | head -c 3)"
    printf '%s' "${out:-000}"
}

# ------------------------------------------------------------------
head2 "۱. سرویس محلی"

if systemctl is-active --quiet "$SERVICE" 2>/dev/null; then
    ok "سرویس ${SERVICE} در حال اجراست"
else
    bad "سرویس ${SERVICE} اجرا نمی‌شود"
    info "راه‌حل:  systemctl start ${SERVICE}  و سپس  journalctl -u ${SERVICE} -n 50"
    FAILED=1
fi

for p in "$SOCKS_PORT" "$HTTP_PORT"; do
    if tcp_port_open "$p"; then
        ok "پورت ${p} در حال گوش دادن است"
    else
        bad "پورت ${p} باز نیست"
        FAILED=1
    fi
done

if udp_port_open 53; then
    ok "سرویس DNS روی پورت 53 فعال است"
elif [ "$?" = "2" ]; then
    warn "ابزار ss نصب نیست؛ وضعیت پورت 53 بررسی نشد (apt install -y iproute2)"
else
    warn "پورت 53 گوش نمی‌دهد. DNS دستی روی کنسول کار نخواهد کرد."
fi

# ------------------------------------------------------------------
head2 "۲. اتصال به سرور خارج"

if [ -n "${SRV_HOST:-}" ]; then
    info "سرور: ${SRV_HOST}:${SRV_PORT:-443}"
    if command -v nc >/dev/null 2>&1 && nc -z -w5 "$SRV_HOST" "${SRV_PORT:-443}" 2>/dev/null; then
        ok "پورت سرور از این شبکه قابل دسترسی است"
    elif timeout 5 bash -c ">/dev/tcp/${SRV_HOST}/${SRV_PORT:-443}" 2>/dev/null; then
        ok "پورت سرور از این شبکه قابل دسترسی است"
    else
        bad "به ${SRV_HOST}:${SRV_PORT:-443} وصل نمی‌شود"
        info "احتمالاً آی‌پی سرور از ایران مسدود شده. سرور یا پورت را عوض کنید."
        FAILED=1
    fi

    RTT="$(ping -c 3 -W 2 "$SRV_HOST" 2>/dev/null | tail -1 | awk -F'/' '{print $5}')"
    [ -n "${RTT:-}" ] && info "میانگین پینگ تا سرور: ${RTT} ms"
else
    warn "اطلاعات سرور خوانده نشد (این تست را با sudo اجرا کنید)"
fi

TUNNEL_OK=0
code="$(curl "${CURL_OPTS[@]}" --proxy "$PROXY" https://www.google.com/generate_204 2>/dev/null || echo 000)"
if [ "$code" = "204" ] || [ "$code" = "200" ]; then
    ok "تونل کار می‌کند (پاسخ ${code})"
    TUNNEL_OK=1
else
    bad "تونل جواب نمی‌دهد (کد ${code})"
    info "UUID، Public Key، Short ID و SNI را با سرور مقایسه کنید."
    FAILED=1
fi

# ------------------------------------------------------------------
head2 "۳. آی‌پی خروجی"

IP_DIRECT="$(curl -s -m 10 https://api.ipify.org 2>/dev/null || echo '?')"
info "آی‌پی مستقیم این دستگاه: ${IP_DIRECT}"

if [ "$TUNNEL_OK" = "1" ]; then
    GEO="$(curl -s -m 12 --proxy "$PROXY" https://ipinfo.io/json 2>/dev/null || echo '')"
    if [ -n "$GEO" ] && command -v jq >/dev/null 2>&1; then
        IP_TUN="$(echo "$GEO"  | jq -r '.ip      // "?"')"
        CC="$(echo "$GEO"      | jq -r '.country // "?"')"
        ORGN="$(echo "$GEO"    | jq -r '.org     // "?"')"
        info "آی‌پی از تونل: ${IP_TUN}"
        info "کشور سرور:    ${CC}"
        info "شبکه سرور:    ${ORGN}"
        if [ "$CC" = "IR" ]; then
            bad "سرور شما در ایران است! Sony این رنج را می‌بندد."
            FAILED=1
        elif [ "$IP_TUN" = "$IP_DIRECT" ]; then
            bad "آی‌پی تونل و مستقیم یکی است. ترافیک از تونل رد نمی‌شود."
            FAILED=1
        else
            ok "ترافیک واقعاً از سرور خارج بیرون می‌رود"
        fi
    fi
fi

# ------------------------------------------------------------------
head2 "۴. سرویس‌های Sony"

# برچسب | آدرس | کدهای قابل قبول
# برچسب‌ها انگلیسی هستند تا ستون‌ها به هم نریزند
PSN_TESTS=(
    "playstation.com|https://www.playstation.com/|200 301 302"
    "store.playstation.com|https://store.playstation.com/|200 301 302 307"
    "ca.account.sony.com|https://ca.account.sony.com/api/v1/ssocookie|200 400 401 403 404 405"
    "auth.api.sonyent...|https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token|400 401 405 415"
    "gs2.ww.prod.dl.psn|https://gs2.ww.prod.dl.playstation.net/|200 301 302 400 403 404"
)

printf "  %-22s %-10s %-10s\n" "SERVICE" "DIRECT" "TUNNEL"
echo "  ------------------------------------------------"

PSN_TUNNEL_OK=0
PSN_TUNNEL_BAD=0

mark() {
    # کد وضعیت را با علامت و رنگ برمی‌گرداند، با عرض ثابت
    local code="$1" accept="$2" cell
    if [[ " $accept " == *" $code "* ]]; then
        cell="$(printf '%-10s' "${code} ok")"
        printf '%b' "${GREEN}${cell}${NC}"
    else
        cell="$(printf '%-10s' "${code} --")"
        printf '%b' "${RED}${cell}${NC}"
    fi
}

for entry in "${PSN_TESTS[@]}"; do
    IFS='|' read -r name url accept <<< "$entry"

    d="$(http_code "$url" direct)"
    t="000"
    [ "$TUNNEL_OK" = "1" ] && t="$(http_code "$url" tunnel)"

    printf "  %-22s " "$name"
    mark "$d" "$accept"
    printf " "
    mark "$t" "$accept"
    printf "\n"

    if [[ " $accept " == *" $t "* ]]; then
        PSN_TUNNEL_OK=$((PSN_TUNNEL_OK + 1))
    else
        PSN_TUNNEL_BAD=$((PSN_TUNNEL_BAD + 1))
    fi
done
unset IFS

echo
if [ "$PSN_TUNNEL_BAD" -eq 0 ]; then
    ok "همه سرویس‌های Sony از تونل باز می‌شوند"
elif [ "$PSN_TUNNEL_OK" -gt 0 ]; then
    warn "بخشی از سرویس‌های Sony از تونل باز نشد (${PSN_TUNNEL_BAD} مورد)"
    info "معمولاً یعنی Sony رنج آی‌پی دیتاسنتر سرور را مسدود کرده."
    info "راه‌حل: سرور را به ارائه‌دهنده یا کشور دیگری منتقل کنید."
else
    bad "هیچ سرویس Sony از تونل باز نشد"
    FAILED=1
fi

# ------------------------------------------------------------------
head2 "۵. آلودگی DNS"

resolve_system() {
    getent ahostsv4 "$1" 2>/dev/null | awk '{print $1; exit}'
}

resolve_at() {
    local domain="$1" server="$2"
    if command -v dig >/dev/null 2>&1; then
        dig +short +time=3 +tries=1 "$domain" @"$server" A 2>/dev/null \
            | grep -E '^[0-9]+\.' | head -1
    elif command -v nslookup >/dev/null 2>&1; then
        nslookup "$domain" "$server" 2>/dev/null \
            | awk '/^Address: /{print $2; exit}'
    fi
}

TEST_DOMAIN="store.playstation.com"
echo "  resolve برای ${TEST_DOMAIN}:"

SYS_RES="$(resolve_system "$TEST_DOMAIN")"
printf "    %-20s %s\n" "سیستم (ISP)" "${SYS_RES:-پاسخی نیامد}"

# آی‌پی‌هایی که یعنی صفحه فیلترینگ
case "${SYS_RES:-}" in
    10.10.34.*|127.0.0.*|0.0.0.0|"")
        bad "پاسخ DNS اپراتور آلوده یا مسدود است"
        info "روی کنسول Primary DNS را روی ${LAN_IP} بگذارید."
        FAILED=1
        ;;
esac

if udp_port_open 53; then
    if command -v dig >/dev/null 2>&1 || command -v nslookup >/dev/null 2>&1; then
        GW_RES="$(resolve_at "$TEST_DOMAIN" 127.0.0.1)"
        printf "    %-20s %s\n" "دروازه (DoH)" "${GW_RES:-پاسخی نیامد}"
        if [ -z "${GW_RES:-}" ]; then
            bad "سرویس DNS دروازه پاسخ نداد"
            info "بررسی کنید تونل بالا باشد:  journalctl -u ${SERVICE} -n 30"
            FAILED=1
        elif [ -n "${SYS_RES:-}" ] && [ "$SYS_RES" != "$GW_RES" ]; then
            warn "پاسخ اپراتور با پاسخ سالم فرق دارد؛ احتمال آلودگی DNS هست."
            info "روی کنسول Primary DNS را روی ${LAN_IP} بگذارید."
        else
            ok "DNS سالم است"
        fi
    else
        warn "ابزار dig نصب نیست؛ مقایسه DNS انجام نشد."
        info "نصب:  apt install -y dnsutils"
    fi
elif [ "$?" = "2" ]; then
    warn "ابزار ss نصب نیست؛ مقایسه DNS انجام نشد."
else
    warn "پورت 53 گوش نمی‌دهد؛ DNS دستی روی کنسول کار نمی‌کند."
fi

# ------------------------------------------------------------------
head2 "۶. وضعیت مسیریابی کنسول"

if [ -n "${CONSOLE_IP:-}" ]; then
    for cip in $(echo "$CONSOLE_IP" | tr ',' ' '); do
        if ping -c 1 -W 2 "$cip" >/dev/null 2>&1; then
            ok "کنسول ${cip} در شبکه پیدا شد"
        else
            warn "کنسول ${cip} پاسخ نداد (شاید خاموش است یا ping بسته است)"
        fi
    done
else
    warn "آی‌پی کنسول ثبت نشده:  ps-mode console <ip>"
fi

if [ "${MODE:-proxy}" = "gateway" ]; then
    if nft list table ip ps_gateway >/dev/null 2>&1; then
        ok "قواعد مسیریابی شفاف فعال است"
    else
        bad "حالت gateway انتخاب شده ولی قواعد nftables وجود ندارد"
        info "راه‌حل:  ps-mode gateway"
        FAILED=1
    fi
    [ "$(cat /proc/sys/net/ipv4/ip_forward)" = "1" ] \
        && ok "فوروارد بسته‌ها روشن است" \
        || { bad "ip_forward خاموش است"; FAILED=1; }
fi

# ------------------------------------------------------------------
head2 "خلاصه"

if [ "$FAILED" -eq 0 ] && [ "$PSN_TUNNEL_BAD" -eq 0 ]; then
    ok "همه چیز سالم است. کنسول باید بدون مشکل به PSN وصل شود."
elif [ "$FAILED" -eq 0 ]; then
    warn "تونل سالم است ولی Sony بخشی از درخواست‌ها را رد می‌کند."
    echo
    info "ترتیب کارهایی که باید امتحان کنید:"
    echo "   ۱. سرور را به کشوری با تحریم کمتر منتقل کنید (آلمان، هلند، ترکیه، امارات)"
    echo "   ۲. از ارائه‌دهنده‌ای استفاده کنید که رنج آی‌پی خانگی می‌دهد نه دیتاسنتر"
    echo "   ۳. حساب PSN را روی کشوری بسازید که با آی‌پی سرور هم‌خوان است"
else
    bad "مشکلاتی پیدا شد. موارد ✗ بالا را به ترتیب برطرف کنید."
fi
echo
exit "$FAILED"
