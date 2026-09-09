#!/bin/bash
#
# ps-manage - منوی مدیریت دروازه پلی‌استیشن
#

set -uo pipefail

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; BOLD='\033[1m'; NC='\033[0m'
ok()   { echo -e "${GREEN}✓ $1${NC}"; }
err()  { echo -e "${RED}✗ $1${NC}"; }
warn() { echo -e "${YELLOW}⚠ $1${NC}"; }
info() { echo -e "${BLUE}ℹ $1${NC}"; }

CONF_DIR="/etc/ps-gateway"
SETTINGS="${CONF_DIR}/settings.env"
SERVICE="ps-gateway"
SHARE_DIR="/usr/local/share/ps-gateway"
XRAY_BIN="/usr/local/bin/ps-xray"

[ "$(id -u)" -eq 0 ] || { err "با sudo اجرا کنید: sudo ps-manage"; exit 1; }
[ -f "$SETTINGS" ]   || { err "دروازه نصب نشده. ابتدا install-ps-gateway.sh را اجرا کنید."; exit 1; }

reload_settings() { set -a; . "$SETTINGS"; set +a; }
reload_settings

pause() { echo; read -rp "برای بازگشت Enter بزنید..." _; }

restart_service() {
    systemctl restart "$SERVICE"
    sleep 2
    if systemctl is-active --quiet "$SERVICE" 2>/dev/null; then
        ok "سرویس با موفقیت راه‌اندازی شد"
    else
        err "سرویس بالا نیامد:"
        journalctl -u "$SERVICE" -n 20 --no-pager
    fi
}

# ------------------------------------------------------------------
show_console_settings() {
    reload_settings
    clear
    echo "=========================================="
    echo -e "${BOLD}  تنظیماتی که باید روی کنسول بزنید${NC}"
    echo "=========================================="
    echo
    echo "مسیر روی کنسول:"
    echo "  Settings → Network → Set Up Internet Connection"
    echo "  → Use a LAN Cable (یا Use Wi-Fi) → Custom"
    echo

    if [ "${MODE:-proxy}" = "gateway" ]; then
        echo -e "${BOLD}حالت فعلی: دروازه شفاف${NC}"
        echo
        echo "  IP Address Settings ..... Manual"
        echo "      IP Address .......... ${CONSOLE_IP%%,*}"
        echo "      Subnet Mask ......... 255.255.255.0"
        echo "      Default Gateway ..... ${LAN_IP}"
        echo "      Primary DNS ......... ${LAN_IP}"
        echo "      Secondary DNS ....... 1.1.1.1"
        echo "  MTU Settings ............ Automatic"
        echo "  Proxy Server ............ Do Not Use"
    else
        echo -e "${BOLD}حالت فعلی: پروکسی${NC}"
        echo
        echo "  IP Address Settings ..... Automatic"
        echo "  DHCP Host Name .......... Do Not Specify"
        echo "  DNS Settings ............ Manual"
        echo "      Primary DNS ......... ${LAN_IP}"
        echo "      Secondary DNS ....... 1.1.1.1"
        echo "  MTU Settings ............ Automatic"
        echo "  Proxy Server ............ Use"
        echo "      Address ............. ${LAN_IP}"
        echo "      Port ................ ${HTTP_PORT}"
    fi
    echo
    echo "=========================================="
    info "بعد از ذخیره، روی کنسول Test Internet Connection بزنید."
    warn "اگر Test فقط PSN را Failed نشان داد ولی اینترنت OK بود، عیبی ندارد."
    warn "بعضی مدل‌ها در تست پروکسی را نادیده می‌گیرند. مهم باز شدن Store است."
    pause
}

# ------------------------------------------------------------------
switch_mode() {
    reload_settings
    clear
    echo "=========================================="
    echo -e "${BOLD}  انتخاب حالت کاری${NC}"
    echo "=========================================="
    echo
    echo "  1) پروکسی  (پیشنهادی)"
    echo "     فقط فروشگاه، لاگین و دانلود از تونل رد می‌شود."
    echo "     ترافیک خود بازی مستقیم می‌ماند، NAT Type و پینگ سالم می‌ماند."
    echo "     نیازی به تغییر IP کنسول نیست."
    echo
    echo "  2) دروازه شفاف"
    echo "     همه ترافیک وب کنسول از این دستگاه رد می‌شود و بر اساس"
    echo "     دامنه تقسیم می‌شود. برای بازی‌هایی که سرورشان ایران را بسته."
    echo "     UDP بازی‌ها همچنان مستقیم است تا NAT Type خراب نشود."
    echo "     این دستگاه باید همیشه روشن بماند."
    echo
    echo "  3) خاموش کردن مسیریابی شفاف"
    echo
    echo "  0) بازگشت"
    echo
    read -rp "انتخاب: " m
    case "$m" in
        1) ps-mode proxy ;;
        2)
            reload_settings
            if [ -z "${CONSOLE_IP:-}" ]; then
                warn "برای حالت دروازه باید آی‌پی کنسول ثبت شود."
                read -rp "آی‌پی کنسول: " cip
                [ -n "$cip" ] && ps-mode console "$cip"
                reload_settings
            fi
            ps-mode gateway
            ;;
        3) ps-mode off ;;
        0) return ;;
        *) err "گزینه نامعتبر" ;;
    esac
    pause
}

