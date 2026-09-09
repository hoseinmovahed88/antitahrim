#!/bin/bash
#
# ps-mode - تغییر حالت کاری دروازه پلی‌استیشن
#
#   ps-mode proxy      حالت پروکسی (پیش‌فرض، امن برای NAT Type)
#   ps-mode gateway    حالت دروازه شفاف (کل ترافیک وب کنسول)
#   ps-mode off        خاموش کردن مسیریابی شفاف
#   ps-mode status     نمایش وضعیت
#   ps-mode console <ip>   تعیین/افزودن آی‌پی کنسول
#   ps-mode games on|off   عبور دامنه ناشران بازی از تونل
#   ps-mode quic  on|off   مسدودسازی QUIC کنسول
#

set -euo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; BOLD='\033[1m'; NC='\033[0m'
ok()   { echo -e "${GREEN}✓ $1${NC}"; }
err()  { echo -e "${RED}✗ $1${NC}"; }
warn() { echo -e "${YELLOW}⚠ $1${NC}"; }
info() { echo -e "${BLUE}ℹ $1${NC}"; }

CONF_DIR="/etc/ps-gateway"
SETTINGS="${CONF_DIR}/settings.env"
SERVICE="ps-gateway"
NFT_TABLE="ps_gateway"
ROUTE_TABLE=100

[ "$(id -u)" -eq 0 ] || { err "با sudo اجرا کنید"; exit 1; }
[ -f "$SETTINGS" ] || { err "دروازه نصب نشده. ابتدا install-ps-gateway.sh را اجرا کنید."; exit 1; }

# shellcheck disable=SC1090
. "$SETTINGS"

save_setting() {
    local key="$1" val="$2"
    if grep -qE "^${key}=" "$SETTINGS"; then
        sed -i "s|^${key}=.*|${key}=${val}|" "$SETTINGS"
    else
        echo "${key}=${val}" >> "$SETTINGS"
    fi
}

console_list() {
    # آی‌پی‌های کنسول به صورت جداشده با کاما
    echo "${CONSOLE_IP:-}" | tr ',' '\n' | sed 's/ //g' | grep -v '^$' || true
}

# ------------------------------------------------------------------
# مسیریابی شفاف
# ------------------------------------------------------------------
require_tools() {
    local missing=""
    command -v nft >/dev/null 2>&1 || missing="${missing} nftables"
    command -v ip  >/dev/null 2>&1 || missing="${missing} iproute2"
    if [ -n "$missing" ]; then
        err "این بسته‌ها نصب نیستند:${missing}"
        info "نصب:  apt install -y${missing}"
        exit 1
    fi
}

set_sysctl() {
    # اگر هسته اجازه ندهد (مثلا داخل کانتینر) فقط هشدار می‌دهیم
    sysctl -qw "$1" 2>/dev/null || warn "تنظیم $1 اعمال نشد"
}

apply_sysctl() {
    # فوروارد لازم است تا ترافیکی که تونل نمی‌شود عبور کند
    set_sysctl net.ipv4.ip_forward=1
    # بدون این، لینوکس به کنسول می‌گوید مستقیم به مودم برود و دروازه دور زده می‌شود
    set_sysctl net.ipv4.conf.all.send_redirects=0
    set_sysctl net.ipv4.conf.default.send_redirects=0
    [ -n "${LAN_IF:-}" ] && sysctl -qw "net.ipv4.conf.${LAN_IF}.send_redirects=0" 2>/dev/null || true
    # مسیریابی نامتقارن TPROXY با rp_filter سخت‌گیرانه سازگار نیست
    set_sysctl net.ipv4.conf.all.rp_filter=0
    set_sysctl net.ipv4.conf.default.rp_filter=0
    [ -n "${LAN_IF:-}" ] && sysctl -qw "net.ipv4.conf.${LAN_IF}.rp_filter=0" 2>/dev/null || true

    mkdir -p /etc/sysctl.d
    cat > /etc/sysctl.d/99-ps-gateway.conf <<EOF
net.ipv4.ip_forward = 1
net.ipv4.conf.all.send_redirects = 0
net.ipv4.conf.default.send_redirects = 0
net.ipv4.conf.all.rp_filter = 0
net.ipv4.conf.default.rp_filter = 0
EOF
}

apply_iproute() {
    ip rule show | grep -q "fwmark ${FWMARK} lookup ${ROUTE_TABLE}" || \
        ip rule add fwmark "${FWMARK}" lookup "${ROUTE_TABLE}"
    ip route show table "${ROUTE_TABLE}" 2>/dev/null | grep -q '^local default' || \
        ip route add local default dev lo table "${ROUTE_TABLE}"
}

clear_iproute() {
    while ip rule show | grep -q "fwmark ${FWMARK} lookup ${ROUTE_TABLE}"; do
        ip rule del fwmark "${FWMARK}" lookup "${ROUTE_TABLE}" 2>/dev/null || break
    done
    ip route flush table "${ROUTE_TABLE}" 2>/dev/null || true
}

