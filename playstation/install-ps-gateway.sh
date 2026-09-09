#!/bin/bash
#
# PlayStation Gateway - Installer
# نصب دروازه پلی‌استیشن روی یک دستگاه لینوکسی داخل شبکه خانگی (ایران)
#
# این اسکریپت روی سرور خارج اجرا نمی‌شود!
# روی یک Raspberry Pi / مینی‌پی‌سی / لپ‌تاپ قدیمی / ماشین مجازی اوبونتو
# که به همان مودم کنسول وصل است اجرا می‌شود.
#
# https://github.com/hoseinmovahed88/antitahrim
#

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
BOLD='\033[1m'
NC='\033[0m'

print_success() { echo -e "${GREEN}✓ $1${NC}"; }
print_error()   { echo -e "${RED}✗ $1${NC}"; }
print_warn()    { echo -e "${YELLOW}⚠ $1${NC}"; }
print_info()    { echo -e "${BLUE}ℹ $1${NC}"; }
print_step()    { echo; echo -e "${BOLD}▸ $1${NC}"; }

CONF_DIR="/etc/ps-gateway"
BIN_DIR="/usr/local/bin"
SHARE_DIR="/usr/local/share/ps-gateway"
XRAY_BIN="${BIN_DIR}/ps-xray"
SERVICE="ps-gateway"

HTTP_PORT=8888
SOCKS_PORT=1080
TPROXY_PORT=12345
FWMARK=1
BYPASS_MARK=255

# ------------------------------------------------------------------
# پیش‌نیازها
# ------------------------------------------------------------------
require_root() {
    if [ "$(id -u)" -ne 0 ]; then
        print_error "این اسکریپت باید با دسترسی root اجرا شود (sudo ./install-ps-gateway.sh)"
        exit 1
    fi
}

detect_arch() {
    case "$(uname -m)" in
        x86_64|amd64)   XRAY_ASSET="Xray-linux-64.zip" ;;
        aarch64|arm64)  XRAY_ASSET="Xray-linux-arm64-v8a.zip" ;;
        armv7l|armv7)   XRAY_ASSET="Xray-linux-arm32-v7a.zip" ;;
        i386|i686)      XRAY_ASSET="Xray-linux-32.zip" ;;
        *)
            print_error "معماری پردازنده پشتیبانی نمی‌شود: $(uname -m)"
            exit 1
            ;;
    esac
}

install_deps() {
    print_step "نصب پیش‌نیازها"
    if command -v apt-get >/dev/null 2>&1; then
        export DEBIAN_FRONTEND=noninteractive
        apt-get update -qq
        apt-get install -y -qq curl unzip jq nftables iproute2 ca-certificates dnsutils >/dev/null
    elif command -v dnf >/dev/null 2>&1; then
        dnf install -y -q curl unzip jq nftables iproute bind-utils >/dev/null
    else
        print_warn "پکیج‌منیجر شناخته نشد. مطمئن شوید curl, unzip, jq, nftables نصب هستند."
    fi
    print_success "پیش‌نیازها آماده شد"
}

install_xray() {
    print_step "دانلود هسته Xray"
    local tmp; tmp="$(mktemp -d)"
    local url="https://github.com/XTLS/Xray-core/releases/latest/download/${XRAY_ASSET}"

    if ! curl -fsSL -o "${tmp}/xray.zip" "$url"; then
        print_error "دانلود Xray ناموفق بود. اتصال اینترنت این دستگاه را بررسی کنید."
        rm -rf "$tmp"; exit 1
    fi

    unzip -qo "${tmp}/xray.zip" -d "${tmp}/x"
    install -m 755 "${tmp}/x/xray" "$XRAY_BIN"
    mkdir -p "$SHARE_DIR"
    install -m 644 "${tmp}/x/geoip.dat"   "${SHARE_DIR}/geoip.dat"
    install -m 644 "${tmp}/x/geosite.dat" "${SHARE_DIR}/geosite.dat"
    rm -rf "$tmp"

    print_success "Xray نصب شد: $("$XRAY_BIN" version | head -1)"
}

# ------------------------------------------------------------------
# دریافت اطلاعات سرور
# ------------------------------------------------------------------
urldecode() { printf '%b' "${1//%/\\x}"; }