# ------------------------------------------------------------------
manage_domains() {
    while true; do
        clear
        echo "=========================================="
        echo -e "${BOLD}  مدیریت دامنه‌ها${NC}"
        echo "=========================================="
        echo
        local n_psn n_games n_custom
        n_psn="$(grep -cvE '^[[:space:]]*(#|$)' "${CONF_DIR}/domains/psn.list" 2>/dev/null || echo 0)"
        n_games="$(grep -cvE '^[[:space:]]*(#|$)' "${CONF_DIR}/domains/games.list" 2>/dev/null || echo 0)"
        n_custom="$(grep -cvE '^[[:space:]]*(#|$)' "${CONF_DIR}/domains/custom.list" 2>/dev/null || echo 0)"
        reload_settings
        echo "  دامنه‌های Sony ......... ${n_psn}"
        echo "  دامنه‌های ناشران بازی .. ${n_games}  (حالت: ${GAMES_MODE:-on})"
        echo "  دامنه‌های دلخواه شما ... ${n_custom}"
        echo
        echo "  1) افزودن دامنه دلخواه"
        echo "  2) نمایش دامنه‌های دلخواه"
        echo "  3) روشن/خاموش کردن دامنه‌های ناشران بازی"
        echo "  4) ویرایش دستی فایل دامنه‌های دلخواه"
        echo "  0) بازگشت"
        echo
        read -rp "انتخاب: " c
        case "$c" in
            1)
                read -rp "دامنه (مثلا ea.com): " d
                d="$(echo "$d" | tr -d ' \t\r' | sed 's|^https\?://||; s|/.*$||')"
                if [ -z "$d" ]; then
                    err "خالی بود"
                elif grep -qxF "$d" "${CONF_DIR}/domains/custom.list" 2>/dev/null; then
                    warn "از قبل وجود دارد"
                else
                    echo "$d" >> "${CONF_DIR}/domains/custom.list"
                    ok "اضافه شد: $d"
                    ps-genconfig && restart_service
                fi
                pause
                ;;
            2)
                echo
                grep -vE '^[[:space:]]*(#|$)' "${CONF_DIR}/domains/custom.list" 2>/dev/null || echo "(خالی)"
                pause
                ;;
            3)
                reload_settings
                if [ "${GAMES_MODE:-on}" = "on" ]; then
                    ps-mode games off
                else
                    ps-mode games on
                fi
                ps-genconfig && restart_service
                pause
                ;;
            4)
                "${EDITOR:-nano}" "${CONF_DIR}/domains/custom.list"
                ps-genconfig && restart_service
                pause
                ;;
            0) return ;;
            *) err "گزینه نامعتبر"; pause ;;
        esac
    done
}

# ------------------------------------------------------------------
change_server() {
    clear
    echo "=========================================="
    echo -e "${BOLD}  تغییر سرور خارج${NC}"
    echo "=========================================="
    echo
    info "لینک vless:// سرور جدید را بچسبانید:"
    read -rp "> " link
    [ -z "$link" ] && { warn "لغو شد"; pause; return; }
    if [[ "$link" != vless://* ]]; then
        err "لینک باید با vless:// شروع شود"; pause; return
    fi

    local body query userinfo rest hostport
    body="${link#vless://}"; body="${body%%#*}"
    userinfo="${body%%@*}"; rest="${body#*@}"
    hostport="${rest%%\?*}"; query=""
    [[ "$rest" == *\?* ]] && query="${rest#*\?}"

    local host port uuid sni pbk sid flow fp
    uuid="$userinfo"; host="${hostport%%:*}"; port="${hostport##*:}"
    [ "$port" = "$host" ] && port=443
    sni=""; pbk=""; sid=""; flow="xtls-rprx-vision"; fp="chrome"
    local IFS='&' kv
    for kv in $query; do
        local k="${kv%%=*}" v="${kv#*=}"
        v="$(printf '%b' "${v//%/\\x}")"
        case "$k" in
            sni) sni="$v" ;; pbk) pbk="$v" ;; sid) sid="$v" ;;
            flow) flow="$v" ;; fp) fp="$v" ;;
        esac
    done
    unset IFS

    if [ -z "$host" ] || [ -z "$uuid" ] || [ -z "$pbk" ] || [ -z "$sni" ]; then
        err "لینک ناقص است"; pause; return
    fi

    cp "${CONF_DIR}/server.env" "${CONF_DIR}/server.env.bak"
    cat > "${CONF_DIR}/server.env" <<EOF
SRV_HOST=${host}
SRV_PORT=${port}
SRV_UUID=${uuid}
SRV_PBK=${pbk}
SRV_SID=${sid}
SRV_SNI=${sni}
SRV_FLOW=${flow}
SRV_FP=${fp}
EOF
    chmod 600 "${CONF_DIR}/server.env"

    if ps-genconfig; then
        restart_service
        ok "سرور تغییر کرد: ${host}:${port}"
    else
        err "کانفیگ ساخته نشد. سرور قبلی برگردانده شد."
        mv "${CONF_DIR}/server.env.bak" "${CONF_DIR}/server.env"
        ps-genconfig >/dev/null 2>&1
    fi
    pause
}