nft_flush() {
    nft list table ip "${NFT_TABLE}" >/dev/null 2>&1 && nft delete table ip "${NFT_TABLE}" || true
}

nft_apply() {
    local consoles
    consoles="$(console_list | paste -sd, -)"
    if [ -z "$consoles" ]; then
        err "هیچ آی‌پی کنسولی ثبت نشده است."
        info "اول اجرا کنید:  ps-mode console 192.168.1.50"
        exit 1
    fi

    local quic_rule=""
    if [ "${BLOCK_QUIC:-off}" = "on" ]; then
        quic_rule='        meta l4proto udp udp dport 443 drop'
    fi

    nft_flush
    nft -f - <<EOF
table ip ${NFT_TABLE} {
    set consoles {
        type ipv4_addr
        elements = { ${consoles} }
    }

    set bypass_dst {
        type ipv4_addr
        flags interval
        elements = {
            0.0.0.0/8,
            10.0.0.0/8,
            100.64.0.0/10,
            127.0.0.0/8,
            169.254.0.0/16,
            172.16.0.0/12,
            192.168.0.0/16,
            224.0.0.0/4,
            240.0.0.0/4
        }
    }

    # بسته‌های متعلق به اتصال‌های ازپیش‌برقرارشده را به سوکت شفاف تحویل می‌دهد
    chain divert {
        type filter hook prerouting priority mangle - 10; policy accept;
        meta l4proto tcp socket transparent 1 meta mark set ${FWMARK} accept
    }

    chain prerouting {
        type filter hook prerouting priority mangle; policy accept;

        # ترافیک تولیدشده توسط خود Xray هرگز نباید دوباره گرفته شود
        meta mark ${BYPASS_MARK} return

        # فقط ترافیک کنسول‌ها
        ip saddr != @consoles return

        # مقصدهای محلی دست نخورده باقی می‌مانند
        ip daddr @bypass_dst return

${quic_rule}

        # DNS کنسول به مفسر داخلی می‌رود تا از آلودگی DNS در امان بماند
        meta l4proto udp udp dport 53 tproxy to :${TPROXY_PORT} meta mark set ${FWMARK} accept
        meta l4proto tcp tcp dport 53 tproxy to :${TPROXY_PORT} meta mark set ${FWMARK} accept

        # فقط وب کنسول گرفته می‌شود. UDP بازی‌ها دست‌نخورده می‌ماند تا NAT Type سالم بماند.
        meta l4proto tcp tcp dport { 80, 443 } tproxy to :${TPROXY_PORT} meta mark set ${FWMARK} accept
    }
}
EOF
}

persist_nft() {
    mkdir -p /etc/ps-gateway
    nft list table ip "${NFT_TABLE}" > "${CONF_DIR}/nftables.rules" 2>/dev/null || true

    cat > /etc/systemd/system/ps-gateway-rules.service <<'UNITEOF'
[Unit]
Description=PlayStation Gateway transparent routing rules
After=ps-gateway.service network-online.target
Wants=ps-gateway.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/local/bin/ps-mode apply-boot
ExecStop=/usr/local/bin/ps-mode off

[Install]
WantedBy=multi-user.target
UNITEOF
    systemctl daemon-reload
    systemctl enable ps-gateway-rules >/dev/null 2>&1 || true
}

mode_gateway() {
    require_tools
    systemctl is-active --quiet "$SERVICE" || systemctl start "$SERVICE"
    apply_sysctl
    apply_iproute
    nft_apply
    save_setting MODE gateway
    persist_nft

    ok "حالت دروازه شفاف فعال شد"
    echo
    info "روی کنسول تنظیم کنید:"
    echo "    IP Address Settings ... Manual"
    echo "        IP Address ........ $(console_list | head -1)"
    echo "        Subnet Mask ....... 255.255.255.0"
    echo "        Default Gateway ... ${LAN_IP}      ← این دستگاه"
    echo "        Primary DNS ....... ${LAN_IP}"
    echo "        Secondary DNS ..... 1.1.1.1"
    echo "    Proxy Server .......... Do Not Use"
    echo
    warn "این دستگاه باید همیشه روشن بماند وگرنه کنسول اینترنت ندارد."
}

mode_proxy() {
    nft_flush
    clear_iproute
    systemctl disable ps-gateway-rules >/dev/null 2>&1 || true
    systemctl is-active --quiet "$SERVICE" || systemctl start "$SERVICE"
    save_setting MODE proxy

    ok "حالت پروکسی فعال شد"
    echo
    info "روی کنسول تنظیم کنید:"
    echo "    IP Address Settings ... Automatic"
    echo "    DNS Settings .......... Manual"
    echo "        Primary DNS ....... ${LAN_IP}"
    echo "        Secondary DNS ..... 1.1.1.1"
    echo "    Proxy Server .......... Use"
    echo "        Address ........... ${LAN_IP}"
    echo "        Port .............. ${HTTP_PORT}"
}