parse_vless_link() {
    local link="$1"
    link="${link#vless://}"
    link="${link%%#*}"

    local userinfo="${link%%@*}"
    local rest="${link#*@}"
    local hostport="${rest%%\?*}"
    local query=""
    [[ "$rest" == *\?* ]] && query="${rest#*\?}"

    SRV_UUID="$userinfo"
    SRV_HOST="${hostport%%:*}"
    SRV_PORT="${hostport##*:}"
    [ "$SRV_PORT" = "$SRV_HOST" ] && SRV_PORT=443

    SRV_SNI=""; SRV_PBK=""; SRV_SID=""; SRV_FLOW=""; SRV_FP="chrome"
    local IFS='&' kv
    for kv in $query; do
        local k="${kv%%=*}" v="${kv#*=}"
        v="$(urldecode "$v")"
        case "$k" in
            sni)  SRV_SNI="$v" ;;
            pbk)  SRV_PBK="$v" ;;
            sid)  SRV_SID="$v" ;;
            flow) SRV_FLOW="$v" ;;
            fp)   SRV_FP="$v" ;;
        esac
    done
}

collect_server_info() {
    print_step "اطلاعات سرور خارج (Xray Reality)"
    echo
    print_info "اگر لینک vless:// سرورتان را دارید، همان را بچسبانید."
    print_info "وگرنه Enter بزنید تا مقادیر را جداگانه وارد کنید."
    echo
    read -rp "لینک vless:// (اختیاری): " VLESS_LINK

    if [ -n "${VLESS_LINK:-}" ]; then
        if [[ "$VLESS_LINK" != vless://* ]]; then
            print_error "لینک باید با vless:// شروع شود."
            exit 1
        fi
        parse_vless_link "$VLESS_LINK"
        print_success "لینک خوانده شد"
    else
        read -rp "آدرس IP سرور: "        SRV_HOST
        read -rp "پورت [443]: "          SRV_PORT
        read -rp "UUID: "                SRV_UUID
        read -rp "Public Key (pbk): "    SRV_PBK
        read -rp "Short ID (sid): "      SRV_SID
        read -rp "SNI [www.google.com]: " SRV_SNI
        SRV_PORT="${SRV_PORT:-443}"
        SRV_SNI="${SRV_SNI:-www.google.com}"
        SRV_FLOW="xtls-rprx-vision"
        SRV_FP="chrome"
    fi

    SRV_FLOW="${SRV_FLOW:-xtls-rprx-vision}"
    SRV_FP="${SRV_FP:-chrome}"

    for v in SRV_HOST SRV_UUID SRV_PBK SRV_SNI; do
        if [ -z "${!v:-}" ]; then
            print_error "مقدار $v خالی است. نصب متوقف شد."
            exit 1
        fi
    done

    echo
    print_info "سرور:  ${SRV_HOST}:${SRV_PORT}"
    print_info "SNI:   ${SRV_SNI}"
    print_info "Flow:  ${SRV_FLOW}"
}

collect_console_info() {
    print_step "اطلاعات کنسول"
    echo
    LAN_IF="$(ip -4 route show default 2>/dev/null | awk '{print $5; exit}')"
    if [ -z "$LAN_IF" ]; then
        print_error "مسیر پیش‌فرض شبکه پیدا نشد. اتصال این دستگاه به مودم را بررسی کنید."
        exit 1
    fi
    LAN_IP="$(ip -4 -o addr show dev "$LAN_IF" 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1)"
    ROUTER_IP="$(ip -4 route show default 2>/dev/null | awk '{print $3; exit}')"
    if [ -z "$LAN_IP" ]; then
        print_error "آی‌پی کارت شبکه ${LAN_IF} خوانده نشد."
        exit 1
    fi

    print_warn "این دستگاه باید آی‌پی ثابت داشته باشد."
    print_warn "اگر آی‌پی آن عوض شود، تنظیمات کنسول از کار می‌افتد."
    print_info "روی مودم برای MAC این دستگاه یک DHCP Reservation بگذارید."
    echo
    print_info "کارت شبکه این دستگاه: ${LAN_IF}"
    print_info "آی‌پی این دستگاه:      ${LAN_IP}"
    print_info "آی‌پی مودم:            ${ROUTER_IP}"
    echo
    print_info "آی‌پی کنسول را در تنظیمات شبکه پلی‌استیشن ببینید:"
    print_info "Settings → Network → View Connection Status"
    echo
    read -rp "آی‌پی کنسول (مثلا 192.168.1.50) [می‌توانید خالی بگذارید]: " CONSOLE_IP
}

# ------------------------------------------------------------------
# ساخت کانفیگ
# ------------------------------------------------------------------
write_config() {
    print_step "ساخت فایل کانفیگ"
    mkdir -p "${CONF_DIR}/domains"
    install -m 644 "${SRC_DIR}/domains/psn.list"   "${CONF_DIR}/domains/psn.list"
    install -m 644 "${SRC_DIR}/domains/games.list" "${CONF_DIR}/domains/games.list"
    [ -f "${CONF_DIR}/domains/custom.list" ] || cat > "${CONF_DIR}/domains/custom.list" <<'CUSTOMEOF'
# دامنه‌های دلخواه شما
# اگر بازی یا سرویسی هنوز باز نشد، دامنه‌اش را اینجا اضافه کنید
# (یک دامنه در هر خط، بدون http)
CUSTOMEOF

    cat > "${CONF_DIR}/server.env" <<ENVEOF
# اطلاعات سرور خارج - برای بازسازی کانفیگ استفاده می‌شود
SRV_HOST=${SRV_HOST}
SRV_PORT=${SRV_PORT}
SRV_UUID=${SRV_UUID}
SRV_PBK=${SRV_PBK}
SRV_SID=${SRV_SID}
SRV_SNI=${SRV_SNI}
SRV_FLOW=${SRV_FLOW}
SRV_FP=${SRV_FP}
ENVEOF
    chmod 600 "${CONF_DIR}/server.env"

    cat > "${CONF_DIR}/settings.env" <<ENVEOF
# تنظیمات دروازه پلی‌استیشن
LAN_IF=${LAN_IF}
LAN_IP=${LAN_IP}
ROUTER_IP=${ROUTER_IP}
CONSOLE_IP=${CONSOLE_IP:-}
HTTP_PORT=${HTTP_PORT}
SOCKS_PORT=${SOCKS_PORT}
TPROXY_PORT=${TPROXY_PORT}
FWMARK=${FWMARK}
BYPASS_MARK=${BYPASS_MARK}
# حالت فعلی: proxy | gateway | off
MODE=proxy
# عبور دامنه ناشران بازی از تونل (on/off)
GAMES_MODE=on
# مسدودسازی QUIC کنسول تا ترافیک به TCP برگردد (on/off)
BLOCK_QUIC=off
ENVEOF

    install -m 755 "${SRC_DIR}/ps-genconfig.sh" "${BIN_DIR}/ps-genconfig"
    "${BIN_DIR}/ps-genconfig"
}

# ------------------------------------------------------------------
# پورت ۵۳ و systemd-resolved
# ------------------------------------------------------------------
free_port_53() {
    print_step "آزادسازی پورت 53"
    if ss -lnu 2>/dev/null | grep -q ':53 ' || ss -lnt 2>/dev/null | grep -q ':53 '; then
        if systemctl is-active --quiet systemd-resolved 2>/dev/null; then
            print_warn "systemd-resolved روی پورت 53 گوش می‌دهد. شنونده آن غیرفعال می‌شود."
            mkdir -p /etc/systemd/resolved.conf.d
            cat > /etc/systemd/resolved.conf.d/ps-gateway.conf <<'RESOLVEOF'
[Resolve]
DNSStubListener=no
RESOLVEOF
            if [ -L /etc/resolv.conf ] || [ ! -s /etc/resolv.conf ]; then
                ln -sf /run/systemd/resolve/resolv.conf /etc/resolv.conf
            fi
            systemctl restart systemd-resolved
            print_success "پورت 53 آزاد شد"
        else
            print_warn "سرویس دیگری روی پورت 53 است. اگر DNS کار نکرد آن را متوقف کنید."
        fi
    else
        print_success "پورت 53 آزاد است"
    fi

    # اگر resolv.conf به خود این دستگاه اشاره کند، حل نام دچار حلقه می‌شود
    if grep -qE '^\s*nameserver\s+127\.0\.0\.1\s*$' /etc/resolv.conf 2>/dev/null; then
        print_warn "فایل /etc/resolv.conf به 127.0.0.1 اشاره می‌کند."
        print_warn "این باعث حلقه در حل نام می‌شود. آن را به 1.1.1.1 تغییر دهید."
    fi
}

# ------------------------------------------------------------------
# سرویس systemd
# ------------------------------------------------------------------
install_service() {
    print_step "ساخت سرویس systemd"
    cat > "/etc/systemd/system/${SERVICE}.service" <<UNITEOF
[Unit]
Description=PlayStation Gateway (Xray split-routing client)
Documentation=https://github.com/hoseinmovahed88/antitahrim
After=network-online.target nss-lookup.target
Wants=network-online.target

[Service]
Type=simple
Environment=XRAY_LOCATION_ASSET=${SHARE_DIR}
ExecStart=${XRAY_BIN} run -c ${CONF_DIR}/config.json
Restart=on-failure
RestartSec=5
LimitNOFILE=1048576
AmbientCapabilities=CAP_NET_ADMIN CAP_NET_BIND_SERVICE CAP_NET_RAW
NoNewPrivileges=false

[Install]
WantedBy=multi-user.target
UNITEOF

    systemctl daemon-reload
    systemctl enable --now "$SERVICE" >/dev/null 2>&1 || systemctl enable "$SERVICE" >/dev/null 2>&1
    systemctl restart "$SERVICE"
    sleep 2

    if systemctl is-active --quiet "$SERVICE" 2>/dev/null; then
        print_success "سرویس ${SERVICE} در حال اجراست"
    else
        print_error "سرویس بالا نیامد. لاگ:"
        journalctl -u "$SERVICE" -n 30 --no-pager
        exit 1
    fi
}

install_helpers() {
    print_step "نصب دستورات کمکی"
    install -m 755 "${SRC_DIR}/ps-mode.sh"   "${BIN_DIR}/ps-mode"
    install -m 755 "${SRC_DIR}/check-psn.sh" "${BIN_DIR}/ps-check"
    install -m 755 "${SRC_DIR}/manage-ps.sh" "${BIN_DIR}/ps-manage"
    install -m 755 "${SRC_DIR}/ps-genconfig.sh" "${BIN_DIR}/ps-genconfig"
    print_success "دستورها نصب شد: ps-manage / ps-mode / ps-check / ps-genconfig"
}

# ------------------------------------------------------------------
# خلاصه پایانی
# ------------------------------------------------------------------
final_report() {
    echo
    echo "=========================================="
    print_success "دروازه پلی‌استیشن نصب شد!"
    echo "=========================================="
    echo
    echo -e "${BOLD}تنظیمات کنسول (روش پیشنهادی - Proxy):${NC}"
    echo
    echo "  Settings → Network → Set Up Internet Connection"
    echo "  → Use Wi-Fi یا Use a LAN Cable → Custom"
    echo
    echo "    IP Address Settings ....... Automatic"
    echo "    DHCP Host Name ............ Do Not Specify"
    echo "    DNS Settings .............. Manual"
    echo "        Primary DNS ........... ${LAN_IP}"
    echo "        Secondary DNS ......... 1.1.1.1"
    echo "    MTU Settings .............. Automatic"
    echo "    Proxy Server .............. Use"
    echo "        Address ............... ${LAN_IP}"
    echo "        Port .................. ${HTTP_PORT}"
    echo
    print_info "با این روش فقط ترافیک فروشگاه و لاگین از تونل رد می‌شود."
    print_info "ترافیک خود بازی مستقیم می‌ماند، پس NAT Type و پینگ خراب نمی‌شود."
    echo
    echo -e "${BOLD}دستورهای مدیریت:${NC}"
    echo "  ps-manage              منوی مدیریت"
    echo "  ps-check               تست و عیب‌یابی"
    echo "  ps-mode gateway        حالت دروازه شفاف (برای بازی‌های سرسخت)"
    echo "  ps-mode proxy          برگشت به حالت پروکسی"
    echo "  journalctl -u ${SERVICE} -f    مشاهده لاگ"
    echo
    echo "=========================================="
}

# ------------------------------------------------------------------
main() {
    SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

    clear
    echo "=========================================="
    echo "  PlayStation Gateway - نصب"
    echo "  دسترسی به PSN و بازی‌ها از داخل ایران"
    echo "=========================================="

    require_root
    detect_arch
    install_deps
    install_xray
    collect_server_info
    collect_console_info
    write_config
    free_port_53
    install_service
    install_helpers
    final_report
}

main "$@"