# ------------------------------------------------------------------
update_core() {
    clear
    info "در حال به‌روزرسانی هسته Xray..."
    local asset tmp
    case "$(uname -m)" in
        x86_64|amd64)  asset="Xray-linux-64.zip" ;;
        aarch64|arm64) asset="Xray-linux-arm64-v8a.zip" ;;
        armv7l|armv7)  asset="Xray-linux-arm32-v7a.zip" ;;
        i386|i686)     asset="Xray-linux-32.zip" ;;
        *) err "معماری پشتیبانی نمی‌شود"; pause; return ;;
    esac
    tmp="$(mktemp -d)"
    if curl -fsSL -o "${tmp}/x.zip" \
        "https://github.com/XTLS/Xray-core/releases/latest/download/${asset}"; then
        unzip -qo "${tmp}/x.zip" -d "${tmp}/x"
        install -m 755 "${tmp}/x/xray" "$XRAY_BIN"
        mkdir -p "$SHARE_DIR"
        install -m 644 "${tmp}/x/geoip.dat"   "${SHARE_DIR}/geoip.dat"
        install -m 644 "${tmp}/x/geosite.dat" "${SHARE_DIR}/geosite.dat"
        ok "به‌روزرسانی شد: $("$XRAY_BIN" version | head -1)"
        restart_service
    else
        err "دانلود ناموفق بود"
    fi
    rm -rf "$tmp"
    pause
}

# ------------------------------------------------------------------
uninstall_all() {
    clear
    warn "این کار دروازه را کامل حذف می‌کند."
    read -rp "برای تایید عبارت DELETE را تایپ کنید: " confirm
    [ "$confirm" = "DELETE" ] || { info "لغو شد"; pause; return; }

    ps-mode off >/dev/null 2>&1
    systemctl disable --now "$SERVICE" >/dev/null 2>&1
    systemctl disable --now ps-gateway-rules >/dev/null 2>&1
    rm -f "/etc/systemd/system/${SERVICE}.service" /etc/systemd/system/ps-gateway-rules.service
    rm -f /etc/sysctl.d/99-ps-gateway.conf
    rm -f /etc/systemd/resolved.conf.d/ps-gateway.conf
    systemctl daemon-reload
    systemctl restart systemd-resolved 2>/dev/null || true
    rm -rf "$CONF_DIR" "$SHARE_DIR"
    rm -f "$XRAY_BIN" /usr/local/bin/ps-mode /usr/local/bin/ps-check /usr/local/bin/ps-genconfig
    ok "حذف شد. فایل ps-manage را هم می‌توانید پاک کنید."
    exit 0
}

# ------------------------------------------------------------------
main_menu() {
    while true; do
        reload_settings
        clear
        echo "=========================================="
        echo -e "${BOLD}  دروازه پلی‌استیشن${NC}"
        echo "=========================================="
        if systemctl is-active --quiet "$SERVICE" 2>/dev/null; then
            echo -e "  سرویس: ${GREEN}فعال${NC}    حالت: ${BOLD}${MODE:-proxy}${NC}"
        else
            echo -e "  سرویس: ${RED}متوقف${NC}   حالت: ${BOLD}${MODE:-proxy}${NC}"
        fi
        echo -e "  آدرس این دستگاه: ${BOLD}${LAN_IP}${NC}   پروکسی: ${BOLD}${HTTP_PORT}${NC}"
        echo "=========================================="
        echo
        echo "  1) تنظیماتی که باید روی کنسول بزنم"
        echo "  2) تست و عیب‌یابی"
        echo "  3) تغییر حالت کاری (پروکسی / دروازه)"
        echo "  4) ثبت آی‌پی کنسول"
        echo "  5) مدیریت دامنه‌ها"
        echo "  6) تغییر سرور خارج"
        echo "  7) وضعیت کامل"
        echo "  8) مشاهده لاگ زنده"
        echo "  9) راه‌اندازی مجدد سرویس"
        echo " 10) به‌روزرسانی هسته Xray"
        echo " 11) حذف کامل"
        echo "  0) خروج"
        echo
        read -rp "انتخاب: " choice
        case "$choice" in
            1) show_console_settings ;;
            2) clear; ps-check; pause ;;
            3) switch_mode ;;
            4) read -rp "آی‌پی کنسول: " cip; [ -n "$cip" ] && ps-mode console "$cip"; pause ;;
            5) manage_domains ;;
            6) change_server ;;
            7) clear; ps-mode status; pause ;;
            8) clear; info "برای خروج Ctrl+C بزنید"; echo; journalctl -u "$SERVICE" -f ;;
            9) clear; restart_service; pause ;;
            10) update_core ;;
            11) uninstall_all ;;
            0) exit 0 ;;
            *) err "گزینه نامعتبر"; sleep 1 ;;
        esac
    done
}

main_menu