mode_off() {
    nft_flush
    clear_iproute
    systemctl disable ps-gateway-rules >/dev/null 2>&1 || true
    save_setting MODE off
    ok "مسیریابی شفاف خاموش شد (سرویس Xray هنوز اجرا می‌شود)"
    info "برای خاموش کردن کامل:  systemctl stop ${SERVICE}"
}

apply_boot() {
    # هنگام بوت فقط اگر حالت دروازه ذخیره شده باشد اعمال می‌شود
    if [ "${MODE:-proxy}" = "gateway" ]; then
        require_tools
        apply_sysctl
        apply_iproute
        nft_apply
    fi
}

# ------------------------------------------------------------------
show_status() {
    echo
    echo -e "${BOLD}وضعیت دروازه پلی‌استیشن${NC}"
    echo "----------------------------------------"
    printf "  حالت:            %s\n" "${MODE:-نامشخص}"
    printf "  حالت بازی‌ها:     %s\n" "${GAMES_MODE:-on}"
    printf "  مسدودسازی QUIC:  %s\n" "${BLOCK_QUIC:-off}"
    printf "  آی‌پی دستگاه:     %s (%s)\n" "${LAN_IP}" "${LAN_IF}"
    printf "  کنسول‌ها:         %s\n" "$(console_list | paste -sd', ' - || echo 'ثبت نشده')"
    printf "  پورت پروکسی:     %s\n" "${HTTP_PORT}"

    if systemctl is-active --quiet "$SERVICE" 2>/dev/null; then
        printf "  سرویس Xray:      ${GREEN}در حال اجرا${NC}\n"
    else
        printf "  سرویس Xray:      ${RED}متوقف${NC}\n"
    fi

    if nft list table ip "${NFT_TABLE}" >/dev/null 2>&1; then
        printf "  قواعد شفاف:      ${GREEN}فعال${NC}\n"
    else
        printf "  قواعد شفاف:      غیرفعال\n"
    fi
    echo "----------------------------------------"
    echo
}

set_console() {
    local ip="$1"
    if ! [[ "$ip" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]]; then
        err "آی‌پی معتبر نیست: $ip"; exit 1
    fi
    local current; current="$(console_list | paste -sd, - || true)"
    if echo "$current" | tr ',' '\n' | grep -qx "$ip"; then
        info "این آی‌پی از قبل ثبت شده است"
    else
        [ -n "$current" ] && current="${current},${ip}" || current="$ip"
        save_setting CONSOLE_IP "$current"
        ok "کنسول ثبت شد: $ip"
    fi
    if [ "${MODE:-proxy}" = "gateway" ]; then
        . "$SETTINGS"; nft_apply; ok "قواعد به‌روزرسانی شد"
    fi
}

toggle_games() {
    case "${1:-}" in
        on|off)
            save_setting GAMES_MODE "$1"
            if command -v ps-genconfig >/dev/null 2>&1 && ps-genconfig; then
                systemctl restart "$SERVICE" 2>/dev/null || true
                ok "حالت بازی‌ها: $1 (اعمال شد)"
            else
                err "کانفیگ بازسازی نشد. تنظیم ذخیره شد ولی اعمال نشده است."
                exit 1
            fi
            ;;
        *) err "مقدار باید on یا off باشد"; exit 1 ;;
    esac
}

toggle_quic() {
    case "${1:-}" in
        on|off) save_setting BLOCK_QUIC "$1"
                . "$SETTINGS"
                [ "${MODE:-proxy}" = "gateway" ] && nft_apply
                ok "مسدودسازی QUIC: $1"
                ;;
        *) err "مقدار باید on یا off باشد"; exit 1 ;;
    esac
}

usage() {
    cat <<'USAGE'
ps-mode - تغییر حالت دروازه پلی‌استیشن

  ps-mode status              نمایش وضعیت
  ps-mode proxy               حالت پروکسی (پیش‌فرض، NAT سالم می‌ماند)
  ps-mode gateway             حالت دروازه شفاف
  ps-mode off                 خاموش کردن مسیریابی شفاف
  ps-mode console <ip>        ثبت آی‌پی کنسول
  ps-mode games on|off        عبور دامنه ناشران بازی از تونل
  ps-mode quic on|off         مسدودسازی QUIC کنسول
USAGE
}

case "${1:-status}" in
    proxy)      mode_proxy ;;
    gateway)    mode_gateway ;;
    off)        mode_off ;;
    status)     show_status ;;
    console)    set_console "${2:-}" ;;
    games)      toggle_games "${2:-}" ;;
    quic)       toggle_quic "${2:-}" ;;
    apply-boot) apply_boot ;;
    -h|--help|help) usage ;;
    *)          err "دستور ناشناخته: $1"; echo; usage; exit 1 ;;
esac
